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

#include "machine.h"
#include "settings.h"

#include "android_internals.h"

static pthread_mutex_t window_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t window_cond = PTHREAD_COND_INITIALIZER;

static ANativeWindow *window;		/* in use by the emulation thread */
static ANativeWindow *pending_window;	/* handed over by the UI thread */
static int have_pending;
static int teardown_requested;
static int teardown_done;
static unsigned window_generation;

/* No faster than this, in nanoseconds: about seventy five frames a second. */
#define PRESENT_INTERVAL_NS ( 13 * 1000 * 1000LL )

static long long last_present;

static long long
now_ns( void )
{
  struct timespec now;

  clock_gettime( CLOCK_MONOTONIC, &now );
  return now.tv_sec * 1000000000LL + now.tv_nsec;
}

/* Whether this frame is worth showing.
 *
 * Above real time the frames the panel cannot show are dropped. Nothing is
 * lost: a screen refreshing sixty times a second cannot show two hundred and
 * fifty of them, so drawing and queueing them is work spent on something
 * nobody will see, and the emulation gets the time back instead. At normal
 * speed every frame is presented.
 *
 * This used to matter far more than it does: with the swap waiting for the
 * panel it was the *only* thing that let the speed setting go above about a
 * hundred and twenty per cent, because the display had quietly become the
 * clock. The swap no longer waits - see attach() in android_gl.c - so this is
 * now only about not doing pointless work.
 */
static int
worth_presenting( void )
{
  long long now;

  if( settings_current.emulation_speed <= 100 ) return 1;

  now = now_ns();
  if( now - last_present < PRESENT_INTERVAL_NS ) return 0;

  last_present = now;
  return 1;
}

/* Tells Android how often this window will have something new to show.
 *
 * A Spectrum makes 50.08 frames a second and no panel refreshes at that rate,
 * so on a sixty hertz screen a tenth of the refreshes have to show the frame
 * before them again - visible in anything that scrolls. A phone that has more
 * than one refresh rate can do better than that, but only if it is told what
 * the content's rate is, which is what this is for: FIXED_SOURCE says the rate
 * is the material's own and not a target to be met, so the platform may switch
 * the display to a rate that suits it and will schedule the frames evenly
 * either way.
 *
 * The rate is asked of the machine rather than assumed, since a 48K and a
 * Pentagon do not agree on it, and it is set again whenever the window or the
 * machine changes.
 */
static float declared_rate;

static void
declare_frame_rate( void )
{
  float rate;

  if( !window || !machine_current ) return;

  rate = (float) machine_current->timings.processor_speed
         / machine_current->timings.tstates_per_frame;

  if( rate == declared_rate ) return;

  ANativeWindow_setFrameRate(
    window, rate, ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE );
  declared_rate = rate;
}

/* Whether there is anywhere to draw at all.
 *
 * For the paused loop, which has to hand the last frame over often enough that
 * surfaceDestroyed() gets its answer promptly - and has nothing to hand it to
 * when the device is asleep or the app is behind something, which is when
 * waking sixty times a second is sixty wakeups an emulator that is not running
 * has no business asking for. See run_while_paused().
 */
int
androidbridge_has_window( void )
{
  int have;

  pthread_mutex_lock( &window_mutex );
  have = window != NULL || have_pending;
  pthread_mutex_unlock( &window_mutex );

  return have;
}

void
androidbridge_present( const void *pixels, int width, int height )
{
  if( !worth_presenting() ) return;

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
    declared_rate = 0;		/* a new window has not been told anything */
  }

  declare_frame_rate();

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
