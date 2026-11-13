package org.nuvio.gen5w.player;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;
import org.nuvio.gen5w.gate.VehicleVideoGate;
import org.nuvio.gen5w.overlay.NativeVideoOverlay;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * JavaScript Bridge exposed to the WebView as `window.NativePlayer`.
 * Facilitates bidirectional communication between Nuvio Web frontend and the native ExoPlayer.
 */
public class NativePlaybackBridge {

    private static final String TAG = "NativePlaybackBridge";

    private final WebView webView;
    private final NativeVideoOverlay videoOverlay;
    private final VehicleVideoGate vehicleGate;
    private final Handler mainHandler;
    private NativePlaybackService playbackService;

    public NativePlaybackBridge(WebView webView, NativeVideoOverlay videoOverlay, VehicleVideoGate vehicleGate) {
        this.webView = webView;
        this.videoOverlay = videoOverlay;
        this.vehicleGate = vehicleGate;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setPlaybackService(NativePlaybackService service) {
        this.playbackService = service;
        if (this.playbackService != null && this.videoOverlay != null) {
            this.playbackService.attachSurfaceView(this.videoOverlay.getSurfaceView());
        }
    }

    // ==========================================
    // JavaScript Exposed Methods (@JavascriptInterface)
    // ==========================================

    @JavascriptInterface
    public void load(final String payloadJson) {
        Log.d(TAG, "Bridge load payload: " + payloadJson);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject json = new JSONObject(payloadJson);
                    String url = json.getString("url");
                    long startPos = json.optLong("position", 0);

                    Map<String, String> headers = new HashMap<String, String>();
                    if (json.has("headers")) {
                        JSONObject headersObj = json.getJSONObject("headers");
                        Iterator<String> keys = headersObj.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            headers.put(key, headersObj.getString(key));
                        }
                    }

                    // Show video overlay
                    videoOverlay.setVisibility(View.VISIBLE);

                    if (playbackService != null) {
                        playbackService.loadMedia(url, headers, startPos);
                    }

                    JSONObject eventData = new JSONObject();
                    eventData.put("url", url);
                    eventData.put("title", json.optString("title", ""));
                    dispatchWebEvent("loaded", eventData);

                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse load() payload: " + e.getMessage(), e);
                    dispatchWebError("Invalid load payload: " + e.getMessage());
                }
            }
        });
    }

    @JavascriptInterface
    public void play() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (playbackService != null) {
                    playbackService.play();
                }
            }
        });
    }

    @JavascriptInterface
    public void pause() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (playbackService != null) {
                    playbackService.pause();
                }
            }
        });
    }

    @JavascriptInterface
    public void toggle() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (playbackService != null) {
                    playbackService.togglePlay();
                }
            }
        });
    }

    @JavascriptInterface
    public void seek(final long positionMs) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (playbackService != null) {
                    playbackService.seekTo(positionMs);
                }
            }
        });
    }

    @JavascriptInterface
    public void stop() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (playbackService != null) {
                    playbackService.stop();
                }
                videoOverlay.setVisibility(View.GONE);
                JSONObject eventData = new JSONObject();
                dispatchWebEvent("stopped", eventData);
            }
        });
    }

    @JavascriptInterface
    public void setVolume(final float volume) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (playbackService != null) {
                    playbackService.setVolume(volume);
                }
            }
        });
    }

    @JavascriptInterface
    public void setOverlayVisible(final boolean visible) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                videoOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        });
    }

    @JavascriptInterface
    public void setAspectRatio(final String mode) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if ("fill".equalsIgnoreCase(mode)) {
                    videoOverlay.setAspectRatioMode(NativeVideoOverlay.AspectRatioMode.FILL);
                } else if ("stretch".equalsIgnoreCase(mode)) {
                    videoOverlay.setAspectRatioMode(NativeVideoOverlay.AspectRatioMode.STRETCH);
                } else {
                    videoOverlay.setAspectRatioMode(NativeVideoOverlay.AspectRatioMode.FIT);
                }
            }
        });
    }

    @JavascriptInterface
    public boolean isParked() {
        return vehicleGate != null && vehicleGate.isVehicleParked();
    }

    @JavascriptInterface
    public void setMockParked(final boolean parked) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (vehicleGate != null) {
                    vehicleGate.setMockParked(parked);
                }
            }
        });
    }

    @JavascriptInterface
    public void clearMockParked() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (vehicleGate != null) {
                    vehicleGate.clearMock();
                }
            }
        });
    }

    @JavascriptInterface
    public String getStateJson() {
        JSONObject state = new JSONObject();
        try {
            boolean isPlaying = playbackService != null && playbackService.isPlaying();
            long pos = playbackService != null ? playbackService.getCurrentPosition() : 0;
            long dur = playbackService != null ? playbackService.getDuration() : 0;
            boolean parked = vehicleGate == null || vehicleGate.isVehicleParked();

            state.put("isPlaying", isPlaying);
            state.put("position", pos);
            state.put("duration", dur);
            state.put("isParked", parked);
            state.put("isOverlayVisible", videoOverlay.getVisibility() == View.VISIBLE);
        } catch (JSONException ignored) {}
        return state.toString();
    }

    @JavascriptInterface
    public void log(String message) {
        Log.d(TAG, "[WebConsole] " + message);
    }

    // ==========================================
    // Dispatch Events to WebView
    // ==========================================

    public void dispatchWebEvent(final String eventName, final JSONObject data) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                String payload = data != null ? data.toString() : "{}";
                final String js = "if (window.onNativePlayerEvent) { window.onNativePlayerEvent('" + eventName + "', " + payload + "); }";
                evaluateJavascript(js);
            }
        });
    }

    public void dispatchWebError(final String errorMessage) {
        JSONObject data = new JSONObject();
        try {
            data.put("error", errorMessage);
        } catch (JSONException ignored) {}
        dispatchWebEvent("error", data);
    }

    private void evaluateJavascript(String script) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(script, null);
        } else {
            webView.loadUrl("javascript:" + script);
        }
    }
}

