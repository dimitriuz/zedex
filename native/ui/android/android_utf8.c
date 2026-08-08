/* Modified UTF-8 and real UTF-8, converted both ways.
 *
 * Java does not hand out UTF-8. GetStringUTFChars gives *modified* UTF-8,
 * which differs in exactly two places: a NUL is written C0 80 so no string can
 * contain a zero byte, and anything above the BMP is written as the two
 * three-byte halves of its surrogate pair rather than one four-byte sequence.
 * Below U+10000 the two are byte for byte identical - which is why Cyrillic,
 * Greek, accented Latin and every CJK ideograph have always worked here, and
 * why this went unnoticed.
 *
 * Above it they diverge, and a filename is where that lands. "\U0001F3AE Manic
 * Miner.tap" is a name a person actually gives a file; handed to open() as six
 * bytes of surrogate halves it matches nothing on disk and Fuse reports a file
 * it cannot open. The other direction is worse because nothing complains:
 * NewStringUTF is handed the real UTF-8 the filesystem gave us, ART does not
 * validate it, and the four-byte sequence arrives in Java as two junk
 * characters in the drive list.
 *
 * No JNI in this file on purpose - it is arithmetic over bytes, and that means
 * native/tests/text_test.c can exercise it on the host with no device and no
 * JVM. The two wrappers that do touch JNI are in android_text.c.
 */

#include <stdlib.h>
#include <string.h>

/* The surrogate halves, as modified UTF-8 writes them: ED A0..AF xx for the
   high half and ED B0..BF xx for the low one. */
static int
is_high_surrogate( const unsigned char *b )
{
  return b[0] == 0xED && b[1] >= 0xA0 && b[1] <= 0xAF;
}

static int
is_low_surrogate( const unsigned char *b )
{
  return b[0] == 0xED && b[1] >= 0xB0 && b[1] <= 0xBF;
}

static unsigned
surrogate_value( const unsigned char *b )
{
  return 0xD000u | ( ( b[1] & 0x3Fu ) << 6 ) | ( b[2] & 0x3Fu );
}

/* The conversion on its own, so it can be tested without a JVM in the way;
   see native/tests/text_test.c. */
char *
androidtext_decode( const char *modified )
{
  const unsigned char *in;
  char *out, *write;
  size_t length;

  if( !modified ) return NULL;

  length = strlen( modified );

  /* Never longer than what came in: a surrogate pair is six bytes going to
     four, C0 80 is two going to one, and everything else is copied. */
  out = malloc( length + 1 );
  if( !out ) return NULL;

  in = (const unsigned char*) modified;
  write = out;

  while( *in ) {
    if( in[0] == 0xC0 && in[1] == 0x80 ) {
      /* An embedded NUL. It cannot be part of a path, and passing one on
         would truncate whatever it is part of, so it is dropped. */
      in += 2;
      continue;
    }

    if( is_high_surrogate( in ) && in[1] && in[2] &&
        is_low_surrogate( in + 3 ) && in[4] && in[5] ) {
      unsigned high = surrogate_value( in );
      unsigned low = surrogate_value( in + 3 );
      unsigned code = 0x10000u + ( ( high - 0xD800u ) << 10 )
                               + ( low - 0xDC00u );

      *write++ = (char) ( 0xF0u | ( code >> 18 ) );
      *write++ = (char) ( 0x80u | ( ( code >> 12 ) & 0x3Fu ) );
      *write++ = (char) ( 0x80u | ( ( code >> 6 ) & 0x3Fu ) );
      *write++ = (char) ( 0x80u | ( code & 0x3Fu ) );

      in += 6;
      continue;
    }

    *write++ = (char) *in++;
  }

  *write = '\0';

  return out;
}

char *
androidtext_encode( const char *utf8 )
{
  const unsigned char *in;
  char *modified, *write;
  size_t length;

  if( !utf8 ) return NULL;

  length = strlen( utf8 );

  /* Six bytes out for every four in, at worst. */
  modified = malloc( length * 3 / 2 + 1 );
  if( !modified ) return NULL;

  in = (const unsigned char*) utf8;
  write = modified;

  while( *in ) {
    if( ( in[0] & 0xF8u ) == 0xF0u && in[1] && in[2] && in[3] ) {
      unsigned code = ( ( in[0] & 0x07u ) << 18 ) | ( ( in[1] & 0x3Fu ) << 12 )
                    | ( ( in[2] & 0x3Fu ) << 6 ) | ( in[3] & 0x3Fu );
      unsigned high = 0xD800u + ( ( code - 0x10000u ) >> 10 );
      unsigned low = 0xDC00u + ( ( code - 0x10000u ) & 0x3FFu );

      *write++ = (char) 0xED;
      *write++ = (char) ( 0xA0u | ( ( high >> 6 ) & 0x0Fu ) );
      *write++ = (char) ( 0x80u | ( high & 0x3Fu ) );

      *write++ = (char) 0xED;
      *write++ = (char) ( 0xB0u | ( ( low >> 6 ) & 0x0Fu ) );
      *write++ = (char) ( 0x80u | ( low & 0x3Fu ) );

      in += 4;
      continue;
    }

    *write++ = (char) *in++;
  }

  *write = '\0';

  return modified;
}
