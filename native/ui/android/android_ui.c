/* android_ui.c: Android user interface routines for Fuse

   The dialogs, menus and debugger UI all come from Fuse's portable widget
   layer (ui/widget), which is why the build configures --with-fb. This file
   only has to provide the handful of entry points that the fb backend would
   otherwise supply.
*/

#include "config.h"

#include "android_internals.h"
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
