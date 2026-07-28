/* android_joystick.c: joystick routines for the Android UI

   The joystick here is the one drawn on the screen. It is not found on a bus
   and it is never polled: its presses arrive through the command queue in
   android_bridge.c and are replayed on the emulation thread in the same pump
   as the keys. All Fuse needs from this file is to be told that a joystick
   exists, so that a snapshot written now records which interface it was
   pretending to be.
*/

#include "config.h"

#include "ui/uijoystick.h"

int
ui_joystick_init( void )
{
  /* One, always. There is nothing to open and nothing to fail: the app
     draws it. Which Spectrum interface it appears as is
     settings_current.joystick_1_output, which the menu sets. */
  return 1;
}

void
ui_joystick_end( void )
{
}

void
ui_joystick_poll( void )
{
  /* Nothing to poll for; see the note above. */
}
