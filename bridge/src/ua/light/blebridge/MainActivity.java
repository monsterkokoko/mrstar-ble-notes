package ua.light.blebridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One screen: asks for what BLE scanning needs and shows whether the bridge is ready. */
public class MainActivity extends Activity {
    private TextView info;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        info = new TextView(this);
        info.setTextSize(16);
        root.addView(info);

        root.addView(button("Дозволити геолокацію", v -> askLocation()));
        root.addView(button("Увімкнути геолокацію", v ->
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))));
        root.addView(button("Без обмежень батареї", v -> askBattery()));
        root.addView(button("Дозволити камеру", v -> askCamera()));
        root.addView(button("Оновити", v -> refresh()));
        setContentView(root);

        BridgeService.start(this);
        askLocation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refresh();
    }

    private Button button(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        return b;
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askLocation() {
        if (!hasLocation()) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }
    }

    private boolean hasCamera() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void askCamera() {
        if (!hasCamera()) requestPermissions(new String[]{Manifest.permission.CAMERA}, 2);
    }

    private boolean ignoresBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void askBattery() {
        if (!ignoresBattery()) {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void refresh() {
        boolean bt = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter().isEnabled();
        int mode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, 0);
        info.setText("Міст працює на 127.0.0.1:" + BridgeService.PORT + "\n\n"
                + mark(hasLocation()) + " дозвіл на геолокацію\n"
                + mark(mode != 0) + " геолокація увімкнена\n"
                + mark(bt) + " Bluetooth увімкнений\n"
                + mark(ignoresBattery()) + " без обмежень батареї\n"
                + mark(hasCamera()) + " камера (знімки гірлянди для кольорів)\n\n"
                + "Також у MIUI: Безпека → Автозапуск → BLE Bridge.\n");
    }

    private static String mark(boolean ok) {
        return ok ? "✅" : "❌";
    }
}
