/* The JNI edge of the UTF-8 conversion; the conversion itself is in
   android_utf8.c, which has no JNI in it so that it can be tested on the
   host. See that file for what modified UTF-8 costs a filename. */

#include "config.h"

#include <jni.h>
#include <stdlib.h>

#include "android_internals.h"

char *
androidtext_from_java( JNIEnv *env, jstring text )
{
  const char *modified;
  char *out;

  if( !text ) return NULL;

  modified = (*env)->GetStringUTFChars( env, text, NULL );
  if( !modified ) return NULL;

  out = androidtext_decode( modified );

  (*env)->ReleaseStringUTFChars( env, text, modified );

  return out;
}

jstring
androidtext_to_java( JNIEnv *env, const char *utf8 )
{
  char *modified = androidtext_encode( utf8 );
  jstring result;

  if( !modified ) return NULL;

  result = (*env)->NewStringUTF( env, modified );
  free( modified );

  return result;
}
