package com.spotifyvault.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public final class MainActivity extends Activity {

    private WebView web;
    private DatabaseHelper db;
    private VaultBridge bridge;
    private String pendingCode;
    private String pendingError;
    private String pendingState;
    private static final int REQ_NOTIF = 9001;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1003;

    // Redirect URIs que interceptamos
    private static final String[] REDIRECT_URIS = new String[]{
        "spotifyvault://callback",
        "http://127.0.0.1:8888/callback",
        "http://localhost:8888/callback",
        "http://127.0.0.1:3000/callback",
        "http://localhost:3000/callback"
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // crash handler que grava em Documents e mostra toast
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    String msg = e.toString() + "\n" + android.util.Log.getStackTraceString(e);
                    android.util.Log.e("VaultCrash", msg);
                    try {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() {
                                try { Toast.makeText(getApplicationContext(), "Vault crash: "+e.getMessage(), Toast.LENGTH_LONG).show(); } catch(Exception ignored){}
                            }
                        });
                    } catch(Exception ignored){}
                    try {
                        java.io.File c1 = new java.io.File(getExternalFilesDir(null), "vault_crash.log");
                        java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(c1, true));
                        pw.println(new java.util.Date() + " " + msg);
                        pw.close();
                    } catch(Exception ignored){}
                    try {
                        java.io.File c2 = new java.io.File("/storage/emulated/0/Documents/vault_crash.log");
                        java.io.PrintWriter pw2 = new java.io.PrintWriter(new java.io.FileWriter(c2, true));
                        pw2.println(new java.util.Date() + " " + msg);
                        pw2.close();
                    } catch(Exception ignored){}
                    try { Thread.sleep(2500); } catch(Exception ignored){}
                } catch(Exception ignored){}
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(2);
            }
        });
        getWindow().setStatusBarColor(Color.parseColor("#0a0a0a"));
        getWindow().setNavigationBarColor(Color.parseColor("#0a0a0a"));
        // Sem KEEP_SCREEN_ON (economia + fluidez; sync roda em Service/Alarm mesmo com tela off)

        db = new DatabaseHelper(this);
        bridge = new VaultBridge(this, db);
        createNotificationChannels();
        requestNotifPermission();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0a0a0a"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0a0a0a"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        try { web.setLayerType(View.LAYER_TYPE_HARDWARE, null); } catch (Exception ignored) {}

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // Evita aviso de navegador privado / unsupported browser do Spotify
        try {
            String chromeUA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
            s.setUserAgentString(chromeUA);
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(web, true);
        } catch (Exception ignored) {}

        web.addJavascriptInterface(bridge, "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return handleUrl(url);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }
            private boolean handleUrl(String url) {
                if (url == null) return false;
                // intercepta OAuth callback
                for (String red : REDIRECT_URIS) {
                    if (url.startsWith(red)) {
                        Uri uri = Uri.parse(url);
                        String code = uri.getQueryParameter("code");
                        String error = uri.getQueryParameter("error");
                        String state = uri.getQueryParameter("state");
                        if (code != null) {
                            pendingCode = code;
                            pendingError = null;
                            pendingState = state;
                            Toast.makeText(MainActivity.this, "Código Spotify recebido, voltando ao app...", Toast.LENGTH_SHORT).show();
                            web.post(new Runnable() {
                                @Override public void run() {
                                    web.loadUrl("file:///android_asset/spotify-vault/index.html");
                                }
                            });
                        } else {
                            pendingCode = null;
                            pendingError = error != null ? error : "unknown";
                            pendingState = state;
                            Toast.makeText(MainActivity.this, "Erro Spotify: " + pendingError, Toast.LENGTH_LONG).show();
                            web.post(new Runnable() {
                                @Override public void run() {
                                    web.loadUrl("file:///android_asset/spotify-vault/index.html");
                                }
                            });
                        }
                        return true; // interceptado
                    }
                }
                // Deixa WebView carregar URLs http(s) normalmente (Spotify auth)
                if (url.startsWith("https://accounts.spotify.com") || url.startsWith("https://www.spotify.com") || url.startsWith("https://open.spotify.com")) {
                    return false;
                }
                // para file:// deixa
                if (url.startsWith("file://")) return false;
                // outros externos, abre no sistema?
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("file://") && (pendingCode != null || pendingError != null)) {
                    final String code = pendingCode;
                    final String err = pendingError;
                    final String st = pendingState;
                    pendingCode = null;
                    pendingError = null;
                    pendingState = null;
                    view.postDelayed(new Runnable() {
                        @Override public void run() {
                            String js;
                            if (code != null) {
                                String esc = code.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n");
                                js = "window.onSpotifyCallback && window.onSpotifyCallback('" + esc + "', null, '" + (st!=null?st:"") + "');";
                            } else {
                                String escErr = err != null ? err.replace("\\","\\\\").replace("'","\\'") : "unknown";
                                js = "window.onSpotifyCallback && window.onSpotifyCallback(null, '" + escErr + "', '" + (st!=null?st:"") + "');";
                            }
                            view.evaluateJavascript(js, null);
                        }
                    }, 700);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    // Força aceitar zip e json
                    intent.setType("*/*");
                    String[] mimeTypes = {"application/zip", "application/json", "text/json", "application/octet-stream"};
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(intent, "Selecione o ZIP ou JSONs"), FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Erro ao abrir seletor: "+e.getMessage(), Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        // Carrega app local
        web.loadUrl("file:///android_asset/spotify-vault/index.html");

        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);

        // agenda sync 8h se habilitado
        String enabled = db.getConfig("auto_sync_enabled", "true");
        if ("true".equals(enabled)) scheduleAlarm();

        handleIntent(getIntent());
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        String url = data.toString();
        for (String red : REDIRECT_URIS) {
            if (url.startsWith(red)) {
                String code = data.getQueryParameter("code");
                String error = data.getQueryParameter("error");
                String state = data.getQueryParameter("state");
                pendingCode = code;
                pendingError = error;
                pendingState = state;
                if (web != null) {
                    web.post(new Runnable() {
                        @Override public void run() {
                            web.loadUrl("file:///android_asset/spotify-vault/index.html");
                        }
                    });
                }
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    public void loadAuthUrl(String url) {
        if (web != null) web.loadUrl(url);
    }

    public void scheduleAlarm() {
        try {
            AlarmReceiver.scheduleExactAlarm(this);
            db.putConfig("auto_sync_enabled","true");
            String next = db.getConfig("next_alarm_human", "");
            Toast.makeText(this, "Sync 9h,12h,15h,18h,21h30 Brasília agendado" + (next.isEmpty()?"":" → próximo "+next), Toast.LENGTH_LONG).show();
            // ask to disable battery optimization for reliable background
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                        Toast.makeText(this, "Permita 'Sem otimização' para notificar com app fechado", Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) {}
                }
            }
            // check exact alarm permission for Android 12+
            if (Build.VERSION.SDK_INT >= 31) {
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (am != null && !am.canScheduleExactAlarms()) {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                        Toast.makeText(this, "Ative 'Alarmes exatos' para garantir 8h com app fechado", Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao agendar sync: "+e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void cancelAlarm() {
        try {
            AlarmReceiver.cancelAlarm(this);
            db.putConfig("auto_sync_enabled","false");
            Toast.makeText(this, "Sync automático cancelado", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {}
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel("vault_sync") == null) {
                NotificationChannel ch = new NotificationChannel("vault_sync", "Spotify Vault Sync", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Sincronização a cada 8h");
                nm.createNotificationChannel(ch);
            }
            if (nm.getNotificationChannel("vault_sync_summary") == null) {
                NotificationChannel ch2 = new NotificationChannel("vault_sync_summary", "Spotify Vault Resumo", NotificationManager.IMPORTANCE_HIGH);
                ch2.setDescription("Resumo a cada 8h com músicas e artista top");
                nm.createNotificationChannel(ch2);
            }
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "Notificações ativadas ✓" : "Notificações negadas - você não receberá o resumo 8h", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        // checa se já passou 8h desde last sync e dispara sync service
        try {
            String lastStr = db.getConfig("last_sync_ms","0");
            long last = Long.parseLong(lastStr);
            long now = System.currentTimeMillis();
            if (last==0 || now - last > 8*60*60*1000L) {
                // só inicia se tem token
                String tok = db.getConfig("access_token");
                if (tok!=null && !tok.isEmpty()) {
                    Intent svc = new Intent(this, SyncService.class);
                    try { startService(svc); } catch(Exception e){ try{ startForegroundService(svc); }catch(Exception ignored){} }
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        if (db != null) db.close();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && web != null) {
            web.resumeTimers();
        }
    }
}
