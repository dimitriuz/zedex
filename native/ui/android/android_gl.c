/* android_gl.c: GLES 3 presentation for the Android UI

   Uploads the emulated frame to a texture and draws it as an
   aspect-corrected quad. The fragment shader is the only place that touches
   pixels, and it is one shader rather than one per filter: the effects branch
   on uniforms, which are constant across a draw and so cost a predictable
   nothing, and the alternative is three programs that share nine tenths of
   their code.

   The filters are written here rather than borrowed. RetroArch's .slang
   shaders are Vulkan GLSL and want glslang and SPIRV-Cross to become GLSL ES -
   two large C++ libraries, in an app that has none - and the format is a
   multi-pass pipeline with framebuffers, history and feedback textures rather
   than a fragment shader. Its hand-converted glsl-shaders are GLES-friendly
   and GPL-2-or-later, so those could be adopted, but only along with
   RetroArch's uniform names, its #pragma parameter directive and multi-pass
   render targets, since every CRT shader worth having is multi-pass. The
   parameters below are named and bounded the way theirs are, so that day is
   not made harder.

   Everything in this file runs on the emulation thread, which owns the EGL
   context; the JNI bridge is responsible for never handing us a window that
   Android has already taken back.
*/

#include "config.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <stdlib.h>

#include "android_internals.h"

static EGLDisplay display = EGL_NO_DISPLAY;
static EGLContext context = EGL_NO_CONTEXT;
static EGLSurface surface = EGL_NO_SURFACE;
static EGLConfig  config;

static ANativeWindow *bound_window;
static unsigned bound_generation;

static GLuint program, texture, vbo;
static int texture_width, texture_height;

static struct {
  GLint scale, offset, source, output, frame;
  GLint scanlines, crt, video;
  GLint sharpness, scanline, curve, mask, glow, bleed, noise;
} uniform;

/* What the app has asked for. Set from the emulation thread, read by it.

   Two switches rather than one choice, because they are two different things
   and a tube has both: scanlines are the beam, and the curve, the mask and the
   glow are the glass in front of it. Either can be had without the other. */
static struct {
  int scanlines, crt, video;
  /* 0 fits the picture to the window; anything else is that many device
     pixels per emulated pixel, kept exact. Which one applies is a question
     about the device's orientation, not about the shape of the box the screen
     was given - in portrait with the keyboard below, that box is wider than it
     is tall - so Java picks and this is only ever told the answer. */
  int scale;
  float sharpness, scanline, curve, mask, glow, bleed, noise;
} settings = { 0, 0, 0, 0, 1.0f, 0.5f, 0.4f, 0.4f, 0.3f, 0.5f, 0.2f };

/* Counts frames, for the parts of a signal that move: snow is different every
   frame or it is not snow. */
static unsigned frame_counter;

/* Whether the texture is currently sampled without interpolation. Nearest is
   the only way to be exactly pixel for pixel, so it stays the default and the
   sampler is only softened when a filter actually wants it. */
static int nearest = 1;

static const char vertex_shader_src[] =
  "#version 300 es\n"
  "in vec2 a_pos;\n"
  "out vec2 v_uv;\n"
  "uniform vec2 u_scale;\n"
  "uniform vec2 u_offset;\n"
  "void main() {\n"
  "  v_uv = a_pos * vec2( 0.5, -0.5 ) + 0.5;\n"
  "  gl_Position = vec4( a_pos * u_scale + u_offset, 0.0, 1.0 );\n"
  "}\n";

/* One shader, three looks. u_filter picks; the rest shape it.

   Everything is in units that mean something: u_source is the emulated frame
   in its own pixels, so a scanline is one of those and stays one however far
   the picture is scaled, and the mask is in output pixels, because a shadow
   mask is a property of the glass and not of the signal. */
static const char fragment_shader_src[] =
  "#version 300 es\n"
  "precision highp float;\n"
  "in vec2 v_uv;\n"
  "uniform sampler2D u_tex;\n"
  "uniform vec2 u_source;\n"
  "uniform vec2 u_output;\n"
  "uniform int u_scanlines;\n"
  "uniform int u_crt;\n"
  "uniform int u_video;\n"
  "uniform int u_frame;\n"
  "uniform float u_sharpness;\n"
  "uniform float u_scanline;\n"
  "uniform float u_curve;\n"
  "uniform float u_mask;\n"
  "uniform float u_glow;\n"
  "uniform float u_bleed;\n"
  "uniform float u_noise;\n"
  "out vec4 colour;\n"
  "\n"
  "const float PI = 3.14159265;\n"
  "\n"
  /* Bilinear sampling pulled towards the middle of each source pixel: at full
     sharpness this is nearest neighbour, and easing it off softens only the
     boundary rather than blurring the whole picture. */
  "vec2 sharpen( vec2 uv ) {\n"
  "  vec2 texel = uv * u_source;\n"
  "  vec2 middle = floor( texel ) + 0.5;\n"
  "  vec2 offset = texel - middle;\n"
  "  float steepness = mix( 1.0, 8.0, u_sharpness );\n"
  "  offset = clamp( offset * steepness, -0.5, 0.5 );\n"
  "  return ( middle + offset ) / u_source;\n"
  "}\n"
  "\n"
  /* Barrel distortion about the centre. Gentle: a tube is not a fishbowl. */
  "vec2 bend( vec2 uv ) {\n"
  "  vec2 centred = uv * 2.0 - 1.0;\n"
  "  centred *= 1.0 + u_curve * 0.12 * dot( centred, centred );\n"
  "  return centred * 0.5 + 0.5;\n"
  "}\n"
  "\n"
  /* An aperture grille in threes, in output pixels. */
  "vec3 grille( float x ) {\n"
  "  float which = mod( floor( x ), 3.0 );\n"
  "  vec3 tint = vec3( which == 0.0 ? 1.0 : 0.6,\n"
  "                    which == 1.0 ? 1.0 : 0.6,\n"
  "                    which == 2.0 ? 1.0 : 0.6 );\n"
  "  return mix( vec3( 1.0 ), tint, u_mask );\n"
  "}\n"
  "\n"
  /* Four taps around the pixel, squared so only the bright parts bloom. */
  "vec3 bloom( vec2 uv ) {\n"
  "  vec2 step = 1.5 / u_source;\n"
  "  vec3 sum = texture( u_tex, uv + vec2( step.x, 0.0 ) ).rgb\n"
  "           + texture( u_tex, uv - vec2( step.x, 0.0 ) ).rgb\n"
  "           + texture( u_tex, uv + vec2( 0.0, step.y ) ).rgb\n"
  "           + texture( u_tex, uv - vec2( 0.0, step.y ) ).rgb;\n"
  "  vec3 average = sum * 0.25;\n"
  "  return average * average;\n"
  "}\n"
  "\n"
  /* A Spectrum reached its television through a modulator, and what came out
     the other end was not what went in. Luma survived; chroma did not - it
     rode a subcarrier with a fraction of the bandwidth, so colour smeared
     sideways across several pixels while the edges stayed put. That is the
     whole of the composite look, and it is why magenta text on a 48K bled.

     Done in YIQ because that is what the encoding actually used: convert,
     blur I and Q along the line, keep Y from the middle, convert back. */
  "const mat3 TO_YIQ = mat3( 0.299,  0.596,  0.211,\n"
  "                          0.587, -0.274, -0.523,\n"
  "                          0.114, -0.322,  0.312 );\n"
  "const mat3 TO_RGB = mat3( 1.0,    1.0,    1.0,\n"
  "                          0.956, -0.272, -1.106,\n"
  "                          0.621, -0.647,  1.703 );\n"
  "\n"
  "vec3 modulated( vec2 uv, vec3 centre ) {\n"
  "  float spread = u_bleed * 3.0 / u_source.x;\n"
  "  vec3 yiq = TO_YIQ * centre;\n"
  "  vec2 chroma = vec2( 0.0 );\n"
  "  float total = 0.0;\n"
  "\n"
  "  for( int tap = -3; tap <= 3; tap++ ) {\n"
  "    float weight = 1.0 - abs( float( tap ) ) / 4.0;\n"
  "    vec3 near = TO_YIQ * texture( u_tex, uv + vec2( float( tap ) * spread,\n"
  "                                                    0.0 ) ).rgb;\n"
  "    chroma += weight * near.yz;\n"
  "    total += weight;\n"
  "  }\n"
  "\n"
  "  yiq.yz = chroma / total;\n"
  "  return TO_RGB * yiq;\n"
  "}\n"
  "\n"
  /* Aerial rather than a lead: the same smearing, plus what an analogue tuner
     adds of its own. Snow, which moves; and a faint horizontal ripple, which
     is the subcarrier beating against the luma and is what dot crawl looks
     like when you are not looking closely. */
  "float snow( vec2 where ) {\n"
  "  vec3 seed = vec3( where, float( u_frame ) );\n"
  "  return fract( sin( dot( seed, vec3( 12.9898, 78.233, 37.719 ) ) )\n"
  "                * 43758.5453 );\n"
  "}\n"
  "\n"
  "void main() {\n"
  "  vec2 uv = v_uv;\n"
  "\n"
  "  if( u_crt == 1 && u_curve > 0.0 ) {\n"
  "    uv = bend( uv );\n"
  /* Past the edge of the tube there is no picture, only cabinet. */
  "    if( any( lessThan( uv, vec2( 0.0 ) ) ) ||\n"
  "        any( greaterThan( uv, vec2( 1.0 ) ) ) ) {\n"
  "      colour = vec4( 0.0, 0.0, 0.0, 1.0 );\n"
  "      return;\n"
  "    }\n"
  "  }\n"
  "\n"
  "  vec3 rgb = texture( u_tex, sharpen( uv ) ).rgb;\n"
  "\n"
  /* The signal first: it is what arrived, and the glass acts on that. */
  "  if( u_video > 0 && u_bleed > 0.0 ) rgb = modulated( uv, rgb );\n"
  "\n"
  "  if( u_video == 2 ) {\n"
  "    float ripple = sin( uv.y * u_source.y * PI * 2.0\n"
  "                        + float( u_frame ) * 0.7 );\n"
  "    rgb *= 1.0 + u_noise * 0.06 * ripple;\n"
  "    rgb += u_noise * 0.18 * ( snow( gl_FragCoord.xy ) - 0.5 );\n"
  "  }\n"
  "\n"
  "  if( u_scanlines == 0 && u_crt == 0 ) {\n"
  "    colour = vec4( clamp( rgb, 0.0, 1.0 ), 1.0 );\n"
  "    return;\n"
  "  }\n"
  "\n"
  /* One dark line per emulated row, brightest through the middle of it. A
     beam is not a step, so this is a sine and not a stripe. */
  "  float scanned = 0.0;\n"
  "  if( u_scanlines == 1 ) {\n"
  "    float across = fract( uv.y * u_source.y );\n"
  "    float beam = sin( across * PI );\n"
  "    rgb *= 1.0 - u_scanline * ( 1.0 - beam );\n"
  "    scanned = u_scanline;\n"
  "  }\n"
  "\n"
  "  float masked = 0.0;\n"
  "  if( u_crt == 1 ) {\n"
  "    if( u_mask > 0.0 ) { rgb *= grille( gl_FragCoord.x ); masked = u_mask; }\n"
  "    if( u_glow > 0.0 ) rgb += u_glow * 0.35 * bloom( uv );\n"
  "  }\n"
  "\n"
  /* Scanlines and a mask both take light away, and a filter that only made
     the picture dimmer would be a poor trade. Give back roughly what they
     cost, so switching one on changes the texture and not the exposure. */
  "  rgb *= 1.0 + 0.45 * scanned + 0.25 * masked;\n"
  "\n"
  "  colour = vec4( clamp( rgb, 0.0, 1.0 ), 1.0 );\n"
  "}\n";

static const GLfloat quad[] = {
  -1.0f, -1.0f,
   1.0f, -1.0f,
  -1.0f,  1.0f,
   1.0f,  1.0f,
};

static GLuint
compile_shader( GLenum type, const char *source )
{
  GLuint shader = glCreateShader( type );
  GLint ok = 0;

  glShaderSource( shader, 1, &source, NULL );
  glCompileShader( shader );
  glGetShaderiv( shader, GL_COMPILE_STATUS, &ok );

  if( !ok ) {
    char log[ 512 ];
    glGetShaderInfoLog( shader, sizeof( log ), NULL, log );
    android_logw( "shader compilation failed: %s", log );
    glDeleteShader( shader );
    return 0;
  }

  return shader;
}

static int
create_program( void )
{
  GLuint vertex, fragment;
  GLint ok = 0;

  vertex = compile_shader( GL_VERTEX_SHADER, vertex_shader_src );
  if( !vertex ) return 1;

  fragment = compile_shader( GL_FRAGMENT_SHADER, fragment_shader_src );
  if( !fragment ) { glDeleteShader( vertex ); return 1; }

  program = glCreateProgram();
  glAttachShader( program, vertex );
  glAttachShader( program, fragment );
  glBindAttribLocation( program, 0, "a_pos" );
  glLinkProgram( program );
  glGetProgramiv( program, GL_LINK_STATUS, &ok );

  glDeleteShader( vertex );
  glDeleteShader( fragment );

  if( !ok ) {
    char log[ 512 ];
    glGetProgramInfoLog( program, sizeof( log ), NULL, log );
    android_logw( "shader link failed: %s", log );
    return 1;
  }

  uniform.scale     = glGetUniformLocation( program, "u_scale" );
  uniform.offset    = glGetUniformLocation( program, "u_offset" );
  uniform.source    = glGetUniformLocation( program, "u_source" );
  uniform.output    = glGetUniformLocation( program, "u_output" );
  uniform.scanlines = glGetUniformLocation( program, "u_scanlines" );
  uniform.crt       = glGetUniformLocation( program, "u_crt" );
  uniform.video     = glGetUniformLocation( program, "u_video" );
  uniform.frame     = glGetUniformLocation( program, "u_frame" );
  uniform.bleed     = glGetUniformLocation( program, "u_bleed" );
  uniform.noise     = glGetUniformLocation( program, "u_noise" );
  uniform.sharpness = glGetUniformLocation( program, "u_sharpness" );
  uniform.scanline  = glGetUniformLocation( program, "u_scanline" );
  uniform.curve     = glGetUniformLocation( program, "u_curve" );
  uniform.mask      = glGetUniformLocation( program, "u_mask" );
  uniform.glow      = glGetUniformLocation( program, "u_glow" );

  glGenBuffers( 1, &vbo );
  glBindBuffer( GL_ARRAY_BUFFER, vbo );
  glBufferData( GL_ARRAY_BUFFER, sizeof( quad ), quad, GL_STATIC_DRAW );

  glGenTextures( 1, &texture );
  glBindTexture( GL_TEXTURE_2D, texture );
  /* Nearest by default, which is the only way to be exactly pixel for pixel.
     apply_sampler() softens it if a filter asks. */
  glTexParameteri( GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST );
  glTexParameteri( GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST );
  glTexParameteri( GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE );
  glTexParameteri( GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE );

  return 0;
}

static int
create_context( void )
{
  const EGLint config_attributes[] = {
    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
    EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
    EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
    EGL_NONE
  };
  const EGLint context_attributes[] = {
    EGL_CONTEXT_CLIENT_VERSION, 3,
    EGL_NONE
  };
  EGLint num_config;

  display = eglGetDisplay( EGL_DEFAULT_DISPLAY );
  if( display == EGL_NO_DISPLAY ) {
    android_logw( "eglGetDisplay failed" );
    return 1;
  }

  if( !eglInitialize( display, NULL, NULL ) ) {
    android_logw( "eglInitialize failed: 0x%x", eglGetError() );
    return 1;
  }

  if( !eglChooseConfig( display, config_attributes, &config, 1, &num_config )
      || num_config < 1 ) {
    android_logw( "no suitable EGL config" );
    return 1;
  }

  context = eglCreateContext( display, config, EGL_NO_CONTEXT,
                              context_attributes );
  if( context == EGL_NO_CONTEXT ) {
    android_logw( "eglCreateContext failed: 0x%x", eglGetError() );
    return 1;
  }

  return 0;
}

static int
attach( ANativeWindow *window )
{
  if( display == EGL_NO_DISPLAY && create_context() ) return 1;

  surface = eglCreateWindowSurface( display, config, window, NULL );
  if( surface == EGL_NO_SURFACE ) {
    android_logw( "eglCreateWindowSurface failed: 0x%x", eglGetError() );
    return 1;
  }

  if( !eglMakeCurrent( display, surface, surface, context ) ) {
    android_logw( "eglMakeCurrent failed: 0x%x", eglGetError() );
    eglDestroySurface( display, surface );
    surface = EGL_NO_SURFACE;
    return 1;
  }

  /* The program belongs to the context, which outlives the surface, so it
     only has to be built once. */
  if( !program && create_program() ) return 1;

  texture_width = texture_height = 0;

  return 0;
}

void
androidgl_detach( void )
{
  if( display == EGL_NO_DISPLAY ) return;

  eglMakeCurrent( display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT );

  if( surface != EGL_NO_SURFACE ) {
    eglDestroySurface( display, surface );
    surface = EGL_NO_SURFACE;
  }

  bound_window = NULL;
}

/* Softening the sampler is only worth doing when something wants it: at full
   sharpness the shader's own snapping already lands on the middle of a texel,
   so nearest is both faster and exactly right. Called with the texture bound. */
static void
apply_sampler( void )
{
  int wanted = settings.sharpness >= 1.0f && settings.video == 0;

  if( wanted == nearest ) return;

  nearest = wanted;
  glTexParameteri( GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                   nearest ? GL_NEAREST : GL_LINEAR );
  glTexParameteri( GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER,
                   nearest ? GL_NEAREST : GL_LINEAR );
}

void
androidgl_set_scale( int pixels )
{
  settings.scale = pixels;
}

void
androidgl_set_filter( int scanlines, int crt, int video, int sharpness,
                      int scanline, int curve, int mask, int glow, int bleed,
                      int noise )
{
  settings.scanlines = scanlines;
  settings.crt = crt;
  settings.video = video;
  settings.bleed = bleed / 100.0f;
  settings.noise = noise / 100.0f;
  settings.sharpness = sharpness / 100.0f;
  settings.scanline = scanline / 100.0f;
  settings.curve = curve / 100.0f;
  settings.mask = mask / 100.0f;
  settings.glow = glow / 100.0f;
}

/* Where the picture goes in the window, as a scale and an offset in clip
   space.

   Fitting is the easy half: the frame is 4:3 whatever the panel is, so one axis
   is shrunk until the aspect matches and the rest is black.

   An integer scale is the interesting one. The whole point of asking for one is
   that every emulated pixel becomes exactly the same number of real ones, and
   that only holds if the quad is a whole number of pixels wide *and* starts on
   one. Centring it can leave half a pixel over - the window is rarely an exact
   multiple of anything - and half a pixel with GL_NEAREST is a row of doubled
   pixels down one edge. So the offset is computed from a floored pixel position
   rather than by centring the quad and hoping.

   A scale too big for the window is reduced until it fits, and if even one to
   one will not fit the picture is fitted instead. Better a smaller picture than
   one with its edges off the screen. */
static void
place( int view_width, int view_height, int width, int height )
{
  int wanted = settings.scale;
  float scale_x = 1.0f, scale_y = 1.0f, view_aspect, image_aspect;

  while( wanted > 1 && ( wanted * width > view_width ||
                         wanted * height > view_height ) ) {
    wanted--;
  }

  if( wanted >= 1 && wanted * width <= view_width &&
      wanted * height <= view_height ) {
    int drawn_width = wanted * width;
    int drawn_height = wanted * height;
    int left = ( view_width - drawn_width ) / 2;
    int top = ( view_height - drawn_height ) / 2;

    glUniform2f( uniform.scale, (float) drawn_width / view_width,
                 (float) drawn_height / view_height );

    /* Clip space is -1 to 1 across the window, so the centre of a rectangle
       starting at `left' and `drawn_width' wide is this. */
    glUniform2f( uniform.offset,
                 ( 2.0f * left + drawn_width ) / view_width - 1.0f,
                 1.0f - ( 2.0f * top + drawn_height ) / view_height );
    return;
  }

  view_aspect = (float) view_width / (float) view_height;
  image_aspect = (float) width / (float) height;

  if( view_aspect > image_aspect ) {
    scale_x = image_aspect / view_aspect;
  } else {
    scale_y = view_aspect / image_aspect;
  }

  glUniform2f( uniform.scale, scale_x, scale_y );
  glUniform2f( uniform.offset, 0.0f, 0.0f );
}

void
androidgl_frame( ANativeWindow *window, unsigned generation,
                 const void *pixels, int width, int height )
{
  EGLint view_width, view_height;
  float scale_x = 1.0f, scale_y = 1.0f, view_aspect, image_aspect;

  if( !window ) return;

  if( window != bound_window || generation != bound_generation ) {
    androidgl_detach();
    if( attach( window ) ) return;
    bound_window = window;
    bound_generation = generation;
  }

  if( surface == EGL_NO_SURFACE ) return;

  eglQuerySurface( display, surface, EGL_WIDTH, &view_width );
  eglQuerySurface( display, surface, EGL_HEIGHT, &view_height );
  if( view_width <= 0 || view_height <= 0 ) return;

  glViewport( 0, 0, view_width, view_height );
  glClearColor( 0.0f, 0.0f, 0.0f, 1.0f );
  glClear( GL_COLOR_BUFFER_BIT );

  glUseProgram( program );
  glBindTexture( GL_TEXTURE_2D, texture );

  if( width != texture_width || height != texture_height ) {
    glTexImage2D( GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA,
                  GL_UNSIGNED_BYTE, pixels );
    texture_width = width;
    texture_height = height;
  } else {
    glTexSubImage2D( GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA,
                     GL_UNSIGNED_BYTE, pixels );
  }

  place( view_width, view_height, width, height );

  apply_sampler();

  glUniform2f( uniform.source, (float) width, (float) height );
  glUniform2f( uniform.output, (float) view_width, (float) view_height );
  glUniform1i( uniform.scanlines, settings.scanlines );
  glUniform1i( uniform.crt, settings.crt );
  glUniform1i( uniform.video, settings.video );
  glUniform1i( uniform.frame, (GLint) ( frame_counter++ & 0xffff ) );
  glUniform1f( uniform.bleed, settings.bleed );
  glUniform1f( uniform.noise, settings.noise );
  glUniform1f( uniform.sharpness, settings.sharpness );
  glUniform1f( uniform.scanline, settings.scanline );
  glUniform1f( uniform.curve, settings.curve );
  glUniform1f( uniform.mask, settings.mask );
  glUniform1f( uniform.glow, settings.glow );

  glBindBuffer( GL_ARRAY_BUFFER, vbo );
  glEnableVertexAttribArray( 0 );
  glVertexAttribPointer( 0, 2, GL_FLOAT, GL_FALSE, 0, NULL );
  glDrawArrays( GL_TRIANGLE_STRIP, 0, 4 );

  eglSwapBuffers( display, surface );
}

void
androidgl_end( void )
{
  androidgl_detach();

  if( display == EGL_NO_DISPLAY ) return;

  if( context != EGL_NO_CONTEXT ) eglDestroyContext( display, context );
  eglTerminate( display );

  display = EGL_NO_DISPLAY;
  context = EGL_NO_CONTEXT;
  program = 0;
}
