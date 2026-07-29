/* aaudiosound.c: AAudio sound output for Fuse

   Substituted for sound/nullsound.o at link time (see
   scripts/build-native.sh), so Fuse itself needs no new audio driver.

   The write is blocking, which is deliberate: it is what paces the
   emulation. Fuse produces one frame of samples per emulated frame and
   blocks here until the device has room for them, so audio - not vsync and
   not a wall clock - is the emulator's clock.

   That works while the device takes its audio in lumps smaller than a
   Spectrum frame, which is what a phone asked for low latency does. Where it
   does not - an emulator's audio device, or a Bluetooth headset - the write
   comes back only once per lump and the emulation would advance in lumps too,
   so pace_frame() holds it to the clock instead and lets the queue absorb the
   lumps. See COARSE_LUMP_MS.
*/

#include "config.h"

#include <errno.h>
#include <time.h>

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

/* A device that takes its audio in lumps this long or longer cannot be the
   emulator's clock without the emulation moving in lumps as well: a blocking
   write only comes back when the device has swallowed a whole lump, so the
   machine runs the two or three frames that fit and then waits. The sound is
   continuous - it is buffered - but the picture stops for as long as the lump
   lasts and then jumps, which is exactly the stutter this guards against.

   Half a Spectrum frame. Phones asked for low latency usually answer with two
   to five milliseconds and are nowhere near it; an emulator's audio device and
   anything played over Bluetooth are, and the emulator has to cope with both.
*/
#define COARSE_LUMP_MS 10

/* Long enough that a write only times out if audio has genuinely stopped;
   short enough that we do not wedge the emulation thread if it has. */
#define WRITE_TIMEOUT_NS ( 200 * 1000 * 1000LL )

static AAudioStream *stream;
static int channels = 1;

static int sample_rate;		/* sample frames a second */
static int lump;		/* what the device swallows at a time */
static int coarse;		/* whether that is too big to be the clock */
static int target_queue;	/* how much audio to keep queued ahead */
static long long frame_due;	/* when the next emulated frame may end */

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

  sample_rate = *freqptr;
  lump = AAudioStream_getFramesPerBurst( stream );
  coarse = sample_rate > 0
           && (long long) lump * 1000 / sample_rate >= COARSE_LUMP_MS;

  /* Half of what the device will hold before a write blocks: a lump of
     cushion against running dry, and a lump of room so the write does not
     block and undo the pacing. */
  target_queue = AAudioStream_getBufferSizeInFrames( stream ) / 2;
  if( target_queue < lump / 2 ) target_queue = lump / 2;

  frame_due = 0;

  result = AAudioStream_requestStart( stream );
  if( result != AAUDIO_OK ) {
    ui_error( UI_ERROR_ERROR, "couldn't start the audio stream: %s",
              AAudio_convertResultToText( result ) );
    AAudioStream_close( stream );
    stream = NULL;
    return 1;
  }

  sound_log( "audio started: %d Hz, %d channel(s), %d frames a lump (%lldms)"
             "%s, keeping %d queued",
             sample_rate, channels, lump,
             (long long) lump * 1000 / ( sample_rate ? sample_rate : 1 ),
             coarse ? ", too coarse to be the clock" : "", target_queue );

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

static long long
audio_now_ns( void )
{
  struct timespec now;

  clock_gettime( CLOCK_MONOTONIC, &now );
  return now.tv_sec * 1000000000LL + now.tv_nsec;
}

/* Holds the emulation back to real time when the device's lumps are too big
   to do it evenly.
 *
 * Fuse hands over exactly one frame's worth of samples per emulated frame, so
 * how long that frame should have taken is simply how long the samples will
 * take to play - which also means this needs no notion of the emulation speed:
 * at two hundred per cent Fuse resamples and hands over half as many.
 *
 * The wall clock and the audio device's clock are not the same clock, and a
 * few parts in a thousand between them is enough to empty or overflow the
 * queue within a minute, so the queue's own depth trims the next deadline:
 * shorter while it is draining, longer while it is filling. That keeps the
 * audio device the clock in the long run - which is the point of it, since it
 * is the one clock the sound cannot drift against - while the emulation
 * advances a frame at a time rather than a lump at a time.
 */
static void
pace_frame( int frames )
{
  long long period = (long long) frames * 1000000000LL / sample_rate;
  long long now = audio_now_ns();
  long long queued;
  struct timespec until;

  /* The first frame, or one so far behind that catching up would be a rush -
     sound paused for a fastload, or the app in the background. Start again
     from here rather than running a burst of frames to nowhere. */
  if( !frame_due || frame_due < now - 4 * period ) {
    frame_due = now + period;
    return;
  }

  if( frame_due > now ) {
    until.tv_sec = frame_due / 1000000000LL;
    until.tv_nsec = frame_due % 1000000000LL;
    while( clock_nanosleep( CLOCK_MONOTONIC, TIMER_ABSTIME, &until, NULL )
           == EINTR );
  }

  frame_due += period;

  queued = AAudioStream_getFramesWritten( stream )
         - AAudioStream_getFramesRead( stream );

  /* An eighth of the error, so the correction is spread over a dozen frames
     and never fast enough to hear. */
  frame_due += ( queued - target_queue ) * 1000000000LL / sample_rate / 8;
}

void
sound_lowlevel_frame( libspectrum_signed_word *data, int len )
{
  /* Fuse counts samples across all channels; AAudio counts frames. */
  int frames = len / channels;

  if( !stream ) return;

  /* On a device with fine lumps the blocking write below is the clock, and
     the best one available. On a coarse one it cannot be, so the emulation is
     held to the wall clock here and the queue is left to absorb the lumps. */
  if( coarse ) pace_frame( frames );

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
