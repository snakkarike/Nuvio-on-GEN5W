package org.nuvio.gen5w;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.nuvio.gen5w.api.NuvioClient;
import org.nuvio.gen5w.debrid.TorboxClient;
import org.nuvio.gen5w.gate.MotionGuard;
import org.nuvio.gen5w.gate.VehicleVideoGate;
import org.nuvio.gen5w.player.AudioFocusHolder;
import org.nuvio.gen5w.player.ExoPlayerActivity;
import org.nuvio.gen5w.player.VideoTls;
import org.nuvio.gen5w.trakt.TraktClient;
import org.nuvio.gen5w.ui.FlagText;
import org.nuvio.gen5w.ui.M3Switch;
import org.nuvio.gen5w.ui.ThemeManager;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Nuvio on GEN5W - Pure Native Android 4.4 KitKat UI.
 * High-performance 1920x720 automotive layout with Stremio account sync, Cinemeta,
 * community addons, and native Torbox debrid resolution.
 */
public class MainActivity extends Activity {

    private static final String TAG = "NuvioMainActivity";
    private static final String CINEMETA = "https://v3-cinemeta.strem.io/";
    public static final String DEFAULT_NUVIO_API = "https://api.nuvio.tv";
    public static final String NUVIO_ANON_KEY = "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN";
    private static final long CATALOG_AGE = 30 * 60 * 1000L;
    private static final long ADDONS_AGE = 20 * 60 * 1000L;

    int bg = 0xFF141218, accent = 0xFFD0BCFF, surface = 0xFF24212B, muted = 0xFFCAC4D0;
    public static class RoundedImageView extends ImageView {
        private final Path clipPath = new Path();
        private final RectF rect = new RectF();
        private float cornerRadius = 0;

        public RoundedImageView(Context context) {
            super(context);
            if (Build.VERSION.SDK_INT < 21) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            }
        }

        public void setCornerRadius(float radiusPx) {
            this.cornerRadius = radiusPx;
            updatePath();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updatePath();
        }

        private void updatePath() {
            if (getWidth() > 0 && getHeight() > 0 && cornerRadius > 0) {
                clipPath.reset();
                rect.set(0, 0, getWidth(), getHeight());
                clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            if (cornerRadius > 0 && !clipPath.isEmpty()) {
                int save = canvas.save();
                canvas.clipPath(clipPath);
                super.draw(canvas);
                canvas.restoreToCount(save);
            } else {
                super.draw(canvas);
            }
        }
    }

    ThemeManager.Palette theme;

    void loadActiveTheme() {
        theme = ThemeManager.loadSavedPalette(this);
        bg = theme.surface;
        surface = theme.surfaceContainer;
        accent = theme.primary;
        muted = theme.onSurfaceVariant;
    }
    LinearLayout root, body;
    TextView streamTitleView;
    String page = "Home", auth = "", selectedId = "", selectedType = "movie", selectedVideoId = "", selectedVideoLabel = "", selectedPoster = "", selectedName = "";
    ArrayList<String> history = new ArrayList<>();
    JSONArray addons = new JSONArray();
    JSONArray libraryItems = new JSONArray();
    SharedPreferences prefs;

    boolean parkedConfirmed = false, warningShown = false, closingApp = false;
    boolean motionLocked = false;
    String motionReason = "Motion lockout is off.";
    Dialog parkedDialog, motionDialog;
    TextView motionDialogText;
    MotionGuard motionGuard;

    final ExecutorService apiWorkers = Executors.newFixedThreadPool(4);
    final ExecutorService catalogWorkers = Executors.newFixedThreadPool(4);
    final ExecutorService imageWorkers = Executors.newFixedThreadPool(4);

    final LruCache<String, Bitmap> memoryPosters = new LruCache<String, Bitmap>(32768) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    final LruCache<String, Bitmap> avatarMemory = new LruCache<String, Bitmap>(32);

    final HashMap<String, JSONObject> catalogMemory = new HashMap<>();
    final HashMap<String, Long> catalogFetched = new HashMap<>();
    final HashSet<String> catalogPending = new HashSet<>();
    final HashMap<String, ArrayList<WeakReference<LinearLayout>>> catalogWaiters = new HashMap<>();
    final HashMap<String, JSONObject> streamMemory = new HashMap<>();
    final HashSet<String> streamPending = new HashSet<>();
    final LinkedHashMap<String, JSONObject> visibleSources = new LinkedHashMap<>();
    LinearLayout sourcePanel;
    TextView sourceStatus;
    String sourceFilter = "All", visibleStreamTitle = "";
    final LinkedHashMap<String, Button> qualityButtons = new LinkedHashMap<>();
    int sourceRequests = 0;
    long lastAddonSync = 0;
    boolean addonsLoading = false;
    String verifiedAccountEmail = "";
    final ArrayList<String> errors = new ArrayList<>();

    // TLS helper for image loading
    private SSLSocketFactory posterTlsFactory;

    // Automotive park & motion gate
    private VehicleVideoGate vehicleGate;

    int dp(int x) {
        return (int) (x * getResources().getDisplayMetrics().density + 0.5f);
    }

    String fileKey(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) out.append('0');
                out.append(hex);
            }
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    File dataFile(String group, String key) {
        File dir = new File(getCacheDir(), group);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, fileKey(key) + ".json");
    }

    File posterFile(String url) {
        File dir = new File(getCacheDir(), "posters");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, fileKey(url) + ".img");
    }

    byte[] bytes(File file, int limit) throws IOException {
        FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                output.write(buf, 0, n);
                if (output.size() > limit) throw new IOException("Cache item too large");
            }
        } finally {
            in.close();
        }
        return output.toByteArray();
    }

    void saveBytes(File target, byte[] data) {
        if (data == null || data.length == 0) return;
        try {
            File temp = new File(target.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            if (!temp.renameTo(target)) {
                if (target.exists()) target.delete();
                temp.renameTo(target);
            }
        } catch (Exception ignored) {}
    }

    JSONObject readJson(File file) {
        try {
            return new JSONObject(new String(bytes(file, 2 * 1024 * 1024), "UTF-8"));
        } catch (Exception ignored) {
            return null;
        }
    }

    void cacheJson(File file, JSONObject item) {
        try {
            saveBytes(file, item.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadActiveTheme();
        prefs = getSharedPreferences("nuvio_stremio_prefs", MODE_PRIVATE);
        sourceFilter = prefs.getString("pref_stream_quality", "All");
        parkedConfirmed = prefs.getBoolean("disable_popup", false) && prefs.getBoolean("welcome_seen", false);
        auth = prefs.getString("auth", "");
        verifiedAccountEmail = prefs.getString("nuvio_email", prefs.getString("verified_account_email", ""));
        refreshAvatarCatalog();
        if (!auth.isEmpty()) {
            if (NuvioClient.getInstance(this).isLoggedIn()) {
                NuvioClient.getInstance(this).refreshSession(null);
                NuvioClient.getInstance(this).fetchLibraryItems(null);
                fetchCloudProfileSettings(prefs.getString("nuvio_active_profile_id", ""));
            }
            fetchNuvioProfiles();
            fetchNuvioAddons();
            fetchNuvioWatchProgress();
            NuvioClient.getInstance(this).registerDevice(null);
        }

        restoreAddons();
        if (savedInstanceState != null) {
            selectedId = savedInstanceState.getString("selected_id", "");
            selectedType = savedInstanceState.getString("selected_type", "movie");
            selectedName = savedInstanceState.getString("selected_name", "");
            selectedVideoId = savedInstanceState.getString("selected_video_id", "");
            selectedVideoLabel = savedInstanceState.getString("selected_video_label", "");
            selectedPoster = savedInstanceState.getString("selected_poster", "");
            String savedPage = savedInstanceState.getString("last_page", "Movies");
            render(savedPage, false);
        } else {
            selectedId = "";
            selectedType = "movie";
            selectedName = "";
            selectedVideoId = "";
            selectedVideoLabel = "";
            selectedPoster = "";
            if (prefs != null) {
                prefs.edit().remove("last_page").remove("selected_id").remove("selected_video_id").apply();
            }
            render("Movies", false);
        }

        immersiveWindow();
        vehicleGate = new VehicleVideoGate(this);
        vehicleGate.start();
        showParkedConfirmation();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("selected_id", selectedId);
        outState.putString("selected_type", selectedType);
        outState.putString("selected_name", selectedName);
        outState.putString("selected_video_id", selectedVideoId);
        outState.putString("selected_video_label", selectedVideoLabel);
        outState.putString("selected_poster", selectedPoster);
        outState.putString("last_page", page);
    }

    void immersiveWindow() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersiveWindow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (vehicleGate != null) vehicleGate.start();
        immersiveWindow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (vehicleGate != null) vehicleGate.stop();
    }

    @Override
    protected void onDestroy() {
        if (vehicleGate != null) vehicleGate.stop();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            AudioFocusHolder.release(am);
        }
        super.onDestroy();
    }

    void showParkedConfirmation() {
        final boolean firstLaunch = !prefs.getBoolean("welcome_seen", false);
        if (!firstLaunch && prefs.getBoolean("disable_popup", false)) {
            parkedConfirmed = true;
            return;
        }
        if (parkedConfirmed || warningShown || closingApp || isFinishing() ||
                (parkedDialog != null && parkedDialog.isShowing())) return;

        warningShown = true;
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        parkedDialog = dialog;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(18), dp(22), dp(18));
        ThemeManager.styleSurfaceCard(this, panel, theme != null ? theme.surfaceContainer : surface, 14, 1, theme != null ? theme.outlineVariant : 0);
        panel.setGravity(Gravity.CENTER);

        TextView heading = text(firstLaunch ? "WELCOME TO NUVIO" : "PARKED VEHICLE REQUIRED", 20);
        heading.setTextColor(accent);
        heading.setTypeface(null, 1);
        panel.addView(heading);

        TextView copy = text(firstLaunch ?
                "Nuvio Auto enables Stremio streaming and Debrid media directly on your car screen.\n\nVideo playback is for use only while parked. Never watch or operate video while driving." :
                "Confirm your vehicle is fully parked. Video playback is for use only while parked. Never watch or operate video while driving.", 15);
        copy.setPadding(dp(8), dp(10), dp(8), dp(14));
        copy.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        panel.addView(copy);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button exit = btn("Exit App", new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
                finish();
            }
        });
        Button confirm = btn(firstLaunch ? "I'm Parked — Let's Go" : "I'm Parked — Continue", new Runnable() {
            @Override
            public void run() {
                if (firstLaunch) prefs.edit().putBoolean("welcome_seen", true).commit();
                parkedConfirmed = true;
                dialog.dismiss();
                parkedDialog = null;
            }
        });

        if (theme != null) {
            ThemeManager.stylePillButton(this, exit, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
            ThemeManager.stylePillButton(this, confirm, theme.primary, theme.onPrimary, 0, 0, true);
        } else {
            confirm.setBackgroundDrawable(shape(accent, 10));
            confirm.setTextColor(Color.WHITE);
        }

        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, dp(48), 1);
        el.setMargins(0, 0, dp(8), 0);
        actions.addView(exit, el);
        actions.addView(confirm, new LinearLayout.LayoutParams(0, dp(48), 2));
        panel.addView(actions);

        dialog.setContentView(panel);
        dialog.show();

        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(720)), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
    }

    void render(String next, boolean push) {
        loadActiveTheme();
        if (push && !page.equals(next)) history.add(page);
        page = next;

        if ("Home".equals(next) || "Streams".equals(next) || "Library".equals(next) || "Search".equals(next)) {
            prefs.edit().putString("last_page", next).apply();
        }

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        // Header bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(12), dp(4));

        LinearLayout brandBox = new LinearLayout(this);
        brandBox.setOrientation(LinearLayout.HORIZONTAL);
        brandBox.setGravity(Gravity.CENTER_VERTICAL);

        ImageView brandLogo = new ImageView(this);
        brandLogo.setScaleType(ImageView.ScaleType.FIT_START);
        brandLogo.setAdjustViewBounds(true);
        String logoRes = (theme != null && theme.isLight) ? "nuvio_logo_light" : "nuvio_logo";
        int logoId = getResources().getIdentifier(logoRes, "drawable", getPackageName());
        if (logoId == 0) logoId = getResources().getIdentifier("nuvio_logo", "drawable", getPackageName());
        if (logoId != 0) {
            brandLogo.setImageResource(logoId);
            brandBox.addView(brandLogo, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)));
        } else {
            ImageView markView = new ImageView(this);
            int markId = getResources().getIdentifier("nuvio_auto_icon", "drawable", getPackageName());
            if (markId != 0) {
                markView.setImageResource(markId);
                LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(28), dp(28));
                markLp.setMargins(0, 0, dp(8), 0);
                brandBox.addView(markView, markLp);
            }
            TextView brand = text("NUVIO", 20);
            brand.setTextColor(accent);
            brand.setTypeface(null, 1);
            brandBox.addView(brand);
        }
        brandBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                history.clear();
                selectedVideoId = "";
                selectedVideoLabel = "";
                render("Movies", false);
            }
        });
        header.addView(brandBox, new LinearLayout.LayoutParams(0, dp(44), 1));

        TextView current = text("v1.0.6 • " + page.toUpperCase(Locale.US), 13);
        current.setTextColor(muted);
        header.addView(current);
        root.addView(header);

        if ("Streams".equals(page)) {
            // Media detail & stream selection: pinned top bar with Back button, media title, and badge
            LinearLayout nav = new LinearLayout(this);
            nav.setOrientation(LinearLayout.HORIZONTAL);
            nav.setGravity(Gravity.CENTER_VERTICAL);
            nav.setPadding(dp(10), dp(2), dp(10), dp(6));

            Button backBtn = btn("← Back", new Runnable() {
                @Override
                public void run() {
                    back();
                }
            });
            backBtn.setTextSize(13);
            ThemeManager.stylePillButton(this, backBtn, theme.primaryContainer, theme.onPrimaryContainer, 0, 0, true);
            nav.addView(backBtn, new LinearLayout.LayoutParams(dp(100), dp(38)));

            streamTitleView = text((selectedName.isEmpty() || "Untitled".equals(selectedName)) ? selectedId : selectedName, 18);
            streamTitleView.setTypeface(null, 1);
            streamTitleView.setTextColor(theme.onSurface);
            streamTitleView.setPadding(dp(14), 0, dp(14), 0);
            streamTitleView.setSingleLine(true);
            streamTitleView.setEllipsize(TextUtils.TruncateAt.END);
            nav.addView(streamTitleView, new LinearLayout.LayoutParams(0, -2, 1));

            if (!selectedVideoLabel.isEmpty()) {
                TextView epBadge = badge(selectedVideoLabel, theme.secondaryContainer, theme.onSecondaryContainer, 0);
                epBadge.setTextSize(12);
                epBadge.setPadding(dp(12), dp(4), dp(12), dp(4));
                nav.addView(epBadge, new LinearLayout.LayoutParams(-2, -2));
            } else if ("series".equals(selectedType)) {
                TextView typeBadge = badge("TV SERIES", theme.surfaceContainerHighest, theme.primary, theme.outlineVariant);
                typeBadge.setTextSize(12);
                typeBadge.setPadding(dp(12), dp(4), dp(12), dp(4));
                nav.addView(typeBadge, new LinearLayout.LayoutParams(-2, -2));
            } else {
                TextView typeBadge = badge("MOVIE", theme.surfaceContainerHighest, theme.primary, theme.outlineVariant);
                typeBadge.setTextSize(12);
                typeBadge.setPadding(dp(12), dp(4), dp(12), dp(4));
                nav.addView(typeBadge, new LinearLayout.LayoutParams(-2, -2));
            }

            root.addView(nav);
        } else {
            // Main Top Navigation Bar - Material Design 3 Navigation Rail Pills
            LinearLayout nav = new LinearLayout(this);
            nav.setOrientation(LinearLayout.HORIZONTAL);
            nav.setPadding(dp(8), dp(2), dp(8), dp(6));

            String[] labels = {"Movies", "TV Series", "Library", "Search", "Add-ons", "Debrid", "Account", "Info", "Settings"};
            final String[] targets = {"Movies", "TV Series", "Library", "Search", "Add-ons", "Debrid", "Account", "Info", "Settings"};

            for (int i = 0; i < labels.length; i++) {
                final String target = targets[i];
                Button tab = btn(labels[i], new Runnable() {
                    @Override
                    public void run() {
                        render(target, false);
                    }
                });
                tab.setTextSize(15);
                tab.setSingleLine(true);
                tab.setPadding(dp(2), dp(2), dp(2), dp(2));
                boolean isSelected = target.equals(page) || ("Home".equals(page) && "Movies".equals(target));
                ThemeManager.styleNavPill(this, tab, theme, labels[i], isSelected);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
                lp.setMargins(dp(3), 0, dp(3), 0);
                nav.addView(tab, lp);
            }
            root.addView(nav);
        }

        // Scrollable Body Content
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setVerticalScrollBarEnabled(true);
        sc.setScrollbarFadingEnabled(true);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(10), dp(4), dp(10), dp(80));
        sc.addView(body, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        if ("Home".equals(page) || "Movies".equals(page)) moviesView();
        else if ("TV Series".equals(page)) seriesView();
        else if ("Library".equals(page)) libraryView();
        else if ("Add-ons".equals(page)) showAddons();
        else if ("Search".equals(page)) searchView();
        else if ("Debrid".equals(page)) debridView();
        else if ("Account".equals(page)) accountView();
        else if ("Info".equals(page)) infoView();
        else if ("Settings".equals(page)) settings();
        else if ("Streams".equals(page)) loadStreams();
    }

    void back() {
        if ("series".equals(selectedType) && !selectedVideoId.isEmpty()) {
            selectedVideoId = "";
            selectedVideoLabel = "";
            render("Streams", false);
            return;
        }
        if (!history.isEmpty()) {
            String prev = history.remove(history.size() - 1);
            if (!"Streams".equals(prev)) {
                selectedVideoId = "";
                selectedVideoLabel = "";
            }
            render(prev, false);
        } else {
            selectedVideoId = "";
            selectedVideoLabel = "";
            render("Movies", false);
        }
    }

    @Override
    public void onBackPressed() {
        if ("Streams".equals(page)) {
            back();
        } else if (!history.isEmpty()) {
            back();
        } else if (!"Movies".equals(page) && !"Home".equals(page)) {
            render("Movies", false);
        } else {
            super.onBackPressed();
        }
    }

    boolean isNuvioToken(String token) {
        if (token == null) return false;
        String t = token.trim();
        return t.startsWith("ey") && t.contains(".");
    }

    boolean isLoggedIn() {
        return isNuvioToken(auth);
    }

    void showContinueWatching(final String filterType) {
        String data = prefs.getString("nuvio_continue_watching", "");
        if (data.isEmpty()) return;
        try {
            JSONArray all = new JSONArray(data);
            final ArrayList<JSONObject> list = new ArrayList<>();
            String activeProfId = prefs.getString("nuvio_active_profile_id", "");
            int activeProfInt = prefs.getInt("nuvio_active_profile_int_id", 1);
            HashSet<String> seenIds = new HashSet<String>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (id.isEmpty() || seenIds.contains(id)) continue;
                String type = item.optString("type", "movie");
                if (filterType != null && !filterType.equals(type)) continue;
                String itemProf = item.optString("profileId", "");
                if (!activeProfId.isEmpty() && !itemProf.isEmpty()) {
                    boolean match = activeProfId.equals(itemProf) || String.valueOf(activeProfInt).equals(itemProf);
                    if (!match) continue;
                }
                long pos = item.optLong("positionMs", 0);
                long dur = item.optLong("durationMs", 0);
                if (pos > 5000 && (dur <= 0 || pos < dur * 0.95)) {
                    seenIds.add(id);
                    list.add(item);
                }
            }
            if (list.isEmpty()) return;

            LinearLayout section = new LinearLayout(this);
            section.setOrientation(LinearLayout.VERTICAL);
            section.setPadding(0, dp(4), 0, dp(12));

            String title = "▶ Continue Watching" + (filterType == null ? "" : (" (" + ("movie".equals(filterType) ? "Movies" : "Series") + ")"));
            TextView heading = text(title, 17);
            heading.setTypeface(null, 1);
            heading.setTextColor(accent);
            section.addView(heading);

            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            for (final JSONObject it : list) {
                final String id = it.optString("id", "");
                final String type = it.optString("type", "movie");
                String rawName = it.optString("name", "");
                if (rawName.isEmpty() || "Untitled".equals(rawName)) {
                    rawName = id;
                }
                final String name = rawName;
                final String poster = it.optString("poster", "");
                final long pos = it.optLong("positionMs", 0);
                final long dur = it.optLong("durationMs", 0);
                String vId = it.optString("videoId", "");
                int season = it.optInt("season", -1);
                int episode = it.optInt("episode", -1);
                String vLabel = it.optString("videoLabel", "");

                if ("series".equals(type)) {
                    if ((season <= 0 || episode <= 0) && vId.contains(":")) {
                        String[] p = vId.split(":");
                        if (p.length >= 3) {
                            try {
                                if (season <= 0) season = Integer.parseInt(p[1]);
                                if (episode <= 0) episode = Integer.parseInt(p[2]);
                            } catch (Exception ignored) {}
                        }
                    }
                    if (season > 0 && episode > 0) {
                        if (!vId.contains(":")) {
                            vId = id + ":" + season + ":" + episode;
                        }
                        if (vLabel.isEmpty()) {
                            vLabel = "Season " + season + ", Episode " + episode;
                        }
                    }
                }
                if (vId.isEmpty()) vId = id;

                final String finalVidId = vId;
                final String finalVidLabel = vLabel;
                final String[] fName = new String[]{ name };
                final String[] fPoster = new String[]{ poster };
                final String[] fVidId = new String[]{ finalVidId };
                final String[] fVidLabel = new String[]{ finalVidLabel };

                final LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(6), dp(6), dp(6), dp(8));
                card.setClickable(true);
                card.setFocusable(true);
                if (theme != null) {
                    ThemeManager.styleInteractiveCard(this, card, theme.surfaceContainer, theme.onSurface, 12, 1, theme.outlineVariant);
                } else {
                    card.setBackgroundDrawable(shape(surface, 12));
                }

                RoundedImageView cover = new RoundedImageView(this);
                cover.setCornerRadius(dp(8));
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (theme != null) {
                    ThemeManager.styleSurfaceCard(this, cover, theme.surfaceContainerHighest, 8, 0, 0);
                } else {
                    cover.setBackgroundDrawable(shape(0xff232b38, 8));
                }

                FrameLayout tile = new FrameLayout(this);
                tile.addView(cover, new FrameLayout.LayoutParams(-1, -1));

                TextView label = text(name, 12);
                label.setGravity(Gravity.CENTER);
                label.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
                label.setMaxLines(3);
                label.setEllipsize(TextUtils.TruncateAt.END);
                tile.addView(label, new FrameLayout.LayoutParams(-1, -1));
                cover.setTag(label);

                card.addView(tile, new LinearLayout.LayoutParams(dp(130), dp(175)));

                if (dur > 0) {
                    FrameLayout progressTrack = new FrameLayout(this);
                    if (theme != null) {
                        ThemeManager.styleSurfaceCard(this, progressTrack, theme.surfaceContainerHighest, 2, 0, 0);
                    } else {
                        progressTrack.setBackgroundDrawable(shape(0xcc000000, 2));
                    }
                    View progressFill = new View(this);
                    if (theme != null) {
                        ThemeManager.styleSurfaceCard(this, progressFill, theme.primary, 2, 0, 0);
                    } else {
                        progressFill.setBackgroundDrawable(shape(accent, 2));
                    }
                    int fillWidth = (int) (dp(130) * Math.min(1.0, (double) pos / (double) dur));
                    FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(fillWidth, dp(4));
                    progressTrack.addView(progressFill, fillLp);
                    LinearLayout.LayoutParams pTrackLp = new LinearLayout.LayoutParams(dp(130), dp(4));
                    pTrackLp.setMargins(0, dp(4), 0, 0);
                    card.addView(progressTrack, pTrackLp);
                }

                LinearLayout info = new LinearLayout(this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setPadding(dp(2), dp(6), dp(2), dp(2));

                final TextView titleView = text(name, 12);
                titleView.setTypeface(null, 1);
                titleView.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
                titleView.setMaxLines(1);
                titleView.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(titleView);

                String epPrefix = "";
                if (!finalVidLabel.isEmpty()) {
                    epPrefix = finalVidLabel.replace("Season ", "S").replace(", Episode ", "E");
                    if (epPrefix.contains(" • ")) epPrefix = epPrefix.substring(0, epPrefix.indexOf(" • "));
                    epPrefix = epPrefix + " • ";
                }
                String minText = dur > 0 ? ((pos / 60000) + " / " + (dur / 60000) + " min") : ((pos / 60000) + " min");
                TextView resumeText = text("▶ " + epPrefix + minText, 11);
                resumeText.setTypeface(null, 1);
                resumeText.setTextColor(theme != null ? theme.primary : accent);
                resumeText.setSingleLine(true);
                resumeText.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(resumeText);

                card.addView(info, new LinearLayout.LayoutParams(dp(130), -2));

                if (poster.startsWith("http")) loadPoster(cover, poster);

                if (!poster.startsWith("http") || fName[0].equals(id) || "Untitled".equals(fName[0])) {
                    final ImageView fCover = cover;
                    final TextView fLabel = label;
                    final TextView fTitle = titleView;
                    apiWorkers.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                JSONObject m = get(CINEMETA + "meta/" + type + "/" + id + ".json");
                                final JSONObject meta = m.optJSONObject("meta");
                                if (meta != null) {
                                    final String pUrl = meta.optString("poster", "");
                                    final String pTitle = meta.optString("name", "");
                                    if (!pTitle.isEmpty()) {
                                        fName[0] = pTitle;
                                        try { it.put("name", pTitle); } catch (Exception ignored) {}
                                    }
                                    if (pUrl.startsWith("http")) {
                                        fPoster[0] = pUrl;
                                        try { it.put("poster", pUrl); } catch (Exception ignored) {}
                                    }
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!pTitle.isEmpty()) {
                                                fLabel.setText(pTitle);
                                                fTitle.setText(pTitle);
                                            }
                                            if (pUrl.startsWith("http")) loadPoster(fCover, pUrl);
                                        }
                                    });
                                }
                            } catch (Exception ignored) {}
                        }
                    });
                }

                final Runnable open = new Runnable() {
                    @Override
                    public void run() {
                        selectedId = id;
                        selectedType = type;
                        selectedName = fName[0];
                        selectedPoster = fPoster[0];
                        selectedVideoId = fVidId[0];
                        selectedVideoLabel = fVidLabel[0];
                        rememberTitle();
                        render("Streams", true);
                    }
                };
                View.OnClickListener openListener = new View.OnClickListener() {
                    @Override public void onClick(View v) { open.run(); }
                };
                View.OnLongClickListener dismissListener = new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Remove from Continue Watching?")
                                .setMessage("Remove \"" + fName[0] + "\" from your watch progress?")
                                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        dismissContinueWatching(id);
                                    }
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                        return true;
                    }
                };
                card.setOnClickListener(openListener);
                tile.setOnClickListener(openListener);
                card.setOnLongClickListener(dismissListener);
                tile.setOnLongClickListener(dismissListener);

                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(142), -2);
                cardLp.setMargins(0, dp(4), dp(10), dp(8));
                row.addView(card, cardLp);
            }
            scroll.addView(row);
            section.addView(scroll);
            add(section);
        } catch (Exception ignored) {}
    }

    void showUpcoming(final String filterType) {
        if ("series".equals(filterType)) {
            showUpcomingSeries();
        } else {
            showUpcomingMovies();
        }
    }

    void showUpcomingSeries() {
        final String data = prefs.getString("nuvio_continue_watching", "");
        final ArrayList<JSONObject> seriesInProgress = new ArrayList<JSONObject>();
        if (!data.isEmpty()) {
            try {
                JSONArray all = new JSONArray(data);
                HashSet<String> seen = new HashSet<String>();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject item = all.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id", "");
                    if (id.isEmpty() || seen.contains(id)) continue;
                    if (!"series".equals(item.optString("type", ""))) continue;
                    seen.add(id);
                    seriesInProgress.add(item);
                }
            } catch (Exception ignored) {}
        }

        if (seriesInProgress.size() > 3) { while (seriesInProgress.size() > 3) seriesInProgress.remove(seriesInProgress.size() - 1); }
        if (!seriesInProgress.isEmpty()) {
            final LinearLayout section = new LinearLayout(this);
            section.setOrientation(LinearLayout.VERTICAL);
            section.setPadding(0, dp(4), 0, dp(12));

            final TextView heading = text("🗓️ Next Up & Upcoming Episodes", 17);
            heading.setTypeface(null, 1);
            heading.setTextColor(accent);
            section.addView(heading);

            final HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            scroll.addView(row);
            section.addView(scroll);
            add(section);

            apiWorkers.execute(new Runnable() {
                @Override
                public void run() {
                    for (final JSONObject sItem : seriesInProgress) {
                        final String id = sItem.optString("id", "");
                        final String name = sItem.optString("name", id);
                        final String poster = sItem.optString("poster", "");
                        String vId = sItem.optString("videoId", "");
                        int curSeason = sItem.optInt("season", -1);
                        int curEpisode = sItem.optInt("episode", -1);

                        if ((curSeason <= 0 || curEpisode <= 0) && vId.contains(":")) {
                            String[] p = vId.split(":");
                            if (p.length >= 3) {
                                try {
                                    if (curSeason <= 0) curSeason = Integer.parseInt(p[1]);
                                    if (curEpisode <= 0) curEpisode = Integer.parseInt(p[2]);
                                } catch (Exception ignored) {}
                            }
                        }

                        if (curSeason <= 0) curSeason = 1;
                        if (curEpisode <= 0) curEpisode = 1;

                        try {
                            JSONObject metaResp = get(CINEMETA + "meta/series/" + enc(id) + ".json");
                            JSONObject meta = metaResp != null ? metaResp.optJSONObject("meta") : null;
                            if (meta == null) continue;
                            JSONArray videos = meta.optJSONArray("videos");
                            if (videos == null || videos.length() == 0) continue;

                            JSONObject nextVid = null;
                            int targetS = curSeason;
                            int targetE = curEpisode + 1;

                            // 1. Next episode in same season
                            for (int v = 0; v < videos.length(); v++) {
                                JSONObject cand = videos.optJSONObject(v);
                                if (cand == null) continue;
                                int s = cand.optInt("season", -1);
                                int e = cand.optInt("number", cand.optInt("episode", -1));
                                if (s == targetS && e == targetE) {
                                    nextVid = cand;
                                    break;
                                }
                            }

                            // 2. Or Season (S+1) Episode 1
                            if (nextVid == null) {
                                for (int v = 0; v < videos.length(); v++) {
                                    JSONObject cand = videos.optJSONObject(v);
                                    if (cand == null) continue;
                                    int s = cand.optInt("season", -1);
                                    int e = cand.optInt("number", cand.optInt("episode", -1));
                                    if (s == curSeason + 1 && (e == 1 || e == 0)) {
                                        nextVid = cand;
                                        targetS = curSeason + 1;
                                        targetE = e;
                                        break;
                                    }
                                }
                            }

                            if (nextVid != null) {
                                final String nextId = nextVid.optString("id", id + ":" + targetS + ":" + targetE);
                                final String epName = nextVid.optString("title", nextVid.optString("name", "Episode " + targetE));
                                final String thumb = nextVid.optString("thumbnail", poster);
                                final String released = nextVid.optString("released", "");
                                final int finalS = targetS;
                                final int finalE = targetE;

                                boolean isUpcoming = false;
                                if (!released.isEmpty()) {
                                    try {
                                        isUpcoming = released.compareTo(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date())) > 0;
                                    } catch (Exception ignored) {}
                                }
                                final boolean upcoming = isUpcoming;

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        final LinearLayout card = new LinearLayout(MainActivity.this);
                                        card.setOrientation(LinearLayout.VERTICAL);
                                        card.setPadding(dp(6), dp(6), dp(6), dp(8));
                                        card.setClickable(true);
                                        card.setFocusable(true);
                                        if (theme != null) {
                                            ThemeManager.styleInteractiveCard(MainActivity.this, card, theme.surfaceContainer, theme.onSurface, 12, 1, theme.outlineVariant);
                                        } else {
                                            card.setBackgroundDrawable(shape(surface, 12));
                                        }

                                        RoundedImageView cover = new RoundedImageView(MainActivity.this);
                                        cover.setCornerRadius(dp(8));
                                        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                        if (theme != null) {
                                            ThemeManager.styleSurfaceCard(MainActivity.this, cover, theme.surfaceContainerHighest, 8, 0, 0);
                                        } else {
                                            cover.setBackgroundDrawable(shape(0xff232b38, 8));
                                        }

                                        FrameLayout tile = new FrameLayout(MainActivity.this);
                                        tile.addView(cover, new FrameLayout.LayoutParams(-1, -1));

                                        TextView badge = badge(upcoming ? "🗓️ UPCOMING" : "▶ NEXT UP",
                                                upcoming ? (theme != null ? theme.secondaryContainer : 0xff1e3a5f) : (theme != null ? theme.primaryContainer : 0xff2a1a3e),
                                                upcoming ? (theme != null ? theme.onSecondaryContainer : 0xff60a5fa) : (theme != null ? theme.onPrimaryContainer : accent),
                                                0);
                                        badge.setTextSize(10);
                                        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
                                        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
                                        blp.setMargins(dp(6), dp(6), 0, 0);
                                        tile.addView(badge, blp);

                                        card.addView(tile, new LinearLayout.LayoutParams(dp(130), dp(175)));

                                        if (thumb.startsWith("http")) {
                                            loadPoster(cover, thumb);
                                        } else if (poster.startsWith("http")) {
                                            loadPoster(cover, poster);
                                        }

                                        LinearLayout info = new LinearLayout(MainActivity.this);
                                        info.setOrientation(LinearLayout.VERTICAL);
                                        info.setPadding(dp(2), dp(6), dp(2), dp(2));

                                        TextView tView = text(name, 12);
                                        tView.setTypeface(null, 1);
                                        tView.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
                                        tView.setMaxLines(1);
                                        tView.setEllipsize(TextUtils.TruncateAt.END);
                                        info.addView(tView);

                                        String subText = "S" + finalS + " E" + finalE + " • " + epName;
                                        TextView subView = text(subText, 11);
                                        subView.setTextColor(theme != null ? theme.primary : accent);
                                        subView.setSingleLine(true);
                                        subView.setEllipsize(TextUtils.TruncateAt.END);
                                        info.addView(subView);

                                        card.addView(info);

                                        View.OnClickListener clicker = new View.OnClickListener() {
                                            @Override
                                            public void onClick(View v) {
                                                selectedId = id;
                                                selectedType = "series";
                                                selectedName = name;
                                                selectedPoster = poster;
                                                selectedVideoId = nextId;
                                                selectedVideoLabel = "Season " + finalS + ", Episode " + finalE + " • " + epName;
                                                rememberTitle();
                                                render("Streams", true);
                                            }
                                        };
                                        card.setOnClickListener(clicker);
                                        tile.setOnClickListener(clicker);

                                        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(142), -2);
                                        cardLp.setMargins(0, dp(4), dp(10), dp(8));
                                        row.addView(card, cardLp);
                                    }
                                });
                            }
                        } catch (Exception ignored) {}
                    }
                }
            });
        }

        showCatalog("🗓️ Upcoming TV Series", CINEMETA + "catalog/series/top/year=2026.json", "series");
    }

    void showUpcomingMovies() {
        showCatalog("🗓️ Upcoming Movies", CINEMETA + "catalog/movie/top/year=2026.json", "movie");
    }

    void showAddonCatalogs(String type) {
        if (addons == null || addons.length() == 0) return;
        for (int i = 0; i < addons.length(); i++) {
            JSONObject addon = addons.optJSONObject(i);
            if (addon == null) continue;
            JSONObject manifest = addon.optJSONObject("manifest");
            if (manifest == null) continue;
            String addonName = manifest.optString("name", "Add-on");
            if (addonName.toLowerCase(Locale.US).contains("cinemeta")) continue;
            String transport = addon.optString("transportUrl", "");
            String base = transport.endsWith("manifest.json") ?
                    transport.substring(0, transport.length() - "manifest.json".length()) : transport;
            if (!base.endsWith("/")) base += "/";

            JSONArray catalogs = manifest.optJSONArray("catalogs");
            if (catalogs != null) {
                for (int c = 0; c < catalogs.length(); c++) {
                    JSONObject cat = catalogs.optJSONObject(c);
                    if (cat == null) continue;
                    String catType = cat.optString("type", "");
                    if (catType.equals(type)) {
                        String catId = cat.optString("id", "top");
                        String catName = cat.optString("name", catId);
                        String url = base + "catalog/" + enc(catType) + "/" + enc(catId) + ".json";
                        showCatalog(addonName + " • " + catName, url, type);
                    }
                }
            }
        }
    }

    void moviesView() {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        TextView heading = text("Movies", 20);
        heading.setTypeface(null, 1);
        actions.addView(heading, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button exit = btn("Exit", new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        exit.setTextSize(14);
        actions.addView(exit, new LinearLayout.LayoutParams(dp(100), dp(42)));
        add(actions);

        if (isLoggedIn()) {
            showContinueWatching("movie");
        }
        showUpcoming("movie");

        showCatalog("Popular Movies", CINEMETA + "catalog/movie/top.json", "movie");
        showCatalog("Action Movies", CINEMETA + "catalog/movie/top/genre=Action.json", "movie");
        showCatalog("Comedy Movies", CINEMETA + "catalog/movie/top/genre=Comedy.json", "movie");
        showCatalog("Sci-Fi & Fantasy", CINEMETA + "catalog/movie/top/genre=Science%20Fiction.json", "movie");
        showAddonCatalogs("movie");
    }

    void seriesView() {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        TextView heading = text("TV Series", 20);
        heading.setTypeface(null, 1);
        actions.addView(heading, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button exit = btn("Exit", new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        exit.setTextSize(14);
        actions.addView(exit, new LinearLayout.LayoutParams(dp(100), dp(42)));
        add(actions);

        if (isLoggedIn()) {
            showContinueWatching("series");
        }
        showUpcoming("series");

        showCatalog("Popular TV Series", CINEMETA + "catalog/series/top.json", "series");
        showCatalog("Drama Series", CINEMETA + "catalog/series/top/genre=Drama.json", "series");
        showCatalog("Comedy Series", CINEMETA + "catalog/series/top/genre=Comedy.json", "series");
        showCatalog("Action & Adventure", CINEMETA + "catalog/series/top/genre=Action.json", "series");
        showCatalog("Sci-Fi & Fantasy", CINEMETA + "catalog/series/top/genre=Sci-Fi%20%26%20Fantasy.json", "series");
        showCatalog("Crime & Mystery", CINEMETA + "catalog/series/top/genre=Crime.json", "series");
        showCatalog("Animation & Anime", CINEMETA + "catalog/series/top/genre=Animation.json", "series");
        showAddonCatalogs("series");
    }

    void showCatalog(final String title, final String url, final String type) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(4), 0, dp(8));

        TextView heading = text(title, 16);
        heading.setTypeface(null, 1);
        section.addView(heading);
        section.addView(text("Loading " + title + "…", 13));
        add(section);

        requestCatalog(url, type, section);
    }

    void requestCatalog(final String url, final String type, final LinearLayout section) {
        JSONObject cached = catalogMemory.get(url);
        if (cached != null) {
            displayCatalog(cached, type, section);
            return;
        }

        catalogWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject data = get(url);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            catalogMemory.put(url, data);
                            displayCatalog(data, type, section);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (section.getChildCount() > 1) section.removeViews(1, section.getChildCount() - 1);
                            section.addView(text("Failed to load catalog: " + e.getMessage(), 13));
                        }
                    });
                }
            }
        });
    }

    void displayCatalog(JSONObject j, String type, LinearLayout section) {
        if (section == null || section.getParent() == null) return;
        JSONArray metas = j.optJSONArray("metas");
        if (section.getChildCount() > 1) section.removeViews(1, section.getChildCount() - 1);
        if (metas == null || metas.length() == 0) {
            section.addView(text("No titles found.", 13));
            return;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.addView(row);

        for (int i = 0; i < metas.length() && i < 12; i++) {
            JSONObject meta = metas.optJSONObject(i);
            if (meta != null) showTitle(meta, type, row);
        }
        section.addView(scroll);
    }

    void showTitle(final JSONObject meta, final String fallback, LinearLayout row) {
        final String id = meta.optString("id", meta.optString("_id", ""));
        final String mt = meta.optString("type", fallback);
        final String name = meta.optString("name", meta.optString("title", id));
        if (id.isEmpty()) return;

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(8));
        card.setClickable(true);
        card.setFocusable(true);
        if (theme != null) {
            ThemeManager.styleInteractiveCard(this, card, theme.surfaceContainer, theme.onSurface, 12, 1, theme.outlineVariant);
        } else {
            card.setBackgroundDrawable(shape(surface, 12));
        }

        RoundedImageView cover = new RoundedImageView(this);
        cover.setCornerRadius(dp(8));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (theme != null) {
            ThemeManager.styleSurfaceCard(this, cover, theme.surfaceContainerHighest, 8, 0, 0);
        } else {
            cover.setBackgroundDrawable(shape(0xff232b38, 8));
        }

        FrameLayout tile = new FrameLayout(this);
        tile.addView(cover, new FrameLayout.LayoutParams(-1, -1));

        TextView label = text(name, 12);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        label.setMaxLines(3);
        label.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(label, new FrameLayout.LayoutParams(-1, -1));
        cover.setTag(label);

        card.addView(tile, new LinearLayout.LayoutParams(dp(130), dp(175)));

        String posterUrl = meta.optString("poster", "");
        if (posterUrl.startsWith("http")) {
            loadPoster(cover, posterUrl);
        }

        final Runnable openTitle = new Runnable() {
            @Override
            public void run() {
                selectedId = id;
                selectedType = mt;
                selectedName = name;
                selectedPoster = meta.optString("poster", "");
                selectedVideoId = "";
                selectedVideoLabel = "";
                rememberTitle();
                render("Streams", true);
            }
        };

        View.OnClickListener clickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTitle.run();
            }
        };
        card.setOnClickListener(clickListener);
        tile.setOnClickListener(clickListener);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(2), dp(6), dp(2), dp(2));

        TextView titleText = text(name, 12);
        titleText.setTypeface(null, 1);
        titleText.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        titleText.setMaxLines(2);
        titleText.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(titleText, new LinearLayout.LayoutParams(-1, dp(32)));

        String year = meta.optString("year", meta.optString("releaseInfo", ""));
        String typeLabel = "series".equals(mt) ? "TV Series" : "Movie";
        String metaLine = !year.isEmpty() ? (typeLabel + " • " + year) : typeLabel;
        TextView metaText = text(metaLine, 11);
        metaText.setTextColor(theme != null ? theme.onSurfaceVariant : muted);
        metaText.setSingleLine(true);
        metaText.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(metaText);

        card.addView(info, new LinearLayout.LayoutParams(dp(130), -2));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(142), -2);
        cardLp.setMargins(0, dp(4), dp(10), dp(8));
        row.addView(card, cardLp);
    }

    File getPosterCacheFile(String url) {
        try {
            File dir = new File(getCacheDir(), "nuvio_posters");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, "p_" + hashKey(url) + ".jpg");
        } catch (Exception e) {
            return null;
        }
    }

    Bitmap getPosterFromCache(String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap mem = memoryPosters.get(url);
        if (mem != null) return mem;

        try {
            File f = getPosterCacheFile(url);
            if (f != null && f.exists() && f.length() > 0) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                if (bmp != null) {
                    memoryPosters.put(url, bmp);
                    return bmp;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    void loadPoster(final ImageView view, final String url) {
        if (url == null || url.isEmpty() || !url.startsWith("http")) return;

        // Instant in-memory check (runs synchronously on UI thread - 0ms disk I/O)
        Bitmap memCached = memoryPosters.get(url);
        if (memCached != null) {
            view.setImageBitmap(memCached);
            View lbl = (View) view.getTag();
            if (lbl != null) lbl.setVisibility(View.GONE);
            return;
        }

        // Tag view with target URL to prevent stale recycling flickers
        view.setContentDescription(url);

        // Offload disk cache lookup & network fetch entirely to background thread pool
        imageWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // Check disk cache asynchronously off the UI thread
                    Bitmap diskBmp = getPosterFromCache(url);
                    if (diskBmp != null) {
                        final Bitmap bmp = diskBmp;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (url.equals(view.getContentDescription())) {
                                    view.setImageBitmap(bmp);
                                    View lbl = (View) view.getTag();
                                    if (lbl != null) lbl.setVisibility(View.GONE);
                                }
                            }
                        });
                        return;
                    }

                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    if (c instanceof HttpsURLConnection) {
                        ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(MainActivity.this));
                    }
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.6");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    in.close();
                    c.disconnect();

                    byte[] data = out.toByteArray();

                    // Persist to disk cache
                    File cacheFile = getPosterCacheFile(url);
                    if (cacheFile != null && data.length > 0) {
                        try {
                            FileOutputStream fos = new FileOutputStream(cacheFile);
                            fos.write(data);
                            fos.flush();
                            fos.close();
                        } catch (Exception ignored) {}
                    }

                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 2;
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);

                    if (bmp != null) {
                        memoryPosters.put(url, bmp);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (url.equals(view.getContentDescription())) {
                                    view.setImageBitmap(bmp);
                                    View lbl = (View) view.getTag();
                                    if (lbl != null) lbl.setVisibility(View.GONE);
                                }
                            }
                        });
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    Bitmap getCircularBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;
        int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setColor(0xffffffff);
        final Rect srcRect = new Rect(
                (bitmap.getWidth() - size) / 2,
                (bitmap.getHeight() - size) / 2,
                (bitmap.getWidth() + size) / 2,
                (bitmap.getHeight() + size) / 2);
        final Rect destRect = new Rect(0, 0, size, size);
        canvas.drawARGB(0, 0, 0, 0);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(bitmap, srcRect, destRect, paint);
        return output;
    }

    String hashKey(String s) {
        if (s == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(s.hashCode()));
        }
    }

    File getAvatarCacheFile(String url) {
        try {
            File dir = new File(getCacheDir(), "nuvio_avatars");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, "av_" + hashKey(url) + ".png");
        } catch (Exception e) {
            return null;
        }
    }

    Bitmap getAvatarFromCache(String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap mem = avatarMemory.get(url);
        if (mem != null) return mem;

        try {
            File f = getAvatarCacheFile(url);
            if (f != null && f.exists() && f.length() > 0) {
                Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bmp != null) {
                    avatarMemory.put(url, bmp);
                    return bmp;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    void loadAvatar(final ImageView view, final String url) {
        if (url == null || url.isEmpty() || !url.startsWith("http")) return;

        // Instant in-memory check (runs synchronously on UI thread - 0ms disk I/O)
        Bitmap memCached = avatarMemory.get(url);
        if (memCached != null) {
            view.setImageBitmap(memCached);
            View lbl = (View) view.getTag();
            if (lbl != null) lbl.setVisibility(View.GONE);
            return;
        }

        view.setContentDescription(url);

        imageWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap diskBmp = getAvatarFromCache(url);
                    if (diskBmp != null) {
                        final Bitmap bmp = diskBmp;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (url.equals(view.getContentDescription())) {
                                    view.setImageBitmap(bmp);
                                    View lbl = (View) view.getTag();
                                    if (lbl != null) lbl.setVisibility(View.GONE);
                                }
                            }
                        });
                        return;
                    }

                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    if (c instanceof HttpsURLConnection) {
                        ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(MainActivity.this));
                    }
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.6");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    if (c.getResponseCode() >= 400) {
                        c.disconnect();
                        return;
                    }
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    in.close();
                    c.disconnect();

                    byte[] data = out.toByteArray();
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length, opts);

                    if (raw != null) {
                        final Bitmap circular = getCircularBitmap(raw);
                        avatarMemory.put(url, circular);

                        try {
                            File f = getAvatarCacheFile(url);
                            if (f != null) {
                                FileOutputStream fos = new FileOutputStream(f);
                                circular.compress(Bitmap.CompressFormat.PNG, 100, fos);
                                fos.flush();
                                fos.close();
                            }
                        } catch (Exception ignored) {}

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (url.equals(view.getContentDescription())) {
                                    view.setImageBitmap(circular);
                                    View lbl = (View) view.getTag();
                                    if (lbl != null) lbl.setVisibility(View.GONE);
                                }
                            }
                        });
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    void loadStreams() {
        if ("series".equals(selectedType) && (selectedVideoId.isEmpty() || !selectedVideoId.contains(":"))) {
            chooseSeriesEpisode();
            return;
        }

        final String resourceId = "series".equals(selectedType) ? selectedVideoId : selectedId;
        final String key = selectedType + "|" + resourceId;
        visibleStreamTitle = key;
        visibleSources.clear();

        if (selectedVideoLabel.isEmpty() && "series".equals(selectedType) && selectedVideoId.contains(":")) {
            String[] parts = selectedVideoId.split(":");
            if (parts.length >= 3) {
                selectedVideoLabel = "Season " + parts[1] + ", Episode " + parts[2];
            }
        }

        // 1. Information Banner Card (Poster + Metadata + Synopsis)
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.HORIZONTAL);
        ThemeManager.styleSurfaceCard(this, infoCard, theme.surfaceContainer, 14, 1, theme.outlineVariant);
        infoCard.setPadding(dp(12), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.setMargins(0, 0, 0, dp(10));
        infoCard.setLayoutParams(infoLp);

        final RoundedImageView posterIv = new RoundedImageView(this);
        posterIv.setCornerRadius(dp(10));
        posterIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ThemeManager.styleSurfaceCard(this, posterIv, theme.surfaceContainerHighest, 10, 1, theme.outlineVariant);
        infoCard.addView(posterIv, new LinearLayout.LayoutParams(dp(75), dp(110)));
        if (selectedPoster.startsWith("http")) {
            loadPoster(posterIv, selectedPoster);
        }

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(14), dp(2), 0, dp(2));

        final TextView infoTitle = text((selectedName.isEmpty() || "Untitled".equals(selectedName)) ? selectedId : selectedName, 17);
        infoTitle.setTypeface(null, 1);
        infoTitle.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        details.addView(infoTitle);

        final TextView metaText = text("Loading details from Cinemeta…", 12);
        metaText.setTextColor(accent);
        metaText.setTypeface(null, 1);
        metaText.setPadding(0, dp(2), 0, dp(2));
        details.addView(metaText);

        final TextView descView = text("", 12);
        descView.setTextColor(muted);
        descView.setMaxLines(3);
        descView.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(descView);

        final Button libBtn = btn(NuvioClient.getInstance(this).isInLibrary(selectedId) ? "✓ In Library" : "＋ Add to Library", null);
        libBtn.setTextSize(12);
        boolean inLib = NuvioClient.getInstance(this).isInLibrary(selectedId);
        if (theme != null) {
            if (inLib) {
                ThemeManager.stylePillButton(this, libBtn, theme.primary, theme.onPrimary, 0, 0, true);
            } else {
                ThemeManager.stylePillButton(this, libBtn, theme.surfaceContainerHighest, theme.onSurface, 1, theme.outlineVariant, true);
            }
        }
        libBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLibraryItem(selectedId, selectedType, selectedName, selectedPoster, libBtn);
            }
        });
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-2, dp(34));
        lbp.setMargins(0, dp(6), 0, 0);
        details.addView(libBtn, lbp);

        if (!selectedVideoLabel.isEmpty()) {
            LinearLayout epRow = new LinearLayout(this);
            epRow.setOrientation(LinearLayout.HORIZONTAL);
            epRow.setGravity(Gravity.CENTER_VERTICAL);
            epRow.setPadding(0, dp(6), 0, 0);

            TextView epLbl = text("Episode: " + selectedVideoLabel, 13);
            epLbl.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
            epLbl.setTypeface(null, 1);
            epRow.addView(epLbl, new LinearLayout.LayoutParams(0, -2, 1));

            Button changeEp = btn("Change Episode", new Runnable() {
                @Override
                public void run() {
                    selectedVideoId = "";
                    selectedVideoLabel = "";
                    render("Streams", false);
                }
            });
            changeEp.setTextSize(11);
            changeEp.setBackgroundDrawable(shape(0xff1f2d3d, 6));
            epRow.addView(changeEp, new LinearLayout.LayoutParams(-2, dp(32)));
            details.addView(epRow);
        }

        infoCard.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        add(infoCard);

        // Background Cinemeta fetch for movie/show metadata
        apiWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject m = get(CINEMETA + "meta/" + selectedType + "/" + enc(selectedId) + ".json");
                    final JSONObject meta = m != null ? m.optJSONObject("meta") : null;
                    if (meta != null) {
                        final String realTitle = meta.optString("name", "");
                        final String pUrl = meta.optString("poster", "");
                        final String year = meta.optString("year", "");
                        final String rating = meta.optString("imdbRating", "");
                        final String runtime = meta.optString("runtime", "");
                        final String desc = meta.optString("description", "");

                        String genre = "";
                        JSONArray gArr = meta.optJSONArray("genres");
                        if (gArr != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int gi = 0; gi < Math.min(3, gArr.length()); gi++) {
                                if (sb.length() > 0) sb.append(" • ");
                                sb.append(gArr.optString(gi));
                            }
                            genre = sb.toString();
                        }

                        StringBuilder badgeSb = new StringBuilder();
                        if (!year.isEmpty()) badgeSb.append(year);
                        if (!rating.isEmpty()) {
                            if (badgeSb.length() > 0) badgeSb.append("   ⭐ ");
                            else badgeSb.append("⭐ ");
                            badgeSb.append(rating);
                        }
                        if (!runtime.isEmpty()) {
                            if (badgeSb.length() > 0) badgeSb.append("   ⏱ ");
                            else badgeSb.append("⏱ ");
                            badgeSb.append(runtime);
                        }
                        if (!genre.isEmpty()) {
                            if (badgeSb.length() > 0) badgeSb.append("   •   ").append(genre);
                            else badgeSb.append(genre);
                        }
                        final String metaLine = badgeSb.toString();

                        if (!realTitle.isEmpty() && (selectedName.isEmpty() || "Untitled".equals(selectedName) || selectedName.equals(selectedId))) {
                            selectedName = realTitle;
                            rememberTitle();
                        }
                        if (selectedPoster.isEmpty() && pUrl.startsWith("http")) {
                            selectedPoster = pUrl;
                            rememberTitle();
                        }

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!realTitle.isEmpty()) {
                                    infoTitle.setText(realTitle);
                                    if (streamTitleView != null) streamTitleView.setText(realTitle);
                                }
                                if (!metaLine.isEmpty()) {
                                    metaText.setText(metaLine);
                                    metaText.setVisibility(View.VISIBLE);
                                } else {
                                    metaText.setVisibility(View.GONE);
                                }
                                if (!desc.isEmpty()) {
                                    descView.setText(desc);
                                    descView.setVisibility(View.VISIBLE);
                                } else {
                                    descView.setVisibility(View.GONE);
                                }
                                if (pUrl.startsWith("http")) loadPoster(posterIv, pUrl);
                            }
                        });
                    }
                } catch (Exception ignored) {}
            }
        });

        // 3. Quality Filter Bar
        LinearLayout filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        qualityButtons.clear();

        for (final String q : new String[]{"All", "H.264 (HW)", "720p", "480p", "1080p"}) {
            Button btnQ = btn(q, new Runnable() {
                @Override
                public void run() {
                    sourceFilter = q;
                    updateQualityButtons();
                    drawSources();
                }
            });
            btnQ.setTextSize(13);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
            lp.setMargins(dp(2), 0, dp(2), dp(4));
            qualityButtons.put(q, btnQ);
            filterBar.addView(btnQ, lp);
        }
        add(filterBar);
        updateQualityButtons();

        sourceStatus = text("Querying streaming add-ons…", 13);
        add(sourceStatus);

        sourcePanel = new LinearLayout(this);
        sourcePanel.setOrientation(LinearLayout.VERTICAL);
        add(sourcePanel);

        fetchStreamsForResource(selectedType, resourceId);
    }

    void updateQualityButtons() {
        for (String q : qualityButtons.keySet()) {
            Button b = qualityButtons.get(q);
            if (b != null) {
                boolean active = q.equals(sourceFilter);
                ThemeManager.styleFilterChip(this, b, theme, q, active);
            }
        }
    }

    void chooseSeriesEpisode() {
        body.removeAllViews();
        TextView heading = text("Select Episode • " + ((selectedName.isEmpty() || "Untitled".equals(selectedName)) ? selectedId : selectedName), 18);
        heading.setTypeface(null, 1);
        add(heading);

        add(text("Loading series episodes from Cinemeta…", 13));

        apiWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String metaUrl = CINEMETA + "meta/series/" + enc(selectedId) + ".json";
                    final JSONObject reply = get(metaUrl);
                    final JSONObject meta = reply.optJSONObject("meta");
                    final JSONArray videos = meta != null ? meta.optJSONArray("videos") : null;
                    if (meta != null) {
                        String realName = meta.optString("name", "");
                        if (!realName.isEmpty() && (selectedName.isEmpty() || "Untitled".equals(selectedName) || selectedName.equals(selectedId))) {
                            selectedName = realName;
                            rememberTitle();
                        }
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!page.equals("Streams") || videos == null || videos.length() == 0) return;
                            if (streamTitleView != null && !selectedName.isEmpty()) {
                                streamTitleView.setText(selectedName);
                            }
                            body.removeAllViews();

                            // 1. Group episodes by season
                            final TreeMap<Integer, ArrayList<JSONObject>> seasonsMap = new TreeMap<Integer, ArrayList<JSONObject>>();
                            for (int i = 0; i < videos.length(); i++) {
                                JSONObject v = videos.optJSONObject(i);
                                if (v == null) continue;
                                int s = v.optInt("season", 1);
                                if (!seasonsMap.containsKey(s)) {
                                    seasonsMap.put(s, new ArrayList<JSONObject>());
                                }
                                seasonsMap.get(s).add(v);
                            }

                            final ArrayList<Integer> seasonList = new ArrayList<Integer>();
                            for (Integer s : seasonsMap.keySet()) {
                                if (s > 0) seasonList.add(s);
                            }
                            if (seasonsMap.containsKey(0)) {
                                seasonList.add(0); // specials at end
                            }
                            if (seasonList.isEmpty() && seasonsMap.containsKey(0)) {
                                seasonList.add(0);
                            }
                            if (seasonList.isEmpty()) {
                                add(text("No episodes found for this series.", 14));
                                return;
                            }

                            int defaultSeason = seasonList.get(0);
                            if (!selectedVideoLabel.isEmpty() && selectedVideoLabel.startsWith("Season ")) {
                                try {
                                    int spIdx = selectedVideoLabel.indexOf(",");
                                    if (spIdx != -1) {
                                        String sNumStr = selectedVideoLabel.substring("Season ".length(), spIdx).trim();
                                        int parsedS = Integer.parseInt(sNumStr);
                                        if (seasonsMap.containsKey(parsedS)) {
                                            defaultSeason = parsedS;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                            final int[] activeSeason = new int[]{ defaultSeason };

                            // Show Overview Card (Poster + Metadata + Description)
                            LinearLayout infoCard = new LinearLayout(MainActivity.this);
                            infoCard.setOrientation(LinearLayout.HORIZONTAL);
                            ThemeManager.styleSurfaceCard(MainActivity.this, infoCard, theme != null ? theme.surfaceContainer : surface, 10, 1, theme != null ? theme.outlineVariant : 0);
                            infoCard.setPadding(dp(12), dp(10), dp(14), dp(10));
                            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
                            infoLp.setMargins(0, 0, 0, dp(10));
                            infoCard.setLayoutParams(infoLp);

                            ImageView posterIv = new ImageView(MainActivity.this);
                            posterIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            ThemeManager.styleSurfaceCard(MainActivity.this, posterIv, theme != null ? theme.surfaceContainerHighest : 0xff0f172a, 8, 1, theme != null ? theme.outlineVariant : 0);
                            infoCard.addView(posterIv, new LinearLayout.LayoutParams(dp(75), dp(110)));
                            String pUrl = meta != null ? meta.optString("poster", selectedPoster) : selectedPoster;
                            if (pUrl.startsWith("http")) loadPoster(posterIv, pUrl);

                            LinearLayout details = new LinearLayout(MainActivity.this);
                            details.setOrientation(LinearLayout.VERTICAL);
                            details.setPadding(dp(14), dp(2), 0, dp(2));

                            TextView infoTitle = text(selectedName, 17);
                            infoTitle.setTypeface(null, 1);
                            infoTitle.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
                            details.addView(infoTitle);

                            String year = meta != null ? meta.optString("year", "") : "";
                            String rating = meta != null ? meta.optString("imdbRating", "") : "";
                            String genre = "";
                            if (meta != null && meta.optJSONArray("genres") != null) {
                                JSONArray gArr = meta.optJSONArray("genres");
                                StringBuilder sb = new StringBuilder();
                                for (int gi = 0; gi < Math.min(3, gArr.length()); gi++) {
                                    if (sb.length() > 0) sb.append(" • ");
                                    sb.append(gArr.optString(gi));
                                }
                                genre = sb.toString();
                            }

                            StringBuilder badgeSb = new StringBuilder();
                            if (!year.isEmpty()) badgeSb.append(year);
                            if (!rating.isEmpty()) {
                                if (badgeSb.length() > 0) badgeSb.append("   ⭐ ");
                                else badgeSb.append("⭐ ");
                                badgeSb.append(rating);
                            }
                            if (!genre.isEmpty()) {
                                if (badgeSb.length() > 0) badgeSb.append("   •   ").append(genre);
                                else badgeSb.append(genre);
                            }
                            if (seasonList.size() > 0) {
                                badgeSb.append("   •   ").append(seasonList.size()).append(" Season").append(seasonList.size() > 1 ? "s" : "");
                            }

                            if (badgeSb.length() > 0) {
                                TextView metaText = text(badgeSb.toString(), 12);
                                metaText.setTextColor(accent);
                                metaText.setTypeface(null, 1);
                                details.addView(metaText);
                            }

                            String desc = meta != null ? meta.optString("description", "") : "";
                            if (!desc.isEmpty()) {
                                TextView descView = text(desc, 12);
                                descView.setTextColor(muted);
                                descView.setMaxLines(3);
                                descView.setEllipsize(TextUtils.TruncateAt.END);
                                details.addView(descView);
                            }

                            final Button libBtn = btn(NuvioClient.getInstance(MainActivity.this).isInLibrary(selectedId) ? "✓ In Library" : "＋ Add to Library", null);
                            libBtn.setTextSize(12);
                            boolean inLib = NuvioClient.getInstance(MainActivity.this).isInLibrary(selectedId);
                            if (theme != null) {
                                if (inLib) {
                                    ThemeManager.stylePillButton(MainActivity.this, libBtn, theme.primary, theme.onPrimary, 0, 0, true);
                                } else {
                                    ThemeManager.stylePillButton(MainActivity.this, libBtn, theme.surfaceContainerHighest, theme.onSurface, 1, theme.outlineVariant, true);
                                }
                            }
                            libBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    toggleLibraryItem(selectedId, selectedType, selectedName, selectedPoster, libBtn);
                                }
                            });
                            LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-2, dp(34));
                            lbp.setMargins(0, dp(6), 0, 0);
                            details.addView(libBtn, lbp);

                            infoCard.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
                            add(infoCard);

                            // 4. Season Selector Tabs Bar
                            TextView seasonHeading = text("Select Season", 15);
                            seasonHeading.setTypeface(null, 1);
                            seasonHeading.setPadding(dp(4), dp(4), dp(4), dp(4));
                            add(seasonHeading);

                            HorizontalScrollView seasonScroll = new HorizontalScrollView(MainActivity.this);
                            seasonScroll.setHorizontalScrollBarEnabled(false);
                            final LinearLayout seasonBar = new LinearLayout(MainActivity.this);
                            seasonBar.setOrientation(LinearLayout.HORIZONTAL);
                            seasonBar.setPadding(0, dp(4), 0, dp(10));
                            seasonScroll.addView(seasonBar);
                            add(seasonScroll);

                            // 5. Episode List Section
                            final TextView epSectionHeader = text("", 15);
                            epSectionHeader.setTypeface(null, 1);
                            epSectionHeader.setPadding(dp(4), dp(4), dp(4), dp(6));
                            add(epSectionHeader);

                            final LinearLayout epContainer = new LinearLayout(MainActivity.this);
                            epContainer.setOrientation(LinearLayout.VERTICAL);
                            add(epContainer);

                            final HashMap<Integer, Button> seasonButtons = new HashMap<Integer, Button>();

                            final Runnable renderEpisodes = new Runnable() {
                                @Override
                                public void run() {
                                    epContainer.removeAllViews();
                                    int s = activeSeason[0];
                                    String sName = (s == 0) ? "Specials" : ("Season " + s);
                                    int epCount = seasonsMap.containsKey(s) ? seasonsMap.get(s).size() : 0;
                                    epSectionHeader.setText(sName + " • " + epCount + " Episode" + (epCount != 1 ? "s" : ""));

                                    for (Integer sNum : seasonButtons.keySet()) {
                                        Button b = seasonButtons.get(sNum);
                                        if (b == null) continue;
                                        boolean sel = (sNum == s);
                                        if (theme != null) {
                                            ThemeManager.styleFilterChip(MainActivity.this, b, theme, b.getText().toString(), sel);
                                        } else {
                                            b.setBackgroundDrawable(shape(sel ? accent : surface, 8));
                                            b.setTextColor(Color.WHITE);
                                            b.setTypeface(null, sel ? 1 : 0);
                                        }
                                    }

                                    ArrayList<JSONObject> eps = seasonsMap.get(s);
                                    if (eps == null || eps.isEmpty()) {
                                        epContainer.addView(text("No episodes found for this season.", 13));
                                        return;
                                    }

                                    Collections.sort(eps, new Comparator<JSONObject>() {
                                        @Override
                                        public int compare(JSONObject a, JSONObject b) {
                                            return Integer.compare(a.optInt("episode", 1), b.optInt("episode", 1));
                                        }
                                    });

                                    LinearLayout currentRow = null;
                                    for (int i = 0; i < eps.size(); i++) {
                                        final JSONObject epObj = eps.get(i);
                                        if (epObj == null) continue;
                                        final String epId = epObj.optString("id", "");
                                        final int epNum = epObj.optInt("episode", i + 1);
                                        final String epTitle = epObj.optString("title", epObj.optString("name", "Episode " + epNum));
                                        final String epOverview = epObj.optString("overview", epObj.optString("description", ""));
                                        final String epRelease = epObj.optString("released", "");
                                        final String fullLabel = "Season " + s + ", Episode " + epNum + " • " + epTitle;

                                        if (i % 2 == 0) {
                                            currentRow = new LinearLayout(MainActivity.this);
                                            currentRow.setOrientation(LinearLayout.HORIZONTAL);
                                            currentRow.setBaselineAligned(false);
                                            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
                                            rLp.setMargins(0, dp(4), 0, dp(4));
                                            currentRow.setLayoutParams(rLp);
                                            epContainer.addView(currentRow);
                                        }

                                        LinearLayout card = new LinearLayout(MainActivity.this);
                                        card.setOrientation(LinearLayout.HORIZONTAL);
                                        card.setGravity(Gravity.CENTER_VERTICAL);
                                        card.setClickable(true);
                                        card.setFocusable(true);
                                        card.setMinimumHeight(dp(84));
                                        card.setPadding(dp(12), dp(10), dp(12), dp(10));
                                        if (theme != null) {
                                            ThemeManager.styleInteractiveCard(MainActivity.this, card, theme.surfaceContainer, theme.onSurface, 12, 1, theme.outlineVariant);
                                        } else {
                                            card.setBackgroundDrawable(shape(surface, 12));
                                        }

                                        TextView badge = text("E" + (epNum < 10 ? "0" + epNum : String.valueOf(epNum)), 13);
                                        badge.setTypeface(null, 1);
                                        badge.setGravity(Gravity.CENTER);
                                        badge.setTextColor(theme != null ? theme.primary : accent);
                                        if (theme != null) {
                                            ThemeManager.styleSurfaceCard(MainActivity.this, badge, theme.surfaceContainerHighest, 10, 0, 0);
                                        } else {
                                            badge.setBackgroundDrawable(shape(0xff251a38, 10));
                                        }
                                        card.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(44)));

                                        LinearLayout info = new LinearLayout(MainActivity.this);
                                        info.setOrientation(LinearLayout.VERTICAL);
                                        info.setPadding(dp(12), 0, dp(8), 0);

                                        TextView tView = text(epTitle, 13);
                                        tView.setTypeface(null, 1);
                                        tView.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
                                        tView.setSingleLine(true);
                                        tView.setEllipsize(TextUtils.TruncateAt.END);
                                        info.addView(tView);

                                        String relDate = !epRelease.isEmpty() ? (epRelease.length() >= 10 ? epRelease.substring(0, 10) : epRelease) : "";
                                        String descText = !epOverview.isEmpty() ? epOverview : ("Season " + s + ", Episode " + epNum);
                                        if (!relDate.isEmpty()) {
                                            descText = relDate + " • " + descText;
                                        }

                                        TextView sView = text(descText, 11);
                                        sView.setTextColor(theme != null ? theme.onSurfaceVariant : muted);
                                        sView.setMaxLines(3);
                                        sView.setEllipsize(TextUtils.TruncateAt.END);
                                        sView.setLineSpacing(dp(1), 1.0f);
                                        info.addView(sView);

                                        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                                        TextView playIco = text("▶", 12);
                                        playIco.setGravity(Gravity.CENTER);
                                        if (theme != null) {
                                            playIco.setTextColor(theme.onPrimary);
                                            ThemeManager.styleSurfaceCard(MainActivity.this, playIco, theme.primary, 14, 0, 0);
                                        } else {
                                            playIco.setTextColor(Color.WHITE);
                                            playIco.setBackgroundDrawable(shape(accent, 14));
                                        }
                                        card.addView(playIco, new LinearLayout.LayoutParams(dp(28), dp(28)));

                                        card.setOnClickListener(new View.OnClickListener() {
                                            @Override
                                            public void onClick(View v) {
                                                selectedVideoId = epId;
                                                selectedVideoLabel = fullLabel;
                                                rememberTitle();
                                                render("Streams", false);
                                            }
                                        });

                                        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, -1, 1);
                                        cardLp.setMargins(dp(4), 0, dp(4), 0);
                                        currentRow.addView(card, cardLp);
                                    }

                                    if (eps.size() % 2 != 0 && currentRow != null) {
                                        View spacer = new View(MainActivity.this);
                                        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, dp(84), 1);
                                        spLp.setMargins(dp(4), 0, dp(4), 0);
                                        currentRow.addView(spacer, spLp);
                                    }
                                }
                            };

                            for (int si = 0; si < seasonList.size(); si++) {
                                final int sNum = seasonList.get(si);
                                String sLabel = (sNum == 0) ? "Specials" : ("Season " + sNum);
                                Button sBtn = btn(sLabel, new Runnable() {
                                    @Override
                                    public void run() {
                                        activeSeason[0] = sNum;
                                        renderEpisodes.run();
                                    }
                                });
                                sBtn.setTextSize(13);
                                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, dp(38));
                                bLp.setMargins(dp(2), 0, dp(6), 0);
                                seasonButtons.put(sNum, sBtn);
                                seasonBar.addView(sBtn, bLp);
                            }

                            renderEpisodes.run();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            add(text("Failed to load episodes: " + e.getMessage(), 14));
                        }
                    });
                }
            }
        });
    }

    void fetchStreamsForResource(final String type, final String id) {
        // Query Cinemeta and installed Add-ons
        ArrayList<String> streamEndpoints = new ArrayList<>();
        ArrayList<String> providerNames = new ArrayList<>();

        if (addons.length() == 0) {
            // Default public torrentio fallback
            streamEndpoints.add("https://torrentio.strem.fun/stream/" + enc(type) + "/" + enc(id) + ".json");
            providerNames.add("Torrentio (Default)");
        } else {
            for (int i = 0; i < addons.length(); i++) {
                JSONObject a = addons.optJSONObject(i);
                if (a == null) continue;
                String transport = a.optString("transportUrl", "");
                if (transport.isEmpty()) continue;
                String base = transport.endsWith("manifest.json") ?
                        transport.substring(0, transport.length() - "manifest.json".length()) : transport;
                if (!base.endsWith("/")) base += "/";
                streamEndpoints.add(base + "stream/" + enc(type) + "/" + enc(id) + ".json");
                providerNames.add(a.optString("name", "Add-on " + (i + 1)));
            }
        }

        sourceRequests = streamEndpoints.size();

        for (int i = 0; i < streamEndpoints.size(); i++) {
            final String url = streamEndpoints.get(i);
            final String provider = providerNames.get(i);

            apiWorkers.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final JSONObject resp = get(url);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                visibleSources.put(provider, resp);
                                if (sourceRequests > 0) sourceRequests--;
                                drawSources();
                            }
                        });
                    } catch (Exception e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (sourceRequests > 0) sourceRequests--;
                                drawSources();
                            }
                        });
                    }
                }
            });
        }
    }

    TextView badge(CharSequence text, int bgColor, int textColor, int strokeColor) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTypeface(null, 1);
        t.setTextColor(textColor);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(bgColor);
        d.setCornerRadius(dp(8));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        t.setBackgroundDrawable(d);
        t.setPadding(dp(7), dp(2), dp(7), dp(2));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    void addBadge(LinearLayout row, View b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(24));
        lp.setMargins(0, 0, dp(6), 0);
        row.addView(b, lp);
    }

    void drawSources() {
        if (sourcePanel == null || !page.equals("Streams")) return;
        sourcePanel.removeAllViews();

        if (sourceStatus != null) {
            sourceStatus.setText(sourceRequests > 0 ?
                    "Checking streaming add-ons (" + sourceRequests + " remaining)…" : "Available Streams:");
        }

        int count = 0;
        for (String provider : visibleSources.keySet()) {
            JSONObject resp = visibleSources.get(provider);
            if (resp == null) continue;
            JSONArray streams = resp.optJSONArray("streams");
            if (streams == null) continue;

            for (int i = 0; i < streams.length(); i++) {
                final JSONObject s = streams.optJSONObject(i);
                if (s == null) continue;

                String rawTitle = s.optString("title", s.optString("name", "Stream"));
                String quality = parseQuality(rawTitle);

                String codec = parseCodec(rawTitle);
                boolean tenBit = is10Bit(rawTitle);

                if (!"All".equals(sourceFilter)) {
                    if ("H.264 (HW)".equals(sourceFilter) && (!"H.264".equals(codec) || tenBit)) continue;
                    if ("1080p".equals(sourceFilter) && !quality.contains("1080p")) continue;
                    if ("720p".equals(sourceFilter) && !quality.contains("720p")) continue;
                    if ("480p".equals(sourceFilter) && !quality.contains("480p") && !quality.contains("SD")) continue;
                }

                count++;
                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(dp(12), dp(9), dp(12), dp(9));
                ThemeManager.styleStreamCard(this, item, theme, false);

                // Top Badges Scroll Row
                HorizontalScrollView badgeScroll = new HorizontalScrollView(this);
                badgeScroll.setHorizontalScrollBarEnabled(false);
                LinearLayout badgesRow = new LinearLayout(this);
                badgesRow.setOrientation(LinearLayout.HORIZONTAL);
                badgesRow.setGravity(Gravity.CENTER_VERTICAL);
                badgesRow.setPadding(0, 0, 0, dp(6));

                // 0. Source Type Badge (Direct, Debrid-cached, or Needs account)
                int srcType = getStreamSourceType(s);
                if (srcType == SOURCE_TYPE_DIRECT) {
                    addBadge(badgesRow, badge("🟢 Direct", 0xff15803d, Color.WHITE, 0));
                } else if (srcType == SOURCE_TYPE_DEBRID_CACHED) {
                    addBadge(badgesRow, badge("⚡ Debrid-cached", 0xff7c3aed, Color.WHITE, 0));
                } else {
                    addBadge(badgesRow, badge("🔒 Needs account", 0xffb91c1c, Color.WHITE, 0));
                }

                // 1. Quality Badge
                if (quality.contains("4K")) {
                    addBadge(badgesRow, badge("4K UHD", 0xff9333ea, Color.WHITE, 0));
                } else if (quality.contains("1080p")) {
                    addBadge(badgesRow, badge("1080p FHD", 0xff7c3aed, Color.WHITE, 0));
                } else if (quality.contains("720p")) {
                    addBadge(badgesRow, badge("720p HD", 0xff6366f1, Color.WHITE, 0));
                } else {
                    addBadge(badgesRow, badge("480p / SD", 0xff64748b, Color.WHITE, 0));
                }

                // 2. Codec Badge & KitKat Compatibility
                if ("H.264".equals(codec) && !tenBit) {
                    addBadge(badgesRow, badge("✓ H.264 (HW PLAYABLE)", 0xff16a34a, Color.WHITE, 0));
                    if (quality.contains("720p")) {
                        addBadge(badgesRow, badge("⭐ BEST FOR GEN5W", 0xffa855f7, Color.WHITE, 0));
                    } else if (quality.contains("480p") || quality.contains("SD")) {
                        addBadge(badgesRow, badge("⭐ EASY PLAY", 0xff0ea5e9, Color.WHITE, 0));
                    }
                } else if ("HEVC".equals(codec)) {
                    addBadge(badgesRow, badge("⚠️ HEVC (No KitKat Decoder)", 0xffdc2626, Color.WHITE, 0));
                } else if (tenBit) {
                    addBadge(badgesRow, badge("⚠️ 10-Bit Color (Unsupported)", 0xffdc2626, Color.WHITE, 0));
                } else if (!codec.isEmpty()) {
                    addBadge(badgesRow, badge(codec, 0xff64748b, Color.WHITE, 0));
                }

                // 3. Seeders Badge
                String seeds = parseSeeds(rawTitle);
                if (!seeds.isEmpty()) {
                    addBadge(badgesRow, badge("🌱 " + seeds + " Seeds", 0xffa855f7, Color.WHITE, 0));
                }

                // 4. File Size Badge
                String size = parseSize(s, rawTitle);
                if (!size.isEmpty()) {
                    addBadge(badgesRow, badge("💾 " + size, 0xff2c3e50, 0xffffffff, 0xff7f8c8d));
                }

                // 5. Audio Badge
                String audio = parseAudio(rawTitle);
                if (!audio.isEmpty()) {
                    addBadge(badgesRow, badge("🔊 " + audio, 0xff34495e, 0xffecf0f1, 0));
                }

                // 6. HDR / Dolby Vision
                String lower = rawTitle.toLowerCase(Locale.US);
                if (lower.contains("hdr")) {
                    addBadge(badgesRow, badge("HDR", 0xfff1c40f, 0xff000000, 0));
                }
                if (lower.contains("dolby vision") || lower.contains("dovi")) {
                    addBadge(badgesRow, badge("Dolby Vision", 0xff8e44ad, 0xffffffff, 0));
                }

                // 7. Language & Flag Badges (Using locally bundled Twemoji PNGs)
                String flags = FlagText.indicators(rawTitle);
                if (!flags.isEmpty()) {
                    TextView flagView = badge(FlagText.decorate(this, flags), 0xff1e272e, 0xffffffff, 0xff485460);
                    addBadge(badgesRow, flagView);
                }

                badgeScroll.addView(badgesRow);
                item.addView(badgeScroll);

                // Source details (Addon name + release name)
                LinearLayout contentRow = new LinearLayout(this);
                contentRow.setOrientation(LinearLayout.HORIZONTAL);
                contentRow.setGravity(Gravity.CENTER_VERTICAL);

                LinearLayout detailsCol = new LinearLayout(this);
                detailsCol.setOrientation(LinearLayout.VERTICAL);

                TextView prov = text(provider, 13);
                prov.setTypeface(null, 1);
                prov.setTextColor(accent);
                detailsCol.addView(prov);

                String cleanName = cleanStreamTitle(rawTitle);
                TextView nameView = text(cleanName, 13);
                nameView.setMaxLines(2);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                nameView.setTextColor(muted);
                detailsCol.addView(nameView);

                contentRow.addView(detailsCol, new LinearLayout.LayoutParams(0, -2, 1));

                final int finalSrcType = srcType;
                String btnLabel = (finalSrcType == SOURCE_TYPE_NEEDS_ACCOUNT) ? "🔒 Needs Debrid" : "▶ Play Stream";
                Button playBtn = btn(btnLabel, new Runnable() {
                    @Override
                    public void run() {
                        onStreamClicked(s);
                    }
                });
                playBtn.setTextSize(14);
                if (theme != null) {
                    if (finalSrcType == SOURCE_TYPE_NEEDS_ACCOUNT) {
                        ThemeManager.stylePillButton(this, playBtn, theme.surfaceContainerHighest, theme.onSurfaceVariant, 1, theme.outlineVariant, true);
                    } else {
                        ThemeManager.stylePillButton(this, playBtn, theme.primary, theme.onPrimary, 0, 0, true);
                    }
                } else {
                    playBtn.setTypeface(null, 1);
                    playBtn.setBackgroundDrawable(shape(finalSrcType == SOURCE_TYPE_NEEDS_ACCOUNT ? 0xff334155 : accent, 8));
                    playBtn.setTextColor(Color.WHITE);
                }
                contentRow.addView(playBtn, new LinearLayout.LayoutParams(dp(140), dp(44)));

                item.addView(contentRow);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, dp(4), 0, dp(6));
                sourcePanel.addView(item, lp);
            }
        }

        if (count == 0 && sourceRequests == 0) {
            sourcePanel.addView(text("No streams matched your quality filter.", 14));
        }
    }

    String cleanStreamTitle(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String line = raw;
        int nl = raw.indexOf('\n');
        if (nl > 0) {
            line = raw.substring(0, nl).trim();
        }
        // Remove Regional Indicator flag characters which Android 4.4 cannot render natively
        line = line.replaceAll("[\uD83C][\uDDE6-\uDDFF]", "");
        // Remove common Torrentio metadata glyphs
        line = line.replaceAll("[👤💾⚙🌱\u2699\uFE0F]", "");
        // Collapse repeating or orphan slashes left behind by extracted flags (e.g. / / / /)
        line = line.replaceAll("\\s*/(?:\\s*/)+\\s*", " ");
        // Remove trailing or leading slashes
        line = line.replaceAll("^\\s*/+\\s*", "").replaceAll("\\s*/+\\s*$", "");
        line = line.replaceAll("\\s+", " ").trim();
        return line.isEmpty() ? raw.replace('\n', ' ').trim() : line;
    }

    String parseQuality(String text) {
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("4k") || lower.contains("2160p") || lower.contains("uhd")) return "4K UHD";
        if (lower.contains("1080p") || lower.contains("fhd")) return "1080p FHD";
        if (lower.contains("720p") || (lower.contains("hd") && !lower.contains("hdr"))) return "720p HD";
        if (lower.contains("480p") || lower.contains("360p") || lower.contains("dvd") || lower.contains("sd")) return "480p / SD";
        return "480p / SD";
    }

    String parseCodec(String text) {
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("hevc") || lower.contains("x265") || lower.contains("h.265") || lower.contains("h265")) return "HEVC";
        if (lower.contains("h.264") || lower.contains("x264") || lower.contains("avc") || lower.contains("h264")) return "H.264";
        if (lower.contains("av1")) return "AV1";
        return "";
    }

    boolean is10Bit(String text) {
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("10bit") || lower.contains("10-bit") || lower.contains("hi10") || lower.contains("hi10p");
    }

    String parseSeeds(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:👤|seeds?:?\\s*|🌱\\s*)([0-9]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return m.group(1);
        return "";
    }

    String parseSize(JSONObject stream, String text) {
        long bytes = stream.optLong("fileSize", 0);
        JSONObject hints = stream.optJSONObject("behaviorHints");
        if (bytes <= 0 && hints != null) bytes = hints.optLong("videoSize", 0);
        if (bytes > 0) {
            double gb = (double) bytes / (1024d * 1024d * 1024d);
            return gb >= 1 ? String.format(Locale.US, "%.2f GB", gb) : String.format(Locale.US, "%.0f MB", bytes / (1024d * 1024d));
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)([0-9]+(?:[.,][0-9]+)?)\\s*(GB|MB|GiB|MiB|TB)\\b").matcher(text);
        if (m.find()) return m.group(1).replace(',', '.') + " " + m.group(2).toUpperCase(Locale.US);
        return "";
    }

    String parseAudio(String text) {
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("atmos")) return "Atmos";
        if (lower.contains("7.1")) return "7.1";
        if (lower.contains("5.1")) return "5.1";
        if (lower.contains("dts")) return "DTS";
        if (lower.contains("aac")) return "AAC";
        if (lower.contains("ac3") || lower.contains("ddp") || lower.contains("eac3")) return "Dolby Digital";
        return "";
    }

    static final int SOURCE_TYPE_DIRECT = 0;
    static final int SOURCE_TYPE_DEBRID_CACHED = 1;
    static final int SOURCE_TYPE_NEEDS_ACCOUNT = 2;

    int getStreamSourceType(JSONObject s) {
        String url = s.optString("url", "").trim();
        String infoHash = s.optString("infoHash", "").trim();
        String rawTitle = s.optString("title", s.optString("name", "")).toLowerCase(Locale.US);

        if (!url.isEmpty()) {
            if (url.contains("torbox") || url.contains("debrid") || url.contains("premiumize")
                    || rawTitle.contains("[rd+]") || rawTitle.contains("[tb+]") || rawTitle.contains("[pm+]")
                    || rawTitle.contains("⚡") || rawTitle.contains("cached")) {
                return SOURCE_TYPE_DEBRID_CACHED;
            }
            return SOURCE_TYPE_DIRECT;
        }

        if (!infoHash.isEmpty()) {
            String torboxKey = prefs.getString("torbox_api_key", "").trim();
            if (!torboxKey.isEmpty()) {
                return SOURCE_TYPE_DEBRID_CACHED;
            }
            return SOURCE_TYPE_NEEDS_ACCOUNT;
        }

        return SOURCE_TYPE_DIRECT;
    }

    void showDebridRequiredDialog() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(18), dp(22), dp(18));
        ThemeManager.styleSurfaceCard(this, shell, theme != null ? theme.surfaceContainer : surface, 14, 1, theme != null ? theme.outlineVariant : 0);

        TextView title = text("⚠️ Debrid Account Required", 18);
        title.setTypeface(null, 1);
        title.setTextColor(theme != null ? theme.primary : accent);
        shell.addView(title);

        TextView msg = text(
            "This source is a bare P2P torrent (infoHash) and requires a Debrid cloud account to stream in your vehicle.\n\n" +
            "Add a Torbox or Real-Debrid API key in Settings, or pick a direct stream labeled [🟢 Direct].",
            14
        );
        msg.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        msg.setPadding(0, dp(10), 0, dp(18));
        shell.addView(msg);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.RIGHT);

        Button dismissBtn = btn("Pick a Direct Stream", new Runnable() {
            @Override
            public void run() {
                d.dismiss();
            }
        });
        dismissBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(MainActivity.this, dismissBtn, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
        } else {
            dismissBtn.setBackgroundDrawable(shape(0xff222e3c, 8));
            dismissBtn.setTextColor(Color.WHITE);
        }
        btns.addView(dismissBtn, new LinearLayout.LayoutParams(-2, dp(42)));

        Button settingsBtn = btn("⚙️ Open Settings", new Runnable() {
            @Override
            public void run() {
                d.dismiss();
                history.add(page);
                render("Debrid", true);
            }
        });
        settingsBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(MainActivity.this, settingsBtn, theme.primary, theme.onPrimary, 0, 0, true);
        } else {
            settingsBtn.setBackgroundDrawable(shape(accent, 8));
            settingsBtn.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(-2, dp(42));
        setLp.setMargins(dp(10), 0, 0, 0);
        btns.addView(settingsBtn, setLp);

        shell.addView(btns);
        d.setContentView(shell);
        d.show();

        Window win = d.getWindow();
        if (win != null) {
            win.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(540)), WindowManager.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.CENTER);
        }
    }

    void showStreamResolutionErrorDialog(final String errorDetail) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(18), dp(22), dp(18));
        ThemeManager.styleSurfaceCard(this, shell, theme != null ? theme.surfaceContainer : surface, 14, 1, theme != null ? theme.outlineVariant : 0);

        TextView title = text("⚠️ Stream Resolution Failed", 18);
        title.setTypeface(null, 1);
        title.setTextColor(theme != null ? theme.error : 0xffe74c3c);
        shell.addView(title);

        String detail = (errorDetail != null && !errorDetail.isEmpty()) ? errorDetail : "Torrent not available on Debrid";
        String msgText = "This torrent could not be converted into an HTTPS stream by your Debrid service.\n\n" +
            "Error: " + detail + "\n\n" +
            "The file may not be cached on Debrid yet. Please pick another stream result or select a source labeled [🟢 Direct].";

        TextView msg = text(msgText, 14);
        msg.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        msg.setPadding(0, dp(10), 0, dp(18));
        shell.addView(msg);

        Button okBtn = btn("Choose Another Stream", new Runnable() {
            @Override
            public void run() {
                d.dismiss();
            }
        });
        okBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(MainActivity.this, okBtn, theme.primary, theme.onPrimary, 0, 0, true);
        } else {
            okBtn.setBackgroundDrawable(shape(accent, 8));
            okBtn.setTextColor(Color.WHITE);
        }
        shell.addView(okBtn, new LinearLayout.LayoutParams(-1, dp(42)));

        d.setContentView(shell);
        d.show();

        Window win = d.getWindow();
        if (win != null) {
            win.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(520)), WindowManager.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.CENTER);
        }
    }

    void onStreamClicked(final JSONObject stream) {
        final String directUrl = stream.optString("url", "");
        final String infoHash = stream.optString("infoHash", "");
        final int fileIdx = stream.optInt("fileIdx", 0);
        final JSONArray subs = stream.optJSONArray("subtitles");

        if (!directUrl.isEmpty()) {
            openInAppPlayer(directUrl, subs);
            return;
        }

        if (!infoHash.isEmpty()) {
            // Check if Torbox API is configured
            final String torboxKey = prefs.getString("torbox_api_key", "");
            if (torboxKey.isEmpty()) {
                showDebridRequiredDialog();
                return;
            }

            final Dialog progress = new Dialog(this);
            progress.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout shell = new LinearLayout(this);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setPadding(dp(22), dp(18), dp(22), dp(18));
            ThemeManager.styleSurfaceCard(this, shell, theme != null ? theme.surfaceContainer : surface, 12, 1, theme != null ? theme.outlineVariant : 0);

            TextView pTitle = text("Resolving Debrid Stream…", 17);
            pTitle.setTypeface(null, 1);
            pTitle.setTextColor(theme != null ? theme.primary : accent);
            shell.addView(pTitle);

            TextView pDesc = text("Requesting cached HTTPS video stream from Debrid CDN…", 13);
            pDesc.setTextColor(muted);
            pDesc.setPadding(0, dp(6), 0, dp(14));
            shell.addView(pDesc);

            Button cancelBtn = btn("Cancel", new Runnable() {
                @Override
                public void run() {
                    progress.dismiss();
                }
            });
            cancelBtn.setTextSize(12);
            if (theme != null) {
                ThemeManager.stylePillButton(MainActivity.this, cancelBtn, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
            }
            shell.addView(cancelBtn, new LinearLayout.LayoutParams(dp(100), dp(36)));

            progress.setContentView(shell);
            progress.show();

            apiWorkers.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final String resolved = TorboxClient.resolveTorrent(MainActivity.this, torboxKey, infoHash, fileIdx);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (progress.isShowing()) progress.dismiss();
                                openInAppPlayer(resolved, subs);
                            }
                        });
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (progress.isShowing()) progress.dismiss();
                                showStreamResolutionErrorDialog(e.getMessage());
                            }
                        });
                    }
                }
            });
            return;
        }

        Toast.makeText(this, "This stream source cannot be played directly.", Toast.LENGTH_SHORT).show();
    }

    void openInAppPlayer(String url) {
        openInAppPlayer(url, null);
    }

    void openInAppPlayer(String url, JSONArray subtitles) {
        if (!parkedConfirmed) {
            showParkedConfirmation();
            return;
        }

        rememberTitle();
        String displayTitle = selectedName;
        if (displayTitle.isEmpty() || "Untitled".equals(displayTitle)) {
            displayTitle = selectedId;
        }
        if (!selectedVideoLabel.isEmpty()) {
            displayTitle = displayTitle + " • " + selectedVideoLabel;
        }

        if (prefs.getBoolean("vlc_integration", false)) {
            try {
                Intent vlcIntent = new Intent(Intent.ACTION_VIEW);
                vlcIntent.setPackage("org.videolan.vlc");
                vlcIntent.setDataAndType(Uri.parse(url), "video/*");
                vlcIntent.putExtra("title", displayTitle);
                vlcIntent.putExtra("from_start", false);
                startActivity(vlcIntent);
                return;
            } catch (Exception e) {
                Toast.makeText(this, "VLC not found. Falling back to native ExoPlayer.", Toast.LENGTH_SHORT).show();
            }
        }

        Intent intent = new Intent(this, ExoPlayerActivity.class);
        intent.putExtra("stream_url", url);
        intent.putExtra("stream_title", displayTitle);
        intent.putExtra("sync_meta_id", selectedId);
        intent.putExtra("sync_video_id", selectedVideoId.isEmpty() ? selectedId : selectedVideoId);
        intent.putExtra("sync_media_type", selectedType);
        intent.putExtra("sync_item_title", (selectedName.isEmpty() || "Untitled".equals(selectedName)) ? selectedId : selectedName);
        intent.putExtra("sync_poster", selectedPoster);
        if (subtitles != null && subtitles.length() > 0) {
            intent.putExtra("stream_subtitles", subtitles.toString());
        }
        startActivity(intent);
    }

    void playDirect(String url, String displayTitle) {
        if (prefs.getBoolean("vlc_integration", false)) {
            try {
                Intent vlcIntent = new Intent(Intent.ACTION_VIEW);
                vlcIntent.setPackage("org.videolan.vlc");
                vlcIntent.setDataAndType(Uri.parse(url), "video/*");
                vlcIntent.putExtra("title", displayTitle);
                vlcIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(vlcIntent);
                return;
            } catch (Exception ignored) {}
        }
        Intent intent = new Intent(this, ExoPlayerActivity.class);
        intent.putExtra("stream_url", url);
        intent.putExtra("stream_title", displayTitle);
        startActivity(intent);
    }

    boolean isVlcInstalled() {
        try {
            getPackageManager().getPackageInfo("org.videolan.vlc", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    LinearLayout cardLayout() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        ThemeManager.styleSurfaceCard(this, c, theme.surfaceContainer, 14, 1, theme.outlineVariant);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        c.setLayoutParams(lp);
        return c;
    }

    LinearLayout switchRow(String title, final M3Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView label = text(title, 16);
        if (theme != null) {
            label.setTextColor(theme.onSurface);
        }
        label.setTypeface(null, 1);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));

        if (theme != null) {
            sw.setPalette(theme);
        }
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(52), dp(32));
        swLp.setMargins(dp(12), 0, 0, 0);
        row.addView(sw, swLp);

        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.performClick();
            }
        });
        return row;
    }

    View divider() {
        View d = new View(this);
        d.setBackgroundColor(theme != null ? theme.outlineVariant : 0xff222e3c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(dp(4), dp(12), dp(4), dp(12));
        d.setLayoutParams(lp);
        return d;
    }

    void recordError(Exception e) {
        String t = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (t.length() > 220) t = t.substring(0, 220);
        errors.add(0, t);
        while (errors.size() > 30) errors.remove(errors.size() - 1);
    }

    String getNuvioApiUrl() {
        return prefs.getString("nuvio_api_url", DEFAULT_NUVIO_API);
    }

    private static final HashMap<String, String> AVATAR_CATALOG = new HashMap<>();
    static {
        AVATAR_CATALOG.put("avatar_linear_man_purple", "avatar_linear_purple_v3.png");
        AVATAR_CATALOG.put("avatar_linear_woman_pink", "avatar_linear_pink_v3.png");
        AVATAR_CATALOG.put("avatar_linear_woman_teal", "avatar_linear_teal_v3.png");
        AVATAR_CATALOG.put("avatar_linear_woman_red", "avatar_linear_red_v3.png");
        AVATAR_CATALOG.put("avatar_linear_man_navy", "avatar_linear_navy_v3.png");
        AVATAR_CATALOG.put("avatar_linear_woman_yellow", "avatar_linear_yellow_v3.png");
        AVATAR_CATALOG.put("avatar_linear_man_green", "avatar_linear_green_v3.png");
        AVATAR_CATALOG.put("avatar_aang", "originals/nova-v1.png");
        AVATAR_CATALOG.put("avatar_arthur_morgan", "originals/bolt-v1.png");
        AVATAR_CATALOG.put("avatar_ash", "originals/marina-v1.png");
        AVATAR_CATALOG.put("avatar_chihiro", "originals/shadow-v1.png");
        AVATAR_CATALOG.put("avatar_daenerys", "originals/rook-v1.png");
        AVATAR_CATALOG.put("avatar_dexter", "originals/riff-v1.png");
        AVATAR_CATALOG.put("avatar_eleven", "originals/clue-v1.png");
        AVATAR_CATALOG.put("avatar_eren", "originals/cedar-v1.png");
        AVATAR_CATALOG.put("avatar_furiosa", "originals/ruby-v1.png");
        AVATAR_CATALOG.put("avatar_geralt", "originals/sage-v1.png");
        AVATAR_CATALOG.put("avatar_gojo", "portraits/quinn-v1.png");
        AVATAR_CATALOG.put("avatar_goku", "portraits/iris-v1.png");
        AVATAR_CATALOG.put("avatar_harry_potter", "portraits/ari-v1.png");
        AVATAR_CATALOG.put("avatar_jack_sparrow", "portraits/hugo-v1.png");
        AVATAR_CATALOG.put("avatar_jinwoo", "portraits/skye-v1.png");
        AVATAR_CATALOG.put("avatar_joel", "portraits/maya-v1.png");
        AVATAR_CATALOG.put("avatar_jon_snow", "portraits/drew-v1.png");
        AVATAR_CATALOG.put("avatar_katara", "portraits/leo-v1.png");
        AVATAR_CATALOG.put("avatar_killua", "portraits/zia-v1.png");
        AVATAR_CATALOG.put("avatar_kratos", "portraits/elle-v1.png");
        AVATAR_CATALOG.put("avatar_levi", "animals/pip-v1.png");
        AVATAR_CATALOG.put("avatar_mikasa", "animals/otto-v1.png");
        AVATAR_CATALOG.put("avatar_naruto", "animals/milo-v1.png");
        AVATAR_CATALOG.put("avatar_negan", "animals/miso-v1.png");
        AVATAR_CATALOG.put("avatar_neo", "animals/elio-v1.png");
        AVATAR_CATALOG.put("avatar_rick_grimes", "animals/finn-v1.png");
        AVATAR_CATALOG.put("avatar_saitama", "animals/poppy-v1.png");
        AVATAR_CATALOG.put("avatar_saul_goodman", "animals/bao-v1.png");
        AVATAR_CATALOG.put("avatar_lalo", "animals/bram-v1.png");
        AVATAR_CATALOG.put("avatar_lara", "animals/clover-v1.png");
        AVATAR_CATALOG.put("avatar_tommy_shelby", "sketches/rowan-v1.png");
        AVATAR_CATALOG.put("avatar_v", "sketches/silas-v1.png");
        AVATAR_CATALOG.put("avatar_walter_white", "sketches/nico-v1.png");
        AVATAR_CATALOG.put("avatar_wednesday", "sketches/violet-v1.png");
        AVATAR_CATALOG.put("avatar_moss", "sketches/moss-v1.png");
    }

    void refreshAvatarCatalog() {
        apiWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String u = getNuvioApiUrl() + "/rest/v1/avatar_catalog?select=id,storage_path";
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    if (c instanceof HttpsURLConnection) {
                        ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(MainActivity.this));
                    }
                    c.setRequestProperty("apikey", NUVIO_ANON_KEY);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.6");
                    c.setConnectTimeout(8000);
                    if (c.getResponseCode() < 400) {
                        InputStream in = c.getInputStream();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = in.read(b)) != -1) out.write(b, 0, n);
                        in.close();
                        JSONArray arr = new JSONArray(new String(out.toByteArray(), "UTF-8"));
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject it = arr.optJSONObject(i);
                            if (it != null) {
                                String id = it.optString("id");
                                String path = it.optString("storage_path");
                                if (!id.isEmpty() && !path.isEmpty()) {
                                    AVATAR_CATALOG.put(id, path);
                                }
                            }
                        }
                    }
                    c.disconnect();
                } catch (Exception ignored) {}
            }
        });
    }

    String resolveAvatarUrl(JSONObject p) {
        if (p == null) return "";
        String url = p.optString("avatar_url", "").trim();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        String avId = p.optString("avatar_id", "").trim();
        if (avId.isEmpty()) avId = p.optString("avatar", "").trim();

        if (!avId.isEmpty()) {
            if (avId.startsWith("http://") || avId.startsWith("https://")) {
                return avId;
            }
            String mapped = AVATAR_CATALOG.get(avId);
            if (mapped != null && !mapped.isEmpty()) {
                return getNuvioApiUrl() + "/storage/v1/object/public/avatars/" + mapped;
            }
            if (avId.contains("/") || avId.endsWith(".png") || avId.endsWith(".jpg") || avId.endsWith(".webp")) {
                return getNuvioApiUrl() + "/storage/v1/object/public/avatars/" + avId;
            }
            if (avId.startsWith("avatar_")) {
                return getNuvioApiUrl() + "/storage/v1/object/public/avatars/" + avId + ".png";
            }
        }

        if (!url.isEmpty()) {
            return resolveAvatarUrl(url);
        }
        return "";
    }

    String resolveAvatarUrl(String ref) {
        if (ref == null) return "";
        String s = ref.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        String mapped = AVATAR_CATALOG.get(s);
        if (mapped != null && !mapped.isEmpty()) {
            return getNuvioApiUrl() + "/storage/v1/object/public/avatars/" + mapped;
        }
        if (s.contains("/") || s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".webp")) {
            return getNuvioApiUrl() + "/storage/v1/object/public/avatars/" + s;
        }
        if (s.startsWith("avatar_")) {
            return getNuvioApiUrl() + "/storage/v1/object/public/avatars/" + s + ".png";
        }
        if (s.length() <= 3 && !s.contains(".")) {
            return s;
        }
        return getNuvioApiUrl() + "/storage/v1/object/public/avatars/" + s + ".png";
    }

    JSONArray getNuvioProfiles() {
        String json = prefs.getString("nuvio_profiles", "");
        if (json.isEmpty()) return new JSONArray();
        try {
            JSONArray arr = new JSONArray(json);
            JSONArray clean = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                String id = p.optString("id", "");
                if ("driver".equalsIgnoreCase(id) || "family".equalsIgnoreCase(id)) continue;
                clean.put(p);
            }
            if (clean.length() != arr.length()) {
                prefs.edit().putString("nuvio_profiles", clean.toString()).apply();
            }
            return clean;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    JSONObject getActiveNuvioProfile() {
        JSONArray profiles = getNuvioProfiles();
        String activeId = prefs.getString("nuvio_active_profile_id", "");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject p = profiles.optJSONObject(i);
            if (p != null && activeId.equals(p.optString("id"))) return p;
        }
        return profiles.length() > 0 ? profiles.optJSONObject(0) : null;
    }

    void switchNuvioProfile(String profileId, String profileName, String profileAvatar) {
        String resolvedAv = resolveAvatarUrl(profileAvatar);
        int intId = 1;
        JSONArray proms = getNuvioProfiles();
        for (int i = 0; i < proms.length(); i++) {
            JSONObject p = proms.optJSONObject(i);
            if (p != null && profileId.equals(p.optString("id"))) {
                intId = p.optInt("profile_id", p.optInt("profile_index", 1));
                break;
            }
        }
        prefs.edit()
                .putString("nuvio_active_profile_id", profileId)
                .putInt("nuvio_active_profile_int_id", intId)
                .putString("nuvio_active_profile_name", profileName)
                .putString("nuvio_active_profile_avatar", resolvedAv)
                .apply();
        Toast.makeText(this, "Switched to Nuvio profile: " + profileName, Toast.LENGTH_SHORT).show();
        fetchNuvioAddons();
        fetchNuvioWatchProgress();
        fetchCloudProfileSettings(profileId);
        render("Account", false);
    }

    void fetchCloudProfileSettings(String profileId) {
        if (!NuvioClient.getInstance(this).isLoggedIn() || profileId == null || profileId.isEmpty()) return;
        NuvioClient.getInstance(this).fetchProfileSettings(profileId, new NuvioClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject settings) {
                if (settings == null) return;
                String audio = settings.optString("preferred_audio_language", "");
                String sub = settings.optString("preferred_subtitle_language", "");
                String quality = settings.optString("streaming_quality", "");
                SharedPreferences.Editor ed = prefs.edit();
                if (!audio.isEmpty()) ed.putString("pref_audio_lang", audio);
                if (!sub.isEmpty()) ed.putString("pref_subtitle_lang", sub);
                if (!quality.isEmpty()) {
                    ed.putString("pref_stream_quality", quality);
                    sourceFilter = quality;
                }
                ed.apply();
            }
            @Override
            public void onError(String error, int code) {}
        });
    }

    void syncCloudProfileSettings() {
        if (!NuvioClient.getInstance(this).isLoggedIn()) return;
        String profId = prefs.getString("nuvio_active_profile_id", "");
        if (profId.isEmpty()) return;

        try {
            JSONObject s = new JSONObject();
            s.put("preferred_audio_language", prefs.getString("pref_audio_lang", "auto"));
            s.put("preferred_subtitle_language", prefs.getString("pref_subtitle_lang", "off"));
            s.put("streaming_quality", prefs.getString("pref_stream_quality", "All"));
            s.put("trakt_scrobbling", TraktClient.getInstance(this).isConnected());
            NuvioClient.getInstance(this).updateProfileSettings(profId, s, new NuvioClient.Callback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    Log.d("NuvioSettings", "Profile settings synced to cloud");
                }
                @Override
                public void onError(String error, int code) {
                    Log.w("NuvioSettings", "Profile settings sync error: " + error);
                }
            });
        } catch (Exception ignored) {}
    }

    void nuvioLogin(final String email, final String password) {
        apiWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String baseUrl = getNuvioApiUrl();
                    URL url = new URL(baseUrl + "/auth/v1/token?grant_type=password");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    if (c instanceof HttpsURLConnection) {
                        ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(MainActivity.this));
                    }
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(18000);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("apikey", NUVIO_ANON_KEY);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.6");

                    JSONObject body = new JSONObject();
                    body.put("email", email);
                    body.put("password", password);
                    byte[] bytes = body.toString().getBytes("UTF-8");
                    OutputStream os = c.getOutputStream();
                    os.write(bytes);
                    os.flush();
                    os.close();

                    int code = c.getResponseCode();
                    if (code >= 400) {
                        ByteArrayOutputStream errOut = new ByteArrayOutputStream();
                        InputStream err = c.getErrorStream();
                        if (err != null) {
                            byte[] b = new byte[2048];
                            int n;
                            while ((n = err.read(b)) != -1) errOut.write(b, 0, n);
                            err.close();
                        }
                        c.disconnect();
                        String msg = "HTTP " + code;
                        try {
                            JSONObject errJson = new JSONObject(new String(errOut.toByteArray(), "UTF-8"));
                            msg = errJson.optString("error_description", errJson.optString("msg", errJson.optString("message", msg)));
                        } catch (Exception ignored) {}
                        final String errMsg = msg;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this, "Nuvio Login Error: " + errMsg, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }

                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    in.close();
                    c.disconnect();

                    JSONObject resp = new JSONObject(new String(out.toByteArray(), "UTF-8"));
                    final String token = resp.optString("access_token", "");
                    final String refresh = resp.optString("refresh_token", "");
                    JSONObject userObj = resp.optJSONObject("user");
                    final String uid = userObj != null ? userObj.optString("id", "") : "";
                    final String userEmail = userObj != null ? userObj.optString("email", email) : email;

                    if (!token.isEmpty()) {
                        auth = token;
                        verifiedAccountEmail = userEmail;
                        prefs.edit()
                                .putString("auth", token)
                                .putString("nuvio_refresh_token", refresh)
                                .putString("nuvio_user_id", uid)
                                .putString("nuvio_email", userEmail)
                                .apply();

                        fetchNuvioProfilesInternal(token);

                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this, "Signed in to Nuvio as " + userEmail, Toast.LENGTH_SHORT).show();
                                render("Account", false);
                            }
                        });
                    }
                } catch (final Exception e) {
                    recordError(e);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Sign-in Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    void nuvioLoginWithToken(final String token) {
        if (token == null || token.trim().isEmpty()) return;
        final String t = token.trim();
        auth = t;
        verifiedAccountEmail = prefs.getString("nuvio_email", "");
        prefs.edit().putString("auth", t).apply();
        Toast.makeText(this, "Connecting Nuvio account…", Toast.LENGTH_SHORT).show();
        apiWorkers.execute(new Runnable() {
            @Override
            public void run() {
                fetchNuvioProfilesInternal(t);
                NuvioClient.getInstance(MainActivity.this).registerDevice(null);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        verifiedAccountEmail = prefs.getString("nuvio_email", "");
                        Toast.makeText(MainActivity.this, "Welcome! Nuvio account linked successfully.", Toast.LENGTH_SHORT).show();
                        render("Account", false);
                    }
                });
            }
        });
    }

    void fetchNuvioProfiles() {
        if (auth.isEmpty()) return;
        apiWorkers.execute(new Runnable() {
            @Override
            public void run() {
                fetchNuvioProfilesInternal(auth);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if ("Account".equals(page)) render("Account", false);
                    }
                });
            }
        });
    }

    void fetchNuvioProfilesInternal(String token) {
        if (!isNuvioToken(token)) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Sign-in required: Current session is not a Nuvio account.", Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        try {
            String baseUrl = getNuvioApiUrl();
            URL url = new URL(baseUrl + "/rest/v1/profiles?select=*");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            if (c instanceof HttpsURLConnection) {
                ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(MainActivity.this));
            }
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("apikey", NUVIO_ANON_KEY);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.6");
            c.setRequestProperty("Accept", "application/json");

            int code = c.getResponseCode();
            if (code >= 400) {
                ByteArrayOutputStream errOut = new ByteArrayOutputStream();
                InputStream err = c.getErrorStream();
                if (err != null) {
                    byte[] b = new byte[2048];
                    int n;
                    while ((n = err.read(b)) != -1) errOut.write(b, 0, n);
                    err.close();
                }
                c.disconnect();
                String errText = new String(errOut.toByteArray(), "UTF-8");
                recordError(new IOException("Nuvio profiles HTTP " + code + ": " + errText));
                final String msg = "HTTP " + code + (code == 401 ? " (Session expired: please sign in again)" : "");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "Profile sync error: " + msg, Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }

            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            in.close();
            c.disconnect();

            String jsonStr = new String(out.toByteArray(), "UTF-8");
            final JSONArray rawProfiles = new JSONArray(jsonStr);
            final JSONArray profiles = new JSONArray();
            for (int i = 0; i < rawProfiles.length(); i++) {
                JSONObject p = rawProfiles.optJSONObject(i);
                if (p != null) {
                    String id = p.optString("id", "");
                    if (!"driver".equalsIgnoreCase(id) && !"family".equalsIgnoreCase(id)) {
                        profiles.put(p);
                    }
                }
            }
            prefs.edit().putString("nuvio_profiles", profiles.toString()).apply();

            if (profiles.length() > 0) {
                String activeId = prefs.getString("nuvio_active_profile_id", "");
                boolean found = false;
                for (int i = 0; i < profiles.length(); i++) {
                    JSONObject p = profiles.optJSONObject(i);
                    if (p != null && activeId.equals(p.optString("id"))) {
                        found = true;
                        String avUrl = resolveAvatarUrl(p);
                        int pInt = p.optInt("profile_id", p.optInt("profile_index", 1));
                        prefs.edit()
                                .putString("nuvio_active_profile_name", p.optString("name", "User Profile"))
                                .putString("nuvio_active_profile_avatar", avUrl)
                                .putInt("nuvio_active_profile_int_id", pInt)
                                .apply();
                        break;
                    }
                }
                if (!found) {
                    JSONObject first = profiles.optJSONObject(0);
                    if (first != null) {
                        String avUrl = resolveAvatarUrl(first);
                        int pInt = first.optInt("profile_id", first.optInt("profile_index", 1));
                        prefs.edit()
                                .putString("nuvio_active_profile_id", first.optString("id"))
                                .putInt("nuvio_active_profile_int_id", pInt)
                                .putString("nuvio_active_profile_name", first.optString("name", "User Profile"))
                                .putString("nuvio_active_profile_avatar", avUrl)
                                .apply();
                    }
                }
                fetchNuvioAddons();
                fetchNuvioWatchProgress();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "Synced " + profiles.length() + " Nuvio profile(s) from server!", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "Your Nuvio account has no profiles yet. Tap 'Create' to add one.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        } catch (final Exception e) {
            recordError(e);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Profile sync failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    void createNuvioProfile(final String profileName, final String avatarUrl) {
        if (auth.isEmpty()) return;
        apiWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String baseUrl = getNuvioApiUrl();
                    URL url = new URL(baseUrl + "/rest/v1/profiles");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    if (c instanceof HttpsURLConnection) {
                        ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(MainActivity.this));
                    }
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("apikey", NUVIO_ANON_KEY);
                    c.setRequestProperty("Authorization", "Bearer " + auth);
                    c.setRequestProperty("Prefer", "return=representation");
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.6");

                    JSONObject body = new JSONObject();
                    body.put("name", profileName);
                    if (avatarUrl != null && !avatarUrl.isEmpty()) {
                        body.put("avatar_url", avatarUrl);
                    }
                    byte[] bytes = body.toString().getBytes("UTF-8");
                    OutputStream os = c.getOutputStream();
                    os.write(bytes);
                    os.flush();
                    os.close();

                    int code = c.getResponseCode();
                    c.disconnect();
                    if (code < 300) {
                        fetchNuvioProfilesInternal(auth);
                        fetchNuvioAddons();
                        fetchNuvioWatchProgress();
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this, "Profile '" + profileName + "' created on Nuvio!", Toast.LENGTH_SHORT).show();
                                render("Account", false);
                            }
                        });
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this, "Failed to create profile (HTTP " + code + ")", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    void fetchNuvioAddons() {
        if (!isNuvioToken(auth)) return;
        apiWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String baseUrl = getNuvioApiUrl();
                    URL url = new URL(baseUrl + "/rest/v1/addons?select=*&order=sort_order.asc");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    if (c instanceof HttpsURLConnection) {
                        ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(MainActivity.this));
                    }
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("apikey", NUVIO_ANON_KEY);
                    c.setRequestProperty("Authorization", "Bearer " + auth);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.6");
                    c.setRequestProperty("Accept", "application/json");

                    int code = c.getResponseCode();
                    if (code >= 400) {
                        c.disconnect();
                        return;
                    }

                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    in.close();
                    c.disconnect();

                    String jsonStr = new String(out.toByteArray(), "UTF-8");
                    JSONArray raw = new JSONArray(jsonStr);
                    if (raw.length() == 0) return;

                    int activeProfInt = prefs.getInt("nuvio_active_profile_int_id", 1);
                    JSONArray next = new JSONArray();
                    boolean hasCinemeta = false;

                    for (int i = 0; i < raw.length(); i++) {
                        JSONObject row = raw.optJSONObject(i);
                        if (row == null) continue;
                        boolean enabled = row.optBoolean("enabled", true);
                        if (!enabled) continue;
                        int rowProf = row.optInt("profile_id", 1);
                        if (rowProf != 1 && rowProf != activeProfInt) continue;

                        String transport = row.optString("url", "").trim();
                        if (transport.isEmpty()) continue;
                        String name = row.optString("name", "Nuvio Addon");
                        if (transport.contains("cinemeta")) hasCinemeta = true;

                        JSONObject entry = new JSONObject();
                        entry.put("transportUrl", transport);
                        entry.put("name", name);
                        entry.put("profile_id", rowProf);
                        next.put(entry);
                    }

                    if (!hasCinemeta) {
                        JSONObject cinemeta = new JSONObject();
                        cinemeta.put("name", "Cinemeta (Official)");
                        cinemeta.put("transportUrl", "https://v3-cinemeta.strem.io/manifest.json");
                        next.put(cinemeta);
                    }

                    addons = next;
                    saveAddonCache();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if ("Add-ons".equals(page)) render("Add-ons", false);
                        }
                    });
                } catch (Exception ignored) {}
            }
        });
    }

    void fetchNuvioWatchProgress() {
        if (!isNuvioToken(auth)) return;
        apiWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String baseUrl = getNuvioApiUrl();
                    // 1. Fetch watch progress from Supabase
                    URL url = new URL(baseUrl + "/rest/v1/watch_progress?select=*&order=last_watched.desc&limit=50");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    if (c instanceof HttpsURLConnection) {
                        ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(MainActivity.this));
                    }
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("apikey", NUVIO_ANON_KEY);
                    c.setRequestProperty("Authorization", "Bearer " + auth);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.6");
                    c.setRequestProperty("Accept", "application/json");

                    int code = c.getResponseCode();
                    if (code >= 400) {
                        c.disconnect();
                        return;
                    }

                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    in.close();
                    c.disconnect();

                    JSONArray raw = new JSONArray(new String(out.toByteArray(), "UTF-8"));
                    if (raw.length() == 0) return;

                    // 2. Fetch library items to map names & posters
                    HashMap<String, JSONObject> libraryMap = new HashMap<String, JSONObject>();
                    try {
                        URL libUrl = new URL(baseUrl + "/rest/v1/library_items?select=content_id,content_type,name,poster&limit=200");
                        HttpURLConnection lc = (HttpURLConnection) libUrl.openConnection();
                        if (lc instanceof HttpsURLConnection) {
                            ((HttpsURLConnection) lc).setSSLSocketFactory(VideoTls.build(MainActivity.this));
                        }
                        lc.setConnectTimeout(8000);
                        lc.setReadTimeout(12000);
                        lc.setRequestProperty("apikey", NUVIO_ANON_KEY);
                        lc.setRequestProperty("Authorization", "Bearer " + auth);
                        lc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.0");
                        lc.setRequestProperty("Accept", "application/json");
                        if (lc.getResponseCode() < 400) {
                            InputStream lin = lc.getInputStream();
                            ByteArrayOutputStream lout = new ByteArrayOutputStream();
                            while ((n = lin.read(b)) != -1) lout.write(b, 0, n);
                            lin.close();
                            JSONArray libArr = new JSONArray(new String(lout.toByteArray(), "UTF-8"));
                            for (int i = 0; i < libArr.length(); i++) {
                                JSONObject item = libArr.optJSONObject(i);
                                if (item != null) {
                                    String cid = item.optString("content_id", "");
                                    if (!cid.isEmpty()) libraryMap.put(cid, item);
                                }
                            }
                        }
                        lc.disconnect();
                    } catch (Exception ignored) {}

                    int activeProfInt = prefs.getInt("nuvio_active_profile_int_id", 1);
                    String activeProfId = prefs.getString("nuvio_active_profile_id", "");
                    LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<String, JSONObject>();
                    HashSet<String> seenServerIds = new HashSet<String>();

                    for (int i = 0; i < raw.length(); i++) {
                        JSONObject wp = raw.optJSONObject(i);
                        if (wp == null) continue;
                        String contentId = wp.optString("content_id", "");
                        if (contentId.isEmpty()) continue;
                        String type = wp.optString("content_type", "movie");
                        String videoId = wp.optString("video_id", "");
                        int season = wp.optInt("season", -1);
                        int episode = wp.optInt("episode", -1);
                        String progressKey = wp.optString("progress_key", "");
                        long position = wp.optLong("position", 0);
                        long duration = wp.optLong("duration", 0);
                        long lastWatched = wp.optLong("last_watched", 0);
                        int profId = wp.optInt("profile_id", 1);

                        // If position or duration are in seconds (e.g. from web client), convert to ms
                        if (duration > 0 && duration < 86400) {
                            position *= 1000L;
                            duration *= 1000L;
                        } else if (duration <= 0 && position > 0 && position < 86400) {
                            position *= 1000L;
                        }

                        // Skip completed items (>95%)
                        if (duration > 0 && position >= duration * 0.95) continue;
                        // Skip items with negligible watch time
                        if (position < 5000) continue;

                        // Parse season and episode from progress_key or videoId if not directly provided
                        if ((season <= 0 || episode <= 0) && progressKey.contains("_s") && progressKey.contains("e")) {
                            try {
                                int sIdx = progressKey.indexOf("_s");
                                int eIdx = progressKey.indexOf("e", sIdx + 2);
                                if (sIdx != -1 && eIdx != -1) {
                                    season = Integer.parseInt(progressKey.substring(sIdx + 2, eIdx));
                                    episode = Integer.parseInt(progressKey.substring(eIdx + 1));
                                }
                            } catch (Exception ignored) {}
                        }

                        if ((season <= 0 || episode <= 0)) {
                            String ref = videoId.contains(":") ? videoId : (progressKey.contains(":") ? progressKey : "");
                            if (!ref.isEmpty()) {
                                String[] parts = ref.split(":");
                                if (parts.length >= 3) {
                                    try {
                                        season = Integer.parseInt(parts[1]);
                                        episode = Integer.parseInt(parts[2]);
                                    } catch (Exception ignored) {}
                                }
                            }
                        }

                        String videoLabel = wp.optString("video_label", "");
                        if ("series".equals(type)) {
                            if (season > 0 && episode > 0) {
                                videoId = contentId + ":" + season + ":" + episode;
                                if (videoLabel.isEmpty()) {
                                    videoLabel = "Season " + season + ", Episode " + episode;
                                }
                            } else if (videoId.isEmpty()) {
                                videoId = contentId;
                            }
                        } else {
                            if (videoId.isEmpty()) videoId = contentId;
                        }

                        // Only show the latest watched item per show per profile
                        String normProf = (profId == activeProfInt && !activeProfId.isEmpty()) ? activeProfId : String.valueOf(profId);
                        String k = contentId + "@" + normProf;
                        if (merged.containsKey(k) || seenServerIds.contains(k) || seenServerIds.contains(contentId)) continue;
                        seenServerIds.add(k);
                        seenServerIds.add(contentId);

                        JSONObject item = new JSONObject();
                        item.put("id", contentId);
                        item.put("type", type);
                        item.put("videoId", videoId);
                        item.put("season", season);
                        item.put("episode", episode);
                        if (!videoLabel.isEmpty()) item.put("videoLabel", videoLabel);
                        item.put("positionMs", position);
                        item.put("durationMs", duration);
                        item.put("lastWatched", lastWatched);
                        if (profId == activeProfInt && !activeProfId.isEmpty()) {
                            item.put("profileId", activeProfId);
                        } else {
                            item.put("profileId", String.valueOf(profId));
                        }

                        String name = "";
                        String poster = "";
                        JSONObject libItem = libraryMap.get(contentId);
                        if (libItem != null) {
                            name = libItem.optString("name", "");
                            poster = libItem.optString("poster", "");
                        }

                        // Resolve missing name and poster directly from Cinemeta
                        if (name.isEmpty() || "Untitled".equals(name) || poster.isEmpty()) {
                            try {
                                JSONObject cResp = get(CINEMETA + "meta/" + type + "/" + contentId + ".json");
                                JSONObject cMeta = cResp != null ? cResp.optJSONObject("meta") : null;
                                if (cMeta != null) {
                                    if (name.isEmpty() || "Untitled".equals(name)) {
                                        name = cMeta.optString("name", "");
                                    }
                                    if (poster.isEmpty()) {
                                        poster = cMeta.optString("poster", "");
                                    }
                                    if ("series".equals(type) && season > 0 && episode > 0) {
                                        JSONArray vids = cMeta.optJSONArray("videos");
                                        if (vids != null) {
                                            for (int vi = 0; vi < vids.length(); vi++) {
                                                JSONObject epObj = vids.optJSONObject(vi);
                                                if (epObj != null && epObj.optInt("season") == season && epObj.optInt("episode") == episode) {
                                                    String epTitle = epObj.optString("title", "");
                                                    if (!epTitle.isEmpty()) {
                                                        item.put("videoLabel", "Season " + season + ", Episode " + episode + " • " + epTitle);
                                                    }
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }

                        if (!name.isEmpty()) item.put("name", name);
                        if (!poster.isEmpty()) item.put("poster", poster);

                        merged.put(k, item);
                    }

                    // Merge un-synced local continue watching items
                    String existing = prefs.getString("nuvio_continue_watching", "[]");
                    JSONArray localArr = new JSONArray(existing);
                    for (int i = 0; i < localArr.length(); i++) {
                        JSONObject it = localArr.optJSONObject(i);
                        if (it == null) continue;
                        String cid = it.optString("id", "");
                        if (cid.isEmpty()) continue;
                        if (seenServerIds.contains(cid)) continue;
                        String pid = it.optString("profileId", "");
                        String normLocalProf = (pid.isEmpty() || pid.equals(activeProfId) || pid.equals(String.valueOf(activeProfInt))) ? (!activeProfId.isEmpty() ? activeProfId : String.valueOf(activeProfInt)) : pid;
                        String k = cid + "@" + normLocalProf;
                        if (!merged.containsKey(k) && !seenServerIds.contains(k)) {
                            seenServerIds.add(k);
                            seenServerIds.add(cid);
                            merged.put(k, it);
                        }
                    }

                    JSONArray result = new JSONArray();
                    HashSet<String> finalSeen = new HashSet<String>();
                    for (JSONObject it : merged.values()) {
                        String fId = it.optString("id", "");
                        String fProf = it.optString("profileId", "");
                        String uKey = fId + "@" + fProf;
                        if (!finalSeen.contains(uKey) && !finalSeen.contains(fId)) {
                            finalSeen.add(uKey);
                            finalSeen.add(fId);
                            result.put(it);
                        }
                    }
                    prefs.edit().putString("nuvio_continue_watching", result.toString()).apply();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if ("Movies".equals(page) || "TV Series".equals(page) || "Home".equals(page)) {
                                render(page, false);
                            }
                        }
                    });
                } catch (Exception ignored) {}
            }
        });
    }

    void showAddProfileDialog() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(18), dp(16), dp(18), dp(16));
        ThemeManager.styleSurfaceCard(this, shell, theme != null ? theme.surfaceContainer : surface, 14, 1, theme != null ? theme.outlineVariant : 0);

        TextView title = text("Create New Nuvio Profile", 18);
        title.setTypeface(null, 1);
        shell.addView(title);

        TextView desc = text("Choose an avatar and name for your Nuvio profile:", 13);
        desc.setTextColor(muted);
        desc.setPadding(0, dp(4), 0, dp(10));
        shell.addView(desc);

        final String[] avatars = {"🚗", "🏎️", "🍿", "🎬", "⭐", "👤", "🚀", "🎮"};
        final String[] selectedAvatar = {avatars[0]};

        LinearLayout avatarRow = new LinearLayout(this);
        avatarRow.setOrientation(LinearLayout.HORIZONTAL);
        avatarRow.setGravity(Gravity.CENTER);
        for (final String av : avatars) {
            Button avBtn = btn(av, null);
            avBtn.setTextSize(20);
            avBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedAvatar[0] = av;
                    Toast.makeText(MainActivity.this, "Selected: " + av, Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.setMargins(dp(2), 0, dp(2), 0);
            avatarRow.addView(avBtn, lp);
        }
        shell.addView(avatarRow);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Profile Name (e.g. Passenger, Kids)…");
        nameInput.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        nameInput.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
        ThemeManager.styleSurfaceCard(this, nameInput, theme != null ? theme.surfaceContainerHigh : bg, 8, 1, theme != null ? theme.outlineVariant : 0);
        nameInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-1, dp(46));
        nLp.setMargins(0, dp(12), 0, dp(14));
        shell.addView(nameInput, nLp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button cancel = btn("Cancel", new Runnable() {
            @Override
            public void run() { d.dismiss(); }
        });
        cancel.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, cancel, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
        }
        btns.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button create = btn("Create Profile", new Runnable() {
            @Override
            public void run() {
                String nm = nameInput.getText().toString().trim();
                if (nm.isEmpty()) nm = "Profile " + (getNuvioProfiles().length() + 1);
                createNuvioProfile(nm, selectedAvatar[0]);
                d.dismiss();
            }
        });
        create.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, create, theme.primary, theme.onPrimary, 0, 0, true);
        } else {
            create.setTypeface(null, 1);
            create.setBackgroundDrawable(shape(accent, 8));
            create.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(0, dp(42), 1);
        crLp.setMargins(dp(8), 0, 0, 0);
        btns.addView(create, crLp);

        shell.addView(btns);
        d.setContentView(shell);
        d.show();

        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(520)), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
    }

    
    void showManageProfilesDialog() {
        final JSONArray all = getNuvioProfiles();
        if (all.length() == 0) {
            Toast.makeText(this, "No profiles found to edit", Toast.LENGTH_SHORT).show();
            return;
        }
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(18), dp(22), dp(18));
        ThemeManager.styleSurfaceCard(this, shell, theme != null ? theme.surfaceContainer : surface, 14, 1, theme != null ? theme.outlineVariant : 0);

        TextView title = text("Manage Profiles", 18);
        title.setTypeface(null, 1);
        title.setTextColor(theme != null ? theme.primary : accent);
        shell.addView(title);

        TextView desc = text("Select a profile to edit name, avatar emoji, PIN lock, or delete:", 13);
        desc.setTextColor(muted);
        desc.setPadding(0, dp(4), 0, dp(14));
        shell.addView(desc);

        for (int i = 0; i < all.length(); i++) {
            final JSONObject p = all.optJSONObject(i);
            if (p == null) continue;
            final String pName = p.optString("name", "Profile " + (i + 1));
            final String pAvatar = p.optString("avatar_url", "👤");
            final boolean isDef = p.optBoolean("is_default", false);

            LinearLayout pRow = new LinearLayout(this);
            pRow.setOrientation(LinearLayout.HORIZONTAL);
            pRow.setGravity(Gravity.CENTER_VERTICAL);
            pRow.setPadding(dp(12), dp(8), dp(12), dp(8));
            if (theme != null) {
                ThemeManager.styleSurfaceCard(this, pRow, theme.surfaceContainerHigh, 8, 1, theme.outlineVariant);
            } else {
                pRow.setBackgroundDrawable(shape(0xff232b38, 8));
            }

            TextView av = text(pAvatar, 20);
            pRow.addView(av);

            TextView nm = text(pName + (isDef ? " (Primary)" : ""), 14);
            nm.setTypeface(null, 1);
            nm.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
            nm.setPadding(dp(10), 0, 0, 0);
            pRow.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));

            Button editB = btn("Edit", new Runnable() {
                @Override
                public void run() {
                    d.dismiss();
                    showEditProfileDialog(p);
                }
            });
            editB.setTextSize(12);
            if (theme != null) {
                ThemeManager.stylePillButton(MainActivity.this, editB, theme.primary, theme.onPrimary, 0, 0, true);
            } else {
                editB.setBackgroundDrawable(shape(accent, 6));
                editB.setTextColor(Color.WHITE);
            }
            pRow.addView(editB, new LinearLayout.LayoutParams(dp(70), dp(34)));

            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, dp(50));
            rLp.setMargins(0, 0, 0, dp(8));
            shell.addView(pRow, rLp);
        }

        Button closeBtn = btn("Done", new Runnable() {
            @Override
            public void run() {
                d.dismiss();
            }
        });
        closeBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, closeBtn, theme.surfaceContainerHighest, theme.onSurface, 1, theme.outlineVariant, false);
        } else {
            closeBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
            closeBtn.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(dp(120), dp(40));
        cLp.setMargins(0, dp(6), 0, 0);
        cLp.gravity = Gravity.CENTER_HORIZONTAL;
        shell.addView(closeBtn, cLp);

        d.setContentView(shell);
        d.show();

        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(520)), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
    }

    void showEditProfileDialog(final JSONObject p) {
        if (p == null) return;
        final String profileId = p.optString("id", "");
        final String curName = p.optString("name", "Profile");
        final String curAvatar = p.optString("avatar_url", "👤");
        final String curPin = p.optString("pin", "");
        final boolean isDefault = p.optBoolean("is_default", false);

        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(18), dp(22), dp(18));
        ThemeManager.styleSurfaceCard(this, shell, theme != null ? theme.surfaceContainer : surface, 14, 1, theme != null ? theme.outlineVariant : 0);

        TextView title = text("Edit Profile: " + curName, 18);
        title.setTypeface(null, 1);
        title.setTextColor(theme != null ? theme.primary : accent);
        shell.addView(title);

        TextView desc = text("Update avatar emoji, profile name, or optional PIN lock:", 13);
        desc.setTextColor(muted);
        desc.setPadding(0, dp(4), 0, dp(10));
        shell.addView(desc);

        final String[] avatars = {"🚗", "🏎️", "🍿", "🎬", "⭐", "👤", "🚀", "🎮", "👑", "🐾"};
        final String[] selectedAvatar = {curAvatar.isEmpty() ? avatars[0] : curAvatar};

        LinearLayout avatarRow = new LinearLayout(this);
        avatarRow.setOrientation(LinearLayout.HORIZONTAL);
        avatarRow.setGravity(Gravity.CENTER);
        for (final String av : avatars) {
            Button avBtn = btn(av, null);
            avBtn.setTextSize(20);
            avBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedAvatar[0] = av;
                    Toast.makeText(MainActivity.this, "Selected: " + av, Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.setMargins(dp(2), 0, dp(2), 0);
            avatarRow.addView(avBtn, lp);
        }
        shell.addView(avatarRow);

        TextView nLabel = text("PROFILE NAME", 11);
        nLabel.setTextColor(theme != null ? theme.onSurfaceVariant : muted);
        nLabel.setTypeface(null, 1);
        nLabel.setPadding(0, dp(10), 0, dp(2));
        shell.addView(nLabel);

        final EditText nameInput = new EditText(this);
        nameInput.setText(curName);
        nameInput.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        nameInput.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
        ThemeManager.styleSurfaceCard(this, nameInput, theme != null ? theme.surfaceContainerHigh : bg, 8, 1, theme != null ? theme.outlineVariant : 0);
        nameInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        nameInput.setSingleLine(true);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-1, dp(46));
        nLp.setMargins(0, 0, 0, dp(8));
        shell.addView(nameInput, nLp);

        TextView pinLabel = text("PIN LOCK (4 DIGITS - OPTIONAL)", 11);
        pinLabel.setTextColor(theme != null ? theme.onSurfaceVariant : muted);
        pinLabel.setTypeface(null, 1);
        shell.addView(pinLabel);

        final EditText pinInput = new EditText(this);
        pinInput.setHint("Leave empty to remove PIN");
        pinInput.setText(curPin);
        pinInput.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        pinInput.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
        ThemeManager.styleSurfaceCard(this, pinInput, theme != null ? theme.surfaceContainerHigh : bg, 8, 1, theme != null ? theme.outlineVariant : 0);
        pinInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        pinInput.setSingleLine(true);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, dp(46));
        pLp.setMargins(0, dp(2), 0, dp(14));
        shell.addView(pinInput, pLp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        if (!isDefault) {
            Button delBtn = btn("Delete", new Runnable() {
                @Override
                public void run() {
                    d.dismiss();
                    confirmDeleteProfile(profileId, curName);
                }
            });
            delBtn.setTextSize(13);
            if (theme != null) {
                ThemeManager.stylePillButton(MainActivity.this, delBtn, theme.errorContainer, theme.onErrorContainer, 0, 0, true);
            } else {
                delBtn.setBackgroundDrawable(shape(0xffc0392b, 8));
                delBtn.setTextColor(Color.WHITE);
            }
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, dp(42), 0.7f);
            dLp.setMargins(0, 0, dp(8), 0);
            btns.addView(delBtn, dLp);
        }

        Button cancel = btn("Cancel", new Runnable() {
            @Override
            public void run() { d.dismiss(); }
        });
        cancel.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(MainActivity.this, cancel, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
        }
        btns.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button save = btn("Save Changes", new Runnable() {
            @Override
            public void run() {
                final String newNm = nameInput.getText().toString().trim();
                final String finalName = newNm.isEmpty() ? curName : newNm;
                final String newPin = pinInput.getText().toString().trim();
                Toast.makeText(MainActivity.this, "Saving profile changes…", Toast.LENGTH_SHORT).show();

                NuvioClient.getInstance(MainActivity.this).updateProfile(profileId, finalName, selectedAvatar[0], newPin, new NuvioClient.Callback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "✓ Profile updated successfully", Toast.LENGTH_SHORT).show();
                                d.dismiss();
                                fetchNuvioProfiles();
                            }
                        });
                    }

                    @Override
                    public void onError(final String error, final int statusCode) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "⚠️ Failed to update profile: " + error, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        });
        save.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(MainActivity.this, save, theme.primary, theme.onPrimary, 0, 0, true);
        } else {
            save.setTypeface(null, 1);
            save.setBackgroundDrawable(shape(accent, 8));
            save.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(0, dp(42), 1.2f);
        svLp.setMargins(dp(8), 0, 0, 0);
        btns.addView(save, svLp);

        shell.addView(btns);
        d.setContentView(shell);
        d.show();

        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(540)), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
    }

    void confirmDeleteProfile(final String profileId, final String profileName) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(18), dp(22), dp(18));
        ThemeManager.styleSurfaceCard(this, shell, theme != null ? theme.surfaceContainer : surface, 14, 1, theme != null ? theme.outlineVariant : 0);

        TextView title = text("Delete Profile?", 18);
        title.setTypeface(null, 1);
        title.setTextColor(theme != null ? theme.error : 0xffe74c3c);
        shell.addView(title);

        TextView desc = text("Are you sure you want to permanently delete \"" + profileName + "\"? Watch progress and watchlist for this profile will be removed.", 13);
        desc.setTextColor(muted);
        desc.setPadding(0, dp(8), 0, dp(16));
        shell.addView(desc);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button cancel = btn("Keep Profile", new Runnable() {
            @Override
            public void run() { d.dismiss(); }
        });
        cancel.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(MainActivity.this, cancel, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
        }
        btns.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button del = btn("Delete Permanently", new Runnable() {
            @Override
            public void run() {
                d.dismiss();
                Toast.makeText(MainActivity.this, "Deleting profile…", Toast.LENGTH_SHORT).show();
                NuvioClient.getInstance(MainActivity.this).deleteProfile(profileId, new NuvioClient.Callback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "Profile deleted", Toast.LENGTH_SHORT).show();
                                fetchNuvioProfiles();
                            }
                        });
                    }

                    @Override
                    public void onError(final String error, final int statusCode) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "⚠️ Failed to delete: " + error, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        });
        del.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(MainActivity.this, del, theme.errorContainer, theme.onErrorContainer, 0, 0, true);
        } else {
            del.setBackgroundDrawable(shape(0xffc0392b, 8));
            del.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, dp(42), 1.2f);
        dLp.setMargins(dp(8), 0, 0, 0);
        btns.addView(del, dLp);

        shell.addView(btns);
        d.setContentView(shell);
        d.show();

        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(480)), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
    }

    void startNuvioDeviceLink() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(20), dp(24), dp(20));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        if (theme != null) {
            ThemeManager.styleSurfaceCard(this, layout, theme.surfaceContainerHighest, 16, 1, theme.outlineVariant);
        } else {
            layout.setBackgroundDrawable(shape(surface, 14));
        }

        TextView heading = text("Nuvio Remote Device Sign-In", 18);
        heading.setTypeface(null, 1);
        heading.setTextColor(theme != null ? theme.primary : accent);
        heading.setGravity(Gravity.CENTER);
        layout.addView(heading);

        final TextView statusText = text("Generating vehicle pairing code…", 13);
        statusText.setTextColor(muted);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(6), 0, dp(6));
        layout.addView(statusText);

        final TextView codeView = text("••••••", 34);
        codeView.setTypeface(null, 1);
        codeView.setTextColor(Color.WHITE);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, dp(10), 0, dp(10));
        layout.addView(codeView);

        final TextView instView = text("1. On your phone or PC, open: https://nuvio.tv/link\n2. Sign in to your Nuvio account and enter the 6-character code above.\n3. Your vehicle head unit will automatically link!", 13);
        instView.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        instView.setPadding(0, dp(4), 0, dp(14));
        instView.setGravity(Gravity.CENTER);
        layout.addView(instView);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);

        Button cancelBtn = btn("Cancel", new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
            }
        });
        cancelBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(MainActivity.this, cancelBtn, theme.surfaceContainer, theme.onSurface, 0, 0, true);
        } else {
            cancelBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
            cancelBtn.setTextColor(Color.WHITE);
        }
        btns.addView(cancelBtn, new LinearLayout.LayoutParams(dp(130), dp(40)));

        layout.addView(btns);
        dialog.setContentView(layout);
        dialog.show();

        Window win = dialog.getWindow();
        if (win != null) {
            win.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(560)), WindowManager.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.CENTER);
        }

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                NuvioClient.getInstance(MainActivity.this).cancelDeviceLinkPolling();
            }
        });

        NuvioClient.getInstance(this).generateDeviceLink(new NuvioClient.Callback<NuvioClient.DeviceLinkInfo>() {
            @Override
            public void onSuccess(final NuvioClient.DeviceLinkInfo info) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing()) return;
                        codeView.setText(info.code);
                        codeView.setTextColor(theme != null ? theme.primary : accent);
                        statusText.setText("Waiting for approval at " + info.linkUrl + "…");
                        instView.setText("1. On your phone or PC, open: " + info.linkUrl + "\n2. Sign in and enter code: " + info.code + "\n3. Your Gen5W will sync your account automatically.");

                        // Start polling Nuvio backend for authorization approval
                        NuvioClient.getInstance(MainActivity.this).pollDeviceLink(info.deviceCode, info.deviceNonce, info.intervalSec, new NuvioClient.Callback<String>() {
                            @Override
                            public void onSuccess(final String token) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        dialog.dismiss();
                                        Toast.makeText(MainActivity.this, "🎉 Vehicle linked successfully to your Nuvio account!", Toast.LENGTH_LONG).show();
                                        nuvioLoginWithToken(token);
                                    }
                                });
                            }

                            @Override
                            public void onError(final String error, final int statusCode) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (dialog.isShowing()) {
                                            statusText.setText("⚠️ " + error);
                                            statusText.setTextColor(0xffe74c3c);
                                        }
                                    }
                                });
                            }
                        });
                    }
                });
            }

            @Override
            public void onError(final String error, final int statusCode) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (dialog.isShowing()) {
                            statusText.setText("⚠️ " + error);
                            statusText.setTextColor(0xffe74c3c);
                        }
                    }
                });
            }
        });
    }

    void renderVehicleDeviceCard() {
        LinearLayout devCard = cardLayout();
        TextView devTitle = text("REGISTERED VEHICLE DEVICE", 15);
        devTitle.setTypeface(null, 1);
        devTitle.setTextColor(accent);
        devCard.addView(devTitle);

        final NuvioClient nc = NuvioClient.getInstance(this);
        String devName = nc.getDeviceName();
        String devId = nc.getDeviceId();

        TextView devStatus = text("✓ Active Automotive Device (Gen5W 1920×720)", 14);
        devStatus.setTextColor(theme != null ? theme.statusPositive : 0xff4ade80);
        devStatus.setTypeface(null, 1);
        devStatus.setPadding(0, dp(4), 0, dp(2));
        devCard.addView(devStatus);

        TextView devDetails = text("Device: " + devName + "\nID: " + devId + "\nAppears in your logged-in devices list at nuvio.tv/account?tab=devices", 12);
        devDetails.setTextColor(muted);
        devDetails.setPadding(0, dp(2), 0, dp(10));
        devCard.addView(devDetails);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Button regBtn = btn("🔄 Sync Device to Nuvio Account", new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "Registering device with Nuvio cloud…", Toast.LENGTH_SHORT).show();
                nc.registerDevice(new NuvioClient.Callback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "✓ Device registered on nuvio.tv/account", Toast.LENGTH_LONG).show();
                                render("Account", false);
                            }
                        });
                    }

                    @Override
                    public void onError(final String error, final int statusCode) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "Device sync: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
            }
        });
        regBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, regBtn, theme.primaryContainer, theme.onPrimaryContainer, 0, 0, true);
        } else {
            regBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
            regBtn.setTextColor(Color.WHITE);
        }
        row.addView(regBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        Button renameBtn = btn("✏️ Rename", new Runnable() {
            @Override
            public void run() {
                showEditDeviceNameDialog();
            }
        });
        renameBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, renameBtn, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
        } else {
            renameBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
            renameBtn.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams rnLp = new LinearLayout.LayoutParams(-2, dp(40));
        rnLp.setMargins(dp(8), 0, 0, 0);
        row.addView(renameBtn, rnLp);

        devCard.addView(row);
        body.addView(devCard);
    }

    void showEditDeviceNameDialog() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(18), dp(22), dp(18));
        ThemeManager.styleSurfaceCard(this, shell, theme != null ? theme.surfaceContainer : surface, 14, 1, theme != null ? theme.outlineVariant : 0);

        TextView title = text("Edit Vehicle Device Name", 18);
        title.setTypeface(null, 1);
        title.setTextColor(theme != null ? theme.primary : accent);
        shell.addView(title);

        TextView desc = text("This name identifies your head unit on your Nuvio account devices page (nuvio.tv/account?tab=devices):", 13);
        desc.setTextColor(muted);
        desc.setPadding(0, dp(4), 0, dp(12));
        shell.addView(desc);

        final NuvioClient nc = NuvioClient.getInstance(this);
        final EditText nameIn = new EditText(this);
        nameIn.setText(nc.getDeviceName());
        nameIn.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        nameIn.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
        ThemeManager.styleSurfaceCard(this, nameIn, theme != null ? theme.surfaceContainerHigh : bg, 8, 1, theme != null ? theme.outlineVariant : 0);
        nameIn.setPadding(dp(12), dp(10), dp(12), dp(10));
        nameIn.setSingleLine(true);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-1, dp(46));
        nLp.setMargins(0, 0, 0, dp(14));
        shell.addView(nameIn, nLp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button cancel = btn("Cancel", new Runnable() {
            @Override
            public void run() { d.dismiss(); }
        });
        cancel.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, cancel, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
        }
        btns.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button save = btn("Save & Sync", new Runnable() {
            @Override
            public void run() {
                String newNm = nameIn.getText().toString().trim();
                if (!newNm.isEmpty()) {
                    nc.setDeviceName(newNm);
                    nc.registerDevice(null);
                    Toast.makeText(MainActivity.this, "Device name updated", Toast.LENGTH_SHORT).show();
                    render("Account", false);
                }
                d.dismiss();
            }
        });
        save.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, save, theme.primary, theme.onPrimary, 0, 0, true);
        } else {
            save.setTypeface(null, 1);
            save.setBackgroundDrawable(shape(accent, 8));
            save.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(42), 1.2f);
        sLp.setMargins(dp(8), 0, 0, 0);
        btns.addView(save, sLp);

        shell.addView(btns);
        d.setContentView(shell);
        d.show();

        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(500)), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
    }

    void nuvioSignOut() {
        auth = "";
        verifiedAccountEmail = "";
        prefs.edit()
                .remove("auth")
                .remove("nuvio_email")
                .remove("nuvio_user_id")
                .remove("nuvio_refresh_token")
                .remove("nuvio_profiles")
                .remove("nuvio_active_profile_id")
                .remove("nuvio_active_profile_name")
                .remove("nuvio_active_profile_avatar")
                .apply();
        Toast.makeText(this, "Signed out of Nuvio.", Toast.LENGTH_SHORT).show();
        render("Account", false);
    }

    void debridView() {
        TextView heading = text("Debrid & Cloud Streaming", 20);
        heading.setTypeface(null, 1);
        add(heading);
        add(text("Configure Torbox or Real-Debrid API keys to stream cached torrents over high-speed HTTPS CDN.", 13));

        final String currentKey = prefs.getString("torbox_api_key", "");

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, dp(10), 0, dp(12));
        inputRow.setLayoutParams(rowLp);

        final EditText input = new EditText(this);
        input.setHint("Enter Torbox API Key (starts with tb_)...");
        input.setText(currentKey);
        input.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        input.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
        ThemeManager.styleSurfaceCard(this, input, theme != null ? theme.surfaceContainerHigh : surface, 8, 1, theme != null ? theme.outlineVariant : 0);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setSingleLine(true);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(48), 0.75f);
        inputLp.setMargins(0, 0, dp(10), 0);
        inputRow.addView(input, inputLp);

        Button saveBtn = btn("💾 Save Token", new Runnable() {
            @Override
            public void run() {
                String k = input.getText().toString().trim();
                prefs.edit().putString("torbox_api_key", k).apply();
                Toast.makeText(MainActivity.this, "Torbox API token saved successfully!", Toast.LENGTH_SHORT).show();
                render("Debrid", false);
            }
        });
        saveBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, saveBtn, theme.primary, theme.onPrimary, 0, 0, true);
        } else {
            saveBtn.setTypeface(null, 1);
            saveBtn.setBackgroundDrawable(shape(accent, 8));
            saveBtn.setTextColor(Color.WHITE);
        }
        inputRow.addView(saveBtn, new LinearLayout.LayoutParams(0, dp(48), 0.25f));

        add(inputRow);

        LinearLayout statusCard = cardLayout();
        TextView stTitle = text(currentKey.isEmpty() ? "⚠️ Torbox Debrid is Not Configured" : "✓ Torbox Debrid Configured", 16);
        stTitle.setTypeface(null, 1);
        stTitle.setTextColor(currentKey.isEmpty() ? 0xffe74c3c : 0xff2ecc71);
        statusCard.addView(stTitle);

        TextView stBody = text(currentKey.isEmpty() ?
                "Without a Debrid token, torrent streams cannot be converted into high-speed direct HTTPS links." :
                "Active Token: " + (currentKey.length() > 10 ? currentKey.substring(0, 8) + "••••••••" : currentKey), 13);
        stBody.setTextColor(muted);
        stBody.setPadding(0, dp(4), 0, dp(8));
        statusCard.addView(stBody);

        if (!currentKey.isEmpty()) {
            Button testBtn = btn("⚡ Test Torbox API Connection", new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Testing Torbox API connection…", Toast.LENGTH_SHORT).show();
                    apiWorkers.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                String res = TorboxClient.testConnection(MainActivity.this, currentKey);
                                final String msg = res;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(MainActivity.this, "Torbox OK: " + msg, Toast.LENGTH_LONG).show();
                                    }
                                });
                            } catch (final Exception e) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(MainActivity.this, "Torbox Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        }
                    });
                }
            });
            testBtn.setTextSize(12);
            if (theme == null) {
                testBtn.setBackgroundDrawable(shape(surface, 6));
                testBtn.setTextColor(accent);
            }
            statusCard.addView(testBtn, new LinearLayout.LayoutParams(-2, dp(38)));
        }
        body.addView(statusCard);
    }

    void showAddons() {
        if (isLoggedIn() && addons.length() <= 3) {
            fetchNuvioAddons();
        }
        TextView heading = text("Nuvio Add-on Manager", 20);
        heading.setTypeface(null, 1);
        add(heading);
        add(text("Community & Debrid add-ons synced with your Nuvio account or installed by URL.", 13));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, dp(10), 0, dp(14));
        inputRow.setLayoutParams(rowLp);

        final EditText urlInput = new EditText(this);
        urlInput.setHint("https://.../manifest.json (e.g. Torrentio, CyberFlix)");
        urlInput.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        urlInput.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
        ThemeManager.styleSurfaceCard(this, urlInput, theme != null ? theme.surfaceContainerHigh : surface, 8, 1, theme != null ? theme.outlineVariant : 0);
        urlInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        urlInput.setSingleLine(true);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(48), 0.75f);
        inputLp.setMargins(0, 0, dp(10), 0);
        inputRow.addView(urlInput, inputLp);

        Button installBtn = btn("＋ Install Add-on", new Runnable() {
            @Override
            public void run() {
                final String url = urlInput.getText().toString().trim();
                if (url.isEmpty()) return;
                apiWorkers.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String manifestUrl = url.endsWith("manifest.json") ? url :
                                    (url.endsWith("/") ? url + "manifest.json" : url + "/manifest.json");
                            JSONObject manifest = get(manifestUrl);
                            JSONObject entry = new JSONObject();
                            entry.put("transportUrl", manifestUrl);
                            entry.put("name", manifest.optString("name", "Custom Addon"));
                            entry.put("manifest", manifest);
                            addons.put(entry);
                            saveAddonCache();
                            if (NuvioClient.getInstance(MainActivity.this).isLoggedIn()) {
                                NuvioClient.getInstance(MainActivity.this).pushCloudAddon(
                                        manifestUrl,
                                        manifest.optString("name", "Custom Addon"),
                                        null
                                );
                            }
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Add-on installed successfully!", Toast.LENGTH_SHORT).show();
                                    render("Add-ons", false);
                                }
                            });
                        } catch (final Exception ex) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Install failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }
                });
            }
        });
        installBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, installBtn, theme.primary, theme.onPrimary, 0, 0, true);
        } else {
            installBtn.setTypeface(null, 1);
            installBtn.setBackgroundDrawable(shape(accent, 8));
            installBtn.setTextColor(Color.WHITE);
        }
        inputRow.addView(installBtn, new LinearLayout.LayoutParams(0, dp(48), 0.25f));

        add(inputRow);

        LinearLayout listHeader = new LinearLayout(this);
        listHeader.setOrientation(LinearLayout.HORIZONTAL);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        listHeader.setPadding(0, dp(8), 0, dp(6));

        TextView sub = text("Installed Add-ons (" + addons.length() + "):", 16);
        sub.setTypeface(null, 1);
        listHeader.addView(sub, new LinearLayout.LayoutParams(0, -2, 1));

        if (isLoggedIn()) {
            Button syncBtn = btn("🔄 Sync Nuvio Add-ons", new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, "Syncing add-ons from Nuvio…", Toast.LENGTH_SHORT).show();
                    fetchNuvioAddons();
                }
            });
            syncBtn.setTextSize(12);
            if (theme == null) {
                syncBtn.setBackgroundDrawable(shape(surface, 6));
                syncBtn.setTextColor(accent);
            }
            listHeader.addView(syncBtn, new LinearLayout.LayoutParams(-2, dp(34)));
        }
        add(listHeader);

        for (int i = 0; i < addons.length(); i++) {
            final int index = i;
            final JSONObject a = addons.optJSONObject(i);
            if (a == null) continue;
            JSONObject m = a.optJSONObject("manifest");
            String name = m != null ? m.optString("name", "Add-on") : a.optString("name", "Add-on");
            String desc = m != null ? m.optString("description", "") : "";
            String transport = a.optString("transportUrl", "");

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            ThemeManager.styleSurfaceCard(this, card, theme != null ? theme.surfaceContainer : surface, 8, 1, theme != null ? theme.outlineVariant : 0);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView nm = text(name, 16);
            nm.setTypeface(null, 1);
            nm.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
            info.addView(nm);

            if (!desc.isEmpty()) {
                TextView ds = text(desc, 12);
                ds.setTextColor(muted);
                ds.setMaxLines(1);
                ds.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info.addView(ds);
            }

            TextView tr = text(transport, 11);
            tr.setTextColor(accent);
            tr.setMaxLines(1);
            tr.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(tr);
            card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

            final String targetUrl = transport;
            Button remove = btn("Remove", new Runnable() {
                @Override
                public void run() {
                    JSONArray next = new JSONArray();
                    for (int k = 0; k < addons.length(); k++) {
                        if (k != index) next.put(addons.opt(k));
                    }
                    addons = next;
                    saveAddonCache();
                    if (NuvioClient.getInstance(MainActivity.this).isLoggedIn()) {
                        NuvioClient.getInstance(MainActivity.this).deleteCloudAddon(targetUrl, null);
                    }
                    render("Add-ons", false);
                }
            });
            remove.setTextSize(12);
            if (theme != null) {
                ThemeManager.stylePillButton(this, remove, theme.errorContainer, theme.onErrorContainer, 0, 0, true);
            } else {
                remove.setBackgroundDrawable(shape(0xffc0392b, 6));
                remove.setTextColor(Color.WHITE);
            }
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(84), dp(38));
            btnLp.setMargins(dp(10), 0, 0, 0);
            card.addView(remove, btnLp);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(6)); // Clear 10dp separation between cards
            body.addView(card, lp);
        }
    }

    void searchView() {
        TextView heading = text("Search Movies & TV Series", 20);
        heading.setTypeface(null, 1);
        add(heading);
        add(text("Search any title across Cinemeta and your installed add-ons.", 13));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, dp(10), 0, dp(12));
        inputRow.setLayoutParams(rowLp);

        final EditText searchInput = new EditText(this);
        searchInput.setHint("Search title (e.g. Inception, Breaking Bad, Dune)…");
        searchInput.setTextColor(theme.onSurface);
        searchInput.setHintTextColor(theme.onSurfaceVariant);
        ThemeManager.styleSurfaceCard(this, searchInput, theme.surfaceContainerHigh, 24, 1, theme.outlineVariant);
        searchInput.setPadding(dp(18), dp(10), dp(18), dp(10));
        searchInput.setSingleLine(true);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(48), 0.75f);
        inputLp.setMargins(0, 0, dp(10), 0);
        inputRow.addView(searchInput, inputLp);

        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);

        Button doSearch = btn("🔍 Search", new Runnable() {
            @Override
            public void run() {
                final String q = searchInput.getText().toString().trim();
                if (q.isEmpty()) return;
                results.removeAllViews();
                results.addView(text("Searching for '" + q + "' across movies & series…", 14));

                catalogWorkers.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String mUrl = CINEMETA + "catalog/movie/top/search=" + URLEncoder.encode(q, "UTF-8") + ".json";
                            String sUrl = CINEMETA + "catalog/series/top/search=" + URLEncoder.encode(q, "UTF-8") + ".json";
                            final JSONObject mResp = get(mUrl);
                            final JSONObject sResp = get(sUrl);

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    results.removeAllViews();
                                    JSONArray mMetas = mResp != null ? mResp.optJSONArray("metas") : null;
                                    JSONArray sMetas = sResp != null ? sResp.optJSONArray("metas") : null;
                                    boolean hasAny = false;

                                    if (mMetas != null && mMetas.length() > 0) {
                                        hasAny = true;
                                        LinearLayout mSection = new LinearLayout(MainActivity.this);
                                        mSection.setOrientation(LinearLayout.VERTICAL);
                                        mSection.setPadding(0, dp(4), 0, dp(12));

                                        TextView mHead = text("🎬 Movie Results (" + mMetas.length() + ")", 17);
                                        mHead.setTypeface(null, 1);
                                        mHead.setTextColor(accent);
                                        mSection.addView(mHead);

                                        LinearLayout mRow = new LinearLayout(MainActivity.this);
                                        mRow.setOrientation(LinearLayout.HORIZONTAL);
                                        HorizontalScrollView mScroll = new HorizontalScrollView(MainActivity.this);
                                        mScroll.setHorizontalScrollBarEnabled(false);
                                        mScroll.addView(mRow);

                                        for (int i = 0; i < mMetas.length() && i < 24; i++) {
                                            JSONObject meta = mMetas.optJSONObject(i);
                                            if (meta != null) showTitle(meta, "movie", mRow);
                                        }
                                        mSection.addView(mScroll);
                                        results.addView(mSection);
                                    }

                                    if (sMetas != null && sMetas.length() > 0) {
                                        hasAny = true;
                                        LinearLayout sSection = new LinearLayout(MainActivity.this);
                                        sSection.setOrientation(LinearLayout.VERTICAL);
                                        sSection.setPadding(0, dp(6), 0, dp(12));

                                        TextView sHead = text("📺 TV Series Results (" + sMetas.length() + ")", 17);
                                        sHead.setTypeface(null, 1);
                                        sHead.setTextColor(accent);
                                        sSection.addView(sHead);

                                        LinearLayout sRow = new LinearLayout(MainActivity.this);
                                        sRow.setOrientation(LinearLayout.HORIZONTAL);
                                        HorizontalScrollView sScroll = new HorizontalScrollView(MainActivity.this);
                                        sScroll.setHorizontalScrollBarEnabled(false);
                                        sScroll.addView(sRow);

                                        for (int i = 0; i < sMetas.length() && i < 24; i++) {
                                            JSONObject meta = sMetas.optJSONObject(i);
                                            if (meta != null) showTitle(meta, "series", sRow);
                                        }
                                        sSection.addView(sScroll);
                                        results.addView(sSection);
                                    }

                                    if (!hasAny) {
                                        results.addView(text("No matches found for '" + q + "'.", 14));
                                    }
                                }
                            });
                        } catch (final Exception e) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    results.removeAllViews();
                                    results.addView(text("Search error: " + e.getMessage(), 14));
                                }
                            });
                        }
                    }
                });
            }
        });
        doSearch.setTextSize(14);
        ThemeManager.stylePillButton(this, doSearch, theme.primary, theme.onPrimary, 0, 0, true);
        inputRow.addView(doSearch, new LinearLayout.LayoutParams(0, dp(48), 0.25f));

        add(inputRow);
        add(results);
    }

    void updateMotionMonitoring() {
        boolean enabled = prefs != null && prefs.getBoolean("motion_lockout", false);
        if (enabled) {
            if (motionGuard == null) {
                motionGuard = new MotionGuard(this, new MotionGuard.Listener() {
                    @Override
                    public void onMotionStateChanged(boolean locked, String reason) {
                        motionLocked = locked;
                        motionReason = reason;
                    }
                });
            }
            motionGuard.start();
        } else {
            if (motionGuard != null) motionGuard.stop();
            motionLocked = false;
            motionReason = "Motion lockout is off.";
        }
    }

    void settings() {
        TextView heading = text("Settings", 22);
        heading.setTypeface(null, 1);
        add(heading);
        add(text("Choose how Nuvio behaves on this Gen5W automotive unit.", 14));

        // 0. Material Design 3 Automotive Theme Selector Card
        LinearLayout themeCard = cardLayout();
        TextView themeHeader = text("Material Design 3 Theme", 18);
        themeHeader.setTypeface(null, 1);
        themeHeader.setTextColor(accent);
        themeCard.addView(themeHeader);

        TextView themeDesc = text("Select an automotive tonal palette to match your vehicle's interior lighting and display.", 13);
        themeDesc.setTextColor(muted);
        themeDesc.setPadding(0, dp(2), 0, dp(8));
        themeCard.addView(themeDesc);

        LinearLayout themeButtonsRow = new LinearLayout(this);
        themeButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView themeScroll = new HorizontalScrollView(this);
        themeScroll.setHorizontalScrollBarEnabled(false);
        themeScroll.addView(themeButtonsRow);

        for (final ThemeManager.Palette p : ThemeManager.getPalettes()) {
            final boolean isCurrent = p.id.equals(theme.id);
            Button themeBtn = btn(p.displayName, new Runnable() {
                @Override
                public void run() {
                    ThemeManager.savePalette(MainActivity.this, p);
                    loadActiveTheme();
                    render("Settings", false);
                    Toast.makeText(MainActivity.this, "Applied " + p.displayName + " theme", Toast.LENGTH_SHORT).show();
                }
            });
            ThemeManager.styleFilterChip(this, themeBtn, theme, p.displayName, isCurrent);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
            lp.setMargins(0, 0, dp(8), 0);
            themeButtonsRow.addView(themeBtn, lp);
        }
        themeCard.addView(themeScroll);
        body.addView(themeCard);

        // 1. Vehicle Motion Lockout Card
        LinearLayout motionCard = cardLayout();
        final M3Switch motion = new M3Switch(this);
        motion.setChecked(prefs.getBoolean("motion_lockout", false));
        motionCard.addView(switchRow("Vehicle Motion Lockout (GPS)", motion));

        final TextView motionStatusText = text(motion.isChecked() ? motionReason : "Motion lockout is off.", 13);
        motionStatusText.setTextColor(accent);
        motionStatusText.setPadding(0, dp(4), 0, dp(4));
        motionCard.addView(motionStatusText);

        TextView motionDesc = text("Off by default. When enabled, GPS movement at 2 mph or above pauses playback. A stable GPS fix is required; playback stays paused if GPS is unavailable.", 13);
        motionDesc.setTextColor(muted);
        motionCard.addView(motionDesc);

        motion.setOnCheckedChangeListener(new M3Switch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(M3Switch view, boolean isChecked) {
                prefs.edit().putBoolean("motion_lockout", isChecked).apply();
                updateMotionMonitoring();
                motionStatusText.setText(isChecked ? motionReason : "Motion lockout is off.");
            }
        });
        body.addView(motionCard);

        // 2. Safety Popups Card
        LinearLayout popupCard = cardLayout();
        final M3Switch popup = new M3Switch(this);
        popup.setChecked(prefs.getBoolean("disable_popup", false));
        popupCard.addView(switchRow("Disable Parked Reminder Popup", popup));

        TextView popupDesc = text("Hide the one-time parked-use reminder popup when launching Nuvio. This preference will be remembered across boots.", 13);
        popupDesc.setTextColor(muted);
        popupDesc.setPadding(0, dp(4), 0, 0);
        popupCard.addView(popupDesc);

        popup.setOnCheckedChangeListener(new M3Switch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(M3Switch view, boolean isChecked) {
                prefs.edit().putBoolean("disable_popup", isChecked).apply();
                parkedConfirmed = isChecked;
            }
        });
        body.addView(popupCard);

        // 3. Network Guard & Modem Routing Card
        LinearLayout modemCard = cardLayout();
        final SharedPreferences netPrefs = getSharedPreferences("network", 0);
        final M3Switch modemSwitch = new M3Switch(this);
        modemSwitch.setChecked(netPrefs.getBoolean("use_internal_modem", false));
        modemCard.addView(switchRow("Use Internal Car Modem", modemSwitch));

        TextView modemDesc = text("OFF (Default): Wi-Fi and Bluetooth/phone hotspots only. Protects your car's built-in Telematics data subscription.\nON: Allow Android to use the car's internal modem. The NEMESIS network guard applies rules automatically.", 13);
        modemDesc.setTextColor(muted);
        modemDesc.setPadding(0, dp(4), 0, 0);
        modemCard.addView(modemDesc);

        modemSwitch.setOnCheckedChangeListener(new M3Switch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(M3Switch view, boolean isChecked) {
                netPrefs.edit().putBoolean("use_internal_modem", isChecked).commit();
                Toast.makeText(MainActivity.this, isChecked ?
                        "Car modem requested (NEMESIS netguard removing firewall)." :
                        "Wi-Fi/Hotspot-only firewall requested.", Toast.LENGTH_LONG).show();
            }
        });
        body.addView(modemCard);

        // 4. Video Player Engine Card
        LinearLayout playerCard = cardLayout();
        TextView plTitle = text("Video Player Configuration", 17);
        plTitle.setTypeface(null, 1);
        playerCard.addView(plTitle);

        TextView plDesc = text("Engine: Standard ExoPlayer 2.14.2 (Single-DEX Native)\nHardware Decoder: OMX.TCC.avcdec (H.264 up to 1080p60 in hardware)\nAspect Ratio: 1920x720 (Auto-Scale / Fit / Crop modes available in player)", 13);
        plDesc.setTextColor(muted);
        plDesc.setPadding(0, dp(4), 0, dp(6));
        playerCard.addView(plDesc);

        final M3Switch vlcSwitch = new M3Switch(this);
        vlcSwitch.setChecked(prefs.getBoolean("vlc_integration", false));
        playerCard.addView(switchRow("Enable External VLC Player Option", vlcSwitch));

        final boolean hasVlc = isVlcInstalled();
        final TextView vlcStatusText = text(hasVlc ?
                (vlcSwitch.isChecked() ? "✓ VLC is installed. Streams will open in external VLC." : "VLC is installed on device (currently inactive).") :
                "⚠️ VLC is not installed. Run 'python tools/install_emulator.py vlc' to install.", 13);
        if (theme != null) {
            vlcStatusText.setTextColor(hasVlc ? (vlcSwitch.isChecked() ? theme.statusPositive : theme.onSurfaceVariant) : theme.statusWarning);
        } else {
            vlcStatusText.setTextColor(hasVlc ? (vlcSwitch.isChecked() ? 0xff4ade80 : muted) : 0xfffbbf24);
        }
        vlcStatusText.setPadding(0, dp(2), 0, dp(4));
        playerCard.addView(vlcStatusText);

        TextView vlcDesc = text("When enabled, selecting a stream or title automatically launches the external VLC app instead of ExoPlayer. Best for HEVC/10-bit video.", 13);
        vlcDesc.setTextColor(muted);
        playerCard.addView(vlcDesc);

        vlcSwitch.setOnCheckedChangeListener(new M3Switch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(M3Switch view, boolean isChecked) {
                prefs.edit().putBoolean("vlc_integration", isChecked).apply();
                boolean installed = isVlcInstalled();
                if (isChecked) {
                    if (installed) {
                        Toast.makeText(MainActivity.this, "External VLC Player enabled for playback.", Toast.LENGTH_SHORT).show();
                        vlcStatusText.setText("✓ VLC is installed. Streams will open in external VLC.");
                        if (theme != null) vlcStatusText.setTextColor(theme.statusPositive);
                    } else {
                        Toast.makeText(MainActivity.this, "VLC is not installed! Run: python tools/install_emulator.py vlc", Toast.LENGTH_LONG).show();
                        vlcStatusText.setText("⚠️ VLC is not installed. Run 'python tools/install_emulator.py vlc' to install.");
                        if (theme != null) vlcStatusText.setTextColor(theme.statusWarning);
                    }
                } else {
                    vlcStatusText.setText(installed ? "VLC is installed on device (currently inactive)." : "⚠️ VLC is not installed. Run 'python tools/install_emulator.py vlc' to install.");
                    if (theme != null) vlcStatusText.setTextColor(theme.onSurfaceVariant);
                }
            }
        });

        // Test streams for verifying player on emulators & car
        TextView testTitle = text("Playback Verification (Emulator & Hardware Test Streams)", 15);
        testTitle.setTypeface(null, 1);
        testTitle.setTextColor(accent);
        testTitle.setPadding(0, dp(12), 0, dp(4));
        playerCard.addView(testTitle);

        TextView testDesc = text("Tap below to test video decoding directly on this emulator or car hardware (offline clips require no internet, guaranteed no 403 errors):", 13);
        testDesc.setTextColor(muted);
        playerCard.addView(testDesc);

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.setPadding(0, dp(8), 0, dp(4));

        Button testBtn1 = btn("▶ Test H.264 (Offline Clip)", new Runnable() {
            @Override
            public void run() {
                playDirect("asset:///test_avc.mp4", "Local Test Clip (H.264 Baseline)");
            }
        });
        if (theme == null) {
            testBtn1.setBackgroundDrawable(shape(surface, 6));
            testBtn1.setTextColor(Color.WHITE);
        }
        testRow.addView(testBtn1, new LinearLayout.LayoutParams(-2, dp(38)));

        Button testBtn2 = btn("▶ Test MPEG-4 (Offline Clip)", new Runnable() {
            @Override
            public void run() {
                playDirect("asset:///test_mpeg4.mp4", "Local Test Clip (MPEG-4)");
            }
        });
        if (theme == null) {
            testBtn2.setBackgroundDrawable(shape(surface, 6));
            testBtn2.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, dp(38));
        lp2.leftMargin = dp(8);
        testRow.addView(testBtn2, lp2);

        Button testBtn3 = btn("▶ Test HLS (Apple Demo)", new Runnable() {
            @Override
            public void run() {
                playDirect("https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8", "Apple BipBop (HLS Adaptive Stream)");
            }
        });
        if (theme == null) {
            testBtn3.setBackgroundDrawable(shape(surface, 6));
            testBtn3.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(-2, dp(38));
        lp3.leftMargin = dp(8);
        testRow.addView(testBtn3, lp3);

        Button testBtn4 = btn("▶ Test VLC App", new Runnable() {
            @Override
            public void run() {
                if (isVlcInstalled()) {
                    try {
                        Intent vlcIntent = new Intent(Intent.ACTION_VIEW);
                        vlcIntent.setPackage("org.videolan.vlc");
                        vlcIntent.setDataAndType(Uri.parse("https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8"), "video/*");
                        vlcIntent.putExtra("title", "Apple BipBop (VLC Stream Test)");
                        vlcIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(vlcIntent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Failed to launch VLC: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "VLC is not installed! On your PC run: python tools/install_emulator.py vlc", Toast.LENGTH_LONG).show();
                }
            }
        });
        if (theme == null) {
            testBtn4.setBackgroundDrawable(shape(surface, 6));
            testBtn4.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(-2, dp(38));
        lp4.leftMargin = dp(8);
        testRow.addView(testBtn4, lp4);

        playerCard.addView(testRow);
        body.addView(playerCard);

        // 5. Cloud Profile Playback Preferences Card
        LinearLayout prefCard = cardLayout();
        TextView prefTitle = text("Cloud Profile Preferences", 17);
        prefTitle.setTypeface(null, 1);
        prefTitle.setTextColor(accent);
        prefCard.addView(prefTitle);

        TextView prefDesc = text("Set default audio language, subtitle preferences, and streaming quality for this Nuvio profile.", 13);
        prefDesc.setTextColor(muted);
        prefDesc.setPadding(0, dp(4), 0, dp(10));
        prefCard.addView(prefDesc);

        // Audio Language Row
        TextView audioLbl = text("Preferred Audio Language", 14);
        audioLbl.setTypeface(null, 1);
        prefCard.addView(audioLbl);

        final String[] audioLangs = {"Default / Auto", "English", "Spanish", "French", "German", "Italian", "Japanese", "Korean", "Hindi"};
        final String[] audioCodes = {"auto", "en", "es", "fr", "de", "it", "ja", "ko", "hi"};
        final String curAudio = prefs.getString("pref_audio_lang", "auto");

        LinearLayout audioRow = new LinearLayout(this);
        audioRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView audioScroll = new HorizontalScrollView(this);
        audioScroll.setHorizontalScrollBarEnabled(false);
        audioScroll.addView(audioRow);

        for (int i = 0; i < audioLangs.length; i++) {
            final String code = audioCodes[i];
            final String name = audioLangs[i];
            final boolean isCur = curAudio.equals(code);
            Button aBtn = btn(name, new Runnable() {
                @Override
                public void run() {
                    prefs.edit().putString("pref_audio_lang", code).apply();
                    syncCloudProfileSettings();
                    render("Settings", false);
                    Toast.makeText(MainActivity.this, "Preferred audio: " + name, Toast.LENGTH_SHORT).show();
                }
            });
            ThemeManager.styleFilterChip(this, aBtn, theme, name, isCur);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMargins(0, 0, dp(8), 0);
            audioRow.addView(aBtn, lp);
        }
        prefCard.addView(audioScroll);

        // Subtitle Language Row
        TextView subLbl = text("Preferred Subtitle Language", 14);
        subLbl.setTypeface(null, 1);
        subLbl.setPadding(0, dp(12), 0, 0);
        prefCard.addView(subLbl);

        final String[] subLangs = {"Off", "English", "Spanish", "French", "German", "Italian", "Japanese", "Korean", "Hindi"};
        final String[] subCodes = {"off", "en", "es", "fr", "de", "it", "ja", "ko", "hi"};
        final String curSub = prefs.getString("pref_subtitle_lang", "off");

        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView subScroll = new HorizontalScrollView(this);
        subScroll.setHorizontalScrollBarEnabled(false);
        subScroll.addView(subRow);

        for (int i = 0; i < subLangs.length; i++) {
            final String code = subCodes[i];
            final String name = subLangs[i];
            final boolean isCur = curSub.equals(code);
            Button sBtn = btn(name, new Runnable() {
                @Override
                public void run() {
                    prefs.edit().putString("pref_subtitle_lang", code).apply();
                    syncCloudProfileSettings();
                    render("Settings", false);
                    Toast.makeText(MainActivity.this, "Preferred subtitles: " + name, Toast.LENGTH_SHORT).show();
                }
            });
            ThemeManager.styleFilterChip(this, sBtn, theme, name, isCur);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMargins(0, 0, dp(8), 0);
            subRow.addView(sBtn, lp);
        }
        prefCard.addView(subScroll);

        // Quality Filter Row
        TextView qLbl = text("Default Stream Quality Filter", 14);
        qLbl.setTypeface(null, 1);
        qLbl.setPadding(0, dp(12), 0, 0);
        prefCard.addView(qLbl);

        final String[] qualities = {"All", "H.264 (HW)", "720p", "480p", "1080p"};
        final String curQ = prefs.getString("pref_stream_quality", "All");

        LinearLayout qRow = new LinearLayout(this);
        qRow.setOrientation(LinearLayout.HORIZONTAL);
        for (final String q : qualities) {
            final boolean isCur = curQ.equals(q);
            Button qBtn = btn(q, new Runnable() {
                @Override
                public void run() {
                    prefs.edit().putString("pref_stream_quality", q).apply();
                    sourceFilter = q;
                    syncCloudProfileSettings();
                    render("Settings", false);
                    Toast.makeText(MainActivity.this, "Quality filter: " + q, Toast.LENGTH_SHORT).show();
                }
            });
            ThemeManager.styleFilterChip(this, qBtn, theme, q, isCur);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            qRow.addView(qBtn, lp);
        }
        prefCard.addView(qRow);

        body.addView(prefCard);

        // 6. Storage & Clear Cache Card
        LinearLayout storageCard = cardLayout();
        TextView stTitle = text("Storage & Cache", 17);
        stTitle.setTypeface(null, 1);
        storageCard.addView(stTitle);

        final TextView stSizeText = text("Calculating cache size…", 14);
        stSizeText.setTextColor(accent);
        stSizeText.setPadding(0, dp(4), 0, dp(2));
        storageCard.addView(stSizeText);

        final Runnable updateSizeRunnable = new Runnable() {
            @Override
            public void run() {
                long posterBytes = getFolderSize(new File(getCacheDir(), "nuvio_posters"));
                long avatarBytes = getFolderSize(new File(getCacheDir(), "nuvio_avatars"));
                long totalBytes = getFolderSize(getCacheDir());
                float totalMb = totalBytes / (1024f * 1024f);
                float posterMb = posterBytes / (1024f * 1024f);
                float avatarMb = avatarBytes / (1024f * 1024f);

                stSizeText.setText(String.format(Locale.US, "Cache Used: %.2f MB  (Posters: %.2f MB • Avatars: %.2f MB)",
                        totalMb, posterMb, avatarMb));
            }
        };
        updateSizeRunnable.run();

        TextView stDesc = text("Clear temporary cached poster graphics, avatars, stream choices, and catalog data to free storage.", 13);
        stDesc.setTextColor(muted);
        stDesc.setPadding(0, dp(4), 0, dp(8));
        storageCard.addView(stDesc);

        Button clearBtn = btn("🗑 Clear Image & Catalog Cache", new Runnable() {
            @Override
            public void run() {
                catalogMemory.clear();
                streamMemory.clear();
                memoryPosters.evictAll();
                avatarMemory.evictAll();
                clearDiskCache("nuvio_posters");
                clearDiskCache("nuvio_avatars");
                updateSizeRunnable.run();
                Toast.makeText(MainActivity.this, "Memory & disk image cache cleared!", Toast.LENGTH_SHORT).show();
            }
        });
        clearBtn.setTextSize(13);
        if (theme == null) {
            clearBtn.setBackgroundDrawable(shape(surface, 6));
            clearBtn.setTextColor(accent);
        }
        storageCard.addView(clearBtn, new LinearLayout.LayoutParams(-2, dp(40)));
        body.addView(storageCard);
    }

    long getFolderSize(File f) {
        if (f == null || !f.exists()) return 0L;
        if (f.isFile()) return f.length();
        long size = 0L;
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    size += getFolderSize(child);
                } else {
                    size += child.length();
                }
            }
        }
        return size;
    }

    void clearDiskCache(String subDir) {
        try {
            File dir = new File(getCacheDir(), subDir);
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) f.delete();
                }
            }
        } catch (Exception ignored) {}
    }

    void infoView() {
        TextView heading = text("System & Network Diagnostics", 22);
        heading.setTypeface(null, 1);
        add(heading);
        add(text("Diagnostic indicators and system information for troubleshooting.", 14));

        // 1. Build and Platform Card
        LinearLayout sysCard = cardLayout();
        TextView sTitle = text("SYSTEM PLATFORM", 16);
        sTitle.setTypeface(null, 1);
        sTitle.setTextColor(accent);
        sysCard.addView(sTitle);

        String telemetryInfo = (vehicleGate != null) ? vehicleGate.getTelemetryStatus() : "N/A";
        String buildInfo = "App: Nuvio Auto v1.0.6 (Native KitKat single-DEX)\n" +
                "Author: Saurabh Nakkarike (@snakkarike)\n" +
                "OS: Android 4.4.2 KitKat (API 19)\n" +
                "Architecture: " + android.os.Build.CPU_ABI + "\n" +
                "Display Resolution: 1920x720 widescreen (10.25\" automotive display)\n" +
                "Hardware Codec: OMX.TCC.avcdec (Telechips Hardware VPU)\n" +
                "Codec Patch: ACTIVE (gen5-codec-patch installed)\n" +
                "Security: Mozilla CA Root Trust + TLS 1.2 Socket Factory\n" +
                "Vehicle Telemetry: " + telemetryInfo;
        TextView sBody = text(buildInfo, 13);
        sBody.setTextColor(muted);
        sBody.setPadding(0, dp(4), 0, 0);
        sysCard.addView(sBody);
        body.addView(sysCard);

        // 2. Active Network Information Card
        LinearLayout netCard = cardLayout();
        TextView nTitle = text("ACTIVE NETWORK INTERFACE", 16);
        nTitle.setTypeface(null, 1);
        nTitle.setTextColor(accent);
        netCard.addView(nTitle);

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        String netState = "none";
        try {
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            if (ni != null) netState = ni.getTypeName() + " (" + ni.getState() + ")";
        } catch (Exception e) {
            netState = e.getMessage();
        }
        TextView nBody = text("Active Route: " + netState + "\n" +
                "Internal Modem Rule: " + (getSharedPreferences("network", 0).getBoolean("use_internal_modem", false) ? "ON (Allow car modem)" : "OFF (Wi-Fi/Hotspot Only)") + "\n" +
                "NEMESIS Netguard: /system/xbin/netguard active", 13);
        nBody.setTextColor(muted);
        nBody.setPadding(0, dp(4), 0, dp(8));
        netCard.addView(nBody);

        final TextView ipText = text("Public IP: Tap below to test internet routing…", 13);
        ipText.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        netCard.addView(ipText);

        Button checkIp = btn("🌐 Test Public IP & DNS Route", new Runnable() {
            @Override
            public void run() {
                ipText.setText("Testing public route via HTTPS…");
                apiWorkers.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject ipObj = get("https://api.ipify.org?format=json");
                            final String ip = ipObj != null ? ipObj.optString("ip", "unknown") : "unknown";
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    ipText.setText("Public IP: " + ip + " (HTTPS Connected OK)");
                                }
                            });
                        } catch (final Exception e) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    ipText.setText("Route Test Error: " + e.getMessage());
                                }
                            });
                        }
                    }
                });
            }
        });
        checkIp.setTextSize(12);
        if (theme == null) {
            checkIp.setBackgroundDrawable(shape(surface, 6));
            checkIp.setTextColor(accent);
        }
        netCard.addView(checkIp, new LinearLayout.LayoutParams(-2, dp(38)));
        body.addView(netCard);

        // 3. API Reachability Test Card
        LinearLayout apiCard = cardLayout();
        TextView aTitle = text("STREAMING API DIAGNOSTICS", 16);
        aTitle.setTypeface(null, 1);
        aTitle.setTextColor(accent);
        apiCard.addView(aTitle);

        final TextView apiStatus = text("Cinemeta: Ready • Torbox: " + (prefs.getString("torbox_api_key", "").isEmpty() ? "No Token" : "Token Saved"), 13);
        apiStatus.setTextColor(muted);
        apiStatus.setPadding(0, dp(4), 0, dp(8));
        apiCard.addView(apiStatus);

        LinearLayout apiBtns = new LinearLayout(this);
        apiBtns.setOrientation(LinearLayout.HORIZONTAL);

        Button testCinemeta = btn("Test Cinemeta HTTPS", new Runnable() {
            @Override
            public void run() {
                apiStatus.setText("Pinging Cinemeta manifest…");
                apiWorkers.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final JSONObject m = get(CINEMETA + "manifest.json");
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    apiStatus.setText("Cinemeta OK: " + (m != null ? m.optString("name", "Connected") : "OK"));
                                }
                            });
                        } catch (final Exception e) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    apiStatus.setText("Cinemeta Error: " + e.getMessage());
                                }
                            });
                        }
                    }
                });
            }
        });
        testCinemeta.setTextSize(12);
        if (theme == null) {
            testCinemeta.setBackgroundDrawable(shape(surface, 6));
            testCinemeta.setTextColor(accent);
        }
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(-2, dp(38));
        lp1.setMargins(0, 0, dp(8), 0);
        apiBtns.addView(testCinemeta, lp1);

        Button testNuvio = btn("Test Nuvio API", new Runnable() {
            @Override
            public void run() {
                apiStatus.setText("Testing Nuvio API server (" + getNuvioApiUrl() + ")…");
                apiWorkers.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            URL url = new URL(getNuvioApiUrl() + "/auth/v1/settings");
                            HttpURLConnection c = (HttpURLConnection) url.openConnection();
                            if (c instanceof HttpsURLConnection) {
                                ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(MainActivity.this));
                            }
                            c.setRequestProperty("apikey", NUVIO_ANON_KEY);
                            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.6");
                            c.setConnectTimeout(8000);
                            int code = c.getResponseCode();
                            c.disconnect();
                            final int finalCode = code;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    apiStatus.setText("Nuvio API reachable: HTTP " + finalCode + " (OK)");
                                }
                            });
                        } catch (final Exception e) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    apiStatus.setText("Nuvio API Error: " + e.getMessage());
                                }
                            });
                        }
                    }
                });
            }
        });
        testNuvio.setTextSize(12);
        if (theme == null) {
            testNuvio.setBackgroundDrawable(shape(surface, 6));
            testNuvio.setTextColor(accent);
        }
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-2, dp(38));
        lp2.setMargins(0, 0, dp(8), 0);
        apiBtns.addView(testNuvio, lp2);

        apiCard.addView(apiBtns);
        body.addView(apiCard);

        // 4. Failed Requests Log
        if (!errors.isEmpty()) {
            LinearLayout errCard = cardLayout();
            TextView eTitle = text("RECENT EVENT & ERROR LOGS (" + errors.size() + ")", 15);
            eTitle.setTypeface(null, 1);
            eTitle.setTextColor(0xffe74c3c);
            errCard.addView(eTitle);

            for (String err : errors) {
                TextView et = text("• " + err, 12);
                et.setTextColor(muted);
                errCard.addView(et);
            }
            body.addView(errCard);
        }

        // 5. Open Source Licenses & Notices Card
        LinearLayout licenseCard = cardLayout();
        TextView licTitle = text("OPEN SOURCE LICENSES & NOTICES", 16);
        licTitle.setTypeface(null, 1);
        licTitle.setTextColor(accent);
        licenseCard.addView(licTitle);

        TextView licDesc = text("Nuvio Auto is built with open-source libraries and components:\n\n" +
                "• ExoPlayer 2.14.2 — Apache License 2.0 (Google LLC)\n" +
                "• Conscrypt Android 2.5.2 — Apache License 2.0 (Google Inc. / OpenSSL)\n" +
                "• Mozilla Included Root CAs — Mozilla Public License 2.0 (Mozilla Foundation)\n" +
                "• Gen5 Automotive Test Clips — MIT License (gen5-codec-patch)\n" +
                "• Nuvio Auto Client — GNU General Public License v3.0", 13);
        licDesc.setTextColor(muted);
        licDesc.setPadding(0, dp(4), 0, dp(8));
        licenseCard.addView(licDesc);

        Button showLicBtn = btn("📜 View Full License Notices", new Runnable() {
            @Override
            public void run() {
                showLicensesDialog();
            }
        });
        showLicBtn.setTextSize(12);
        if (theme == null) {
            showLicBtn.setBackgroundDrawable(shape(surface, 6));
            showLicBtn.setTextColor(Color.WHITE);
        }
        licenseCard.addView(showLicBtn, new LinearLayout.LayoutParams(-2, dp(38)));
        body.addView(licenseCard);
    }

    void showLicensesDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Open Source Licenses");
        ScrollView sv = new ScrollView(this);
        sv.setPadding(dp(16), dp(10), dp(16), dp(10));
        if (theme != null) {
            sv.setBackgroundColor(theme.surfaceContainer);
        }
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        tv.setText(
            "Nuvio Auto (Nuvio on GEN5W)\n" +
            "Copyright (c) 2026 Saurabh Nakkarike (https://github.com/snakkarike)\n" +
            "Licensed under the GNU General Public License v3.0 (GPL-3.0).\n\n" +
            "=====================================================\n" +
            "ExoPlayer (exoplayer-core, exoplayer-ui, exoplayer-hls)\n" +
            "Copyright (C) 2014-2021 Google LLC\n" +
            "Licensed under the Apache License, Version 2.0.\n\n" +
            "=====================================================\n" +
            "Conscrypt Android (TLS 1.2 / BoringSSL)\n" +
            "Copyright (C) 2017 Google Inc. / Android Open Source Project\n" +
            "Copyright (C) 1998-2016 The OpenSSL Project\n" +
            "Licensed under the Apache License, Version 2.0.\n\n" +
            "=====================================================\n" +
            "Mozilla Included Root CA Certificates (cacert.pem)\n" +
            "Copyright Mozilla Foundation\n" +
            "Licensed under the Mozilla Public License, Version 2.0 (MPL 2.0).\n\n" +
            "=====================================================\n" +
            "Gen5 Codec Test Clips\n" +
            "Copyright (C) 2023-2026 gen5-codec-patch contributors\n" +
            "Licensed under the MIT License."
        );
        sv.addView(tv);
        builder.setView(sv);
        builder.setPositiveButton("Close", null);
        builder.show();
    }

    void accountView() {
        TextView heading = text("Nuvio Account", 22);
        heading.setTypeface(null, 1);
        add(heading);

        final String userEmail = verifiedAccountEmail.isEmpty() ? prefs.getString("nuvio_email", "") : verifiedAccountEmail;
        final boolean loggedIn = isLoggedIn();

        if (!loggedIn) {
            add(text("Sign in with your Nuvio account to access your user profiles, sync your watchlist, and resume playback across devices.", 13));

            if (!auth.isEmpty() && !isNuvioToken(auth)) {
                LinearLayout legacyNotice = cardLayout();
                legacyNotice.setBackgroundDrawable(shape(0xff2d1a18, 8));
                TextView lTitle = text("⚠️ STREMIO TOKEN DETECTED", 15);
                lTitle.setTypeface(null, 1);
                lTitle.setTextColor(0xffe74c3c);
                legacyNotice.addView(lTitle);
                TextView lDesc = text("You were previously signed in with Stremio. Nuvio user profiles and continue-watching sync require a Nuvio account (api.nuvio.tv). Please sign in with your Nuvio credentials below.", 13);
                lDesc.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
                lDesc.setPadding(0, dp(4), 0, 0);
                legacyNotice.addView(lDesc);
                body.addView(legacyNotice);
            }

            // Card 1: Nuvio Server URL
            LinearLayout serverCard = cardLayout();
            TextView sHead = text("NUVIO SERVER URL", 15);
            sHead.setTypeface(null, 1);
            sHead.setTextColor(accent);
            serverCard.addView(sHead);

            LinearLayout sRow = new LinearLayout(this);
            sRow.setOrientation(LinearLayout.HORIZONTAL);
            sRow.setGravity(Gravity.CENTER_VERTICAL);
            sRow.setPadding(0, dp(6), 0, 0);

            final EditText serverIn = new EditText(this);
            serverIn.setText(getNuvioApiUrl());
            serverIn.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
            serverIn.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
            ThemeManager.styleSurfaceCard(this, serverIn, theme != null ? theme.surfaceContainerHigh : bg, 6, 1, theme != null ? theme.outlineVariant : 0);
            serverIn.setPadding(dp(12), dp(8), dp(12), dp(8));
            serverIn.setSingleLine(true);
            sRow.addView(serverIn, new LinearLayout.LayoutParams(0, dp(44), 0.75f));

            Button resetUrl = btn("Default", new Runnable() {
                @Override
                public void run() {
                    serverIn.setText(DEFAULT_NUVIO_API);
                    prefs.edit().putString("nuvio_api_url", DEFAULT_NUVIO_API).apply();
                    Toast.makeText(MainActivity.this, "Reset to official Nuvio server", Toast.LENGTH_SHORT).show();
                }
            });
            resetUrl.setTextSize(12);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, dp(44), 0.25f);
            rLp.setMargins(dp(8), 0, 0, 0);
            sRow.addView(resetUrl, rLp);
            serverCard.addView(sRow);
            body.addView(serverCard);

            // Card 2: Remote Device Sign In (No Typing)
            LinearLayout remoteCard = cardLayout();
            TextView rHeading = text("REMOTE DEVICE SIGN-IN (NO TYPING)", 16);
            rHeading.setTypeface(null, 1);
            rHeading.setTextColor(accent);
            remoteCard.addView(rHeading);

            TextView rPrompt = text("Sign in from your smartphone or computer without typing passwords on the in-car screen:", 13);
            rPrompt.setTextColor(muted);
            rPrompt.setPadding(0, dp(4), 0, dp(10));
            remoteCard.addView(rPrompt);

            Button linkBtn = btn("📱 Link Device via nuvio.tv/link (QR & PIN)", new Runnable() {
                @Override
                public void run() {
                    startNuvioDeviceLink();
                }
            });
            linkBtn.setTextSize(14);
            if (theme != null) {
                ThemeManager.stylePillButton(this, linkBtn, theme.primary, theme.onPrimary, 0, 0, true);
            } else {
                linkBtn.setTypeface(null, 1);
                linkBtn.setBackgroundDrawable(shape(accent, 8));
                linkBtn.setTextColor(Color.WHITE);
            }
            remoteCard.addView(linkBtn, new LinearLayout.LayoutParams(-1, dp(46)));
            body.addView(remoteCard);

            // Card 3: Nuvio Email / Password Sign In
            LinearLayout loginCard = cardLayout();
            TextView lHeading = text("SIGN IN WITH NUVIO CREDENTIALS", 16);
            lHeading.setTypeface(null, 1);
            lHeading.setTextColor(accent);
            loginCard.addView(lHeading);

            TextView prompt = text("Enter your Nuvio account email and password:", 13);
            prompt.setTextColor(muted);
            prompt.setPadding(0, dp(4), 0, dp(10));
            loginCard.addView(prompt);

            final EditText emailIn = new EditText(this);
            emailIn.setHint("Email (e.g. user@nuvio.tv)…");
            emailIn.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
            emailIn.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
            ThemeManager.styleSurfaceCard(this, emailIn, theme != null ? theme.surfaceContainerHigh : bg, 6, 1, theme != null ? theme.outlineVariant : 0);
            emailIn.setPadding(dp(14), dp(10), dp(14), dp(10));
            emailIn.setSingleLine(true);
            LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(-1, dp(46));
            eLp.setMargins(0, 0, 0, dp(8));
            loginCard.addView(emailIn, eLp);

            final EditText passIn = new EditText(this);
            passIn.setHint("Password…");
            passIn.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
            passIn.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
            passIn.setInputType(129); // textPassword
            ThemeManager.styleSurfaceCard(this, passIn, theme != null ? theme.surfaceContainerHigh : bg, 6, 1, theme != null ? theme.outlineVariant : 0);
            passIn.setPadding(dp(14), dp(10), dp(14), dp(10));
            passIn.setSingleLine(true);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, dp(46));
            pLp.setMargins(0, 0, 0, dp(12));
            loginCard.addView(passIn, pLp);

            Button loginBtn = btn("Sign In to Nuvio", new Runnable() {
                @Override
                public void run() {
                    String sUrl = serverIn.getText().toString().trim();
                    if (!sUrl.isEmpty()) prefs.edit().putString("nuvio_api_url", sUrl).apply();
                    final String em = emailIn.getText().toString().trim();
                    final String pw = passIn.getText().toString().trim();
                    if (em.isEmpty() || pw.isEmpty()) {
                        Toast.makeText(MainActivity.this, "Please enter email and password", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(MainActivity.this, "Signing in to Nuvio…", Toast.LENGTH_SHORT).show();
                    nuvioLogin(em, pw);
                }
            });
            loginBtn.setTextSize(14);
            if (theme != null) {
                ThemeManager.stylePillButton(this, loginBtn, theme.primary, theme.onPrimary, 0, 0, true);
            } else {
                loginBtn.setTypeface(null, 1);
                loginBtn.setBackgroundDrawable(shape(accent, 8));
                loginBtn.setTextColor(Color.WHITE);
            }
            loginCard.addView(loginBtn, new LinearLayout.LayoutParams(-1, dp(46)));
            body.addView(loginCard);

            // Card 3: Direct Token Sign In
            LinearLayout tokenCard = cardLayout();
            TextView tTitle = text("SIGN IN WITH AUTH TOKEN", 15);
            tTitle.setTypeface(null, 1);
            tTitle.setTextColor(accent);
            tokenCard.addView(tTitle);

            TextView tDesc = text("If you signed in via browser or OAuth on another device, paste your Nuvio JWT token below:", 13);
            tDesc.setTextColor(muted);
            tDesc.setPadding(0, dp(4), 0, dp(8));
            tokenCard.addView(tDesc);

            LinearLayout tRow = new LinearLayout(this);
            tRow.setOrientation(LinearLayout.HORIZONTAL);
            tRow.setGravity(Gravity.CENTER_VERTICAL);

            final EditText tokenIn = new EditText(this);
            tokenIn.setHint("Paste Nuvio JWT Token (eyJhbGciOi...)");
            tokenIn.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
            tokenIn.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
            ThemeManager.styleSurfaceCard(this, tokenIn, theme != null ? theme.surfaceContainerHigh : bg, 6, 1, theme != null ? theme.outlineVariant : 0);
            tokenIn.setPadding(dp(12), dp(8), dp(12), dp(8));
            tokenIn.setSingleLine(true);
            tRow.addView(tokenIn, new LinearLayout.LayoutParams(0, dp(44), 0.75f));

            Button saveTokenBtn = btn("Save Token", new Runnable() {
                @Override
                public void run() {
                    String t = tokenIn.getText().toString().trim();
                    if (t.isEmpty()) {
                        Toast.makeText(MainActivity.this, "Please paste an auth token", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    nuvioLoginWithToken(t);
                }
            });
            saveTokenBtn.setTextSize(12);
            if (theme != null) {
                ThemeManager.stylePillButton(this, saveTokenBtn, theme.primary, theme.onPrimary, 0, 0, true);
            } else {
                saveTokenBtn.setTypeface(null, 1);
                saveTokenBtn.setBackgroundDrawable(shape(accent, 6));
                saveTokenBtn.setTextColor(Color.WHITE);
            }
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0, dp(44), 0.25f);
            stLp.setMargins(dp(8), 0, 0, 0);
            tRow.addView(saveTokenBtn, stLp);
            tokenCard.addView(tRow);
            body.addView(tokenCard);

            // Card 4: Guest Mode Info
            LinearLayout guestCard = cardLayout();
            TextView gTitle = text("GUEST / NO ACCOUNT MODE", 15);
            gTitle.setTypeface(null, 1);
            gTitle.setTextColor(muted);
            guestCard.addView(gTitle);

            TextView gDesc = text("An account is only required for syncing watch progress across devices and multi-user profile switching. Browsing catalogs and streaming via Debrid works without an account.", 13);
            gDesc.setTextColor(muted);
            gDesc.setPadding(0, dp(4), 0, 0);
            guestCard.addView(gDesc);
            body.addView(guestCard);

            renderTraktCard();
            return;
        }

        // --- USER IS LOGGED IN ---
        add(text("Signed in as " + userEmail + " • Select or switch your Nuvio profile below.", 13));

        JSONObject active = getActiveNuvioProfile();
        String fallbackName = !userEmail.isEmpty() ? (userEmail.contains("@") ? userEmail.split("@")[0] : userEmail) : "My Profile";
        String activeName = active != null ? active.optString("name", prefs.getString("nuvio_active_profile_name", fallbackName)) : prefs.getString("nuvio_active_profile_name", fallbackName);
        String activeAvatar = active != null ? resolveAvatarUrl(active) : resolveAvatarUrl(prefs.getString("nuvio_active_profile_avatar", ""));
        String activeId = active != null ? active.optString("id", "") : prefs.getString("nuvio_active_profile_id", "");

        // 1. Active Profile Card
        LinearLayout currentCard = cardLayout();
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout avBox = new FrameLayout(this);
        if (theme != null) {
            ThemeManager.styleSurfaceCard(this, avBox, theme.primaryContainer, 28, 2, theme.primary);
        } else {
            avBox.setBackgroundDrawable(circleShape(surface, accent, 2));
        }

        ImageView avImg = new ImageView(this);
        avImg.setScaleType(ImageView.ScaleType.FIT_CENTER);

        TextView avFallback = text(!activeAvatar.startsWith("http") && !activeAvatar.isEmpty() ? activeAvatar : (!activeName.isEmpty() ? activeName.substring(0, 1).toUpperCase(Locale.US) : "👤"), 22);
        avFallback.setGravity(Gravity.CENTER);
        avFallback.setTextColor(theme != null ? theme.onPrimaryContainer : accent);
        avFallback.setTypeface(null, 1);
        avBox.addView(avFallback, new FrameLayout.LayoutParams(-1, -1));
        avBox.addView(avImg, new FrameLayout.LayoutParams(dp(56), dp(56)));
        avImg.setTag(avFallback);

        if (activeAvatar.startsWith("http")) {
            loadAvatar(avImg, activeAvatar);
        }

        LinearLayout.LayoutParams avBoxLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        avBoxLp.setMargins(0, 0, dp(14), 0);
        headerRow.addView(avBox, avBoxLp);

        LinearLayout nameCol = new LinearLayout(this);
        nameCol.setOrientation(LinearLayout.VERTICAL);

        TextView pTitle = text(activeName, 20);
        pTitle.setTypeface(null, 1);
        pTitle.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        nameCol.addView(pTitle);

        TextView pSub = text("Active Nuvio Profile on this Head Unit", 13);
        pSub.setTextColor(theme != null ? theme.onSurfaceVariant : accent);
        nameCol.addView(pSub);

        headerRow.addView(nameCol, new LinearLayout.LayoutParams(0, -2, 1));

        if (theme != null) {
            TextView actBadge = badge("✓ ACTIVE", theme.primaryContainer, theme.onPrimaryContainer, 0);
            actBadge.setTextSize(12);
            actBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
            headerRow.addView(actBadge, new LinearLayout.LayoutParams(-2, -2));
        }
        currentCard.addView(headerRow);
        body.addView(currentCard);

        // 2. Profile Switcher Carousel
        LinearLayout switchCard = cardLayout();
        LinearLayout swHdrRow = new LinearLayout(this);
        swHdrRow.setOrientation(LinearLayout.HORIZONTAL);
        swHdrRow.setGravity(Gravity.CENTER_VERTICAL);
        swHdrRow.setPadding(0, 0, 0, dp(8));

        TextView swHeading = text("SWITCH NUVIO PROFILE", 15);
        swHeading.setTypeface(null, 1);
        swHeading.setTextColor(accent);
        swHdrRow.addView(swHeading, new LinearLayout.LayoutParams(0, -2, 1));

        Button manageProfBtn = btn("Manage Profiles", new Runnable() {
            @Override
            public void run() {
                showManageProfilesDialog();
            }
        });
        manageProfBtn.setTextSize(12);
        if (theme != null) {
            ThemeManager.stylePillButton(this, manageProfBtn, theme.surfaceContainerHighest, theme.onSurface, 1, theme.outlineVariant, false);
        } else {
            manageProfBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
            manageProfBtn.setTextColor(Color.WHITE);
        }
        swHdrRow.addView(manageProfBtn, new LinearLayout.LayoutParams(-2, dp(32)));
        switchCard.addView(swHdrRow);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final JSONArray allProfiles = getNuvioProfiles();
        if (allProfiles.length() == 0) {
            TextView emptyText = text("No Nuvio profiles synced yet. Tap '🔄 Sync Nuvio Profiles' below.", 13);
            emptyText.setTextColor(muted);
            emptyText.setPadding(0, dp(6), dp(12), dp(6));
            row.addView(emptyText, new LinearLayout.LayoutParams(-2, -2));
        }
        for (int i = 0; i < allProfiles.length(); i++) {
            final JSONObject p = allProfiles.optJSONObject(i);
            if (p == null) continue;
            final String pId = p.optString("id");
            final String pNm = p.optString("name", "Profile");
            final String pAv = resolveAvatarUrl(p);
            final boolean isCurrent = pId.equals(activeId);

            final LinearLayout pCard = new LinearLayout(this);
            pCard.setOrientation(LinearLayout.VERTICAL);
            pCard.setGravity(Gravity.CENTER);
            pCard.setPadding(dp(16), dp(16), dp(16), dp(16));
            pCard.setClickable(true);
            pCard.setFocusable(true);
            if (theme != null) {
                if (isCurrent) {
                    ThemeManager.styleInteractiveCard(this, pCard, theme.primaryContainer, theme.primary, 14, 2, theme.primary);
                } else {
                    ThemeManager.styleInteractiveCard(this, pCard, theme.surfaceContainerHigh, theme.onSurface, 14, 1, theme.outlineVariant);
                }
            } else {
                pCard.setBackgroundDrawable(shape(isCurrent ? 0xff2a1a3e : surface, 14));
            }

            FrameLayout pAvBox = new FrameLayout(this);
            if (theme != null) {
                ThemeManager.styleSurfaceCard(this, pAvBox, isCurrent ? theme.primary : theme.surfaceContainerHighest, 26, isCurrent ? 2 : 1, isCurrent ? theme.onPrimary : theme.outlineVariant);
            } else {
                pAvBox.setBackgroundDrawable(circleShape(isCurrent ? 0xff241436 : surface, isCurrent ? accent : 0xff232b38, isCurrent ? 2 : 1));
            }

            ImageView pAvImg = new ImageView(this);
            pAvImg.setScaleType(ImageView.ScaleType.FIT_CENTER);

            TextView pAvFallback = text(!pAv.startsWith("http") && !pAv.isEmpty() ? pAv : (!pNm.isEmpty() ? pNm.substring(0, 1).toUpperCase(Locale.US) : "👤"), 20);
            pAvFallback.setGravity(Gravity.CENTER);
            if (theme != null) {
                pAvFallback.setTextColor(isCurrent ? theme.onPrimary : theme.onSurface);
            } else {
                pAvFallback.setTextColor(isCurrent ? accent : Color.WHITE);
            }
            pAvFallback.setTypeface(null, 1);
            pAvBox.addView(pAvFallback, new FrameLayout.LayoutParams(-1, -1));
            pAvBox.addView(pAvImg, new FrameLayout.LayoutParams(dp(52), dp(52)));
            pAvImg.setTag(pAvFallback);

            if (pAv.startsWith("http")) {
                loadAvatar(pAvImg, pAv);
            }

            pCard.addView(pAvBox, new LinearLayout.LayoutParams(dp(52), dp(52)));

            TextView nm = text(pNm, 14);
            nm.setTypeface(null, 1);
            if (theme != null) {
                nm.setTextColor(isCurrent ? theme.onPrimaryContainer : theme.onSurface);
            } else {
                nm.setTextColor(isCurrent ? accent : Color.WHITE);
            }
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            nm.setPadding(0, dp(8), 0, dp(8));
            pCard.addView(nm);

            final View.OnClickListener switchListener = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!isCurrent) switchNuvioProfile(pId, pNm, pAv);
                }
            };

            LinearLayout pBtnRow = new LinearLayout(this);
            pBtnRow.setOrientation(LinearLayout.HORIZONTAL);
            pBtnRow.setGravity(Gravity.CENTER);

            Button selBtn = btn(isCurrent ? "✓ Active" : "Select", new Runnable() {
                @Override
                public void run() {
                    if (!isCurrent) switchNuvioProfile(pId, pNm, pAv);
                }
            });
            selBtn.setTextSize(12);
            if (theme != null) {
                if (isCurrent) {
                    ThemeManager.stylePillButton(this, selBtn, theme.primary, theme.onPrimary, 0, 0, true);
                } else {
                    ThemeManager.stylePillButton(this, selBtn, theme.surfaceContainerHighest, theme.onSurface, 1, theme.outlineVariant, false);
                }
            } else if (isCurrent) {
                selBtn.setBackgroundDrawable(shape(accent, 6));
                selBtn.setTextColor(Color.WHITE);
            } else {
                selBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
                selBtn.setTextColor(Color.WHITE);
            }
            selBtn.setOnClickListener(switchListener);
            pCard.addView(selBtn, new LinearLayout.LayoutParams(-1, dp(34)));

            pCard.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showEditProfileDialog(p);
                    return true;
                }
            });

            pCard.setOnClickListener(switchListener);

            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(dp(136), dp(172));
            pLp.setMargins(0, 0, dp(12), 0);
            row.addView(pCard, pLp);
        }

        // Add Profile Card in the row
        LinearLayout addCard = new LinearLayout(this);
        addCard.setOrientation(LinearLayout.VERTICAL);
        addCard.setGravity(Gravity.CENTER);
        addCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        addCard.setClickable(true);
        addCard.setFocusable(true);
        if (theme != null) {
            ThemeManager.styleInteractiveCard(this, addCard, theme.surfaceContainer, theme.onSurface, 14, 1, theme.outlineVariant);
        } else {
            addCard.setBackgroundDrawable(shape(surface, 14));
        }

        FrameLayout addAvBox = new FrameLayout(this);
        if (theme != null) {
            ThemeManager.styleSurfaceCard(this, addAvBox, theme.surfaceContainerHighest, 26, 1, theme.outlineVariant);
        } else {
            addAvBox.setBackgroundDrawable(circleShape(surface, accent, 1));
        }
        TextView plus = text("＋", 24);
        plus.setGravity(Gravity.CENTER);
        plus.setTextColor(theme != null ? theme.primary : accent);
        addAvBox.addView(plus, new FrameLayout.LayoutParams(-1, -1));
        addCard.addView(addAvBox, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView addLbl = text("New Profile", 14);
        addLbl.setTypeface(null, 1);
        addLbl.setTextColor(theme != null ? theme.onSurface : muted);
        addLbl.setPadding(0, dp(8), 0, dp(8));
        addCard.addView(addLbl);

        final View.OnClickListener addListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddProfileDialog();
            }
        };

        Button addBtn = btn("Create", new Runnable() {
            @Override
            public void run() {
                showAddProfileDialog();
            }
        });
        addBtn.setTextSize(12);
        if (theme != null) {
            ThemeManager.stylePillButton(this, addBtn, theme.surfaceContainerHighest, theme.primary, 1, theme.outlineVariant, false);
        } else {
            addBtn.setBackgroundDrawable(shape(surface, 6));
            addBtn.setTextColor(accent);
        }
        addBtn.setOnClickListener(addListener);
        addCard.addView(addBtn, new LinearLayout.LayoutParams(dp(96), dp(34)));
        addCard.setOnClickListener(addListener);

        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(dp(136), dp(172));
        row.addView(addCard, addLp);

        scroll.addView(row);
        switchCard.addView(scroll);
        body.addView(switchCard);

        // 3. Account Actions Card
        LinearLayout actionCard = cardLayout();
        TextView aHeading = text("ACCOUNT ACTIONS", 15);
        aHeading.setTypeface(null, 1);
        aHeading.setTextColor(accent);
        actionCard.addView(aHeading);

        TextView syncSub = text("Watch progress & Continue Watching synchronize across your devices for this Nuvio profile.", 13);
        syncSub.setTextColor(muted);
        syncSub.setPadding(0, dp(4), 0, dp(10));
        actionCard.addView(syncSub);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button syncBtn = btn("🔄 Sync Nuvio Account", new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "Syncing profiles, add-ons, and watch progress…", Toast.LENGTH_SHORT).show();
                fetchNuvioProfiles();
                fetchNuvioAddons();
                fetchNuvioWatchProgress();
            }
        });
        syncBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, syncBtn, theme.primary, theme.onPrimary, 0, 0, true);
        } else {
            syncBtn.setBackgroundDrawable(shape(accent, 6));
            syncBtn.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(-2, dp(40));
        b1.setMargins(0, 0, dp(10), 0);
        btnRow.addView(syncBtn, b1);

        Button signOutBtn = btn("Sign Out", new Runnable() {
            @Override
            public void run() {
                nuvioSignOut();
            }
        });
        signOutBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, signOutBtn, theme.errorContainer, theme.onErrorContainer, 0, 0, true);
        } else {
            signOutBtn.setBackgroundDrawable(shape(0xffc0392b, 6));
            signOutBtn.setTextColor(Color.WHITE);
        }
        btnRow.addView(signOutBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        actionCard.addView(btnRow);
        body.addView(actionCard);

        // Vehicle Device Card (nuvio.tv/account?tab=devices)
        renderVehicleDeviceCard();

        renderTraktCard();
    }

    void renderTraktCard() {
        LinearLayout traktCard = cardLayout();
        TextView traktTitle = text("TRAKT.TV ACCOUNT & SCROBBLER", 15);
        traktTitle.setTypeface(null, 1);
        traktTitle.setTextColor(accent);
        traktCard.addView(traktTitle);

        final TraktClient trakt = TraktClient.getInstance(this);
        if (trakt.isConnected()) {
            String note = trakt.hasCustomCredentials() ? " (Custom API Key)" : "";
            TextView traktStatus = text("✓ Connected to Trakt.tv" + note, 14);
            traktStatus.setTextColor(theme != null ? theme.statusPositive : 0xff4ade80);
            traktStatus.setTypeface(null, 1);
            traktStatus.setPadding(0, dp(4), 0, dp(2));
            traktCard.addView(traktStatus);

            TextView traktDesc = text("Playback progress, watch history, and episode scrobbles synchronize with your Trakt.tv account in real time.", 13);
            traktDesc.setTextColor(muted);
            traktDesc.setPadding(0, 0, 0, dp(10));
            traktCard.addView(traktDesc);

            LinearLayout actRow = new LinearLayout(this);
            actRow.setOrientation(LinearLayout.HORIZONTAL);
            actRow.setGravity(Gravity.CENTER_VERTICAL);

            Button traktDisconnectBtn = btn("Disconnect Trakt", new Runnable() {
                @Override
                public void run() {
                    trakt.disconnect();
                    Toast.makeText(MainActivity.this, "Trakt.tv account disconnected", Toast.LENGTH_SHORT).show();
                    accountView();
                }
            });
            traktDisconnectBtn.setTextSize(13);
            if (theme != null) {
                ThemeManager.stylePillButton(this, traktDisconnectBtn, theme.errorContainer, theme.onErrorContainer, 0, 0, true);
            } else {
                traktDisconnectBtn.setBackgroundDrawable(shape(0xffc0392b, 6));
                traktDisconnectBtn.setTextColor(Color.WHITE);
            }
            actRow.addView(traktDisconnectBtn, new LinearLayout.LayoutParams(-2, dp(40)));

            Button cfgBtn = btn("⚙️ Edit API Keys", new Runnable() {
                @Override
                public void run() {
                    showTraktApiConfigDialog(null);
                }
            });
            cfgBtn.setTextSize(13);
            if (theme != null) {
                ThemeManager.stylePillButton(this, cfgBtn, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
            } else {
                cfgBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
                cfgBtn.setTextColor(Color.WHITE);
            }
            LinearLayout.LayoutParams cfgLp = new LinearLayout.LayoutParams(-2, dp(40));
            cfgLp.setMargins(dp(8), 0, 0, 0);
            actRow.addView(cfgBtn, cfgLp);

            traktCard.addView(actRow);
        } else {
            TextView traktDesc = text("Connect Trakt.tv to automatically scrobble watched movies and series without typing on the vehicle screen.", 13);
            traktDesc.setTextColor(muted);
            traktDesc.setPadding(0, dp(4), 0, dp(10));
            traktCard.addView(traktDesc);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            Button traktLinkBtn = btn("🔗 Link Trakt (Quick Code)", new Runnable() {
                @Override
                public void run() {
                    startTraktDeviceLink();
                }
            });
            traktLinkBtn.setTextSize(13);
            if (theme != null) {
                ThemeManager.stylePillButton(this, traktLinkBtn, theme.primary, theme.onPrimary, 0, 0, true);
            } else {
                traktLinkBtn.setBackgroundDrawable(shape(accent, 6));
                traktLinkBtn.setTextColor(Color.WHITE);
            }
            row.addView(traktLinkBtn, new LinearLayout.LayoutParams(-2, dp(40)));

            Button traktApiBtn = btn("⚙️ API Keys", new Runnable() {
                @Override
                public void run() {
                    showTraktApiConfigDialog(null);
                }
            });
            traktApiBtn.setTextSize(13);
            if (theme != null) {
                ThemeManager.stylePillButton(this, traktApiBtn, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
            } else {
                traktApiBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
                traktApiBtn.setTextColor(Color.WHITE);
            }
            LinearLayout.LayoutParams apiLp = new LinearLayout.LayoutParams(-2, dp(40));
            apiLp.setMargins(dp(8), 0, 0, 0);
            row.addView(traktApiBtn, apiLp);

            Button traktTokenBtn = btn("🔑 Direct Token", new Runnable() {
                @Override
                public void run() {
                    showTraktTokenDialog();
                }
            });
            traktTokenBtn.setTextSize(13);
            if (theme != null) {
                ThemeManager.stylePillButton(this, traktTokenBtn, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
            } else {
                traktTokenBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
                traktTokenBtn.setTextColor(Color.WHITE);
            }
            LinearLayout.LayoutParams tokLp = new LinearLayout.LayoutParams(-2, dp(40));
            tokLp.setMargins(dp(8), 0, 0, 0);
            row.addView(traktTokenBtn, tokLp);

            traktCard.addView(row);
        }
        body.addView(traktCard);
    }

    void showTraktApiConfigDialog(final Runnable onSavedRetry) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(18), dp(22), dp(18));
        ThemeManager.styleSurfaceCard(this, shell, theme != null ? theme.surfaceContainer : surface, 14, 1, theme != null ? theme.outlineVariant : 0);

        TextView title = text("Trakt.tv API Configuration", 18);
        title.setTypeface(null, 1);
        title.setTextColor(theme != null ? theme.primary : accent);
        shell.addView(title);

        TextView desc = text("Create a free API app at: https://trakt.tv/oauth/applications\n(Name: Nuvio, Redirect URI: urn:ietf:wg:oauth:2.0:oob)\nThen paste your Client ID (and optional Client Secret) below:", 13);
        desc.setTextColor(muted);
        desc.setPadding(0, dp(4), 0, dp(12));
        shell.addView(desc);

        final SharedPreferences p = getSharedPreferences("nuvio_stremio_prefs", MODE_PRIVATE);

        TextView idLabel = text("CLIENT ID *", 11);
        idLabel.setTextColor(theme != null ? theme.onSurfaceVariant : muted);
        idLabel.setTypeface(null, 1);
        shell.addView(idLabel);

        final EditText idInput = new EditText(this);
        idInput.setHint("Paste Trakt Client ID (64 characters)…");
        idInput.setText(p.getString("trakt_custom_client_id", ""));
        idInput.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        idInput.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
        ThemeManager.styleSurfaceCard(this, idInput, theme != null ? theme.surfaceContainerHigh : bg, 8, 1, theme != null ? theme.outlineVariant : 0);
        idInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        idInput.setSingleLine(true);
        LinearLayout.LayoutParams idLp = new LinearLayout.LayoutParams(-1, dp(44));
        idLp.setMargins(0, dp(4), 0, dp(10));
        shell.addView(idInput, idLp);

        TextView secLabel = text("CLIENT SECRET (OPTIONAL)", 11);
        secLabel.setTextColor(theme != null ? theme.onSurfaceVariant : muted);
        secLabel.setTypeface(null, 1);
        shell.addView(secLabel);

        final EditText secInput = new EditText(this);
        secInput.setHint("Paste Trakt Client Secret (if generated)…");
        secInput.setText(p.getString("trakt_custom_client_secret", ""));
        secInput.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        secInput.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
        ThemeManager.styleSurfaceCard(this, secInput, theme != null ? theme.surfaceContainerHigh : bg, 8, 1, theme != null ? theme.outlineVariant : 0);
        secInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        secInput.setSingleLine(true);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(-1, dp(44));
        secLp.setMargins(0, dp(4), 0, dp(14));
        shell.addView(secInput, secLp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button cancel = btn("Cancel", new Runnable() {
            @Override
            public void run() { d.dismiss(); }
        });
        cancel.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, cancel, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
        }
        btns.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button save = btn("Save & Connect", new Runnable() {
            @Override
            public void run() {
                String cId = idInput.getText().toString().trim();
                String cSec = secInput.getText().toString().trim();
                if (cId.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please enter your Trakt Client ID", Toast.LENGTH_SHORT).show();
                    return;
                }
                TraktClient.getInstance(MainActivity.this).saveCustomCredentials(cId, cSec);
                Toast.makeText(MainActivity.this, "✓ Trakt API keys saved!", Toast.LENGTH_SHORT).show();
                d.dismiss();
                if (onSavedRetry != null) {
                    onSavedRetry.run();
                } else {
                    startTraktDeviceLink();
                }
            }
        });
        save.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, save, theme.primary, theme.onPrimary, 0, 0, true);
        } else {
            save.setTypeface(null, 1);
            save.setBackgroundDrawable(shape(accent, 8));
            save.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(0, dp(42), 1);
        svLp.setMargins(dp(8), 0, 0, 0);
        btns.addView(save, svLp);

        shell.addView(btns);
        d.setContentView(shell);
        d.show();

        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(520)), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
    }

    void showTraktTokenDialog() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(18), dp(22), dp(18));
        ThemeManager.styleSurfaceCard(this, shell, theme != null ? theme.surfaceContainer : surface, 14, 1, theme != null ? theme.outlineVariant : 0);

        TextView title = text("Connect via Trakt Access Token", 18);
        title.setTypeface(null, 1);
        title.setTextColor(theme != null ? theme.primary : accent);
        shell.addView(title);

        TextView desc = text("If you already have a Trakt OAuth access token from trakt.tv or another app, paste it directly below:", 13);
        desc.setTextColor(muted);
        desc.setPadding(0, dp(4), 0, dp(12));
        shell.addView(desc);

        final EditText tokenIn = new EditText(this);
        tokenIn.setHint("Paste Trakt Access Token (e.g. 64-char hex)…");
        tokenIn.setText(TraktClient.getInstance(this).getAccessToken());
        tokenIn.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        tokenIn.setHintTextColor(theme != null ? theme.onSurfaceVariant : 0xff888888);
        ThemeManager.styleSurfaceCard(this, tokenIn, theme != null ? theme.surfaceContainerHigh : bg, 8, 1, theme != null ? theme.outlineVariant : 0);
        tokenIn.setPadding(dp(12), dp(8), dp(12), dp(8));
        tokenIn.setSingleLine(true);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, dp(44));
        tLp.setMargins(0, 0, 0, dp(14));
        shell.addView(tokenIn, tLp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        Button cancel = btn("Cancel", new Runnable() {
            @Override
            public void run() { d.dismiss(); }
        });
        cancel.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, cancel, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
        }
        btns.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button save = btn("Save & Activate", new Runnable() {
            @Override
            public void run() {
                String token = tokenIn.getText().toString().trim();
                if (token.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please paste an access token", Toast.LENGTH_SHORT).show();
                    return;
                }
                TraktClient.getInstance(MainActivity.this).saveDirectAccessToken(token);
                Toast.makeText(MainActivity.this, "🎉 Trakt.tv account connected successfully!", Toast.LENGTH_LONG).show();
                d.dismiss();
                render("Account", false);
            }
        });
        save.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, save, theme.primary, theme.onPrimary, 0, 0, true);
        } else {
            save.setTypeface(null, 1);
            save.setBackgroundDrawable(shape(accent, 8));
            save.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(0, dp(42), 1);
        svLp.setMargins(dp(8), 0, 0, 0);
        btns.addView(save, svLp);

        shell.addView(btns);
        d.setContentView(shell);
        d.show();

        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(520)), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
    }

    void startTraktDeviceLink() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(20), dp(24), dp(20));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        if (theme != null) {
            ThemeManager.styleSurfaceCard(this, layout, theme.surfaceContainerHighest, 16, 1, theme.outlineVariant);
        } else {
            layout.setBackgroundDrawable(shape(surface, 14));
        }

        TextView heading = text("Trakt.tv Device Activation", 18);
        heading.setTypeface(null, 1);
        heading.setTextColor(theme != null ? theme.primary : accent);
        heading.setGravity(Gravity.CENTER);
        layout.addView(heading);

        final TextView statusText = text("Requesting activation code from Trakt…", 13);
        statusText.setTextColor(muted);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(8), 0, dp(8));
        layout.addView(statusText);

        final TextView codeView = text("••••••••", 32);
        codeView.setTypeface(null, 1);
        codeView.setTextColor(Color.WHITE);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, dp(12), 0, dp(12));
        layout.addView(codeView);

        final TextView instView = text("1. On your phone or PC, open: https://trakt.tv/activate\n2. Enter the 8-character code shown above and tap Authorize.", 13);
        instView.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        instView.setPadding(0, dp(6), 0, dp(14));
        instView.setGravity(Gravity.CENTER);
        layout.addView(instView);

        final LinearLayout errBtnRow = new LinearLayout(this);
        errBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        errBtnRow.setGravity(Gravity.CENTER);
        errBtnRow.setVisibility(View.GONE);
        errBtnRow.setPadding(0, 0, 0, dp(12));

        Button cfgBtn = btn("⚙️ Set API Keys", new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
                showTraktApiConfigDialog(new Runnable() {
                    @Override
                    public void run() {
                        startTraktDeviceLink();
                    }
                });
            }
        });
        cfgBtn.setTextSize(12);
        if (theme != null) {
            ThemeManager.stylePillButton(this, cfgBtn, theme.primaryContainer, theme.onPrimaryContainer, 0, 0, true);
        } else {
            cfgBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
            cfgBtn.setTextColor(Color.WHITE);
        }
        errBtnRow.addView(cfgBtn, new LinearLayout.LayoutParams(-2, dp(38)));

        Button tokenBtn = btn("🔑 Direct Token", new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
                showTraktTokenDialog();
            }
        });
        tokenBtn.setTextSize(12);
        if (theme != null) {
            ThemeManager.stylePillButton(this, tokenBtn, theme.secondaryContainer, theme.onSecondaryContainer, 0, 0, true);
        } else {
            tokenBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
            tokenBtn.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(-2, dp(38));
        tbLp.setMargins(dp(8), 0, 0, 0);
        errBtnRow.addView(tokenBtn, tbLp);

        layout.addView(errBtnRow);

        Button cancelBtn = btn("Cancel", new Runnable() {
            @Override
            public void run() {
                TraktClient.getInstance(MainActivity.this).cancelPolling();
                dialog.dismiss();
            }
        });
        cancelBtn.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(MainActivity.this, cancelBtn, theme.surfaceContainer, theme.onSurface, 0, 0, true);
        } else {
            cancelBtn.setBackgroundDrawable(shape(0xff222e3c, 6));
            cancelBtn.setTextColor(Color.WHITE);
        }
        layout.addView(cancelBtn, new LinearLayout.LayoutParams(dp(120), dp(40)));

        dialog.setContentView(layout);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                TraktClient.getInstance(MainActivity.this).cancelPolling();
            }
        });
        dialog.show();

        Window win = dialog.getWindow();
        if (win != null) {
            win.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(520)), WindowManager.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.CENTER);
        }

        final TraktClient trakt = TraktClient.getInstance(this);
        trakt.startDeviceCodeFlow(new TraktClient.Callback<TraktClient.DeviceCodeResponse>() {
            @Override
            public void onSuccess(final TraktClient.DeviceCodeResponse dcr) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing()) return;
                        codeView.setText(dcr.userCode);
                        codeView.setTextColor(theme != null ? theme.primary : accent);
                        statusText.setText("Waiting for authorization on trakt.tv/activate…");

                        trakt.pollForAuthorization(dcr.deviceCode, dcr.interval, new TraktClient.Callback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean result) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        dialog.dismiss();
                                        Toast.makeText(MainActivity.this, "🎉 Trakt.tv account connected successfully!", Toast.LENGTH_LONG).show();
                                        render("Account", false);
                                    }
                                });
                            }

                            @Override
                            public void onError(final String error) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (dialog.isShowing()) {
                                            statusText.setText("⚠️ " + error);
                                            statusText.setTextColor(0xffe74c3c);
                                            errBtnRow.setVisibility(View.VISIBLE);
                                        }
                                    }
                                });
                            }
                        });
                    }
                });
            }

            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (dialog.isShowing()) {
                            statusText.setText("⚠️ " + error);
                            statusText.setTextColor(0xffe74c3c);
                            errBtnRow.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    void libraryView() {
        if (!NuvioClient.getInstance(this).isLoggedIn()) {
            LinearLayout emptyCard = cardLayout();
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setPadding(dp(24), dp(36), dp(24), dp(36));

            TextView lTitle = text("Sign In to Access Your Library", 20);
            lTitle.setTypeface(null, 1);
            lTitle.setTextColor(accent);
            emptyCard.addView(lTitle);

            TextView subtitle = text("Your Nuvio Library keeps your saved movies and TV series synchronized across all your devices.", 14);
            subtitle.setTextColor(muted);
            subtitle.setGravity(Gravity.CENTER);
            subtitle.setPadding(0, dp(8), 0, dp(16));
            emptyCard.addView(subtitle);

            Button signInBtn = btn("🔑 Sign In with Nuvio", new Runnable() {
                @Override
                public void run() {
                    render("Account", false);
                }
            });
            if (theme != null) {
                ThemeManager.stylePillButton(this, signInBtn, theme.primary, theme.onPrimary, 0, 0, true);
            }
            emptyCard.addView(signInBtn, new LinearLayout.LayoutParams(dp(220), dp(44)));
            add(emptyCard);
            return;
        }

        // Header Row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, dp(4), 0, dp(8));

        TextView heading = text("My Library", 22);
        heading.setTypeface(null, 1);
        heading.setTextColor(accent);
        headerRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        Button refreshBtn = btn("🔄 Sync", new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "Syncing library with Nuvio…", Toast.LENGTH_SHORT).show();
                syncLibraryAndRender();
            }
        });
        if (theme != null) {
            ThemeManager.stylePillButton(this, refreshBtn, theme.surfaceContainerHighest, theme.onSurface, 0, 0, true);
        }
        headerRow.addView(refreshBtn, new LinearLayout.LayoutParams(-2, dp(38)));
        add(headerRow);

        // Filter chips: All, Movies, TV Series
        final String currentFilter = prefs.getString("nuvio_lib_filter", "All");
        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(0, 0, 0, dp(10));

        String[] filters = {"All", "Movies", "TV Series", "History"};
        for (final String f : filters) {
            Button fb = btn(f, new Runnable() {
                @Override
                public void run() {
                    prefs.edit().putString("nuvio_lib_filter", f).apply();
                    render("Library", false);
                }
            });
            boolean isSel = f.equals(currentFilter);
            if (theme != null) {
                ThemeManager.styleFilterChip(this, fb, theme, f, isSel);
            }
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, dp(36));
            flp.setMargins(0, 0, dp(8), 0);
            filterRow.addView(fb, flp);
        }
        add(filterRow);

        if ("History".equals(currentFilter)) {
            renderWatchHistoryGrid();
        } else {
            renderLibraryGrid(currentFilter);
        }
    }

    void syncLibraryAndRender() {
        NuvioClient.getInstance(this).fetchLibraryItems(new NuvioClient.Callback<JSONArray>() {
            @Override
            public void onSuccess(JSONArray result) {
                prefs.edit().putString("nuvio_library_cache", result.toString()).apply();
                if ("Library".equals(page)) {
                    render("Library", false);
                }
            }

            @Override
            public void onError(String error, int statusCode) {
                Toast.makeText(MainActivity.this, "Sync error: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    
    void renderWatchHistoryGrid() {
        NuvioClient.getInstance(this).fetchWatchHistory(new NuvioClient.Callback<JSONArray>() {
            @Override
            public void onSuccess(final JSONArray result) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!"Library".equals(page)) return;
                        if (result == null || result.length() == 0) {
                            TextView empty = text("No watch history recorded yet.\nCompleted movies and episodes will appear here automatically.", 14);
                            empty.setTextColor(muted);
                            empty.setGravity(Gravity.CENTER);
                            empty.setPadding(0, dp(30), 0, dp(30));
                            add(empty);
                            return;
                        }

                        LinearLayout grid = new LinearLayout(MainActivity.this);
                        grid.setOrientation(LinearLayout.VERTICAL);

                        LinearLayout curRow = null;
                        int cols = 4;
                        for (int i = 0; i < result.length(); i++) {
                            if (i % cols == 0) {
                                curRow = new LinearLayout(MainActivity.this);
                                curRow.setOrientation(LinearLayout.HORIZONTAL);
                                curRow.setPadding(0, 0, 0, dp(12));
                                grid.addView(curRow);
                            }
                            JSONObject h = result.optJSONObject(i);
                            if (h == null) continue;
                            final String contentId = h.optString("content_id", "");
                            final String title = h.optString("title", "Untitled");
                            final String type = h.optString("content_type", "movie");
                            final int season = h.optInt("season", 0);
                            final int episode = h.optInt("episode", 0);

                            LinearLayout itemCard = new LinearLayout(MainActivity.this);
                            itemCard.setOrientation(LinearLayout.VERTICAL);
                            itemCard.setPadding(dp(12), dp(10), dp(12), dp(10));
                            if (theme != null) {
                                ThemeManager.styleInteractiveCard(MainActivity.this, itemCard, theme.surfaceContainer, theme.onSurface, 8, 1, theme.outlineVariant);
                            } else {
                                itemCard.setBackgroundDrawable(shape(surface, 8));
                            }

                            TextView tView = text(title, 13);
                            tView.setTypeface(null, 1);
                            tView.setSingleLine(true);
                            tView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            itemCard.addView(tView);

                            String epInfo = (season > 0 && episode > 0) ? (" S" + season + "E" + episode) : "";
                            TextView subView = text("✓ Watched • " + type.toUpperCase(Locale.US) + epInfo, 11);
                            subView.setTextColor(theme != null ? theme.statusPositive : 0xff4ade80);
                            subView.setPadding(0, dp(3), 0, 0);
                            itemCard.addView(subView);

                            itemCard.setClickable(true);
                            itemCard.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if (!contentId.isEmpty()) {
                                        selectedId = contentId;
                                        selectedType = type;
                                        selectedName = title;
                                        selectedPoster = "";
                                        if (season > 0 && episode > 0) {
                                            selectedVideoId = contentId + ":" + season + ":" + episode;
                                            selectedVideoLabel = "Season " + season + ", Episode " + episode;
                                        } else {
                                            selectedVideoId = "";
                                            selectedVideoLabel = "";
                                        }
                                        rememberTitle();
                                        render("Streams", true);
                                    }
                                }
                            });

                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f);
                            lp.setMargins(0, 0, dp(8), 0);
                            if (curRow != null) curRow.addView(itemCard, lp);
                        }
                        add(grid);
                    }
                });
            }

            @Override
            public void onError(final String error, final int statusCode) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        TextView errView = text("Failed to load watch history: " + error, 13);
                        errView.setTextColor(0xffe74c3c);
                        add(errView);
                    }
                });
            }
        });
    }

    void renderLibraryGrid(final String filter) {
        String cached = prefs.getString("nuvio_library_cache", "[]");
        JSONArray items = new JSONArray();
        try {
            items = new JSONArray(cached);
        } catch (Exception ignored) {}

        ArrayList<JSONObject> filtered = new ArrayList<JSONObject>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("content_type", "movie");
            if ("Movies".equals(filter) && !"movie".equals(type)) continue;
            if ("TV Series".equals(filter) && !"series".equals(type)) continue;
            filtered.add(item);
        }

        if (filtered.isEmpty()) {
            LinearLayout emptyCard = cardLayout();
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setPadding(dp(20), dp(30), dp(20), dp(30));

            TextView eTitle = text("Your library is empty", 17);
            eTitle.setTypeface(null, 1);
            eTitle.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
            emptyCard.addView(eTitle);

            TextView eDesc = text("Browse Movies or TV Series and tap '＋ Add to Library' to bookmark them here.", 13);
            eDesc.setTextColor(muted);
            eDesc.setGravity(Gravity.CENTER);
            eDesc.setPadding(0, dp(6), 0, dp(14));
            emptyCard.addView(eDesc);

            Button exploreBtn = btn("Explore Movies", new Runnable() {
                @Override
                public void run() {
                    render("Movies", false);
                }
            });
            if (theme != null) {
                ThemeManager.stylePillButton(this, exploreBtn, theme.primary, theme.onPrimary, 0, 0, true);
            }
            emptyCard.addView(exploreBtn, new LinearLayout.LayoutParams(dp(180), dp(40)));
            add(emptyCard);
            return;
        }

        // Render responsive grid rows (4 items per row on 1920x720)
        LinearLayout currentRow = null;
        for (int i = 0; i < filtered.size(); i++) {
            final JSONObject it = filtered.get(i);
            final String cid = it.optString("content_id", "");
            final String ctype = it.optString("content_type", "movie");
            final String cname = it.optString("name", "Untitled");
            final String cposter = it.optString("poster", "");

            if (i % 4 == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setBaselineAligned(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.setMargins(0, 0, 0, dp(10));
                currentRow.setLayoutParams(rlp);
                add(currentRow);
            }

            final LinearLayout itemCard = new LinearLayout(this);
            itemCard.setOrientation(LinearLayout.VERTICAL);
            itemCard.setPadding(dp(6), dp(6), dp(6), dp(8));
            itemCard.setClickable(true);
            itemCard.setFocusable(true);
            if (theme != null) {
                ThemeManager.styleInteractiveCard(this, itemCard, theme.surfaceContainer, theme.onSurface, 12, 1, theme.outlineVariant);
            } else {
                itemCard.setBackgroundDrawable(shape(surface, 12));
            }

            RoundedImageView iv = new RoundedImageView(this);
            iv.setCornerRadius(dp(8));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (theme != null) {
                ThemeManager.styleSurfaceCard(this, iv, theme.surfaceContainerHighest, 8, 0, 0);
            }
            if (cposter.startsWith("http")) {
                loadPoster(iv, cposter);
            }

            FrameLayout tile = new FrameLayout(this);
            tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));

            TextView label = text(cname, 12);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(label, new FrameLayout.LayoutParams(-1, -1));
            iv.setTag(label);

            itemCard.addView(tile, new LinearLayout.LayoutParams(-1, dp(160)));

            TextView titleView = text(cname, 13);
            titleView.setTypeface(null, 1);
            titleView.setMaxLines(1);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
            titleView.setPadding(0, dp(6), 0, dp(2));
            itemCard.addView(titleView);

            TextView typeChip = badge("series".equals(ctype) ? "TV SERIES" : "MOVIE",
                    theme != null ? theme.surfaceContainerHighest : 0xff283344,
                    theme != null ? theme.primary : accent,
                    theme != null ? theme.outlineVariant : 0);
            typeChip.setTextSize(10);
            typeChip.setPadding(dp(6), dp(2), dp(6), dp(2));
            itemCard.addView(typeChip, new LinearLayout.LayoutParams(-2, -2));

            itemCard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    history.add(page);
                    selectedId = cid;
                    selectedType = ctype;
                    selectedName = cname;
                    selectedPoster = cposter;
                    selectedVideoId = "";
                    selectedVideoLabel = "";
                    render("Streams", true);
                }
            });

            itemCard.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Remove from Library?")
                            .setMessage("Remove \"" + cname + "\" from your Nuvio Library?")
                            .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    toggleLibraryItem(cid, ctype, cname, cposter, null);
                                    syncLibraryAndRender();
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
                }
            });

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1);
            clp.setMargins(dp(4), 0, dp(4), 0);
            currentRow.addView(itemCard, clp);
        }

        int rem = filtered.size() % 4;
        if (rem != 0 && currentRow != null) {
            for (int k = 0; k < (4 - rem); k++) {
                View placeholder = new View(this);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(1), 1);
                plp.setMargins(dp(4), 0, dp(4), 0);
                currentRow.addView(placeholder, plp);
            }
        }
    }

    void toggleLibraryItem(final String contentId, final String contentType, final String name,
                           final String poster, final Button btn) {
        if (!NuvioClient.getInstance(this).isLoggedIn()) {
            Toast.makeText(this, "Sign in to Nuvio under Account to save to Library.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean currentlyIn = NuvioClient.getInstance(this).isInLibrary(contentId);
        if (currentlyIn) {
            if (btn != null) {
                btn.setText("＋ Add to Library");
                if (theme != null) {
                    ThemeManager.stylePillButton(this, btn, theme.surfaceContainerHighest, theme.onSurface, 1, theme.outlineVariant, true);
                }
            }
            Toast.makeText(this, "Removed from Library", Toast.LENGTH_SHORT).show();
            NuvioClient.getInstance(this).removeFromLibrary(contentId, new NuvioClient.Callback<Boolean>() {
                @Override public void onSuccess(Boolean res) {}
                @Override public void onError(String err, int code) {}
            });
        } else {
            if (btn != null) {
                btn.setText("✓ In Library");
                if (theme != null) {
                    ThemeManager.stylePillButton(this, btn, theme.primary, theme.onPrimary, 0, 0, true);
                }
            }
            Toast.makeText(this, "Added to Library!", Toast.LENGTH_SHORT).show();
            NuvioClient.getInstance(this).addToLibrary(contentId, contentType, name, poster, new NuvioClient.Callback<Boolean>() {
                @Override public void onSuccess(Boolean res) {}
                @Override public void onError(String err, int code) {}
            });
        }
    }

    void dismissContinueWatching(final String contentId) {
        String data = prefs.getString("nuvio_continue_watching", "");
        if (!data.isEmpty()) {
            try {
                JSONArray all = new JSONArray(data);
                JSONArray updated = new JSONArray();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject item = all.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id", "");
                    if (!id.equals(contentId)) {
                        updated.put(item);
                    }
                }
                prefs.edit().putString("nuvio_continue_watching", updated.toString()).apply();
            } catch (Exception ignored) {}
        }

        NuvioClient.getInstance(this).deleteWatchProgress(contentId, null);
        Toast.makeText(this, "Removed from Continue Watching", Toast.LENGTH_SHORT).show();
        render(page, false);
    }

    void rememberTitle() {
        if (prefs != null) {
            prefs.edit()
                    .putString("selected_id", selectedId)
                    .putString("selected_type", selectedType)
                    .putString("selected_name", selectedName)
                    .putString("selected_video_id", selectedVideoId)
                    .putString("selected_video_label", selectedVideoLabel)
                    .putString("selected_poster", selectedPoster)
                    .apply();
        }
    }

    void refreshAccountIdentity() {
        if (auth.isEmpty()) return;
        apiWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject q = new JSONObject();
                    q.put("authKey", auth);
                    JSONObject resp = post("getUser", q);
                    JSONObject res = resp.optJSONObject("result");
                    JSONObject user = res != null ? res.optJSONObject("user") : null;
                    if (user != null) {
                        final String email = user.optString("email", "");
                        if (!email.isEmpty()) {
                            verifiedAccountEmail = email;
                            prefs.edit().putString("verified_account_email", email).apply();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if ("Account".equals(page)) render("Account", false);
                                }
                            });
                        }
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    void refreshAddons() {
        if (isNuvioToken(auth)) {
            fetchNuvioAddons();
            return;
        }
        if (auth.isEmpty() || addonsLoading) return;
        addonsLoading = true;
        apiWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject q = new JSONObject();
                    q.put("authKey", auth);
                    JSONObject resp = post("addonCollectionGet", q);
                    JSONArray arr = resp.optJSONArray("result");
                    if (arr == null) {
                        JSONObject r = resp.optJSONObject("result");
                        if (r != null) arr = r.optJSONArray("addons");
                    }
                    if (arr != null) {
                        addons = arr;
                        saveAddonCache();
                    }
                } catch (Exception ignored) {
                } finally {
                    addonsLoading = false;
                }
            }
        });
    }

    void restoreAddons() {
        String saved = prefs.getString("nuvio_addons", "");
        if (!saved.isEmpty()) {
            try {
                addons = new JSONArray(saved);
                return;
            } catch (Exception ignored) {}
        }
        // Seed default addons: Cinemeta, Torrentio, CyberFlix
        addons = new JSONArray();
        try {
            JSONObject cinemeta = new JSONObject();
            cinemeta.put("name", "Cinemeta (Official)");
            cinemeta.put("transportUrl", "https://v3-cinemeta.strem.io/manifest.json");
            addons.put(cinemeta);

            JSONObject torrentio = new JSONObject();
            torrentio.put("name", "Torrentio (Streams & Debrid)");
            torrentio.put("transportUrl", "https://torrentio.strem.fun/manifest.json");
            addons.put(torrentio);

            JSONObject cyberflix = new JSONObject();
            cyberflix.put("name", "CyberFlix Catalog");
            cyberflix.put("transportUrl", "https://cyberflix.elfhosted.com/manifest.json");
            addons.put(cyberflix);

            saveAddonCache();
        } catch (Exception ignored) {}
    }

    void saveAddonCache() {
        if (prefs != null) {
            prefs.edit().putString("nuvio_addons", addons.toString()).apply();
        }
    }

    JSONObject get(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(this));
        }
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.0");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Accept-Encoding", "gzip");

        int code = c.getResponseCode();
        if (code >= 400) {
            ByteArrayOutputStream errOut = new ByteArrayOutputStream();
            InputStream err = c.getErrorStream();
            if (err != null) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = err.read(buf)) != -1) errOut.write(buf, 0, n);
                err.close();
            }
            c.disconnect();
            throw new IOException("HTTP " + code + ": " + new String(errOut.toByteArray(), "UTF-8"));
        }

        InputStream in = c.getInputStream();
        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
            in = new GZIPInputStream(in);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new JSONObject(new String(out.toByteArray(), "UTF-8"));
    }

    JSONObject post(String endpoint, JSONObject payload) throws Exception {
        URL url = new URL("https://api.strem.io/api/" + endpoint);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(this));
        }
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.0");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        byte[] b = payload.toString().getBytes("UTF-8");
        OutputStream os = c.getOutputStream();
        os.write(b);
        os.flush();
        os.close();

        int code = c.getResponseCode();
        if (code >= 400) {
            ByteArrayOutputStream errOut = new ByteArrayOutputStream();
            InputStream err = c.getErrorStream();
            if (err != null) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = err.read(buf)) != -1) errOut.write(buf, 0, n);
                err.close();
            }
            c.disconnect();
            throw new IOException("HTTP " + code + ": " + new String(errOut.toByteArray(), "UTF-8"));
        }

        InputStream in = c.getInputStream();
        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
            in = new GZIPInputStream(in);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new JSONObject(new String(out.toByteArray(), "UTF-8"));
    }

    String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    GradientDrawable shape(int color, int strokeColor, int radius, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (strokeDp > 0 && strokeColor != 0) {
            d.setStroke(dp(strokeDp), strokeColor);
        }
        return d;
    }

    GradientDrawable circleShape(int bgColor, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(bgColor);
        if (strokeDp > 0 && strokeColor != 0) {
            d.setStroke(dp(strokeDp), strokeColor);
        }
        return d;
    }

    TextView text(String str, int sp) {
        TextView t = new TextView(this);
        t.setText(str);
        t.setTextColor(theme != null ? theme.onSurface : Color.WHITE);
        t.setTextSize(sp);
        t.setPadding(dp(4), dp(2), dp(4), dp(2));
        return t;
    }

    Button btn(String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        if (theme != null) {
            ThemeManager.stylePillButton(this, b, theme.surfaceContainerHigh, theme.onSurface, 1, theme.outlineVariant, false);
        } else {
            b.setTextColor(Color.WHITE);
            b.setBackgroundDrawable(shape(surface, 8));
        }
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    void add(View v) {
        if (body != null) body.addView(v);
    }
}
