package com.adlesstv.launcher;

import android.media.tv.TvInputManager;

/** Human labels for TvInputManager states; the framework reports them, nothing is polled. */
final class InputStates {
    private InputStates() { }

    static int label(int state) {
        if (state == TvInputManager.INPUT_STATE_DISCONNECTED) return R.string.input_disconnected;
        if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) return R.string.input_standby;
        return R.string.input_connected;
    }
}
