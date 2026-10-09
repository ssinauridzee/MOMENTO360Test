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
    private TextView testResult;
    private boolean scanning = false;
    private boolean notificationSubscribed = false;
    private final java.util.ArrayList<BluetoothGattCharacteristic> infoReadQueue = new java.util.ArrayList<>();
    private boolean infoReading = false;
    private final UUID deviceInfoId = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    private volatile boolean bleConnected = false;
    private volatile boolean bleReady = false;
    private int observedMotor = -1, observedLights = -1, observedSpeed = -1;
    private String lastPacket = "";
    private int duplicatePackets = 0;
    private int packetCount = 0;
    private long lastStatusAt = 0;
    private int statusSequence = 0;
    private boolean autoTesting = false;
    private int autoStep = 0;
    private int baselineMotor, baselineLights, baselineSpeed;
    private Button autoButton;
    private final StringBuilder diagnosticLog = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final UUID serviceId = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private final UUID ffe1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private final UUID ffe2 = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    private final UUID[] testChannels = new UUID[]{ffe2,ffe1};
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
        controlsTitle.setText("მართვის პანელი — ექსპერიმენტული რეჟიმი");
        controlsTitle.setTextColor(Color.WHITE);
        controlsTitle.setTextSize(19);
        root.addView(controlsTitle);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        root.addView(controls);
        TextView hint = new TextView(this);
        hint.setText("მართვის ბრძანებები დაუდასტურებელია. არხის არჩევა:");
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
        testResult = new TextView(this);
        testResult.setText("ტესტის შედეგი: ჯერ არ ჩატარებულა");
        Button rfMonitor = new Button(this);
        rfMonitor.setText("📡 პულტის ბრძანებების შესწავლა");
        controls.addView(rfMonitor);
        Button inspectButton = new Button(this);
        inspectButton.setText("🔎 მწარმოებლისა და Firmware-ის წაკითხვა");
        controls.addView(inspectButton);
        inspectButton.setOnClickListener(v -> inspectGatt());
        rfMonitor.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("პულტის BLE მონიტორინგი")
            .setMessage("აპი აკვირდება პულტით გამოწვეულ ცვლილებებს. დააჭირე პულტზე თითო ღილაკს ცალ-ცალკე და ნახე ძრავის, სიჩქარისა და განათების ცვლილებები. ეს არ ნიშნავს, რომ RF კოდებს ვკითხულობთ. დიაგნოსტიკა დააკოპირე და გამომიგზავნე.")
            .setPositiveButton("გასაგებია",null).show());
        testResult.setTextColor(Color.YELLOW);
        testResult.setTextSize(17);
        controls.addView(testResult);
        autoButton = new Button(this);
        autoButton.setText("🧪 ფუნქციების ავტოტესტი (24 მცდელობა)");
        controls.addView(autoButton);
        autoButton.setOnClickListener(v -> { if (autoTesting) stopAutoTest("შეჩერებულია მომხმარებლის მიერ"); else confirmAutoTest(); });
        TextView protocolTitle = new TextView(this);
        protocolTitle.setText("ChackTok · რეალური 12-ბაიტიანი პროტოკოლი");
        protocolTitle.setTextSize(19);
        protocolTitle.setTextColor(Color.CYAN);
        root.addView(protocolTitle);
        TextView protocolHelp = new TextView(this);
        protocolHelp.setText("AA CC SS VV 22 MM TT 11 00 KK CC AA. ChackTok-ის პროტოკოლია; 360Tok-თან თავსებადობა უცნობია. ტესტები მხოლოდ ცარიელ პლატფორმაზე.");
        protocolHelp.setTextColor(Color.YELLOW);
        root.addView(protocolHelp);
        Button chackStop = new Button(this);
        chackStop.setText("■ ChackTok STOP — ერთჯერადი ტესტი");
        root.addView(chackStop);
        chackStop.setOnClickListener(v -> confirmChackStop());
        Button chackChannels = new Button(this);
        chackChannels.setText("🧪 ChackTok STOP — არხების ტესტი");
        root.addView(chackChannels);
        chackChannels.setOnClickListener(v -> confirmChackChannels());
        Button cancelTests = new Button(this);
        cancelTests.setText("■ ყველა ტესტის შეჩერება");
        root.addView(cancelTests);
        cancelTests.setOnClickListener(v -> { chackRunning=false; stopAutoTest("მომხმარებელმა შეაჩერა"); append("ALL TESTS CANCELLED"); });
        TextView candidateLabel = new TextView(this);
        candidateLabel.setText("სხვა კოდის ტესტი: SS (HEX), VV (HEX), დრო წამებში");
        candidateLabel.setTextColor(Color.WHITE);
        root.addView(candidateLabel);
        LinearLayout candidateRow = new LinearLayout(this);
        candidateRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(candidateRow);
        EditText candidateSwitch = new EditText(this);
        candidateSwitch.setHint("SS");
        candidateSwitch.setText("11");
        candidateSwitch.setTextColor(Color.WHITE);
        candidateSwitch.setSingleLine(true);
        candidateSwitch.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        candidateRow.addView(candidateSwitch,new LinearLayout.LayoutParams(0,-2,1));
        EditText candidateSpeed = new EditText(this);
        candidateSpeed.setHint("VV");
        candidateSpeed.setText("11");
        candidateSpeed.setTextColor(Color.WHITE);
        candidateSpeed.setSingleLine(true);
        candidateRow.addView(candidateSpeed,new LinearLayout.LayoutParams(0,-2,1));
        EditText candidateSeconds = new EditText(this);
        candidateSeconds.setHint("წამი");
        candidateSeconds.setText("1");
        candidateSeconds.setTextColor(Color.WHITE);
        candidateSeconds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        candidateRow.addView(candidateSeconds,new LinearLayout.LayoutParams(0,-2,1));
        Spinner chackChannel = new Spinner(this);
        chackChannel.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"FFE2","FFE1"}));
        root.addView(chackChannel);
        Button chackCandidate = new Button(this);
        chackCandidate.setText("▶ კანდიდატის გაგზავნა (ხელით)");
        root.addView(chackCandidate);
        chackCandidate.setOnClickListener(v -> {
            if(commandBusy()) { Toast.makeText(this,"მიმდინარე ტესტის დროს ხელით გაგზავნა დაბლოკილია",Toast.LENGTH_LONG).show(); return; }
            try {
                int sw = Integer.parseInt(candidateSwitch.getText().toString().trim(),16);
                int sp = Integer.parseInt(candidateSpeed.getText().toString().trim(),16);
                int seconds = Integer.parseInt(candidateSeconds.getText().toString().trim());
                if(sw<0 || sw>255 || sp<0 || sp>255 || seconds<1 || seconds>5) throw new IllegalArgumentException();
                UUID target = chackChannel.getSelectedItemPosition()==0 ? ffe2 : ffe1;
                byte[] packet = chackPacket(sw,sp,seconds);
                new AlertDialog.Builder(this).setTitle("დაუდასტურებელი ძრავის ბრძანება")
                    .setMessage("ამ ბრძანებამ შეიძლება ძრავა მოულოდნელად აამუშაოს. პლატფორმა ცარიელი უნდა იყოს და ფიზიკური STOP პულტი ხელთ გქონდეს.\\n\\n"+toHex(packet))
                    .setNegativeButton("გაუქმება",null)
                    .setPositiveButton("ერთჯერადი გაგზავნა",(d,w)->sendChack(packet,target,"MANUAL SS="+Integer.toHexString(sw),null)).show();
            } catch(Exception e) {
                Toast.makeText(this,"SS/VV: 00–FF HEX; დრო: 1–5 წამი",Toast.LENGTH_LONG).show();
            }
        });
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

    // ChackTok AppDeviceManager.BleTask.run() — reconstructed from APK.
    // This is NOT confirmed compatible with the 360Tok controller.
    private byte[] chackPacket(int sw,int speed,int seconds) {
        if(seconds<0 || seconds>60) throw new IllegalArgumentException("seconds");
        byte[] p = new byte[]{(byte)0xAA,(byte)0xCC,(byte)sw,(byte)speed,0x22,0,(byte)seconds,0x11,0,0,(byte)0xCC,(byte)0xAA};
        int sum=0;
        for(int i=2;i<=8;i++) sum+=p[i]; // signed Java byte addition matches original APK
        p[9]=(byte)(sum & 0xff);
        return p;
    }

    private void confirmChackStop() {
        if(!chackReady()) return;
        if(commandBusy()) { Toast.makeText(this,"ჯერ მიმდინარე ტესტი შეაჩერე",Toast.LENGTH_LONG).show(); return; }
        byte[] stop=chackPacket(0x33,0x11,0);
        new AlertDialog.Builder(this).setTitle("ChackTok STOP ტესტი")
            .setMessage("ChackTok-იდან აღდგენილი პაკეტი: "+toHex(stop)+"\\n\\nეს 360Tok-ის დადასტურებული STOP არ არის. გამოიყენე მხოლოდ ცარიელ პლატფორმაზე.")
            .setNegativeButton("გაუქმება",null)
            .setPositiveButton("FFE2-ზე გაგზავნა",(d,w)->sendChack(stop,ffe2,"CHACK STOP",null)).show();
    }

    private boolean chackReady() {
        if(gatt==null || !bleConnected || !bleReady) {
            Toast.makeText(this,"ჯერ დაუკავშირდი 360Tok-ს",Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private boolean chackRunning=false;
    private boolean commandBusy() { return chackRunning || autoTesting; }
    private int chackIndex=0;
    private final UUID[] chackTargets=new UUID[]{ffe2,ffe1};
    private void confirmChackChannels() {
        if(!chackReady()) return;
        if(chackRunning) { chackRunning=false; append("CHACK CHANNEL TEST CANCELLED"); return; }
        if(autoTesting) { Toast.makeText(this,"ჯერ ავტოტესტი შეაჩერე",Toast.LENGTH_LONG).show(); return; }
        new AlertDialog.Builder(this).setTitle("ChackTok STOP — 4 არხის/რეჟიმის შემოწმება")
            .setMessage("FFE2 და FFE1; WRITE და NO_RESPONSE მხოლოდ მხარდაჭერილი რეჟიმებით. ყოველი გაგზავნის შემდეგ 3 წამი. ავტომატურად არ იგზავნება START. ფიზიკური პულტი ხელთ გქონდეს.")
            .setNegativeButton("გაუქმება",null)
            .setPositiveButton("ტესტის დაწყება",(d,w)->{
                chackRunning=true; chackIndex=0;
                append("CHACK CHANNEL TEST BEGIN baseline motor="+observedMotor+" speed="+observedSpeed);
                nextChackChannel();
            }).show();
    }

    private void nextChackChannel() {
        if(!chackRunning) return;
        if(!bleConnected || !bleReady) {chackRunning=false;append("CHACK TEST: disconnected");return;}
        if(chackIndex>=4) {chackRunning=false;append("CHACK TEST COMPLETE — check status and physical motor");return;}
        final int step=chackIndex++;
        UUID target=chackTargets[step/2];
        boolean noResponse=(step%2)==1;
        byte[] packet=chackPacket(0x33,0x11,0);
        append("CHACK STEP "+(step+1)+"/4 "+target+" "+(noResponse?"NO_RESPONSE":"WRITE")+" "+toHex(packet));
        sendChack(packet,target,"CHACK STOP "+(step+1),noResponse);
        handler.postDelayed(()->{if(chackRunning) nextChackChannel();},3000);
    }

    private void sendChack(byte[] packet,UUID target,String label,Boolean forceNoResponse) {
        if(!chackReady()) return;
        BluetoothGattService svc=gatt.getService(serviceId);
        BluetoothGattCharacteristic c=svc==null?null:svc.getCharacteristic(target);
        if(c==null){append(label+" characteristic missing");return;}
        int props=c.getProperties();
        boolean noResponse=forceNoResponse==null
            ? (props & BluetoothGattCharacteristic.PROPERTY_WRITE)==0
            : forceNoResponse.booleanValue();
        int required=noResponse?BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE:BluetoothGattCharacteristic.PROPERTY_WRITE;
        if((props & required)==0){append(label+" unsupported write mode "+(noResponse?"NO_RESPONSE":"WRITE"));return;}
        c.setWriteType(noResponse?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        c.setValue(packet);
        boolean queued=gatt.writeCharacteristic(c);
        append(label+" TX="+toHex(packet)+" channel="+target+" mode="+(noResponse?"NO_RESPONSE":"WRITE")+" queued="+queued+
            " motor="+observedMotor+" speed="+observedSpeed+" statusSeq="+statusSequence);
        testResult.setText(label+" queued="+queued+" — BLE მიღება ძრავის მოქმედებას არ ადასტურებს");
    }

    private void confirmAutoTest() {
        if(chackRunning) { Toast.makeText(this,"ჯერ ChackTok ტესტი დაასრულე",Toast.LENGTH_LONG).show(); return; }
        if (gatt == null || !bleConnected || !bleReady || observedMotor < 0 || observedLights < 0 || observedSpeed < 0) {
            Toast.makeText(this,"ჯერ დაუკავშირდი და დაელოდე სტატუსს",Toast.LENGTH_LONG).show();
            return;
        }
        if (observedMotor != 0) {
            Toast.makeText(this,"ჯერ ძრავა პულტით გააჩერე",Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("ფუნქციების ავტომატური ტესტი")
            .setMessage("24 მცდელობა: განათების შეცვლა, სიჩქარის +1 ცვლილება და START, თითოეული FFE2/FFE1 არხზე. 4 წამი თითო მცდელობას შორის. ეს მხოლოდ ჰიპოთეზური 13-ბაიტიანი ფორმატია; შესაძლოა საერთოდ არ იმუშაოს. პლატფორმა ცარიელია? ფიზიკური პულტი ხელთ გაქვს?")
            .setNegativeButton("გაუქმება",null)
            .setPositiveButton("დაწყება",(d,w)->startAutoTest()).show();
    }

    private void startAutoTest() {
        autoTesting = true;
        autoStep = 0;
        baselineMotor = observedMotor;
        baselineLights = observedLights;
        baselineSpeed = observedSpeed;
        autoButton.setText("■ ტესტის შეჩერება");
        append("AUTO TEST START (connected=" + bleConnected + " ready=" + bleReady + ") baseline motor=" + baselineMotor + " lights=" + baselineLights + " speed=" + baselineSpeed);
        nextAutoStep();
    }

    private void stopAutoTest(String reason) {
        if (!autoTesting) return;
        autoTesting = false;
        autoButton.setText("🧪 ფუნქციების ავტოტესტი (24 მცდელობა)");
        testResult.setText("ავტოტესტი: " + reason);
        append("AUTO TEST END: " + reason);
    }

    private void nextAutoStep() {
        if (!autoTesting) return;
        if (gatt == null || !bleConnected || !bleReady || observedMotor < 0) {
            stopAutoTest("კავშირი ან საწყისი სტატუსი მიუწვდომელია"); return;
        }
        if (observedMotor != baselineMotor || observedLights != baselineLights || observedSpeed != baselineSpeed) {
            stopAutoTest("ცვლილება დაფიქსირდა! motor=" + observedMotor + " lights=" + observedLights + " speed=" + observedSpeed + ". ფიზიკურად გადაამოწმე");
            return;
        }
        if (autoStep >= 24) {
            stopAutoTest("24 მცდელობა დასრულდა; სტატუსში ცვლილება არ დაფიქსირდა"); return;
        }
        int function = autoStep / 4;
        UUID id = testChannels[(autoStep / 2) % 2];
        boolean withoutResponse = (autoStep % 2) == 1;
        int motor = baselineMotor;
        int lights = baselineLights;
        int speed = baselineSpeed;
        String name;
        if (function == 0) { lights = 0; name = "LIGHT OFF"; }
        else if (function == 1) { lights = 1; name = "LIGHT ON"; }
        else if (function == 2) { speed = Math.min(255,baselineSpeed + 1); name = "SPEED+1"; }
        else if (function == 3) { speed = Math.max(0,baselineSpeed - 1); name = "SPEED-1"; }
        else if (function == 4) { motor = 1; name = "START"; }
        else { motor = 0; name = "STOP"; }
        byte[] data = new byte[]{0x11,0x22,(byte)motor,(byte)lights,(byte)speed,0,0,0,1,0,(byte)0xff,0,0};
        int sum = 0;
        for (int i=0;i<12;i++) sum=(sum+(data[i]&255))&255;
        data[12]=(byte)sum;
        autoStep++;
        testResult.setText("ავტოტესტი " + autoStep + "/24: " + name + " " + (id.equals(ffe2)?"FFE2":"FFE1") + (withoutResponse?" NO_RESPONSE":" WRITE"));
        append("AUTO STEP " + autoStep + "/24 " + name + " " + id + " mode=" + (withoutResponse?"NO_RESPONSE":"WRITE") + " HEX=" + toHex(data));
        sendToMode(data,id,withoutResponse);
        handler.postDelayed(() -> { if (autoTesting) nextAutoStep(); },4000);
    }

    private void addControl(LinearLayout parent, String title, int action, int value) {
        Button b = new Button(this);
        b.setText(title);
        parent.addView(b);
        b.setOnClickListener(v -> {
            if(commandBusy()) { Toast.makeText(this,"ჯერ მიმდინარე ტესტი შეაჩერე",Toast.LENGTH_LONG).show(); return; }
            if (gatt == null || !bleConnected || !bleReady || observedMotor < 0 || observedLights < 0 || observedSpeed < 0) {
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
            final int targetMotor = motor, targetLights = lights, targetSpeed = speed;
            new AlertDialog.Builder(this)
                .setTitle("ექსპერიმენტული მართვის ტესტი")
                .setMessage("ეს არის სტატუსის პაკეტიდან შედგენილი ჰიპოთეზური ბრძანება და შეიძლება არ იმუშაოს. პლატფორმა აუცილებლად ცარიელი უნდა იყოს. გაუგზავნო არჩეულ BLE არხზე?\n\n"+toHex(candidate))
                .setNegativeButton("გაუქმება",null)
                .setPositiveButton("ერთჯერადი ტესტი",(d,w)->verifyTest(candidate,controlChannel.getSelectedItemPosition()==0?ffe2:ffe1,action,targetMotor,targetLights,targetSpeed))
                .show();
        });
    }

    private void verifyTest(byte[] data, UUID id, int action, int targetMotor, int targetLights, int targetSpeed) {
        final int initialPackets = packetCount;
        final int initialSequence = statusSequence;
        final int initialValue = action == 0 ? observedMotor : action == 1 ? observedSpeed : observedLights;
        final int expected = action == 0 ? targetMotor : action == 1 ? targetSpeed : targetLights;
        testResult.setText("ტესტის შედეგი: იგზავნება, ველოდები პასუხს...");
        append("TEST BEGIN channel=" + id + " action=" + action + " expected=" + expected);
        sendTo(data,id);
        handler.postDelayed(() -> {
            int actual = action == 0 ? observedMotor : action == 1 ? observedSpeed : observedLights;
            boolean changed = actual == expected && initialValue != expected && statusSequence > initialSequence;
            String outcome = changed && packetCount > initialPackets
                ? "სტატუსში სასურველი მდგომარეობა დაფიქსირდა (ფიზიკურად გადაამოწმე)"
                : "კონტროლერმა სასურველი მდგომარეობა არ დაადასტურა";
            testResult.setText("ტესტის შედეგი: " + outcome);
            append("TEST END: " + outcome + " expected=" + expected + " actual=" + actual
                + " notifications=" + (packetCount-initialPackets));
        },2500);
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
        if (gatt != null) { bleConnected = false; bleReady = false; gatt.disconnect(); gatt.close(); gatt = null; }
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
                    observedMotor = -1; observedLights = -1; observedSpeed = -1;
                    lastStatusAt = 0; statusSequence = 0;
                    runOnUiThread(() -> motorStatus.setText("ძრავის მდგომარეობა: ველოდები მონაცემებს"));
                    bleConnected = true;
                    bleReady = false;
                    notificationSubscribed = false;
                    append("CONNECTED");
                    runOnUiThread(() -> status.setText("დაკავშირებულია"));
                    g.discoverServices();
                } else {
                    bleConnected = false;
                    bleReady = false;
                    notificationSubscribed = false;
                    infoReading = false;
                    infoReadQueue.clear();
                    append("Disconnected: " + code);
                    runOnUiThread(() -> { if (autoTesting) stopAutoTest("კავშირი გაწყდა"); });
                    runOnUiThread(() -> {
                        status.setText("კავშირი გათიშულია");
                        sendButton.setEnabled(false);
                        observedMotor = -1; observedLights = -1; observedSpeed = -1;
                        motorStatus.setText("ძრავა: კავშირი გათიშულია");
                        speedStatus.setText("სიჩქარე: უცნობია");
                        lightStatus.setText("განათება: უცნობია");
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
                for (BluetoothGattService svc : g.getServices()) {
                    append("SERVICE " + svc.getUuid());
                    for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                        append("CHAR " + c.getUuid() + " props=" + c.getProperties());
                        for (BluetoothGattDescriptor d : c.getDescriptors()) append("DESC " + d.getUuid());
                    }
                }
                BluetoothGattCharacteristic notify = service.getCharacteristic(ffe1);
                if (notify == null || (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                    append("NOTIFY ERROR: FFE1 notification characteristic unavailable");
                    return;
                }
                boolean enabled = g.setCharacteristicNotification(notify,true);
                append("NOTIFY local enable=" + enabled);
                if (!enabled) return;
                BluetoothGattDescriptor descriptor = notify.getDescriptor(cccd);
                if (descriptor == null) {
                    append("NOTIFY ERROR: FFE1 CCCD descriptor missing");
                    return;
                }
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean queued = g.writeDescriptor(descriptor);
                append("CCCD subscription queued=" + queued);
                if (!queued) append("NOTIFY ERROR: CCCD write not queued");
            }
            @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int code) {
                if (!cccd.equals(d.getUuid()) || !ffe1.equals(d.getCharacteristic().getUuid())) return;
                append("CCCD subscription result=" + code);
                notificationSubscribed = code == BluetoothGatt.GATT_SUCCESS;
                bleReady = notificationSubscribed;
                runOnUiThread(() -> {
                    sendButton.setEnabled(notificationSubscribed);
                    status.setText(notificationSubscribed ? "Bluetooth: დაკავშირებულია · შეტყობინებები აქტიურია" : "Bluetooth: დაკავშირებულია · შეტყობინებების შეცდომა " + code);
                });
                if (notificationSubscribed) append("READY: FFE1 notifications confirmed");
            }
            @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) {
                String packet = toHex(c.getValue());
                if (packetCount < 15) append("RX " + c.getUuid() + " HEX=" + packet);
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
                            if (autoTesting && (motor != baselineMotor || lights != baselineLights || speed != baselineSpeed)) runOnUiThread(() -> stopAutoTest("სტატუსის ცვლილება დაფიქსირდა! ფიზიკურად გადაამოწმე; საჭიროებისას პულტით გააჩერე"));
                            lastStatusAt=android.os.SystemClock.elapsedRealtime();
                            statusSequence++;
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
            @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int code) {
                byte[] value = c.getValue();
                append("INFO READ " + c.getUuid() + " status=" + code + " HEX=" + toHex(value));
                if (code == BluetoothGatt.GATT_SUCCESS && value != null) {
                    String label = infoLabel(c.getUuid());
                    String decoded = new String(value, java.nio.charset.StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
                    append("INFO " + label + " = " + decoded);
                }
                handler.post(() -> readNextInfo());
            }
            @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code) {
                append("WRITE result=" + code + " (0 means BLE write accepted, not motor action)");
            }
        });
    }

    private String infoLabel(UUID id) {
        String shortId = id.toString().substring(4,8).toUpperCase(java.util.Locale.US);
        switch (shortId) {
            case "2A29": return "მწარმოებელი";
            case "2A24": return "მოდელი";
            case "2A25": return "სერიული ნომერი";
            case "2A26": return "Firmware";
            case "2A27": return "Hardware";
            case "2A28": return "Software";
            case "2A23": return "System ID (binary)";
            case "2A2A": return "IEEE ID";
            case "2A50": return "PnP ID (binary)";
            default: return shortId;
        }
    }

    private void inspectGatt() {
        if (gatt == null || !bleConnected || !bleReady) {
            Toast.makeText(this,"ჯერ დაუკავშირდი კონტროლერს",Toast.LENGTH_LONG).show();
            return;
        }
        if (infoReading) {
            Toast.makeText(this,"ინფორმაციის წაკითხვა მიმდინარეობს",Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothGattService service = gatt.getService(deviceInfoId);
        if (service == null) { append("INFO: Device Information (180A) unavailable"); return; }
        infoReadQueue.clear();
        for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                infoReadQueue.add(c);
            }
        }
        append("INFO SCAN: " + infoReadQueue.size() + " readable characteristics");
        infoReading = true;
        readNextInfo();
    }

    private void readNextInfo() {
        if (!infoReading) return;
        if (gatt == null || !bleConnected || infoReadQueue.isEmpty()) {
            infoReading = false;
            append("INFO SCAN COMPLETE — დააკოპირე დიაგნოსტიკა");
            return;
        }
        BluetoothGattCharacteristic next = infoReadQueue.remove(0);
        boolean queued = gatt.readCharacteristic(next);
        append("INFO REQUEST " + infoLabel(next.getUuid()) + " queued=" + queued);
        if (!queued) handler.postDelayed(() -> readNextInfo(), 200);
    }

    private void prepareSend() {
        if(commandBusy()) { Toast.makeText(this,"ჯერ მიმდინარე ტესტი შეაჩერე",Toast.LENGTH_LONG).show(); return; }
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

    private void sendToMode(byte[] data, UUID id, boolean withoutResponse) {
        if (gatt == null || !bleConnected || !bleReady) { append("Not connected"); return; }
        BluetoothGattService service = gatt.getService(serviceId);
        BluetoothGattCharacteristic c = service == null ? null : service.getCharacteristic(id);
        if (c == null) { append("Characteristic not found"); return; }
        int required = withoutResponse ? BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE : BluetoothGattCharacteristic.PROPERTY_WRITE;
        if ((c.getProperties() & required) == 0) { append("Write mode unsupported"); return; }
        c.setWriteType(withoutResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        c.setValue(data);
        boolean queued = gatt.writeCharacteristic(c);
        append("AUTO SEND " + id + " mode=" + (withoutResponse?"NO_RESPONSE":"WRITE") + " queued=" + queued);
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
