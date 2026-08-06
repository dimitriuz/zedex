; ---------------------------------------------------------------------------
; How many times the CPU goes round a loop in one frame, printed as hex.
;
; The frame is a fiftieth of a second whether or not the machine is in turbo,
; so the count is the processor speed and nothing else: a Pentagon at 3.5MHz
; reaches about $0C00, and the same machine in turbo reaches twice that.  The
; number doubling is the whole test.
;
; FRAMES, at 23672, is the ROM's own frame counter, incremented by the
; interrupt.  Waiting for it to change puts us at a frame boundary; counting
; until it changes again measures exactly one frame.  Interrupts stay on for
; that reason, and the loop is small enough that the interrupt's own work is a
; rounding error rather than the thing being measured.
;
; Printed with RST 16 through whatever channel BASIC left open, so this needs
; no font, no screen address and no assumptions about the machine.
; ---------------------------------------------------------------------------

        org  $8000

frames  equ  23672             ; sysvar: the interrupt's own counter

        ld   a,(frames)        ; wait for the start of a frame
        ld   b,a
w1:     ld   a,(frames)
        cp   b
        jr   z,w1

        ld   hl,0              ; and count until the next one
        ld   b,a
count:  inc  hl
        ld   a,(frames)
        cp   b
        jr   z,count

        ; hl, as four hex digits
        ld   a,h
        call byte_out
        ld   a,l
        call byte_out

hold:   jr   hold

; a = a byte, printed as two hex digits.
byte_out:
        push af
        rra
        rra
        rra
        rra
        call nibble
        pop  af
        ; fall through for the low half

; the bottom four bits of a, as one character.
nibble: and  $0f
        add  a,'0'
        cp   '9' + 1
        jr   c,nib_out
        add  a,7               ; 'A' is seven past '9'
nib_out:
        rst  16
        ret
