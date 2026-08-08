/* The two UTF-8 converters, on the host, with no device and no JVM.
 *
 * Build and run:  cc -o /tmp/text_test native/tests/text_test.c && /tmp/text_test
 *
 * The functions live in ui/android/android_utf8.c, which is included rather
 * than linked so this is one command with no build system in it. That file
 * has no JNI in it precisely so that this is possible.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../ui/android/android_utf8.c"

static int failures;

static void
check( const char *what, const char *got, const char *wanted )
{
  if( got && wanted && strcmp( got, wanted ) == 0 ) {
    printf( "  ok   %s\n", what );
    return;
  }

  printf( "  FAIL %s\n", what );
  printf( "       got    " );
  for( const unsigned char *p = (const unsigned char*) got; p && *p; p++ )
    printf( "%02X ", *p );
  printf( "\n       wanted " );
  for( const unsigned char *p = (const unsigned char*) wanted; p && *p; p++ )
    printf( "%02X ", *p );
  printf( "\n" );
  failures++;
}

int
main( void )
{
  /* U+1F3AE, the game controller. Real UTF-8 is four bytes; modified UTF-8 is
     the two halves of its surrogate pair, three bytes each. */
  const char *real = "\xF0\x9F\x8E\xAE Manic Miner.tap";
  const char *modified = "\xED\xA0\xBC\xED\xBE\xAE Manic Miner.tap";

  /* Everything below U+10000 is identical in both, which is why this went
     unnoticed for so long. */
  const char *cyrillic = "\xD0\x9C\xD0\xB0\xD0\xBD\xD1\x8F.tap";
  const char *cjk = "\xE6\x97\xA5\xE6\x9C\xAC.tzx";

  printf( "decode - what Java hands us, to what open() needs\n" );
  check( "an emoji becomes one four-byte sequence",
         androidtext_decode( modified ), real );
  check( "Cyrillic is untouched", androidtext_decode( cyrillic ), cyrillic );
  check( "CJK is untouched", androidtext_decode( cjk ), cjk );
  check( "ASCII is untouched", androidtext_decode( "plain.tap" ), "plain.tap" );
  check( "an embedded NUL is dropped rather than truncating",
         androidtext_decode( "a\xC0\x80z" ), "az" );

  printf( "encode - what the filesystem gave us, to what Java expects\n" );
  check( "an emoji becomes a surrogate pair",
         androidtext_encode( real ), modified );
  check( "Cyrillic is untouched", androidtext_encode( cyrillic ), cyrillic );
  check( "CJK is untouched", androidtext_encode( cjk ), cjk );

  printf( "round trip\n" );
  check( "real -> modified -> real",
         androidtext_decode( androidtext_encode( real ) ), real );
  check( "modified -> real -> modified",
         androidtext_encode( androidtext_decode( modified ) ), modified );

  printf( failures ? "\n%d failed\n" : "\nall passed\n", failures );
  return failures ? 1 : 0;
}
