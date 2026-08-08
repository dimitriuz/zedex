/* android_state.c: what the UI thread is allowed to read

   Fuse's core is single threaded and none of it may be touched from the UI
   thread - but the menus have to show which machine is running and what is in
   which drive. So the emulation thread copies what the UI needs into plain
   arrays once a frame, behind a mutex, and the UI thread reads those.

   Its own file because it is a different job from the queue that
   android_bridge.c is named for: that one carries intentions in, this one
   carries facts out. Everything here either fills the snapshot or reads it;
   anything that asks Fuse to *do* something stays with the queue.

   The machine table is built during Fuse's initialisation and never changes
   afterwards, so it is copied once. The current machine and the drives are
   refreshed every frame, because Fuse can change them without being asked -
   falling back to 48K when a machine's ROMs are missing, for instance.
*/

#include "config.h"

#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "android_internals.h"

#include "machine.h"
#include "settings.h"
#include "tape.h"
#include "ui/uimedia.h"
#include "peripherals/disk/disk.h"
#include "peripherals/disk/fdd.h"

#define MAX_MACHINES 32

typedef struct machine_entry {
  char id[ 32 ];			/* Fuse's command line id, e.g. "128" */
  char name[ 64 ];			/* human readable */
} machine_entry;

static pthread_mutex_t machine_mutex = PTHREAD_MUTEX_INITIALIZER;
static machine_entry machine_list[ MAX_MACHINES ];
static int machine_list_count;
static int machine_list_current = -1;
static int machine_turbo_capable;
static int tape_on_machine;
static int tape_running;

/* The tape's blocks, for the browser.
 *
 * Rebuilt only when something about them changes - the count, or which one is
 * current, or a command that touches the tape has run - because formatting a
 * long tape's descriptions is not work to do fifty times a second for a dialog
 * nobody has opened. Capped: a TZX can carry thousands of pulse blocks, and a
 * list nobody can scroll is no more use than a shorter one.
 */
#define MAX_TAPE_BLOCKS 128

static char tape_blocks[ MAX_TAPE_BLOCKS ][ 128 ];
static int tape_block_count;
static int tape_block_current = -1;

/* Set by the bridge when it runs anything that can change the tape, since two
   different tapes can have the same number of blocks and both be at the start. */
int androidstate_tape_changed;

/* Counting and formatting both walk the same list; which one this is doing
   depends on whether there is room left to write into. */
typedef struct block_walk {
  int count;
  int format;
} block_walk;

static void
count_block( libspectrum_tape_block *block, void *user_data )
{
  block_walk *walk = user_data;

  if( walk->format && walk->count < MAX_TAPE_BLOCKS ) {
    char type[ 64 ], details[ 96 ];

    type[0] = details[0] = '\0';
    libspectrum_tape_block_description( type, sizeof( type ), block );
    tape_block_details( details, sizeof( details ), block );

    if( details[0] ) {
      snprintf( tape_blocks[ walk->count ], sizeof( tape_blocks[0] ),
                "%d. %s: %s", walk->count + 1, type, details );
    } else {
      snprintf( tape_blocks[ walk->count ], sizeof( tape_blocks[0] ),
                "%d. %s", walk->count + 1, type );
    }
  }

  walk->count++;
}

static void
publish_tape_blocks( void )
{
  block_walk walk = { 0, 0 };
  int current = tape_get_current_block();

  if( !tape_on_machine ) {
    tape_block_count = 0;
    tape_block_current = -1;
    androidstate_tape_changed = 0;
    return;
  }

  tape_foreach( count_block, &walk );

  if( walk.count != tape_block_count || current != tape_block_current
      || androidstate_tape_changed ) {
    walk.count = 0;
    walk.format = 1;
    tape_foreach( count_block, &walk );

    tape_block_count = walk.count;
    tape_block_current = current;
    androidstate_tape_changed = 0;
  }
}

/* Drives with a disk in them, refreshed every pump for the UI thread.
   MAX_CONTROLLERS and MAX_DRIVES_PER_CONTROLLER are in android_internals.h,
   since the disk lamp walks the same controllers. */
#define MAX_DRIVES 16

typedef struct drive_entry {
  int controller;
  int drive;
  int loaded;
  int dirty;
  char name[ 48 ];
  char disk[ 96 ];			/* what is in it, if anything */
} drive_entry;

static drive_entry drive_list[ MAX_DRIVES ];
static int drive_list_count;

/* The DivMMC: whether Fuse has the interface, and the card in it. One name
   rather than a list because Fuse's own divmmc.c emulates one slot - "for now,
   we emulate only one card", as it says. */
static int card_interface;
static char card_file[ 96 ];

/* Copies everything the UI thread may read. Called once a frame from the
   pump, on the emulation thread. */
void
androidstate_publish( void )
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

  tape_on_machine = tape_present();
  tape_running = tape_is_playing();
  publish_tape_blocks();

  drive_list_count = 0;
  for( i = 0; i < MAX_CONTROLLERS && drive_list_count < MAX_DRIVES; i++ ) {
    int which;

    for( which = 0; which < MAX_DRIVES_PER_CONTROLLER &&
                    drive_list_count < MAX_DRIVES; which++ ) {
      ui_media_drive_info_t *found = ui_media_drive_find( i, which );
      drive_entry *entry = &drive_list[ drive_list_count ];
      const char *file;

      /* Every drive the running machine actually has, empty or not. Fuse
         knows which interfaces are present - a +3 has no Beta drives, a
         Pentagon no +3 ones - so ask rather than listing them all. */
      if( !found || !found->fdd ) continue;
      if( found->is_available && !found->is_available() ) continue;

      entry->controller = i;
      entry->drive = which;
      entry->loaded = found->fdd->loaded;
      entry->dirty = found->fdd->disk.dirty;

      snprintf( entry->name, sizeof( entry->name ), "%s",
                found->name ? found->name : "Disk" );

      file = found->fdd->loaded ? found->fdd->disk.filename : NULL;
      if( file ) {
        const char *base = strrchr( file, '/' );
        snprintf( entry->disk, sizeof( entry->disk ), "%s",
                  base ? base + 1 : file );
      } else if( found->fdd->loaded ) {
        /* A disk made here has no file behind it yet, but it is still in
           the drive and still worth saving. */
        snprintf( entry->disk, sizeof( entry->disk ), "%s", "Blank disk" );
      } else {
        entry->disk[0] = '\0';
      }

      drive_list_count++;
    }
  }

  card_interface = settings_current.divmmc_enabled;
  if( settings_current.divmmc_file ) {
    const char *base = strrchr( settings_current.divmmc_file, '/' );
    snprintf( card_file, sizeof( card_file ), "%s",
              base ? base + 1 : settings_current.divmmc_file );
  } else {
    card_file[0] = '\0';
  }

  /* Whether this machine could have a 7MHz turbo, which is not whether one is
     switched on: the quick bar offers the row only where there is something to
     offer, and the answer belongs to Fuse rather than to a list of machine
     names kept on the Java side that could drift from it. */
  machine_turbo_capable = machine_can_turbo();

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
free_strings( char **values, int count )
{
  int i;

  if( !values ) return;
  for( i = 0; i < count; i++ ) free( values[i] );
  free( values );
}

/* Builds a String[] out of copies this file made under the lock.
 *
 * Called with machine_mutex *not* held, on purpose. Every accessor below used
 * to allocate its Java objects inside the lock, which meant a GC triggered by
 * a NewStringUTF on the UI thread stalled the emulation thread on the same
 * mutex - and the emulation thread is the one that has to hand a frame to the
 * sound card on time. Not a deadlock, just the kind of pause that is heard
 * rather than seen. The snapshot under the lock is a few pointer copies; the
 * allocation happens after it.
 *
 * A null element is left null rather than abandoning the array: the caller
 * gets a String[] with a hole in it, which Java can see and reason about,
 * where a null array says only that something went wrong.
 */
static jobjectArray
strings_from( JNIEnv *env, char **values, int count )
{
  jclass string_class = (*env)->FindClass( env, "java/lang/String" );
  jobjectArray result;
  int i;

  if( !string_class ) return NULL;

  result = (*env)->NewObjectArray( env, count, string_class, NULL );
  (*env)->DeleteLocalRef( env, string_class );

  if( !result ) return NULL;

  for( i = 0; i < count; i++ ) {
    jstring value;

    if( !values[i] ) continue;

    value = (*env)->NewStringUTF( env, values[i] );
    if( !value ) continue;          /* out of memory: leave the hole */

    (*env)->SetObjectArrayElement( env, result, i, value );
    (*env)->DeleteLocalRef( env, value );
  }

  return result;
}

/* Builds a String[] from the machine snapshot; `names' picks which column. */
static jobjectArray
machine_strings( JNIEnv *env, int names )
{
  jobjectArray result;
  char **values;
  int i, count;

  pthread_mutex_lock( &machine_mutex );

  count = machine_list_count;
  values = count ? calloc( count, sizeof( char* ) ) : NULL;

  /* strdup and not the pointer: machine_list's buffers are static, so they
     cannot dangle, but they are rewritten in place when the list is rebuilt -
     and reading one while that happens gives half of each name. A copy is the
     only way the lock actually covers what it is being held for. */
  for( i = 0; values && i < count; i++ )
    values[i] = strdup( names ? machine_list[i].name : machine_list[i].id );

  pthread_mutex_unlock( &machine_mutex );

  if( count && !values ) return NULL;

  result = strings_from( env, values, count );
  free_strings( values, count );

  return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_ldlab_zedex_FuseNative_machineNames( JNIEnv *env, jclass class )
{
  return machine_strings( env, 1 );
}

JNIEXPORT jobjectArray JNICALL
Java_dev_ldlab_zedex_FuseNative_machineIds( JNIEnv *env, jclass class )
{
  return machine_strings( env, 0 );
}

JNIEXPORT jboolean JNICALL
Java_dev_ldlab_zedex_FuseNative_hasTape( JNIEnv *env, jclass class )
{
  jboolean present;

  pthread_mutex_lock( &machine_mutex );
  present = tape_on_machine ? JNI_TRUE : JNI_FALSE;
  pthread_mutex_unlock( &machine_mutex );

  return present;
}

/* The tape's blocks as the browser lists them, and which one is current. */
JNIEXPORT jobjectArray JNICALL
Java_dev_ldlab_zedex_FuseNative_tapeBlocks( JNIEnv *env, jclass class )
{
  jobjectArray result;
  char **values;
  int i, count;

  pthread_mutex_lock( &machine_mutex );

  count = tape_block_count;
  values = count ? calloc( count, sizeof( char* ) ) : NULL;

  for( i = 0; values && i < count; i++ )
    values[i] = tape_blocks[i] ? strdup( tape_blocks[i] ) : NULL;

  pthread_mutex_unlock( &machine_mutex );

  if( count && !values ) return NULL;

  result = strings_from( env, values, count );
  free_strings( values, count );

  return result;
}

JNIEXPORT jint JNICALL
Java_dev_ldlab_zedex_FuseNative_tapeBlock( JNIEnv *env, jclass class )
{
  jint current;

  pthread_mutex_lock( &machine_mutex );
  current = tape_block_current;
  pthread_mutex_unlock( &machine_mutex );

  return current;
}

/* Whether the deck is running, for a menu that has to say Play or Stop. */
JNIEXPORT jboolean JNICALL
Java_dev_ldlab_zedex_FuseNative_tapePlaying( JNIEnv *env, jclass class )
{
  jboolean playing;

  pthread_mutex_lock( &machine_mutex );
  playing = tape_running ? JNI_TRUE : JNI_FALSE;
  pthread_mutex_unlock( &machine_mutex );

  return playing;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_ldlab_zedex_FuseNative_driveNames( JNIEnv *env, jclass class )
{
  jobjectArray result;
  char **values;
  int i, count;

  pthread_mutex_lock( &machine_mutex );

  count = drive_list_count;
  values = count ? calloc( count, sizeof( char* ) ) : NULL;

  for( i = 0; values && i < count; i++ )
    values[i] = strdup( drive_list[i].name );

  pthread_mutex_unlock( &machine_mutex );

  if( count && !values ) return NULL;

  result = strings_from( env, values, count );
  free_strings( values, count );

  return result;
}

/* Controller in the high byte, drive in the low one. */
JNIEXPORT jintArray JNICALL
Java_dev_ldlab_zedex_FuseNative_driveIds( JNIEnv *env, jclass class )
{
  jintArray result;
  jint ids[ MAX_DRIVES ];
  int i;

  pthread_mutex_lock( &machine_mutex );

  for( i = 0; i < drive_list_count; i++ )
    ids[i] = ( drive_list[i].controller << 8 ) | drive_list[i].drive;

  result = (*env)->NewIntArray( env, drive_list_count );
  if( result )
    (*env)->SetIntArrayRegion( env, result, 0, drive_list_count, ids );

  pthread_mutex_unlock( &machine_mutex );

  return result;
}

/* "name", then the disk in it or "" if empty, then "1" or "0" for modified,
   three entries per drive. One call rather than three keeps the list
   consistent. */
JNIEXPORT jobjectArray JNICALL
Java_dev_ldlab_zedex_FuseNative_driveDetails( JNIEnv *env, jclass class )
{
  jobjectArray result;
  char **values;
  int i, count;

  pthread_mutex_lock( &machine_mutex );

  /* Three per drive: name, what is in it, and whether that has been written
     to. Flat rather than nested because a String[] needs no class lookup per
     row, and Java pulls them apart three at a time. */
  count = drive_list_count * 3;
  values = count ? calloc( count, sizeof( char* ) ) : NULL;

  for( i = 0; values && i < drive_list_count; i++ ) {
    values[i * 3]     = strdup( drive_list[i].name );
    values[i * 3 + 1] = strdup( drive_list[i].disk );
    values[i * 3 + 2] = strdup( drive_list[i].dirty ? "1" : "0" );
  }

  pthread_mutex_unlock( &machine_mutex );

  if( count && !values ) return NULL;

  result = strings_from( env, values, count );
  free_strings( values, count );

  return result;
}

/* The card in the DivMMC, or "" for an empty slot. The interface being out
   is a third state, and the menu wants to tell them apart: a card with no
   interface to read it is worth saying something about. */
JNIEXPORT jstring JNICALL
Java_dev_ldlab_zedex_FuseNative_cardName( JNIEnv *env, jclass class )
{
  jstring name;

  pthread_mutex_lock( &machine_mutex );
  name = (*env)->NewStringUTF( env, card_file );
  pthread_mutex_unlock( &machine_mutex );

  return name;
}

JNIEXPORT jboolean JNICALL
Java_dev_ldlab_zedex_FuseNative_hasDivmmc( JNIEnv *env, jclass class )
{
  jboolean present;

  pthread_mutex_lock( &machine_mutex );
  present = card_interface ? JNI_TRUE : JNI_FALSE;
  pthread_mutex_unlock( &machine_mutex );

  return present;
}

JNIEXPORT jboolean JNICALL
Java_dev_ldlab_zedex_FuseNative_canTurbo( JNIEnv *env, jclass class )
{
  jboolean can;

  pthread_mutex_lock( &machine_mutex );
  can = machine_turbo_capable ? JNI_TRUE : JNI_FALSE;
  pthread_mutex_unlock( &machine_mutex );

  return can;
}

JNIEXPORT jint JNICALL
Java_dev_ldlab_zedex_FuseNative_currentMachine( JNIEnv *env, jclass class )
{
  jint current;

  pthread_mutex_lock( &machine_mutex );
  current = machine_list_current;
  pthread_mutex_unlock( &machine_mutex );

  return current;
}
