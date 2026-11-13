package org.nuvio.gen5w.debrid;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.nuvio.gen5w.player.VideoTls;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import javax.net.ssl.HttpsURLConnection;

/**
 * Native Torbox Debrid API Client for Android 4.4 KitKat.
 * Resolves torrent info hashes / magnets to direct CDN streaming URLs via Torbox API v1.
 */
public final class TorboxClient {

    private static final String TAG = "TorboxClient";
    private static final String API_BASE = "https://api.torbox.app/v1/api";

    private TorboxClient() {}

    private static HttpURLConnection openHttps(Context context, String endpoint) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection) {
            ((HttpsURLConnection) conn).setSSLSocketFactory(VideoTls.build(context));
        }
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        return conn;
    }

    private static String readResponse(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    public static String testConnection(Context context, String token) throws Exception {
        HttpURLConnection conn = openHttps(context, API_BASE + "/user/me");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        int code = conn.getResponseCode();
        InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String resp = readResponse(is);
        conn.disconnect();
        JSONObject j = new JSONObject(resp);
        if (j.optBoolean("success", false)) {
            JSONObject data = j.optJSONObject("data");
            String email = data != null ? data.optString("email", "Active") : "Active";
            return "Connected (" + email + ")";
        }
        return "HTTP " + code + ": " + j.optString("detail", "Auth failed");
    }

    /**
     * Resolves an infoHash to a direct playable CDN streaming URL using Torbox API v1.
     */
    public static String resolveTorrent(Context context, String apiKey, String infoHash, int fileIndex) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Torbox API token is missing. Please add it in Settings.");
        }
        String token = apiKey.trim();
        String hash = infoHash.toLowerCase(java.util.Locale.US).trim();

        // 1. Create torrent on Torbox (or fetch cached)
        String createUrl = API_BASE + "/torrents/createtorrent";
        HttpURLConnection conn = openHttps(context, createUrl);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        String magnet = "magnet:?xt=urn:btih:" + hash;
        String postBody = "magnet=" + URLEncoder.encode(magnet, "UTF-8");
        OutputStream os = conn.getOutputStream();
        os.write(postBody.getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String respStr = readResponse(is);
        conn.disconnect();

        JSONObject createJson = new JSONObject(respStr);
        if (!createJson.optBoolean("success", false)) {
            String detail = createJson.optString("detail", "Failed to add torrent to Torbox");
            throw new Exception("Torbox error: " + detail);
        }

        JSONObject data = createJson.optJSONObject("data");
        String torrentId = data != null ? data.optString("torrent_id", "") : "";

        // 2. Query torrent list to inspect files and find torrent ID if not returned
        String listUrl = API_BASE + "/torrents/mylist?bypass_cache=true";
        HttpURLConnection listConn = openHttps(context, listUrl);
        listConn.setRequestProperty("Authorization", "Bearer " + token);
        int listCode = listConn.getResponseCode();
        InputStream listIs = listCode < 400 ? listConn.getInputStream() : listConn.getErrorStream();
        String listStr = readResponse(listIs);
        listConn.disconnect();

        JSONObject listJson = new JSONObject(listStr);
        JSONArray torrents = listJson.optJSONArray("data");
        JSONObject matchedTorrent = null;

        if (torrents != null) {
            for (int i = 0; i < torrents.length(); i++) {
                JSONObject t = torrents.optJSONObject(i);
                if (t == null) continue;
                String tHash = t.optString("hash", "").toLowerCase(java.util.Locale.US);
                String tId = String.valueOf(t.opt("id"));
                if (hash.equalsIgnoreCase(tHash) || (!torrentId.isEmpty() && torrentId.equals(tId))) {
                    matchedTorrent = t;
                    torrentId = tId;
                    break;
                }
            }
        }

        if (matchedTorrent == null || torrentId.isEmpty()) {
            throw new Exception("Torbox: Torrent added but could not be located in your cloud library.");
        }

        // 3. Determine file ID
        int chosenFileId = fileIndex;
        JSONArray files = matchedTorrent.optJSONArray("files");
        if (files != null && files.length() > 0) {
            if (fileIndex >= 0 && fileIndex < files.length()) {
                JSONObject f = files.optJSONObject(fileIndex);
                if (f != null) chosenFileId = f.optInt("id", fileIndex);
            } else {
                // Find the largest video file
                long maxBytes = -1;
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.optJSONObject(i);
                    if (f == null) continue;
                    long size = f.optLong("size", 0);
                    if (size > maxBytes) {
                        maxBytes = size;
                        chosenFileId = f.optInt("id", i);
                    }
                }
            }
        }

        // 4. Request download / streaming link
        String dlUrl = API_BASE + "/torrents/requestdl?token=" + URLEncoder.encode(token, "UTF-8")
                + "&torrent_id=" + URLEncoder.encode(torrentId, "UTF-8")
                + "&file_id=" + chosenFileId;

        HttpURLConnection dlConn = openHttps(context, dlUrl);
        int dlCode = dlConn.getResponseCode();
        InputStream dlIs = dlCode < 400 ? dlConn.getInputStream() : dlConn.getErrorStream();
        String dlStr = readResponse(dlIs);
        dlConn.disconnect();

        JSONObject dlJson = new JSONObject(dlStr);
        if (!dlJson.optBoolean("success", false)) {
            String detail = dlJson.optString("detail", "Torbox failed to generate streaming URL");
            throw new Exception("Torbox stream error: " + detail);
        }

        String streamUrl = dlJson.optString("data", "");
        if (streamUrl.isEmpty()) {
            throw new Exception("Torbox returned empty download URL");
        }

        Log.i(TAG, "Successfully resolved Torbox stream URL: " + streamUrl);
        return streamUrl;
    }
}
