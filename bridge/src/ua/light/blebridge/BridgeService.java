package ua.light.blebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Foreground service with a tiny JSON-over-HTTP API on 127.0.0.1:8099. Only loopback: Termux on the
 * phone reaches it directly, the VPS through the reverse SSH tunnel.
 *
 * GET /status | /log | /scan?seconds=&name= | /connect?address= | /services | /disconnect[?forget=1]
 *     /write?char=&hex=[&service=&mode=resp|noresp] | /read?char= | /notify?char=&on=1 | /notifications
 *     /config?idle=<seconds, 0 = keep connected>&on_return=<char>:<hex>&return_gap=<seconds> | /bt?on=1|0
 *     /camera | /photo?... (a JPEG) | /burst?... (JPEG frames back to back), see Cam
 */
public class BridgeService extends Service implements Ble.Listener {
    static final int PORT = 8099;
    private static final String CHANNEL = "bridge";

    private Ble ble;
    private Cam cam;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean running;
    private volatile ServerSocket server;
    private final long started = SystemClock.elapsedRealtime();

    static void start(Context c) {
        c.startForegroundService(new Intent(c, BridgeService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "BLE Bridge", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, note("Запуск"));
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blebridge:server");
        wakeLock.acquire();
        ble = new Ble(this, this);
        cam = new Cam(this);
        running = true;
        Thread t = new Thread(this::serve, "http");
        t.setDaemon(true);
        t.start();
        Ble.log("service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        ble.close();
        if (wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onState(String text) {
        getSystemService(NotificationManager.class).notify(1, note(text));
    }

    private Notification note(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("BLE Bridge")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ---- HTTP --------------------------------------------------------------------------------

    private void serve() {
        while (running) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
                server = s;
                onState("Готовий, порт " + PORT);
                while (running) {
                    try (Socket c = s.accept()) {
                        c.setSoTimeout(10_000);
                        handle(c);
                    } catch (Exception e) {
                        if (running) Ble.log("http: " + e);
                    }
                }
            } catch (Exception e) {
                Ble.log("server: " + e);
                SystemClock.sleep(3000);
            }
        }
    }

    private void handle(Socket c) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String request = in.readLine();
        if (request == null) return;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            // headers are not used
        }
        String[] parts = request.split(" ");
        Uri uri = Uri.parse("http://bridge" + (parts.length > 1 ? parts[1] : "/"));

        int code = 200;
        Object result;
        try {
            result = route(uri.getPath() == null ? "/" : uri.getPath(), uri);
        } catch (IllegalArgumentException e) {
            code = 400;
            result = error(e);
        } catch (Exception e) {
            code = 500;
            result = error(e);
        }
        OutputStream out = c.getOutputStream();
        if (result instanceof Cam.Shot) {
            Cam.Shot shot = (Cam.Shot) result;
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: image/jpeg\r\n"
                    + "X-Shot: " + shot.meta + "\r\n"
                    + "Content-Length: " + shot.jpeg.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(shot.jpeg);
            out.flush();
            return;
        }
        JSONObject body = new JSONObject();
        body.put("ok", code == 200);
        body.put("result", result);
        byte[] bytes = (body.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static Object error(Exception e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    private static String need(Uri u, String key) {
        String v = u.getQueryParameter(key);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException("missing ?" + key + "=");
        return v;
    }

    private Object route(String path, Uri u) throws Exception {
        switch (path) {
            case "/":
            case "/status": {
                JSONObject o = ble.status();
                o.put("uptime", (SystemClock.elapsedRealtime() - started) / 1000);
                o.put("version", 5);
                return o;
            }
            case "/log":
                return Ble.logLines();
            case "/bt":
                return ble.setEnabled(!"0".equals(u.getQueryParameter("on")));
            case "/config": {
                String idle = u.getQueryParameter("idle");
                if (idle != null) ble.setIdle(Integer.parseInt(idle));
                String gap = u.getQueryParameter("return_gap");
                ble.setOnReturn(u.getQueryParameter("on_return"), gap == null ? null : Integer.parseInt(gap));
                return ble.status();
            }
            case "/scan": {
                String s = u.getQueryParameter("seconds");
                int seconds = s == null ? 4 : Math.max(1, Math.min(20, Integer.parseInt(s)));
                return ble.scan(seconds, u.getQueryParameter("name"));
            }
            case "/connect":
                return ble.connect(need(u, "address"), !"0".equals(u.getQueryParameter("save")));
            case "/services":
                return ble.services();
            case "/disconnect":
                ble.disconnect();
                if ("1".equals(u.getQueryParameter("forget"))) ble.forget();
                return ble.status();
            case "/write":
                return ble.write(u.getQueryParameter("service"), need(u, "char"),
                        Hex.decode(need(u, "hex")), u.getQueryParameter("mode"));
            case "/read":
                return ble.read(u.getQueryParameter("service"), need(u, "char"));
            case "/notify":
                return ble.subscribe(u.getQueryParameter("service"), need(u, "char"),
                        !"0".equals(u.getQueryParameter("on")));
            case "/notifications":
                return ble.drainNotifications();
            case "/camera":
                return cam.info();
            case "/photo":
                return cam.shoot(u);
            case "/burst":
                return cam.burst(u);
            default:
                throw new IllegalArgumentException("unknown path " + path);
        }
    }
}
