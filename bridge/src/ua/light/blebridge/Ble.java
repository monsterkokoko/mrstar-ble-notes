package ua.light.blebridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One BLE peripheral at a time. Every GATT operation is serialised under {@link #lock} and waits for
 * its callback, so callers get a plain blocking API.
 *
 * The last connected address is remembered. With idle = 0 the bridge keeps that device connected and
 * reconnects after it drops (the strip loses power with the mains); with idle > 0 it disconnects
 * after that many seconds without use, so the vendor app can connect in between.
 */
class Ble extends BluetoothGattCallback {
    private static final String TAG = "BleBridge";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    private final Context ctx;
    private final BluetoothAdapter adapter;
    private final SharedPreferences prefs;
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<JSONObject> notifications = new ArrayDeque<>();
    private final Listener listener;

    private volatile BluetoothGatt gatt;
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile int lastStatus;
    private volatile int opStatus;
    private volatile byte[] opValue;
    private volatile long lastUse = SystemClock.elapsedRealtime();
    // For the server's power-return rule: how long the device was gone before the current connection.
    private volatile long connectedAt = -1;
    private volatile long lostAt = -1;
    private volatile long gapBefore = -1;
    private volatile int connections;
    private volatile int returnedAt = -1;  // the connection number on which on_return was sent
    private volatile CountDownLatch connLatch = new CountDownLatch(0);
    private volatile CountDownLatch discLatch = new CountDownLatch(0);
    private volatile CountDownLatch opLatch = new CountDownLatch(0);

    interface Listener {
        void onState(String text);
    }

    Ble(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.adapter = ((BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        this.prefs = ctx.getSharedPreferences("ble", Context.MODE_PRIVATE);
        Thread keeper = new Thread(this::keep, "ble-keeper");
        keeper.setDaemon(true);
        keeper.start();
    }

    // ---- log ---------------------------------------------------------------------------------

    static void log(String msg) {
        Log.i(TAG, msg);
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + msg;
        synchronized (LOG) {
            LOG.addLast(line);
            while (LOG.size() > 300) LOG.removeFirst();
        }
    }

    static JSONArray logLines() {
        synchronized (LOG) {
            return new JSONArray(new ArrayList<>(LOG));
        }
    }

    // ---- state -------------------------------------------------------------------------------

    String savedAddress() {
        return prefs.getString("address", null);
    }

    int idleSeconds() {
        return prefs.getInt("idle", 0);
    }

    /**
     * "fff3:bc01010055": written the moment the device reconnects after being gone for return_gap seconds or more,
     * i.e. when the mains come back. Done here, not on the server, because the router (and so the tunnel) needs
     * minutes to come back after an outage, and the strip must not shine all that time.
     */
    void setOnReturn(String spec, Integer gapSeconds) {
        SharedPreferences.Editor e = prefs.edit();
        if (spec != null) {
            if (spec.isEmpty()) {
                e.remove("on_return");
            } else {
                String[] p = spec.split(":", 2);
                if (p.length != 2) throw new IllegalArgumentException("on_return must be <char>:<hex>");
                Hex.uuid(p[0]);
                Hex.decode(p[1]);
                e.putString("on_return", spec);
            }
        }
        if (gapSeconds != null) e.putInt("return_gap", Math.max(1, gapSeconds));
        e.apply();
    }

    private void onReturn() {
        String spec = prefs.getString("on_return", null);
        int gap = prefs.getInt("return_gap", 60);
        if (spec == null || gapBefore < gap) return;
        String[] p = spec.split(":", 2);
        try {
            JSONObject r = write(null, p[0], Hex.decode(p[1]), null);
            if (r.optBoolean("ok")) returnedAt = connections;
            log("back after " + gapBefore + "s: sent on_return " + p[1] + " -> " + r.optBoolean("ok"));
        } catch (Exception e) {
            log("on_return failed: " + e.getMessage());
        }
    }

    void setIdle(int seconds) {
        prefs.edit().putInt("idle", Math.max(0, seconds)).apply();
    }

    void forget() {
        prefs.edit().remove("address").apply();
    }

    boolean isEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    boolean setEnabled(boolean on) {
        return on ? adapter.enable() : adapter.disable();
    }

    JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("bluetooth", isEnabled());
        o.put("connected", connected);
        BluetoothGatt g = gatt;
        o.put("address", g != null ? g.getDevice().getAddress() : JSONObject.NULL);
        o.put("saved", savedAddress() != null ? savedAddress() : JSONObject.NULL);
        o.put("idle", idleSeconds());
        o.put("last_status", lastStatus);
        long now = SystemClock.elapsedRealtime();
        o.put("connected_for", connected && connectedAt >= 0 ? (now - connectedAt) / 1000 : -1);
        o.put("gap_before", gapBefore);
        o.put("connections", connections);
        o.put("returned_at", returnedAt);
        o.put("on_return", prefs.getString("on_return", ""));
        o.put("return_gap", prefs.getInt("return_gap", 60));
        synchronized (notifications) {
            o.put("notifications", notifications.size());
        }
        return o;
    }

    void close() {
        closed = true;
        lock.lock();
        try {
            closeGatt();
        } finally {
            lock.unlock();
        }
    }

    // ---- scan --------------------------------------------------------------------------------

    JSONArray scan(int seconds, String nameFilter) throws Exception {
        if (!isEnabled()) throw new IllegalStateException("bluetooth is off");
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) throw new IllegalStateException("no LE scanner");
        final Map<String, JSONObject> found = new ConcurrentHashMap<>();
        final int[] failed = {0};
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                put(found, r);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult r : results) put(found, r);
            }

            @Override
            public void onScanFailed(int code) {
                failed[0] = code;
            }
        };
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, settings, cb);
        try {
            Thread.sleep(seconds * 1000L);
        } finally {
            scanner.stopScan(cb);
        }
        if (failed[0] != 0) throw new IOException("scan failed, code " + failed[0]);
        List<JSONObject> list = new ArrayList<>(found.values());
        list.sort((a, b) -> b.optInt("rssi") - a.optInt("rssi"));
        JSONArray out = new JSONArray();
        String f = nameFilter == null ? null : nameFilter.toLowerCase(Locale.US);
        for (JSONObject o : list) {
            if (f == null || o.optString("name").toLowerCase(Locale.US).contains(f)) out.put(o);
        }
        log("scan " + seconds + "s: " + found.size() + " devices");
        return out;
    }

    private static void put(Map<String, JSONObject> found, ScanResult r) {
        try {
            JSONObject o = new JSONObject();
            BluetoothDevice d = r.getDevice();
            ScanRecord rec = r.getScanRecord();
            String name = d.getName();
            if (name == null && rec != null) name = rec.getDeviceName();
            o.put("address", d.getAddress());
            o.put("name", name != null ? name : "");
            o.put("rssi", r.getRssi());
            if (rec != null) {
                JSONArray uuids = new JSONArray();
                List<ParcelUuid> su = rec.getServiceUuids();
                if (su != null) for (ParcelUuid u : su) uuids.put(u.toString());
                o.put("uuids", uuids);
                JSONObject manu = new JSONObject();
                SparseArray<byte[]> md = rec.getManufacturerSpecificData();
                if (md != null) {
                    for (int i = 0; i < md.size(); i++) {
                        manu.put(String.format(Locale.US, "%04x", md.keyAt(i)), Hex.encode(md.valueAt(i)));
                    }
                }
                o.put("manufacturer", manu);
                o.put("raw", Hex.encode(Hex.trimZeros(rec.getBytes())));
            }
            found.put(d.getAddress(), o);
        } catch (JSONException ignored) {
        }
    }

    // ---- connection --------------------------------------------------------------------------

    JSONArray connect(String address, boolean save) throws Exception {
        lock.lock();
        try {
            if (!isEnabled()) throw new IllegalStateException("bluetooth is off");
            address = address.toUpperCase(Locale.US);
            BluetoothGatt g = gatt;
            if (!connected || g == null || !g.getDevice().getAddress().equals(address)) {
                closeGatt();
                Exception last = null;
                for (int attempt = 1; attempt <= 3 && !connected; attempt++) {
                    try {
                        doConnect(address);
                    } catch (Exception e) {
                        last = e;
                        log("connect " + address + " attempt " + attempt + ": " + e.getMessage());
                        closeGatt();
                        Thread.sleep(800L * attempt);
                    }
                }
                if (!connected) throw last != null ? last : new IOException("connect failed");
                onReturn();
            }
            if (save) prefs.edit().putString("address", address).apply();
            lastUse = SystemClock.elapsedRealtime();
            return services();
        } finally {
            lock.unlock();
        }
    }

    private void doConnect(String address) throws Exception {
        BluetoothDevice device = adapter.getRemoteDevice(address);
        connLatch = new CountDownLatch(1);
        discLatch = new CountDownLatch(1);
        log("connecting " + address);
        BluetoothGatt g = device.connectGatt(ctx, false, this, BluetoothDevice.TRANSPORT_LE);
        if (g == null) throw new IOException("connectGatt returned null");
        gatt = g;
        if (!connLatch.await(12, TimeUnit.SECONDS)) throw new IOException("connect timeout");
        if (!connected) throw new IOException("connect failed, status " + lastStatus);
        Thread.sleep(200);
        discLatch = new CountDownLatch(1);
        if (!g.discoverServices()) throw new IOException("discoverServices refused");
        if (!discLatch.await(12, TimeUnit.SECONDS)) throw new IOException("service discovery timeout");
        if (!connected) throw new IOException("dropped during discovery");
        long now = SystemClock.elapsedRealtime();
        gapBefore = lostAt < 0 ? -1 : (now - lostAt) / 1000;
        connectedAt = now;
        connections++;
        listener.onState("Підключено: " + address);
        log("connected " + address + ", " + g.getServices().size() + " services");
    }

    void disconnect() {
        lock.lock();
        try {
            closeGatt();
        } finally {
            lock.unlock();
        }
    }

    private void closeGatt() {
        BluetoothGatt g = gatt;
        gatt = null;
        if (connected) lostAt = SystemClock.elapsedRealtime();
        connected = false;
        if (g != null) {
            try {
                g.disconnect();
            } catch (Exception ignored) {
            }
            g.close();
            listener.onState("Не підключено");
        }
    }

    private void ensureConnected() throws Exception {
        if (connected && gatt != null) return;
        String saved = savedAddress();
        if (saved == null) throw new IllegalStateException("not connected and no saved device");
        connect(saved, false);
    }

    /** Keeps the saved device connected (idle = 0) or drops it after idle seconds of disuse. */
    private void keep() {
        while (!closed) {
            SystemClock.sleep(10_000);
            int idle = idleSeconds();
            if (!lock.tryLock()) continue;
            try {
                if (idle > 0) {
                    if (connected && SystemClock.elapsedRealtime() - lastUse > idle * 1000L) {
                        log("idle " + idle + "s, disconnecting");
                        closeGatt();
                    }
                } else if (!connected && savedAddress() != null && isEnabled()) {
                    try {
                        connect(savedAddress(), false);
                    } catch (Exception e) {
                        // The strip is probably unpowered; try again on the next round.
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    // ---- GATT operations ---------------------------------------------------------------------

    JSONArray services() throws JSONException {
        JSONArray out = new JSONArray();
        BluetoothGatt g = gatt;
        if (g == null) return out;
        for (BluetoothGattService s : g.getServices()) {
            JSONObject so = new JSONObject();
            so.put("uuid", s.getUuid().toString());
            JSONArray chars = new JSONArray();
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                JSONObject co = new JSONObject();
                co.put("uuid", c.getUuid().toString());
                co.put("props", props(c.getProperties()));
                chars.put(co);
            }
            so.put("chars", chars);
            out.put(so);
        }
        return out;
    }

    private static JSONArray props(int p) {
        JSONArray a = new JSONArray();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) a.put("read");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) a.put("write");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) a.put("write_noresp");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) a.put("notify");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) a.put("indicate");
        return a;
    }

    private BluetoothGattCharacteristic find(String service, String chr) {
        UUID cu = Hex.uuid(chr);
        UUID su = service == null || service.isEmpty() ? null : Hex.uuid(service);
        BluetoothGatt g = gatt;
        if (g == null) throw new IllegalStateException("not connected");
        for (BluetoothGattService s : g.getServices()) {
            if (su != null && !s.getUuid().equals(su)) continue;
            BluetoothGattCharacteristic c = s.getCharacteristic(cu);
            if (c != null) return c;
        }
        throw new IllegalArgumentException("characteristic " + cu + " not found");
    }

    JSONObject write(String service, String chr, byte[] value, String mode) throws Exception {
        lock.lock();
        try {
            ensureConnected();
            BluetoothGattCharacteristic c = find(service, chr);
            int type;
            if ("resp".equals(mode)) {
                type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            } else if ("noresp".equals(mode)) {
                type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            } else {
                type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            }
            c.setWriteType(type);
            c.setValue(value);
            boolean ok = runOp(() -> gatt.writeCharacteristic(c));
            lastUse = SystemClock.elapsedRealtime();
            JSONObject o = new JSONObject();
            o.put("ok", ok && opStatus == BluetoothGatt.GATT_SUCCESS);
            o.put("status", opStatus);
            o.put("type", type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ? "noresp" : "resp");
            o.put("hex", Hex.encode(value));
            log("write " + c.getUuid().toString().substring(4, 8) + " " + Hex.encode(value) + " -> " + o.get("ok"));
            return o;
        } finally {
            lock.unlock();
        }
    }

    JSONObject read(String service, String chr) throws Exception {
        lock.lock();
        try {
            ensureConnected();
            BluetoothGattCharacteristic c = find(service, chr);
            boolean ok = runOp(() -> gatt.readCharacteristic(c));
            lastUse = SystemClock.elapsedRealtime();
            JSONObject o = new JSONObject();
            o.put("ok", ok && opStatus == BluetoothGatt.GATT_SUCCESS);
            o.put("status", opStatus);
            byte[] v = opValue;
            o.put("hex", v != null ? Hex.encode(v) : "");
            o.put("text", v != null ? Hex.printable(v) : "");
            return o;
        } finally {
            lock.unlock();
        }
    }

    JSONObject subscribe(String service, String chr, boolean on) throws Exception {
        lock.lock();
        try {
            ensureConnected();
            BluetoothGattCharacteristic c = find(service, chr);
            if (!gatt.setCharacteristicNotification(c, on)) throw new IOException("setCharacteristicNotification refused");
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            boolean ok = true;
            if (d != null) {
                boolean indicate = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
                d.setValue(!on ? BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        : indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                ok = runOp(() -> gatt.writeDescriptor(d)) && opStatus == BluetoothGatt.GATT_SUCCESS;
            }
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("cccd", d != null);
            return o;
        } finally {
            lock.unlock();
        }
    }

    JSONArray drainNotifications() {
        synchronized (notifications) {
            JSONArray a = new JSONArray(new ArrayList<>(notifications));
            notifications.clear();
            return a;
        }
    }

    private interface Op {
        boolean start();
    }

    private boolean runOp(Op op) throws InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            opLatch = new CountDownLatch(1);
            opStatus = -1;
            opValue = null;
            if (op.start()) return opLatch.await(4, TimeUnit.SECONDS) && connected;
            Thread.sleep(100); // stack busy with a previous no-response write
        }
        return false;
    }

    // ---- callbacks ---------------------------------------------------------------------------

    @Override
    public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
        lastStatus = status;
        boolean up = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS;
        log("state " + g.getDevice().getAddress() + " status=" + status + " state=" + newState);
        if (g == gatt) {
            if (connected && !up) lostAt = SystemClock.elapsedRealtime();
            connected = up;
            if (!up) {
                gatt = null;
                g.close();
                listener.onState("Зв'язок втрачено");
                discLatch.countDown();
                opLatch.countDown();
            }
        } else if (!up) {
            g.close();
        }
        connLatch.countDown();
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt g, int status) {
        lastStatus = status;
        discLatch.countDown();
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
        opStatus = status;
        opLatch.countDown();
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
        opValue = c.getValue();
        opStatus = status;
        opLatch.countDown();
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
        opStatus = status;
        opLatch.countDown();
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("char", c.getUuid().toString());
            o.put("hex", Hex.encode(c.getValue()));
            synchronized (notifications) {
                notifications.addLast(o);
                while (notifications.size() > 200) notifications.removeFirst();
            }
        } catch (JSONException ignored) {
        }
    }
}
