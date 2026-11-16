package org.nuvio.gen5w.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import org.nuvio.gen5w.player.VideoTls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;

/**
 * Sends real ExoPlayer playback progress to the signed-in Stremio library datastore.
 */
public final class PlaybackSync {
  private final Context app;
  private final String titleId, mediaType, videoId, title, poster;
  private final ExecutorService queue=Executors.newSingleThreadExecutor();
  private long lastQueuedAt=0;
  private long confirmedWatchedMs=0;

  public PlaybackSync(Context context,String metaId,String type,String playingVideoId,
               String itemTitle,String itemPoster){
    app=context.getApplicationContext();
    titleId=metaId==null?"":metaId.trim();
    mediaType=type==null?"":type.trim();
    videoId=playingVideoId==null?"":playingVideoId.trim();
    title=itemTitle==null?"":itemTitle.trim();
    poster=itemPoster==null?"":itemPoster.trim();
  }

  public boolean hasIdentity(){
    if(titleId.isEmpty()||videoId.isEmpty())return false;
    if(!"movie".equals(mediaType)&&!"series".equals(mediaType))return false;
    return !"series".equals(mediaType)||!titleId.equals(videoId);
  }

  public synchronized void record(final long positionMs,final long durationMs,
                           final long watchedMs,boolean finalUpdate){
    if(!hasIdentity()||durationMs<=0||positionMs<0)return;
    long now=SystemClock.elapsedRealtime();
    if(!finalUpdate&&now-lastQueuedAt<25000L)return;
    lastQueuedAt=now;
    confirmedWatchedMs+=watchedMs;
    final long totalWatched=confirmedWatchedMs;
    queue.execute(new Runnable(){
      @Override public void run(){
        try{
          send(positionMs,durationMs,totalWatched);
        }catch(Exception ignored){}
      }
    });
  }

  private void send(long positionMs,long durationMs,long watchedMs) throws Exception {
    SharedPreferences prefs=app.getSharedPreferences("nuvio_stremio_prefs",Context.MODE_PRIVATE);
    saveLocalContinueWatching(prefs, positionMs, durationMs);
    String auth=prefs.getString("auth","");
    if(auth.isEmpty())return;

    // Check if authenticated with Nuvio (JWT)
    if (auth.startsWith("ey") && auth.contains(".")) {
      try {
        String baseUrl = prefs.getString("nuvio_api_url", "https://api.nuvio.tv");
        URL target = new URL(baseUrl + "/rest/v1/rpc/sync_push_watch_progress");
        HttpURLConnection c = (HttpURLConnection) target.openConnection();
        if (c instanceof HttpsURLConnection) {
          ((HttpsURLConnection) c).setSSLSocketFactory(VideoTls.build(app));
        }
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("apikey", "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN");
        c.setRequestProperty("Authorization", "Bearer " + auth);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.5");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);

        int profIntId = prefs.getInt("nuvio_active_profile_int_id", 1);
        if (profIntId <= 0) profIntId = 1;

        JSONObject entry = new JSONObject();
        entry.put("content_id", titleId);
        entry.put("content_type", mediaType);
        entry.put("video_id", videoId);
        entry.put("position", positionMs);
        entry.put("duration", durationMs);
        entry.put("last_watched", System.currentTimeMillis());

        if ("series".equals(mediaType) && videoId.contains(":")) {
          String[] parts = videoId.split(":");
          if (parts.length >= 3) {
            try {
              entry.put("season", Integer.parseInt(parts[1]));
              entry.put("episode", Integer.parseInt(parts[2]));
              entry.put("progress_key", titleId + "_s" + parts[1] + "e" + parts[2]);
            } catch (Exception ignored) {}
          }
        }
        if (!entry.has("progress_key")) {
          entry.put("progress_key", titleId);
        }

        JSONArray entries = new JSONArray();
        entries.put(entry);

        JSONObject payload = new JSONObject();
        payload.put("p_profile_id", profIntId);
        payload.put("p_entries", entries);

        byte[] data = payload.toString().getBytes("UTF-8");
        OutputStream os = c.getOutputStream();
        os.write(data);
        os.flush();
        os.close();
        int status = c.getResponseCode();
        c.disconnect();

        if (status >= 400) {
          // Direct table upsert fallback to /rest/v1/watch_progress
          entry.put("profile_id", profIntId);
          URL tableTarget = new URL(baseUrl + "/rest/v1/watch_progress");
          HttpURLConnection tc = (HttpURLConnection) tableTarget.openConnection();
          if (tc instanceof HttpsURLConnection) {
            ((HttpsURLConnection) tc).setSSLSocketFactory(VideoTls.build(app));
          }
          tc.setRequestMethod("POST");
          tc.setDoOutput(true);
          tc.setRequestProperty("Content-Type", "application/json");
          tc.setRequestProperty("apikey", "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN");
          tc.setRequestProperty("Authorization", "Bearer " + auth);
          tc.setRequestProperty("Prefer", "resolution=merge-duplicates");
          tc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; Gen5W) Nuvio/1.0.5");
          OutputStream tos = tc.getOutputStream();
          tos.write(entry.toString().getBytes("UTF-8"));
          tos.flush();
          tos.close();
          tc.getResponseCode();
          tc.disconnect();
        }
        return;
      } catch (Exception ignored) {}
    }

    // Fallback: Legacy Stremio datastorePut
    JSONObject state=new JSONObject();
    state.put("timeOffset",positionMs/1000L);
    state.put("duration",durationMs/1000L);
    state.put("video_id",videoId);
    state.put("lastWatched",isoNow());
    state.put("timesWatched",1);
    state.put("flaggedWatched",positionMs>=(durationMs*9/10)?1:0);

    JSONObject item=new JSONObject();
    item.put("_id",titleId);
    item.put("type",mediaType);
    item.put("name",title);
    if(!poster.isEmpty())item.put("poster",poster);
    item.put("state",state);
    item.put("temp",true);

    JSONArray changes=new JSONArray();
    changes.put(item);

    JSONObject payload=new JSONObject();
    payload.put("authKey",auth);
    payload.put("changes",changes);

    URL target=new URL("https://api.strem.io/api/datastorePut");
    HttpURLConnection c=(HttpURLConnection)target.openConnection();
    if(c instanceof HttpsURLConnection){
      ((HttpsURLConnection)c).setSSLSocketFactory(VideoTls.build(app));
    }
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setRequestProperty("Content-Type","application/json");
    c.setConnectTimeout(10000);
    c.setReadTimeout(15000);
    byte[] data=payload.toString().getBytes("UTF-8");
    OutputStream os=c.getOutputStream();
    os.write(data);
    os.flush();
    os.close();
    int status=c.getResponseCode();
    c.disconnect();
  }

  private void saveLocalContinueWatching(SharedPreferences prefs, long positionMs, long durationMs) {
    if (titleId.isEmpty()) return;
    try {
      String activeProfileId = prefs.getString("nuvio_active_profile_id", "");
      String existing = prefs.getString("nuvio_continue_watching", "[]");
      JSONArray arr = new JSONArray(existing);
      JSONArray next = new JSONArray();

      JSONObject current = new JSONObject();
      current.put("id", titleId);
      current.put("type", mediaType);
      current.put("name", title);
      current.put("poster", poster);
      current.put("positionMs", positionMs);
      current.put("durationMs", durationMs);
      current.put("videoId", videoId);
      current.put("profileId", activeProfileId);
      current.put("lastWatched", System.currentTimeMillis());

      if ("series".equals(mediaType) && videoId.contains(":")) {
        String[] parts = videoId.split(":");
        if (parts.length >= 3) {
          try {
            int s = Integer.parseInt(parts[1]);
            int e = Integer.parseInt(parts[2]);
            current.put("season", s);
            current.put("episode", e);
            current.put("videoLabel", "Season " + s + ", Episode " + e);
          } catch (Exception ignored) {}
        }
      }
      next.put(current);

      java.util.HashSet<String> seen = new java.util.HashSet<String>();
      seen.add(titleId);
      for (int i = 0; i < arr.length() && next.length() < 30; i++) {
        JSONObject item = arr.optJSONObject(i);
        if (item != null) {
          String itId = item.optString("id");
          if (itId.isEmpty() || seen.contains(itId)) continue;
          seen.add(itId);
          next.put(item);
        }
      }
      prefs.edit().putString("nuvio_continue_watching", next.toString()).apply();
    } catch (Exception ignored) {}
  }

  private static String isoNow(){
    SimpleDateFormat sdf=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    return sdf.format(new Date());
  }
}
