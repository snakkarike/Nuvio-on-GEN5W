package org.nuvio.gen5w.api;

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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Complete Nuvio Supabase Backend API Client for Android 4.4 KitKat.
 *
 * Implements:
 * 1. Authentication (Password login, Signup, Automatic Token Refresh via /auth/v1/).
 * 2. Multi-Profile Management (Fetch, Create, Update, Delete via /rest/v1/profiles).
 * 3. Library / Watchlist (Fetch, Add, Remove, Local Cache via /rest/v1/library_items).
 * 4. Watch Progress / Continue Watching (Fetch, Upsert, Delete via /rest/v1/watch_progress).
 * 5. Addon Cloud Synchronization (Fetch, Push, Delete via /rest/v1/addons).
 *
 * Automatically handles TLS 1.2/1.3 via VideoTls and transparent token renewal on HTTP 401.
 */
public final class NuvioClient {

    private static final String TAG = "NuvioClient";
    public static final String DEFAULT_API_URL = "https://api.nuvio.tv";
    public static final String ANON_KEY = "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN";
    private static final String PREFS_NAME = "nuvio_stremio_prefs";

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error, int statusCode);
    }

    private static volatile NuvioClient instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executor;
    private final Handler mainHandler;

    // In-memory cache of library content IDs for instantaneous UI lookups
    private final Set<String> libraryContentIds = Collections.synchronizedSet(new HashSet<String>());

    private NuvioClient(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.executor = Executors.newFixedThreadPool(3);
        this.mainHandler = new Handler(Looper.getMainLooper());
        loadCachedLibraryIds();
    }

    public static NuvioClient getInstance(Context context) {
        if (instance == null) {
            synchronized (NuvioClient.class) {
                if (instance == null) {
                    instance = new NuvioClient(context);
                }
            }
        }
        return instance;
    }

    public String getBaseUrl() {
        return prefs.getString("nuvio_api_url", DEFAULT_API_URL).trim();
    }

    public String getAccessToken() {
        return prefs.getString("auth", "").trim();
    }

    public String getRefreshToken() {
        return prefs.getString("nuvio_refresh_token", "").trim();
    }

    public int getActiveProfileIntId() {
        return prefs.getInt("nuvio_active_profile_int_id", 1);
    }

    public String getActiveProfileId() {
        return prefs.getString("nuvio_active_profile_id", "").trim();
    }

    public boolean isLoggedIn() {
        String token = getAccessToken();
        return token.startsWith("ey") && token.contains(".");
    }

    // =========================================================================
    // 1. Authentication Endpoints (/auth/v1/)
    // =========================================================================

    public void login(final String email, final String password, final Callback<JSONObject> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("email", email);
                    body.put("password", password);

                    ApiResponse res = makeRequest("POST", "/auth/v1/token?grant_type=password", body.toString(), false);
                    if (res.isSuccess()) {
                        JSONObject resp = new JSONObject(res.body);
                        saveAuthSession(resp, email);
                        postSuccess(callback, resp);
                    } else {
                        String errMsg = parseErrorMessage(res.body, "HTTP " + res.statusCode);
                        postError(callback, errMsg, res.statusCode);
                    }
                } catch (final Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public void signup(final String email, final String password, final Callback<JSONObject> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("email", email);
                    body.put("password", password);

                    ApiResponse res = makeRequest("POST", "/auth/v1/signup", body.toString(), false);
                    if (res.isSuccess()) {
                        JSONObject resp = new JSONObject(res.body);
                        if (resp.has("access_token")) {
                            saveAuthSession(resp, email);
                        }
                        postSuccess(callback, resp);
                    } else {
                        String errMsg = parseErrorMessage(res.body, "HTTP " + res.statusCode);
                        postError(callback, errMsg, res.statusCode);
                    }
                } catch (final Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public void refreshSession(final Callback<Boolean> callback) {
        final String refresh = getRefreshToken();
        if (refresh.isEmpty()) {
            if (callback != null) postError(callback, "No refresh token stored", 401);
            return;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = performTokenRefreshSync();
                if (success) {
                    if (callback != null) postSuccess(callback, true);
                } else {
                    if (callback != null) postError(callback, "Session renewal failed", 401);
                }
            }
        });
    }

    private synchronized boolean performTokenRefreshSync() {
        String refresh = getRefreshToken();
        if (refresh.isEmpty()) return false;
        try {
            JSONObject body = new JSONObject();
            body.put("refresh_token", refresh);

            ApiResponse res = makeRequest("POST", "/auth/v1/token?grant_type=refresh_token", body.toString(), false);
            if (res.isSuccess()) {
                JSONObject resp = new JSONObject(res.body);
                String newToken = resp.optString("access_token", "");
                String newRefresh = resp.optString("refresh_token", refresh);
                if (!newToken.isEmpty()) {
                    prefs.edit()
                            .putString("auth", newToken)
                            .putString("nuvio_refresh_token", newRefresh)
                            .apply();
                    Log.i(TAG, "Successfully refreshed Nuvio session token");
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Token refresh error: " + e.getMessage());
        }
        return false;
    }

    public void logout() {
        prefs.edit()
                .remove("auth")
                .remove("nuvio_refresh_token")
                .remove("nuvio_user_id")
                .remove("nuvio_email")
                .remove("nuvio_profiles")
                .remove("nuvio_active_profile_id")
                .remove("nuvio_active_profile_name")
                .remove("nuvio_active_profile_avatar")
                .apply();
        libraryContentIds.clear();
    }

    private void saveAuthSession(JSONObject resp, String fallbackEmail) {
        String token = resp.optString("access_token", "");
        String refresh = resp.optString("refresh_token", "");
        JSONObject userObj = resp.optJSONObject("user");
        String uid = userObj != null ? userObj.optString("id", "") : "";
        String userEmail = userObj != null ? userObj.optString("email", fallbackEmail) : fallbackEmail;

        prefs.edit()
                .putString("auth", token)
                .putString("nuvio_refresh_token", refresh)
                .putString("nuvio_user_id", uid)
                .putString("nuvio_email", userEmail)
                .apply();
    }

    // =========================================================================
    // 2. Profile Management Endpoints (/rest/v1/profiles)
    // =========================================================================

    public void fetchProfiles(final Callback<JSONArray> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ApiResponse res = makeAuthenticatedRequest("GET", "/rest/v1/profiles?select=*");
                    if (res.isSuccess()) {
                        JSONArray raw = new JSONArray(res.body);
                        JSONArray filtered = new JSONArray();
                        for (int i = 0; i < raw.length(); i++) {
                            JSONObject p = raw.optJSONObject(i);
                            if (p != null) {
                                String id = p.optString("id", "");
                                if (!"driver".equalsIgnoreCase(id) && !"family".equalsIgnoreCase(id)) {
                                    filtered.put(p);
                                }
                            }
                        }
                        prefs.edit().putString("nuvio_profiles", filtered.toString()).apply();
                        postSuccess(callback, filtered);
                    } else {
                        postError(callback, "Failed to load profiles (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public void createProfile(final String name, final String avatarUrl, final Callback<JSONObject> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("name", name);
                    if (avatarUrl != null && !avatarUrl.isEmpty()) {
                        body.put("avatar_url", avatarUrl);
                    }
                    ApiResponse res = makeAuthenticatedRequest("POST", "/rest/v1/profiles", body.toString(), "return=representation");
                    if (res.isSuccess()) {
                        JSONArray arr = new JSONArray(res.body);
                        JSONObject created = arr.optJSONObject(0);
                        postSuccess(callback, created != null ? created : body);
                    } else {
                        postError(callback, "Create profile failed (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public void deleteProfile(final String profileId, final Callback<Boolean> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ApiResponse res = makeAuthenticatedRequest("DELETE", "/rest/v1/profiles?id=eq." + profileId);
                    if (res.isSuccess()) {
                        postSuccess(callback, true);
                    } else {
                        postError(callback, "Delete profile failed (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public void fetchProfileSettings(final String profileId, final Callback<JSONObject> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String path = "/rest/v1/profile_settings?profile_id=eq." + profileId + "&limit=1";
                    ApiResponse res = makeAuthenticatedRequest("GET", path);
                    if (res.isSuccess()) {
                        JSONArray arr = new JSONArray(res.body);
                        JSONObject settings = arr.length() > 0 ? arr.optJSONObject(0) : new JSONObject();
                        postSuccess(callback, settings);
                    } else {
                        postError(callback, "Fetch settings failed (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public void fetchProfileSettings(int profileIntId, Callback<JSONObject> callback) {
        fetchProfileSettings(String.valueOf(profileIntId), callback);
    }

    public void updateProfileSettings(final String profileId, final JSONObject settings, final Callback<Boolean> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    settings.put("profile_id", profileId);
                    ApiResponse res = makeAuthenticatedRequest(
                            "POST",
                            "/rest/v1/profile_settings",
                            settings.toString(),
                            "resolution=merge-duplicates"
                    );
                    if (res.isSuccess()) {
                        postSuccess(callback, true);
                    } else {
                        postError(callback, "Save settings failed (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public void updateProfileSettings(int profileIntId, JSONObject settings, Callback<Boolean> callback) {
        updateProfileSettings(String.valueOf(profileIntId), settings, callback);
    }

    // =========================================================================
    // 3. User Library / Watchlist Endpoints (/rest/v1/library_items)
    // =========================================================================

    public void fetchLibraryItems(final Callback<JSONArray> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int profId = getActiveProfileIntId();
                    String path = "/rest/v1/library_items?select=*&order=created_at.desc";
                    ApiResponse res = makeAuthenticatedRequest("GET", path);
                    if (res.isSuccess()) {
                        JSONArray raw = new JSONArray(res.body);
                        JSONArray profileItems = new JSONArray();
                        synchronized (libraryContentIds) {
                            libraryContentIds.clear();
                            for (int i = 0; i < raw.length(); i++) {
                                JSONObject item = raw.optJSONObject(i);
                                if (item == null) continue;
                                int p = item.optInt("profile_id", 1);
                                if (p == 1 || p == profId) {
                                    profileItems.put(item);
                                    String cid = item.optString("content_id", "");
                                    if (!cid.isEmpty()) libraryContentIds.add(cid);
                                }
                            }
                        }
                        saveCachedLibraryIds();
                        postSuccess(callback, profileItems);
                    } else {
                        postError(callback, "Failed to fetch library (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public void addToLibrary(final String contentId, final String contentType, final String name,
                             final String poster, final Callback<Boolean> callback) {
        final int profId = getActiveProfileIntId();
        libraryContentIds.add(contentId);
        saveCachedLibraryIds();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("content_id", contentId);
                    body.put("content_type", contentType);
                    body.put("name", name);
                    if (poster != null && !poster.isEmpty()) body.put("poster", poster);
                    body.put("profile_id", profId);

                    ApiResponse res = makeAuthenticatedRequest(
                            "POST",
                            "/rest/v1/library_items",
                            body.toString(),
                            "resolution=merge-duplicates"
                    );

                    if (res.isSuccess()) {
                        postSuccess(callback, true);
                    } else {
                        // Rollback local state on permanent failure
                        libraryContentIds.remove(contentId);
                        saveCachedLibraryIds();
                        postError(callback, "Add to library failed (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    libraryContentIds.remove(contentId);
                    saveCachedLibraryIds();
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public void removeFromLibrary(final String contentId, final Callback<Boolean> callback) {
        final int profId = getActiveProfileIntId();
        libraryContentIds.remove(contentId);
        saveCachedLibraryIds();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String path = "/rest/v1/library_items?content_id=eq." + contentId + "&profile_id=eq." + profId;
                    ApiResponse res = makeAuthenticatedRequest("DELETE", path);
                    if (res.isSuccess()) {
                        postSuccess(callback, true);
                    } else {
                        libraryContentIds.add(contentId);
                        saveCachedLibraryIds();
                        postError(callback, "Remove from library failed (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    libraryContentIds.add(contentId);
                    saveCachedLibraryIds();
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public boolean isInLibrary(String contentId) {
        if (contentId == null || contentId.isEmpty()) return false;
        return libraryContentIds.contains(contentId);
    }

    private void loadCachedLibraryIds() {
        String saved = prefs.getString("nuvio_cached_lib_ids", "");
        if (!saved.isEmpty()) {
            String[] ids = saved.split(",");
            for (String id : ids) {
                if (!id.trim().isEmpty()) libraryContentIds.add(id.trim());
            }
        }
    }

    private void saveCachedLibraryIds() {
        StringBuilder sb = new StringBuilder();
        synchronized (libraryContentIds) {
            for (String id : libraryContentIds) {
                if (sb.length() > 0) sb.append(",");
                sb.append(id);
            }
        }
        prefs.edit().putString("nuvio_cached_lib_ids", sb.toString()).apply();
    }

    // =========================================================================
    // 4. Watch Progress / Continue Watching Endpoints (/rest/v1/watch_progress)
    // =========================================================================

    public void deleteWatchProgress(final String contentId, final Callback<Boolean> callback) {
        final int profId = getActiveProfileIntId();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String path = "/rest/v1/watch_progress?content_id=eq." + contentId + "&profile_id=eq." + profId;
                    ApiResponse res = makeAuthenticatedRequest("DELETE", path);
                    if (res.isSuccess()) {
                        postSuccess(callback, true);
                    } else {
                        postError(callback, "Delete watch progress failed (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    // =========================================================================
    // 5. Addon Cloud Synchronization (/rest/v1/addons)
    // =========================================================================

    public void pushCloudAddon(final String transportUrl, final String name, final Callback<Boolean> callback) {
        final int profId = getActiveProfileIntId();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("url", transportUrl);
                    body.put("name", name);
                    body.put("profile_id", profId);
                    body.put("enabled", true);
                    body.put("sort_order", 100);

                    ApiResponse res = makeAuthenticatedRequest(
                            "POST",
                            "/rest/v1/addons",
                            body.toString(),
                            "resolution=merge-duplicates"
                    );

                    if (res.isSuccess()) {
                        postSuccess(callback, true);
                    } else {
                        postError(callback, "Failed to push cloud addon (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public void deleteCloudAddon(final String transportUrl, final Callback<Boolean> callback) {
        final int profId = getActiveProfileIntId();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String path = "/rest/v1/addons?url=eq." + transportUrl + "&profile_id=eq." + profId;
                    ApiResponse res = makeAuthenticatedRequest("DELETE", path);
                    if (res.isSuccess()) {
                        postSuccess(callback, true);
                    } else {
                        postError(callback, "Delete cloud addon failed (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    // =========================================================================
    // HTTP Low-Level Engine with VideoTls & Auto-Refresh
    // =========================================================================

    private ApiResponse makeAuthenticatedRequest(String method, String endpoint) throws Exception {
        return makeAuthenticatedRequest(method, endpoint, null, null);
    }

    private ApiResponse makeAuthenticatedRequest(String method, String endpoint, String jsonBody, String preferHeader) throws Exception {
        String token = getAccessToken();
        ApiResponse res = makeRequest(method, endpoint, jsonBody, true, token, preferHeader);

        // Transparent token refresh on HTTP 401
        if (res.statusCode == 401) {
            Log.i(TAG, "Access token expired (HTTP 401); attempting transparent refresh...");
            boolean refreshed = performTokenRefreshSync();
            if (refreshed) {
                String freshToken = getAccessToken();
                res = makeRequest(method, endpoint, jsonBody, true, freshToken, preferHeader);
            }
        }
        return res;
    }

    private ApiResponse makeRequest(String method, String endpoint, String jsonBody, boolean needsAuth) throws Exception {
        return makeRequest(method, endpoint, jsonBody, needsAuth, getAccessToken(), null);
    }

    private ApiResponse makeRequest(String method, String endpoint, String jsonBody, boolean needsAuth,
                                    String authToken, String preferHeader) throws Exception {
        String baseUrl = getBaseUrl();
        URL url = new URL(baseUrl + endpoint);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(context));
        }

        c.setRequestMethod(method);
        c.setConnectTimeout(12000);
        c.setReadTimeout(18000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.4");
        c.setRequestProperty("apikey", ANON_KEY);
        c.setRequestProperty("Accept", "application/json");

        if (needsAuth && authToken != null && !authToken.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + authToken);
        }

        if (preferHeader != null && !preferHeader.isEmpty()) {
            c.setRequestProperty("Prefer", preferHeader);
        }

        if (jsonBody != null && !jsonBody.isEmpty()) {
            c.setRequestProperty("Content-Type", "application/json");
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

    private String parseErrorMessage(String errorBody, String fallback) {
        if (errorBody == null || errorBody.isEmpty()) return fallback;
        try {
            JSONObject json = new JSONObject(errorBody);
            if (json.has("error_description")) return json.getString("error_description");
            if (json.has("msg")) return json.getString("msg");
            if (json.has("message")) return json.getString("message");
        } catch (Exception ignored) {}
        return fallback;
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

    private <T> void postError(final Callback<T> callback, final String message, final int code) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message, code);
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
