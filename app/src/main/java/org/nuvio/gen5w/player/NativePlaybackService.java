package org.nuvio.gen5w.player;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoListener;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

/**
 * NativePlaybackService runs the ExoPlayer instance on Android 4.4 KitKat.
 * Handles hardware-accelerated playback, audio focus management, and custom HTTP headers.
 */
public class NativePlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "NativePlaybackService";
    private static final String DEFAULT_USER_AGENT = "Nuvio-GEN5W/1.0 (Android 4.4.2; armeabi-v7a)";

    public class LocalBinder extends Binder {
        public NativePlaybackService getService() {
            return NativePlaybackService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private SimpleExoPlayer player;
    private AudioManager audioManager;
    private PlayerEventListener eventListener;
    private Handler progressHandler;
    private boolean isPausedByTransientLoss = false;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.getPlaybackState() == Player.STATE_READY && player.getPlayWhenReady()) {
                if (eventListener != null) {
                    eventListener.onPositionUpdate(player.getCurrentPosition(), player.getDuration());
                }
            }
            if (progressHandler != null) {
                progressHandler.postDelayed(this, 500);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        progressHandler = new Handler(Looper.getMainLooper());
        initPlayer();
    }

    private void initPlayer() {
        if (player != null) {
            player.release();
        }

        player = new SimpleExoPlayer.Builder(this).build();

        player.addListener(new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                Log.d(TAG, "ExoPlayer state: playWhenReady=" + playWhenReady + ", state=" + playbackState);
                if (eventListener != null) {
                    eventListener.onPlayerStateChanged(playWhenReady, playbackState);
                }
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                String errorMsg = "Playback error: " + error.getMessage();
                Throwable cause = error.getCause();
                String details = cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : errorMsg;
                Log.e(TAG, "ExoPlayer error [" + error.type + "]: " + details, error);

                if (eventListener != null) {
                    eventListener.onError(details);
                }

                final String userNotice;
                if (error.type == ExoPlaybackException.TYPE_RENDERER) {
                    userNotice = "Codec error: Android 4.4 cannot decode this video format (HEVC/x265 unsupported). Please pick an H.264 stream.";
                } else if (error.type == ExoPlaybackException.TYPE_SOURCE) {
                    userNotice = "Network/Stream error: Failed to connect to stream source (" + details + ").";
                } else {
                    userNotice = "Playback error: " + details;
                }

                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        android.widget.Toast.makeText(getApplicationContext(),
                            userNotice,
                            android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        player.addVideoListener(new VideoListener() {
            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                Log.d(TAG, "Video size changed: " + width + "x" + height);
                if (eventListener != null) {
                    eventListener.onVideoSizeChanged(width, height);
                }
            }
        });

        progressHandler.post(progressRunnable);
    }

    public void setEventListener(PlayerEventListener listener) {
        this.eventListener = listener;
    }

    public void attachPlayerView(com.google.android.exoplayer2.ui.PlayerView playerView) {
        if (player != null && playerView != null) {
            playerView.setPlayer(player);
        }
    }

    public void attachSurfaceView(SurfaceView surfaceView) {
        if (player != null) {
            player.setVideoSurfaceView(surfaceView);
        }
    }

    public void detachSurfaceView() {
        if (player != null) {
            player.clearVideoSurface();
        }
    }

    public void loadMedia(String streamUrl, Map<String, String> headers, long startPositionMs) {
        if (player == null) {
            initPlayer();
        }

        // Install TLS 1.2 and modern Mozilla root certificates for HTTPS streams
        try {
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(VideoTls.build(this));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to apply VideoTls SSL socket factory: " + t.getMessage());
        }

        requestAudioFocus();

        Uri uri = Uri.parse(streamUrl);
        String userAgent = DEFAULT_USER_AGENT;
        if (headers != null && headers.containsKey("User-Agent")) {
            userAgent = headers.get("User-Agent");
        }

        DefaultHttpDataSourceFactory httpDataSourceFactory = new DefaultHttpDataSourceFactory(
                userAgent,
                null,
                15000,
                25000,
                true
        );

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (!entry.getKey().equalsIgnoreCase("User-Agent")) {
                    httpDataSourceFactory.getDefaultRequestProperties().set(entry.getKey(), entry.getValue());
                }
            }
        }

        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter.Builder(this).build();
        DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(this, bandwidthMeter, httpDataSourceFactory);

        MediaSource mediaSource;
        if (streamUrl.contains(".m3u8") || (uri.getPath() != null && uri.getPath().endsWith(".m3u8"))) {
            mediaSource = new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
        } else {
            mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
        }

        player.prepare(mediaSource, true, true);
        if (startPositionMs > 0) {
            player.seekTo(startPositionMs);
        }
        player.setPlayWhenReady(true);
    }

    public void play() {
        if (player != null) {
            requestAudioFocus();
            player.setPlayWhenReady(true);
        }
    }

    public void pause() {
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    public void togglePlay() {
        if (player != null) {
            if (player.getPlayWhenReady()) {
                pause();
            } else {
                play();
            }
        }
    }

    public void seekTo(long positionMs) {
        if (player != null) {
            player.seekTo(positionMs);
        }
    }

    public void stop() {
        if (player != null) {
            player.stop(true);
        }
        abandonAudioFocus();
    }

    public void setVolume(float volume) {
        if (player != null) {
            player.setVolume(Math.max(0.0f, Math.min(1.0f, volume)));
        }
    }

    public long getDuration() {
        return player != null ? player.getDuration() : 0;
    }

    public long getCurrentPosition() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    public boolean isPlaying() {
        return player != null && player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_READY;
    }

    public int getPlaybackState() {
        return player != null ? player.getPlaybackState() : Player.STATE_IDLE;
    }

    private void requestAudioFocus() {
        if (audioManager != null) {
            audioManager.requestAudioFocus(
                    this,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
        }
    }

    private void abandonAudioFocus() {
        if (audioManager != null) {
            audioManager.abandonAudioFocus(this);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (isPlaying()) {
                    isPausedByTransientLoss = true;
                    pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (isPausedByTransientLoss) {
                    isPausedByTransientLoss = false;
                    play();
                }
                break;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (progressHandler != null) {
            progressHandler.removeCallbacks(progressRunnable);
        }
        if (player != null) {
            player.release();
            player = null;
        }
        abandonAudioFocus();
        super.onDestroy();
    }
}

