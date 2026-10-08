package ge.momento.test360;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import java.util.UUID;

public class MainActivity extends Activity {
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private TextView status, log, motorStatus, speedStatus, lightStatus;
    private Button connectButton, sendButton;
    private EditText command;
    private Spinner channel, controlChannel;
    private boolean scanning = false;
    private int observedMotor = -1, observedLights = -1, observedSpeed = -1;
    private String lastPacket = "";
    private int duplicatePackets = 0;
    private int packetCount = 0;
    private final StringBuilder diagnosticLog = new StringBuilder();
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
        motorStatus = new TextView(this);
        motorStatus.setText("ძრავის მდგომარეობა: უცნობია");
        motorStatus.setTextSize(19);
        motorStatus.setTextColor(Color.CYAN);
        motorStatus.setPadding(0,10,0,14);
        root.addView(motorStatus);
        speedStatus = new TextView(this);
        speedStatus.setText("სიჩქარე: უცნობია");
        speedStatus.setTextSize(18);
        speedStatus.setTextColor(Color.WHITE);
        root.addView(speedStatus);
        lightStatus = new TextView(this);
        lightStatus.setText("განათება: უცნობია");
        lightStatus.setTextSize(18);
        lightStatus.setTextColor(Color.WHITE);
        root.addView(lightStatus);
        connectButton = new Button(this);
        connectButton.setText("დაკავშირება 360Tok");
        root.addView(connectButton);
        TextView controlsTitle = new TextView(this);
        controlsTitle.setText("მართვის პანელი — სატესტო რეჟიმი");
        controlsTitle.setTextColor(Color.WHITE);
        controlsTitle.setTextSize(19);
        root.addView(controlsTitle);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        root.addView(controls);
        TextView hint = new TextView(this);
        hint.setText("მართვის არხი (პირველად FFE2 სცადე)");
        hint.setTextColor(Color.YELLOW);
        controls.addView(hint);
        controlChannel = new Spinner(this);
        controlChannel.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"FFE2 — საცდელი","FFE1 — საცდელი"}));
        controls.addView(controlChannel);
        addControl(controls,"▶ START",0,1);
        addControl(controls,"■ STOP",0,0);
        addControl(controls,"＋ სიჩქარის მომატება",1,1);
        addControl(controls,"－ სიჩქარის დაკლება",1,-1);
        addControl(controls,"💡 განათების ჩართვა",3,1);
        addControl(controls,"💡 განათების გამორთვა",3,0);
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
        Button copyLog = new Button(this);
        copyLog.setText("დიაგნოსტიკის კოპირება");
        root.addView(copyLog);
        copyLog.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("MOMENTO BLE diagnostics",diagnosticLog.toString()));
            Toast.makeText(this,"ლოგი დაკოპირებულია",Toast.LENGTH_SHORT).show();
        });
        log = new TextView(this);
        log.setTextColor(Color.LTGRAY);
        log.setTextSize(12);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,520));
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.addView(root);
        setContentView(page);
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = manager.getAdapter();
        connectButton.setOnClickListener(v -> scan());
        sendButton.setOnClickListener(v -> prepareSend());
    }

    private void addControl(LinearLayout parent, String title, int action, int value) {
        Button b = new Button(this);
        b.setText(title);
        parent.addView(b);
        b.setOnClickListener(v -> {
            if (gatt == null || observedMotor < 0 || observedLights < 0 || observedSpeed < 0) {
                Toast.makeText(this,"ჯერ დაუკავშირდი და დაელოდე სტატუსს",Toast.LENGTH_LONG).show();
                return;
            }
            int motor = observedMotor, lights = observedLights, speed = observedSpeed;
            append("BUTTON " + title + " current motor=" + motor + " speed=" + speed + " lights=" + lights);
            if (action == 0) motor = value;
            if (action == 1) speed = Math.max(0,Math.min(255,speed+value));
            if (action == 3) lights = value;
            append("TEST channel=" + (controlChannel.getSelectedItemPosition()==0 ? "FFE2" : "FFE1"));
            byte[] data = new byte[]{0x11,0x22,(byte)motor,(byte)lights,(byte)speed,0,0,0,1,0,(byte)0xff,0,0};
            int sum=0;
            for (int i=0;i<12;i++) sum=(sum+(data[i]&255))&255;
            data[12]=(byte)sum;
            final byte[] candidate = data;
            new AlertDialog.Builder(this)
                .setTitle("ექსპერიმენტული მართვის ტესტი")
                .setMessage("ეს არის სტატუსის პაკეტიდან შედგენილი ჰიპოთეზური ბრძანება და შეიძლება არ იმუშაოს. პლატფორმა აუცილებლად ცარიელი უნდა იყოს. გაუგზავნო არჩეულ BLE არხზე?\n\n"+toHex(candidate))
                .setNegativeButton("გაუქმება",null)
                .setPositiveButton("ერთჯერადი ტესტი",(d,w)->sendTo(candidate,controlChannel.getSelectedItemPosition()==0?ffe2:ffe1))
                .show();
        });
    }

    private void append(String message) {
        runOnUiThread(() -> {
            diagnosticLog.append(message).append("\n");
            log.append(message + "\n");
        });
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
                    lastPacket = "";
                    duplicatePackets = 0;
                    packetCount = 0;
                    runOnUiThread(() -> motorStatus.setText("ძრავის მდგომარეობა: ველოდები მონაცემებს"));
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
                String packet = toHex(c.getValue());
                packetCount++;
                if (packet.equals(lastPacket)) {
                    duplicatePackets++;
                    if (duplicatePackets % 50 == 0) append("REPEATED x" + duplicatePackets + " (total=" + packetCount + ")");
                } else {
                    append("CHANGE #" + packetCount + " : " + packet + " (previous repeated " + duplicatePackets + " times)");
                    lastPacket = packet;
                    byte[] data = c.getValue();
                    if (data != null && data.length == 13 && (data[0] & 255) == 0x11 && (data[1] & 255) == 0x22) {
                        int checksum = 0;
                        for (int i = 0; i < 12; i++) checksum = (checksum + (data[i] & 255)) & 255;
                        if (checksum == (data[12] & 255)) {
                            final int motor = data[2] & 255;
                            final int lights = data[3] & 255;
                            final int speed = data[4] & 255;
                            observedMotor=motor;
                            observedLights=lights;
                            observedSpeed=speed;
                            runOnUiThread(() -> {
                                motorStatus.setText(motor == 1 ? "ძრავა: ჩართულია ●" : motor == 0 ? "ძრავა: გაჩერებულია ■" : "ძრავა: უცნობი მდგომარეობა " + motor);
                                speedStatus.setText("სიჩქარე: " + speed);
                                lightStatus.setText(lights == 1 ? "განათება: ჩართულია" : lights == 0 ? "განათება: გამორთულია" : "განათება: უცნობი მდგომარეობა " + lights);
                            });
                        } else append("INVALID CHECKSUM: " + packet);
                    }
                    duplicatePackets = 0;
                }
            }
            @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code) {
                append("WRITE result=" + code + " (0 means BLE write accepted, not motor action)");
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
        writeToCharacteristic(c,data,id);
    }

    private void sendTo(byte[] data,UUID id) {
        if (gatt == null) { append("Not connected"); return; }
        BluetoothGattService service = gatt.getService(serviceId);
        BluetoothGattCharacteristic c = service == null ? null : service.getCharacteristic(id);
        if (c == null) { append("Characteristic not found"); return; }
        writeToCharacteristic(c,data,id);
    }

    private void writeToCharacteristic(BluetoothGattCharacteristic c,byte[] data,UUID id) {
        int props = c.getProperties();
        if ((props & (BluetoothGattCharacteristic.PROPERTY_WRITE |
                      BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
            append("Characteristic not writable"); return;
        }
        c.setWriteType((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ?
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT :
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        c.setValue(data);
        boolean queued = gatt.writeCharacteristic(c);
        append("SEND " + id + " : " + toHex(data) + " queued=" + queued);
        runOnUiThread(() -> Toast.makeText(this,queued ? "BLE ბრძანება გაგზავნილია (შესრულება უცნობია)" : "BLE გაგზავნა ვერ მოხერხდა",Toast.LENGTH_LONG).show());
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
