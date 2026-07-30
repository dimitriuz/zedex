/* android_card.c: the DivMMC, its firmware, and the card in it

   Fuse emulates the whole interface already - peripherals/ide/divmmc.c, with
   libspectrum's MMC card behind it - so there is nothing here about SPI or
   FAT. What there is instead is the three things Fuse's desktop UI does through
   dialogs this port has no room for, and one that it cannot do at all:

   * plugging the interface in, which is a setting and a periph_update();
   * putting a card in, taking it out, and writing the changes back;
   * getting the firmware into the EPROM.

   The last is the interesting one. Fuse has no setting for the DivMMC's EPROM
   contents, because on real hardware you flash it from the Spectrum: the
   firmware ships as a tape that writes the EPROM through the interface itself.
   That works here too - it is only a tape - but it is a five minute ritual to
   repeat on every phone, and it leaves the emulator unusable in between. So
   this file does what the flasher tape does, from C: page the EPROM in with
   CONMEM, write the eight kilobytes, page it out again.

   Everything here runs on the emulation thread, called from the command queue
   in android_bridge.c. Nothing here may be called from Java directly.
*/

#include "config.h"

#include <string.h>

#include "android_internals.h"

#include "machine.h"
#include "memory_pages.h"
#include "periph.h"
#include "settings.h"
#include "ui/ui.h"
#include "utils.h"
#include "peripherals/ide/divmmc.h"

/* The DivMMC's EPROM is 8K, and so is every firmware built for it. */
#define FIRMWARE_LENGTH 0x2000

/* The DivMMC's control port, and the bit that pages the EPROM in on its own -
   which is how the interface is used when it is not automapping, and the only
   way to reach the EPROM to write it. */
#define DIVMMC_CONTROL_PORT 0x00e3
#define DIVMMC_CONTROL_CONMEM 0x80

/* The firmware, kept because the EPROM has to be written again after anything
   that can take the interface away - a snapshot load does - and because the
   file it came from is on storage the app may not be able to reach later. */
static libspectrum_byte firmware[ FIRMWARE_LENGTH ];
static int have_firmware;

/* Whether the app asked for the interface, as opposed to whether Fuse has it
   now. Snapshot loading calls periph_disable_optional(), which zeroes every
   optional peripheral's setting, and only the peripherals the snapshot itself
   recorded put themselves back - so an .sna, which cannot record a DivMMC at
   all, would silently unplug it. The same trap the Kempston mouse falls into;
   see restore_mouse() in android_bridge.c. */
static int wanted;

/* Writes the firmware into the EPROM, exactly the way the flasher tape does.

   The write protect jumper has to come off first, and it cannot simply stay
   off: divxxx_refresh_page_state() only automaps while the EPROM is protected,
   so an unprotected DivMMC never pages itself in and the firmware never runs.
   Off to write, on to work.

   The readback is not paranoia about the memory write. It is that the ports
   are shared: 0xe3 is the +D's and the DivIDE's too, and if one of those were
   ever plugged in as well it would answer first, the write would land in a ROM
   that is not writable, and the machine would then automap eight kilobytes of
   0xff - which is RST 38h eight thousand times over, and a dead Spectrum. */
static int
flash_eprom( void )
{
  int protect = settings_current.divmmc_wp;
  int i;

  settings_current.divmmc_wp = 0;
  writeport_internal( DIVMMC_CONTROL_PORT, DIVMMC_CONTROL_CONMEM );

  for( i = 0; i < FIRMWARE_LENGTH; i++ ) writebyte_internal( i, firmware[i] );

  for( i = 0; i < FIRMWARE_LENGTH; i += 0x400 ) {
    if( readbyte_internal( i ) != firmware[i] ) {
      writeport_internal( DIVMMC_CONTROL_PORT, 0x00 );
      settings_current.divmmc_wp = protect;
      divmmc_refresh_page_state();
      return 1;
    }
  }

  writeport_internal( DIVMMC_CONTROL_PORT, 0x00 );
  settings_current.divmmc_wp = protect;
  divmmc_refresh_page_state();

  return 0;
}

/* Plugs the interface in or takes it out. A hard reset follows a change,
   because that is what the interface being there or not means: the firmware
   takes over the machine's reset, and Fuse's own periph_update() says so by
   returning that one is needed. */
void
androidcard_set_enabled( int on )
{
  wanted = on;

  /* No firmware, no interface. A DivMMC with a blank EPROM is worse than no
     DivMMC: it automaps into the reset and hangs the machine before anything
     is drawn, which looks exactly like the app being broken. */
  if( on && !have_firmware ) {
    ui_error( UI_ERROR_ERROR,
              "The DivMMC needs its firmware. Load an 8K esxDOS ROM in "
              "Settings, Machine." );
    on = 0;
  }

  if( settings_current.divmmc_enabled == on ) return;

  android_log( "divmmc %s", on ? "on" : "off" );

  settings_current.divmmc_enabled = on;
  settings_current.divmmc_wp = 1;

  if( periph_update() ) machine_reset( 1 );

  if( on && flash_eprom() ) {
    ui_error( UI_ERROR_ERROR, "Couldn't write the DivMMC firmware." );
    settings_current.divmmc_enabled = 0;
    periph_update();
    return;
  }

  /* The firmware only takes over from a reset, and the machine has been
     running without it until now. */
  if( on ) machine_reset( 1 );
}

/* Reads a firmware file and, if the interface is meant to be on, puts it
   straight into the EPROM. Anything longer than the EPROM is refused rather
   than truncated: a file that is not an 8K image is not firmware, and half of
   one flashed silently would hang the machine on the next reset. */
int
androidcard_load_firmware( const char *path )
{
  utils_file file;

  if( utils_read_file( path, &file ) ) return 1;

  if( file.length != FIRMWARE_LENGTH ) {
    android_logw( "firmware %s is %lu bytes, not %d", path,
                  (unsigned long) file.length, FIRMWARE_LENGTH );
    utils_close_file( &file );
    ui_error( UI_ERROR_ERROR, "That is not an 8K DivMMC firmware." );
    return 1;
  }

  memcpy( firmware, file.buffer, FIRMWARE_LENGTH );
  have_firmware = 1;
  utils_close_file( &file );

  android_log( "divmmc firmware loaded from %s", path );

  if( !settings_current.divmmc_enabled ) return 0;

  if( flash_eprom() ) {
    ui_error( UI_ERROR_ERROR, "Couldn't write the DivMMC firmware." );
    return 1;
  }

  /* Replacing the firmware under a running esxDOS leaves the machine holding a
     copy of the old one in the interface's RAM, which is the state a soft reset
     leaves behind and just as broken. Start again. */
  machine_reset( 1 );

  return 0;
}

/* Puts the interface back after something took it away. Called where the
   mouse is restored, for the same reason and after the same events. */
void
androidcard_restore( void )
{
  if( !wanted || !have_firmware || settings_current.divmmc_enabled ) return;

  settings_current.divmmc_enabled = 1;
  settings_current.divmmc_wp = 1;
  periph_update();
  flash_eprom();
}

/* --- the card ---------------------------------------------------------- */

/* Fuse asks about unsaved changes through its widget layer, which draws a
   modal into the emulated screen that only Enter or Escape dismisses - see
   ui_error_specific in android_ui.c for why that is no good here. Committing
   first means there is nothing left to ask about, and it is what a card wants
   anyway: the changes belong in the image, and a phone can be put down at any
   moment. */
void
androidcard_insert( const char *path )
{
  if( settings_current.divmmc_file ) divmmc_commit();

  android_log( "card in: %s", path );

  if( divmmc_insert( path ) ) {
    ui_error( UI_ERROR_ERROR, "Couldn't use that as a card image." );
    return;
  }

  /* esxDOS reads the card once, while it starts, and keeps what it found: a
     card that arrives afterwards is not there as far as it is concerned, and
     every dot command answers "ESXDOS error #19, 0:1" until the machine is
     reset. So reset it here rather than leaving the user with an interface that
     appears to be working and is not.

     A *hard* reset, and that is the whole difference between esxDOS working and
     not: divxxx_reset() keeps the MAPRAM bit through a soft one, so the machine
     comes back up out of the DivMMC's RAM page 3 - where esxDOS put a copy of
     itself, with the drive it had then - instead of out of the EPROM. It boots,
     it prints its banner, and every path is invalid. Hours went into that
     error message.

     Nothing is lost by resetting that was not lost anyway - taking the card out
     from under a running esxDOS is not something it survives - and at startup
     this costs nothing at all, since the machine has not run an instruction
     yet: the firmware, the interface and the card all arrive in one drain of
     the queue. */
  if( settings_current.divmmc_enabled ) machine_reset( 1 );
}

void
androidcard_commit( void )
{
  if( !settings_current.divmmc_file ) return;

  divmmc_commit();
  android_log( "card written back" );
}

/* Writing the card back without being asked, once a second.

   libspectrum keeps written sectors in a hash table and puts them on the card
   only when it is told to, so a card nobody commits loses everything the
   machine wrote to it. A menu item alone would mean a save survives only if the
   user remembers a menu item, on a device that gets put in a pocket mid-game -
   which is not a saving system, it is a lottery.

   Once a second is not expensive: an empty commit walks an empty hash table and
   does nothing at all, and a commit that does write is doing the I/O that had to
   happen anyway. It only writes sectors the machine has already written, so what
   lands in the image is what esxDOS put there.

   *Write changes* stays in the menu for the moment you want to be certain, and
   pausing commits too - see run_while_paused() in android_bridge.c, which is
   where the app goes when Android takes it away. */
#define COMMIT_FRAMES 50

void
androidcard_tick( void )
{
  static int frames;

  if( !settings_current.divmmc_file ) {
    frames = 0;
    return;
  }

  if( ++frames < COMMIT_FRAMES ) return;

  frames = 0;
  divmmc_commit();
}

void
androidcard_eject( void )
{
  if( !settings_current.divmmc_file ) return;

  divmmc_commit();
  divmmc_eject();
}
