package com.spotifyvault.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

public class VaultBridge {
    private final Context ctx;
    private final DatabaseHelper db;
    private final MainActivity activity;

    public VaultBridge(MainActivity activity, DatabaseHelper db) {
        this.activity = activity;
        this.ctx = activity;
        this.db = db;
    }

    @JavascriptInterface
    public void showToast(String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    @JavascriptInterface
    public void saveConfig(String key, String value) {
        db.putConfig(key, value);
    }

    @JavascriptInterface
    public String getConfig(String key) {
        String v = db.getConfig(key);
        return v != null ? v : "";
    }

    @JavascriptInterface
    public String getConfigWithDefault(String key, String def) {
        String v = db.getConfig(key, def);
        return v != null ? v : def;
    }

    @JavascriptInterface
    public String getAllConfig() {
        try {
            JSONObject o = new JSONObject();
            String[] keys = new String[]{"client_id","redirect_uri","access_token","refresh_token","expires_at","scope","code_verifier","last_sync_ms","last_sync_human","auto_sync_enabled"};
            for (String k: keys) {
                String v = db.getConfig(k);
                if (v != null) o.put(k, v);
            }
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public int savePlays(String jsonArrayStr) {
        try {
            JSONArray arr = new JSONArray(jsonArrayStr);
            int n = db.insertPlays(arr);
            if (n>0) {
                invalidateStats();
                // update human
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
                db.putConfig("last_sync_human", sdf.format(new java.util.Date()));
            }
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    @JavascriptInterface
    public String getPlaysByDate(String yyyyMMdd) {
        try {
            JSONArray arr = db.getPlaysByDate(yyyyMMdd);
            return arr.toString();
        } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface
    public String getDatesWithPlays() {
        try {
            return db.getDatesWithPlays().toString();
        } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface
    public String getStats() {
        try {
            long now = System.currentTimeMillis();
            synchronized (this) {
                if (statsCache != null && now - statsAt < 10000) return statsCache;
            }
            String s = db.getStats().toString();
            synchronized (this) { statsCache = s; statsAt = now; }
            return s;
        } catch (Exception e) { return "{}"; }
    }

    private String statsCache = null;
    private long statsAt = 0;

    private synchronized void invalidateStats() { statsCache = null; statsAt = 0; }

    @JavascriptInterface
    public String getAllPlays(int limit, int offset) {
        try {
            return db.getAllPlays(limit, offset).toString();
        } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface
    public String getPlaysRange(String startDate, String endDate, int limit, int offset) {
        try {
            return db.getPlaysRange(startDate, endDate, limit, offset).toString();
        } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface
    public String searchPlays(String query, String sort, int limit, int offset) {
        try {
            return db.searchPlays(query, sort, limit, offset).toString();
        } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface
    public int countSearchPlays(String query) {
        try {
            return db.countSearchPlays(query);
        } catch (Exception e) { return 0; }
    }

    @JavascriptInterface
    public void clearAllPlays() {
        db.clearAllPlays();
        invalidateStats();
    }

    @JavascriptInterface
    public long getLastPlayedAtMs() {
        return db.getLastPlayedAtMs();
    }

    @JavascriptInterface
    public void setLastSyncMs(String ms) {
        db.putConfig("last_sync_ms", ms);
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        db.putConfig("last_sync_human", sdf.format(new java.util.Date()));
    }

    @JavascriptInterface
    public void openAuth(String url) {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.loadAuthUrl(url); }
        });
    }

    @JavascriptInterface
    public String getLastSyncHuman() {
        String v = db.getConfig("last_sync_human");
        return v != null ? v : "";
    }

    @JavascriptInterface
    public void scheduleSync() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.scheduleAlarm(); }
        });
    }

    @JavascriptInterface
    public void cancelSync() {
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.cancelAlarm(); }
        });
    }

    @JavascriptInterface
    public String exportJson() {
        try {
            JSONObject exp = new JSONObject();
            exp.put("stats", db.getStats());
            exp.put("plays", db.getAllPlays(10000, 0));
            exp.put("exported_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(new java.util.Date()));
            return exp.toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(ctx, "Não foi possível abrir: "+url, Toast.LENGTH_SHORT).show();
        }
    }

    @JavascriptInterface
    public void openAppInfo() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) { Toast.makeText(ctx, "Erro: "+e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    @JavascriptInterface
    public void openNotificationSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
            i.putExtra("android.provider.extra.APP_PACKAGE", ctx.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent f = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                f.setData(Uri.parse("package:" + ctx.getPackageName()));
                f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(f);
            } catch (Exception ignored) {}
        }
    }

    @JavascriptInterface
    public void openExactAlarmSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) { openAppInfo(); }
    }

    @JavascriptInterface
    public void openBatterySettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) { openAppInfo(); }
    }
}
