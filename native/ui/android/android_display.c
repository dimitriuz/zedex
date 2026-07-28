/* android_display.c: Android display routines for Fuse

   Fuse hands us palette indices one pixel, byte or word at a time; we keep
   them in an 8bpp image the same way ui/fb does, expand the whole frame to
   RGBA once per frame, and let the GPU do all the scaling. Fuse's own
   software scalers stay at 1x - scaling and filtering belong in the shader.
*/

#include "config.h"

#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <libspectrum.h>

#include "android_internals.h"
#include "display.h"
#include "machine.h"
#include "settings.h"
#include "ui/ui.h"
#include "ui/uidisplay.h"
#include "ui/scaler/scaler.h"

/* Palette indices, in the same layout ui/fb uses: big enough for the
   double-size Timex hi-res modes. */
static libspectrum_byte
  androiddisplay_image[ 2 * DISPLAY_SCREEN_HEIGHT ][ DISPLAY_SCREEN_WIDTH ];

/* The frame handed to the GPU, RGBA8888. */
static libspectrum_dword
  androiddisplay_rgba[ 2 * DISPLAY_SCREEN_HEIGHT * DISPLAY_SCREEN_WIDTH ];

static int image_width, image_height;

/* 0xAABBGGRR: GL_RGBA on a little endian machine. */
#define RGBA( r, g, b ) ( 0xff000000 | ( (b) << 16 ) | ( (g) << 8 ) | (r) )

static const libspectrum_dword colours[ 16 ] = {
  RGBA(   0,   0,   0 ), RGBA(   0,   0, 192 ),
  RGBA( 192,   0,   0 ), RGBA( 192,   0, 192 ),
  RGBA(   0, 192,   0 ), RGBA(   0, 192, 192 ),
  RGBA( 192, 192,   0 ), RGBA( 192, 192, 192 ),
  RGBA(   0,   0,   0 ), RGBA(   0,   0, 255 ),
  RGBA( 255,   0,   0 ), RGBA( 255,   0, 255 ),
  RGBA(   0, 255,   0 ), RGBA(   0, 255, 255 ),
  RGBA( 255, 255,   0 ), RGBA( 255, 255, 255 ),
};

static const libspectrum_dword greys[ 16 ] = {
  RGBA(   0,   0,   0 ), RGBA(  22,  22,  22 ),
  RGBA(  57,  57,  57 ), RGBA(  79,  79,  79 ),
  RGBA( 108, 108, 108 ), RGBA( 130, 130, 130 ),
  RGBA( 165, 165, 165 ), RGBA( 192, 192, 192 ),
  RGBA(   0,   0,   0 ), RGBA(  29,  29,  29 ),
  RGBA(  76,  76,  76 ), RGBA( 105, 105, 105 ),
  RGBA( 144, 144, 144 ), RGBA( 173, 173, 173 ),
  RGBA( 220, 220, 220 ), RGBA( 255, 255, 255 ),
};

int
androiddisplay_init( void )
{
  return 0;
}

static void
register_scalers( void )
{
  scaler_register_clear();
  scaler_register( SCALER_NORMAL );
  scaler_select_scaler( SCALER_NORMAL );
}

int
uidisplay_init( int width, int height )
{
  image_width = width;
  image_height = height;

  register_scalers();

  display_ui_initialised = 1;
  display_refresh_all();

  android_log( "display initialised at %dx%d", width, height );

  return 0;
}

int
uidisplay_hotswap_gfx_mode( void )
{
  return 0;
}

/* Fuse tells us which parts of the screen changed; we upload the whole
   frame regardless. At 320x240 that is 300kB per frame, which costs far
   less than tracking dirty rectangles through to the GPU would. */
void
uidisplay_area( int x, int y, int w, int h )
{
}

void
uidisplay_frame_end( void )
{
  const libspectrum_dword *palette = settings_current.bw_tv ? greys : colours;
  libspectrum_dword *out = androiddisplay_rgba;
  int x, y;

  for( y = 0; y < image_height; y++ ) {
    const libspectrum_byte *in = androiddisplay_image[y];
    for( x = 0; x < image_width; x++ ) *out++ = palette[ *in++ ];
  }

  androidbridge_present( androiddisplay_rgba, image_width, image_height );
}

int
uidisplay_end( void )
{
  androidgl_end();
  return 0;
}

/* The widget UI draws its dialogs straight into the emulated screen, so it
   asks us to stash a clean copy first. ui/fb leaves these unimplemented and
   corrupts the display behind menus; there is no reason to inherit that. */

static libspectrum_byte
  saved_image[ 2 * DISPLAY_SCREEN_HEIGHT ][ DISPLAY_SCREEN_WIDTH ];
static int have_saved_image;

/* Writes the last presented frame at half size for the save state list:
   an 8 byte header of width and height, then RGBA rows. Called on the
   emulation thread, so the frame is whole. */
int
androiddisplay_write_thumbnail( const char *path )
{
  int32_t header[2];
  int x, y, width, height;
  FILE *file;

  if( image_width < 2 || image_height < 2 ) return 1;

  width = image_width / 2;
  height = image_height / 2;

  file = fopen( path, "wb" );
  if( !file ) {
    android_logw( "cannot write thumbnail %s", path );
    return 1;
  }

  header[0] = width;
  header[1] = height;
  fwrite( header, sizeof( header ), 1, file );

  for( y = 0; y < height; y++ ) {
    libspectrum_dword row[ DISPLAY_SCREEN_WIDTH ];

    for( x = 0; x < width; x++ )
      row[x] = androiddisplay_rgba[ ( y * 2 ) * image_width + x * 2 ];

    fwrite( row, sizeof( libspectrum_dword ), width, file );
  }

  fclose( file );

  return 0;
}

void
uidisplay_frame_save( void )
{
  memcpy( saved_image, androiddisplay_image, sizeof( androiddisplay_image ) );
  have_saved_image = 1;
}

void
uidisplay_frame_restore( void )
{
  if( !have_saved_image ) return;

  memcpy( androiddisplay_image, saved_image, sizeof( androiddisplay_image ) );
}

void
uidisplay_putpixel( int x, int y, int colour )
{
  if( machine_current->timex ) {
    x <<= 1; y <<= 1;
    androiddisplay_image[y  ][x  ] = colour;
    androiddisplay_image[y  ][x+1] = colour;
    androiddisplay_image[y+1][x  ] = colour;
    androiddisplay_image[y+1][x+1] = colour;
  } else {
    androiddisplay_image[y][x] = colour;
  }
}

/* Print the 8 pixels in `data' using ink colour `ink' and paper colour
   `paper' to the screen at ( (8*x), y ) */
void
uidisplay_plot8( int x, int y, libspectrum_byte data,
                 libspectrum_byte ink, libspectrum_byte paper )
{
  x <<= 3;

  if( machine_current->timex ) {
    int i;

    x <<= 1; y <<= 1;
    for( i = 0; i < 2; i++, y++ ) {
      androiddisplay_image[y][x+ 0] = ( data & 0x80 ) ? ink : paper;
      androiddisplay_image[y][x+ 1] = ( data & 0x80 ) ? ink : paper;
      androiddisplay_image[y][x+ 2] = ( data & 0x40 ) ? ink : paper;
      androiddisplay_image[y][x+ 3] = ( data & 0x40 ) ? ink : paper;
      androiddisplay_image[y][x+ 4] = ( data & 0x20 ) ? ink : paper;
      androiddisplay_image[y][x+ 5] = ( data & 0x20 ) ? ink : paper;
      androiddisplay_image[y][x+ 6] = ( data & 0x10 ) ? ink : paper;
      androiddisplay_image[y][x+ 7] = ( data & 0x10 ) ? ink : paper;
      androiddisplay_image[y][x+ 8] = ( data & 0x08 ) ? ink : paper;
      androiddisplay_image[y][x+ 9] = ( data & 0x08 ) ? ink : paper;
      androiddisplay_image[y][x+10] = ( data & 0x04 ) ? ink : paper;
      androiddisplay_image[y][x+11] = ( data & 0x04 ) ? ink : paper;
      androiddisplay_image[y][x+12] = ( data & 0x02 ) ? ink : paper;
      androiddisplay_image[y][x+13] = ( data & 0x02 ) ? ink : paper;
      androiddisplay_image[y][x+14] = ( data & 0x01 ) ? ink : paper;
      androiddisplay_image[y][x+15] = ( data & 0x01 ) ? ink : paper;
    }
  } else {
    androiddisplay_image[y][x+ 0] = ( data & 0x80 ) ? ink : paper;
    androiddisplay_image[y][x+ 1] = ( data & 0x40 ) ? ink : paper;
    androiddisplay_image[y][x+ 2] = ( data & 0x20 ) ? ink : paper;
    androiddisplay_image[y][x+ 3] = ( data & 0x10 ) ? ink : paper;
    androiddisplay_image[y][x+ 4] = ( data & 0x08 ) ? ink : paper;
    androiddisplay_image[y][x+ 5] = ( data & 0x04 ) ? ink : paper;
    androiddisplay_image[y][x+ 6] = ( data & 0x02 ) ? ink : paper;
    androiddisplay_image[y][x+ 7] = ( data & 0x01 ) ? ink : paper;
  }
}

/* Print the 16 pixels in `data' using ink colour `ink' and paper colour
   `paper' to the screen at ( (16*x), y ) */
void
uidisplay_plot16( int x, int y, libspectrum_word data,
                  libspectrum_byte ink, libspectrum_byte paper )
{
  int i;

  x <<= 4; y <<= 1;

  for( i = 0; i < 2; i++, y++ ) {
    androiddisplay_image[y][x+ 0] = ( data & 0x8000 ) ? ink : paper;
    androiddisplay_image[y][x+ 1] = ( data & 0x4000 ) ? ink : paper;
    androiddisplay_image[y][x+ 2] = ( data & 0x2000 ) ? ink : paper;
    androiddisplay_image[y][x+ 3] = ( data & 0x1000 ) ? ink : paper;
    androiddisplay_image[y][x+ 4] = ( data & 0x0800 ) ? ink : paper;
    androiddisplay_image[y][x+ 5] = ( data & 0x0400 ) ? ink : paper;
    androiddisplay_image[y][x+ 6] = ( data & 0x0200 ) ? ink : paper;
    androiddisplay_image[y][x+ 7] = ( data & 0x0100 ) ? ink : paper;
    androiddisplay_image[y][x+ 8] = ( data & 0x0080 ) ? ink : paper;
    androiddisplay_image[y][x+ 9] = ( data & 0x0040 ) ? ink : paper;
    androiddisplay_image[y][x+10] = ( data & 0x0020 ) ? ink : paper;
    androiddisplay_image[y][x+11] = ( data & 0x0010 ) ? ink : paper;
    androiddisplay_image[y][x+12] = ( data & 0x0008 ) ? ink : paper;
    androiddisplay_image[y][x+13] = ( data & 0x0004 ) ? ink : paper;
    androiddisplay_image[y][x+14] = ( data & 0x0002 ) ? ink : paper;
    androiddisplay_image[y][x+15] = ( data & 0x0001 ) ? ink : paper;
  }
}

int
androiddisplay_end( void )
{
  return 0;
}
