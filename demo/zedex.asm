; ---------------------------------------------------------------------------
; zedex — the demo the screenshots are taken of.
;
; The app's icon, running on the machine the app emulates: the wordmark, the
; four coloured pills under it, `48K` and `Z80` in the corners, and the line
; from the store page scrolling past.
;
; Built by scripts/build-demo.py into demo/zedex.tap — a BASIC loader that
; CLEARs below this code, LOADs it and jumps here.  That means BASIC has set
; the machine up before we arrive, and two things are borrowed from it: the
; ROM's interrupt, still running, so HALT paces the demo at one pass of the
; main loop per frame; and CHARS, so the text is drawn in whatever font the
; machine has rather than one carried here.
;
; Nothing is timed against the raster — no border stripes, no multicolour —
; which is exactly why four things can move at once and why the demo behaves
; the same on a 48K, a 128 and a +3.  The border stays black, because that is
; what the app puts around the picture anyway.
;
; There is music, on the AY: a ProTracker 3 module, played by pt3.asm and
; stepped once a frame from the same HALT.  The chip is written to
; unconditionally, since a machine without an AY decodes neither of its ports
; and hears nothing — so the demo stays one file, and on a 48K it is simply
; quiet.  A beeper tune would have to be played by the CPU between the effects,
; and there is no frame left for that.
;
; The tune is "Time Up" by shiru8bit, CC-BY 3.0, from
; https://opengameart.org/users/shiru8bit — the credit is in the scroller
; because that is the licence's price and the tape is what travels.
;
; Assembler notes: `equ` names are folded to lower case, `$` is hex, and IY is
; untouched throughout, since the ROM's interrupt needs it where it left it.
; IX belongs to the player, which uses it for whichever channel it is on.
; ---------------------------------------------------------------------------

        org  $8000

screen  equ  $4000
attrs   equ  $5800
chars   equ  23606              ; sysvar: the font, less 256 bytes

; Where everything sits, in character rows.
r_stars equ  2                  ; two rows of sky, y 16..31
r_logo  equ  5                  ; six rows of wordmark, y 40..87
r_pills equ  12                 ; one row of pills, y 96..103
r_text  equ  15                 ; the scroller, y 120..127
r_sky   equ  17                 ; six more rows of sky, y 136..183

logo_col equ 5                  ; 176 pixels of wordmark, centred
logo_w  equ  22
logo_h  equ  48

pill_col equ 5                  ; four pills, four columns each, two apart
pill_w  equ  4
pill_gap equ 2

; The scroller's row, worked out the way the display is wired: the third of
; the screen in the high byte, the row within it in the low one.
text_row equ screen + ((r_text & $18) * 256) + ((r_text & 7) * 32)

; ---------------------------------------------------------------------------

start:
        xor  a
        out  ($fe),a            ; black border, like the app's own letterbox
        call clear

        ld   hl,msg             ; from the top, however the demo was entered
        ld   (msgptr),hl
        ld   a,1
        ld   (subcol),a
        ld   hl,colbits
        ld   bc,8
        xor  a
        call fill

        call pt3init

        ld   hl,lab48           ; the icon's two corner labels
        ld   b,0
        ld   c,1
        call print
        ld   hl,labz80
        ld   b,0
        ld   c,28
        call print

        call drawlogo
        call drawpills
        call paint

loop:   halt                    ; the frame, and the only clock here
        call pt3frame
        call stars
        call scroll
        call wash
        call sweep
        call pills
        call cycle

        ld   a,$7f              ; BREAK is Caps Shift and Space together
        in   a,($fe)
        and  $01
        jr   nz,loop
        ld   a,$fe
        in   a,($fe)
        and  $01
        jr   nz,loop

        call hush               ; and leaves neither a note nor a screen behind
        ld   a,$07
        out  ($fe),a
        call clear
        ld   hl,attrs
        ld   bc,768
        ld   a,$38
        call fill
        ret

; --- the picture, drawn once ------------------------------------------------

; Pixels and attributes both: an attribute of zero is ink black on paper
; black, so everything the demo does not colour stays invisible.
clear:  ld   hl,screen
        ld   bc,6912
        xor  a
        call fill
        ret

; hl = start, bc = count, a = the byte to leave there.
fill:   ld   (hl),a
        ld   d,h
        ld   e,l
        inc  de
        dec  bc
        ldir
        ret

; The wordmark: one row of the picture per scanline, straight to the screen.
drawlogo:
        ld   hl,logodata
        ld   a,r_logo * 8
        ld   b,logo_h
dl1:    push bc
        push af
        push hl
        call scr_line
        ld   a,l
        add  a,logo_col
        ld   e,a
        ld   d,h
        pop  hl
        ld   bc,logo_w
        ldir                    ; hl walks on into the next line of the picture
        pop  af
        inc  a
        pop  bc
        djnz dl1
        ret

; The four pills, all one shape: an end, two middles and the other end, so a
; line of the picture is three bytes however wide the pills are.
drawpills:
        ld   hl,pilldata
        ld   a,r_pills * 8
        ld   b,8
dp1:    push bc
        push af
        push hl
        call scr_line
        ld   a,l
        add  a,pill_col
        ld   e,a
        ld   d,h
        pop  hl
        ld   b,4
dp2:    push hl
        ld   a,(hl)
        ld   (de),a             ; the left end
        inc  de
        inc  hl
        ld   a,(hl)
        ld   (de),a             ; the middle, twice
        inc  de
        ld   (de),a
        inc  de
        inc  hl
        ld   a,(hl)
        ld   (de),a             ; the right end
        inc  de
        inc  de                 ; and over the gap
        inc  de
        pop  hl
        djnz dp2
        inc  hl
        inc  hl
        inc  hl
        pop  af
        inc  a
        pop  bc
        djnz dp1
        ret

; The colours that never change: white sky, and the icon's red in the corners.
paint:  ld   hl,attrs + r_stars * 32
        ld   bc,2 * 32
        ld   a,$07              ; white ink, not bright, so a star is a dot
        call fill
        ld   hl,attrs + r_sky * 32
        ld   bc,6 * 32
        ld   a,$07
        call fill
        ld   hl,attrs
        ld   bc,32
        ld   a,$42              ; bright red
        call fill
        ret

; hl = a string, b = row, c = column.  The font is the machine's own.
print:  ex   de,hl              ; de = the string
        ld   a,b
        add  a,a
        add  a,a
        add  a,a
        call scr_line
        ld   a,l
        add  a,c
        ld   l,a
pr1:    ld   a,(de)
        or   a
        ret  z
        inc  de
        push de
        push hl
        call putc
        pop  hl
        pop  de
        inc  hl
        jr   pr1

; a = a character, hl = where it goes.
putc:   ex   de,hl              ; de = the screen
        ld   l,a
        ld   h,0
        add  hl,hl
        add  hl,hl
        add  hl,hl              ; eight bytes per character
        ld   bc,(chars)
        add  hl,bc
        ld   b,8
pu1:    ld   a,(hl)
        ld   (de),a
        inc  hl
        inc  d                  ; the next scanline is 256 bytes on
        djnz pu1
        ret

; a = a pixel row, 0..191; out: hl = its first byte on screen.
;
; The display is not laid out in rows but in thirds: the high byte carries
; which third and which of the eight lines within a character row, the low
; byte which character row of the third.
scr_line:
        ld   l,a
        and  $c0
        rrca
        rrca
        rrca                    ; the third, in bits 3 and 4
        or   $40
        ld   h,a
        ld   a,l
        and  $07
        or   h
        ld   h,a
        ld   a,l
        and  $38
        rlca
        rlca
        ld   l,a
        ret

; --- the sky ---------------------------------------------------------------

; Stars are single pixels, plotted with XOR: nothing else is drawn in either
; band, so a star can be rubbed out by drawing it again, and the two bands
; never need clearing.  Where two stars meet they cancel for a frame, which
; is what a starfield has always looked like.
stars:  ld   de,startab
        ld   b,nstars
st1:    push bc
        ld   a,(de)             ; the row it lives on, which never changes
        call scr_line
        ld   (rowbase),hl
        inc  de
        ld   a,(de)
        call xorpix             ; out with the old
        inc  de
        ld   a,(de)
        ld   c,a                ; how fast this one goes
        dec  de
        ld   a,(de)
        add  a,c                ; and round the screen it wraps, in a byte
        ld   (de),a
        call xorpix             ; in with the new
        inc  de
        inc  de
        pop  bc
        djnz st1
        ret

; a = x, the row already worked out in rowbase.
xorpix: push de
        ld   hl,(rowbase)
        call pixel
        xor  (hl)
        ld   (hl),a
        pop  de
        ret

; a = x, hl = the row's first byte; out: hl = the byte x is in, a = its bit.
pixel:  ld   b,a
        and  $07
        ld   c,a
        ld   a,$80
        inc  c
pi1:    dec  c
        jr   z,pi2
        rrca
        jr   pi1
pi2:    push af
        ld   a,b
        rrca
        rrca
        rrca
        and  $1f                ; x / 8, whichever byte that is
        add  a,l                ; a row's bytes are consecutive, so no carry
        ld   l,a
        pop  af
        ret

; --- the scroller ----------------------------------------------------------

; One pixel a frame, and the pixel coming in at the right is the top bit of
; whatever is left of the current letter: the eight bytes of it are shifted
; out of colbits a column at a time, and a new letter is fetched every eighth
; frame.  Each of the row's eight scanlines is rotated from its right hand end
; leftwards, so the bit falling out of one byte drops into the next — which
; is why nothing here touches the carry between the rotates.
scroll: ld   hl,colbits
        ld   de,text_row + 31
        ld   c,8
sc1:    sla  (hl)               ; the pixel about to appear at the right
        push hl
        push de
        ex   de,hl
        ld   b,32
sc2:    rl   (hl)
        dec  hl
        djnz sc2
        pop  de
        pop  hl
        inc  d                  ; the next scanline down
        inc  hl
        dec  c
        jr   nz,sc1

        ld   hl,subcol
        dec  (hl)
        ret  nz
        ld   (hl),8
        ld   hl,(msgptr)
        ld   a,(hl)
        or   a
        jr   nz,sc3
        ld   hl,msg             ; round again
        ld   a,(hl)
sc3:    inc  hl
        ld   (msgptr),hl
        ld   l,a
        ld   h,0
        add  hl,hl
        add  hl,hl
        add  hl,hl
        ld   bc,(chars)
        add  hl,bc
        ld   de,colbits
        ld   bc,8
        ldir
        ret

; --- the colour ------------------------------------------------------------

; The wordmark is white, and a band of the icon's colours passes up through
; it: every frame the six rows of it take six entries of the table, one entry
; further along than before.
wash:   ld   a,(phase)
        ld   e,a
        ld   d,0
        ld   hl,rainbow
        add  hl,de
        ld   de,attrs + r_logo * 32
        ld   c,6
wa1:    ld   a,(hl)
        inc  hl
        ld   b,32
wa2:    ld   (de),a
        inc  de
        djnz wa2
        dec  c
        jr   nz,wa1
        ret

; The scroller gets its own table and the other direction, so a single band
; of colour crosses the words rather than travelling with them.  A cell is
; eight pixels and half a letter, so anything busier than one band reads as
; confetti and the line stops being legible — which is the one thing it is
; there to be.
sweep:  ld   a,(phase2)
        ld   e,a
        ld   d,0
        ld   hl,sweeptab
        add  hl,de
        ld   de,attrs + r_text * 32
        ld   b,32
sw1:    ld   a,(hl)
        inc  hl
        ld   (de),a
        inc  de
        djnz sw1
        ret

; The pills keep the icon's four colours; what moves is the bright bit, one
; pill at a time, so the row reads as a chase and not as a flash.
pills:  ld   hl,attrs + r_pills * 32 + pill_col
        ld   de,pillink
        ld   a,(wave)
        ld   c,a
        ld   b,4
pl1:    ld   a,(de)
        inc  de
        dec  c
        jr   nz,pl2
        or   $40                ; this is the lit one
pl2:    push bc
        ld   b,pill_w
pl3:    ld   (hl),a
        inc  hl
        djnz pl3
        pop  bc
        inc  hl
        inc  hl
        djnz pl1
        ret

; How fast each of those moves.  The wash steps a whole character row at a
; time — there is no finer colour on this machine — so it is stepped slowly:
; every eighth frame is 50 pixels a second, and anything quicker strobes.
cycle:  ld   hl,tick
        inc  (hl)
        ld   a,(hl)
        and  $07
        jr   nz,cy1
        ld   hl,phase
        ld   a,(hl)
        inc  a
        and  $0f
        ld   (hl),a
cy1:    ld   a,(tick)
        and  $03
        jr   nz,cy2
        ld   hl,phase2
        ld   a,(hl)
        dec  a
        and  $1f                ; the scroller's table is twice as long
        ld   (hl),a
cy2:    ld   hl,wavewait
        dec  (hl)
        ret  nz
        ld   (hl),12
        ld   hl,wave
        ld   a,(hl)
        inc  a
        cp   5
        jr   c,cy3
        ld   a,1
cy3:    ld   (hl),a
        ret

; --- what it says ----------------------------------------------------------

lab48:  db   "48K",0
labz80: db   "Z80",0

; The credit is not decoration: the music is CC-BY, and this is where the
; attribution travels — the tape carries it wherever the tape goes.
msg:    db   "   zedex  *  Modern ZX Spectrum emulator for Android"
        db   "  *  48K to +3, tapes, disks, snapshots, cheats"
        db   "  *  the Fuse core, unmodified"
        db   "  *  music: Time Up by shiru8bit, CC-BY"
        db   "  *  opengameart.org/users/shiru8bit  ",0

; --- tables ----------------------------------------------------------------

; The wordmark is white and stays white: what passes up through it is one
; band of the icon's four colours, in the icon's order, so a screenshot taken
; at any moment is still the wordmark.  Sixteen entries, six read at a time,
; and the pattern written out twice rather than wrapped in the loop.
rainbow:
        db   $47,$47,$47,$47,$47,$47,$42,$46
        db   $44,$45,$47,$47,$47,$47,$47,$47
        db   $47,$47,$47,$47,$47,$47,$42,$46
        db   $44,$45,$47,$47,$47,$47,$47,$47

; The scroller's: white, one band of colour, and thirty two entries because
; that is the width of the screen — so the band crosses the line once and is
; on its own out there.  Twice over again, for the same reason as above.
sweeptab:
        db   $47,$47,$47,$47,$47,$47,$47,$47
        db   $47,$47,$47,$47,$47,$47,$42,$46
        db   $44,$45,$47,$47,$47,$47,$47,$47
        db   $47,$47,$47,$47,$47,$47,$47,$47
        db   $47,$47,$47,$47,$47,$47,$47,$47
        db   $47,$47,$47,$47,$47,$47,$42,$46
        db   $44,$45,$47,$47,$47,$47,$47,$47
        db   $47,$47,$47,$47,$47,$47,$47,$47

; Red, yellow, green, cyan: the icon's own order, left to right.
pillink:
        db   $02,$06,$04,$05

; A pill, a line at a time: its left end, its middle, its right end.
pilldata:
        db   $00,$00,$00
        db   $3f,$ff,$fc
        db   $7f,$ff,$fe
        db   $ff,$ff,$ff
        db   $ff,$ff,$ff
        db   $7f,$ff,$fe
        db   $3f,$ff,$fc
        db   $00,$00,$00

nstars  equ  32
startab:
        ; the band above the wordmark
        db    18, 95,1
        db    19,228,2
        db    20, 72,1
        db    22, 21,3
        db    23,202,2
        db    24, 80,3
        db    25,  7,3
        db    27, 32,1
        db    28, 18,1
        db    29,123,3
        db    30, 15,2
        db    31,167,2
        ; and the wider one below
        db   136, 35,3
        db   141, 55,2
        db   145, 55,2
        db   148,197,1
        db   150,  8,1
        db   152,109,1
        db   153, 26,2
        db   154,192,2
        db   156,214,1
        db   162,101,2
        db   165,172,1
        db   167,159,2
        db   169,  7,2
        db   171, 60,1
        db   173,126,1
        db   175,  5,1
        db   176,238,2
        db   179, 90,3
        db   180, 96,2
        db   181, 97,1

; --- state -----------------------------------------------------------------

msgptr:   dw   msg
subcol:   db   1
colbits:  ds   8
rowbase:  dw   0
tick:     db   0
phase:    db   0
phase2:   db   0
wave:     db   1
wavewait: db   12

; --- the wordmark ----------------------------------------------------------

logodata:
        include "logo.inc"

; --- the music -------------------------------------------------------------

        include "pt3.asm"
