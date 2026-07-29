/* android_window.c: handing the drawing surface between Android and Fuse

   Android may take the window away at any moment, and the emulation thread
   must have stopped using it before surfaceDestroyed() returns - it owns the
   EGL context and is the only thread allowed near it. So this is a handshake
   rather than a lock: the UI thread leaves a request, and the emulation thread
   answers it at a frame boundary, which is the only moment the surface is not
   in use.

   Its own file because it is a protocol with an invariant of its own, and
   because it is the one part of the bridge that has to keep working while
   everything else has stopped - a paused emulator still comes through here.
   See run_while_paused() in android_bridge.c.
*/

#include "config.h"

#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <time.h>

#include <android/native_window_jni.h>

#include "android_internals.h"

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

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_surfaceChanged( JNIEnv *env, jclass class,
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
Java_dev_ldlab_zedex_FuseNative_surfaceDestroyed( JNIEnv *env, jclass class )
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
