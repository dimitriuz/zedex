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
static int tape_on_machine;
static int tape_running;

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
  int i;

  pthread_mutex_lock( &machine_mutex );

  result = (*env)->NewObjectArray( env, drive_list_count,
             (*env)->FindClass( env, "java/lang/String" ), NULL );

  for( i = 0; result && i < drive_list_count; i++ ) {
    jstring value = (*env)->NewStringUTF( env, drive_list[i].name );
    (*env)->SetObjectArrayElement( env, result, i, value );
    (*env)->DeleteLocalRef( env, value );
  }

  pthread_mutex_unlock( &machine_mutex );

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
  jclass string_class;
  int i;

  pthread_mutex_lock( &machine_mutex );

  string_class = (*env)->FindClass( env, "java/lang/String" );
  result = (*env)->NewObjectArray( env, drive_list_count * 3, string_class,
                                   NULL );

  for( i = 0; result && i < drive_list_count; i++ ) {
    jstring name = (*env)->NewStringUTF( env, drive_list[i].name );
    jstring disk = (*env)->NewStringUTF( env, drive_list[i].disk );
    jstring dirty = (*env)->NewStringUTF( env,
                      drive_list[i].dirty ? "1" : "0" );

    (*env)->SetObjectArrayElement( env, result, i * 3, name );
    (*env)->SetObjectArrayElement( env, result, i * 3 + 1, disk );
    (*env)->SetObjectArrayElement( env, result, i * 3 + 2, dirty );

    (*env)->DeleteLocalRef( env, name );
    (*env)->DeleteLocalRef( env, disk );
    (*env)->DeleteLocalRef( env, dirty );
  }

  pthread_mutex_unlock( &machine_mutex );

  return result;
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
