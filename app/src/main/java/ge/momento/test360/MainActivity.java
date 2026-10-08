
package ge.momento.test360;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.List;

public class MainActivity extends Activity {

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private TextView status, log;
    private Button scanButton;
    private final Handler handler =
        new Handler(Looper.getMainLooper());
    private boolean scanning = false;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(36, 60, 36, 24);
        root.setBackgroundColor(Color.rgb(14, 19, 32));

        TextView title = new TextView(this);
        title.setText("MOMENTO 360");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("Bluetooth: მზადაა");
        status.setTextColor(Color.WHITE);
        status.setPadding(0, 30, 0, 25);
        root.addView(status);

        scanButton = new Button(this);
        scanButton.setText("SCAN 360Tok");
        root.addView(scanButton);

        log = new TextView(this);
        log.setTextColor(Color.LTGRAY);
        log.setTextSize(13);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll);

        setContentView(root);

        BluetoothManager manager =
            (BluetoothManager) getSystemService(
                BLUETOOTH_SERVICE);

        adapter = manager.getAdapter();

        scanButton.setOnClickListener(v -> scan());
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                }, 100);
                return false;
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
                }, 100);
                return false;
            }
        }
        return true;
    }

    private void scan() {
        if (!hasPermission()) return;

        if (adapter == null || !adapter.isEnabled()) {
            status.setText("ჩართე Bluetooth");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status.setText("BLE Scanner მიუწვდომელია");
            return;
        }

        if (scanning) return;

        scanning = true;
        status.setText("მოწყობილობის ძებნა...");
        append("Scanning BLE devices...");

        scanner.startScan(callback);

        handler.postDelayed(() -> {
            if (scanning) {
                scanning = false;
                scanner.stopScan(callback);
                append("Scan completed");
            }
        }, 15000);
    }

    private final ScanCallback callback =
        new ScanCallback() {

        @Override
        public void onScanResult(
            int type, ScanResult result) {

            BluetoothDevice device =
                result.getDevice();

            String name = null;
            if (result.getScanRecord() != null) {
                name = result.getScanRecord()
                    .getDeviceName();
            }
            if (name == null) {
                name = device.getName();
            }

            if (name != null &&
                name.toLowerCase().contains("360tok")) {

                append("FOUND: " + name);
                status.setText("ნაპოვნია: " + name);

                if (scanning) {
                    scanning = false;
                    scanner.stopScan(this);
                }

                connect(device);
            }
        }

        @Override
        public void onScanFailed(int error) {
            scanning = false;
            append("Scan error: " + error);
        }
    };

    private void connect(BluetoothDevice device) {
        append("Connecting...");

        gatt = device.connectGatt(
            this, false,
            new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(
                BluetoothGatt g,
                int code, int state) {

                if (code == BluetoothGatt.GATT_SUCCESS &&
                    state == BluetoothProfile.STATE_CONNECTED) {

                    runOnUiThread(() ->
                        status.setText("დაკავშირებულია"));

                    g.discoverServices();

                } else {
                    append("Disconnected/status: " + code);
                    runOnUiThread(() ->
                        status.setText("კავშირი ვერ დამყარდა"));
                    g.close();
                }
            }

            @Override
            public void onServicesDiscovered(
                BluetoothGatt g, int code) {

                if (code != BluetoothGatt.GATT_SUCCESS) {
                    append("Service error: " + code);
                    return;
                }

                for (BluetoothGattService service :
                    g.getServices()) {

                    append("SERVICE: " +
                        service.getUuid());

                    for (BluetoothGattCharacteristic c :
                        service.getCharacteristics()) {

                        append("  CHAR: " + c.getUuid()
                            + " PROPS: " +
                            c.getProperties());
                    }
                }
            }
        });
    }

    private void append(String message) {
        runOnUiThread(() ->
            log.append(message + "\n"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (scanner != null && scanning) {
            scanner.stopScan(callback);
        }
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
        }
    }
}
