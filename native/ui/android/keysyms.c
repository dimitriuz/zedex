/* keysyms.c: Android keycode to Fuse input layer mapping

   The other UIs generate this table from keysyms.dat via keysyms.pl, but
   that would mean adding an "android" column to a Fuse source file. The
   table is small enough to keep here instead, which leaves Fuse untouched.

   Both physical keyboards and the on-screen keyboard come through here, so
   the virtual keys the UI sends are simply Android keycodes.
*/

#include "config.h"

#include <android/keycodes.h>

#include <libspectrum.h>

#include "input.h"
#include "keyboard.h"

keysyms_map_t keysyms_map[] = {

  { AKEYCODE_A, INPUT_KEY_a }, { AKEYCODE_B, INPUT_KEY_b },
  { AKEYCODE_C, INPUT_KEY_c }, { AKEYCODE_D, INPUT_KEY_d },
  { AKEYCODE_E, INPUT_KEY_e }, { AKEYCODE_F, INPUT_KEY_f },
  { AKEYCODE_G, INPUT_KEY_g }, { AKEYCODE_H, INPUT_KEY_h },
  { AKEYCODE_I, INPUT_KEY_i }, { AKEYCODE_J, INPUT_KEY_j },
  { AKEYCODE_K, INPUT_KEY_k }, { AKEYCODE_L, INPUT_KEY_l },
  { AKEYCODE_M, INPUT_KEY_m }, { AKEYCODE_N, INPUT_KEY_n },
  { AKEYCODE_O, INPUT_KEY_o }, { AKEYCODE_P, INPUT_KEY_p },
  { AKEYCODE_Q, INPUT_KEY_q }, { AKEYCODE_R, INPUT_KEY_r },
  { AKEYCODE_S, INPUT_KEY_s }, { AKEYCODE_T, INPUT_KEY_t },
  { AKEYCODE_U, INPUT_KEY_u }, { AKEYCODE_V, INPUT_KEY_v },
  { AKEYCODE_W, INPUT_KEY_w }, { AKEYCODE_X, INPUT_KEY_x },
  { AKEYCODE_Y, INPUT_KEY_y }, { AKEYCODE_Z, INPUT_KEY_z },

  { AKEYCODE_0, INPUT_KEY_0 }, { AKEYCODE_1, INPUT_KEY_1 },
  { AKEYCODE_2, INPUT_KEY_2 }, { AKEYCODE_3, INPUT_KEY_3 },
  { AKEYCODE_4, INPUT_KEY_4 }, { AKEYCODE_5, INPUT_KEY_5 },
  { AKEYCODE_6, INPUT_KEY_6 }, { AKEYCODE_7, INPUT_KEY_7 },
  { AKEYCODE_8, INPUT_KEY_8 }, { AKEYCODE_9, INPUT_KEY_9 },

  { AKEYCODE_SPACE,      INPUT_KEY_space     },
  { AKEYCODE_ENTER,      INPUT_KEY_Return    },
  { AKEYCODE_NUMPAD_ENTER, INPUT_KEY_Return  },
  { AKEYCODE_DEL,        INPUT_KEY_BackSpace },
  { AKEYCODE_ESCAPE,     INPUT_KEY_Escape    },
  { AKEYCODE_TAB,        INPUT_KEY_Tab       },
  { AKEYCODE_CAPS_LOCK,  INPUT_KEY_Caps_Lock },

  { AKEYCODE_SHIFT_LEFT,  INPUT_KEY_Shift_L   },
  { AKEYCODE_SHIFT_RIGHT, INPUT_KEY_Shift_R   },
  { AKEYCODE_CTRL_LEFT,   INPUT_KEY_Control_L },
  { AKEYCODE_CTRL_RIGHT,  INPUT_KEY_Control_R },
  { AKEYCODE_ALT_LEFT,    INPUT_KEY_Alt_L     },
  { AKEYCODE_ALT_RIGHT,   INPUT_KEY_Alt_R     },

  { AKEYCODE_DPAD_UP,    INPUT_KEY_Up    },
  { AKEYCODE_DPAD_DOWN,  INPUT_KEY_Down  },
  { AKEYCODE_DPAD_LEFT,  INPUT_KEY_Left  },
  { AKEYCODE_DPAD_RIGHT, INPUT_KEY_Right },

  { AKEYCODE_COMMA,          INPUT_KEY_comma        },
  { AKEYCODE_PERIOD,         INPUT_KEY_period       },
  { AKEYCODE_MINUS,          INPUT_KEY_minus        },
  { AKEYCODE_EQUALS,         INPUT_KEY_equal        },
  { AKEYCODE_SEMICOLON,      INPUT_KEY_semicolon    },
  { AKEYCODE_APOSTROPHE,     INPUT_KEY_apostrophe   },
  { AKEYCODE_SLASH,          INPUT_KEY_slash        },
  { AKEYCODE_BACKSLASH,      INPUT_KEY_backslash    },
  { AKEYCODE_LEFT_BRACKET,   INPUT_KEY_bracketleft  },
  { AKEYCODE_RIGHT_BRACKET,  INPUT_KEY_bracketright },
  { AKEYCODE_GRAVE,          INPUT_KEY_asciitilde   },

  { AKEYCODE_F1,  INPUT_KEY_F1  }, { AKEYCODE_F2,  INPUT_KEY_F2  },
  { AKEYCODE_F3,  INPUT_KEY_F3  }, { AKEYCODE_F4,  INPUT_KEY_F4  },
  { AKEYCODE_F5,  INPUT_KEY_F5  }, { AKEYCODE_F6,  INPUT_KEY_F6  },
  { AKEYCODE_F7,  INPUT_KEY_F7  }, { AKEYCODE_F8,  INPUT_KEY_F8  },
  { AKEYCODE_F9,  INPUT_KEY_F9  }, { AKEYCODE_F10, INPUT_KEY_F10 },
  { AKEYCODE_F11, INPUT_KEY_F11 }, { AKEYCODE_F12, INPUT_KEY_F12 },

  { 0, 0 }			/* End marker */

};
