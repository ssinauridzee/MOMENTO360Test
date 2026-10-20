package ge.momento.test360;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String DEVICE_NAME = "360Tok-00772";
    private static final UUID SERVICE_ID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID FFE1_ID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID FFE2_ID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_ID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQUEST_BLE_PERMISSIONS = 1001;

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic char1, char2;
    private BluetoothDevice ipad;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder events = new StringBuilder();
    private final Object eventLock = new Object();
    private TextView stateView, logView, countView;
    private String originalName;
    private boolean nameChanged;
    private boolean advertising;
    private boolean subscribed;
    private boolean motorOn;
    private int speed = 6;
    private int receivedWrites;
    private File logFile;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        logFile = new File(base, "360tok-captured-commands.txt");

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 22, 24, 22);
        content.setBackgroundColor(Color.rgb(13, 21, 35));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);

        TextView heading = label("MOMENTO 360 · BLE EMULATOR", 22, Color.CYAN);
        heading.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(heading);
        content.addView(label("iPad → HONOR: 360Tok ბრძანებების ჩაწერა", 15, Color.WHITE));
        content.addView(label("რეალური კონტროლერი გამორთე, რომ iPad ჩვენს იმიტატორს დაუკავშირდეს. ძრავას ეს აპი არ ამოძრავებს.", 14, Color.YELLOW));

        stateView = label("სტატუსი: ჯერ არ დაწყებულა", 17, Color.GREEN);
        content.addView(stateView);
        countView = label("მიღებული WRITE ბრძანებები: 0", 16, Color.WHITE);
        content.addView(countView);

        Button start = button("▶ BLE იმიტატორის გაშვება", content);
        start.setOnClickListener(v -> requestThenStart());
        Button stop = button("■ იმიტატორის გაჩერება", content);
        stop.setOnClickListener(v -> stopPeripheral());

        content.addView(label("სტატუსის იმიტაცია (ძრავას არ მართავს)", 15, Color.YELLOW));
        Button sendOff = button("გავაგზავნოთ სტატუსი: OFF", content);
        sendOff.setOnClickListener(v -> { motorOn = false; sendStatus("MANUAL OFF"); });
        Button sendOn = button("გავაგზავნოთ სტატუსი: ON", content);
        sendOn.setOnClickListener(v -> { motorOn = true; sendStatus("MANUAL ON"); });

        Button copy = button("📋 სრული ლოგის კოპირება", content);
        copy.setOnClickListener(v -> {
            String text;
            synchronized (eventLock) { text = events.toString(); }
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("360Tok BLE log", text));
            Toast.makeText(this, "ლოგი დაკოპირებულია", Toast.LENGTH_LONG).show();
        });

        Button share = button("↗ ლოგის გაზიარება", content);
        share.setOnClickListener(v -> {
            String text;
            synchronized (eventLock) { text = events.toString(); }
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, "MOMENTO 360Tok BLE Commands");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(intent, "BLE ლოგის გაგზავნა"));
        });

        Button clear = button("ლოგის გასუფთავება", content);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setMessage("წავშალოთ დაგროვილი ლოგები?")
            .setNegativeButton("არა", null)
            .setPositiveButton("წაშლა", (d, w) -> {
                synchronized (eventLock) {
                    events.setLength(0);
                    if (logFile.exists()) logFile.delete();
                    receivedWrites = 0;
                }
                logView.setText("");
                countView.setText("მიღებული WRITE ბრძანებები: 0");
                trace("Log cleared");
            }).show());

        content.addView(label("Bluetooth მოვლენები და HEX პაკეტები", 18, Color.CYAN));
        logView = label("", 12, Color.WHITE);
        logView.setTextIsSelectable(true);
        content.addView(logView);

        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) adapter = bluetoothManager.getAdapter();
        trace("APP START; Android " + Build.VERSION.RELEASE + "; target=360Tok-00772");
        trace("Expected: SERVICE=FFE0; FFE1=NOTIFY/WRITE; FFE2=WRITE");
        trace("Confirmed OFF STATUS: " + hex(statusPacket()));
        trace("Log path: " + logFile.getAbsolutePath());
        if (adapter == null) trace("ERROR: Bluetooth adapter missing");
    }

    private TextView label(String txt, int size, int color) {
        TextView view = new TextView(this);
        view.setText(txt);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, 9, 0, 9);
        return view;
    }

    private Button button(String txt, LinearLayout root) {
        Button b = new Button(this);
        b.setText(txt);
        b.setAllCaps(false);
        root.addView(b);
        return b;
    }

    private boolean hasPermissions() {
        return Build.VERSION.SDK_INT < 31 ||
            (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
             checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED);
    }

    private void requestThenStart() {
        if (!hasPermissions()) {
            requestPermissions(new String[]{
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            }, REQUEST_BLE_PERMISSIONS);
            return;
        }
        startPeripheral();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            if (hasPermissions()) startPeripheral();
            else trace("ERROR: Bluetooth permissions denied");
        }
    }

    private void startPeripheral() {
        if (!hasPermissions()) { trace("ERROR: permissions missing"); return; }
        if (advertising || server != null) { trace("Already running, or opening server"); return; }
        if (adapter == null || !adapter.isEnabled()) {
            trace("ERROR: Bluetooth must be enabled in phone Settings");
            return;
        }
        if (!adapter.isMultipleAdvertisementSupported()) {
            trace("ERROR: This phone reports no BLE peripheral advertising support");
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            trace("ERROR: BluetoothLeAdvertiser unavailable");
            return;
        }

        originalName = adapter.getName();
        nameChanged = adapter.setName(DEVICE_NAME);
        trace("ADAPTER NAME: " + DEVICE_NAME + " setName=" + nameChanged + "; original=" + originalName);
        subscribed = false;
        ipad = null;
        motorOn = false;
        speed = 6;

        server = bluetoothManager.openGattServer(this, gattCallback);
        if (server == null) {
            trace("ERROR: openGattServer returned null");
            restoreName();
            return;
        }

        int ffe1Props = BluetoothGattCharacteristic.PROPERTY_NOTIFY |
            BluetoothGattCharacteristic.PROPERTY_READ |
            BluetoothGattCharacteristic.PROPERTY_WRITE |
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        char1 = new BluetoothGattCharacteristic(FFE1_ID, ffe1Props,
            BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD_ID,
            BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        char1.addDescriptor(cccd);

        char2 = new BluetoothGattCharacteristic(FFE2_ID,
            BluetoothGattCharacteristic.PROPERTY_WRITE |
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE |
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE | BluetoothGattCharacteristic.PERMISSION_READ);

        android.bluetooth.BluetoothGattService svc = new android.bluetooth.BluetoothGattService(
            SERVICE_ID, android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY);
        svc.addCharacteristic(char1);
        svc.addCharacteristic(char2);
        boolean adding = server.addService(svc);
        trace("GATT ADD SERVICE FFE0: " + adding);
        if (!adding) stopPeripheral();
        else setState("GATT სერვისი იქმნება...");
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            trace("ADVERTISING STARTED: " + DEVICE_NAME + " / FFE0");
            setState("რეკლამირება ჩართულია — ველოდებით iPad-ს");
        }
        @Override public void onStartFailure(int errorCode) {
            trace("ADVERTISING FAILED: error=" + errorCode + " (1=data large, 2=too many advertisers, 3=already started, 4=internal, 5=feature unsupported)");
            setState("რეკლამირება ვერ ჩაირთო: " + errorCode);
        }
    };

    private void beginAdvertising() {
        try {
            if (advertiser == null) { trace("ERROR: advertiser null"); return; }
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
            // 16-bit FFE0 UUID in primary advertisement; full device name in scan response.
            AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(SERVICE_ID))
                .setIncludeDeviceName(false)
                .build();
            AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
            trace("ADVERTISE REQUEST: service FFE0; scan-response name " + DEVICE_NAME);
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
        } catch (Exception e) {
            trace("ERROR beginAdvertising: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            setState("BLE რეკლამირება ვერ დაიწყო");
        }
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, android.bluetooth.BluetoothGattService service) {
            trace("GATT SERVICE ADDED: " + shortUuid(service.getUuid()) + " result=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) runOnUiThread(() -> beginAdvertising());
            else setState("GATT სერვისის შექმნა ვერ მოხერხდა");
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            trace("CONNECTION: " + (newState == BluetoothProfile.STATE_CONNECTED ? "CONNECTED" : "DISCONNECTED") +
                " address=" + device.getAddress() + " status=" + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                ipad = device;
                subscribed = false;
                setState("iPad/კლიენტი დაუკავშირდა!");
            } else if (ipad != null && ipad.getAddress().equals(device.getAddress())) {
                ipad = null;
                subscribed = false;
                setState("კლიენტი გაითიშა — რეკლამირება ისევ ჩართულია");
            }
        }

        @Override public void onMtuChanged(BluetoothDevice device, int mtu) {
            trace("MTU: " + mtu);
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                           BluetoothGattCharacteristic characteristic) {
            byte[] payload = FFE1_ID.equals(characteristic.getUuid()) ? statusPacket() : new byte[]{0};
            trace("READ " + shortUuid(characteristic.getUuid()) + " offset=" + offset + " => " + hex(payload));
            byte[] response = offset < payload.length ?
                java.util.Arrays.copyOfRange(payload, offset, payload.length) : new byte[0];
            try { if (server != null) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response); }
            catch (Exception e) { trace("READ response ERROR: " + e); }
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                                       BluetoothGattDescriptor descriptor) {
            byte[] bytes = subscribed ? new byte[]{1,0} : new byte[]{0,0};
            trace("DESCRIPTOR READ " + shortUuid(descriptor.getUuid()) + " => " + hex(bytes));
            if (server != null) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                offset, offset < bytes.length ? java.util.Arrays.copyOfRange(bytes, offset, bytes.length) : new byte[0]);
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            String channel = shortUuid(characteristic.getUuid());
            trace("<<< WRITE " + channel + " len=" + (value == null ? 0 : value.length) +
                " response=" + responseNeeded + " prepared=" + preparedWrite + " offset=" + offset +
                " HEX=[" + hex(value) + "]");
            receivedWrites++;
            runOnUiThread(() -> countView.setText("მიღებული WRITE ბრძანებები: " + receivedWrites));
            if (responseNeeded && server != null)
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            trace("<<< DESCRIPTOR WRITE " + shortUuid(descriptor.getUuid()) + " HEX=[" + hex(value) + "]");
            if (CCCD_ID.equals(descriptor.getUuid()) && value != null && value.length >= 2) {
                subscribed = (value[0] & 0xff) != 0;
                trace("FFE1 NOTIFY SUBSCRIPTION: " + subscribed);
            }
            if (responseNeeded && server != null)
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            if (subscribed) handler.postDelayed(() -> sendStatus("INITIAL STATUS"), 200);
        }

        @Override public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            trace("EXECUTE WRITE: " + execute);
            if (server != null) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override public void onNotificationSent(BluetoothDevice device, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) trace("NOTIFY ERROR: " + status);
        }
    };

    private synchronized byte[] statusPacket() {
        // Observed on real 360Tok: 11 22 00 01 06 00 00 00 01 00 FF 00 3A
        byte[] packet = new byte[]{0x11,0x22,(byte)(motorOn ? 1 : 0),0x01,
            (byte)speed,0,0,0,0x01,0,(byte)0xff,0,0};
        int sum = 0;
        for (int i = 0; i < packet.length - 1; i++) sum += packet[i] & 0xff;
        packet[12] = (byte)(sum & 0xff);
        return packet;
    }

    private void sendStatus(String reason) {
        if (server == null || ipad == null || char1 == null || !subscribed) {
            trace("STATUS " + reason + ": can't notify (no connected subscribed client)");
            return;
        }
        byte[] data = statusPacket();
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int result = server.notifyCharacteristicChanged(ipad, char1, false, data);
                trace(">>> NOTIFY FFE1 " + reason + " result=" + result + " HEX=[" + hex(data) + "]");
            } else {
                char1.setValue(data);
                boolean ok = server.notifyCharacteristicChanged(ipad, char1, false);
                trace(">>> NOTIFY FFE1 " + reason + " result=" + ok + " HEX=[" + hex(data) + "]");
            }
        } catch (Exception e) {
            trace("NOTIFY ERROR: " + e.getMessage());
        }
    }

    private void restoreName() {
        if (adapter != null && nameChanged && originalName != null) {
            try { adapter.setName(originalName); } catch (Exception ignored) {}
        }
        nameChanged = false;
    }

    private void stopPeripheral() {
        try { if (advertiser != null) advertiser.stopAdvertising(advertiseCallback); }
        catch (Exception e) { trace("STOP advertise: " + e.getMessage()); }
        advertising = false;
        try { if (server != null) { server.clearServices(); server.close(); } }
        catch (Exception e) { trace("CLOSE server: " + e.getMessage()); }
        server = null;
        ipad = null;
        subscribed = false;
        restoreName();
        trace("PERIPHERAL STOPPED");
        setState("იმიტატორი გაჩერებულია");
    }

    private void setState(String state) {
        runOnUiThread(() -> { if (stateView != null) stateView.setText("სტატუსი: " + state); });
    }

    private static String shortUuid(UUID uuid) {
        if (uuid == null) return "unknown";
        return uuid.toString().substring(4, 8).toUpperCase(Locale.US);
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "(null)";
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            if (result.length() > 0) result.append(' ');
            result.append(String.format(Locale.US, "%02X", b & 255));
        }
        return result.toString();
    }

    private void trace(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = timestamp + " | " + message + "\n";
        synchronized (eventLock) {
            events.append(line);
            try (FileOutputStream out = new FileOutputStream(logFile, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
        runOnUiThread(() -> {
            if (logView == null) return;
            String text;
            synchronized (eventLock) {
                int from = Math.max(0, events.length() - 18000);
                text = events.substring(from);
            }
            logView.setText(text);
        });
    }

    @Override protected void onDestroy() {
        stopPeripheral();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
