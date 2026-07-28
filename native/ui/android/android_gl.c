/* android_gl.c: GLES 3 presentation for the Android UI

   Uploads the emulated frame to a texture and draws it as an
   aspect-corrected quad. The fragment shader is deliberately the only place
   that touches pixels, so CRT/scanline filters drop straight in here.

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
static GLint uniform_scale;
static int texture_width, texture_height;

static const char vertex_shader_src[] =
  "#version 300 es\n"
  "in vec2 a_pos;\n"
  "out vec2 v_uv;\n"
  "uniform vec2 u_scale;\n"
  "void main() {\n"
  "  v_uv = a_pos * vec2( 0.5, -0.5 ) + 0.5;\n"
  "  gl_Position = vec4( a_pos * u_scale, 0.0, 1.0 );\n"
  "}\n";

static const char fragment_shader_src[] =
  "#version 300 es\n"
  "precision mediump float;\n"
  "in vec2 v_uv;\n"
  "uniform sampler2D u_tex;\n"
  "out vec4 colour;\n"
  "void main() {\n"
  "  colour = texture( u_tex, v_uv );\n"
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

  uniform_scale = glGetUniformLocation( program, "u_scale" );

  glGenBuffers( 1, &vbo );
  glBindBuffer( GL_ARRAY_BUFFER, vbo );
  glBufferData( GL_ARRAY_BUFFER, sizeof( quad ), quad, GL_STATIC_DRAW );

  glGenTextures( 1, &texture );
  glBindTexture( GL_TEXTURE_2D, texture );
  /* Nearest keeps the pixels crisp; filtering is a shader's job. */
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

  /* Letterbox: the Spectrum's 320x240 frame is 4:3 whatever the panel is. */
  view_aspect = (float) view_width / (float) view_height;
  image_aspect = (float) width / (float) height;
  if( view_aspect > image_aspect ) {
    scale_x = image_aspect / view_aspect;
  } else {
    scale_y = view_aspect / image_aspect;
  }
  glUniform2f( uniform_scale, scale_x, scale_y );

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
