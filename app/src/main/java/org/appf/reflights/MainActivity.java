package org.appf.reflights;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;

/**
 * APPF Referee Lights.
 * One app, decided by the password only:
 *   NN00          -> this phone is the HOST + TV screen for platform N (cast it to the TV)
 *   NN01/02/03    -> this phone is the Left / Head / Right referee of platform N
 */
public class MainActivity extends Activity {

    private static final String START = "file:///android_asset/start.html";
    private WebView web;
    private RefServer server;
    private String phoneHtml, tvHtml;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastBack = 0;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        phoneHtml = asset("phone.html");
        tvHtml = asset("tv.html");

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (Build.VERSION.SDK_INT >= 21 && req.isForMainFrame()) failed();
            }
            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView v, int code, String desc, String url) {
                if (Build.VERSION.SDK_INT < 23) failed();
            }
        });
        setContentView(web);
        fullScreen();
        web.loadUrl(START);
    }

    private void failed() {
        ui.post(new Runnable() { public void run() { showStart("Platform not found. Join the platform WiFi."); } });
    }

    private void showStart(String error) {
        stopServer();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        String url = START;
        if (error != null) url += "#" + android.net.Uri.encode(error);
        web.loadUrl(url);
    }

    private void fullScreen() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) fullScreen();
    }

    private String asset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            in.close();
            return o.toString("UTF-8");
        } catch (Exception e) {
            return "<h1>Missing " + name + "</h1>";
        }
    }

    private void stopServer() {
        if (server != null) { server.stop(); server = null; }
    }

    /** Called from start.html. Returns "" when OK, otherwise an error message to show. */
    private class Bridge {
        @JavascriptInterface
        public String enter(String pin) {
            pin = pin == null ? "" : pin.trim();
            if (!pin.matches("[1-9]{2}0[0-3]") || pin.charAt(0) != pin.charAt(1)) return "Wrong password";
            final int platform = pin.charAt(0) - '0';
            final char role = pin.charAt(3);
            if (role == '0') return startHost(platform);
            return startReferee(pin);
        }
    }

    private String startHost(final int platform) {
        stopServer();
        try {
            RefServer srv = new RefServer(platform, new RefServer.Pages() {
                public String phone() { return phoneHtml; }
                public String tv() { return tvHtml; }
            });
            srv.start();
            server = srv;
        } catch (Exception e) {
            return "Could not start. Close other apps using port 8080.";
        }
        ui.post(new Runnable() {
            public void run() {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                web.loadUrl("http://127.0.0.1:" + RefServer.PORT + "/tv");
                fullScreen();
            }
        });
        return "";
    }

    private String startReferee(final String pin) {
        String host = findHost();
        if (host == null) return "Join the platform WiFi first";
        final String url = "http://" + host + ":" + RefServer.PORT + "/?pin=" + pin;
        ui.post(new Runnable() {
            public void run() {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                web.loadUrl(url);
                fullScreen();
            }
        });
        return "";
    }

    /** The host phone is the WiFi gateway (it gives the hotspot). */
    @SuppressWarnings("deprecation")
    private String findHost() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null || !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (RouteInfo r : lp.getRoutes()) {
                    InetAddress g = r.getGateway();
                    if (r.isDefaultRoute() && g != null && g.getAddress().length == 4 && !g.isAnyLocalAddress()) {
                        if (Build.VERSION.SDK_INT >= 23) cm.bindProcessToNetwork(n);
                        else ConnectivityManager.setProcessDefaultNetwork(n);
                        return g.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm.getDhcpInfo();
            if (d != null && d.gateway != 0) {
                int g = d.gateway;
                return (g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
            }
        } catch (Exception ignored) { }
        return null;
    }

    @Override
    public void onBackPressed() {
        String u = web.getUrl();
        if (u == null || u.startsWith(START)) { super.onBackPressed(); return; }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) { showStart(null); return; }
        lastBack = now;
        Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        stopServer();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
