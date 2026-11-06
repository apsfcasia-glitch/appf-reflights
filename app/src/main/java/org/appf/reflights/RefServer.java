package org.appf.reflights;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * APPF Referee Lights host server (pure Java, no Android classes).
 * One instance serves ONE platform. Runs on the host phone, port 8080.
 */
public class RefServer {

    public interface Pages {
        String phone();
        String tv();
    }

    public static final int PORT = 8080;
    public static int SHOW_SECONDS = 10;
    private static final String[] ROLES = {"left", "center", "right"};
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final int platform;
    private final Pages pages;
    private final Object lock = new Object();
    private final Map<String, String> pins = new HashMap<>();   // pin -> role
    private final Map<String, String> tokens = new HashMap<>(); // token -> role
    private final Map<String, String> votes = new HashMap<>();
    private final Map<String, Long> lastSeen = new HashMap<>();
    private boolean revealed = false;
    private int round = 1;
    private long version = 0;
    private Thread resetTimer;
    private volatile boolean running = false;
    private ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final SecureRandom rnd = new SecureRandom();

    public RefServer(int platform, Pages pages) {
        this.platform = platform;
        this.pages = pages;
        String p = "" + platform + platform;
        pins.put(p + "00", "tv");
        for (int i = 0; i < 3; i++) {
            pins.put(p + "0" + (i + 1), ROLES[i]);
            votes.put(ROLES[i], null);
            lastSeen.put(ROLES[i], 0L);
        }
    }

    public int getPlatform() { return platform; }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new java.net.InetSocketAddress(PORT));
        running = true;
        Thread acc = new Thread(new Runnable() { public void run() { acceptLoop(); } }, "accept");
        acc.setDaemon(true);
        acc.start();
        Thread hb = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    synchronized (lock) { bump(); }
                }
            }
        }, "heartbeat");
        hb.setDaemon(true);
        hb.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        synchronized (lock) { lock.notifyAll(); }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                pool.execute(new Runnable() { public void run() { handle(s); } });
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    // ------------------------------------------------------------------ state
    private void bump() { version++; lock.notifyAll(); }

    private void doReset() {
        synchronized (lock) {
            for (String r : ROLES) votes.put(r, null);
            revealed = false;
            round++;
            resetTimer = null;
            bump();
        }
    }

    private String stateJson(String role) {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        b.append("{\"platform\": ").append(platform)
         .append(", \"revealed\": ").append(revealed)
         .append(", \"round\": ").append(round)
         .append(", \"voted\": {");
        for (int i = 0; i < 3; i++) {
            if (i > 0) b.append(", ");
            b.append('"').append(ROLES[i]).append("\": ").append(votes.get(ROLES[i]) != null);
        }
        b.append("}, \"online\": {");
        for (int i = 0; i < 3; i++) {
            if (i > 0) b.append(", ");
            b.append('"').append(ROLES[i]).append("\": ").append(now - lastSeen.get(ROLES[i]) < 20000);
        }
        b.append("}, \"show\": ").append(SHOW_SECONDS);
        if (revealed) {
            int whites = 0;
            b.append(", \"votes\": {");
            for (int i = 0; i < 3; i++) {
                String v = votes.get(ROLES[i]);
                if ("white".equals(v)) whites++;
                if (i > 0) b.append(", ");
                b.append('"').append(ROLES[i]).append("\": \"").append(v).append('"');
            }
            b.append("}, \"result\": \"").append(whites >= 2 ? "good" : "nolift").append('"');
        }
        if (role != null) {
            String m = votes.get(role);
            b.append(", \"mine\": ").append(m == null ? "null" : "\"" + m + "\"")
             .append(", \"role\": \"").append(role).append('"');
        }
        return b.append('}').toString();
    }

    // ------------------------------------------------------------------- http
    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",}]*)\"?").matcher(json);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') o.write(c);
            if (o.size() > 8192) throw new IOException("line too long");
        }
        if (c == -1 && o.size() == 0) return null;
        return new String(o.toByteArray(), UTF8);
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            String reqLine = readLine(in);
            if (reqLine == null) { s.close(); return; }
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) { s.close(); return; }
            String method = parts[0];
            String target = parts[1];
            int len = 0;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    try { len = Integer.parseInt(line.substring(c + 1).trim()); } catch (NumberFormatException ignored) { }
                }
            }
            String body = "";
            if (len > 0 && len < 65536) {
                byte[] buf = new byte[len];
                int r = 0;
                while (r < len) { int n = in.read(buf, r, len - r); if (n < 0) break; r += n; }
                body = new String(buf, 0, r, UTF8);
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            boolean local = s.getInetAddress().isLoopbackAddress();

            if (method.equals("GET")) {
                if (path.equals("/") || path.equals("/ref")) { send(out, 200, "text/html; charset=utf-8", pages.phone()); }
                else if (path.equals("/tv")) { send(out, 200, "text/html; charset=utf-8", pages.tv()); }
                else if (path.equals("/manifest.json")) {
                    send(out, 200, "application/json", "{\"name\": \"APPF Referee\", \"short_name\": \"APPF Ref\", \"start_url\": \"/\", \"display\": \"standalone\", \"background_color\": \"#000000\", \"theme_color\": \"#000000\"}");
                }
                else if (path.equals("/api/autotv")) {
                    if (!local) { send(out, 403, "application/json", "{\"ok\": false}"); }
                    else {
                        String t = newToken();
                        synchronized (lock) { tokens.put(t, "tv"); }
                        send(out, 200, "application/json", "{\"ok\": true, \"token\": \"" + t + "\", \"platform\": " + platform + "}");
                    }
                }
                else if (path.equals("/api/events")) {
                    String t = "";
                    for (String kv : query.split("&")) if (kv.startsWith("t=")) t = kv.substring(2);
                    events(s, out, t);
                    return;
                }
                else send(out, 404, "text/plain", "Not found");
            } else if (method.equals("POST")) {
                post(out, path, body);
            } else {
                send(out, 405, "text/plain", "Method not allowed");
            }
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private String newToken() {
        byte[] b = new byte[12];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void post(OutputStream out, String path, String body) throws IOException {
        if (path.equals("/api/login")) {
            String role = pins.get(field(body, "pin"));
            if (role == null) { send(out, 401, "application/json", "{\"ok\": false}"); return; }
            String t = newToken();
            synchronized (lock) {
                tokens.put(t, role);
                if (!role.equals("tv")) lastSeen.put(role, System.currentTimeMillis());
                bump();
            }
            String name = role.equals("tv") ? "TV" : role.equals("left") ? "LEFT REFEREE" : role.equals("center") ? "HEAD REFEREE" : "RIGHT REFEREE";
            send(out, 200, "application/json", "{\"ok\": true, \"token\": \"" + t + "\", \"role\": \"" + role
                    + "\", \"platform\": " + platform + ", \"name\": \"" + name + "\"}");
            return;
        }
        String role;
        synchronized (lock) { role = tokens.get(field(body, "token")); }
        if (role == null || role.equals("tv")) { send(out, 401, "application/json", "{\"ok\": false, \"relogin\": true}"); return; }

        if (path.equals("/api/vote")) {
            String v = field(body, "vote");
            if (!v.equals("white") && !v.equals("red")) { send(out, 400, "application/json", "{\"ok\": false}"); return; }
            synchronized (lock) {
                if (revealed) { send(out, 200, "application/json", "{\"ok\": false, \"locked\": true}"); return; }
                votes.put(role, v);
                lastSeen.put(role, System.currentTimeMillis());
                boolean all = true;
                for (String r : ROLES) if (votes.get(r) == null) all = false;
                if (all) {
                    revealed = true;
                    final int myRound = round;
                    resetTimer = new Thread(new Runnable() {
                        public void run() {
                            try { Thread.sleep(SHOW_SECONDS * 1000L); } catch (InterruptedException e) { return; }
                            synchronized (lock) { if (round != myRound || !revealed) return; }
                            doReset();
                        }
                    });
                    resetTimer.setDaemon(true);
                    resetTimer.start();
                }
                bump();
            }
            send(out, 200, "application/json", "{\"ok\": true}");
            return;
        }
        if (path.equals("/api/reset") && role.equals("center")) {
            synchronized (lock) { if (resetTimer != null) resetTimer.interrupt(); }
            doReset();
            send(out, 200, "application/json", "{\"ok\": true}");
            return;
        }
        send(out, 404, "application/json", "{\"ok\": false}");
    }

    private void events(Socket s, OutputStream out, String token) throws IOException {
        String role;
        synchronized (lock) { role = tokens.get(token); }
        if (role == null) { send(out, 401, "application/json", "{\"relogin\": true}"); s.close(); return; }
        if (role.equals("tv")) role = null;
        s.setSoTimeout(0);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\n"
                + "Connection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n").getBytes(UTF8));
        out.flush();
        long seen = -1;
        try {
            while (running) {
                String msg = null;
                long ver;
                synchronized (lock) {
                    if (version == seen) {
                        try { lock.wait(15000); } catch (InterruptedException e) { break; }
                    }
                    if (role != null) lastSeen.put(role, System.currentTimeMillis());
                    ver = version;
                    if (ver != seen) msg = stateJson(role);
                }
                if (msg != null) { out.write(("data: " + msg + "\n\n").getBytes(UTF8)); seen = ver; }
                else out.write(": ping\n\n".getBytes(UTF8));
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] data = body.getBytes(UTF8);
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden"
                : code == 404 ? "Not Found" : code == 400 ? "Bad Request" : "Error";
        out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                + data.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(UTF8));
        out.write(data);
        out.flush();
    }

    /** Local address other devices on the hotspot use to reach this phone (for display only). */
    public static String lanAddress() {
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> e = java.net.NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                java.net.NetworkInterface ni = e.nextElement();
                for (java.util.Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress ad = a.nextElement();
                    if (!ad.isLoopbackAddress() && ad instanceof java.net.Inet4Address && ad.isSiteLocalAddress())
                        return ad.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "";
    }
}
