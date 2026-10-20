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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
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
    private boolean firstToggle = false;
    private int selectedSpeed = 6, selectedDuration=10;
    private boolean experimentalAcknowledged=false, brightnessAcknowledged=false;
    private TextView speedSelected, durationLabel;
    private LinearLayout logContainer;


    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xff090E1C);
        getWindow().setNavigationBarColor(0xff090E1C);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(0xff090E1C);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(16),dp(16),dp(28));
        scroll.addView(root);setContentView(scroll);

        LinearLayout header=row();
        header.setPadding(dp(17),dp(14),dp(16),dp(14));
        header.setBackground(grad(0xff156D83,0xff3E275F,21));
        TextView icon=text("M",27,Color.WHITE);
        icon.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(grad(0xff28C1B2,0xff795BE0,17));
        header.addView(icon,size(54,54));
        LinearLayout identity=new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,-2,1);ip.leftMargin=dp(13);
        header.addView(identity,ip);
        TextView brand=text("MOMENTO 360",23,Color.WHITE);brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        identity.addView(brand);
        identity.addView(text("CONTROL STUDIO  •  BLE",11,0xff71E4E2));
        root.addView(header);
        connectionView=text("●  OFFLINE • 360Tok კონტროლერი",14,0xff87D7D5);
        root.addView(connectionView,top(-1,-2,12));

        LinearLayout pair=row();root.addView(pair,top(-1,-2,6));
        Button connect=button(pair,"◉  დაკავშირება");weight(connect,8);
        connect.setBackground(bg(0xff205973,14));connect.setOnClickListener(v->requestOrScan());
        Button disconnect=button(pair,"გათიშვა");weight(disconnect,0);
        disconnect.setOnClickListener(v->disconnect());

        LinearLayout motorPanel=panel(root,"ძრავის მართვა","ცალკე START და STOP • კონტროლერის სტატუსით");
        motorView=text("ძრავა: უცნობია",16,0xff72EAD8);
        motorPanel.addView(motorView,top(-1,-2,10));
        LinearLayout actionRow=row();motorPanel.addView(actionRow,top(-1,-2,12));
        Button startMotor=button(actionRow,"▶  START");weight(startMotor,8);
        startMotor.setTextSize(18);startMotor.setBackground(grad(0xff0EC39A,0xff117A83,15));
        startMotor.setOnClickListener(v->sendPacket(1,1,"MOTOR START"));
        Button stopMotor=button(actionRow,"■  STOP");weight(stopMotor,0);
        stopMotor.setTextSize(18);stopMotor.setBackground(grad(0xffC85D77,0xff8E4369,15));
        stopMotor.setOnClickListener(v->sendPacket(1,0,"MOTOR STOP"));
        motorPanel.addView(text("მიმართულების შეცვლა",14,Color.WHITE),top(-1,-2,16));
        LinearLayout dir=row();motorPanel.addView(dir,top(-1,-2,8));
        Button directionA=button(dir,"↺  მიმართულება A");weight(directionA,8);
        directionA.setOnClickListener(v->sendPacket(3,0,"DIRECTION A"));
        Button directionB=button(dir,"↻  მიმართულება B");weight(directionB,0);
        directionB.setOnClickListener(v->sendPacket(3,1,"DIRECTION B"));
        motorPanel.addView(text("მიმართულებების ↺/↻ რეალური შესაბამისობა ჯერ შესამოწმებელია.",11,0xff9CB0C9),top(-1,-2,7));

        LinearLayout speedPanel=panel(root,"სიჩქარე","6 დონე • + / − / პირდაპირი არჩევა");
        LinearLayout speedRow=row();speedPanel.addView(speedRow,top(-1,-2,14));
        Button minus=button(speedRow,"−");minus.setTextSize(25);minus.setLayoutParams(size(66,56));
        speedSelected=text("06 / 06",25,0xff72EAD8);
        speedSelected.setGravity(Gravity.CENTER);speedSelected.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        speedRow.addView(speedSelected,new LinearLayout.LayoutParams(0,dp(56),1));
        Button plus=button(speedRow,"+");plus.setTextSize(25);plus.setLayoutParams(size(66,56));
        minus.setOnClickListener(v->{selectedSpeed=Math.max(1,selectedSpeed-1);updateSelectedSpeed();sendPacket(2,selectedSpeed,"SPEED "+selectedSpeed);});
        plus.setOnClickListener(v->{selectedSpeed=Math.min(6,selectedSpeed+1);updateSelectedSpeed();sendPacket(2,selectedSpeed,"SPEED "+selectedSpeed);});
        LinearLayout levels=row();speedPanel.addView(levels,top(-1,-2,12));
        for(int n=1;n<=6;n++){final int level=n;Button item=button(levels,String.valueOf(level));
            weight(item,n==6?0:4);
            item.setOnClickListener(v->{selectedSpeed=level;updateSelectedSpeed();sendPacket(2,level,"SPEED "+level);});
        }
        speedView=text("რეალური სიჩქარე: უცნობია",12,0xff9CB0C9);
        speedPanel.addView(speedView,top(-1,-2,10));

        LinearLayout durationPanel=panel(root,"ბრუნვის ხანგრძლივობა","დადასტურებულია 5, 10 და 15 წამის ბრძანებები");
        durationLabel=text("არჩეული: 10 წამი",14,0xff72EAD8);
        durationPanel.addView(durationLabel,top(-1,-2,12));
        LinearLayout durations=row();durationPanel.addView(durations,top(-1,-2,8));
        for(int n:new int[]{5,10,15,20}){final int seconds=n;
            Button item=button(durations,n+" წმ");weight(item,n==20?0:4);
            item.setOnClickListener(v->{selectedDuration=seconds;durationLabel.setText("არჩეული: "+seconds+" წამი");sendPacket(4,seconds,"DURATION "+seconds);});
        }
        Button custom=button(durationPanel,"Custom • სხვა დრო (1–60 წამი)");
        custom.setLayoutParams(top(-1,52,10));custom.setOnClickListener(v->durationDialog());

        LinearLayout lightPanel=panel(root,"განათება","LED • CCT • Colors • Loop");
        LinearLayout lights=row();lightPanel.addView(lights,top(-1,-2,14));
        Button lightOn=button(lights,"☀  LIGHT ON");weight(lightOn,8);
        lightOn.setBackground(bg(0xff395D51,14));lightOn.setOnClickListener(v->sendPacket(6,1,"LIGHT ON"));
        Button lightOff=button(lights,"☾  LIGHT OFF");weight(lightOff,0);
        lightOff.setOnClickListener(v->sendPacket(6,0,"LIGHT OFF"));
        lightPanel.addView(text("განათების რეჟიმი",14,Color.WHITE),top(-1,-2,15));
        LinearLayout modes=row();lightPanel.addView(modes,top(-1,-2,8));
        Button colors=button(modes,"Colors *");weight(colors,6);
        colors.setOnClickListener(v->modeCandidate(0,"Colors"));
        Button cct=button(modes,"CCT ↻");weight(cct,6);
        cct.setBackground(bg(0xff205973,14));
        cct.setOnClickListener(v->sendPacket(7,1,"CCT NEXT TONE"));
        Button loop=button(modes,"Loop *");weight(loop,0);
        loop.setOnClickListener(v->modeCandidate(2,"Loop"));
        lightPanel.addView(text("CCT-ის ტონების 8 ცვლილებისას iPad ყოველთვის აგზავნიდა 07 01-ს. აქ ყოველი დაჭერა ამ კოდს იმეორებს. *Colors/Loop ჯერ საცდელია.",11,0xff9CB0C9),top(-1,-2,9));
        lightPanel.addView(text("RGB ფერის არჩევა",14,Color.WHITE),top(-1,-2,14));
        LinearLayout swatches=row();lightPanel.addView(swatches,top(-1,25,10));
        for(int col:new int[]{0xffED4D74,0xffF6B64B,0xff4FE4AB,0xff4ABAF7,0xffAE7CF1,0xffFFFFFF}){
            View dot=new View(this);dot.setBackground(bg(col,50));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(24),1);p.setMargins(dp(5),0,dp(5),0);
            swatches.addView(dot,p);
            dot.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("ფერების ცალკეული კოდები").setMessage("RGB ფერის ზუსტი ბრძანებები iPad-ის ლოგში არ ჩანს. ეს წერტილები მხოლოდ ფერთა წინასწარი ხედი არის და არ აგზავნის გამოგონილ კოდებს.").setPositiveButton("გასაგებია",null).show());
        }
        lightPanel.addView(text("კონკრეტული RGB ფერები ჯერ დაუდგენელია.",11,0xff9CB0C9),top(-1,-2,8));
        lightPanel.addView(text("სიკაშკაშე • 08 xx (საცდელი)",14,Color.WHITE),top(-1,-2,15));
        LinearLayout brightRow=row();lightPanel.addView(brightRow,top(-1,-2,6));
        SeekBar brightness=new SeekBar(this);brightness.setMax(254);brightness.setProgress(254);
        brightRow.addView(brightness,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView percent=text("100%",13,0xff72EAD8);brightRow.addView(percent,size(54,44));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int p,boolean user){percent.setText(Math.round((p+1)*100f/255)+"%");}
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){brightnessCandidate(b.getProgress()+1);}
        });
        lightPanel.addView(text("08 01 და 08 FF ფიქსირდება; შუალედური მნიშვნელობები ჯერ შესამოწმებელია.",11,0xff9CB0C9));

        LinearLayout diagnostics=panel(root,"დიაგნოსტიკა","BLE TX/RX HEX პაკეტები და სტატუსი");
        Button toggleLog=button(diagnostics,"▾  ლოგის გახსნა / დახურვა");toggleLog.setLayoutParams(top(-1,49,10));
        logContainer=new LinearLayout(this);logContainer.setOrientation(LinearLayout.VERTICAL);
        logContainer.setVisibility(View.GONE);diagnostics.addView(logContainer);
        Button copy=button(logContainer,"📋 ლოგის კოპირება");copy.setLayoutParams(top(-1,48,7));
        copy.setOnClickListener(v->{((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("MOMENTO 360 Log",log.toString()));toast("ლოგი დაკოპირებულია");});
        logView=text("",11,0xffB6C9DE);logView.setTextIsSelectable(true);logContainer.addView(logView,top(-1,-2,12));
        toggleLog.setOnClickListener(v->logContainer.setVisibility(logContainer.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));

        root.addView(text("MOMENTO 360 • STUDIO v1.0 • FFE0 / FFE1",11,0xff8DA3C1),top(-1,-2,20));
        BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
        addLog("STUDIO loaded: captured iPad BLE protocol");
        addLog("CCT repeated 07/01 verified; Colors/Loop and RGB unmapped");
    }

    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private LinearLayout.LayoutParams size(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private LinearLayout.LayoutParams top(int w,int h,int margin){LinearLayout.LayoutParams p=size(w,h);p.topMargin=dp(margin);return p;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private GradientDrawable grad(int from,int to,int radius){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{from,to});d.setCornerRadius(dp(radius));return d;}
    private void weight(Button b,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(51),1);p.rightMargin=dp(margin);b.setLayoutParams(p);}
    private LinearLayout panel(LinearLayout root,String title,String subtitle){
        LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(17),dp(17),dp(17),dp(18));
        GradientDrawable background=bg(0xff152038,21);background.setStroke(dp(1),0xff2B3D5B);p.setBackground(background);root.addView(p,top(-1,-2,14));
        TextView heading=text(title,19,Color.WHITE);heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);p.addView(heading);
        p.addView(text(subtitle,12,0xff9CB0C9),top(-1,-2,4));return p;
    }
    private void updateSelectedSpeed(){if(speedSelected!=null)speedSelected.setText(String.format(Locale.US,"%02d / 06",selectedSpeed));}
    private void durationDialog(){
        EditText e=new EditText(this);e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);e.setText(String.valueOf(selectedDuration));
        new AlertDialog.Builder(this).setTitle("ხანგრძლივობა წამებში").setView(e).setNegativeButton("არა",null)
          .setPositiveButton("დაყენება",(d,w)->{try{int seconds=Integer.parseInt(e.getText().toString().trim());
             if(seconds<1||seconds>60){toast("დასაშვებია 1–60 წამი");return;}
             selectedDuration=seconds;durationLabel.setText("არჩეული: "+seconds+" წამი");sendPacket(4,seconds,"DURATION "+seconds);
          }catch(Exception x){toast("შეიყვანე რიცხვი");}}).show();
    }
    private void modeCandidate(int val,String name){
        Runnable action=()->sendPacket(7,val,name+" EXPERIMENTAL");
        if(experimentalAcknowledged){action.run();return;}
        new AlertDialog.Builder(this).setTitle(name+" — საცდელი").setMessage("CCT 07 01 დადასტურებულია, მაგრამ 07 00 და 07 02-ის Colors/Loop შესაბამისობა ჯერ არ დადასტურებულა.")
          .setNegativeButton("არა",null).setPositiveButton("გამოცდა",(d,w)->{experimentalAcknowledged=true;action.run();}).show();
    }
    private void brightnessCandidate(int value){
        Runnable action=()->sendPacket(8,value,"BRIGHTNESS CANDIDATE "+value);
        if(brightnessAcknowledged){action.run();return;}
        new AlertDialog.Builder(this).setTitle("სიკაშკაშის საცდელი კოდი").setMessage("ლოგში 08 01 და 08 FF არსებობს; შუალედური მნიშვნელობები დაუდასტურებელია.")
          .setNegativeButton("არა",null).setPositiveButton("გამოცდა",(d,w)->{brightnessAcknowledged=true;action.run();}).show();
    }

    private TextView text(String txt,int size,int color) {
        TextView t = new TextView(this);
        t.setText(txt); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,8,0,8); return t;
    }
    private Button button(LinearLayout root,String caption) {
        Button b=new Button(this);b.setText(caption);b.setAllCaps(false);b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextSize(14);
        b.setPadding(dp(5),0,dp(5),0);b.setBackground(bg(0xff293B57,14));
        root.addView(b,size(-1,51));return b;
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
            motor=currentMotor;speed=currentSpeed;selectedSpeed=currentSpeed;
            runOnUiThread(() -> {
                motorView.setText("ძრავა: "+(currentMotor==0?"გამორთულია":currentMotor==1?"ჩართულია":"მდგომარეობა "+currentMotor));
                speedView.setText("რეალური სიჩქარე: "+currentSpeed);
                updateSelectedSpeed();
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
