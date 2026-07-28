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
#include "event.h"
#include "input.h"
#include "keyboard.h"
#include "machine.h"
#include "rzx.h"
#include "settings.h"
#include "tape.h"
#include "utils.h"
#include "z80/z80.h"

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

/* --- command queue ---------------------------------------------------- */

#define COMMAND_QUEUE_SIZE 256

typedef enum command_type {
  COMMAND_KEY,				/* a: keycode, b: pressed */
  COMMAND_SELECT_MACHINE,		/* a: index into machine_types */
  COMMAND_RESET,
  COMMAND_NMI,
  COMMAND_OPEN_FILE,			/* text: path to open */
  COMMAND_SET_OPTION,			/* a: option, b: value */
} command_type;

/* Options the Android UI can toggle. */
enum {
  OPTION_FAST_TAPE,
  OPTION_TAPE_SOUND,
};

typedef struct queued_command {
  command_type type;
  int a, b;
  char *text;				/* owned here; freed once it has run */
} queued_command;

static pthread_mutex_t command_mutex = PTHREAD_MUTEX_INITIALIZER;
static queued_command command_queue[ COMMAND_QUEUE_SIZE ];
static size_t command_head, command_tail;

/* `text', if given, is taken over by the queue. */
static void
queue_command_text( command_type type, int a, int b, char *text )
{
  size_t next;

  pthread_mutex_lock( &command_mutex );

  next = ( command_tail + 1 ) % COMMAND_QUEUE_SIZE;
  if( next == command_head ) {
    /* Full: the emulation thread has stalled. Dropping is better than
       blocking the UI thread. */
    android_logw( "command queue overflow, dropping type %d", type );
    free( text );
  } else {
    command_queue[ command_tail ].type = type;
    command_queue[ command_tail ].a = a;
    command_queue[ command_tail ].b = b;
    command_queue[ command_tail ].text = text;
    command_tail = next;
  }

  pthread_mutex_unlock( &command_mutex );
}

static void
queue_command( command_type type, int a, int b )
{
  queue_command_text( type, a, b, NULL );
}

static void
run_key( int keycode, int pressed )
{
  input_event_t fuse_event;
  input_key fuse_key = keysyms_remap( keycode );

  if( fuse_key == INPUT_KEY_NONE ) return;

  fuse_event.type = pressed ? INPUT_EVENT_KEYPRESS : INPUT_EVENT_KEYRELEASE;
  fuse_event.types.key.native_key = fuse_key;
  fuse_event.types.key.spectrum_key = fuse_key;

  input_event( &fuse_event );
}

/* --- machines --------------------------------------------------------- */

#define MAX_MACHINES 32

typedef struct machine_entry {
  char id[ 32 ];			/* Fuse's command line id, e.g. "128" */
  char name[ 64 ];			/* human readable */
} machine_entry;

static pthread_mutex_t machine_mutex = PTHREAD_MUTEX_INITIALIZER;
static machine_entry machine_list[ MAX_MACHINES ];
static int machine_list_count;
static int machine_list_current = -1;

/* Fuse's machine table is built during initialisation and never changes
   afterwards, so it is snapshotted once, on the emulation thread, for the UI
   thread to read. The current machine is refreshed every pump because Fuse
   can change it without us asking - falling back to 48K when a machine's
   ROMs are missing, for instance. */
static void
publish_machines( void )
{
  int i;

  pthread_mutex_lock( &machine_mutex );

  if( !machine_list_count && machine_count ) {
    machine_list_count = machine_count < MAX_MACHINES ? machine_count
                                                      : MAX_MACHINES;
    for( i = 0; i < machine_list_count; i++ ) {
      const char *id = machine_types[i]->id;
      const char *name = libspectrum_machine_name( machine_types[i]->machine );

      snprintf( machine_list[i].id, sizeof( machine_list[i].id ), "%s",
                id ? id : "" );
      snprintf( machine_list[i].name, sizeof( machine_list[i].name ), "%s",
                name ? name : "?" );
    }
  }

  machine_list_current = -1;
  if( machine_current ) {
    for( i = 0; i < machine_list_count; i++ ) {
      if( machine_types[i]->machine == machine_current->machine ) {
        machine_list_current = i;
        break;
      }
    }
  }

  pthread_mutex_unlock( &machine_mutex );
}

static void
run_select_machine( int index )
{
  if( index < 0 || index >= machine_count ) return;

  android_log( "selecting machine %s", machine_types[ index ]->id );
  machine_select( machine_types[ index ]->machine );
}

/* Keys pressed during the current pump, so their release can be held over to
   the next one. The Spectrum ROM only scans the keyboard once per frame, so a
   press and release arriving together - which is what a synthesised tap or a
   very fast finger produces - would otherwise never be seen at all. */
static int pressed_this_pump[ 16 ];
static int pressed_count;

static int
pressed_during_this_pump( int keycode )
{
  int i;

  for( i = 0; i < pressed_count; i++ )
    if( pressed_this_pump[i] == keycode ) return 1;

  return 0;
}

void
androidbridge_pump_commands( void )
{
  pressed_count = 0;

  for(;;) {
    queued_command command;

    pthread_mutex_lock( &command_mutex );
    if( command_head == command_tail ) {
      pthread_mutex_unlock( &command_mutex );
      break;
    }
    command = command_queue[ command_head ];

    /* Leave this key up for the next frame, and everything behind it with
       it: the queue has to stay in order. */
    if( command.type == COMMAND_KEY && !command.b &&
        pressed_during_this_pump( command.a ) ) {
      pthread_mutex_unlock( &command_mutex );
      break;
    }

    command_head = ( command_head + 1 ) % COMMAND_QUEUE_SIZE;
    pthread_mutex_unlock( &command_mutex );

    switch( command.type ) {
    case COMMAND_KEY:
      run_key( command.a, command.b );
      if( command.b && pressed_count < 16 )
        pressed_this_pump[ pressed_count++ ] = command.a;
      break;
    case COMMAND_SELECT_MACHINE:
      run_select_machine( command.a );
      break;
    case COMMAND_RESET:
      android_log( "reset" );
      /* Same order Fuse's own menu uses: a reset mid-recording would
         otherwise leave the RZX out of step with the machine. */
      rzx_stop_recording();
      rzx_stop_playback( 1 );
      machine_reset( 0 );
      break;
    case COMMAND_NMI:
      android_log( "nmi" );
      event_add( 0, z80_nmi_event );
      break;
    case COMMAND_OPEN_FILE:
      /* Fuse works out for itself what the file is - snapshot, tape, disk,
         cartridge, microdrive, RZX - and inserts it wherever it belongs,
         switching machine first if the media needs one we are not running.
         Autoloading is what makes a tape start on its own. */
      android_log( "opening %s", command.text ? command.text : "(null)" );
      if( command.text )
        utils_open_file( command.text, tape_can_autoload(), NULL );
      break;
    case COMMAND_SET_OPTION:
      switch( command.a ) {
      case OPTION_FAST_TAPE:
        /* Fuse spreads this across three settings: traps catch the ROM
           loading routine, fastload makes a trapped block appear at once,
           and accelerate_loader speeds up the timing loops of custom
           loaders that never call the ROM at all. */
        android_log( "fast tape loading %s", command.b ? "on" : "off" );
        settings_current.tape_traps = command.b;
        settings_current.fastload = command.b;
        settings_current.accelerate_loader = command.b;
        break;
      case OPTION_TAPE_SOUND:
        android_log( "tape sound %s", command.b ? "on" : "off" );
        settings_current.sound_load = command.b;
        break;
      }
      break;
    }

    free( command.text );
  }

  publish_machines();
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
  queue_command( COMMAND_KEY, keycode, pressed ? 1 : 0 );
}

/* Builds a String[] from the machine snapshot; `names' picks which column. */
static jobjectArray
machine_strings( JNIEnv *env, int names )
{
  jobjectArray result;
  int i, count;

  pthread_mutex_lock( &machine_mutex );
  count = machine_list_count;

  result = (*env)->NewObjectArray( env, count,
             (*env)->FindClass( env, "java/lang/String" ), NULL );

  for( i = 0; result && i < count; i++ ) {
    jstring value = (*env)->NewStringUTF( env, names ? machine_list[i].name
                                                     : machine_list[i].id );
    (*env)->SetObjectArrayElement( env, result, i, value );
    (*env)->DeleteLocalRef( env, value );
  }

  pthread_mutex_unlock( &machine_mutex );

  return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_fusemobile_FuseNative_machineNames( JNIEnv *env, jclass class )
{
  return machine_strings( env, 1 );
}

JNIEXPORT jobjectArray JNICALL
Java_com_fusemobile_FuseNative_machineIds( JNIEnv *env, jclass class )
{
  return machine_strings( env, 0 );
}

JNIEXPORT jint JNICALL
Java_com_fusemobile_FuseNative_currentMachine( JNIEnv *env, jclass class )
{
  jint current;

  pthread_mutex_lock( &machine_mutex );
  current = machine_list_current;
  pthread_mutex_unlock( &machine_mutex );

  return current;
}

JNIEXPORT void JNICALL
Java_com_fusemobile_FuseNative_selectMachine( JNIEnv *env, jclass class,
                                              jint index )
{
  queue_command( COMMAND_SELECT_MACHINE, index, 0 );
}

JNIEXPORT void JNICALL
Java_com_fusemobile_FuseNative_reset( JNIEnv *env, jclass class )
{
  queue_command( COMMAND_RESET, 0, 0 );
}

JNIEXPORT void JNICALL
Java_com_fusemobile_FuseNative_nmi( JNIEnv *env, jclass class )
{
  queue_command( COMMAND_NMI, 0, 0 );
}

JNIEXPORT void JNICALL
Java_com_fusemobile_FuseNative_setFastTape( JNIEnv *env, jclass class,
                                            jboolean fast )
{
  queue_command( COMMAND_SET_OPTION, OPTION_FAST_TAPE, fast ? 1 : 0 );
}

JNIEXPORT void JNICALL
Java_com_fusemobile_FuseNative_setTapeSound( JNIEnv *env, jclass class,
                                             jboolean on )
{
  queue_command( COMMAND_SET_OPTION, OPTION_TAPE_SOUND, on ? 1 : 0 );
}

JNIEXPORT void JNICALL
Java_com_fusemobile_FuseNative_openFile( JNIEnv *env, jclass class,
                                         jstring path )
{
  const char *utf = (*env)->GetStringUTFChars( env, path, NULL );

  if( utf ) {
    queue_command_text( COMMAND_OPEN_FILE, 0, 0, strdup( utf ) );
    (*env)->ReleaseStringUTFChars( env, path, utf );
  }
}
