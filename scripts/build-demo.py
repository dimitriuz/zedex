#!/usr/bin/env python3
"""Assembles demo/zedex.asm into demo/zedex.tap.

The demo is a couple of kilobytes of Z80 with no dependencies, and every Z80
assembler is a package this tree would otherwise need, on every machine that
builds it, for one file.  So the assembler is here: two passes over the
source, a table per instruction group, and enough of the instruction set for
the demo and nothing more.  It is not a general assembler and does not try to
be one — an instruction it has never seen is an error, not silence.

    ./scripts/build-demo.py             # demo/zedex.asm -> demo/zedex.tap
    ./scripts/build-demo.py --list      # ...and what each line assembled to
    ./scripts/build-demo.py --logo [font.ttf]    # redraw demo/logo.inc

The wordmark is a picture, and `--logo` is the only thing here that needs
Pillow and a font on the machine: it writes `demo/logo.inc`, which is
committed, so an ordinary build renders nothing and cannot drift with the
system's fonts.
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEMO = os.path.join(HERE, "demo")


# --- the assembler ---------------------------------------------------------

R8 = {"b": 0, "c": 1, "d": 2, "e": 3, "h": 4, "l": 5, "(hl)": 6, "a": 7}
RP = {"bc": 0, "de": 1, "hl": 2, "sp": 3}
PP = {"bc": 0, "de": 1, "hl": 2, "af": 3}
CC = {"nz": 0, "z": 1, "nc": 2, "c": 3, "po": 4, "pe": 5, "p": 6, "m": 7}
CC_JR = {"nz": 0, "z": 1, "nc": 2, "c": 3}

# add/adc/sub/sbc/and/xor/or/cp: the register form is 0x80 + group * 8 + reg,
# the immediate form 0xc6 + group * 8.
ALU = {"add": 0, "adc": 1, "sub": 2, "sbc": 3, "and": 4, "xor": 5, "or": 6, "cp": 7}

# The CB shift group, in opcode order.
ROT = {"rlc": 0, "rrc": 1, "rl": 2, "rr": 3, "sla": 4, "sra": 5, "sll": 6, "srl": 7}

BIT_OPS = {"bit": 0x40, "res": 0x80, "set": 0xc0}

NO_OPERAND = {
    "nop": [0x00], "halt": [0x76], "di": [0xf3], "ei": [0xfb], "exx": [0xd9],
    "ret": [0xc9], "scf": [0x37], "ccf": [0x3f], "cpl": [0x2f], "daa": [0x27],
    "rlca": [0x07], "rrca": [0x0f], "rla": [0x17], "rra": [0x1f],
    "ldi": [0xed, 0xa0], "ldd": [0xed, 0xa8], "ldir": [0xed, 0xb0],
    "lddr": [0xed, 0xb8], "cpi": [0xed, 0xa1], "cpir": [0xed, 0xb1],
    "neg": [0xed, 0x44], "reti": [0xed, 0x4d], "retn": [0xed, 0x45],
    "rld": [0xed, 0x6f], "rrd": [0xed, 0x67],
}


class Error(Exception):
    pass


class Assembler:

    def __init__(self):
        self.labels = {}
        self.origin = None
        self.image = bytearray()
        self.listing = []
        self.pc = 0
        self.pass_two = False
        self.source_dir = "."

    # --- source ---

    def load(self, path):
        """Reads a source file, splicing in whatever it includes."""
        lines = []
        with open(path) as source:
            for number, text in enumerate(source, 1):
                include = re.match(r'\s*include\s+"([^"]+)"\s*$', text, re.I)
                if include:
                    lines += self.load(os.path.join(os.path.dirname(path),
                                                    include.group(1)))
                else:
                    lines.append(("%s:%d" % (os.path.basename(path), number),
                                  text.rstrip("\n")))
        return lines

    def assemble(self, path):
        self.source_dir = os.path.dirname(os.path.abspath(path))
        lines = self.load(path)
        for self.pass_two in (False, True):
            self.pc = 0
            self.image = bytearray()
            self.listing = []
            for where, text in lines:
                try:
                    self.line(text)
                except Error as failure:
                    raise Error("%s: %s\n    %s" % (where, failure, text.strip()))
        return bytes(self.image)

    def line(self, text):
        body = strip_comment(text)
        if not body.strip():
            return

        # A label is a name in the first column, with or without its colon;
        # anything indented is an instruction.
        label = None
        if body[0] not in " \t":
            named = re.match(r"([A-Za-z_]\w*):?", body)
            if not named:
                raise Error("this is neither a label nor an instruction")
            label = named.group(1).lower()
            body = body[named.end():]
        body = body.strip()

        # An `equ` names the value it is given; every other label names here.
        parts = body.split(None, 1)
        mnemonic = parts[0].lower() if parts else ""
        operands = parts[1] if len(parts) > 1 else ""

        if mnemonic == "equ":
            if not label:
                raise Error("equ with nothing to name")
            self.define(label, self.value(operands))
            return

        if label:
            self.define(label, self.pc)
        if not mnemonic:
            return

        at = self.pc
        code = self.directive(mnemonic, operands)
        if code is None:
            code = self.instruction(mnemonic, split_operands(operands))
        self.image += bytes(code)
        self.pc += len(code)
        if self.pass_two:
            self.listing.append((at, bytes(code), body))

    def define(self, name, value):
        if not self.pass_two:
            if name in self.labels:
                raise Error("%s is defined twice" % name)
            self.labels[name] = value
        elif self.labels.get(name) != value:
            raise Error("%s moved between passes" % name)

    # --- directives ---

    def directive(self, mnemonic, operands):
        if mnemonic == "org":
            where = self.value(operands)
            if self.origin is None:
                self.origin = where
            if where < self.origin + len(self.image):
                raise Error("org goes backwards")
            self.image += bytes(where - self.origin - len(self.image))
            self.pc = where
            return []

        if mnemonic in ("db", "defb", "dw", "defw"):
            wide = mnemonic in ("dw", "defw")
            out = []
            for item in split_operands(operands):
                if item[0] in "\"'" and len(item) > 1 and item[0] == item[-1]:
                    if wide:
                        raise Error("a string is bytes, not words")
                    out += [ord(c) for c in item[1:-1]]
                elif wide:
                    out += word(self.value(item))
                else:
                    out.append(byte(self.value(item)))
            return out

        if mnemonic in ("ds", "defs"):
            items = split_operands(operands)
            count = self.value(items[0])
            fill = byte(self.value(items[1])) if len(items) > 1 else 0
            return [fill] * count

        if mnemonic == "incbin":
            name = operands.strip().strip('"')
            with open(os.path.join(self.source_dir, name), "rb") as blob:
                return list(blob.read())

        return None

    # --- expressions ---

    def value(self, text):
        expr = text.strip()
        if not expr:
            raise Error("an expression was expected")

        expr = re.sub(r"'(.)'", lambda m: str(ord(m.group(1))), expr)
        expr = re.sub(r"\$([0-9a-fA-F]+)", r"0x\1", expr)
        expr = re.sub(r"%([01]+)", r"0b\1", expr)
        expr = expr.replace("$", str(self.pc))            # $ on its own is here
        expr = re.sub(r"(?<![\w])/(?![/])", "//", expr)   # integer division

        def label(match):
            name = match.group(0).lower()
            if name in self.labels:
                return str(self.labels[name])
            if self.pass_two:
                raise Error("%s is not defined" % name)
            return "0"

        expr = re.sub(r"(?<![\w0-9])[A-Za-z_]\w*", label, expr)
        try:
            return int(eval(expr, {"__builtins__": {}}, {}))
        except Error:
            raise
        except Exception:
            raise Error("cannot work out %r" % text.strip())

    # --- instructions ---

    def instruction(self, op, args):
        # IX and IY are HL with a prefix in front, so they are assembled as HL
        # and the prefix — and the displacement — put back afterwards.
        prefix, args, displacement = index_registers(args)
        code = self.plain(op, args)
        if prefix is None:
            return code
        if displacement is None:
            return [prefix] + code
        step = self.value(displacement)
        if not -128 <= step <= 127:
            raise Error("a displacement is one signed byte, not %d" % step)
        step &= 0xff
        if code[0] == 0xcb:
            # DD CB d op: the displacement comes before the operation, not after
            return [prefix, 0xcb, step] + code[1:]
        return [prefix, code[0], step] + code[1:]

    def plain(self, op, args):
        if op == "ex" and len(args) == 2:
            pair = (args[0], args[1].rstrip("'"))
            if pair == ("de", "hl"):
                return [0xeb]
            if pair == ("af", "af"):
                return [0x08]
            if pair == ("(sp)", "hl"):
                return [0xe3]
            raise Error("no such ex")

        args = [a.lower() for a in args]

        if op in NO_OPERAND and not args:
            return NO_OPERAND[op]

        handler = getattr(self, "op_" + op, None)
        if handler is None:
            raise Error("unknown instruction %r" % op)
        return handler(args)

    def op_ld(self, args):
        dest, source = expect(args, 2)

        if dest in R8 and source in R8:
            if dest == "(hl)" and source == "(hl)":
                raise Error("that is halt, not ld")
            return [0x40 + R8[dest] * 8 + R8[source]]

        if dest == "a" and source in ("(bc)", "(de)"):
            return [0x0a if source == "(bc)" else 0x1a]
        if source == "a" and dest in ("(bc)", "(de)"):
            return [0x02 if dest == "(bc)" else 0x12]
        if dest == "a" and source == "i":
            return [0xed, 0x57]
        if dest == "i" and source == "a":
            return [0xed, 0x47]
        if dest == "sp" and source == "hl":
            return [0xf9]

        if indirect(source):
            where = self.value(source[1:-1])
            if dest == "a":
                return [0x3a] + word(where)
            if dest == "hl":
                return [0x2a] + word(where)
            if dest in RP:
                return [0xed, 0x4b + RP[dest] * 16] + word(where)
            raise Error("nothing loads %s from memory" % dest)

        if indirect(dest):
            where = self.value(dest[1:-1])
            if source == "a":
                return [0x32] + word(where)
            if source == "hl":
                return [0x22] + word(where)
            if source in RP:
                return [0xed, 0x43 + RP[source] * 16] + word(where)
            raise Error("nothing stores %s to memory" % source)

        if dest in R8:
            return [0x06 + R8[dest] * 8, byte(self.value(source))]
        if dest in RP:
            return [0x01 + RP[dest] * 16] + word(self.value(source))
        raise Error("no such ld")

    def alu(self, op, args):
        group = ALU[op]

        if op in ("add", "adc", "sbc") and len(args) == 2 and args[0] == "hl":
            if args[1] not in RP:
                raise Error("hl adds a register pair")
            if op == "add":
                return [0x09 + RP[args[1]] * 16]
            return [0xed, (0x4a if op == "adc" else 0x42) + RP[args[1]] * 16]

        if len(args) == 2:
            if args[0] != "a":
                raise Error("%s works on a" % op)
            args = args[1:]
        elif op in ("add", "adc", "sbc"):
            raise Error("%s wants both operands" % op)

        operand = expect(args, 1)[0]
        if operand in R8:
            return [0x80 + group * 8 + R8[operand]]
        return [0xc6 + group * 8, byte(self.value(operand))]

    def op_inc(self, args):
        return self.step(args, 0x04, 0x03)

    def op_dec(self, args):
        return self.step(args, 0x05, 0x0b)

    def step(self, args, eight, sixteen):
        target = expect(args, 1)[0]
        if target in R8:
            return [eight + R8[target] * 8]
        if target in RP:
            return [sixteen + RP[target] * 16]
        raise Error("nothing to step")

    def op_push(self, args):
        return [0xc5 + PP[self.pair(args)] * 16]

    def op_pop(self, args):
        return [0xc1 + PP[self.pair(args)] * 16]

    def pair(self, args):
        name = expect(args, 1)[0]
        if name not in PP:
            raise Error("%s is not a pair the stack takes" % name)
        return name

    def op_jp(self, args):
        if len(args) == 1:
            if args[0] == "(hl)":
                return [0xe9]
            return [0xc3] + word(self.value(args[0]))
        condition, target = expect(args, 2)
        if condition not in CC:
            raise Error("%s is not a condition" % condition)
        return [0xc2 + CC[condition] * 8] + word(self.value(target))

    def op_call(self, args):
        if len(args) == 1:
            return [0xcd] + word(self.value(args[0]))
        condition, target = expect(args, 2)
        if condition not in CC:
            raise Error("%s is not a condition" % condition)
        return [0xc4 + CC[condition] * 8] + word(self.value(target))

    def op_ret(self, args):
        condition = expect(args, 1)[0]
        if condition not in CC:
            raise Error("%s is not a condition" % condition)
        return [0xc0 + CC[condition] * 8]

    def op_jr(self, args):
        if len(args) == 1:
            return [0x18, self.hop(args[0])]
        condition, target = expect(args, 2)
        if condition not in CC_JR:
            raise Error("jr cannot test %s" % condition)
        return [0x20 + CC_JR[condition] * 8, self.hop(target)]

    def op_djnz(self, args):
        return [0x10, self.hop(expect(args, 1)[0])]

    def hop(self, target):
        # A label further down the source is still 0 on the first pass, so the
        # distance only means anything once every label has an address.
        step = self.value(target) - (self.pc + 2)
        if self.pass_two and not -128 <= step <= 127:
            raise Error("that jump is %d away, too far to be relative" % step)
        return step & 0xff

    def op_rst(self, args):
        where = self.value(expect(args, 1)[0])
        if where % 8 or not 0 <= where <= 0x38:
            raise Error("rst goes to a multiple of eight, up to $38")
        return [0xc7 + where]

    def op_im(self, args):
        mode = self.value(expect(args, 1)[0])
        return [0xed, {0: 0x46, 1: 0x56, 2: 0x5e}[mode]]

    def rotate(self, op, args):
        target = expect(args, 1)[0]
        if target not in R8:
            raise Error("%s works on a register or (hl)" % op)
        return [0xcb, ROT[op] * 8 + R8[target]]

    def bitwise(self, op, args):
        which, target = expect(args, 2)
        number = self.value(which)
        if not 0 <= number <= 7:
            raise Error("there are eight bits")
        if target not in R8:
            raise Error("%s works on a register or (hl)" % op)
        return [0xcb, BIT_OPS[op] + number * 8 + R8[target]]

    def op_in(self, args):
        where, port = expect(args, 2)
        if port == "(c)":
            if where not in R8 or where == "(hl)":
                raise Error("in reads into a register")
            return [0xed, 0x40 + R8[where] * 8]
        if where != "a" or not indirect(port):
            raise Error("no such in")
        return [0xdb, byte(self.value(port[1:-1]))]

    def op_out(self, args):
        port, what = expect(args, 2)
        if port == "(c)":
            if what not in R8 or what == "(hl)":
                raise Error("out writes a register")
            return [0xed, 0x41 + R8[what] * 8]
        if what != "a" or not indirect(port):
            raise Error("no such out")
        return [0xd3, byte(self.value(port[1:-1]))]


# The three groups whose members differ only by a number in the opcode get one
# method each and a handler per mnemonic, rather than a table of near copies.
def _group(method, mnemonics):
    for mnemonic in mnemonics:
        def handler(self, args, _mnemonic=mnemonic, _method=method):
            return getattr(self, _method)(_mnemonic, args)
        setattr(Assembler, "op_" + mnemonic, handler)


_group("alu", ALU)
_group("rotate", ROT)
_group("bitwise", BIT_OPS)


INDEX = {"ix": 0xdd, "iy": 0xfd}


def index_registers(args):
    """Turns IX and IY operands into the HL ones they are encoded as.

    Returns the prefix byte, the rewritten operands, and the displacement
    expression if there was one — `(ix)` counts as `(ix+0)`, which is what
    every assembler means by it.
    """
    prefix = None
    displacement = None
    out = []

    for operand in args:
        found = re.match(r"^\((ix|iy)([+-][^)]+)?\)$", operand) \
            or re.match(r"^(ix|iy)$", operand)
        if not found:
            out.append(operand)
            continue

        register = found.group(1)
        if prefix is not None and prefix != INDEX[register]:
            raise Error("one instruction cannot reach both ix and iy")
        prefix = INDEX[register]

        if operand.startswith("("):
            displacement = (found.group(2) or "0").lstrip("+")
            out.append("(hl)")
        else:
            out.append("hl")

    return prefix, out, displacement


def expect(args, count):
    if len(args) != count:
        raise Error("expected %d operands, got %d" % (count, len(args)))
    return args


def indirect(operand):
    return (operand.startswith("(") and operand.endswith(")")
            and operand not in ("(hl)", "(bc)", "(de)", "(c)", "(sp)"))


def byte(value):
    if not -128 <= value <= 255:
        raise Error("%d does not fit in a byte" % value)
    return value & 0xff


def word(value):
    if not -32768 <= value <= 65535:
        raise Error("%d does not fit in a word" % value)
    return [value & 0xff, (value >> 8) & 0xff]


def strip_comment(text):
    quote = None
    for at, c in enumerate(text):
        if quote:
            if c == quote:
                quote = None
        elif c in "\"'":
            quote = c
        elif c == ";":
            return text[:at]
    return text


def split_operands(text):
    """Splits on commas, leaving anything quoted alone."""
    items, item, quote = [], "", None
    for c in text:
        if quote:
            item += c
            if c == quote:
                quote = None
        elif c in "\"'":
            quote = c
            item += c
        elif c == ",":
            items.append(item.strip())
            item = ""
        elif c in " \t":
            continue
        else:
            item += c
    if item.strip():
        items.append(item.strip())
    return [i for i in items if i]


# --- the tape --------------------------------------------------------------

TOKENS = [
    "RND", "INKEY$", "PI", "FN", "POINT", "SCREEN$", "ATTR", "AT", "TAB",
    "VAL$", "CODE", "VAL", "LEN", "SIN", "COS", "TAN", "ASN", "ACS", "ATN",
    "LN", "EXP", "INT", "SQR", "SGN", "ABS", "PEEK", "IN", "USR", "STR$",
    "CHR$", "NOT", "BIN", "OR", "AND", "<=", ">=", "<>", "LINE", "THEN", "TO",
    "STEP", "DEF FN", "CAT", "FORMAT", "MOVE", "ERASE", "OPEN #", "CLOSE #",
    "MERGE", "VERIFY", "BEEP", "CIRCLE", "INK", "PAPER", "FLASH", "BRIGHT",
    "INVERSE", "OVER", "OUT", "LPRINT", "LLIST", "STOP", "READ", "DATA",
    "RESTORE", "NEW", "BORDER", "CONTINUE", "DIM", "REM", "FOR", "GO TO",
    "GO SUB", "INPUT", "LOAD", "LIST", "LET", "PAUSE", "NEXT", "POKE",
    "PRINT", "PLOT", "RUN", "SAVE", "RANDOMIZE", "IF", "CLS", "DRAW",
    "CLEAR", "RETURN", "COPY",
]


def tokenise(text):
    """Turns a line of BASIC into what the Spectrum stores.

    A keyword is one byte, and every number is kept twice: as the digits that
    list, and again as the five byte form the interpreter is the one to read.
    """
    out = bytearray()
    at = 0
    while at < len(text):
        for keyword in sorted(TOKENS, key=len, reverse=True):
            if not text.upper().startswith(keyword, at):
                continue
            if keyword[0].isalpha():
                before = text[at - 1] if at else " "
                after = text[at + len(keyword):at + len(keyword) + 1] or " "
                if before.isalnum() or (keyword[-1].isalpha() and after.isalnum()):
                    continue
            out.append(0xa5 + TOKENS.index(keyword))
            at += len(keyword)
            break
        else:
            c = text[at]
            if c.isdigit() and not (at and text[at - 1].isalpha()):
                digits = re.match(r"\d+", text[at:]).group(0)
                out += digits.encode("ascii")
                out += bytes([0x0e, 0x00, 0x00, int(digits) & 0xff,
                              int(digits) >> 8, 0x00])
                at += len(digits)
            else:
                out.append(ord(c))
                at += 1
    return bytes(out)


def basic(lines):
    out = bytearray()
    for number, text in lines:
        body = tokenise(text) + b"\r"
        out += bytes([number >> 8, number & 0xff])       # big endian, alone in this
        out += bytes([len(body) & 0xff, len(body) >> 8])
        out += body
    return bytes(out)


def block(flag, payload):
    checksum = flag
    for b in payload:
        checksum ^= b
    body = bytes([flag]) + bytes(payload) + bytes([checksum])
    return bytes([len(body) & 0xff, len(body) >> 8]) + body


def header(kind, name, length, first, second):
    return bytes([kind]) + name.ljust(10)[:10].encode("ascii") \
        + bytes(word(length) + word(first) + word(second))


def tap(name, program, autostart, code, origin):
    return (block(0x00, header(0, name, len(program), autostart, len(program)))
            + block(0xff, program)
            + block(0x00, header(3, name, len(code), origin, 32768))
            + block(0xff, code))


# --- the tune's lookup tables ----------------------------------------------

# A PT3 player needs two tables that are not in the module: which AY period a
# note means, and how a sample's amplitude and a line's volume combine.  Both
# are properties of the tracker rather than of the music, both are worked out
# here rather than carried, and neither is guesswork — the frequency tables are
# grown from twelve base periods by halving an octave at a time, and the volume
# table comes from Ivan Roshin's VolTableCreator, which is the routine the
# tracker's own player runs at startup.
#
# The arithmetic follows Vince Weaver's pt3_lib, which is in turn checked
# against Bulba's ay_emul: http://www.deater.net/weave/vmwprod/pt3_lib/

# Table 1, "ST", one octave of periods; the rest is this halved, twice adjusted.
ST_BASE = [0xef8, 0xe10, 0xd60, 0xc80, 0xbd8, 0xb28,
           0xa88, 0x9f0, 0x960, 0x8e0, 0x858, 0x7e0]


def note_table(which, version):
    if which != 1:
        raise Error("only frequency table 1 is built here, and this module "
                    "asks for %d" % which)
    periods = list(ST_BASE)
    for note in range(84):
        periods.append(periods[note] >> 1)
    periods[23] += 13                    # the tracker's own two corrections
    periods[46] -= 1
    return periods


def volume_table(version):
    """The sixteen volume curves, one per line volume."""
    which = 1 if version <= 4 else 0
    table = [[0] * 16 for _ in range(16)]
    de = which << 4

    for level in range(1, 16):
        hl = (0x11 - which) + de
        carry = hl >> 16
        de, hl = hl & 0xffff, de         # the routine swaps them here
        hl = (-carry) & 0xffff
        for amplitude in range(16):
            if not which:
                carry = 1 if hl & 0x80 else 0
            table[level][amplitude] = ((hl >> 8) & 0xff) + carry
            hl = (hl + de) & 0xffff
        if (de & 0xff) == 0x77:
            de += 1

    return table


def write_tables(path, module):
    version = module[13] - ord("0")
    if not 0 <= version <= 9:
        version = 6
    which = module[0x63]

    with open(path, "w") as out:
        out.write("; Written by scripts/build-demo.py; do not hand edit.\n")
        out.write("; ProTracker 3.%d, frequency table %d.\n" % (version, which))

        out.write("\nperiods:\n")
        periods = note_table(which, version)
        for at in range(0, 96, 8):
            out.write("        dw   " + ",".join("%4d" % p
                                                 for p in periods[at:at + 8]) + "\n")

        out.write("\nvolumes:\n")
        for row in volume_table(version):
            out.write("        db   " + ",".join("%2d" % v for v in row) + "\n")

    return version, which


# --- the wordmark ----------------------------------------------------------

LOGO_WIDTH = 22                  # bytes; 176 pixels, centred on a 256 wide screen
LOGO_HEIGHT = 48                 # six character rows
LOGO_FONT = "/usr/share/fonts/noto/NotoSans-ExtraBold.ttf"
LOGO_SIZE = 60


def draw_logo(path, font=LOGO_FONT):
    """Redraws demo/logo.inc: `zedex`, as bits.

    The icon's wordmark is Onest ExtraBold, which no Linux box has by default,
    so the face here is the nearest thing one does have — at 48 pixels tall,
    thresholded to one bit, an extra bold grotesque is an extra bold grotesque.
    Pass a path to `--logo` to use the real thing.
    """
    from PIL import Image, ImageDraw, ImageFont

    face = ImageFont.truetype(font, LOGO_SIZE)
    sheet = Image.new("L", (600, 200), 0)
    ImageDraw.Draw(sheet).text((40, 40), "zedex", font=face, fill=255)
    word_mark = sheet.crop(sheet.getbbox())

    logo = Image.new("L", (LOGO_WIDTH * 8, LOGO_HEIGHT), 0)
    logo.paste(word_mark, ((logo.width - word_mark.width) // 2,
                           (logo.height - word_mark.height) // 2))

    with open(path, "w") as out:
        out.write("; %s at %dpt, thresholded: %d bytes across, %d lines down.\n"
                  % (os.path.basename(font), LOGO_SIZE,
                     LOGO_WIDTH, LOGO_HEIGHT))
        out.write("; Written by scripts/build-demo.py --logo; do not hand edit.\n")
        for y in range(logo.height):
            row = []
            for x in range(0, logo.width, 8):
                bits = 0
                for bit in range(8):
                    if logo.getpixel((x + bit, y)) >= 128:
                        bits |= 0x80 >> bit
                row.append("$%02x" % bits)
            out.write("        db   " + ",".join(row) + "\n")
    print("wrote %s" % path)


# --- ------------------------------------------------------------------------

def main(argv):
    if "--logo" in argv:
        at = argv.index("--logo") + 1
        font = argv[at] if at < len(argv) else LOGO_FONT
        draw_logo(os.path.join(DEMO, "logo.inc"), font)
        return 0

    source = os.path.join(DEMO, "zedex.asm")
    assembler = Assembler()
    try:
        code = assembler.assemble(source)
    except Error as failure:
        print("error: %s" % failure, file=sys.stderr)
        return 1

    if "--list" in argv:
        for at, emitted, text in assembler.listing:
            print("%04x  %-14s %s" % (at, emitted.hex(" "), text))

    loader = [
        (10, 'CLEAR 32767: BORDER 0: PAPER 0: INK 7: CLS'),
        (20, 'LOAD ""CODE'),
        (30, 'RANDOMIZE USR 32768'),
    ]
    program = basic(loader)
    tape = os.path.join(DEMO, "zedex.tap")
    with open(tape, "wb") as out:
        out.write(tap("zedex", program, 10, code, assembler.origin))

    print("%s: %d bytes of code at $%04x, %d bytes of tape"
          % (os.path.relpath(tape, HERE), len(code), assembler.origin,
             os.path.getsize(tape)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
