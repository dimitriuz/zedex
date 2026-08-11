/* android_ui.c: Android user interface routines for Fuse

   The dialogs, menus and debugger UI all come from Fuse's portable widget
   layer (ui/widget), which is why the build configures --with-fb. This file
   only has to provide the handful of entry points that the fb backend would
   otherwise supply.
*/

#include "config.h"

#include "android_internals.h"
#include "input.h"
#include "ui/ui.h"
#include "ui/uidisplay.h"

int
ui_init( int *argc, char ***argv )
{
  int error;

  error = ui_widget_init();
  if( error ) return error;

  error = androiddisplay_init();
  if( error ) return error;

  android_log( "UI initialised" );

  return 0;
}

int
ui_event( void )
{
  androidbridge_pump_commands();
  return 0;
}

int
ui_end( void )
{
  androiddisplay_end();
  ui_widget_end();

  return 0;
}

/* The Kempston mouse is not wired up yet; claiming the grab succeeded keeps
   Fuse's mouse handling quiet. */

int
ui_mouse_grab( int startup )
{
  return 1;
}

int
ui_mouse_release( int suspend )
{
  return 0;
}

/* --- error reporting -------------------------------------------------- */

/* Fuse reports errors through its widget layer: a Spectrum-styled modal that
   only Enter or Escape dismisses, drawn into the emulated screen. On a
   touchscreen that is the wrong shape, and worse, it blocks whatever raised
   it - a save to a lossy snapshot format does not write its file until the
   warning has been answered.

   The build weakens ui/widget/error.c's ui_error_specific so this one wins;
   the rest of that file stays, because ui/widget/query.c shares its
   split_message. The error widget is simply never opened. */

int
ui_error_specific( ui_error_level severity, const char *message )
{
  androidbridge_report_error( severity, message );
  return 0;
}

/* --- "the disk has been modified" ------------------------------------- */

/* The same argument as the error widget, and the same fix: ui/widget's
   ui_confirm_save_specific draws a Spectrum-styled modal into the emulated
   screen that only Enter or Escape dismisses, which on a touchscreen is a
   dialog with no buttons anybody can press.

   It is asked more often than it looks. Inserting a disk ejects the one
   already in the drive, and drive_eject in uimedia.c asks this whenever that
   disk is dirty - so on a .trd the game has written to, simply loading the
   next one raises it.

   The build weakens the widget's copy so this one wins, exactly as it does
   for ui_error_specific and ui_statusbar_update. Note that widget.c's version
   opens with a settings_current.confirm_actions check and returns "don't
   save" when confirmations are off; that check belongs to the widget UI's own
   idea of a confirmation, and this deliberately does not repeat it - the
   question here is not a confirmation of something already chosen, it is the
   only chance to keep work the machine is about to throw away. */

ui_confirm_save_t
ui_confirm_save_specific( const char *message )
{
  switch( androidbridge_confirm_save( message ) ) {

  case UI_CONFIRM_SAVE_SAVE:     return UI_CONFIRM_SAVE_SAVE;
  case UI_CONFIRM_SAVE_DONTSAVE: return UI_CONFIRM_SAVE_DONTSAVE;

  /* Cancel for anything else, including whatever an older or newer Java side
     might answer with: it is the reply that leaves the disk in the drive and
     the changes in it. */
  default:                       return UI_CONFIRM_SAVE_CANCEL;
  }
}
