/* android_internals.h: interfaces internal to the Android UI backend

   This backend replaces ui/fb at link time (see scripts/build-native.sh):
   Fuse is configured --with-fb so that the portable widget UI is built, and
   the ui/fb objects are then swapped for these. Fuse itself is unmodified.
*/

#ifndef FUSE_ANDROID_INTERNALS_H
#define FUSE_ANDROID_INTERNALS_H

#include <android/log.h>
#include <android/native_window.h>

#include <libspectrum.h>

#define ANDROID_LOG_TAG "FuseNative"
#define android_log( ... ) \
  __android_log_print( ANDROID_LOG_INFO, ANDROID_LOG_TAG, __VA_ARGS__ )
#define android_logw( ... ) \
  __android_log_print( ANDROID_LOG_WARN, ANDROID_LOG_TAG, __VA_ARGS__ )

/* --- display (android_display.c) ------------------------------------- */

int androiddisplay_init( void );
int androiddisplay_end( void );

/* --- renderer (android_gl.c) ----------------------------------------- */

/* All of these run on the emulation thread, which owns the EGL context. */

/* Present one frame of 32bpp RGBA pixels. Silently does nothing while no
   surface is attached (i.e. while the app is backgrounded). */
void androidgl_frame( ANativeWindow *window, unsigned generation,
                      const void *pixels, int width, int height );

/* Drop the EGL surface but keep the context, for when Android takes the
   window away. */
void androidgl_detach( void );

void androidgl_end( void );

/* --- JNI bridge (android_bridge.c) ----------------------------------- */

/* Runs the window handover handshake with the UI thread and then draws.
   Called from uidisplay_frame_end(). */
void androidbridge_present( const void *pixels, int width, int height );

/* Drain queued UI commands - keys, machine changes - onto the emulation
   thread, and refresh the state the UI thread reads back. Called from
   ui_event(). */
void androidbridge_pump_commands( void );

#endif				/* #ifndef FUSE_ANDROID_INTERNALS_H */
