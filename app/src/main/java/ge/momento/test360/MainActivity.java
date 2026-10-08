package ge.momento.test360;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import java.util.UUID;

public class MainActivity extends Activity {
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private TextView status, log;
    private Button connectButton, sendButton;
    private EditText command;
    private Spinner channel;
    private boolean scanning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final UUID serviceId = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private final UUID ffe1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private final UUID ffe2 = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    private final UUID cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 35, 24, 20);
        root.setBackgroundColor(Color.rgb(14,19,32));
        TextView title = new TextView(this);
        title.setText("MOMENTO 360");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        status = new TextView(this);
        status.setText("Bluetooth: მზადაა");
        status.setTextColor(Color.WHITE);
        status.setPadding(0,20,0,16);
        root.addView(status);
        connectButton = new Button(this);
        connectButton.setText("დაკავშირება 360Tok");
        root.addView(connectButton);
        channel = new Spinner(this);
        channel.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"FFE1", "FFE2"}));
        root.addView(channel);
        command = new EditText(this);
        command.setHint("HEX ბრძანება");
        command.setTextColor(Color.WHITE);
        command.setHintTextColor(Color.LTGRAY);
        command.setSingleLine(true);
        root.addView(command);
        sendButton = new Button(this);
        sendButton.setText("ბრძანების გაგზავნა");
        sendButton.setEnabled(false);
        root.addView(sendButton);
        TextView caution = new TextView(this);
        caution.setText("START/STOP კოდები ჯერ უცნობია. ტესტირებისას პლატფორმა ცარიელი უნდა იყოს.");
        caution.setTextColor(Color.YELLOW);
        root.addView(caution);
        log = new TextView(this);
        log.setTextColor(Color.LTGRAY);
        log.setTextSize(12);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = manager.getAdapter();
        connectButton.setOnClickListener(v -> scan());
        sendButton.setOnClickListener(v -> prepareSend());
    }

    private void append(String message) {
        runOnUiThread(() -> log.append(message + "\n"));
    }

    private boolean permitted() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, 100);
                return false;
            }
        } else if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},100);
            return false;
        }
        return true;
    }

    private void scan() {
        if (!permitted()) return;
        if (adapter == null || !adapter.isEnabled()) {
            status.setText("ჩართე Bluetooth");
            return;
        }
        if (scanning) return;
        sendButton.setEnabled(false);
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { append("BLE scanner unavailable"); return; }
        scanning = true;
        status.setText("ძებნა...");
        scanner.startScan(scanCallback);
        handler.postDelayed(() -> {
            if (scanning) {
                scanning = false;
                scanner.stopScan(scanCallback);
                append("Scan timeout");
            }
        },12000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null) name = device.getName();
            if (name != null && name.toLowerCase().contains("360tok")) {
                scanning = false;
                scanner.stopScan(this);
                append("FOUND: " + name);
                connect(device);
            }
        }
        @Override public void onScanFailed(int error) {
            scanning = false;
            append("Scan failed: " + error);
        }
    };

    private void connect(BluetoothDevice device) {
        status.setText("დაკავშირება...");
        gatt = device.connectGatt(this,false,new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g,int code,int state) {
                if (code == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    append("CONNECTED");
                    runOnUiThread(() -> status.setText("დაკავშირებულია"));
                    g.discoverServices();
                } else {
                    append("Disconnected: " + code);
                    runOnUiThread(() -> {
                        status.setText("კავშირი გათიშულია");
                        sendButton.setEnabled(false);
                    });
                }
            }
            @Override public void onServicesDiscovered(BluetoothGatt g,int code) {
                if (code != BluetoothGatt.GATT_SUCCESS) {
                    append("Service discovery failed: " + code);
                    return;
                }
                BluetoothGattService service = g.getService(serviceId);
                if (service == null) { append("FFE0 not found"); return; }
                for (BluetoothGattCharacteristic c : service.getCharacteristics())
                    append("CHAR " + c.getUuid() + " props=" + c.getProperties());
                BluetoothGattCharacteristic notify = service.getCharacteristic(ffe1);
                if (notify != null && (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    g.setCharacteristicNotification(notify,true);
                    BluetoothGattDescriptor descriptor = notify.getDescriptor(cccd);
                    if (descriptor != null) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(descriptor);
                    }
                }
                runOnUiThread(() -> {
                    sendButton.setEnabled(true);
                    append("READY");
                });
            }
            @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) {
                append("RECEIVED " + c.getUuid() + " : " + toHex(c.getValue()));
            }
            @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code) {
                append("WRITE result=" + code);
            }
        });
    }

    private void prepareSend() {
        String hex = command.getText().toString().replaceAll("[\\s,:-]","");
        if (hex.length() == 0 || hex.length() % 2 != 0 || !hex.matches("[0-9a-fA-F]+")) {
            Toast.makeText(this,"შეიყვანე სწორი HEX ბრძანება",Toast.LENGTH_LONG).show();
            return;
        }
        byte[] data = new byte[hex.length()/2];
        for (int i=0;i<data.length;i++)
            data[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        new AlertDialog.Builder(this).setTitle("უსაფრთხოების შემოწმება")
            .setMessage("პლატფორმა ცარიელია? უცნობმა ბრძანებამ შეიძლება ძრავა აამუშაოს.")
            .setNegativeButton("გაუქმება",null)
            .setPositiveButton("გაგზავნა",(d,w)->send(data)).show();
    }

    private void send(byte[] data) {
        if (gatt == null) { append("Not connected"); return; }
        BluetoothGattService service = gatt.getService(serviceId);
        UUID id = channel.getSelectedItemPosition() == 0 ? ffe1 : ffe2;
        BluetoothGattCharacteristic c = service == null ? null : service.getCharacteristic(id);
        if (c == null) { append("Characteristic not found"); return; }
        int props = c.getProperties();
        if ((props & (BluetoothGattCharacteristic.PROPERTY_WRITE |
                      BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
            append("Characteristic not writable"); return;
        }
        c.setWriteType((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ?
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT :
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        c.setValue(data);
        append("SEND " + id + " : " + toHex(data) + " queued=" + gatt.writeCharacteristic(c));
    }

    private String toHex(byte[] bytes) {
        if (bytes == null) return "(null)";
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format("%02X ",b & 255));
        return s.toString().trim();
    }

    @Override protected void onDestroy() {
        if (scanner != null && scanning) scanner.stopScan(scanCallback);
        if (gatt != null) { gatt.disconnect(); gatt.close(); }
        super.onDestroy();
    }
}
