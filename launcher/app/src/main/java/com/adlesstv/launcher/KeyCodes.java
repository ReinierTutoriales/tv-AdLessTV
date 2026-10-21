package com.adlesstv.launcher;

import android.view.KeyEvent;

/** "190" or "KEYCODE_BUTTON_3" to a key code; 0 when it is not a valid key. */
final class KeyCodes {
    private KeyCodes() { }

    static int parse(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            int value = Integer.parseInt(text);
            return value > 0 && value <= KeyEvent.getMaxKeyCode() ? value : 0;
        } catch (NumberFormatException notNumber) {
            int value = KeyEvent.keyCodeFromString(text.startsWith("KEYCODE_") ? text : "KEYCODE_" + text);
            return value > 0 ? value : 0;
        }
    }
}
