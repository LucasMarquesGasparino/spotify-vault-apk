package com.spotifyvault.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        Log.i("VaultBoot", "boot receiver action=" + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            "android.intent.action.PACKAGE_REPLACED".equals(action)) {
            DatabaseHelper db = new DatabaseHelper(ctx);
            String enabled = db.getConfig("auto_sync_enabled", "true");
            String hasToken = db.getConfig("access_token");
            db.close();
            boolean should = "true".equals(enabled) && hasToken != null && !hasToken.isEmpty();
            if (should) {
                AlarmReceiver.scheduleExactAlarm(ctx);
                Log.i("VaultBoot", "rescheduled alarm after boot");
            } else {
                Log.i("VaultBoot", "not scheduling, enabled=" + enabled + " hasToken=" + (hasToken!=null && !hasToken.isEmpty()));
            }
        }
    }
}
