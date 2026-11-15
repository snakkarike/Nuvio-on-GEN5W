package org.nuvio.gen5w.player;

import android.media.AudioManager;
import android.util.Log;

/**
 * AudioFocusHolder retains Nuvio as the active Gen5W AV source package
 * when playback pauses or stops, preventing the head unit's ModeManagerService
 * and AudioServiceHelper from immediately auto-restoring the FM radio screen.
 *
 * Gen5W monitors audio focus continuously: if an application abandons audio focus
 * when video playback stops, the head unit interprets this as "no media active"
 * and switches the screen back to FM Radio. By requesting focus on a process-scoped
 * no-op listener, Nuvio stays registered as the active AV mode package.
 *
 * Audio focus is released automatically when Nuvio's process terminates.
 */
public final class AudioFocusHolder {
    private static final String TAG = "AudioFocusHolder";

    private static final AudioManager.OnAudioFocusChangeListener NO_OP_LISTENER =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    // Process-wide no-op listener to hold active AV source state
                }
            };

    private static boolean holding = false;

    private AudioFocusHolder() {}

    /**
     * Acquires process-wide audio focus hold to keep Nuvio as the active AV source.
     */
    public static synchronized void hold(AudioManager audioManager) {
        if (audioManager == null) return;
        try {
            int result = audioManager.requestAudioFocus(
                    NO_OP_LISTENER,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
            holding = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            Log.d(TAG, "AudioFocusHolder hold: " + holding);
        } catch (Throwable t) {
            Log.w(TAG, "Cannot acquire audio focus hold: " + t.getMessage());
        }
    }

    /**
     * Explicitly releases the process-wide audio focus hold (e.g. on clean app exit).
     */
    public static synchronized void release(AudioManager audioManager) {
        if (audioManager == null || !holding) return;
        try {
            audioManager.abandonAudioFocus(NO_OP_LISTENER);
            holding = false;
            Log.d(TAG, "AudioFocusHolder released");
        } catch (Throwable t) {
            Log.w(TAG, "Cannot release audio focus hold: " + t.getMessage());
        }
    }

    public static synchronized boolean isHolding() {
        return holding;
    }
}
