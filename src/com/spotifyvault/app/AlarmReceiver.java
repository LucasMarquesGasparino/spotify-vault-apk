package com.spotifyvault.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "VaultAlarm";
    public static final int REQ_CODE = 1001;
    public static final long INTERVAL_MS = 8L * 60L * 60L * 1000L; // legado 8h, agora fixo 14h e 22h Brasília

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.i(TAG, "alarm fired, starting sync");
        Intent svc = new Intent(ctx, SyncService.class);
        svc.putExtra("from_alarm", true);
        try {
            // usa startService normal (não foreground) para evitar MissingForegroundServiceTypeException no target 35
            ctx.startService(svc);
        } catch (Exception e) {
            Log.e(TAG, "start service failed", e);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svc);
                } else {
                    ctx.startService(svc);
                }
            } catch(Exception e2){ Log.e(TAG, "fallback also failed", e2); }
        }
        // Reschedule next alarm
        scheduleExactAlarm(ctx);
    }

    public static long getNextAlarmMillis(){
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
        long now = cal.getTimeInMillis();
        int[][] times = {{9,0},{12,0},{15,0},{18,0},{21,30}};
        for(int[] t : times){
            java.util.Calendar cand = (java.util.Calendar) cal.clone();
            cand.set(java.util.Calendar.HOUR_OF_DAY, t[0]);
            cand.set(java.util.Calendar.MINUTE, t[1]);
            cand.set(java.util.Calendar.SECOND, 0);
            cand.set(java.util.Calendar.MILLISECOND, 0);
            long ms = cand.getTimeInMillis();
            if (now < ms) return ms;
        }
        // amanhã 09:00
        java.util.Calendar cand = (java.util.Calendar) cal.clone();
        cand.add(java.util.Calendar.DATE, 1);
        cand.set(java.util.Calendar.HOUR_OF_DAY, 9);
        cand.set(java.util.Calendar.MINUTE, 0);
        cand.set(java.util.Calendar.SECOND, 0);
        cand.set(java.util.Calendar.MILLISECOND, 0);
        return cand.getTimeInMillis();
    }

    public static void scheduleExactAlarm(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(ctx, AlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long next = getNextAlarmMillis();
            // Usa setAlarmClock para garantir disparo mesmo com app fechado e em Doze (mais confiável que setExact)
            try {
                Intent show = new Intent(ctx, MainActivity.class);
                PendingIntent showPi = PendingIntent.getActivity(ctx, 0, show, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(next, showPi);
                am.setAlarmClock(info, pi);
                Log.i(TAG, "alarmClock 9,12,15,18,21:30 Brasília scheduled for " + new java.util.Date(next) + " tz Sao_Paulo");
            } catch (Exception e) {
                // fallback exact
                boolean canExact = true;
                if (Build.VERSION.SDK_INT >= 31) {
                    try { canExact = am.canScheduleExactAlarms(); } catch(Exception ignored){}
                }
                if (canExact) {
                    if (Build.VERSION.SDK_INT >= 23) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
                    } else {
                        am.setExact(AlarmManager.RTC_WAKEUP, next, pi);
                    }
                    Log.i(TAG, "fallback exact alarm for " + new java.util.Date(next));
                } else {
                    if (Build.VERSION.SDK_INT >= 23) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
                    } else {
                        am.set(AlarmManager.RTC_WAKEUP, next, pi);
                    }
                    Log.i(TAG, "fallback inexact alarm for " + new java.util.Date(next));
                }
            }
            // also save next alarm time
            DatabaseHelper db = new DatabaseHelper(ctx);
            db.putConfig("next_alarm_ms", String.valueOf(next));
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
            db.putConfig("next_alarm_human", sdf.format(new java.util.Date(next)) + " Brasília");
            db.close();
        } catch (Exception e) {
            Log.e(TAG, "schedule failed", e);
        }
    }

    public static void cancelAlarm(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(ctx, AlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (am != null) am.cancel(pi);
            Log.i(TAG, "alarm cancelled");
        } catch (Exception e) {}
    }

    public static void scheduleInexactRepeating(Context ctx) {
        // legacy fallback for devices where exact fails, also keep as backup
        scheduleExactAlarm(ctx);
    }
}
