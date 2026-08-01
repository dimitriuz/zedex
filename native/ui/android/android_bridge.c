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
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <android/native_window_jni.h>

#include "android_internals.h"

/* Set here, cleared in android_state.c once the list is rebuilt. */
extern int androidstate_tape_changed;

#include "display.h"
#include "event.h"
#include "fuse.h"
#include "input.h"
#include "keyboard.h"
#include "machine.h"
#include "memory_pages.h"
#include "periph.h"
#include "rzx.h"
#include "settings.h"
#include "snapshot.h"
#include "tape.h"
#include "ui/ui.h"
#include "ui/uimedia.h"
#include "peripherals/disk/disk.h"
#include "peripherals/disk/fdd.h"
#include "peripherals/joystick.h"
#include "peripherals/kempmouse.h"
#include "utils.h"
#include "z80/z80.h"


/* Whether the app has asked for the Kempston mouse.
 *
 * Kept because a snapshot load unplugs it: snapshot.c calls
 * periph_disable_optional(), which zeroes every optional peripheral's setting,
 * and each module then puts itself back only if the snapshot recorded it as
 * active. An .sna cannot record it at all and most .szx files do not, so
 * loading a state silently took the mouse away - the lamp went on lighting,
 * because the port monitor is attached either way, and the pointer stopped
 * moving. That is the whole of the bug it fixes. */
static int mouse_wanted;

static void
restore_mouse( void )
{
  if( !mouse_wanted || settings_current.kempston_mouse ) return;

  settings_current.kempston_mouse = 1;
  periph_update();
}

/* And the Kempston joystick interface, which goes the same way and is worse
   when it does: the pad stops working *and* the game starts playing itself.
   Nothing answers port 31 with the interface gone, so the read comes back off
   the floating bus - mostly ones, and a one is a direction held down. Every
   direction at once and fire with it, which looks like the machine flying up
   and to the left on its own.
   The setting the snapshot cannot take away is joystick_1_output, so that is
   what is asked: if the pad is meant to come out as Kempston then the
   interface belongs plugged in, which is the same rule OPTION_JOYSTICK_TYPE
   applies when the choice is made. */
static void
restore_joystick( void )
{
  if( settings_current.joystick_1_output != JOYSTICK_TYPE_KEMPSTON ) return;
  if( settings_current.joy_kempston ) return;

  settings_current.joy_kempston = 1;
  periph_update();
}

/* The DivMMC is unplugged by the same thing for the same reason, and putting
   it back means the firmware as well as the setting - so that lives with the
   rest of the interface, in android_card.c. */
static void
restore_peripherals( void )
{
  restore_mouse();
  restore_joystick();
  androidcard_restore();
}


/* --- command queue ---------------------------------------------------- */

#define COMMAND_QUEUE_SIZE 256

typedef enum command_type {
  COMMAND_KEY,
  COMMAND_CHARACTER,			/* a typed character, not a keycode */				/* a: keycode, b: pressed */
  COMMAND_JOYSTICK,			/* a: joystick_button, b: pressed */
  COMMAND_SELECT_MACHINE,		/* a: index into machine_types */
  COMMAND_RESET,
  COMMAND_NMI,
  COMMAND_OPEN_FILE,			/* text: path to open */
  COMMAND_SET_OPTION,			/* a: option, b: value */
  COMMAND_SAVE_SNAPSHOT,		/* text: path to write */
  COMMAND_LOAD_SNAPSHOT,		/* text: path to read */
  COMMAND_SAVE_THUMBNAIL,		/* text: path to write */
  COMMAND_WRITE_TAPE,			/* text: path to write */
  COMMAND_NEW_TAPE,
  COMMAND_TAPE_PLAY,			/* a: 1 play, 0 stop */
  COMMAND_TAPE_REWIND,
  COMMAND_TAPE_BLOCK,			/* a: which block to wind to */
  COMMAND_MOUSE_MOVE,			/* a: dx, b: dy, in the mouse's own units */
  COMMAND_MOUSE_BUTTON,			/* a: 0 left 1 right, b: down */
  COMMAND_POKE,				/* a: address, b: value */
  COMMAND_WRITE_DISK,			/* a: controller, b: drive, text: path */
  COMMAND_DISK_INSERT,			/* a: controller, b: drive, text: path */
  COMMAND_DISK_NEW,			/* a: controller, b: drive */
  COMMAND_DISK_EJECT,			/* a: controller, b: drive */
  COMMAND_CARD_FIRMWARE,		/* text: an 8K DivMMC firmware to flash */
  COMMAND_CARD_INSERT,			/* text: path to a card image */
  COMMAND_CARD_COMMIT,
  COMMAND_CARD_EJECT,
} command_type;

/* Options the Android UI can set. Values are integers; booleans are 0 or 1. */
enum {
  OPTION_LOADER_ACCELERATION,		/* 0 none, 1 safe, 2 turbo */
  OPTION_TAPE_SOUND,
  OPTION_AUTOLOAD,
  OPTION_ISSUE2,
  OPTION_BW_TV,
  OPTION_SPEED,				/* per cent */
  OPTION_SOUND,
  OPTION_AY_VOLUME,			/* 0 - 100 */
  OPTION_BEEPER_VOLUME,			/* 0 - 100 */
  OPTION_JOYSTICK_TYPE,			/* a joystick_type_t */
  OPTION_DETECT_LOADER,
  OPTION_AY_STEREO,			/* 0 none, 1 ACB, 2 ABC */
  OPTION_FILTER_SCANLINES,		/* on or off */
  OPTION_FILTER_CRT,			/* on or off */
  OPTION_FILTER_VIDEO,			/* 0 RGB, 1 composite, 2 RF */
  OPTION_FILTER_SHARPNESS,		/* the rest are 0 - 100 */
  OPTION_FILTER_SCANLINE,
  OPTION_FILTER_CURVE,
  OPTION_FILTER_MASK,
  OPTION_FILTER_GLOW,
  OPTION_FILTER_BLEED,
  OPTION_FILTER_NOISE,
  OPTION_SCALE,				/* 0 fits, else pixels per pixel */
  OPTION_BORDER,			/* 0 all of it, 1 a quarter, 2 none */
  OPTION_KEMPSTON_MOUSE,
  OPTION_DIVMMC,			/* the interface, not the card */
};

/* The filters' shape, kept here because the settings arrive one at a time and
   the renderer wants them together. Defaults match android_gl.c's. */
static android_filter filter = {
  0, 0, 0,				/* scanlines, crt, video */
  100, 50, 40, 40, 30, 50, 20,
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

/* A character typed on the system keyboard, which arrives as a character and
   not as a keycode - an Android IME commits text rather than pressing keys.

   No keysym translation, because there is nothing to translate: Fuse's
   input_key values *are* ASCII for everything printable, and its own
   keysyms_map turns one into the Spectrum keys it takes - a colon is SYMBOL
   SHIFT and Z, and Fuse knows that already. Skipping keysyms_remap is the whole
   trick, and it is why punctuation comes out right without a table of our own. */
static void
run_character( int character, int pressed )
{
  input_event_t fuse_event;

  if( character <= 0 || character > 0xff ) return;

  fuse_event.type = pressed ? INPUT_EVENT_KEYPRESS : INPUT_EVENT_KEYRELEASE;
  fuse_event.types.key.native_key = character;
  fuse_event.types.key.spectrum_key = character;

  input_event( &fuse_event );
}

/* The on-screen joystick is joystick 1, so it comes out as whichever
   interface settings_current.joystick_1_output names - which is what the
   menu chooses. joystick_press() is Fuse's own entry point for a moved
   stick; input_event() is deliberately not used, because for a joystick it
   also routes presses into the widget UI's dialog navigation and turns fire
   button 2 into "open the menu", neither of which a five-control pad on a
   touchscreen wants. */
static void
run_joystick( int button, int pressed )
{
  if( button < JOYSTICK_BUTTON_LEFT || button > JOYSTICK_BUTTON_FIRE ) return;

  joystick_press( 0, button, pressed );
}


static void
run_select_machine( int index )
{
  if( index < 0 || index >= machine_count ) return;

  android_log( "selecting machine %s", machine_types[ index ]->id );
  machine_select( machine_types[ index ]->machine );
}

/* Keys and joystick directions pressed during the current pump, so their
   release can be held over to the next one. The Spectrum ROM only scans the
   keyboard once per frame, so a press and release arriving together - which
   is what a synthesised tap or a very fast finger produces - would otherwise
   never be seen at all. The joystick needs it just as much: the Cursor and
   Sinclair interfaces are keys, and a Kempston port is read no more often. */
static int pressed_this_pump[ 16 ];
static int pressed_count;

/* One namespace for all of them, so a direction cannot be mistaken for a
   keycode nor a character for either. */
static int
press_tag( command_type type, int code )
{
  switch( type ) {
  case COMMAND_JOYSTICK:  return 0x10000 | code;
  case COMMAND_CHARACTER: return 0x20000 | code;
  default:                return code;
  }
}

static int
pressed_during_this_pump( int tag )
{
  int i;

  for( i = 0; i < pressed_count; i++ )
    if( pressed_this_pump[i] == tag ) return 1;

  return 0;
}

/* Sound settings are only read when the sound subsystem starts, so changing
   one means restarting it - which is what Fuse's own options dialogs do. */
static void
restart_sound( void )
{
  fuse_emulation_pause();
  fuse_emulation_unpause();
}

static void
run_set_option( int option, int value )
{
  android_log( "option %d = %d", option, value );

  switch( option ) {

  case OPTION_LOADER_ACCELERATION:
    /* Three of Fuse's settings, in three useful combinations. Traps catch the
       ROM's loading routine and fastload makes a trapped block appear at once,
       which between them cover every tape that loads the standard way;
       accelerate_loader goes further and skips the timing loops of custom
       loaders that never call the ROM, which is the part that can occasionally
       defeat one. So the middle setting is the one to fall back to when a tape
       will not load, and it is still nothing like real time.

       The same three levels as Spectacol, the other Fuse-based Android port,
       and for the same reason. */
    settings_current.tape_traps = value > 0;
    settings_current.fastload = value > 0;
    settings_current.accelerate_loader = value > 1;
    break;

  case OPTION_FILTER_SCANLINES:
  case OPTION_FILTER_CRT:
  case OPTION_FILTER_VIDEO:
  case OPTION_FILTER_SHARPNESS:
  case OPTION_FILTER_SCANLINE:
  case OPTION_FILTER_CURVE:
  case OPTION_FILTER_MASK:
  case OPTION_FILTER_GLOW:
  case OPTION_FILTER_BLEED:
  case OPTION_FILTER_NOISE:
    switch( option ) {
    case OPTION_FILTER_SCANLINES: filter.scanlines = value; break;
    case OPTION_FILTER_CRT:       filter.crt = value; break;
    case OPTION_FILTER_VIDEO:     filter.video = value; break;
    case OPTION_FILTER_SHARPNESS: filter.sharpness = value; break;
    case OPTION_FILTER_SCANLINE:  filter.scanline = value; break;
    case OPTION_FILTER_CURVE:     filter.curve = value; break;
    case OPTION_FILTER_MASK:      filter.mask = value; break;
    case OPTION_FILTER_GLOW:      filter.glow = value; break;
    case OPTION_FILTER_BLEED:     filter.bleed = value; break;
    case OPTION_FILTER_NOISE:     filter.noise = value; break;
    }

    /* Safe from here: this runs on the emulation thread, which is the one that
       owns the GL context. */
    androidgl_set_filter( &filter );
    break;

  case OPTION_SCALE:
    androidgl_set_scale( value );
    break;

  case OPTION_BORDER:
    androidgl_set_border( value );
    break;

  case OPTION_KEMPSTON_MOUSE:
    /* Plugging an interface in is a change of hardware, so periph_update() has
       to be told; the peripheral itself points at this setting. It is only
       plugged when it is asked for, since it answers three ports a game might
       otherwise read for something else. */
    mouse_wanted = value;
    settings_current.kempston_mouse = value;
    periph_update();
    break;

  case OPTION_DIVMMC:
    /* Hardware again, and rather more of it than a mouse: the interface has
       its own memory and its own firmware, and the machine resets around it.
       android_card.c has the whole of that. */
    androidcard_set_enabled( value );
    break;

  case OPTION_AY_STEREO: {
    /* Fuse takes this as one of its own three words and matches it with
       strcmp, so anything else silently means None. It reads it when the sound
       subsystem starts, which is why this restarts it - and turning it on
       makes Fuse's output two channels rather than one, which the AAudio
       driver asks the device for and falls back from if it cannot have it.

       settings_set_string because the setting owns its copy: assigning a
       literal would leave Fuse freeing static storage later. */
    static const char * const names[] = { "None", "ACB", "ABC" };

    if( value < 0 || value > 2 ) break;

    settings_set_string( &settings_current.stereo_ay, names[ value ] );
    restart_sound();
    break;
  }

  case OPTION_DETECT_LOADER:
    /* Watches the ULA for the pattern of a loader polling it and starts the
       tape when it sees one - and stops it when the loader stops asking. */
    settings_current.detect_loader = value;
    break;

  case OPTION_TAPE_SOUND:
    settings_current.sound_load = value;
    break;

  case OPTION_AUTOLOAD:
    settings_current.auto_load = value;
    break;

  case OPTION_ISSUE2:
    settings_current.issue2 = value;
    break;

  case OPTION_BW_TV:
    settings_current.bw_tv = value;
    display_refresh_all();
    break;

  case OPTION_SPEED:
    /* The speed reaches the machine through the sound, which in this port is
       also the clock: Fuse scales the blip buffer's clock rate by
       sound_get_effective_processor_speed() and reads that once, in
       sound_init(). So setting the number alone did nothing at all - the
       emulation kept running at whatever rate the audio device was consuming
       samples - and this setting has never worked without the restart.

       Past 300% Fuse switches the sound off itself, since its own range for
       having any is 50 to 300, and then timer.c does the throttling and reads
       the speed live. Which is why fast forward is silent, and should be. */
    settings_current.emulation_speed = value;
    restart_sound();
    break;

  case OPTION_SOUND:
    settings_current.sound = value;
    restart_sound();
    break;

  case OPTION_AY_VOLUME:
    settings_current.volume_ay = value;
    restart_sound();
    break;

  case OPTION_BEEPER_VOLUME:
    settings_current.volume_beeper = value;
    restart_sound();
    break;

  case OPTION_JOYSTICK_TYPE:
    if( value < JOYSTICK_TYPE_NONE || value >= JOYSTICK_TYPE_COUNT ) break;

    settings_current.joystick_1_output = value;

    /* Kempston is the one type that is also a piece of hardware: without
       the interface plugged in, nothing decodes the port and the game reads
       a stick that is not there. Fuse keeps the two apart because a real
       setup can have the interface without using it; here choosing the type
       is the whole of the user's intent, so the interface follows it.
       periph_posthook() is what makes the change take effect, exactly as in
       Fuse's own options dialogs. */
    settings_current.joy_kempston = value == JOYSTICK_TYPE_KEMPSTON;
    periph_posthook();
    break;
  }
}

/* Whether the app wants the machine stopped. Written by the UI thread, read
   here; one word, so it needs nothing more than being volatile. */
static volatile int pause_wanted;

static void
drain_commands( void )
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

    /* Leave this release for the next frame, and everything behind it with
       it: the queue has to stay in order. */
    if( ( command.type == COMMAND_KEY || command.type == COMMAND_JOYSTICK ||
          command.type == COMMAND_CHARACTER ) &&
        !command.b &&
        pressed_during_this_pump( press_tag( command.type, command.a ) ) ) {
      pthread_mutex_unlock( &command_mutex );
      break;
    }

    command_head = ( command_head + 1 ) % COMMAND_QUEUE_SIZE;
    pthread_mutex_unlock( &command_mutex );

    switch( command.type ) {
    case COMMAND_KEY:
    case COMMAND_JOYSTICK:
    case COMMAND_CHARACTER:
      if( command.type == COMMAND_KEY )           run_key( command.a, command.b );
      else if( command.type == COMMAND_CHARACTER ) run_character( command.a,
                                                                 command.b );
      else                                        run_joystick( command.a,
                                                                command.b );

      if( command.b && pressed_count < 16 )
        pressed_this_pump[ pressed_count++ ] =
          press_tag( command.type, command.a );
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
      /* Hard when the DivMMC is in, because a soft reset keeps its MAPRAM bit
         set and the machine then restarts out of the interface's RAM rather
         than its EPROM - which leaves esxDOS running but unable to reach the
         card. See androidcard_insert(). There is only one Reset in this app's
         menu, and it has to be the one that works. */
      machine_reset( settings_current.divmmc_enabled );
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
      /* A snapshot among them unplugs the optional peripherals. */
      restore_peripherals();
      /* Whatever it was, it may have been a tape: two different ones can have
         the same number of blocks and both be at the start, so the browser's
         list cannot tell by looking. */
      androidstate_tape_changed = 1;
      break;
    case COMMAND_SET_OPTION:
      run_set_option( command.a, command.b );
      break;
    case COMMAND_SAVE_SNAPSHOT:
      /* Run between frames on the emulation thread, so the machine is in a
         consistent state; libspectrum picks the format from the extension. */
      android_log( "saving snapshot %s", command.text ? command.text : "" );
      if( command.text ) snapshot_write( command.text );
      break;
    case COMMAND_LOAD_SNAPSHOT:
      android_log( "loading snapshot %s", command.text ? command.text : "" );
      if( command.text ) snapshot_read( command.text );
      restore_peripherals();
      break;
    case COMMAND_SAVE_THUMBNAIL:
      if( command.text ) androiddisplay_write_thumbnail( command.text );
      break;
    case COMMAND_WRITE_TAPE:
      /* Whatever the machine has SAVEd - Fuse's tape traps catch the ROM's
         save routine and append each block to the tape in memory - plus
         anything already on it. The extension picks the format. */
      android_log( "writing tape %s", command.text ? command.text : "" );
      if( command.text ) tape_write( command.text );
      break;
    case COMMAND_WRITE_DISK: {
      ui_media_drive_info_t *target = ui_media_drive_find( command.a, command.b );

      android_log( "writing disk %s", command.text ? command.text : "" );

      if( target && target->fdd && command.text ) {
        /* Clearing the type lets disk_write pick the format from the
           extension, which is what Fuse's own save-as does. */
        target->fdd->disk.type = DISK_TYPE_NONE;

        struct stat written;
        int failed = disk_write( &target->fdd->disk, command.text );

        /* A disk made here is unformatted until the machine formats it, and
           an unformatted disk has nothing to write. Fuse reports that either
           as an error or as a write of nothing at all, and leaves an empty
           file behind either way. */
        if( !failed && !stat( command.text, &written ) &&
            written.st_size == 0 )
          failed = 1;

        if( failed ) {
          remove( command.text );
          ui_error( UI_ERROR_ERROR,
                    "Couldn't write the disk. A new disk has to be formatted "
                    "by the machine before there is anything to save." );
        } else {
          target->fdd->disk.dirty = 0;
        }
      }
      break;
    }
    case COMMAND_DISK_INSERT:
    case COMMAND_DISK_NEW: {
      ui_media_drive_info_t *target = ui_media_drive_find( command.a, command.b );

      if( target && target->fdd ) {
        /* Android has already asked about losing changes; clearing this
           stops Fuse asking again through a modal of its own. */
        target->fdd->disk.dirty = 0;
        ui_media_drive_insert( target,
                               command.type == COMMAND_DISK_INSERT
                                 ? command.text : NULL,
                               0 );
      }
      break;
    }
    case COMMAND_DISK_EJECT: {
      ui_media_drive_info_t *target = ui_media_drive_find( command.a, command.b );

      if( target && target->fdd ) target->fdd->disk.dirty = 0;
      ui_media_drive_eject( command.a, command.b );
      break;
    }

    case COMMAND_CARD_FIRMWARE:
      if( command.text ) androidcard_load_firmware( command.text );
      break;

    case COMMAND_CARD_INSERT:
      if( command.text ) androidcard_insert( command.text );
      break;

    case COMMAND_CARD_COMMIT:
      androidcard_commit();
      break;

    case COMMAND_CARD_EJECT:
      androidcard_eject();
      break;
    case COMMAND_TAPE_PLAY:
      /* Not tape_toggle_play(): the app knows which way round it is from the
         published state, and a toggle would flip whichever way the tape
         happened to be by the time this ran. autoplay is 0 because a finger
         asked - Fuse uses it to tell its own automatic plays apart. */
      if( command.a ) tape_do_play( 0 ); else tape_stop();
      break;

    case COMMAND_TAPE_REWIND:
      tape_rewind();
      break;

    case COMMAND_TAPE_BLOCK:
      tape_select_block( command.a );
      break;

    /* The mouse is relative and Fuse takes it that way: kempmouse keeps the
       counters and negates y itself, so what goes in is the movement in screen
       terms - down is positive - and nothing here has to know where any pointer
       is. Fuse's own ui.c is bypassed on purpose: its mouse path is gated on a
       grab that belongs to a desktop with a cursor to capture. */
    case COMMAND_MOUSE_MOVE:
      kempmouse_update( command.a, command.b, -1, 0 );
      break;

    /* One byte into the sixteen bit space as it is paged right now, which is
       what POKE means and what Fuse's own poke code does for a poke with no bank
       of its own - see poke_apply() in pokefinder/pokemem.c. writebyte_internal
       rather than writebyte: this is a debugger's write, not the machine's, and
       it has no business waiting for contention or telling the ULA about it. */
    case COMMAND_POKE:
      if( command.a >= 0 && command.a <= 0xffff &&
          command.b >= 0 && command.b <= 0xff ) {
        writebyte_internal( command.a, command.b );
      }
      break;

    case COMMAND_MOUSE_BUTTON: {
      /* Fuse's numbering, from ui_mouse_button(): the left button is bit one
         and the right bit zero, unless the swap setting says otherwise. */
      int left = !settings_current.mouse_swap_buttons;

      kempmouse_update( 0, 0, command.a == 0 ? left : !left, command.b );
      break;
    }

    case COMMAND_NEW_TAPE:
      /* Android has asked already; clearing the flag stops Fuse asking
         again through a modal of its own. */
      tape_modified = 0;
      tape_close();
      androidstate_tape_changed = 1;
      break;
    }

    free( command.text );
  }
}

/* Nothing runs while paused: no opcodes, no events, no sound.

   But the loop cannot simply block, because this is the only thread that ever
   calls androidbridge_present(), and that is where the window handover
   happens - surfaceDestroyed() waits for it. Blocking here would deadlock the
   moment Android took the window away, which is precisely when the app pauses
   itself. So the last frame is handed over again and again instead: it costs a
   texture upload every sixteen milliseconds, it keeps the handover working,
   and it redraws the paused picture after a rotation for free.

   fuse_emulation_pause() is Fuse's own, and stops the sound - which for this
   port is also the clock, so the emulation thread would otherwise sit in a
   blocking AAudio write. It counts, so the pairing matters. */
static void
run_while_paused( void )
{
  fuse_emulation_pause();
  androidstatus_idle();

  /* Pausing is where the app goes when Android takes it away, and after that
     the process can be killed without another word. Anything the machine wrote
     to the card goes to the file now rather than on the next tick. */
  androidcard_commit();

  while( pause_wanted && !fuse_exiting ) {
    int width, height;
    const void *pixels = androiddisplay_last_frame( &width, &height );

    drain_commands();
    if( pixels ) androidbridge_present( pixels, width, height );

    /* A window to draw into means somebody is looking at the paused picture:
       stay at a frame's pace, so a rotation redraws at once and
       surfaceDestroyed() is answered before it has to time out. With no window
       - the device asleep, or the app behind something - there is nothing to
       redraw and nothing to hand back, so the same sixty wakeups a second would
       be spent keeping the CPU out of its deep idle for no reason at all. A
       quarter of a second instead, which is still soon enough that coming back
       is not something you can see happen. */
    usleep( androidbridge_has_window() ? 16000 : 250000 );
  }

  fuse_emulation_unpause();
}

void
androidbridge_pump_commands( void )
{
  /* Once a frame, and on the emulation thread, which is what the lamps need. */
  androidstatus_frame();

  /* Same place for the same reason: the card is written back on a count of
     frames, and this is the once-a-frame that the emulation thread reaches. */
  androidcard_tick();

  drain_commands();

  if( pause_wanted ) run_while_paused();

  androidstate_publish();
}


/* --- reporting errors to Android -------------------------------------- */

static JavaVM *java_vm;
static jclass native_class;
static jmethodID on_error_method;
static jmethodID on_frame_method;
static jmethodID on_screenshot_method;

/* Read every frame by the emulation thread, written by the UI thread. */
static volatile int recording;
static volatile int screenshot_wanted;

/* The emulation thread is a plain pthread, so it has to be attached before
   it can call back into Java. It runs for the life of the process, so there
   is nothing to detach. */
static JNIEnv *
attached_env( void )
{
  JNIEnv *env;

  if( !java_vm ) return NULL;

  if( (*java_vm)->GetEnv( java_vm, (void**) &env,
                          JNI_VERSION_1_6 ) != JNI_OK ) {
    if( (*java_vm)->AttachCurrentThread( java_vm, &env, NULL ) != JNI_OK )
      return NULL;
  }

  return env;
}

JNIEXPORT jint JNICALL
JNI_OnLoad( JavaVM *vm, void *reserved )
{
  JNIEnv *env;
  jclass local;

  java_vm = vm;

  if( (*vm)->GetEnv( vm, (void**) &env, JNI_VERSION_1_6 ) != JNI_OK )
    return JNI_VERSION_1_6;

  local = (*env)->FindClass( env, "dev/ldlab/zedex/FuseNative" );
  if( local ) {
    native_class = (*env)->NewGlobalRef( env, local );
    on_error_method = (*env)->GetStaticMethodID( env, native_class, "onError",
                                                 "(ILjava/lang/String;)V" );
    on_frame_method = (*env)->GetStaticMethodID( env, native_class, "onFrame",
                                                 "(II)V" );
    on_screenshot_method = (*env)->GetStaticMethodID( env, native_class,
                                                      "onScreenshot", "(II)V" );
    (*env)->DeleteLocalRef( env, local );
  }

  return JNI_VERSION_1_6;
}

void
androidbridge_report_error( int severity, const char *message )
{
  JNIEnv *env;
  jstring text;

  android_logw( "fuse: %s", message ? message : "" );

  if( !native_class || !on_error_method || !message ) return;

  env = attached_env();
  if( !env ) return;

  text = (*env)->NewStringUTF( env, message );
  if( !text ) return;

  (*env)->CallStaticVoidMethod( env, native_class, on_error_method,
                                (jint) severity, text );
  (*env)->DeleteLocalRef( env, text );
}

/* --- handing frames to Android ---------------------------------------- */

/* Called for every frame Fuse draws, so the common case - nobody watching -
   has to cost nothing. The Java side reads the pixels out of the buffer
   published by frameBuffer() before this returns, which is safe because the
   emulation thread is here rather than drawing the next frame. */
void
androidbridge_frame_ready( int width, int height )
{
  JNIEnv *env;

  if( !recording && !screenshot_wanted ) return;
  if( !native_class ) return;

  env = attached_env();
  if( !env ) return;

  if( screenshot_wanted ) {
    screenshot_wanted = 0;
    if( on_screenshot_method )
      (*env)->CallStaticVoidMethod( env, native_class, on_screenshot_method,
                                    (jint) width, (jint) height );
  }

  if( recording && on_frame_method )
    (*env)->CallStaticVoidMethod( env, native_class, on_frame_method,
                                  (jint) width, (jint) height );
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

/* Fuse looks for a ROM in the current directory before anywhere else, so
   pointing that at the user's roms folder is all it takes to find them. Must
   be called before the emulation thread starts. */
JNIEXPORT jboolean JNICALL
Java_dev_ldlab_zedex_FuseNative_setWorkingDirectory( JNIEnv *env, jclass class,
                                                    jstring path )
{
  const char *utf = (*env)->GetStringUTFChars( env, path, NULL );
  int ok;

  if( !utf ) return JNI_FALSE;

  ok = chdir( utf ) == 0;
  if( !ok ) android_logw( "cannot use %s as the working directory", utf );

  (*env)->ReleaseStringUTFChars( env, path, utf );

  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_start( JNIEnv *env, jclass class,
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
Java_dev_ldlab_zedex_FuseNative_key( JNIEnv *env, jclass class, jint keycode,
                                    jboolean pressed )
{
  queue_command( COMMAND_KEY, keycode, pressed ? 1 : 0 );
}

/* A character from the system keyboard; see run_character(). */
JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_character( JNIEnv *env, jclass class,
                                         jint character, jboolean pressed )
{
  queue_command( COMMAND_CHARACTER, character, pressed ? 1 : 0 );
}

/* Whether the Spectrum has any use for this key at all.

   The activity has to know before it decides to swallow a key event: volume,
   media and the rest belong to the phone, and consuming them so that Fuse can
   ignore them is how the volume buttons stopped working.

   Walks keysyms_map rather than calling keysyms_remap, for two reasons: the
   hash table behind that is not built until Fuse has initialised, and this can
   be asked before there is a machine - or when there is no ROM and there never
   will be one. A read of a static table is also safe from the UI thread, which
   nothing else here is. */
JNIEXPORT jboolean JNICALL
Java_dev_ldlab_zedex_FuseNative_mapsKey( JNIEnv *env, jclass class, jint keycode )
{
  const keysyms_map_t *ptr;

  for( ptr = keysyms_map; ptr->ui; ptr++ )
    if( ptr->ui == (libspectrum_dword) keycode ) return JNI_TRUE;

  return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_joystick( JNIEnv *env, jclass class,
                                          jint button, jboolean pressed )
{
  queue_command( COMMAND_JOYSTICK, button, pressed ? 1 : 0 );
}

/* Stops the machine, or lets it go again. Not queued: the emulation thread has
   to be able to see this while it is sitting in the paused loop, and a command
   in the queue is only read between frames. */
JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setPaused( JNIEnv *env, jclass class,
                                          jboolean paused )
{
  pause_wanted = paused ? 1 : 0;
}

/* What the machine is busy with, as ACTIVITY_* bits. A plain read of one word
   the emulation thread publishes; nothing is queued and nothing blocks, which
   is what lets the app poll it while a frame is being drawn. */
JNIEXPORT jint JNICALL
Java_dev_ldlab_zedex_FuseNative_activity( JNIEnv *env, jclass class )
{
  return androidstatus_activity();
}

/* How loud each of the AY's three channels is, for the meter the app draws in
   place of a single lamp. Three bytes, A in the bottom. */
JNIEXPORT jint JNICALL
Java_dev_ldlab_zedex_FuseNative_ayLevels( JNIEnv *env, jclass class )
{
  return androidstatus_ay_levels();
}

/* Fuse's own names for the interfaces it can pretend to be, in the order of
   joystick_type_t, so the index is the value. A plain table with no state
   behind it: it can be read before the emulation thread has started. */
JNIEXPORT jobjectArray JNICALL
Java_dev_ldlab_zedex_FuseNative_joystickTypeNames( JNIEnv *env, jclass class )
{
  jobjectArray result;
  int i;

  result = (*env)->NewObjectArray( env, JOYSTICK_TYPE_COUNT,
             (*env)->FindClass( env, "java/lang/String" ), NULL );

  for( i = 0; result && i < JOYSTICK_TYPE_COUNT; i++ ) {
    jstring value = (*env)->NewStringUTF( env, joystick_name[i] );
    (*env)->SetObjectArrayElement( env, result, i, value );
    (*env)->DeleteLocalRef( env, value );
  }

  return result;
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setJoystickType( JNIEnv *env, jclass class,
                                                 jint type )
{
  queue_command( COMMAND_SET_OPTION, OPTION_JOYSTICK_TYPE, type );
}








JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_insertDisk( JNIEnv *env, jclass class,
                                           jint controller, jint drive,
                                           jstring path )
{
  const char *utf = (*env)->GetStringUTFChars( env, path, NULL );

  if( utf ) {
    queue_command_text( COMMAND_DISK_INSERT, controller, drive, strdup( utf ) );
    (*env)->ReleaseStringUTFChars( env, path, utf );
  }
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_newDisk( JNIEnv *env, jclass class,
                                        jint controller, jint drive )
{
  queue_command( COMMAND_DISK_NEW, controller, drive );
}

/* --- the DivMMC ------------------------------------------------------- */

/* The interface, the firmware and the card, in that order: the firmware has to
   be in hand before the interface goes in - see androidcard_set_enabled() -
   and the queue keeps them in the order they were asked for. */

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setDivmmc( JNIEnv *env, jclass class,
                                          jboolean on )
{
  queue_command( COMMAND_SET_OPTION, OPTION_DIVMMC, on ? 1 : 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_loadDivmmcFirmware( JNIEnv *env, jclass class,
                                                   jstring path )
{
  const char *utf = (*env)->GetStringUTFChars( env, path, NULL );

  if( utf ) {
    queue_command_text( COMMAND_CARD_FIRMWARE, 0, 0, strdup( utf ) );
    (*env)->ReleaseStringUTFChars( env, path, utf );
  }
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_insertCard( JNIEnv *env, jclass class,
                                           jstring path )
{
  const char *utf = (*env)->GetStringUTFChars( env, path, NULL );

  if( utf ) {
    queue_command_text( COMMAND_CARD_INSERT, 0, 0, strdup( utf ) );
    (*env)->ReleaseStringUTFChars( env, path, utf );
  }
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_commitCard( JNIEnv *env, jclass class )
{
  queue_command( COMMAND_CARD_COMMIT, 0, 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_ejectCard( JNIEnv *env, jclass class )
{
  queue_command( COMMAND_CARD_EJECT, 0, 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_ejectDisk( JNIEnv *env, jclass class,
                                          jint controller, jint drive )
{
  queue_command( COMMAND_DISK_EJECT, controller, drive );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_writeDisk( JNIEnv *env, jclass class,
                                          jint controller, jint drive,
                                          jstring path )
{
  const char *utf = (*env)->GetStringUTFChars( env, path, NULL );

  if( utf ) {
    queue_command_text( COMMAND_WRITE_DISK, controller, drive, strdup( utf ) );
    (*env)->ReleaseStringUTFChars( env, path, utf );
  }
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_writeTape( JNIEnv *env, jclass class,
                                          jstring path )
{
  const char *utf = (*env)->GetStringUTFChars( env, path, NULL );

  if( utf ) {
    queue_command_text( COMMAND_WRITE_TAPE, 0, 0, strdup( utf ) );
    (*env)->ReleaseStringUTFChars( env, path, utf );
  }
}

/* The tape deck's transport; see COMMAND_TAPE_PLAY. */
JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_tapePlay( JNIEnv *env, jclass class,
                                         jboolean playing )
{
  queue_command( COMMAND_TAPE_PLAY, playing ? 1 : 0, 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_tapeRewind( JNIEnv *env, jclass class )
{
  queue_command( COMMAND_TAPE_REWIND, 0, 0 );
}

/* Winds to one of the blocks the browser listed. */
JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_tapeBlockSelect( JNIEnv *env, jclass class,
                                                jint block )
{
  queue_command( COMMAND_TAPE_BLOCK, block, 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_newTape( JNIEnv *env, jclass class )
{
  queue_command( COMMAND_NEW_TAPE, 0, 0 );
}


JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_selectMachine( JNIEnv *env, jclass class,
                                              jint index )
{
  queue_command( COMMAND_SELECT_MACHINE, index, 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_reset( JNIEnv *env, jclass class )
{
  queue_command( COMMAND_RESET, 0, 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_nmi( JNIEnv *env, jclass class )
{
  queue_command( COMMAND_NMI, 0, 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setLoaderAcceleration( JNIEnv *env, jclass class,
                                                       jint level )
{
  queue_command( COMMAND_SET_OPTION, OPTION_LOADER_ACCELERATION, level );
}

/* The picture filter, one number at a time; see OPTION_FILTER. */
JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setFilter( JNIEnv *env, jclass class,
                                          jint which, jint value )
{
  queue_command( COMMAND_SET_OPTION, OPTION_FILTER_SCANLINES + which, value );
}

/* How big the picture is drawn; see OPTION_SCALE. */
JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setScale( JNIEnv *env, jclass class,
                                         jint pixels )
{
  queue_command( COMMAND_SET_OPTION, OPTION_SCALE, pixels );
}

/* One byte of memory; see COMMAND_POKE. */
JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_poke( JNIEnv *env, jclass class,
                                     jint address, jint value )
{
  queue_command( COMMAND_POKE, address, value );
}

/* The Kempston mouse: whether it is plugged in, and what it is doing. */
JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setKempstonMouse( JNIEnv *env, jclass class,
                                                 jboolean on )
{
  queue_command( COMMAND_SET_OPTION, OPTION_KEMPSTON_MOUSE, on ? 1 : 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_mouseMove( JNIEnv *env, jclass class,
                                          jint dx, jint dy )
{
  queue_command( COMMAND_MOUSE_MOVE, dx, dy );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_mouseButton( JNIEnv *env, jclass class,
                                            jint which, jboolean down )
{
  queue_command( COMMAND_MOUSE_BUTTON, which, down ? 1 : 0 );
}

/* How much of the border is shown; see OPTION_BORDER. */
JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setBorder( JNIEnv *env, jclass class,
                                          jint border )
{
  queue_command( COMMAND_SET_OPTION, OPTION_BORDER, border );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setAyStereo( JNIEnv *env, jclass class,
                                            jint separation )
{
  queue_command( COMMAND_SET_OPTION, OPTION_AY_STEREO, separation );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setDetectLoader( JNIEnv *env, jclass class,
                                                 jboolean on )
{
  queue_command( COMMAND_SET_OPTION, OPTION_DETECT_LOADER, on ? 1 : 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setTapeSound( JNIEnv *env, jclass class,
                                             jboolean on )
{
  queue_command( COMMAND_SET_OPTION, OPTION_TAPE_SOUND, on ? 1 : 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setAutoLoad( JNIEnv *env, jclass class, jboolean on )
{
  queue_command( COMMAND_SET_OPTION, OPTION_AUTOLOAD, on ? 1 : 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setIssue2( JNIEnv *env, jclass class, jboolean on )
{
  queue_command( COMMAND_SET_OPTION, OPTION_ISSUE2, on ? 1 : 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setBlackAndWhite( JNIEnv *env, jclass class, jboolean on )
{
  queue_command( COMMAND_SET_OPTION, OPTION_BW_TV, on ? 1 : 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setSound( JNIEnv *env, jclass class, jboolean on )
{
  queue_command( COMMAND_SET_OPTION, OPTION_SOUND, on ? 1 : 0 );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setSpeed( JNIEnv *env, jclass class, jint value )
{
  queue_command( COMMAND_SET_OPTION, OPTION_SPEED, value );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setAyVolume( JNIEnv *env, jclass class, jint value )
{
  queue_command( COMMAND_SET_OPTION, OPTION_AY_VOLUME, value );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setBeeperVolume( JNIEnv *env, jclass class, jint value )
{
  queue_command( COMMAND_SET_OPTION, OPTION_BEEPER_VOLUME, value );
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_saveSnapshot( JNIEnv *env, jclass class,
                                             jstring path )
{
  const char *utf = (*env)->GetStringUTFChars( env, path, NULL );

  if( utf ) {
    queue_command_text( COMMAND_SAVE_SNAPSHOT, 0, 0, strdup( utf ) );
    (*env)->ReleaseStringUTFChars( env, path, utf );
  }
}

/* --- screenshots and recording ---------------------------------------- */

/* The frame itself, as palette indices, wrapped without copying. Only ever
   read from inside onFrame()/onScreenshot(), while the emulation thread is
   blocked in the callback and cannot be part way through the next frame. */
JNIEXPORT jobject JNICALL
Java_dev_ldlab_zedex_FuseNative_frameBuffer( JNIEnv *env, jclass class )
{
  size_t size;
  const libspectrum_byte *pixels = androiddisplay_indices( NULL, &size );

  return (*env)->NewDirectByteBuffer( env, (void*) pixels, (jlong) size );
}

/* Rows in that buffer are this wide whatever the machine is drawing. */
JNIEXPORT jint JNICALL
Java_dev_ldlab_zedex_FuseNative_frameStride( JNIEnv *env, jclass class )
{
  int stride;

  androiddisplay_indices( &stride, NULL );
  return (jint) stride;
}

/* The sixteen colours, 0xAABBGGRR, as the renderer has them. */
JNIEXPORT jintArray JNICALL
Java_dev_ldlab_zedex_FuseNative_palette( JNIEnv *env, jclass class )
{
  const libspectrum_dword *palette = androiddisplay_palette();
  jintArray colours = (*env)->NewIntArray( env, 16 );

  if( colours )
    (*env)->SetIntArrayRegion( env, colours, 0, 16, (const jint*) palette );

  return colours;
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_setRecording( JNIEnv *env, jclass class,
                                             jboolean on )
{
  recording = on ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_captureScreenshot( JNIEnv *env, jclass class )
{
  screenshot_wanted = 1;
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_saveThumbnail( JNIEnv *env, jclass class,
                                              jstring path )
{
  const char *utf = (*env)->GetStringUTFChars( env, path, NULL );

  if( utf ) {
    queue_command_text( COMMAND_SAVE_THUMBNAIL, 0, 0, strdup( utf ) );
    (*env)->ReleaseStringUTFChars( env, path, utf );
  }
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_loadSnapshot( JNIEnv *env, jclass class,
                                             jstring path )
{
  const char *utf = (*env)->GetStringUTFChars( env, path, NULL );

  if( utf ) {
    queue_command_text( COMMAND_LOAD_SNAPSHOT, 0, 0, strdup( utf ) );
    (*env)->ReleaseStringUTFChars( env, path, utf );
  }
}

JNIEXPORT void JNICALL
Java_dev_ldlab_zedex_FuseNative_openFile( JNIEnv *env, jclass class,
                                         jstring path )
{
  const char *utf = (*env)->GetStringUTFChars( env, path, NULL );

  if( utf ) {
    queue_command_text( COMMAND_OPEN_FILE, 0, 0, strdup( utf ) );
    (*env)->ReleaseStringUTFChars( env, path, utf );
  }
}
