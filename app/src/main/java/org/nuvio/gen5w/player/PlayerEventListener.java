package org.nuvio.gen5w.player;

/**
 * Event callbacks emitted by the native ExoPlayer playback service.
 */
public interface PlayerEventListener {
    void onPlayerStateChanged(boolean playWhenReady, int playbackState);
    void onPositionUpdate(long currentPositionMs, long durationMs);
    void onVideoSizeChanged(int width, int height);
    void onError(String errorMessage);
}

