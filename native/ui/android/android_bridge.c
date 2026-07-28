/* android_bridge.c: the boundary between Android and Fuse

   Fuse's core is single threaded and is not safe to call from anywhere but
   the emulation thread. Everything arriving from Android - key presses,
   and later menu actions, snapshots and debugger commands - is therefore
   queued here and replayed on the emulation thread from ui_event().

   The window handover is the other half of the job: Android may take the
   drawing surface away at any moment, and the emulation thread must have
   stopped using it before surfaceDestroyed() returns.
*/

#include "config.h"

#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <android/native_window_jni.h>

#include "android_internals.h"
#include "input.h"
#include "keyboard.h"

/* --- window handover -------------------------------------------------- */

static pthread_mutex_t window_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t window_cond = PTHREAD_COND_INITIALIZER;

static ANativeWindow *window;		/* in use by the emulation thread */
static ANativeWindow *pending_window;	/* handed over by the UI thread */
static int have_pending;
static int teardown_requested;
static int teardown_done;
static unsigned window_generation;

void
androidbridge_present( const void *pixels, int width, int height )
{
  pthread_mutex_lock( &window_mutex );

  if( teardown_requested ) {
    androidgl_detach();
    if( window ) { ANativeWindow_release( window ); window = NULL; }
    teardown_requested = 0;
    teardown_done = 1;
    pthread_cond_broadcast( &window_cond );
  }

  if( have_pending ) {
    androidgl_detach();
    if( window ) ANativeWindow_release( window );
    window = pending_window;
    pending_window = NULL;
    have_pending = 0;
    window_generation++;
  }

  androidgl_frame( window, window_generation, pixels, width, height );

  pthread_mutex_unlock( &window_mutex );
}

/* --- input queue ------------------------------------------------------ */

#define INPUT_QUEUE_SIZE 256

typedef struct queued_key {
  int keycode;				/* Android keycode */
  int pressed;
} queued_key;

static pthread_mutex_t input_mutex = PTHREAD_MUTEX_INITIALIZER;
static queued_key input_queue[ INPUT_QUEUE_SIZE ];
static size_t input_head, input_tail;

static void
queue_key( int keycode, int pressed )
{
  size_t next;

  pthread_mutex_lock( &input_mutex );

  next = ( input_tail + 1 ) % INPUT_QUEUE_SIZE;
  if( next == input_head ) {
    /* Full: the emulation thread has stalled. Dropping is better than
       blocking the UI thread. */
    android_logw( "input queue overflow, dropping keycode %d", keycode );
  } else {
    input_queue[ input_tail ].keycode = keycode;
    input_queue[ input_tail ].pressed = pressed;
    input_tail = next;
  }

  pthread_mutex_unlock( &input_mutex );
}

void
androidbridge_pump_input( void )
{
  for(;;) {
    queued_key event;
    input_event_t fuse_event;
    input_key fuse_key;

    pthread_mutex_lock( &input_mutex );
    if( input_head == input_tail ) {
      pthread_mutex_unlock( &input_mutex );
      return;
    }
    event = input_queue[ input_head ];
    input_head = ( input_head + 1 ) % INPUT_QUEUE_SIZE;
    pthread_mutex_unlock( &input_mutex );

    fuse_key = keysyms_remap( event.keycode );
    if( fuse_key == INPUT_KEY_NONE ) continue;

    fuse_event.type = event.pressed ? INPUT_EVENT_KEYPRESS
                                    : INPUT_EVENT_KEYRELEASE;
    fuse_event.types.key.native_key = fuse_key;
    fuse_event.types.key.spectrum_key = fuse_key;

    input_event( &fuse_event );
  }
}

/* --- emulation thread ------------------------------------------------- */

/* Fuse's own entry point. With the fb UI selected there is no SDL header to
   rename it, so this is simply Fuse's main(). */
extern int main( int argc, char **argv );

static int fuse_argc;
static char **fuse_argv;

static void *
emulation_thread( void *arg )
{
  android_log( "emulation thread starting" );
  main( fuse_argc, fuse_argv );
  android_logw( "fuse main() returned" );
  return NULL;
}

/* --- JNI -------------------------------------------------------------- */

JNIEXPORT void JNICALL
Java_com_fusemobile_FuseNative_start( JNIEnv *env, jclass class,
                                      jobjectArray args )
{
  pthread_attr_t attributes;
  pthread_t thread;
  jsize i, count;

  if( fuse_argv ) {
    android_logw( "emulation thread already started" );
    return;
  }

  count = (*env)->GetArrayLength( env, args );
  fuse_argv = calloc( count + 1, sizeof( char* ) );
  if( !fuse_argv ) return;

  for( i = 0; i < count; i++ ) {
    jstring value = (*env)->GetObjectArrayElement( env, args, i );
    const char *utf = (*env)->GetStringUTFChars( env, value, NULL );
    fuse_argv[i] = strdup( utf );
    (*env)->ReleaseStringUTFChars( env, value, utf );
    (*env)->DeleteLocalRef( env, value );
  }
  fuse_argc = count;

  /* Fuse keeps several PATH_MAX buffers live at once; the 1MB a thread
     gets by default is uncomfortably tight. */
  pthread_attr_init( &attributes );
  pthread_attr_setstacksize( &attributes, 4 * 1024 * 1024 );
  pthread_create( &thread, &attributes, emulation_thread, NULL );
  pthread_attr_destroy( &attributes );
  pthread_detach( thread );
}

JNIEXPORT void JNICALL
Java_com_fusemobile_FuseNative_surfaceChanged( JNIEnv *env, jclass class,
                                               jobject surface )
{
  ANativeWindow *native = ANativeWindow_fromSurface( env, surface );

  pthread_mutex_lock( &window_mutex );
  if( pending_window ) ANativeWindow_release( pending_window );
  pending_window = native;
  have_pending = 1;
  pthread_mutex_unlock( &window_mutex );
}

JNIEXPORT void JNICALL
Java_com_fusemobile_FuseNative_surfaceDestroyed( JNIEnv *env, jclass class )
{
  struct timespec deadline;

  pthread_mutex_lock( &window_mutex );

  if( pending_window ) {
    ANativeWindow_release( pending_window );
    pending_window = NULL;
    have_pending = 0;
  }

  if( window ) {
    teardown_requested = 1;
    teardown_done = 0;

    clock_gettime( CLOCK_REALTIME, &deadline );
    deadline.tv_sec += 1;

    while( !teardown_done ) {
      if( pthread_cond_timedwait( &window_cond, &window_mutex,
                                  &deadline ) == ETIMEDOUT ) {
        /* Leave the request standing so the emulation thread still cleans
           up when it next reaches a frame boundary. */
        android_logw( "timed out waiting for the surface to be released" );
        break;
      }
    }
  }

  pthread_mutex_unlock( &window_mutex );
}

JNIEXPORT void JNICALL
Java_com_fusemobile_FuseNative_key( JNIEnv *env, jclass class, jint keycode,
                                    jboolean pressed )
{
  queue_key( keycode, pressed ? 1 : 0 );
}
