package ge.momento.test360;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private final UUID SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private final UUID FFE1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private final UUID FFE2 = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    private final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandCharacteristic;
    private boolean scanning, ready, waitingNotification;
    private int motor = -1, speed = -1, packetsReceived = 0;
    private TextView connectionView, motorView, speedView, logView;
    private final StringBuilder log = new StringBuilder();
    private LinearLayout commandArea;
    private boolean firstToggle = true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,24);
        root.setBackgroundColor(Color.rgb(13,20,34));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        TextView header = text("MOMENTO 360 · REAL BLE CONTROL", 22, Color.CYAN);
        header.setGravity(Gravity.CENTER);
        root.addView(header);
        root.addView(text("iPad-ის 360Tok-იდან რეალურად ჩაწერილი ბრძანებები", 15, Color.WHITE));
        root.addView(text("გაფრთხილება: პლატფორმა იყოს ცარიელი. ფიზიკური STOP პულტი გქონდეს ხელში. უცნობია 01 01 ბრძანება Toggle-ია თუ START.", 15, Color.YELLOW));

        connectionView = text("კავშირი: გათიშულია", 18, Color.GREEN);
        motorView = text("ძრავა: უცნობია", 18, Color.WHITE);
        speedView = text("სიჩქარე: უცნობია", 18, Color.WHITE);
        root.addView(connectionView);
        root.addView(motorView);
        root.addView(speedView);

        Button connect = button(root, "📡 რეალურ 360Tok-00772-თან დაკავშირება");
        connect.setOnClickListener(v -> requestOrScan());
        Button disconnect = button(root, "კავშირის გაწყვეტა");
        disconnect.setOnClickListener(v -> disconnect());

        root.addView(text("მართვა — დადასტურებული HEX პაკეტები", 19, Color.CYAN));
        commandArea = new LinearLayout(this);
        commandArea.setOrientation(LinearLayout.VERTICAL);
        root.addView(commandArea);
        Button toggle = button(commandArea, "▶ / ■ ძრავის მართვის ბრძანება (01 01)");
        toggle.setOnClickListener(v -> {
            if (!ready) { toast("ჯერ დაუკავშირდი რეალურ კონტროლერს"); return; }
            if (firstToggle) {
                new AlertDialog.Builder(this).setTitle("ფიზიკური ძრავის ტესტი")
                    .setMessage("პლატფორმა ცარიელი უნდა იყოს. ფიზიკური STOP პულტი მზად გქონდეს. iPad-ის ლოგიდან ვიცით, რომ ბრძანება 88 99 01 01 00 00 00 23 გაიგზავნა, მაგრამ მისი ზუსტი ეფექტი ჯერ არ დადასტურებულა.")
                    .setNegativeButton("გაუქმება",null)
                    .setPositiveButton("გაგზავნა", (d,w) -> { firstToggle = false; sendPacket(1,1,"Motor 01 01"); })
                    .show();
            } else sendPacket(1,1,"Motor 01 01");
        });

        LinearLayout speeds = new LinearLayout(this);
        speeds.setOrientation(LinearLayout.VERTICAL);
        commandArea.addView(speeds);
        for (int level : new int[]{1,2,3,4,6}) {
            Button b = button(speeds, "სიჩქარის ბრძანება " + level + " (iPad-იდან ჩაწერილი)");
            b.setOnClickListener(v -> sendPacket(2, level, "Speed " + level));
        }
        root.addView(text("სიჩქარე 5 ამ კონკრეტულ ლოგში არ ჩანს, ამიტომ არ გამოგვიცდია.", 13, Color.YELLOW));
        Button copy = button(root, "📋 დიაგნოსტიკური ლოგის კოპირება");
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("BLE control", log.toString()));
            toast("ლოგი დაკოპირებულია");
        });
        root.addView(text("მიღებული სტატუსი და გაგზავნილი HEX:", 18, Color.CYAN));
        logView = text("", 13, Color.WHITE);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        BluetoothManager manager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        addLog("READY: real 360Tok command 88 99 01 01 00 00 00 23; speed op=02.");
        addLog("IMPORTANT: commands are captured, but physical motor effects need confirmation.");
    }

    private TextView text(String txt,int size,int color) {
        TextView t = new TextView(this);
        t.setText(txt); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,8,0,8); return t;
    }
    private Button button(LinearLayout root,String caption) {
        Button b = new Button(this); b.setText(caption); b.setAllCaps(false); root.addView(b); return b;
    }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void setConnection(String s) { runOnUiThread(() -> connectionView.setText("კავშირი: "+s)); }
    private void addLog(String message) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date())+" | "+message+"\n";
        runOnUiThread(() -> {
            log.append(line);
            if (log.length()>70000) log.delete(0,20000);
            logView.setText(log.toString());
        });
    }
    private boolean hasPermissions() {
        return Build.VERSION.SDK_INT < 31 ||
            (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
             checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);
    }
    private void requestOrScan() {
        if (Build.VERSION.SDK_INT>=31 && !hasPermissions()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},50);
            return;
        }
        scan();
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] result) {
        super.onRequestPermissionsResult(code,names,result);
        if (code==50) { if (hasPermissions()) scan(); else toast("Bluetooth ნებართვა აუცილებელია"); }
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult r) {
            BluetoothDevice device = r.getDevice();
            if (device==null) return;
            String name = r.getScanRecord()==null ? null : r.getScanRecord().getDeviceName();
            if (name==null && Build.VERSION.SDK_INT>=31 && !hasPermissions()) return;
            if (name==null) { try { name=device.getName(); } catch(Exception ignored) {} }
            if (name!=null && name.startsWith("360Tok-")) {
                stopScan();
                addLog("FOUND: "+name+" ["+device.getAddress()+"]");
                setConnection("მოწყობილობა ნაპოვნია, ვუკავშირდებით...");
                runOnUiThread(() -> gatt=device.connectGatt(MainActivity.this,false,gattCallback,BluetoothDevice.TRANSPORT_LE));
            }
        }
        @Override public void onScanFailed(int error) { scanning=false; addLog("SCAN ERROR="+error); setConnection("ძებნის შეცდომა "+error); }
    };
    private void scan() {
        if (adapter==null || !adapter.isEnabled()) { toast("ჩართე Bluetooth"); return; }
        disconnect();
        scanner=adapter.getBluetoothLeScanner();
        if (scanner==null) { toast("BLE Scanner მიუწვდომელია"); return; }
        scanner.startScan(scanCallback);
        scanning=true;
        setConnection("ვეძებთ 360Tok-00772...");
        addLog("SCAN START");
        handler.postDelayed(() -> {
            if (scanning) { stopScan(); setConnection("მოწყობილობა ვერ მოიძებნა"); addLog("SCAN TIMEOUT"); }
        },16000);
    }
    private void stopScan() {
        if (!scanning || scanner==null) return;
        scanning=false;
        try { scanner.stopScan(scanCallback); } catch(Exception e) { addLog("SCAN STOP ERROR "+e.getMessage()); }
    }
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current,int status,int newState) {
            addLog("CONNECTION status="+status+" newState="+newState);
            if (newState==BluetoothProfile.STATE_CONNECTED) {
                setConnection("დაკავშირებულია, სერვისების ძებნა...");
                current.discoverServices();
            } else {
                ready=false;
                setConnection("გათიშულია (status="+status+")");
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt current,int status) {
            if (status!=BluetoothGatt.GATT_SUCCESS) { addLog("DISCOVER ERROR "+status); return; }
            BluetoothGattService service=current.getService(SERVICE);
            if (service==null) { addLog("ERROR no FFE0 service"); setConnection("FFE0 არ ჩანს"); return; }
            commandCharacteristic=service.getCharacteristic(FFE1);
            if (commandCharacteristic==null) { addLog("ERROR no FFE1 characteristic"); return; }
            addLog("SERVICE FFE0; COMMAND FFE1 props="+commandCharacteristic.getProperties());
            boolean set=current.setCharacteristicNotification(commandCharacteristic,true);
            BluetoothGattDescriptor cccd=commandCharacteristic.getDescriptor(CCCD);
            if (cccd!=null) {
                if (Build.VERSION.SDK_INT>=33) {
                    int code=current.writeDescriptor(cccd,new byte[]{1,0});
                    addLog("ENABLE NOTIFY request="+code+" set="+set);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    boolean ok=current.writeDescriptor(cccd);
                    addLog("ENABLE NOTIFY request="+ok+" set="+set);
                }
            } else {
                addLog("WARNING: no CCCD descriptor");
                ready=true;
                setConnection("მზადაა (FFE1)");
            }
        }
        @Override public void onDescriptorWrite(BluetoothGatt current,BluetoothGattDescriptor desc,int status) {
            addLog("DESCRIPTOR WRITE="+status);
            ready=(status==BluetoothGatt.GATT_SUCCESS);
            setConnection(ready?"მზადაა — რეალური კონტროლერი":"შეტყობინების გამოწერა ვერ მოხერხდა");
        }
        @Override public void onCharacteristicChanged(BluetoothGatt current,BluetoothGattCharacteristic characteristic,byte[] value) {
            acceptStatus(characteristic.getUuid(),value);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt current,BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT<33) acceptStatus(characteristic.getUuid(),characteristic.getValue());
        }
        @Override public void onCharacteristicWrite(BluetoothGatt current,BluetoothGattCharacteristic characteristic,int status) {
            addLog("WRITE CALLBACK status="+status);
        }
    };
    private void acceptStatus(UUID uuid,byte[] payload) {
        if (!FFE1.equals(uuid)||payload==null) return;
        addLog("RX FFE1 ["+hex(payload)+"]");
        if (payload.length>=13 && (payload[0]&255)==0x11 && (payload[1]&255)==0x22) {
            int currentMotor=payload[2]&255;
            int currentSpeed=payload[4]&255;
            motor=currentMotor;speed=currentSpeed;
            runOnUiThread(() -> {
                motorView.setText("ძრავა: "+(currentMotor==0?"გამორთულია":currentMotor==1?"ჩართულია":"მდგომარეობა "+currentMotor));
                speedView.setText("რეალური სიჩქარე: "+currentSpeed);
            });
            packetsReceived++;
        }
    }
    private byte[] packet(int op,int value) {
        byte[] p=new byte[]{(byte)0x88,(byte)0x99,(byte)op,(byte)value,0,0,0,0};
        int sum=0;for(int i=0;i<7;i++)sum+=(p[i]&255);
        p[7]=(byte)(sum&255);
        return p;
    }
    private void sendPacket(int op,int value,String label) {
        if (!ready||gatt==null||commandCharacteristic==null) { toast("კონტროლერს ჯერ არ დაუკავშირდი");return; }
        byte[] bytes=packet(op,value);
        int properties=commandCharacteristic.getProperties();
        int writeType=(properties&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try {
            if (Build.VERSION.SDK_INT>=33) {
                int result=gatt.writeCharacteristic(commandCharacteristic,bytes,writeType);
                addLog("TX FFE1 "+label+" ["+hex(bytes)+"] Android API result="+result+" (არ არის ძრავის მოქმედების დასტური)");
            } else {
                commandCharacteristic.setWriteType(writeType);
                commandCharacteristic.setValue(bytes);
                boolean result=gatt.writeCharacteristic(commandCharacteristic);
                addLog("TX FFE1 "+label+" ["+hex(bytes)+"] queued="+result+" (არ არის ძრავის მოქმედების დასტური)");
            }
        } catch(Exception e) { addLog("TX ERROR "+e.getMessage()); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder b=new StringBuilder();
        for(byte x:bytes){if(b.length()>0)b.append(' ');b.append(String.format(Locale.US,"%02X",x&255));}
        return b.toString();
    }
    private void disconnect() {
        stopScan();ready=false;commandCharacteristic=null;
        if(gatt!=null) { try { gatt.disconnect();gatt.close(); } catch(Exception ignored){} gatt=null; }
        setConnection("გათიშულია");
    }
    @Override protected void onDestroy() {disconnect();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
