package com.spotifyvault.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class SyncService extends Service {
    private static final String TAG = "SpotifyVaultSync";
    private static final String CHANNEL_ID = "vault_sync";
    private static final int NOTIF_ID_ONGOING = 1001;
    private static final int NOTIF_ID_SUMMARY = 1002;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpotifyVault:Sync");
            }
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(10 * 60 * 1000L);
        } catch (Exception e) { Log.w(TAG, "wakelock acquire falhou", e); }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        acquireWakeLock();
        Notification ongoing = buildOngoingNotification("Sincronizando Spotify Vault...");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int fgsType = 0;
                try {
                    fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                } catch (Exception ignored) {}
                if (Build.VERSION.SDK_INT >= 34 && fgsType != 0) {
                    startForeground(NOTIF_ID_ONGOING, ongoing, fgsType);
                } else {
                    startForeground(NOTIF_ID_ONGOING, ongoing);
                }
            } else {
                startForeground(NOTIF_ID_ONGOING, ongoing);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground falhou, usando notify simples", e);
            try {
                NotificationManager nm0 = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm0 != null) nm0.notify(NOTIF_ID_ONGOING, ongoing);
            } catch (Exception ignored) {}
        }
        new Thread(new Runnable() {
            @Override public void run() {
                int inserted = 0;
                // janela desde último alarme (9h,12h,15h,18h,21h30) - fallback 12h para cobrir overnight
                long windowStart;
                try {
                    DatabaseHelper tmpDb = new DatabaseHelper(SyncService.this);
                    String lastRun = tmpDb.getConfig("last_alarm_run");
                    tmpDb.close();
                    if(lastRun!=null) windowStart = Long.parseLong(lastRun);
                    else windowStart = System.currentTimeMillis() - 12*60*60*1000L;
                    // se window muito antiga (>24h) limita a 12h
                    if(System.currentTimeMillis() - windowStart > 24*60*60*1000L) windowStart = System.currentTimeMillis() - 12*60*60*1000L;
                } catch(Exception e){ windowStart = System.currentTimeMillis() - 12*60*60*1000L; }
                try {
                    inserted = doSync();
                } catch (Exception e) {
                    Log.e(TAG, "sync error", e);
                    showErrorNotification(e.getMessage());
                } finally {
                    // after sync, show summary notification com stats desde windowStart
                    try {
                        showSummaryNotification(inserted, windowStart);
                    } catch (Exception e) {
                        Log.e(TAG, "summary notif error", e);
                    }
                    // cancela ongoing e sai do foreground
                    try {
                        stopForeground(true);
                    } catch (Exception ignored) {}
                    try {
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null) nm.cancel(NOTIF_ID_ONGOING);
                    } catch (Exception ignored) {}
                    releaseWakeLock();
                    // reschedule next alarm garantido mesmo com app fechado
                    AlarmReceiver.scheduleExactAlarm(SyncService.this);
                    stopSelf();
                }
            }
        }).start();
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Spotify Vault Sync", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Sincronização a cada 8h das reproduções do Spotify");
                ch.enableLights(false);
                ch.enableVibration(false);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            // second channel for summary with higher importance
            String summaryId = CHANNEL_ID + "_summary";
            if (nm != null && nm.getNotificationChannel(summaryId) == null) {
                NotificationChannel ch2 = new NotificationChannel(summaryId, "Spotify Vault Resumo", NotificationManager.IMPORTANCE_HIGH);
                ch2.setDescription("Resumo 9h,12h,15h,18h,21h30 Brasília com músicas e artista top");
                nm.createNotificationChannel(ch2);
            }
        }
    }

    private Notification buildOngoingNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        b.setContentTitle("Spotify Vault")
         .setContentText(text)
         .setSmallIcon(R.drawable.ic_vault)
         .setOngoing(true)
         .setOnlyAlertOnce(true)
         .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            return b.build();
        } else {
            return b.getNotification();
        }
    }

    private void showSummaryNotification(int insertedThisSync, long windowStart) {
        DatabaseHelper db = new DatabaseHelper(this);
        JSONObject win = db.getWindowStats(windowStart);
        long count8h = win.optLong("count", 0);
        String topArtist = win.optString("top_artist", "");
        long topCount = win.optLong("top_artist_count", 0);
        db.close();

        // also inserted count for debugging
        Log.i(TAG, "summary window count=" + count8h + " top=" + topArtist + " insertedThisSync=" + insertedThisSync);

        // Build content - período desde último alarme (9h,12h,15h,18h,21h30 Brasília)
        String title;
        String content;
        if (count8h == 0) {
            title = "Spotify Vault • sem músicas no período";
            content = "Nenhuma reprodução no período. Abra o app para sincronizar.";
        } else {
            title = "Spotify Vault • " + count8h + " música" + (count8h==1?"":"s") + " no período";
            if (topArtist != null && !topArtist.isEmpty()) {
                content = "Top: " + topArtist + " (" + topCount + "×) • +" + insertedThisSync + " novas no banco";
            } else {
                content = "+" + insertedThisSync + " novas no banco • " + count8h + " no período";
            }
        }

        // If no new inserted and no window count, still show? Requirement says always notify. We'll show.
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // pass extra to open timeline?
        open.putExtra("open_tab", "timeline");
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String summaryChannel = CHANNEL_ID + "_summary";
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) summaryChannel = CHANNEL_ID;

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, summaryChannel);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_HIGH);
        }
        b.setContentTitle(title)
         .setContentText(content)
         .setStyle(new Notification.BigTextStyle().bigText(content))
         .setSmallIcon(R.drawable.ic_vault)
         .setAutoCancel(true)
         .setContentIntent(pi)
         .setOnlyAlertOnce(false);

        // Add action to open app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            b.addAction(new Notification.Action.Builder(R.drawable.ic_vault, "Ver timeline", pi).build());
        }

        Notification notif;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            notif = b.build();
        } else {
            notif = b.getNotification();
        }
        // ensure we have permission (Android 13+), if not, notification will be dropped silently
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIF_ID_SUMMARY, notif);
            } catch (SecurityException se) {
                Log.w(TAG, "notification permission missing", se);
            }
        }
    }

    private void showErrorNotification(String err) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String ch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? CHANNEL_ID + "_summary" : CHANNEL_ID;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, ch);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_DEFAULT);
        }
        b.setContentTitle("Spotify Vault • Erro no sync")
         .setContentText(err != null ? err.substring(0, Math.min(100, err.length())) : "Falha")
         .setSmallIcon(R.drawable.ic_vault)
         .setAutoCancel(true)
         .setContentIntent(pi);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try { nm.notify(NOTIF_ID_SUMMARY + 1, b.build()); } catch (Exception ignored) {}
        }
    }

    private int doSync() throws Exception {
        DatabaseHelper db = new DatabaseHelper(this);
        String accessToken = db.getConfig("access_token");
        String refreshToken = db.getConfig("refresh_token");
        String expiresAtStr = db.getConfig("expires_at");
        String clientId = db.getConfig("client_id");
        long expiresAt = 0;
        try { expiresAt = Long.parseLong(expiresAtStr); } catch (Exception ignored) {}
        long now = System.currentTimeMillis();
        if (accessToken == null || accessToken.isEmpty()) {
            Log.w(TAG, "no access token, skipping sync");
            db.close();
            return 0;
        }
        // refresh if needed (1 min before)
        if (now + 60000 > expiresAt && refreshToken != null && !refreshToken.isEmpty() && clientId != null) {
            Log.i(TAG, "refreshing token");
            String newAccess = refreshAccessToken(clientId, refreshToken, db);
            if (newAccess != null) accessToken = newAccess;
            else Log.w(TAG, "refresh failed");
        }

        long lastMs = db.getLastPlayedAtMs();
        Log.i(TAG, "lastMs=" + lastMs);
        // fetch loop
        String cursorAfter = lastMs > 0 ? String.valueOf(lastMs) : null;
        int totalInserted = 0;
        int loops = 0;
        while (loops < 5) { // max 5*50=250 tracks per sync to avoid infinite
            String urlStr = "https://api.spotify.com/v1/me/player/recently-played?limit=50";
            if (cursorAfter != null) urlStr += "&after=" + URLEncoder.encode(cursorAfter, "UTF-8");
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code == 401) {
                // try refresh once
                if (refreshToken != null && loops==0) {
                    String newAccess = refreshAccessToken(clientId, refreshToken, db);
                    if (newAccess != null) {
                        accessToken = newAccess;
                        continue;
                    }
                }
                Log.w(TAG, "401 unauthorized");
                break;
            }
            if (code != 200) {
                Log.w(TAG, "sync http code " + code);
                InputStream err = conn.getErrorStream();
                if (err != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(err));
                    String line; StringBuilder sb=new StringBuilder();
                    while ((line=br.readLine())!=null) sb.append(line);
                    Log.w(TAG, sb.toString());
                }
                break;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line=br.readLine())!=null) sb.append(line);
            br.close();
            JSONObject resp = new JSONObject(sb.toString());
            JSONArray items = resp.optJSONArray("items");
            if (items == null || items.length()==0) {
                Log.i(TAG, "no more items");
                break;
            }
            JSONArray toInsert = new JSONArray();
            String newestCursor = null;
            for (int i=0;i<items.length();i++) {
                JSONObject it = items.getJSONObject(i);
                String played_at = it.optString("played_at", "");
                JSONObject track = it.optJSONObject("track");
                if (track == null) continue;
                String track_id = track.optString("id","");
                String track_name = track.optString("name","");
                JSONArray artists = track.optJSONArray("artists");
                JSONArray artistIds = new JSONArray();
                JSONArray artistNames = new JSONArray();
                if (artists != null) {
                    for (int a=0;a<artists.length();a++) {
                        JSONObject ar = artists.getJSONObject(a);
                        artistIds.put(ar.optString("id",""));
                        artistNames.put(ar.optString("name",""));
                    }
                }
                JSONObject album = track.optJSONObject("album");
                String album_id = album != null ? album.optString("id","") : "";
                String album_name = album != null ? album.optString("name","") : "";
                String album_image = "";
                if (album != null) {
                    JSONArray imgs = album.optJSONArray("images");
                    if (imgs != null && imgs.length()>0) album_image = imgs.getJSONObject(0).optString("url","");
                }
                long duration_ms = track.optLong("duration_ms",0);
                int explicit = track.optBoolean("explicit",false) ? 1 : 0;
                JSONObject context = it.optJSONObject("context");
                String ctxType = context != null ? context.optString("type","") : "";
                String ctxUri = context != null ? context.optString("uri","") : "";

                JSONObject flat = new JSONObject();
                flat.put("played_at", played_at);
                // compute ms for sorting
                long playedMs = 0;
                try {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                    sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                    java.util.Date d = sdf.parse(played_at);
                    if (d!=null) playedMs = d.getTime();
                } catch (Exception e) {
                    try {
                        java.text.SimpleDateFormat sdf2 = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                        sdf2.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        java.util.Date d2 = sdf2.parse(played_at);
                        if (d2!=null) playedMs = d2.getTime();
                    } catch (Exception ignored) {}
                }
                flat.put("played_at_ms", playedMs);
                flat.put("track_id", track_id);
                flat.put("track_name", track_name);
                flat.put("artist_ids", artistIds.toString());
                flat.put("artist_names", artistNames.toString());
                flat.put("album_id", album_id);
                flat.put("album_name", album_name);
                flat.put("album_image", album_image);
                flat.put("duration_ms", duration_ms);
                flat.put("explicit", explicit);
                flat.put("context_type", ctxType);
                flat.put("context_uri", ctxUri);
                flat.put("raw_json", it.toString());
                toInsert.put(flat);
                newestCursor = played_at;
            }
            int inserted = db.insertPlays(toInsert);
            totalInserted += inserted;
            Log.i(TAG, "inserted " + inserted + " / " + toInsert.length());
            // determine next cursor: use 'cursors.after' from response or last played_at ms
            JSONObject cursors = resp.optJSONObject("cursors");
            String nextAfter = cursors != null ? cursors.optString("after", null) : null;
            if (nextAfter != null && !nextAfter.isEmpty() && !nextAfter.equals(cursorAfter) && items.length()==50) {
                cursorAfter = nextAfter;
            } else if (newestCursor != null && items.length()==50) {
                // fallback: use last played_at ms
                try {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                    sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                    java.util.Date d = sdf.parse(newestCursor);
                    if (d!=null) cursorAfter = String.valueOf(d.getTime());
                    else break;
                } catch (Exception e) { break; }
            } else {
                break;
            }
            loops++;
            if (inserted==0 && loops>1) break; // duplicates only
        }
        Log.i(TAG, "sync done totalInserted=" + totalInserted);
        if (totalInserted>0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            db.putConfig("last_sync_human", sdf.format(new java.util.Date()));
        }
        // always update last run time for scheduling
        db.putConfig("last_alarm_run", String.valueOf(System.currentTimeMillis()));
        db.close();
        return totalInserted;
    }

    private String refreshAccessToken(String clientId, String refreshToken, DatabaseHelper db) {
        try {
            URL url = new URL("https://accounts.spotify.com/api/token");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken,"UTF-8") + "&client_id=" + URLEncoder.encode(clientId,"UTF-8");
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            int code = conn.getResponseCode();
            InputStream is = (code==200) ? conn.getInputStream() : conn.getErrorStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line=br.readLine())!=null) sb.append(line);
            br.close();
            if (code==200) {
                JSONObject resp = new JSONObject(sb.toString());
                String newAccess = resp.optString("access_token","");
                String newRefresh = resp.optString("refresh_token", null);
                long expiresIn = resp.optLong("expires_in", 3600);
                long expiresAt = System.currentTimeMillis() + expiresIn*1000;
                db.putConfig("access_token", newAccess);
                db.putConfig("expires_at", String.valueOf(expiresAt));
                if (newRefresh != null && !newRefresh.isEmpty()) db.putConfig("refresh_token", newRefresh);
                String scope = resp.optString("scope", null);
                if (scope!=null) db.putConfig("scope", scope);
                Log.i(TAG, "refresh ok expiresAt=" + expiresAt);
                return newAccess;
            } else {
                Log.w(TAG, "refresh failed code " + code + " body " + sb.toString());
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "refresh exception", e);
            return null;
        }
    }
}
