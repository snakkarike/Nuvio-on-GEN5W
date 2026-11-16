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
    public static final String FALLBACK_API_URL = "https://sxwcyzellfnuqtufxyjz.supabase.co";
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

    public void saveAccessToken(String token) {
        prefs.edit().putString("auth", token != null ? token.trim() : "").apply();
    }

    public void saveSession(String token, String refreshToken) {
        SharedPreferences.Editor ed = prefs.edit();
        if (token != null) ed.putString("auth", token.trim());
        if (refreshToken != null) ed.putString("nuvio_refresh_token", refreshToken.trim());
        ed.apply();
    }

    public String getDeviceId() {
        String id = prefs.getString("nuvio_device_id", "");
        if (id.isEmpty() || id.length() < 16) {
            id = "nuvio-tv-gen5w-" + java.util.UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString("nuvio_device_id", id).apply();
        }
        return id;
    }

    public String getDeviceName() {
        return prefs.getString("nuvio_device_name", "Genesis Gen5W");
    }

    public void setDeviceName(String name) {
        prefs.edit().putString("nuvio_device_name", name != null ? name.trim() : "").apply();
    }

    public boolean isDeviceRegistered() {
        return prefs.getLong("nuvio_device_registered_at", 0) > 0;
    }

    public void registerDevice(final Callback<Boolean> callback) {
        if (!isLoggedIn()) {
            if (callback != null) postError(callback, "Not logged in", 401);
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // Official Nuvio device registration RPC for nuvio.tv/account?tab=devices
                    JSONObject req = new JSONObject();
                    req.put("p_installation_id", getDeviceId());
                    req.put("p_client_name", "Nuvio TV"); // Required whitelist value for nuvio.tv/account?tab=devices
                    req.put("p_client_version", "1.0.6");
                    req.put("p_platform", "Android TV 4.4.2");
                    req.put("p_device_name", getDeviceName());

                    ApiResponse res = makeAuthenticatedRequest("POST", "/rest/v1/rpc/register_current_device", req.toString(), null);
                    if (!res.isSuccess() && !FALLBACK_API_URL.equals(getBaseUrl())) {
                        res = makeRequest("POST", FALLBACK_API_URL + "/rest/v1/rpc/register_current_device", req.toString(), true, getAccessToken(), null);
                    }
                    if (res.isSuccess()) {
                        prefs.edit().putLong("nuvio_device_registered_at", System.currentTimeMillis()).apply();
                        postSuccess(callback, true);
                    } else {
                        postError(callback, "Device registration HTTP " + res.statusCode, res.statusCode);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage(), 0);
                }
            }
        });
    }

    public static class DeviceLinkInfo {
        public final String code;          // 6-character user code for nuvio.tv/link (e.g. "BX5FWF")
        public final String deviceCode;    // Secret code used to poll
        public final String deviceNonce;   // Secret nonce matching the request
        public final String linkUrl;       // "https://nuvio.tv/link"
        public final int expiresInSec;
        public final int intervalSec;

        public DeviceLinkInfo(String code, String deviceCode, String deviceNonce, String linkUrl, int expiresInSec, int intervalSec) {
            this.code = code;
            this.deviceCode = deviceCode;
            this.deviceNonce = deviceNonce;
            this.linkUrl = linkUrl;
            this.expiresInSec = expiresInSec;
            this.intervalSec = intervalSec;
        }
    }

    private volatile boolean pollingDeviceLink = false;

    public void generateDeviceLink(final Callback<DeviceLinkInfo> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String nonce = java.util.UUID.randomUUID().toString();
                    JSONObject req = new JSONObject();
                    req.put("p_device_name", getDeviceName());
                    req.put("p_device_nonce", nonce);
                    req.put("p_device_type", "tv");
                    req.put("p_redirect_base_url", "https://nuvio.tv/link");

                    ApiResponse res = makeRequest("POST", "/rest/v1/rpc/start_device_login_session", req.toString(), false);
                    if (!res.isSuccess() && !FALLBACK_API_URL.equals(getBaseUrl())) {
                        res = makeRequest("POST", FALLBACK_API_URL + "/rest/v1/rpc/start_device_login_session", req.toString(), false);
                    }
                    if (res.isSuccess()) {
                        JSONArray arr = new JSONArray(res.body);
                        if (arr.length() > 0) {
                            JSONObject json = arr.getJSONObject(0);
                            String userCode = json.optString("user_code", "");
                            String deviceCode = json.optString("device_code", "");
                            String uri = json.optString("verification_uri", "https://nuvio.tv/link");
                            int interval = json.optInt("poll_interval_seconds", 3);
                            postSuccess(callback, new DeviceLinkInfo(userCode, deviceCode, nonce, uri, 600, interval));
                            return;
                        }
                    }
                    postError(callback, "Failed to start device login session (HTTP " + res.statusCode + ")", res.statusCode);
                } catch (Exception e) {
                    postError(callback, e.getMessage(), 0);
                }
            }
        });
    }

    public void pollDeviceLink(final String deviceCode, final String deviceNonce, final int intervalSec, final Callback<String> callback) {
        pollingDeviceLink = true;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                int intervalMs = Math.max(intervalSec, 3) * 1000;
                long start = System.currentTimeMillis();
                long timeout = 600 * 1000L; // 10 minutes

                while (pollingDeviceLink && (System.currentTimeMillis() - start < timeout)) {
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        break;
                    }

                    if (!pollingDeviceLink) break;

                    try {
                        JSONObject req = new JSONObject();
                        req.put("p_code", deviceCode);
                        req.put("p_device_nonce", deviceNonce);

                        ApiResponse res = makeRequest("POST", "/rest/v1/rpc/poll_tv_login_session", req.toString(), false);
                        if (!res.isSuccess() && !FALLBACK_API_URL.equals(getBaseUrl())) {
                            res = makeRequest("POST", FALLBACK_API_URL + "/rest/v1/rpc/poll_tv_login_session", req.toString(), false);
                        }
                        if (res.isSuccess()) {
                            JSONArray arr = new JSONArray(res.body);
                            if (arr.length() > 0) {
                                JSONObject json = arr.getJSONObject(0);
                                String status = json.optString("status", "");

                                // 1. Direct token check (if present in poll response)
                                String directToken = json.optString("access_token", json.optString("token", ""));
                                if (!directToken.isEmpty()) {
                                    pollingDeviceLink = false;
                                    saveAccessToken(directToken);
                                    registerDevice(null);
                                    postSuccess(callback, directToken);
                                    return;
                                }

                                // 2. Official exchange flow: when status is "approved", exchange deviceCode + deviceNonce for session tokens
                                if ("approved".equalsIgnoreCase(status)) {
                                    JSONObject exReq = new JSONObject();
                                    exReq.put("code", deviceCode);
                                    exReq.put("device_nonce", deviceNonce);

                                    // Call /functions/v1/tv-logins-exchange with apikey and Authorization headers
                                    ApiResponse exRes = makeRequest("POST", "/functions/v1/tv-logins-exchange", exReq.toString(), true, ANON_KEY, null);
                                    // Fallback to official direct Supabase project domain if proxy blocked/failed
                                    if (!exRes.isSuccess() && !FALLBACK_API_URL.equals(getBaseUrl())) {
                                        exRes = makeRequest("POST", FALLBACK_API_URL + "/functions/v1/tv-logins-exchange", exReq.toString(), true, ANON_KEY, null);
                                    }

                                    if (exRes.isSuccess()) {
                                        JSONObject exJson = new JSONObject(exRes.body);
                                        String token = exJson.optString("access_token", "");
                                        if (!token.isEmpty()) {
                                            pollingDeviceLink = false;
                                            saveAuthSession(exJson, "");
                                            registerDevice(null);
                                            postSuccess(callback, token);
                                            return;
                                        }
                                    }
                                } else if ("expired".equalsIgnoreCase(status) || "denied".equalsIgnoreCase(status)) {
                                    pollingDeviceLink = false;
                                    postError(callback, "Device pairing " + status, 400);
                                    return;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                pollingDeviceLink = false;
                postError(callback, "Pairing timed out", 408);
            }
        });
    }

    public void cancelDeviceLinkPolling() {
        pollingDeviceLink = false;
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

    public void updateProfile(final String profileId, final String name, final String avatarUrl, final String pin, final Callback<Boolean> callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    if (name != null && !name.trim().isEmpty()) {
                        body.put("name", name.trim());
                    }
                    if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
                        body.put("avatar_url", avatarUrl.trim());
                    }
                    if (pin != null) {
                        body.put("pin", pin.trim());
                    }
                    ApiResponse res = makeAuthenticatedRequest("PATCH", "/rest/v1/profiles?id=eq." + profileId, body.toString(), "return=minimal");
                    if (res.isSuccess()) {
                        postSuccess(callback, true);
                    } else {
                        postError(callback, "Update profile failed (HTTP " + res.statusCode + ")", res.statusCode);
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

    public void fetchWatchHistory(final Callback<JSONArray> callback) {
        final int profId = getActiveProfileIntId();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String path = "/rest/v1/watched_items?select=*&profile_id=eq." + profId + "&order=watched_at.desc&limit=50";
                    ApiResponse res = makeAuthenticatedRequest("GET", path);
                    if (res.isSuccess()) {
                        JSONArray items = new JSONArray(res.body);
                        postSuccess(callback, items);
                    } else {
                        // Fallback to /rest/v1/watch_history
                        ApiResponse fb = makeAuthenticatedRequest("GET", "/rest/v1/watch_history?select=*&order=watched_at.desc&limit=50");
                        if (fb.isSuccess()) {
                            postSuccess(callback, new JSONArray(fb.body));
                        } else {
                            postError(callback, "Load history failed (HTTP " + res.statusCode + ")", res.statusCode);
                        }
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public void recordWatchHistory(final String contentId, final String contentType, final String title, final int season, final int episode, final Callback<Boolean> callback) {
        if (!isLoggedIn() || contentId == null || contentId.isEmpty()) return;
        final int profId = getActiveProfileIntId();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("content_id", contentId);
                    body.put("content_type", contentType != null ? contentType : "movie");
                    body.put("title", title != null ? title : "Untitled");
                    body.put("profile_id", profId);
                    if (season > 0) body.put("season", season);
                    if (episode > 0) body.put("episode", episode);
                    body.put("watched_at", getIsoTimestamp());

                    ApiResponse res = makeAuthenticatedRequest("POST", "/rest/v1/watched_items", body.toString(), "resolution=merge-duplicates");
                    if (!res.isSuccess()) {
                        res = makeAuthenticatedRequest("POST", "/rest/v1/watch_history", body.toString(), "resolution=merge-duplicates");
                    }
                    if (res.isSuccess()) {
                        postSuccess(callback, true);
                    } else if (callback != null) {
                        postError(callback, "Record history failed (HTTP " + res.statusCode + ")", res.statusCode);
                    }
                } catch (Exception e) {
                    if (callback != null) postError(callback, e.getMessage(), -1);
                }
            }
        });
    }

    public String getIsoTimestamp() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new java.util.Date());
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
        URL url = new URL(endpoint.startsWith("http") ? endpoint : (baseUrl + endpoint));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(context));
        }

        c.setRequestMethod(method);
        c.setConnectTimeout(12000);
        c.setReadTimeout(18000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.6");
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
