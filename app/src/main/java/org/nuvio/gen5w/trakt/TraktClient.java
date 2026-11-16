package org.nuvio.gen5w.trakt;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.nuvio.gen5w.player.VideoTls;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Trakt.tv Client for Android 4.4 KitKat.
 *
 * Implements:
 * 1. OAuth Device Code Authorization Flow (https://api.trakt.tv/oauth/device/code)
 *    Enables driver to authorize on phone at https://trakt.tv/activate with an 8-digit PIN.
 * 2. Real-time Video Scrobbling (/scrobble/start, /scrobble/pause, /scrobble/stop)
 *    Automatically logs watch progress, history, and ratings to Trakt.tv.
 */
public final class TraktClient {

    private static final String TAG = "TraktClient";
    private static final String PREFS_NAME = "nuvio_stremio_prefs";

    // Public community Trakt Client ID for open-source media player integrations
    public static final String TRAKT_CLIENT_ID = "61d9894eef1792fc2217c91e0a24bf42b158097b83c518fc71836a94fbe08e33";
    private static final String TRAKT_API_URL = "https://api.trakt.tv";

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static class DeviceCodeResponse {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUrl;
        public final int expiresIn;
        public final int interval;

        public DeviceCodeResponse(String deviceCode, String userCode, String verificationUrl, int expiresIn, int interval) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUrl = verificationUrl;
            this.expiresIn = expiresIn;
            this.interval = interval;
        }
    }

    private static volatile TraktClient instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private boolean pollingDeviceCode = false;

    private TraktClient(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.executor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static TraktClient getInstance(Context context) {
        if (instance == null) {
            synchronized (TraktClient.class) {
                if (instance == null) {
                    instance = new TraktClient(context);
                }
            }
        }
        return instance;
    }

    public boolean isConnected() {
        return !prefs.getString("trakt_access_token", "").isEmpty();
    }

    public String getAccessToken() {
        return prefs.getString("trakt_access_token", "");
    }

    public String getClientId() {
        String custom = prefs.getString("trakt_custom_client_id", "").trim();
        if (!custom.isEmpty()) return custom;
        return TRAKT_CLIENT_ID;
    }

    public String getClientSecret() {
        return prefs.getString("trakt_custom_client_secret", "").trim();
    }

    public boolean hasCustomCredentials() {
        return !prefs.getString("trakt_custom_client_id", "").trim().isEmpty();
    }

    public void saveCustomCredentials(String clientId, String clientSecret) {
        prefs.edit()
                .putString("trakt_custom_client_id", clientId != null ? clientId.trim() : "")
                .putString("trakt_custom_client_secret", clientSecret != null ? clientSecret.trim() : "")
                .apply();
    }

    public void saveDirectAccessToken(String token) {
        prefs.edit()
                .putString("trakt_access_token", token != null ? token.trim() : "")
                .apply();
    }

    public void disconnect() {
        pollingDeviceCode = false;
        prefs.edit()
                .remove("trakt_access_token")
                .remove("trakt_refresh_token")
                .remove("trakt_user_name")
                .apply();
    }

    // =========================================================================
    // 1. Device Code Authorization Flow
    // =========================================================================

    public void startDeviceCodeFlow(final Callback<DeviceCodeResponse> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String clientId = getClientId();
                    if (clientId == null || clientId.trim().isEmpty()) {
                        postError(callback, "Trakt Client ID is not configured");
                        return;
                    }
                    JSONObject body = new JSONObject();
                    body.put("client_id", clientId);

                    ApiResponse res = makeRequest("POST", "/oauth/device/code", body.toString(), false);
                    if (res.isSuccess()) {
                        JSONObject json = new JSONObject(res.body);
                        final DeviceCodeResponse dcr = new DeviceCodeResponse(
                                json.getString("device_code"),
                                json.getString("user_code"),
                                json.optString("verification_url", "https://trakt.tv/activate"),
                                json.optInt("expires_in", 600),
                                json.optInt("interval", 5)
                        );
                        postSuccess(callback, dcr);
                    } else if (res.statusCode == 401) {
                        postError(callback, "HTTP 401: Invalid Trakt Client ID. Please configure your Trakt API app credentials.");
                    } else {
                        postError(callback, "Failed to get Trakt device code (HTTP " + res.statusCode + ")");
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    public void pollForAuthorization(final String deviceCode, final int intervalSec, final Callback<Boolean> callback) {
        pollingDeviceCode = true;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                int intervalMs = Math.max(intervalSec, 3) * 1000;
                long start = System.currentTimeMillis();
                long timeout = 600 * 1000L; // 10 minutes

                while (pollingDeviceCode && (System.currentTimeMillis() - start < timeout)) {
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        break;
                    }

                    if (!pollingDeviceCode) break;

                    try {
                        JSONObject body = new JSONObject();
                        body.put("code", deviceCode);
                        body.put("client_id", getClientId());
                        String secret = getClientSecret();
                        if (!secret.isEmpty()) {
                            body.put("client_secret", secret);
                        }

                        ApiResponse res = makeRequest("POST", "/oauth/device/token", body.toString(), false);
                        if (res.statusCode == 200) {
                            JSONObject json = new JSONObject(res.body);
                            String token = json.getString("access_token");
                            String refresh = json.optString("refresh_token", "");
                            prefs.edit()
                                    .putString("trakt_access_token", token)
                                    .putString("trakt_refresh_token", refresh)
                                    .apply();
                            pollingDeviceCode = false;
                            postSuccess(callback, true);
                            return;
                        } else if (res.statusCode == 400) {
                            // Pending authorization, continue polling
                            continue;
                        } else if (res.statusCode == 404 || res.statusCode == 409 || res.statusCode == 410) {
                            // Expired or denied
                            pollingDeviceCode = false;
                            postError(callback, "Authorization expired or denied");
                            return;
                        }
                    } catch (Exception ignored) {}
                }

                pollingDeviceCode = false;
                postError(callback, "Authorization timed out");
            }
        });
    }

    public void cancelPolling() {
        pollingDeviceCode = false;
    }

    // =========================================================================
    // 2. Trakt Scrobbler Engine
    // =========================================================================

    public void scrobbleStart(String metaId, String mediaType, String videoId, String title, float progressPercent) {
        sendScrobble("start", metaId, mediaType, videoId, title, progressPercent);
    }

    public void scrobblePause(String metaId, String mediaType, String videoId, String title, float progressPercent) {
        sendScrobble("pause", metaId, mediaType, videoId, title, progressPercent);
    }

    public void scrobbleStop(String metaId, String mediaType, String videoId, String title, float progressPercent) {
        sendScrobble("stop", metaId, mediaType, videoId, title, progressPercent);
    }

    private void sendScrobble(final String action, final String metaId, final String mediaType,
                              final String videoId, final String title, final float progressPercent) {
        final String token = getAccessToken();
        if (token.isEmpty()) return;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("progress", Math.min(100f, Math.max(0f, progressPercent)));

                    if ("series".equals(mediaType)) {
                        JSONObject show = new JSONObject();
                        JSONObject showIds = new JSONObject();
                        if (metaId.startsWith("tt")) {
                            showIds.put("imdb", metaId);
                        } else if (metaId.startsWith("tmdb:")) {
                            showIds.put("tmdb", metaId.substring(5));
                        }
                        show.put("ids", showIds);
                        show.put("title", title);

                        JSONObject episode = new JSONObject();
                        int s = 1, e = 1;
                        if (videoId.contains(":")) {
                            String[] p = videoId.split(":");
                            if (p.length >= 3) {
                                try {
                                    s = Integer.parseInt(p[1]);
                                    e = Integer.parseInt(p[2]);
                                } catch (Exception ignored) {}
                            }
                        }
                        episode.put("season", s);
                        episode.put("number", e);

                        body.put("show", show);
                        body.put("episode", episode);
                    } else {
                        JSONObject movie = new JSONObject();
                        movie.put("title", title);
                        JSONObject ids = new JSONObject();
                        if (metaId.startsWith("tt")) {
                            ids.put("imdb", metaId);
                        } else if (metaId.startsWith("tmdb:")) {
                            ids.put("tmdb", metaId.substring(5));
                        }
                        movie.put("ids", ids);
                        body.put("movie", movie);
                    }

                    ApiResponse res = makeRequest("POST", "/scrobble/" + action, body.toString(), true);
                    Log.d(TAG, "Trakt scrobble " + action + " status: " + res.statusCode);
                } catch (Exception e) {
                    Log.w(TAG, "Trakt scrobble " + action + " failed: " + e.getMessage());
                }
            }
        });
    }

    // =========================================================================
    // 3. Trakt Calendar / Upcoming Endpoints
    // =========================================================================

    public void fetchCalendarShows(final Callback<JSONArray> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean hasAuth = isConnected();
                    String path = hasAuth ? "/calendars/my/shows" : "/calendars/all/shows";
                    ApiResponse res = makeRequest("GET", path, null, hasAuth);
                    if (res.isSuccess()) {
                        JSONArray arr = new JSONArray(res.body);
                        postSuccess(callback, arr);
                    } else if (hasAuth) {
                        ApiResponse fallback = makeRequest("GET", "/calendars/all/shows", null, false);
                        if (fallback.isSuccess()) {
                            postSuccess(callback, new JSONArray(fallback.body));
                        } else {
                            postError(callback, "Failed to load upcoming shows: HTTP " + res.statusCode);
                        }
                    } else {
                        postError(callback, "Failed to load upcoming shows: HTTP " + res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    public void fetchCalendarMovies(final Callback<JSONArray> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean hasAuth = isConnected();
                    String path = hasAuth ? "/calendars/my/movies" : "/calendars/all/movies";
                    ApiResponse res = makeRequest("GET", path, null, hasAuth);
                    if (res.isSuccess()) {
                        JSONArray arr = new JSONArray(res.body);
                        postSuccess(callback, arr);
                    } else if (hasAuth) {
                        ApiResponse fallback = makeRequest("GET", "/calendars/all/movies", null, false);
                        if (fallback.isSuccess()) {
                            postSuccess(callback, new JSONArray(fallback.body));
                        } else {
                            postError(callback, "Failed to load upcoming movies: HTTP " + res.statusCode);
                        }
                    } else {
                        postError(callback, "Failed to load upcoming movies: HTTP " + res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        });
    }

    // =========================================================================
    // Low-Level HTTP Engine
    // =========================================================================

    private ApiResponse makeRequest(String method, String endpoint, String jsonBody, boolean auth) throws Exception {
        URL url = new URL(TRAKT_API_URL + endpoint);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(context));
        }

        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", getClientId());
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.6");

        if (auth) {
            String token = getAccessToken();
            if (!token.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + token);
            }
        }

        if (jsonBody != null && !jsonBody.isEmpty()) {
            c.setDoOutput(true);
            byte[] bytes = jsonBody.getBytes("UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
        }

        int code = c.getResponseCode();
        InputStream is = (code < 400) ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            is.close();
        }
        c.disconnect();

        String body = new String(bos.toByteArray(), "UTF-8");
        return new ApiResponse(code, body);
    }

    private <T> void postSuccess(final Callback<T> callback, final T result) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(result);
            }
        });
    }

    private <T> void postError(final Callback<T> callback, final String message) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message);
            }
        });
    }

    private static class ApiResponse {
        final int statusCode;
        final String body;

        ApiResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body != null ? body : "";
        }

        boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
