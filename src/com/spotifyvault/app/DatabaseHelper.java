package com.spotifyvault.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "spotify_vault.db";
    private static final int DB_VERSION = 4;

    // Reuso de SimpleDateFormat (caro p/ criar por linha) — ThreadLocal pois SDF não é thread-safe
    private static final ThreadLocal<java.text.SimpleDateFormat> ISO_PARSER = new ThreadLocal<java.text.SimpleDateFormat>() {
        @Override protected java.text.SimpleDateFormat initialValue() {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return sdf;
        }
    };

    public DatabaseHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS plays (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "played_at TEXT NOT NULL UNIQUE," +
                "played_at_ms INTEGER," +
                "track_id TEXT," +
                "track_name TEXT," +
                "artist_ids TEXT," +
                "artist_names TEXT," +
                "album_id TEXT," +
                "album_name TEXT," +
                "album_image TEXT," +
                "duration_ms INTEGER," +
                "explicit INTEGER," +
                "context_type TEXT," +
                "context_uri TEXT," +
                "raw_json TEXT," +
                "inserted_at INTEGER," +
                "ms_played INTEGER," +
                "skipped INTEGER," +
                "reason_start TEXT," +
                "reason_end TEXT," +
                "shuffle INTEGER," +
                "offline INTEGER," +
                "platform TEXT" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plays_played_at ON plays(played_at_ms)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plays_track_id ON plays(track_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plays_skipped ON plays(skipped)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plays_ms_desc ON plays(played_at_ms DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plays_track_time ON plays(track_id, played_at_ms)");
    }

    private void createV3Indices(SQLiteDatabase db) {
        try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_plays_ms_desc ON plays(played_at_ms DESC)"); } catch (Exception ignored) {}
        try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_plays_track_time ON plays(track_id, played_at_ms)"); } catch (Exception ignored) {}
        try { db.execSQL("DROP INDEX IF EXISTS idx_plays_date"); } catch (Exception ignored) {}
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE plays ADD COLUMN ms_played INTEGER"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE plays ADD COLUMN skipped INTEGER"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE plays ADD COLUMN reason_start TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE plays ADD COLUMN reason_end TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE plays ADD COLUMN shuffle INTEGER"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE plays ADD COLUMN offline INTEGER"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE plays ADD COLUMN platform TEXT"); } catch (Exception ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_plays_skipped ON plays(skipped)"); } catch (Exception ignored) {}
        }
        if (oldVersion < 3) {
            createV3Indices(db);
        }
        if (oldVersion < 4) {
            try { db.execSQL("DROP INDEX IF EXISTS idx_plays_date"); } catch (Exception ignored) {}
            createV3Indices(db);
        }
    }

    public synchronized void putConfig(String key, String value) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        db.insertWithOnConflict("config", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String getConfig(String key) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT value FROM config WHERE key=?", new String[]{key});
        try {
            if (c.moveToFirst()) return c.getString(0);
            return null;
        } finally { c.close(); }
    }

    public synchronized String getConfig(String key, String def) {
        String v = getConfig(key);
        return v != null ? v : def;
    }

    // Insert plays from JSON array generated by JS or Service
    // Returns count inserted (ignoring duplicates)
    public synchronized int insertPlays(JSONArray arr) {
        if (arr == null || arr.length() == 0) return 0;
        SQLiteDatabase db = getWritableDatabase();
        int inserted = 0;
        db.beginTransaction();
        try {
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject o = arr.getJSONObject(i);
                    String played_at = o.optString("played_at", null);
                    if (played_at == null || played_at.isEmpty()) continue;
                    long played_at_ms = o.optLong("played_at_ms", 0);
                    if (played_at_ms == 0) {
                        try {
                            java.util.Date d = ISO_PARSER.get().parse(played_at);
                            if (d != null) played_at_ms = d.getTime();
                        } catch (Exception e) {
                            played_at_ms = System.currentTimeMillis();
                        }
                    }
                    String track_id = o.optString("track_id", "");
                    String track_name = o.optString("track_name", "");
                    String artist_ids = o.optString("artist_ids", "[]");
                    String artist_names = o.optString("artist_names", "[]");
                    String album_id = o.optString("album_id", "");
                    String album_name = o.optString("album_name", "");
                    String album_image = o.optString("album_image", "");
                    long duration_ms = o.optLong("duration_ms", 0);
                    int explicit = o.optInt("explicit", 0);
                    String context_type = o.optString("context_type", "");
                    String context_uri = o.optString("context_uri", "");
                    String raw_json = o.optString("raw_json", o.toString());
                    long ms_played = o.optLong("ms_played", duration_ms);
                    int skipped = o.optInt("skipped", 0);
                    String reason_start = o.optString("reason_start", "");
                    String reason_end = o.optString("reason_end", "");
                    int shuffle = o.optInt("shuffle", 0);
                    int offline = o.optInt("offline", 0);
                    String platform = o.optString("platform", "");

                    ContentValues cv = new ContentValues();
                    cv.put("played_at", played_at);
                    cv.put("played_at_ms", played_at_ms);
                    cv.put("track_id", track_id);
                    cv.put("track_name", track_name);
                    cv.put("artist_ids", artist_ids);
                    cv.put("artist_names", artist_names);
                    cv.put("album_id", album_id);
                    cv.put("album_name", album_name);
                    cv.put("album_image", album_image);
                    cv.put("duration_ms", duration_ms);
                    cv.put("explicit", explicit);
                    cv.put("context_type", context_type);
                    cv.put("context_uri", context_uri);
                    cv.put("raw_json", raw_json);
                    cv.put("inserted_at", System.currentTimeMillis());
                    cv.put("ms_played", ms_played);
                    cv.put("skipped", skipped);
                    cv.put("reason_start", reason_start);
                    cv.put("reason_end", reason_end);
                    cv.put("shuffle", shuffle);
                    cv.put("offline", offline);
                    cv.put("platform", platform);

                    long res = db.insertWithOnConflict("plays", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                    if (res != -1) inserted++;
                } catch (Exception ex) {
                    // skip malformed
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (inserted > 0) {
            // update last_sync to max played_at_ms
            try {
                Cursor c = db.rawQuery("SELECT MAX(played_at_ms) FROM plays", null);
                if (c.moveToFirst()) {
                    long max = c.getLong(0);
                    if (max > 0) putConfig("last_sync_ms", String.valueOf(max));
                }
                c.close();
            } catch (Exception ignored) {}
        }
        return inserted;
    }

    public synchronized JSONArray getPlaysByDate(String yyyyMMdd) {
        // yyyy-MM-dd em Horário de Brasília — usa BETWEEN em played_at_ms (usa índice) em vez de date(col)
        SQLiteDatabase db = getReadableDatabase();
        long[] bounds = dayBoundsBRT(yyyyMMdd);
        Cursor c = db.rawQuery("SELECT played_at, track_id, track_name, artist_names, album_name, album_image, duration_ms, explicit, context_type, context_uri, ms_played, skipped, reason_end, reason_start, platform, played_at_ms FROM plays WHERE played_at_ms>=? AND played_at_ms<? ORDER BY played_at_ms DESC LIMIT 500", new String[]{String.valueOf(bounds[0]), String.valueOf(bounds[1])});
        JSONArray arr = new JSONArray();
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                try {
                    o.put("played_at", c.getString(0));
                    o.put("track_id", c.getString(1));
                    o.put("track_name", c.getString(2));
                    o.put("artist_names", c.getString(3) != null ? new JSONArray(c.getString(3)) : new JSONArray());
                    o.put("album_name", c.getString(4));
                    o.put("album_image", c.getString(5));
                    o.put("duration_ms", c.getLong(6));
                    o.put("explicit", c.getInt(7));
                    o.put("context_type", c.getString(8));
                    o.put("context_uri", c.getString(9));
                    o.put("ms_played", c.isNull(10) ? c.getLong(6) : c.getLong(10));
                    o.put("skipped", c.isNull(11) ? 0 : c.getInt(11));
                    o.put("reason_end", c.isNull(12) ? "" : c.getString(12));
                    o.put("reason_start", c.isNull(13) ? "" : c.getString(13));
                    o.put("platform", c.isNull(14) ? "" : c.getString(14));
                    arr.put(o);
                } catch (Exception e) {}
            }
        } finally { c.close(); }
        return arr;
    }

    // Converte yyyy-MM-dd (BRT) em [startMs, endMs) para query indexada
    private long[] dayBoundsBRT(String yyyyMMdd) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
            java.util.Date d = sdf.parse(yyyyMMdd);
            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
            cal.setTime(d);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            long start = cal.getTimeInMillis();
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
            return new long[]{start, cal.getTimeInMillis()};
        } catch (Exception e) {
            return new long[]{0, Long.MAX_VALUE};
        }
    }

    public synchronized JSONArray getPlaysRange(String startDate, String endDate, int limit, int offset) {
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT played_at, track_id, track_name, artist_names, album_name, album_image, duration_ms, explicit, context_type, context_uri, ms_played, skipped, reason_end FROM plays ";
        String[] args;
        if (startDate != null && endDate != null) {
            sql += "WHERE date(played_at_ms/1000, 'unixepoch', 'localtime') BETWEEN ? AND ? ";
            args = new String[]{startDate, endDate};
        } else if (startDate != null) {
            sql += "WHERE date(played_at_ms/1000, 'unixepoch', 'localtime') >= ? ";
            args = new String[]{startDate};
        } else {
            args = new String[]{};
        }
        sql += "ORDER BY played_at_ms DESC LIMIT ? OFFSET ?";
        // need to append limit/offset to args
        String[] finalArgs = new String[args.length + 2];
        System.arraycopy(args, 0, finalArgs, 0, args.length);
        finalArgs[args.length] = String.valueOf(limit);
        finalArgs[args.length+1] = String.valueOf(offset);
        Cursor c = db.rawQuery(sql, finalArgs);
        JSONArray arr = new JSONArray();
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                try {
                    o.put("played_at", c.getString(0));
                    o.put("track_id", c.getString(1));
                    o.put("track_name", c.getString(2));
                    o.put("artist_names", c.getString(3) != null ? new JSONArray(c.getString(3)) : new JSONArray());
                    o.put("album_name", c.getString(4));
                    o.put("album_image", c.getString(5));
                    o.put("duration_ms", c.getLong(6));
                    o.put("explicit", c.getInt(7));
                    o.put("context_type", c.getString(8));
                    o.put("context_uri", c.getString(9));
                    o.put("ms_played", c.isNull(10) ? c.getLong(6) : c.getLong(10));
                    o.put("skipped", c.isNull(11) ? 0 : c.getInt(11));
                    o.put("reason_end", c.isNull(12) ? "" : c.getString(12));
                    arr.put(o);
                } catch (Exception e) {}
            }
        } finally { c.close(); }
        return arr;
    }

    public synchronized JSONArray getAllPlays(int limit, int offset) {
        return getPlaysRange(null, null, limit, offset);
    }

    /** Alto risco: busca/ordenação no SQL (paginado, sem raw_json). */
    public synchronized JSONArray searchPlays(String query, String sort, int limit, int offset) {
        PlaysQuery.Built b = PlaysQuery.buildSearch(query, sort, limit, offset);
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(b.sql, b.args);
        JSONArray arr = new JSONArray();
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                try {
                    o.put("played_at", c.getString(0));
                    o.put("track_id", c.getString(1));
                    o.put("track_name", c.getString(2));
                    o.put("artist_names", c.getString(3) != null ? new JSONArray(c.getString(3)) : new JSONArray());
                    o.put("album_name", c.getString(4));
                    o.put("album_image", c.getString(5));
                    o.put("duration_ms", c.getLong(6));
                    o.put("explicit", c.getInt(7));
                    o.put("context_type", c.getString(8));
                    o.put("context_uri", c.getString(9));
                    o.put("ms_played", c.isNull(10) ? c.getLong(6) : c.getLong(10));
                    o.put("skipped", c.isNull(11) ? 0 : c.getInt(11));
                    o.put("reason_end", c.isNull(12) ? "" : c.getString(12));
                    o.put("played_at_ms", c.getLong(13));
                    arr.put(o);
                } catch (Exception e) {}
            }
        } finally { c.close(); }
        return arr;
    }

    public synchronized int countSearchPlays(String query) {
        PlaysQuery.BuiltCount b = PlaysQuery.buildCount(query);
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(b.sql, b.args);
        try {
            if (c.moveToFirst()) return c.getInt(0);
            return 0;
        } finally { c.close(); }
    }

    public synchronized JSONObject getStats() {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject stats = new JSONObject();
        try {
            Cursor c1 = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(duration_ms),0), COUNT(DISTINCT track_id) FROM plays", null);
            if (c1.moveToFirst()) {
                stats.put("total_plays", c1.getLong(0));
                stats.put("total_duration_ms", c1.getLong(1));
                stats.put("unique_tracks", c1.getLong(2));
            }
            c1.close();
            // unique artists correto - parseia JSON arrays individuais (Brasília)
            try {
                Cursor ca = db.rawQuery("SELECT DISTINCT artist_names FROM plays", null);
                java.util.HashSet<String> uniq = new java.util.HashSet<>();
                while(ca.moveToNext()){
                    String raw = ca.getString(0);
                    if(raw==null) continue;
                    try{
                        JSONArray arr = new JSONArray(raw);
                        for(int i=0;i<arr.length();i++){
                            String n = arr.getString(i);
                            if(n!=null) {
                                n=n.trim();
                                if(!n.isEmpty()) uniq.add(n.toLowerCase(java.util.Locale.ROOT));
                            }
                        }
                    } catch(Exception e){
                        String t = raw.trim();
                        if(!t.isEmpty()) uniq.add(t.toLowerCase(java.util.Locale.ROOT));
                    }
                }
                ca.close();
                stats.put("unique_artists", uniq.size());
                stats.put("unique_artists_approx", uniq.size());
            } catch(Exception e){
                stats.put("unique_artists", 0);
                stats.put("unique_artists_approx", 0);
            }

            // Top artists - need to parse artist_names JSON arrays; do JS side for accuracy, but provide simple aggregation via raw extraction
            // Instead provide daily counts
            Cursor c2 = db.rawQuery("SELECT date(played_at_ms/1000, 'unixepoch','localtime') as d, COUNT(*) as cnt, SUM(duration_ms) as dur FROM plays GROUP BY d ORDER BY d DESC LIMIT 30", null);
            JSONArray daily = new JSONArray();
            while (c2.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("date", c2.getString(0));
                o.put("plays", c2.getLong(1));
                o.put("duration_ms", c2.getLong(2));
                daily.put(o);
            }
            c2.close();
            stats.put("daily", daily);

            // Top tracks
            Cursor c3 = db.rawQuery("SELECT track_id, track_name, artist_names, COUNT(*) as cnt, SUM(duration_ms) as dur, MAX(album_image) as img FROM plays GROUP BY track_id ORDER BY cnt DESC LIMIT 20", null);
            JSONArray topTracks = new JSONArray();
            while (c3.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("track_id", c3.getString(0));
                o.put("track_name", c3.getString(1));
                try { o.put("artist_names", new JSONArray(c3.getString(2))); } catch (Exception e) { o.put("artist_names", new JSONArray()); }
                o.put("plays", c3.getLong(3));
                o.put("duration_ms", c3.getLong(4));
                o.put("album_image", c3.getString(5));
                topTracks.put(o);
            }
            c3.close();
            stats.put("top_tracks", topTracks);

            // Top artists aggregated by artist_names string - inclui artist_ids para buscar imagem
            Cursor c4 = db.rawQuery("SELECT artist_names, artist_ids, COUNT(*) as cnt, MAX(played_at_ms) as last FROM plays GROUP BY artist_names ORDER BY cnt DESC LIMIT 20", null);
            JSONArray topArtists = new JSONArray();
            while (c4.moveToNext()) {
                JSONObject o = new JSONObject();
                try { o.put("artist_names", new JSONArray(c4.getString(0))); } catch (Exception e) { o.put("artist_names", new JSONArray()); }
                // artist_ids é JSON array, pega primeiro id para imagem
                String idsRaw = c4.getString(1);
                try {
                    JSONArray ids = new JSONArray(idsRaw);
                    o.put("artist_ids", ids);
                    if(ids.length()>0) o.put("artist_id", ids.getString(0));
                } catch (Exception e) { o.put("artist_ids", new JSONArray()); }
                o.put("plays", c4.getLong(2));
                o.put("last_played_ms", c4.getLong(3));
                topArtists.put(o);
            }
            c4.close();
            stats.put("top_artists", topArtists);

            // Hourly distribution - Horário de Brasília (localtime)
            Cursor c5 = db.rawQuery("SELECT CAST(strftime('%H', played_at_ms/1000, 'unixepoch','localtime') AS INTEGER) as h, COUNT(*) FROM plays GROUP BY h ORDER BY h", null);
            JSONArray hourly = new JSONArray();
            // fill 0-23
            long[] hours = new long[24];
            while (c5.moveToNext()) {
                int h = c5.getInt(0);
                if (h>=0 && h<24) hours[h] = c5.getLong(1);
            }
            c5.close();
            for (int i=0;i<24;i++) hourly.put(hours[i]);
            stats.put("hourly", hourly);

            // Daypart - Horário de Brasília
            try {
                long madrugada=0, manha=0, tarde=0, noite=0;
                for(int i=0;i<24;i++){
                    long v=hours[i];
                    if(i>=0 && i<6) madrugada+=v;
                    else if(i<12) manha+=v;
                    else if(i<18) tarde+=v;
                    else noite+=v;
                }
                JSONObject dp = new JSONObject();
                dp.put("madrugada", madrugada);
                dp.put("manha", manha);
                dp.put("tarde", tarde);
                dp.put("noite", noite);
                stats.put("daypart", dp);
                // period label for personality
                String peakPeriod="noite";
                long max = noite;
                if(manha>max){max=manha; peakPeriod="manha";}
                if(tarde>max){max=tarde; peakPeriod="tarde";}
                if(madrugada>max){max=madrugada; peakPeriod="madrugada";}
                stats.put("peak_period", peakPeriod);
            } catch (Exception ignored) {}

            // Monthly evolution - últimos 12 meses localtime
            try {
                Cursor cM = db.rawQuery("SELECT strftime('%Y-%m', played_at_ms/1000, 'unixepoch','localtime') as m, COUNT(*) as cnt, SUM(duration_ms) as dur FROM plays GROUP BY m ORDER BY m DESC LIMIT 12", null);
                JSONArray monthly = new JSONArray();
                while(cM.moveToNext()){
                    JSONObject o = new JSONObject();
                    o.put("month", cM.getString(0));
                    o.put("plays", cM.getLong(1));
                    o.put("duration_ms", cM.getLong(2));
                    monthly.put(o);
                }
                cM.close();
                stats.put("monthly", monthly);
            } catch (Exception ignored) {}

            // Streaks - dias consecutivos com plays (Brasília)
            try {
                Cursor cDates = db.rawQuery("SELECT DISTINCT date(played_at_ms/1000, 'unixepoch','localtime') FROM plays ORDER BY 1 ASC", null);
                java.util.ArrayList<String> allDates = new java.util.ArrayList<>();
                while(cDates.moveToNext()) allDates.add(cDates.getString(0));
                cDates.close();
                int longest=0, current=0;
                String prev=null;
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                for(String d: allDates){
                    if(prev==null){
                        current=1;
                    } else {
                        java.util.Date pd = sdf.parse(prev);
                        java.util.Date cd = sdf.parse(d);
                        long diff = (cd.getTime()-pd.getTime())/(24*60*60*1000);
                        if(diff==1) current++;
                        else if(diff>1) current=1;
                    }
                    if(current>longest) longest=current;
                    prev=d;
                }
                stats.put("longest_streak", longest);
                // current streak (até hoje)
                int curStreak=0;
                if(!allDates.isEmpty()){
                    java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
                    String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.getTime());
                    // check backwards from today
                    java.util.HashSet<String> set = new java.util.HashSet<>(allDates);
                    String cur = today;
                    while(set.contains(cur)){
                        curStreak++;
                        java.util.Date cd = sdf.parse(cur);
                        cal.setTime(cd);
                        cal.add(java.util.Calendar.DATE, -1);
                        cur = sdf.format(cal.getTime());
                    }
                }
                stats.put("current_streak", curStreak);
                stats.put("active_days", allDates.size());
            } catch (Exception ignored) {}

            // Favoritos por período - artista em tempo (ms_played), música em execuções (Brasília)
            try {
                JSONObject fav = new JSONObject();
                java.util.TimeZone tz = java.util.TimeZone.getTimeZone("America/Sao_Paulo");
                java.util.Calendar cal = java.util.Calendar.getInstance(tz);
                // day
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
                cal.set(java.util.Calendar.MINUTE, 0);
                cal.set(java.util.Calendar.SECOND, 0);
                cal.set(java.util.Calendar.MILLISECOND, 0);
                long startDay = cal.getTimeInMillis();
                // week (últimos 7 dias incluindo hoje)
                java.util.Calendar calWeek = (java.util.Calendar) cal.clone();
                calWeek.add(java.util.Calendar.DATE, -6);
                long startWeek = calWeek.getTimeInMillis();
                // month
                java.util.Calendar calMonth = (java.util.Calendar) cal.clone();
                calMonth.set(java.util.Calendar.DAY_OF_MONTH, 1);
                long startMonth = calMonth.getTimeInMillis();
                // year
                java.util.Calendar calYear = (java.util.Calendar) cal.clone();
                calYear.set(java.util.Calendar.DAY_OF_YEAR, 1);
                long startYear = calYear.getTimeInMillis();
                long[] starts = new long[]{startDay, startWeek, startMonth, startYear, 0};
                String[] keys = new String[]{"day","week","month","year","all"};
                for(int idx=0; idx<keys.length; idx++){
                    String key = keys[idx];
                    long since = starts[idx];
                    JSONObject period = new JSONObject();
                    String where = since>0 ? "WHERE played_at_ms >= " + since : "";
                    // artista mais ouvido em tempo
                    Cursor ca = db.rawQuery("SELECT artist_names, artist_ids, SUM(COALESCE(ms_played, duration_ms)) as total FROM plays " + where + " GROUP BY artist_names ORDER BY total DESC LIMIT 1", null);
                    if(ca.moveToFirst()){
                        String namesRaw = ca.getString(0);
                        String idsRaw = ca.getString(1);
                        long totalTime = ca.getLong(2);
                        try { period.put("top_artist_names", new JSONArray(namesRaw)); } catch(Exception e){ period.put("top_artist_names", new JSONArray()); period.put("top_artist_raw", namesRaw); }
                        try { JSONArray ids = new JSONArray(idsRaw); period.put("top_artist_ids", ids); if(ids.length()>0) period.put("top_artist_id", ids.getString(0)); } catch(Exception e){}
                        period.put("top_artist_time_ms", totalTime);
                        // count plays desse artista no período
                        // extra: plays count for that artist
                        try {
                            String topName = new JSONArray(namesRaw).optString(0, "");
                            // not precise, but keep time as primary
                        } catch(Exception ignored2){}
                    }
                    ca.close();
                    // música mais ouvida em execuções
                    Cursor ct = db.rawQuery("SELECT track_id, track_name, artist_names, COUNT(*) as cnt, MAX(album_image) as img FROM plays " + where + " GROUP BY track_id ORDER BY cnt DESC LIMIT 1", null);
                    if(ct.moveToFirst()){
                        period.put("top_track_id", ct.getString(0));
                        period.put("top_track_name", ct.getString(1));
                        try { period.put("top_track_artists", new JSONArray(ct.getString(2))); } catch(Exception e){ period.put("top_track_artists", new JSONArray()); }
                        period.put("top_track_plays", ct.getLong(3));
                        period.put("top_track_image", ct.getString(4));
                    }
                    ct.close();
                    // fallback counts
                    Cursor cc = db.rawQuery("SELECT COUNT(*) FROM plays " + where, null);
                    if(cc.moveToFirst()) period.put("plays", cc.getLong(0));
                    cc.close();
                    fav.put(key, period);
                }
                stats.put("favorites", fav);
            } catch (Exception e) {
                try { stats.put("favorites_error", e.getMessage()); } catch(Exception ignored){}
            }

            // total minutes
            long totalMs = stats.optLong("total_duration_ms",0);
            stats.put("total_minutes", totalMs/60000);
            stats.put("total_hours", Math.round(totalMs/3600000.0*10)/10.0);

            // skipped stats (Extended History)
            try {
                Cursor c6 = db.rawQuery("SELECT COUNT(*) FROM plays WHERE skipped=1", null);
                long skipped = 0;
                if (c6.moveToFirst()) skipped = c6.getLong(0);
                c6.close();
                stats.put("skipped", skipped);
                long total = stats.optLong("total_plays",0);
                stats.put("skip_rate", total>0 ? Math.round(skipped*1000.0/total)/10.0 : 0);
                // top skipped tracks
                Cursor c7 = db.rawQuery("SELECT track_id, track_name, artist_names, COUNT(*) as c FROM plays WHERE skipped=1 GROUP BY track_id ORDER BY c DESC LIMIT 10", null);
                JSONArray topSkipped = new JSONArray();
                while (c7.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("track_id", c7.getString(0));
                    o.put("track_name", c7.getString(1));
                    try { o.put("artist_names", new JSONArray(c7.getString(2))); } catch (Exception e) { o.put("artist_names", new JSONArray()); }
                    o.put("skipped", c7.getLong(3));
                    topSkipped.put(o);
                }
                c7.close();
                stats.put("top_skipped", topSkipped);
                // avg ms_played where available
                Cursor c8 = db.rawQuery("SELECT AVG(ms_played) FROM plays WHERE ms_played>0", null);
                if (c8.moveToFirst()) stats.put("avg_ms_played", c8.getLong(0));
                c8.close();
            } catch (Exception ignored) {}

            // Dia do ano corrente que mais escutou (Brasília)
            try {
                Cursor cPeak = db.rawQuery("SELECT date(played_at_ms/1000,'unixepoch','localtime') as d, COUNT(*) as cnt, SUM(COALESCE(ms_played,duration_ms)) as dur FROM plays WHERE strftime('%Y', played_at_ms/1000,'unixepoch','localtime') = strftime('%Y','now','localtime') GROUP BY d ORDER BY cnt DESC LIMIT 1", null);
                if(cPeak.moveToFirst()){
                    JSONObject peak = new JSONObject();
                    peak.put("date", cPeak.getString(0));
                    peak.put("plays", cPeak.getLong(1));
                    peak.put("duration_ms", cPeak.getLong(2));
                    // dia da semana
                    try {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                        sdf.setTimeZone(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
                        java.util.Date dd = sdf.parse(cPeak.getString(0));
                        java.text.SimpleDateFormat sdf2 = new java.text.SimpleDateFormat("EEEE", new java.util.Locale("pt","BR"));
                        sdf2.setTimeZone(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
                        peak.put("weekday", sdf2.format(dd));
                    } catch(Exception ignored){}
                    stats.put("peak_day_year", peak);
                }
                cPeak.close();
            } catch(Exception ignored){}

            // Top-3 músicas mais ouvidas em um mesmo dia (mais plays em 24h) historicamente - Brasília
            try {
                Cursor cTopDay = db.rawQuery("SELECT track_id, MAX(track_name), MAX(artist_names), MAX(album_image), date(played_at_ms/1000,'unixepoch','localtime') as d, COUNT(*) as cnt FROM plays GROUP BY track_id, date(played_at_ms/1000,'unixepoch','localtime') ORDER BY cnt DESC LIMIT 3", null);
                JSONArray top3Day = new JSONArray();
                while(cTopDay.moveToNext()){
                    JSONObject o = new JSONObject();
                    o.put("track_id", cTopDay.getString(0));
                    o.put("track_name", cTopDay.getString(1));
                    try { o.put("artist_names", new JSONArray(cTopDay.getString(2))); } catch(Exception e){ o.put("artist_names", new JSONArray()); }
                    o.put("album_image", cTopDay.getString(3));
                    o.put("date", cTopDay.getString(4));
                    o.put("plays", cTopDay.getLong(5));
                    top3Day.put(o);
                }
                cTopDay.close();
                stats.put("top3_single_day", top3Day);
            } catch(Exception e){
                try { stats.put("top3_single_day_error", e.getMessage()); } catch(Exception ignored){}
            }

            String last = getConfig("last_sync_ms");
            stats.put("last_sync_ms", last != null ? Long.parseLong(last) : 0);
            String lastHuman = getConfig("last_sync_human");
            stats.put("last_sync_human", lastHuman != null ? lastHuman : "");

        } catch (Exception e) {
            try { stats.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return stats;
    }

    public synchronized void clearAllPlays() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM plays");
        putConfig("last_sync_ms", "0");
        putConfig("last_sync_human", "");
    }

    public synchronized JSONArray getDatesWithPlays() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT DISTINCT date(played_at_ms/1000, 'unixepoch','localtime') as d FROM plays ORDER BY d DESC LIMIT 365", null);
        JSONArray arr = new JSONArray();
        try {
            while (c.moveToNext()) arr.put(c.getString(0));
        } finally { c.close(); }
        return arr;
    }

    public synchronized long getLastPlayedAtMs() {
        String v = getConfig("last_sync_ms");
        if (v != null) try { return Long.parseLong(v); } catch (Exception e) {}
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT MAX(played_at_ms) FROM plays", null);
        try {
            if (c.moveToFirst()) return c.getLong(0);
        } finally { c.close(); }
        return 0;
    }

    public synchronized JSONObject getWindowStats(long sinceMs) {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject out = new JSONObject();
        try {
            Cursor c1 = db.rawQuery("SELECT COUNT(*) FROM plays WHERE played_at_ms >= ?", new String[]{String.valueOf(sinceMs)});
            long cnt = 0;
            if (c1.moveToFirst()) cnt = c1.getLong(0);
            c1.close();
            out.put("count", cnt);
            // top artist in window
            Cursor c2 = db.rawQuery("SELECT artist_names, COUNT(*) as c FROM plays WHERE played_at_ms >= ? GROUP BY artist_names ORDER BY c DESC LIMIT 1", new String[]{String.valueOf(sinceMs)});
            String topArtist = "";
            long topCount = 0;
            if (c2.moveToFirst()) {
                String raw = c2.getString(0);
                topCount = c2.getLong(1);
                try {
                    JSONArray arr = new JSONArray(raw);
                    if (arr.length() > 0) topArtist = arr.getString(0);
                    else topArtist = raw;
                } catch (Exception e) {
                    topArtist = raw;
                }
                // if multiple names in array, join
                try {
                    JSONArray arr2 = new JSONArray(raw);
                    if (arr2.length() > 1) {
                        StringBuilder sb = new StringBuilder();
                        for (int i=0;i<arr2.length();i++) {
                            if (i>0) sb.append(", ");
                            sb.append(arr2.getString(i));
                        }
                        topArtist = sb.toString();
                    }
                } catch (Exception ignored) {}
            }
            c2.close();
            out.put("top_artist", topArtist);
            out.put("top_artist_count", topCount);
            // also distinct tracks in window
            Cursor c3 = db.rawQuery("SELECT COUNT(DISTINCT track_id) FROM plays WHERE played_at_ms >= ?", new String[]{String.valueOf(sinceMs)});
            if (c3.moveToFirst()) out.put("unique_tracks", c3.getLong(0));
            c3.close();
        } catch (Exception e) {
            try { out.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return out;
    }
}
