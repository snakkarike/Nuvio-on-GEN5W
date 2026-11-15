package org.nuvio.gen5w.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoListener;

import org.nuvio.gen5w.gate.MotionGuard;
import org.nuvio.gen5w.gate.VehicleVideoGate;
import org.nuvio.gen5w.sync.PlaybackSync;
import org.nuvio.gen5w.ui.ThemeManager;

import java.util.ArrayList;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * Standard ExoPlayer Activity for Nuvio on Hyundai/Kia Gen5W (Android 4.4 KitKat).
 * Supports 1920x720 fullscreen, on-screen controls, aspect ratio cycling, audio track selection,
 * subtitle selection, and vehicle motion lockout.
 */
public class ExoPlayerActivity extends Activity {
    private SimpleExoPlayer player;
    private DefaultTrackSelector selector;
    private SSLSocketFactory originalTls;
    private AudioManager audioManager;
    private boolean audioFocusGranted = false;
    private boolean released = false, firstFrame = false, controlsVisible = true, seeking = false;
    private long lastPosition = 0, lastDuration = 0, openedAt = 0;
    private long watchedElapsedMs = 0, lastWatchSample = 0;
    private PlaybackSync cloudSync;
    private String streamUrl = "", videoTitle = "";
    private FrameLayout root;
    private PlayerView video;
    private View brightnessShade;
    private FrameLayout activeTrackOverlay;
    private int pictureBrightness = 50;
    private LinearLayout topPanel, bottomPanel, transportPanel;
    private TextView title, positionLabel, durationLabel, status;
    private Button pauseButton, aspectButton, audioButton, subtitleButton;
    private SeekBar seek;
    private int aspect = 0;
    private boolean userPaused = false, autoplayStarted = false;
    private boolean motionFeatureEnabled = false, motionLocked = false;
    private MotionGuard motionGuard;
    private Dialog motionDialog;
    private TextView motionDialogText;
    private VehicleVideoGate vehicleGate;
    private FrameLayout safetyLockoutOverlay;
    private TextView safetyLockoutText;
    private float savedWindowBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private boolean manualAudioSelected = false;
    private String selectedEnglishTrackKey = "";
    private boolean playerPrepared = false;
    private MediaSource pendingMediaSource = null;
    private final Handler handler = new Handler();
    private ThemeManager.Palette theme;

    private static final int ACCENT = 0xffa855f7, WHITE = Color.WHITE, PANEL_BASE = 0xff131620, BORDER = 0xff283344, MUTED = 0xff94a3b8;

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focus) {
            if (player == null || released) return;
            if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                player.setPlayWhenReady(false);
                showControls(true);
            } else if (focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                player.setVolume(0.25f);
            } else if (focus == AudioManager.AUDIOFOCUS_GAIN) {
                player.setVolume(1f);
            }
        }
    };

    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && !released && player.getPlayWhenReady() && !seeking) {
                showControls(false);
            }
        }
    };

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (released || player == null) return;
            try {
                long p = player.getCurrentPosition(), d = player.getDuration();
                if (p >= 0) lastPosition = p;
                if (d > 0 && d != C.TIME_UNSET) lastDuration = d;
                long sample = android.os.SystemClock.elapsedRealtime();
                if (lastWatchSample > 0 && player.getPlayWhenReady() &&
                        player.getPlaybackState() == Player.STATE_READY && firstFrame) {
                    watchedElapsedMs += Math.min(1000, Math.max(0, sample - lastWatchSample));
                }
                lastWatchSample = sample;
                if (cloudSync != null && firstFrame && lastPosition >= 5000 && lastDuration > 0) {
                    cloudSync.record(lastPosition, lastDuration, watchedElapsedMs, false);
                }
                if (!seeking) {
                    if (seek != null && lastDuration > 0) {
                        seek.setProgress((int) Math.min(1000, lastPosition * 1000L / lastDuration));
                        long buffered = player.getBufferedPosition();
                        seek.setSecondaryProgress((int) Math.min(1000, Math.max(0, buffered) * 1000L / lastDuration));
                    }
                    if (positionLabel != null) positionLabel.setText(time(lastPosition));
                    if (durationLabel != null) durationLabel.setText(time(lastDuration));
                }
                if (pauseButton != null) pauseButton.setText(player.getPlayWhenReady() ? "Ⅱ" : "▶");
            } catch (Throwable ignored) {}
            handler.postDelayed(this, 750);
        }
    };

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String time(long ms) {
        if (ms < 0) return "--:--";
        long sec = ms / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60);
    }

    private TextView text(String value, int sp) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(theme != null ? theme.onSurface : WHITE);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        return t;
    }

    private GradientDrawable pill(int bg, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(bg);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private GradientDrawable shade(boolean atTop) {
        int solidScrim = (theme != null) ? (0xF2000000 | (theme.surface & 0x00FFFFFF)) : 0xed0b0d14;
        int midScrim = (theme != null) ? (0xB0000000 | (theme.surface & 0x00FFFFFF)) : 0xc90e101b;
        int transScrim = (theme != null) ? (0x00000000 | (theme.surface & 0x00FFFFFF)) : 0x000b0d14;
        int[] colors = atTop ?
                new int[]{solidScrim, midScrim, transScrim} :
                new int[]{transScrim, midScrim, solidScrim};
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        if (theme != null) {
            ThemeManager.stylePillButton(this, b, (0xB8000000 | (theme.surfaceContainerHigh & 0x00FFFFFF)), theme.onSurface, 1, theme.outlineVariant, false);
        } else {
            b.setTextColor(WHITE);
            b.setBackgroundDrawable(pill(0xcc181d2a, BORDER, 12));
        }
        b.setOnClickListener(listener);
        return b;
    }

    private Button tool(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        if (theme != null) {
            ThemeManager.stylePillButton(this, b, (0xC8000000 | (theme.surfaceContainerHigh & 0x00FFFFFF)), theme.onSurface, 1, theme.outlineVariant, true);
        } else {
            b.setTextColor(WHITE);
            b.setBackgroundDrawable(pill(0xdd1e2334, 0xff323a4d, 12));
        }
        b.setOnClickListener(listener);
        return b;
    }

    private Button roundControl(String symbol, int size, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(symbol);
        b.setTextSize(primary ? 32 : 21);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        if (theme != null) {
            if (primary) {
                ThemeManager.stylePillButton(this, b, theme.primary, theme.onPrimary, 0, 0, true);
            } else {
                ThemeManager.stylePillButton(this, b, (0xCC000000 | (theme.surfaceContainerHigh & 0x00FFFFFF)), theme.onSurface, 1, theme.outlineVariant, false);
            }
        } else {
            b.setTextColor(WHITE);
            b.setBackgroundDrawable(pill(primary ? ACCENT : 0xe6251a38, primary ? 0 : 0xff3b485d, size / 2));
        }
        b.setOnClickListener(listener);
        return b;
    }

    private void styleSeekBar(SeekBar sb) {
        if (sb == null) return;
        try {
            int trackH = dp(4);
            int radius = dp(2);

            GradientDrawable bgTrack = new GradientDrawable();
            bgTrack.setShape(GradientDrawable.RECTANGLE);
            bgTrack.setColor(theme != null ? (0x55000000 | (theme.onSurface & 0x00FFFFFF)) : 0x44ffffff);
            bgTrack.setCornerRadius(radius);
            bgTrack.setSize(-1, trackH);

            GradientDrawable bufTrack = new GradientDrawable();
            bufTrack.setShape(GradientDrawable.RECTANGLE);
            bufTrack.setColor(theme != null ? (0x88000000 | (theme.primary & 0x00FFFFFF)) : 0x88a855f7);
            bufTrack.setCornerRadius(radius);
            bufTrack.setSize(-1, trackH);
            ClipDrawable bufClip = new ClipDrawable(bufTrack, Gravity.LEFT, ClipDrawable.HORIZONTAL);

            GradientDrawable progTrack = new GradientDrawable();
            progTrack.setShape(GradientDrawable.RECTANGLE);
            progTrack.setColor(theme != null ? theme.primary : 0xffa855f7);
            progTrack.setCornerRadius(radius);
            progTrack.setSize(-1, trackH);
            ClipDrawable progClip = new ClipDrawable(progTrack, Gravity.LEFT, ClipDrawable.HORIZONTAL);

            Drawable[] layers = new Drawable[]{ bgTrack, bufClip, progClip };
            LayerDrawable ld = new LayerDrawable(layers);
            ld.setId(0, android.R.id.background);
            ld.setId(1, android.R.id.secondaryProgress);
            ld.setId(2, android.R.id.progress);
            sb.setProgressDrawable(ld);

            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(theme != null ? theme.primary : 0xffa855f7);
            thumb.setSize(dp(18), dp(18));
            sb.setThumb(thumb);
        } catch (Throwable ignored) {}
    }

    private void spaced(LinearLayout row, Button b, int width, int height, int space) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), dp(height));
        lp.setMargins(dp(space), 0, dp(space), 0);
        row.addView(b, lp);
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        if (controlsVisible && player != null && player.getPlayWhenReady() && !seeking) {
            handler.postDelayed(hideRunnable, 4800);
        }
    }

    private void showControls(boolean visible) {
        controlsVisible = visible;
        if (topPanel != null) topPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (bottomPanel != null) bottomPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (transportPanel != null) transportPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(hideRunnable);
        if (visible) scheduleHide();
    }

    private void status(String value) {
        if (status == null) return;
        status.setText(value);
        status.setVisibility(View.VISIBLE);
    }

    private void clearStatus() {
        if (status != null) status.setVisibility(View.GONE);
    }

    private void updateAspect() {
        if (video == null) return;
        int mode = aspect == 0 ? AspectRatioFrameLayout.RESIZE_MODE_FIT :
                aspect == 1 ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FILL;
        video.setResizeMode(mode);
        if (aspectButton != null) {
            aspectButton.setText(aspect == 0 ? "Fit (16:9)" : aspect == 1 ? "Fill (21:9)" : "Stretch");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }

        theme = ThemeManager.loadSavedPalette(this);

        Intent intent = getIntent();
        streamUrl = intent.getStringExtra("stream_url");
        videoTitle = intent.getStringExtra("stream_title");
        if (videoTitle == null) videoTitle = "Video Playback";

        cloudSync = new PlaybackSync(this,
                intent.getStringExtra("sync_meta_id"),
                intent.getStringExtra("sync_media_type"),
                intent.getStringExtra("sync_video_id"),
                intent.getStringExtra("sync_item_title"),
                intent.getStringExtra("sync_poster"));

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int res = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            audioFocusGranted = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new PlayerView(this);
        video.setUseController(false);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));

        brightnessShade = new View(this);
        brightnessShade.setBackgroundColor(Color.TRANSPARENT);
        root.addView(brightnessShade, new FrameLayout.LayoutParams(-1, -1));

        // Touch listener for controls toggle
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    showControls(!controlsVisible);
                }
                return true;
            }
        });

        // Top Panel
        topPanel = new LinearLayout(this);
        topPanel.setOrientation(LinearLayout.HORIZONTAL);
        topPanel.setGravity(Gravity.CENTER_VERTICAL);
        topPanel.setBackgroundDrawable(shade(true));
        topPanel.setPadding(dp(16), dp(10), dp(16), dp(10));

        Button backBtn = tool("‹ Back", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        topPanel.addView(backBtn, new LinearLayout.LayoutParams(dp(85), dp(42)));

        title = text(videoTitle, 16);
        title.setTypeface(null, 1);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(42), 1);
        tp.setMargins(dp(12), 0, dp(12), 0);
        topPanel.addView(title, tp);

        aspectButton = tool("Fit (16:9)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                aspect = (aspect + 1) % 3;
                updateAspect();
            }
        });
        topPanel.addView(aspectButton, new LinearLayout.LayoutParams(dp(105), dp(42)));

        root.addView(topPanel, new FrameLayout.LayoutParams(-1, dp(72), Gravity.TOP));

        // Transport Panel (Center controls)
        transportPanel = new LinearLayout(this);
        transportPanel.setOrientation(LinearLayout.HORIZONTAL);
        transportPanel.setGravity(Gravity.CENTER);

        Button rew = roundControl("«", 52, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player != null) player.seekTo(Math.max(0, player.getCurrentPosition() - 10000));
            }
        });
        spaced(transportPanel, rew, 60, 60, 16);

        pauseButton = roundControl("▶", 70, true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player == null) return;
                userPaused = player.getPlayWhenReady();
                player.setPlayWhenReady(!player.getPlayWhenReady());
                pauseButton.setText(player.getPlayWhenReady() ? "Ⅱ" : "▶");
            }
        });
        spaced(transportPanel, pauseButton, 76, 76, 16);

        Button fwd = roundControl("»", 52, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player != null) player.seekTo(Math.min(player.getDuration(), player.getCurrentPosition() + 10000));
            }
        });
        spaced(transportPanel, fwd, 60, 60, 16);

        FrameLayout.LayoutParams trp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(transportPanel, trp);

        // Bottom Panel (Seekbar + Audio / Subtitle options)
        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setBackgroundDrawable(shade(false));
        bottomPanel.setPadding(dp(16), dp(8), dp(16), dp(12));

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);

        positionLabel = text("00:00:00", 13);
        if (theme != null) positionLabel.setTextColor(theme.onSurfaceVariant);
        seekRow.addView(positionLabel, new LinearLayout.LayoutParams(dp(75), -2));

        seek = new SeekBar(this);
        seek.setMax(1000);
        styleSeekBar(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && lastDuration > 0) {
                    positionLabel.setText(time(progress * lastDuration / 1000L));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seeking = false;
                if (player != null && lastDuration > 0) {
                    player.seekTo(seekBar.getProgress() * lastDuration / 1000L);
                }
                scheduleHide();
            }
        });
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));

        durationLabel = text("00:00:00", 13);
        if (theme != null) durationLabel.setTextColor(theme.onSurfaceVariant);
        durationLabel.setGravity(Gravity.RIGHT);
        seekRow.addView(durationLabel, new LinearLayout.LayoutParams(dp(90), -2));
        bottomPanel.addView(seekRow);

        LinearLayout toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        toolsRow.setGravity(Gravity.CENTER_VERTICAL);

        audioButton = tool("♫  Audio", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseTracks(C.TRACK_TYPE_AUDIO);
            }
        });
        spaced(toolsRow, audioButton, 105, 42, 6);

        subtitleButton = tool("CC  Subtitles", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseTracks(C.TRACK_TYPE_TEXT);
            }
        });
        spaced(toolsRow, subtitleButton, 115, 42, 6);

        bottomPanel.addView(toolsRow);
        root.addView(bottomPanel, new FrameLayout.LayoutParams(-1, dp(120), Gravity.BOTTOM));

        status = text("Connecting to stream…", 14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(16), dp(24), dp(16));
        if (theme != null) {
            ThemeManager.styleSurfaceCard(this, status, (0xF2000000 | (theme.surfaceContainer & 0x00FFFFFF)), 12, 1, theme.outlineVariant);
            status.setTextColor(theme.onSurface);
        } else {
            status.setBackgroundDrawable(pill(0xf2151922, ACCENT, 10));
            status.setTextColor(Color.WHITE);
        }
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        slp.setMargins(dp(30), 0, dp(30), 0);
        root.addView(status, slp);

        // Safety Lockout Overlay (fail-closed park gate for Gen5W)
        safetyLockoutOverlay = new FrameLayout(this);
        safetyLockoutOverlay.setBackgroundColor(0xF0000000);
        safetyLockoutOverlay.setVisibility(View.GONE);

        LinearLayout lockCard = new LinearLayout(this);
        lockCard.setOrientation(LinearLayout.VERTICAL);
        lockCard.setGravity(Gravity.CENTER);
        lockCard.setPadding(dp(32), dp(24), dp(32), dp(24));
        if (theme != null) {
            ThemeManager.styleSurfaceCard(this, lockCard, theme.surfaceContainerHighest, 16, 2, 0xFFFF5252);
        } else {
            lockCard.setBackgroundDrawable(pill(0xFF1E222B, 0xFFFF5252, 16));
        }

        TextView lockTitle = text("⚠️ VIDEO LOCKED FOR VEHICLE SAFETY", 18);
        lockTitle.setTypeface(null, 1);
        lockTitle.setTextColor(0xFFFF5252);
        lockTitle.setGravity(Gravity.CENTER);
        lockCard.addView(lockTitle);

        safetyLockoutText = text("Video playback is restricted while vehicle is in motion or shifted out of PARK.", 14);
        safetyLockoutText.setTextColor(Color.WHITE);
        safetyLockoutText.setGravity(Gravity.CENTER);
        safetyLockoutText.setPadding(0, dp(12), 0, dp(8));
        lockCard.addView(safetyLockoutText);

        TextView lockHint = text("Shift transmission into PARK and come to a complete stop to resume video.", 13);
        lockHint.setTextColor(0xFFAAAAAA);
        lockHint.setGravity(Gravity.CENTER);
        lockCard.addView(lockHint);

        FrameLayout.LayoutParams lclp = new FrameLayout.LayoutParams(dp(540), -2, Gravity.CENTER);
        safetyLockoutOverlay.addView(lockCard, lclp);
        root.addView(safetyLockoutOverlay, new FrameLayout.LayoutParams(-1, -1));

        // Connect Gen5W Vehicle Telemetry Gate
        vehicleGate = new VehicleVideoGate(this);
        vehicleGate.setListener(new VehicleVideoGate.OnParkStateChangeListener() {
            @Override
            public void onParkStateChanged(boolean isParked, String reason) {
                handleParkState(isParked, reason);
            }
        });
        if (!vehicleGate.isVehicleParked()) {
            handleParkState(false, vehicleGate.getLastReason());
        }

        setContentView(root);
        updateAspect();
        openedAt = System.currentTimeMillis();

        initPlayer();
    }

    private void initPlayer() {
        if (streamUrl == null || streamUrl.isEmpty()) {
            status("Error: No stream URL provided.");
            return;
        }

        try {
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(this);
            renderers.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
            renderers.setEnableDecoderFallback(true);

            selector = new DefaultTrackSelector(this);
            selector.setParameters(selector.buildUponParameters()
                    .setPreferredAudioLanguage("en")
                    .setPreferredAudioMimeType(null));

            player = new SimpleExoPlayer.Builder(this, renderers).setTrackSelector(selector).build();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_MOVIE).build(), true);
            player.setVolume(1.0f);
            video.setPlayer(player);

            player.addVideoListener(new VideoListener() {
                @Override
                public void onRenderedFirstFrame() {
                    firstFrame = true;
                    clearStatus();
                    scheduleHide();
                }
            });

            player.addListener(new Player.EventListener() {
                @Override
                public void onPlayerStateChanged(boolean ready, int state) {
                    if (released) return;
                    if (state == Player.STATE_READY) {
                        if (!motionLocked && !autoplayStarted && !userPaused) {
                            autoplayStarted = true;
                            player.setPlayWhenReady(true);
                        }
                        if (firstFrame) clearStatus();
                        scheduleHide();
                    } else if (state == Player.STATE_BUFFERING && !firstFrame) {
                        status("Buffering video…");
                    } else if (state == Player.STATE_ENDED) {
                        showControls(true);
                        status("Playback finished.");
                    }
                }

                @Override
                public void onPlayerError(ExoPlaybackException error) {
                    if (released) return;
                    Throwable why = error.getCause();
                    String msg = error.getMessage();
                    Format f = error.rendererFormat;

                    String mime = (f != null && f.sampleMimeType != null) ? f.sampleMimeType.toLowerCase(Locale.US) : "";
                    String codecs = (f != null && f.codecs != null) ? f.codecs.toLowerCase(Locale.US) : "";
                    int width = (f != null) ? f.width : 0;
                    int height = (f != null) ? f.height : 0;

                    StringBuilder sb = new StringBuilder();

                    boolean isHevc = mime.contains("hevc") || codecs.contains("hevc") || codecs.contains("h265") || codecs.contains("x265") || codecs.contains("h.265") || codecs.startsWith("hvc1") || codecs.startsWith("hev1");
                    boolean is10Bit = codecs.contains("10bit") || codecs.startsWith("avc1.6e") || codecs.startsWith("avc1.7a") || codecs.startsWith("avc1.f4");
                    boolean isDolby = mime.contains("eac3") || mime.contains("ac3") || mime.contains("dts");

                    if (isHevc) {
                        sb.append("⚠️ Codec Unsupported on KitKat\n\n");
                        sb.append("Format: ").append(describeCodec(codecs, mime)).append("\n");
                        sb.append("Android 4.4 KitKat only has hardware decoders for H.264 (AVC).\n");
                        sb.append("HEVC/x265 video cannot be decoded on Android 4.4.\n\n");
                        sb.append("👉 Tap ‹ Back and select an 'H.264' / 'x264' stream.");
                    } else if (is10Bit) {
                        sb.append("⚠️ 10-Bit Color Unsupported\n\n");
                        sb.append("Format: ").append(describeCodec(codecs, mime)).append("\n");
                        sb.append("This stream uses 10-bit color (High 10 Profile).\n");
                        sb.append("Android 4.4 KitKat and automotive hardware only support standard 8-bit H.264.\n\n");
                        sb.append("👉 Tap ‹ Back and select an 8-bit H.264 stream.");
                    } else if (isDolby) {
                        sb.append("⚠️ Dolby Audio Unsupported\n\n");
                        sb.append("Audio Codec: ").append(mime).append("\n");
                        sb.append("Multi-channel Dolby audio (E-AC3/AC3) has no decoder on Android 4.4 KitKat.\n\n");
                        sb.append("👉 Tap ‹ Back and select a stream with stereo AAC/MP3 audio.");
                    } else if (error.type == ExoPlaybackException.TYPE_SOURCE) {
                        sb.append("⚠️ Stream Connection Error\n\n");
                        String detail = (why != null && why.getMessage() != null) ? why.getMessage() : (msg != null ? msg : "Unable to connect to stream source.");
                        sb.append(detail).append("\n\n");
                        sb.append("👉 Tap ‹ Back and try another stream.");
                    } else {
                        sb.append("⚠️ Codec / Playback Error\n\n");
                        if (!codecs.isEmpty() || !mime.isEmpty()) {
                            sb.append("Format: ").append(describeCodec(codecs, mime));
                            if (width > 0 && height > 0) sb.append(" (").append(width).append("x").append(height).append(")");
                            sb.append("\n");
                        }

                        String causeMsg = (why != null && why.getMessage() != null) ? why.getMessage() : (msg != null ? msg : "");
                        if (!causeMsg.isEmpty()) {
                            sb.append("Details: ").append(causeMsg).append("\n");
                        }

                        if (causeMsg.contains("OMX.google.h264.decoder") || (msg != null && msg.contains("OMX.google.h264.decoder"))) {
                            sb.append("\n💡 Note: Android 4.4 emulator software decoder (OMX.google.h264.decoder) only supports Baseline H.264.\n");
                            sb.append("🚗 On the physical Gen5W car, OMX.TCC.avcdec plays High Profile in hardware!\n");
                            sb.append("For emulator testing, select a 480p / Baseline stream.\n");
                        }

                        sb.append("\n👉 Tap ‹ Back to return to streams.");
                    }

                    status(sb.toString());
                    showControls(true);
                }
            });

            // Modern TLS 1.2 and Mozilla CA Trust Provider for KitKat
            originalTls = HttpsURLConnection.getDefaultSSLSocketFactory();
            HttpsURLConnection.setDefaultSSLSocketFactory(VideoTls.build(this));

            DefaultDataSourceFactory dataSourceFactory = new DefaultDataSourceFactory(
                    this, "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.0");

            Uri uri = Uri.parse(streamUrl);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.US);
            MediaSource mediaSource;
            if (path.endsWith(".m3u8")) {
                mediaSource = new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
            }

            prepareWhenSurfaceReady(mediaSource);

        } catch (Throwable t) {
            status("Playback setup failed: " + t.getMessage());
            showControls(true);
        }
    }

    private String describeCodec(String codecs, String mime) {
        if (codecs == null || codecs.trim().isEmpty()) {
            return (mime != null && !mime.isEmpty()) ? mime : "Unknown Format";
        }
        String lower = codecs.toLowerCase(Locale.US);
        if (lower.startsWith("avc1.") || lower.startsWith("avc3.")) {
            String profileHex = lower.length() >= 7 ? lower.substring(5, 7) : "";
            String levelHex = lower.length() >= 11 ? lower.substring(9, 11) : "";
            String profileName = "H.264 High Profile (8-bit)";
            if ("42".equals(profileHex)) profileName = "H.264 Baseline Profile (8-bit)";
            else if ("4d".equals(profileHex)) profileName = "H.264 Main Profile (8-bit)";
            else if ("64".equals(profileHex)) profileName = "H.264 High Profile (8-bit)";
            else if ("6e".equals(profileHex)) profileName = "H.264 High 10 (10-bit color ⚠️)";
            else if ("7a".equals(profileHex)) profileName = "H.264 High 4:2:2 (10-bit ⚠️)";
            else if ("f4".equals(profileHex)) profileName = "H.264 High 4:4:4 (⚠️)";

            String levelName = "";
            try {
                int lvl = Integer.parseInt(levelHex, 16);
                levelName = " Level " + (lvl / 10) + "." + (lvl % 10);
            } catch (Throwable ignored) {}

            return profileName + levelName + " [" + codecs + "]";
        }
        if (lower.startsWith("hvc1.") || lower.startsWith("hev1.")) {
            return "HEVC / H.265 [" + codecs + "] (Unsupported on KitKat)";
        }
        if (lower.startsWith("vp09") || lower.startsWith("vp9")) {
            return "VP9 [" + codecs + "]";
        }
        return codecs;
    }

    private void prepareWhenSurfaceReady(final MediaSource mediaSource) {
        if (player == null || playerPrepared) return;
        pendingMediaSource = mediaSource;

        View surfaceView = video.getVideoSurfaceView();
        if (surfaceView instanceof SurfaceView) {
            SurfaceView sv = (SurfaceView) surfaceView;
            SurfaceHolder holder = sv.getHolder();
            Surface surface = (holder != null) ? holder.getSurface() : null;
            if (surface != null && surface.isValid()) {
                startPlayback();
            } else if (holder != null) {
                holder.addCallback(new SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(SurfaceHolder holder) {
                        startPlayback();
                    }

                    @Override
                    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

                    @Override
                    public void surfaceDestroyed(SurfaceHolder holder) {}
                });
                // Fallback timeout in case surface is already being created
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        startPlayback();
                    }
                }, 350);
            } else {
                startPlayback();
            }
        } else {
            startPlayback();
        }
    }

    private void startPlayback() {
        if (playerPrepared || player == null || released) return;
        playerPrepared = true;
        if (pendingMediaSource == null) return;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (player != null && !released && pendingMediaSource != null) {
                    player.setPlayWhenReady(true);
                    player.prepare(pendingMediaSource);
                    handler.postDelayed(progressRunnable, 500);
                    showControls(true);
                }
            }
        });
    }

    private void chooseTracks(final int kind) {
        showControls(true);
        if (selector == null) return;
        final MappedTrackInfo map = selector.getCurrentMappedTrackInfo();
        if (map == null) return;

        final ArrayList<String> names = new ArrayList<>();
        final ArrayList<Integer> renderers = new ArrayList<>();
        final ArrayList<Integer> groups = new ArrayList<>();
        final ArrayList<Integer> tracks = new ArrayList<>();

        names.add(kind == C.TRACK_TYPE_AUDIO ? "Default / Auto" : "Off");
        renderers.add(-1);
        groups.add(-1);
        tracks.add(-1);

        for (int r = 0; r < map.getRendererCount(); r++) {
            if (map.getRendererType(r) != kind) continue;
            TrackGroupArray tga = map.getTrackGroups(r);
            for (int g = 0; g < tga.length; g++) {
                TrackGroup group = tga.get(g);
                for (int t = 0; t < group.length; t++) {
                    Format format = group.getFormat(t);
                    String label = (format.label != null && !format.label.isEmpty()) ? format.label :
                            (format.language != null ? format.language.toUpperCase(Locale.US) : "Track " + (t + 1));
                    names.add(label);
                    renderers.add(r);
                    groups.add(g);
                    tracks.add(t);
                }
            }
        }

        final Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(18), dp(22), dp(18));
        if (theme != null) {
            ThemeManager.styleSurfaceCard(this, panel, (0xF2000000 | (theme.surfaceContainer & 0x00FFFFFF)), 16, 1, theme.outlineVariant);
        } else {
            panel.setBackgroundDrawable(pill(0xf0151922, BORDER, 14));
        }

        TextView dTitle = text(kind == C.TRACK_TYPE_AUDIO ? "Select Audio Track" : "Select Subtitles", 17);
        dTitle.setTypeface(null, 1);
        dTitle.setTextColor(theme != null ? theme.primary : ACCENT);
        dTitle.setPadding(0, 0, 0, dp(12));
        panel.addView(dTitle);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < names.size(); i++) {
            final int which = i;
            Button itemBtn = new Button(this);
            itemBtn.setText(names.get(i));
            itemBtn.setTextSize(14);
            itemBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            itemBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
            if (theme != null) {
                ThemeManager.stylePillButton(this, itemBtn, (0xC8000000 | (theme.surfaceContainerHigh & 0x00FFFFFF)), theme.onSurface, 1, theme.outlineVariant, false);
            } else {
                itemBtn.setTextColor(Color.WHITE);
                itemBtn.setBackgroundDrawable(pill(0xcc181d2a, BORDER, 8));
            }
            itemBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    int r = renderers.get(which);
                    DefaultTrackSelector.ParametersBuilder settings = selector.buildUponParameters();
                    if (r == -1) {
                        for (int k = 0; k < map.getRendererCount(); k++) {
                            if (map.getRendererType(k) == kind) {
                                settings.setRendererDisabled(k, kind == C.TRACK_TYPE_TEXT);
                                settings.clearSelectionOverrides(k);
                            }
                        }
                    } else {
                        int g = groups.get(which);
                        int t = tracks.get(which);
                        settings.setRendererDisabled(r, false);
                        settings.setSelectionOverride(r, map.getTrackGroups(r),
                                new DefaultTrackSelector.SelectionOverride(g, t));
                    }
                    selector.setParameters(settings);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
            lp.setMargins(0, dp(3), 0, dp(3));
            list.addView(itemBtn, lp);
        }
        sv.addView(list);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, cancel, theme.surfaceContainerHighest, theme.onSurface, 0, 0, true);
        } else {
            cancel.setTextColor(Color.WHITE);
            cancel.setBackgroundDrawable(pill(0xcc181d2a, 0, 8));
        }
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(42));
        clp.setMargins(0, dp(12), 0, 0);
        panel.addView(cancel, clp);

        dialog.setContentView(panel);
        dialog.show();

        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(480)),
                    Math.min(getResources().getDisplayMetrics().heightPixels - dp(48), dp(380)));
            w.setGravity(Gravity.CENTER);
        }
    }

    private void handleParkState(final boolean isParked, final String reason) {
        if (safetyLockoutOverlay == null) return;
        if (!isParked) {
            if (player != null && player.getPlayWhenReady()) {
                player.setPlayWhenReady(false);
            }
            if (safetyLockoutText != null) {
                safetyLockoutText.setText("Video playback is restricted while vehicle is in motion or shifted out of PARK.\n(" + reason + ")");
            }
            safetyLockoutOverlay.setVisibility(View.VISIBLE);
            showControls(false);
        } else {
            safetyLockoutOverlay.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (vehicleGate != null) {
            vehicleGate.start();
        }
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && player.getPlayWhenReady()) {
            player.setPlayWhenReady(false);
        }
        if (vehicleGate != null) {
            vehicleGate.stop();
        }
    }

    @Override
    protected void onDestroy() {
        released = true;
        playerPrepared = false;
        pendingMediaSource = null;
        handler.removeCallbacksAndMessages(null);
        if (vehicleGate != null) {
            vehicleGate.stop();
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (originalTls != null) {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalTls);
            originalTls = null;
        }
        if (audioManager != null && audioFocusGranted) {
            // Hand off to process-wide focus holder to retain Nuvio as the active Gen5W AV source
            AudioFocusHolder.hold(audioManager);
            audioManager.abandonAudioFocus(focusListener);
        }
        super.onDestroy();
    }
}
