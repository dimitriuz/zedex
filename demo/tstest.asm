; ---------------------------------------------------------------------------
; Two tones, one per AY chip, for testing TurboSound.
;
; The first chip is given a tone on channel A and the second one a different
; tone on channel C, and everything else on both is silent.  On a machine with
; a TurboSound that is two tones at once, one from each chip:
;
;   1792000 / (16 * 250) = 448 Hz            the first chip, channel A
;   1792000 / (16 * 167) = 671 Hz            the second, channel C
;
; On a machine without one, 0xff and 0xfe are registers 15 and 14 as they have
; always been, so the second chip's writes land on the first chip's registers
; and overwrite them: channel A is silenced and only channel C is heard.
;
; Two chips or one is therefore visible as well as audible - the app's AY meter
; shows two bars lit where a machine without a TurboSound lights only the third.
; The border goes green when both chips have been written, so a screenshot says
; the program got that far.
; ---------------------------------------------------------------------------

        org  $8000

        ld   a,5                ; cyan: started
        out  ($fe),a

        ld   a,$ff              ; the first chip
        ld   hl,chip0
        call sendregs

        ld   a,$fe              ; the second
        ld   hl,chip1
        call sendregs

        ld   a,4                ; green: both chips written
        out  ($fe),a

hold:   jr   hold

; a = the chip select byte, hl = fourteen register values.
sendregs:
        ld   bc,$fffd
        out  (c),a              ; 0xff or 0xfe: which chip the rest addresses
        ld   e,0
sr1:    ld   bc,$fffd
        ld   a,e
        out  (c),a
        ld   b,$bf
        ld   a,(hl)
        out  (c),a
        inc  hl
        inc  e
        ld   a,e
        cp   14
        jr   nz,sr1
        ret

; R0-R13.  A mixer byte passes a tone where its bit is 0, so $3e is channel A
; alone and $3b is channel C alone; all three noises are off in both.  The
; amplitude of the channel being used is 15 and the other two are 0.
chip0:  db   250, 0             ; A period 250 -> 448 Hz
        db   0, 0
        db   0, 0
        db   0
        db   $3e                ; tone A only
        db   15, 0, 0
        db   0, 0, 0

chip1:  db   0, 0
        db   0, 0
        db   167, 0             ; C period 167 -> 671 Hz
        db   0
        db   $3b                ; tone C only
        db   0, 0, 15
        db   0, 0, 0
