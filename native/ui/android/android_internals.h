/* android_internals.h: interfaces internal to the Android UI backend

   This backend replaces ui/fb at link time (see scripts/build-native.sh):
   Fuse is configured --with-fb so that the portable widget UI is built, and
   the ui/fb objects are then swapped for these. Fuse itself is unmodified.
*/

#ifndef FUSE_ANDROID_INTERNALS_H
#define FUSE_ANDROID_INTERNALS_H

#include <android/log.h>
#include <android/native_window.h>
#include <jni.h>

#include <libspectrum.h>

#define ANDROID_LOG_TAG "Zedex"
#define android_log( ... ) \
  __android_log_print( ANDROID_LOG_INFO, ANDROID_LOG_TAG, __VA_ARGS__ )
#define android_logw( ... ) \
  __android_log_print( ANDROID_LOG_WARN, ANDROID_LOG_TAG, __VA_ARGS__ )
/* For what stops the app doing what it was asked, rather than what it
   recovered from - a level logcat can filter on and a bug report keeps. */
#define android_loge( ... ) \
  __android_log_print( ANDROID_LOG_ERROR, ANDROID_LOG_TAG, __VA_ARGS__ )

/* --- text (android_text.c) -------------------------------------------- */

/* Modified UTF-8 to real UTF-8 and back, both malloc'd; free them. No JNI in
   these two, which is what lets native/tests/text_test.c run them on the
   host - see android_utf8.c. */
char *androidtext_decode( const char *modified );
char *androidtext_encode( const char *utf8 );

/* A Java string as real UTF-8, malloc'd; free it. NULL if the string was
   null or there was no memory. Not GetStringUTFChars, which gives modified
   UTF-8 - see android_text.c for what that costs a filename with an emoji
   in it. */
char *androidtext_from_java( JNIEnv *env, jstring text );

/* Real UTF-8 as a Java string. Not NewStringUTF, which expects modified
   UTF-8 and silently mangles anything above the BMP. */
jstring androidtext_to_java( JNIEnv *env, const char *utf8 );

/* --- display (android_display.c) ------------------------------------- */

int androiddisplay_init( void );
int androiddisplay_end( void );

/* Half-size RGBA dump of the last frame, for the save state list. */
int androiddisplay_write_thumbnail( const char *path );

/* The frame as it was last handed over, RGBA8888, or NULL before there has
   been one. For presenting again while the machine is not running. */
const void *androiddisplay_last_frame( int *width, int *height );

/* The frame as palette indices, and the sixteen colours they stand for.
   Recording and screenshots are built from these. */
const libspectrum_byte *androiddisplay_indices( int *stride, size_t *size );
const libspectrum_dword *androiddisplay_palette( void );

/* --- renderer (android_gl.c) ----------------------------------------- */

/* All of these run on the emulation thread, which owns the EGL context. */

/* Present one frame of 32bpp RGBA pixels. Silently does nothing while no
   surface is attached (i.e. while the app is backgrounded). */
void androidgl_frame( ANativeWindow *window, unsigned generation,
                      const void *pixels, int width, int height );

/* How big the picture is drawn: 0 fits it to the window, anything else is that
   many device pixels per emulated pixel. */
void androidgl_set_scale( int pixels );

/* How much of the Spectrum's border to show: 0 all, 1 a quarter, 2 none. */
void androidgl_set_border( int border );

/* The picture filters: three displays, each on or off, the signal that reached
   them, and a strength from 0 to 100 for every dial.

   One struct rather than a dozen arguments, which is what this was: they arrive
   from the settings one at a time and go to the renderer together, and a
   positional list of ints that all mean something different is a mistake waiting
   to be made. Bounded like RetroArch's #pragma parameter, for the day someone
   wants to run one of theirs. */
typedef struct android_filter {
  int scanlines;			/* on or off */
  int crt;
  int video;				/* 0 RGB, 1 composite, 2 RF */

  int sharpness;			/* the rest are 0 - 100 */
  int scanline;
  int curve;
  int mask;
  int glow;
  int bleed;
  int noise;
} android_filter;

void androidgl_set_filter( const android_filter *filter );

/* Drop the EGL surface but keep the context, for when Android takes the
   window away. */
void androidgl_detach( void );

void androidgl_end( void );

/* --- JNI bridge (android_bridge.c) ----------------------------------- */

/* Runs the window handover handshake with the UI thread and then draws.
   Called from uidisplay_frame_end(). */
void androidbridge_present( const void *pixels, int width, int height );

/* Answer the window handshake without drawing - for a caller that has no
   frame, which is every iteration of the paused loop before Fuse has
   managed to initialise its display. */
void androidbridge_service_window( void );

/* Whether there is a surface to draw into. For the paused loop, which slows
   right down when there is not. Any thread. */
int androidbridge_has_window( void );

/* Hands one of Fuse's errors to the Android side to show. Called on the
   emulation thread. */
void androidbridge_report_error( int severity, const char *message );

/* Offers the frame just drawn to whatever is recording or waiting for a
   screenshot. Does nothing, cheaply, when nothing is. Called from
   uidisplay_frame_end() on the emulation thread. */
void androidbridge_frame_ready( int width, int height );

/* Drain queued UI commands - keys, machine changes - onto the emulation
   thread, and refresh the state the UI thread reads back. Called from
   ui_event(). */
void androidbridge_pump_commands( void );

/* How far to look for drives when walking Fuse's controllers. Shared because
   both the drive list and the disk lamp have to walk the same ground. */
#define MAX_CONTROLLERS 8
#define MAX_DRIVES_PER_CONTROLLER 4

/* --- the DivMMC and its card (android_card.c) ------------------------- */

/* All of these run on the emulation thread, from the command queue. */

/* Plugs the interface in or takes it out, hard resetting either way. Refuses
   to plug it in without firmware: a blank EPROM automaps into the reset and
   hangs the machine. */
void androidcard_set_enabled( int on );

/* Reads an 8K firmware image and writes it into the EPROM. Non-zero if the
   file is not one; the user has already been told. */
int androidcard_load_firmware( const char *path );

/* Puts the interface back after a snapshot load has unplugged it. */
void androidcard_restore( void );

/* The card: in, written back, out. Ejecting and replacing both commit first,
   so Fuse never has to ask about unsaved changes through a modal. */
void androidcard_insert( const char *path );
void androidcard_commit( void );
void androidcard_eject( void );

/* Commits by itself, once a second. Called every frame from the pump: what the
   machine writes to a card has to reach the file without anyone remembering a
   menu item. */
void androidcard_tick( void );

/* --- state for the UI thread (android_state.c) ------------------------ */

/* Copy everything the UI thread may read - the machine list, which machine is
   running, what is in which drive. Called once a frame from the pump. */
void androidstate_publish( void );

/* --- what the machine is busy with (android_status.c) ----------------- */

/* Gather the tape, disk, AY and port-read state for the app's indicators.
   Called once a frame on the emulation thread. */
void androidstatus_frame( void );

/* Every lamp dark, for while the machine is not running. */
void androidstatus_idle( void );

/* The gathered state as ACTIVITY_* bits, for the UI thread to read. */
int androidstatus_activity( void );

/* How loud the AY's three channels are, 0-15 each, as three bytes: A in the
   bottom, then B, then C. */
int androidstatus_ay_levels( void );

#endif				/* #ifndef FUSE_ANDROID_INTERNALS_H */
