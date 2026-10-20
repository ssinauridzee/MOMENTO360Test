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
    private TextView modeText;
    private Runnable pendingStart;


    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xff080e19);
        getWindow().setNavigationBarColor(0xff080e19);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff080e19);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(24),dp(18),dp(32));
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout header=row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark=text("M",23,Color.WHITE);
        mark.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(grad(0xff00D6C2,0xff4979DD,18));
        header.addView(mark,size(50,50));
        LinearLayout brand=new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams brp=new LinearLayout.LayoutParams(0,-2,1);
        brp.leftMargin=dp(12);
        header.addView(brand,brp);
        TextView logoText=text("MOMENTO 360",23,Color.WHITE);
        logoText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        brand.addView(logoText);
        brand.addView(text("360 PHOTO BOOTH  •  CONTROL",11,0xff8A9CB4));
        root.addView(header);

        LinearLayout conn=card(root,0xff111D2D,15);
        conn.setPadding(dp(16),dp(11),dp(16),dp(11));
        LinearLayout connTop=row();
        conn.addView(connTop);
        connectionView=text("●  გათიშულია",14,0xff8CA5BC);
        connTop.addView(connectionView,new LinearLayout.LayoutParams(0,-2,1));
        Button connect=compactButton("დაკავშირება",0xff164A54);
        connTop.addView(connect,size(118,43));
        connect.setOnClickListener(v->requestOrScan());
        Button disconnect=compactButton("✕",0xff22314B);
        LinearLayout.LayoutParams disc=size(44,43);disc.leftMargin=dp(7);
        connTop.addView(disconnect,disc);
        disconnect.setOnClickListener(v->disconnect());

        LinearLayout motorCard=card(root,0xff111C2D,24);
        LinearLayout topInfo=row();
        motorCard.addView(topInfo);
        LinearLayout topLabels=new LinearLayout(this);topLabels.setOrientation(LinearLayout.VERTICAL);
        topInfo.addView(topLabels,new LinearLayout.LayoutParams(0,-2,1));
        topLabels.addView(sectionTitle("ბრუნვის მართვა"));
        motorView=text("ძრავა: გამორთულია",13,0xff86A4BD);
        topLabels.addView(motorView);
        TextView live=text("●  MOTOR",11,0xff61E6D6);
        live.setGravity(Gravity.RIGHT);
        topInfo.addView(live);

        LinearLayout motorBtns=row();motorCard.addView(motorBtns,top(-1,-2,18));
        Button motorStart=wideButton("▶   START",0xff0FAF9C);
        motorStart.setTextSize(19);
        motorBtns.addView(motorStart,new LinearLayout.LayoutParams(0,dp(68),1));
        Button motorStop=wideButton("■   STOP",0xff9E4563);
        motorStop.setTextSize(19);
        LinearLayout.LayoutParams stopPar=new LinearLayout.LayoutParams(0,dp(68),1);
        stopPar.leftMargin=dp(10);
        motorBtns.addView(motorStop,stopPar);
        motorStart.setOnClickListener(v->startManual());
        motorStop.setOnClickListener(v->stopManual());

        modeText=text("START — უწყვეტი მუშაობა, STOP-მდე",12,0xff9FB1CB);
        motorCard.addView(modeText,top(-1,-2,12));

        motorCard.addView(subLabel("მიმართულება"),top(-1,-2,18));
        LinearLayout directions=row();motorCard.addView(directions,top(-1,-2,9));
        Button a=wideButton("↺   A",0xff233450);
        directions.addView(a,new LinearLayout.LayoutParams(0,dp(51),1));
        a.setOnClickListener(v->sendPacket(3,0,"DIRECTION A"));
        Button b=wideButton("↻   B",0xff233450);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(51),1);
        bp.leftMargin=dp(9);directions.addView(b,bp);
        b.setOnClickListener(v->sendPacket(3,1,"DIRECTION B"));

        LinearLayout speedCard=card(root,0xff111C2D,24);
        speedCard.addView(sectionTitle("სიჩქარე"));
        speedCard.addView(text("6 დონე  ·  ± რეგულირება",12,0xff8DA6C0),top(-1,-2,3));
        LinearLayout speedRow=row();speedCard.addView(speedRow,top(-1,-2,15));
        Button minus=wideButton("−",0xff233450);
        minus.setTextSize(28);speedRow.addView(minus,size(66,60));
        speedSelected=text("06",31,0xff54EAD9);
        speedSelected.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        speedSelected.setGravity(Gravity.CENTER);
        speedRow.addView(speedSelected,new LinearLayout.LayoutParams(0,dp(60),1));
        Button plus=wideButton("+",0xff233450);
        plus.setTextSize(28);speedRow.addView(plus,size(66,60));
        minus.setOnClickListener(v->applySpeed(Math.max(1,selectedSpeed-1)));
        plus.setOnClickListener(v->applySpeed(Math.min(6,selectedSpeed+1)));
        LinearLayout speedPresets=row();speedCard.addView(speedPresets,top(-1,-2,12));
        for(int n=1;n<=6;n++){
            final int speedValue=n;
            Button v=compactButton(String.valueOf(n),0xff233450);
            LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(0,dp(44),1);
            if(n>1)vp.leftMargin=dp(6);
            speedPresets.addView(v,vp);
            v.setOnClickListener(x->applySpeed(speedValue));
        }
        speedView=text("კონტროლერის სიჩქარე: —",12,0xff8DA6C0);
        speedCard.addView(speedView,top(-1,-2,10));

        LinearLayout durationCard=card(root,0xff111C2D,24);
        durationCard.addView(sectionTitle("დროით გაშვება"));
        durationCard.addView(text("აირჩიე დრო — ძრავა მაშინვე ჩაირთვება და თავად გაჩერდება",12,0xff9BB0C8),top(-1,-2,5));
        LinearLayout times=row();durationCard.addView(times,top(-1,-2,18));
        for(int sec:new int[]{5,10,15,20}){
            final int seconds=sec;
            Button timer=wideButton(sec+" წმ",0xff233450);
            timer.setTextSize(14);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(55),1);
            if(sec!=5)lp.leftMargin=dp(7);
            times.addView(timer,lp);
            timer.setOnClickListener(v->startTimed(seconds));
        }
        Button custom=wideButton("＋  სხვა დრო",0xff1A3B4C);
        durationCard.addView(custom,top(-1,49,10));
        custom.setOnClickListener(v->durationDialog());
        durationLabel=text("Manual START დროის არჩევას არ იყენებს",12,0xff61CFCB);
        durationCard.addView(durationLabel,top(-1,-2,10));

        LinearLayout lights=card(root,0xff111C2D,24);
        lights.addView(sectionTitle("განათება"));
        lights.addView(text("განათების ჩართვა და რეჟიმების ცვლა",12,0xff8DA6C0),top(-1,-2,3));
        LinearLayout led=row();lights.addView(led,top(-1,-2,16));
        Button lightOn=wideButton("☀   ON",0xff446952);
        led.addView(lightOn,new LinearLayout.LayoutParams(0,dp(55),1));
        lightOn.setOnClickListener(v->sendPacket(6,1,"LIGHT ON"));
        Button lightOff=wideButton("☾   OFF",0xff233450);
        LinearLayout.LayoutParams offp=new LinearLayout.LayoutParams(0,dp(55),1);
        offp.leftMargin=dp(9);led.addView(lightOff,offp);
        lightOff.setOnClickListener(v->sendPacket(6,0,"LIGHT OFF"));

        lights.addView(subLabel("რეჟიმი"),top(-1,-2,17));
        LinearLayout modes=row();lights.addView(modes,top(-1,-2,10));
        Button colors=wideButton("Colors",0xff233450);
        modes.addView(colors,new LinearLayout.LayoutParams(0,dp(52),1));
        colors.setOnClickListener(v->sendPacket(7,0,"COLORS MODE"));
        Button cct=wideButton("CCT",0xff156D7B);
        LinearLayout.LayoutParams cctp=new LinearLayout.LayoutParams(0,dp(52),1);cctp.leftMargin=dp(8);
        modes.addView(cct,cctp);
        cct.setOnClickListener(v->sendPacket(7,1,"CCT NEXT"));
        Button loop=wideButton("Loop",0xff233450);
        LinearLayout.LayoutParams loopp=new LinearLayout.LayoutParams(0,dp(52),1);loopp.leftMargin=dp(8);
        modes.addView(loop,loopp);
        loop.setOnClickListener(v->sendPacket(7,2,"LOOP MODE"));

        lights.addView(subLabel("სიკაშკაშე"),top(-1,-2,19));
        LinearLayout bright=row();lights.addView(bright,top(-1,-2,8));
        SeekBar slider=new SeekBar(this);slider.setMax(254);slider.setProgress(254);
        bright.addView(slider,new LinearLayout.LayoutParams(0,dp(46),1));
        TextView percentage=text("100%",13,0xff6DE9D9);
        percentage.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        bright.addView(percentage,size(52,46));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar sb,int progress,boolean byUser){
                percentage.setText(Math.round((progress+1)*100f/255)+"%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb){}
            @Override public void onStopTrackingTouch(SeekBar sb){brightnessCandidate(sb.getProgress()+1);}
        });

        root.addView(text("MOMENTO 360   ·   CONTROL STUDIO",11,0xff5F718D),top(-1,-2,21));
        BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
    }

    private TextView sectionTitle(String txt){
        TextView title=text(txt,19,Color.WHITE);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return title;
    }
    private TextView subLabel(String txt){
        TextView result=text(txt,13,0xffA7BBD1);
        result.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return result;
    }
    private LinearLayout card(LinearLayout root,int color,int radius){
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(17),dp(18),dp(17),dp(18));
        GradientDrawable background=bg(color,radius);
        background.setStroke(dp(1),0xff2B3D59);
        panel.setBackground(background);
        root.addView(panel,top(-1,-2,15));
        return panel;
    }
    private Button wideButton(String txt,int color){
        Button button=new Button(this);
        button.setAllCaps(false);
        button.setText(txt);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setBackground(bg(color,16));
        button.setPadding(dp(5),0,dp(5),0);
        return button;
    }
    private Button compactButton(String txt,int color){
        Button button=wideButton(txt,color);
        button.setTextSize(12);
        return button;
    }
    private void applySpeed(int n){
        if(!ready){toast("ჯერ დაუკავშირდი კონტროლერს");return;}
        selectedSpeed=n;
        updateSelectedSpeed();
        sendPacket(2,n,"SPEED "+n);
    }
    private void startManual(){
        if(!ready){toast("ჯერ დაუკავშირდი კონტროლერს");return;}
        modeText.setText("●  ხელით მართვა • STOP-მდე უწყვეტად");
        durationLabel.setText("ხელით გაშვება — დროის შეზღუდვის გარეშე");
        sendTimedSequence(0);
    }
    private void startTimed(int seconds){
        if(!ready){toast("ჯერ დაუკავშირდი კონტროლერს");return;}
        selectedDuration=seconds;
        modeText.setText("●  ტაიმერი • "+seconds+" წამი");
        durationLabel.setText("გაშვებულია: "+seconds+" წამით");
        sendTimedSequence(seconds);
    }
    private void sendTimedSequence(int seconds){
        if(pendingStart!=null) handler.removeCallbacks(pendingStart);
        sendPacket(4,seconds,seconds==0?"DURATION FOREVER":"DURATION "+seconds);
        pendingStart=()->{
            pendingStart=null;
            sendPacket(1,1,seconds==0?"MANUAL START":"TIMED START "+seconds);
        };
        handler.postDelayed(pendingStart,250);
    }
    private void stopManual(){
        if(pendingStart!=null)handler.removeCallbacks(pendingStart);
        pendingStart=null;
        if(!ready){toast("ჯერ დაუკავშირდი კონტროლერს");return;}
        modeText.setText("●  გაჩერებულია");
        durationLabel.setText("აირჩიე დრო ხელახლა გასაშვებად");
        sendPacket(1,0,"MOTOR STOP");
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
    private void updateSelectedSpeed(){if(speedSelected!=null)speedSelected.setText(String.format(Locale.US,"%02d",selectedSpeed));}
    private void durationDialog(){
        EditText e=new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(selectedDuration));
        new AlertDialog.Builder(this).setTitle("გაშვების ხანგრძლივობა")
            .setMessage("1-დან 60 წამამდე. დაჭერისას ძრავა მაშინვე ჩაირთვება.")
            .setView(e).setNegativeButton("გაუქმება",null)
            .setPositiveButton("გაშვება",(d,w)->{
                try{
                    int seconds=Integer.parseInt(e.getText().toString().trim());
                    if(seconds<1||seconds>60){toast("დასაშვებია 1–60 წამი");return;}
                    startTimed(seconds);
                }catch(Exception error){toast("შეიყვანე რიცხვი");}
            }).show();
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
    private void addLog(String message) { android.util.Log.d("MOMENTO360",message); }
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
                speedView.setText("კონტროლერის სიჩქარე: "+currentSpeed);
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
        if(pendingStart!=null)handler.removeCallbacks(pendingStart);
        pendingStart=null;
        stopScan();ready=false;commandCharacteristic=null;
        if(gatt!=null) { try { gatt.disconnect();gatt.close(); } catch(Exception ignored){} gatt=null; }
        setConnection("გათიშულია");
    }
    @Override protected void onDestroy() {disconnect();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
