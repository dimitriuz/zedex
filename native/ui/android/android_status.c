/* android_status.c: what the machine is busy with, for the app's lamps

   Five things the app shows as indicators: the tape running, a disk turning,
   the AY making a noise, and the machine reading the keyboard or a joystick
   port. Each comes from a different place, and only the first two are
   something Fuse announces.

   - The tape and the disks arrive through ui_statusbar_update(), which is how
     Fuse tells a UI to light its status bar. ui/widget/widget.c has a stub
     that returns 0 and throws the news away; the build weakens that symbol so
     the one below wins the link (see scripts/build-native.sh).

   - The tape needs more than that. Fuse only announces a tape that is
     *playing*, and loading fast never plays one: the trap hands the block over
     whole. So the block the tape sits on is watched as well.

   - The AY has no notification of any kind, so its registers are read at the
     end of every frame. They live on the machine, so this is a look at state
     Fuse already keeps rather than anything new.

   - Nothing at all reports a port read, and there is no hook for one. What
     there is, in readport_internal(), is a walk over *every* peripheral whose
     mask matches, ANDing what each returns - so a peripheral that returns
     0xff and leaves `attached' alone can watch a port without changing what
     the machine reads from it. That is the whole trick: the monitor below is
     registered like any other peripheral and is invisible to the emulation.

   All of it runs on the emulation thread. The one word the UI thread reads is
   written last and read as a whole, which is all the synchronisation an int
   needs.
*/

#include "config.h"

#include "android_internals.h"

#include "machine.h"
#include "periph.h"
#include "tape.h"
#include "ui/ui.h"
#include "ui/uimedia.h"

#include "peripherals/disk/disk.h"
#include "peripherals/disk/fdd.h"

#include "peripherals/sound/ay.h"

/* Bits as the app sees them; keep in step with FuseNative.ACTIVITY_*. */
#define ACTIVITY_TAPE     ( 1 << 0 )
#define ACTIVITY_DISK     ( 1 << 1 )
#define ACTIVITY_AY       ( 1 << 2 )
#define ACTIVITY_KEYBOARD ( 1 << 3 )
#define ACTIVITY_JOYSTICK ( 1 << 4 )
#define ACTIVITY_MOUSE    ( 1 << 5 )

/* The same five bits again, this far up, for "and it is writing rather than
   reading". Only some of the lamps can say: see the notes at each of them. */
#define ACTIVITY_WRITING  8

/* Some of this is an instant rather than a state - a block appended, a sector
   put down, a tape wound on by a trap - so it is held for long enough to see.
   At 50Hz this is a fifth of a second. */
#define HOLD_FRAMES 10

/* The tape and the disks for longer still. A tape can be trapped through in
   three frames; a format pauses between tracks for longer than a fifth of a
   second, and a lamp that goes out between them reads as one that has
   finished. Half a second each. */
#define TAPE_FRAMES 25
#define DISK_FRAMES 25

/* What the UI thread reads. Written once a frame by the emulation thread. */
static volatile int published;
static volatile int published_levels;

/* Fuse's own view, which changes only when it says so. */
static int tape_running;
static int disk_running;

/* Set by the monitor, cleared every frame: a lamp that stayed lit because a
   game read the port once, half a minute ago, would say nothing at all. */
static int keyboard_seen;
static int joystick_seen;
static int mouse_seen;
static int ay_written;

/* Counting down while something that already happened is worth showing. */
static int tape_reading;
static int tape_writing;
static int disk_reading;

/* Where each drive's head was a frame ago; -1 for a drive with nothing in it. */
static int disk_was_at[ MAX_CONTROLLERS ][ MAX_DRIVES_PER_CONTROLLER ];

/* What we last saw, so a change can be told from a state. */
static int tape_was_modified;
static int tape_was_at_block = -1;

/* --- what Fuse announces ---------------------------------------------- */

int
ui_statusbar_update( ui_statusbar_item item, ui_statusbar_state state )
{
  int active = state == UI_STATUSBAR_STATE_ACTIVE;

  switch( item ) {
  case UI_STATUSBAR_ITEM_TAPE: tape_running = active; break;

  /* A microdrive is a disk as far as a lamp is concerned: both mean the
     machine is waiting on something that spins. */
  case UI_STATUSBAR_ITEM_MICRODRIVE: disk_running = active; break;

  /* Not the disks: see the note at watch_disk_access(). Fuse's count of
     turning motors can stick above zero, because fdd_unload() clears the
     drive's `loaded' before calling fdd_motoron( d, 0 ), which returns early
     for a drive with nothing in it and so never decrements it. Ejecting a
     spinning disk therefore leaves the lamp on for the rest of the session. */
  case UI_STATUSBAR_ITEM_DISK:
  case UI_STATUSBAR_ITEM_MOUSE:
  case UI_STATUSBAR_ITEM_PAUSED:
    break;
  }

  return 0;
}

/* --- the AY ----------------------------------------------------------- */

/* How loud each of the AY's three channels is, 0 to 15, as three bytes: A in
   the bottom, then B, then C.

   An amplitude on its own is not enough. A game that has finished with a
   channel usually silences it in the mixer and leaves the amplitude where it
   was, so a meter watching only R8-R10 would stay up for the rest of the game;
   a channel counts only when R7 has not switched off both its tone and its
   noise. Bit 4 of an amplitude means "follow the envelope generator" instead of
   holding still, and where the envelope has got to is not something the
   registers say - so that reads as full, which is what a sweep mostly is. */
static int
ay_levels( void )
{
  const libspectrum_byte *r;
  int channel, levels = 0;

  if( !machine_current ) return 0;

  r = machine_current->ay.registers;

  for( channel = 0; channel < 3; channel++ ) {
    int tone  = !( r[7] & ( 1 << channel ) );
    int noise = !( r[7] & ( 1 << ( channel + 3 ) ) );
    int amplitude = r[ 8 + channel ];
    int level = 0;

    if( tone || noise ) level = amplitude & 0x10 ? 15 : amplitude & 0x0f;

    levels |= level << ( channel * 8 );
  }

  return levels;
}

/* --- watching the ports ----------------------------------------------- */

/* periph_register() only ever uses the type as a hash key, so a value past
   the end of Fuse's enum is a type of our own that cannot collide with a real
   peripheral. */
#define PERIPH_TYPE_ANDROID_MONITOR ( PERIPH_TYPE_ZXPRINTER_FULL_DECODE + 1000 )

/* Both of these have to return 0xff and leave `attached' alone: the value is
   ANDed into what the machine reads, and `attached' decides how much of the
   floating bus shows through. Touching either would change what a program
   sees. */
static libspectrum_byte
watch_keyboard( libspectrum_word port, libspectrum_byte *attached )
{
  keyboard_seen = 1;
  return 0xff;
}

static libspectrum_byte
watch_joystick( libspectrum_word port, libspectrum_byte *attached )
{
  joystick_seen = 1;
  return 0xff;
}

static libspectrum_byte
watch_mouse( libspectrum_word port, libspectrum_byte *attached )
{
  mouse_seen = 1;
  return 0xff;
}

/* Writes need no such care: a write function is told what was written and its
   return value is nobody's business. */
static void
watch_ay( libspectrum_word port, libspectrum_byte data )
{
  ay_written = 1;
}

static const periph_port_t monitor_ports[] = {
  /* Any even port is the ULA, and reading the ULA is how the keyboard is
     scanned. It is also how a tape is read, so the keyboard lamp flickers
     while one loads - which is true, if not the whole truth. */
  { 0x0001, 0x0000, watch_keyboard, NULL },

  /* The joystick ports, decoded loosely so this sees a game reaching for a
     Kempston whether or not one is plugged in. That is the useful half: a
     lit lamp and a dead stick is exactly the moment to go and choose
     Kempston in the menu. */
  { 0x0020, 0x0000, watch_joystick, NULL },

  /* The Kempston mouse's three, decoded exactly as kempmouse.c decodes them:
     the buttons, then x, then y. Tight rather than loose because the point of
     this lamp is that it answers "does this game use the mouse?", and a loose
     decode would answer yes to half the ports on the machine.

     One thing it cannot avoid: those ports all have bit five clear, which is
     what the joystick watcher above catches, so reading the mouse lights the
     joystick lamp as well. Both are true - the game is reaching for a Kempston
     something - and separating them would mean giving up the loose decode that
     makes the joystick lamp useful. */
  { 0x0121, 0x0001, watch_mouse, NULL },
  { 0x0521, 0x0101, watch_mouse, NULL },
  { 0x0521, 0x0501, watch_mouse, NULL },

  /* The AY's register and data ports, 0xfffd and 0xbffd. Writing them is how
     a machine makes a sound, which is why the AY lamp has only the one
     colour: what the chip does is data on its way out. */
  { 0xc002, 0x8000, NULL, watch_ay },

  { 0, 0, NULL, NULL }
};

static const periph_t monitor_periph = {
  /* No option: this is never something the user turns on. */
  /* .option = */ NULL,
  /* .ports = */ monitor_ports,
  /* .hard_reset = */ 0,
  /* .activate = */ NULL,
};

/* Every machine's reset calls periph_clear(), which empties the port list and
   marks every type inactive - the registration itself survives. So this
   registers once and then only has to put the monitor back on the list, which
   is what a machine change costs it. */
static void
keep_monitor_attached( void )
{
  static int registered;

  if( !registered ) {
    periph_register( PERIPH_TYPE_ANDROID_MONITOR, &monitor_periph );
    registered = 1;
  }

  if( periph_is_active( PERIPH_TYPE_ANDROID_MONITOR ) ) return;

  periph_set_present( PERIPH_TYPE_ANDROID_MONITOR, PERIPH_PRESENT_ALWAYS );
  periph_activate_type( PERIPH_TYPE_ANDROID_MONITOR, 1 );
}

/* --- who is writing --------------------------------------------------- */

/* The tape, which the status bar is nearly no help with.

   Loading fast is a trap: the ROM's routine is caught, the block handed over
   whole, and the tape never "played" - so tape_play() is not called and the
   status bar hears nothing at all. The lamp would then be dark for exactly the
   loads that matter, since fast loading is the default. What does change is
   which block the tape is sitting on, and that is true whether the block was
   read in real time or handed over.
   
   Writing is the save trap appending a block, which sets tape_modified - only
   the rise of it, because it stays set afterwards and a tape written to ten
   minutes ago is not being written to now - or a real-time recording, which
   says so for as long as it runs. */
static void
watch_tape( void )
{
  int block = tape_get_current_block();
  int modified = tape_modified;

  /* A whole small tape can be trapped through in three frames, so this holds
     for longer than the rest: a lamp that is technically right and never seen
     is no use. A real load re-arms it block after block and stays lit. */
  if( block != tape_was_at_block ) tape_reading = TAPE_FRAMES;
  tape_was_at_block = block;

  /* The tape can say which way, and the rise of tape_modified is the moment
     the save trap appended a block - which is inside the save rather than
     merely after it, so unlike the disk's latch this really does mark the
     event. Only the rise: it stays set afterwards, and a tape saved to ten
     minutes ago is not being saved to now. A real-time recording is a state
     and says so for as long as it runs. */
  if( modified && !tape_was_modified ) tape_writing = HOLD_FRAMES;
  tape_was_modified = modified;

  if( tape_recording ) tape_writing = HOLD_FRAMES;
}

/* Whether any drive is being read or written this instant.

   Not from the status bar, which cannot be trusted here: Fuse counts turning
   motors in a global, and fdd_unload() clears a drive's `loaded' *before*
   calling fdd_motoron( d, 0 ), which returns early for a drive with nothing in
   it and so never decrements the count. Eject a spinning disk once and the
   lamp stays on for the rest of the session. The per-drive flag goes stale the
   same way, and a fresh disk inherits it.

   So this watches the drive doing something instead of a flag saying it is.
   disk.i is the drive's position along the current track, and it only advances
   inside fdd_read_write_data() - which nothing but the controller calls, for a
   byte it is genuinely reading or writing. A stale flag cannot move it. It
   wraps at the end of a track, so any change at all is a change.

   Which way the byte was going is not on offer. The only trace a write leaves
   is disk.dirty, and that is a latch: set on the first written byte and cleared
   only when the app saves the disk out. Two proxies for it were tried and both
   were wrong more often than right - the rise of the latch flashed for a fifth
   of a second at the start of a two minute format and then sat in the reading
   colour for the rest of it, and the level of it turned the lamp amber for the
   whole session, because a disk made by New disk is dirty before the machine
   has touched it. The controllers do know, and wd_fdc even has a WRITETRACK
   state which is exactly what a format is, but every instance of one is static
   inside its own interface's file and ui_media_drive_info_t hands out the drive
   rather than the controller. So the lamp says the drive is being used, which
   is true, and says nothing it cannot stand behind.
*/
static int
watch_disk_access( void )
{
  int controller, drive, moved = 0;

  for( controller = 0; controller < MAX_CONTROLLERS; controller++ ) {
    for( drive = 0; drive < MAX_DRIVES_PER_CONTROLLER; drive++ ) {
      ui_media_drive_info_t *found = ui_media_drive_find( controller, drive );
      int *last = &disk_was_at[ controller ][ drive ];
      int now;

      if( !found || !found->fdd || !found->fdd->loaded ) { *last = -1; continue; }

      now = found->fdd->disk.i;
      if( *last >= 0 && now != *last ) moved = 1;
      *last = now;
    }
  }

  return moved;
}

/* --- once a frame ----------------------------------------------------- */

void
androidstatus_frame( void )
{
  int state = 0;

  keep_monitor_attached();
  watch_tape();

  /* Accesses come in bursts, so this is held like the rest: a lamp flickering
     at fifty hertz is a lamp nobody can read. */
  if( watch_disk_access() ) disk_reading = DISK_FRAMES;

  if( tape_reading ) tape_reading--;
  if( tape_writing ) tape_writing--;
  if( disk_reading ) disk_reading--;

  /* A write shows as a write and as activity: the lamp is on either way, and
     the colour is what says which. A tape being saved is not "playing", so it
     has to light the lamp itself. */
  if( tape_running || tape_reading ) {
    state |= ACTIVITY_TAPE;
    if( tape_writing ) state |= ACTIVITY_TAPE << ACTIVITY_WRITING;
  }

  /* No writing bit: see the note at watch_disk_access(). disk_running is the
     microdrive, which does still come from the status bar. */
  if( disk_running || disk_reading ) state |= ACTIVITY_DISK;

  /* A level covers a note left running after the registers were set; the
     write covers the setting of them. Either way it is sound going out. */
  published_levels = ay_levels();
  if( published_levels || ay_written ) {
    state |= ACTIVITY_AY | ( ACTIVITY_AY << ACTIVITY_WRITING );
  }

  /* These two are reads by their nature: there is nothing to write to a
     keyboard or a joystick. */
  if( keyboard_seen ) state |= ACTIVITY_KEYBOARD;
  if( joystick_seen ) state |= ACTIVITY_JOYSTICK;
  if( mouse_seen ) state |= ACTIVITY_MOUSE;

  keyboard_seen = 0;
  joystick_seen = 0;
  mouse_seen = 0;
  ay_written = 0;

  published = state;
}

/* A paused machine is doing nothing, and every lamp should say so. Without
   this they freeze at whatever they happened to be showing, which reads as a
   tape still running or a keyboard still being scanned. */
void
androidstatus_idle( void )
{
  published = 0;
  published_levels = 0;

  tape_reading = 0;
  tape_writing = 0;
  disk_reading = 0;
  keyboard_seen = 0;
  joystick_seen = 0;
  ay_written = 0;
}

int
androidstatus_activity( void )
{
  return published;
}

int
androidstatus_ay_levels( void )
{
  return published_levels;
}
