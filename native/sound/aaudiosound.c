/* aaudiosound.c: AAudio sound output for Fuse

   Substituted for sound/nullsound.o at link time (see
   scripts/build-native.sh), so Fuse itself needs no new audio driver.

   The write is blocking, which is deliberate: it is what paces the
   emulation. Fuse produces one frame of samples per emulated frame and
   blocks here until the device has room for them, so audio - not vsync and
   not a wall clock - is the emulator's clock.
*/

#include "config.h"

#include <aaudio/AAudio.h>
#include <android/log.h>

#include <libspectrum.h>

#include "settings.h"
#include "sound.h"
#include "ui/ui.h"

#define LOG_TAG "FuseNative"
#define sound_log( ... ) \
  __android_log_print( ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__ )
#define sound_logw( ... ) \
  __android_log_print( ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__ )

/* Roughly three Spectrum frames of slack: enough to ride out scheduling
   jitter without adding audible latency. */
#define BUFFER_BURSTS 3

/* Long enough that a write only times out if audio has genuinely stopped;
   short enough that we do not wedge the emulation thread if it has. */
#define WRITE_TIMEOUT_NS ( 200 * 1000 * 1000LL )

static AAudioStream *stream;
static int channels = 1;

int
sound_lowlevel_init( const char *device, int *freqptr, int *stereoptr )
{
  AAudioStreamBuilder *builder;
  aaudio_result_t result;

  result = AAudio_createStreamBuilder( &builder );
  if( result != AAUDIO_OK ) {
    ui_error( UI_ERROR_ERROR, "couldn't create an audio stream builder: %s",
              AAudio_convertResultToText( result ) );
    return 1;
  }

  AAudioStreamBuilder_setDirection( builder, AAUDIO_DIRECTION_OUTPUT );
  AAudioStreamBuilder_setFormat( builder, AAUDIO_FORMAT_PCM_I16 );
  AAudioStreamBuilder_setChannelCount( builder, *stereoptr ? 2 : 1 );
  AAudioStreamBuilder_setSampleRate( builder, *freqptr );
  AAudioStreamBuilder_setSharingMode( builder, AAUDIO_SHARING_MODE_SHARED );
  AAudioStreamBuilder_setPerformanceMode( builder,
                                          AAUDIO_PERFORMANCE_MODE_LOW_LATENCY );

  result = AAudioStreamBuilder_openStream( builder, &stream );
  AAudioStreamBuilder_delete( builder );

  if( result != AAUDIO_OK ) {
    ui_error( UI_ERROR_ERROR, "couldn't open the audio device: %s",
              AAudio_convertResultToText( result ) );
    stream = NULL;
    return 1;
  }

  AAudioStream_setBufferSizeInFrames(
    stream, AAudioStream_getFramesPerBurst( stream ) * BUFFER_BURSTS );

  /* Tell Fuse what we actually got, which need not be what we asked for. */
  channels = AAudioStream_getChannelCount( stream );
  *freqptr = AAudioStream_getSampleRate( stream );
  if( channels < 2 ) *stereoptr = 0;

  result = AAudioStream_requestStart( stream );
  if( result != AAUDIO_OK ) {
    ui_error( UI_ERROR_ERROR, "couldn't start the audio stream: %s",
              AAudio_convertResultToText( result ) );
    AAudioStream_close( stream );
    stream = NULL;
    return 1;
  }

  sound_log( "audio started: %d Hz, %d channel(s)", *freqptr, channels );

  return 0;
}

void
sound_lowlevel_end( void )
{
  if( !stream ) return;

  AAudioStream_requestStop( stream );
  AAudioStream_close( stream );
  stream = NULL;
}

void
sound_lowlevel_frame( libspectrum_signed_word *data, int len )
{
  /* Fuse counts samples across all channels; AAudio counts frames. */
  int frames = len / channels;

  if( !stream ) return;

  while( frames > 0 ) {
    aaudio_result_t written = AAudioStream_write( stream, data, frames,
                                                  WRITE_TIMEOUT_NS );

    if( written < 0 ) {
      /* The device has gone away - a headset unplugged mid-frame, say.
         Drop the stream rather than stalling the emulation on every frame
         from here on. */
      sound_logw( "audio write failed, disabling sound: %s",
                  AAudio_convertResultToText( written ) );
      sound_lowlevel_end();
      return;
    }

    if( written == 0 ) {
      sound_logw( "audio write timed out, dropping %d frames", frames );
      return;
    }

    data += written * channels;
    frames -= written;
  }
}
