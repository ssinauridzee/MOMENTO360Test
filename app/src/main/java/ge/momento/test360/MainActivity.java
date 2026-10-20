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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
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
    private MotorRingButton mainMotorButton;
    private TextView motorChip, connectionBadge, speedNumber;
    private Button directionAButton, directionBButton, colorsButton, cctButton, loopButton, ledOnButton, ledOffButton, customTimeButton;
    private final java.util.ArrayList<Button> timedPresetButtons = new java.util.ArrayList<>();
    private final int[] durations = new int[]{5,10,15,20};
    private Runnable autoUiStop;
    private boolean motorUiRunning = false;
    private boolean motorStatusSeen = false;
    private int selectedDirection = 0;
    private int selectedLightMode = 0;
    private int selectedTimer = 0;
    private boolean ledUiOn = true;
    private final int ink = 0xff07101f, panelInk = 0xff101d34, softText = 0xffA6BAD4,
            pearl = 0xffF5F8FF, turquoise = 0xff4CE7E0, blue = 0xff096DFA, pink = 0xffED3979;



    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(ink);
        getWindow().setNavigationBarColor(ink);
        getWindow().getDecorView().setSystemUiVisibility(0);

        ScrollView viewport=new ScrollView(this);
        viewport.setFillViewport(true);
        viewport.setBackgroundColor(ink);
        viewport.setVerticalScrollBarEnabled(false);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(15),dp(17),dp(15),dp(25));
        viewport.addView(root);
        setContentView(viewport);

        // Compact header with actual connection indicator.
        LinearLayout header=horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header,layout(-1,58,0));
        TextView emblem=makeText("M",26,pearl,true);
        emblem.setGravity(Gravity.CENTER);
        emblem.setBackground(gradient(new int[]{0xff15E3CB,0xff176FED,0xff644CE4},14));
        header.addView(emblem,layout(49,49,0));
        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);
        titleParams.leftMargin=dp(10);
        header.addView(titles,titleParams);
        TextView brand=makeText("MOMENTO 360",18,pearl,true);
        brand.setSingleLine(true);
        brand.setTextSize(18);
        titles.addView(brand);
        titles.addView(makeText("360 PHOTO BOOTH",10,0xff8DA3C0,false));
        connectionBadge=makeText("●  OFFLINE",10,0xff9BB0CC,true);
        connectionBadge.setGravity(Gravity.CENTER);
        connectionBadge.setBackground(strokeBackground(0xff10223A,0xff294560,28));
        header.addView(connectionBadge,layout(103,35,0));
        connectionBadge.setOnClickListener(v -> {
            if(ready) new AlertDialog.Builder(this).setTitle("Bluetooth")
                    .setMessage("360Tok-00772 დაკავშირებულია.")
                    .setNegativeButton("დახურვა",null)
                    .setPositiveButton("გათიშვა",(d,w)->disconnect()).show();
            else requestOrScan();
        });

        // The one single circular start / stop button controls the motor state.
        LinearLayout motorCard=panel(root,0);
        LinearLayout topMotor=horizontal();
        topMotor.setGravity(Gravity.CENTER_VERTICAL);
        motorCard.addView(topMotor);
        topMotor.addView(makeText("◉",18,turquoise,true),layout(28,-2,0));
        topMotor.addView(makeText("ბრუნვის მართვა",17,pearl,true));
        mainMotorButton=new MotorRingButton();
        LinearLayout.LayoutParams motorCircle=new LinearLayout.LayoutParams(dp(178),dp(178));
        motorCircle.gravity=Gravity.CENTER_HORIZONTAL;
        motorCircle.topMargin=dp(4);
        motorCard.addView(mainMotorButton,motorCircle);
        mainMotorButton.setOnClickListener(v->toggleMotor());
        motorChip=makeText("●  გათიშულია",12,0xff9CAFC8,true);
        motorChip.setGravity(Gravity.CENTER);
        motorChip.setBackground(strokeBackground(0xff0C2133,0xff203F50,24));
        LinearLayout.LayoutParams statusP=layout(176,32,0);
        statusP.gravity=Gravity.CENTER_HORIZONTAL;
        motorCard.addView(motorChip,statusP);

        LinearLayout drow=horizontal();
        drow.setGravity(Gravity.CENTER_VERTICAL);
        motorCard.addView(drow,layout(-1,47,14));
        directionAButton=controlButton("↶  ნორმალური",true);
        directionBButton=controlButton("↷  უკუ",false);
        drow.addView(directionAButton,new LinearLayout.LayoutParams(0,dp(47),1));
        LinearLayout.LayoutParams dirEnd=new LinearLayout.LayoutParams(0,dp(47),1);
        dirEnd.leftMargin=dp(8);
        drow.addView(directionBButton,dirEnd);
        directionAButton.setOnClickListener(v->chooseDirection(0));
        directionBButton.setOnClickListener(v->chooseDirection(1));

        // Compact speed console.
        LinearLayout speedCard=panel(root,13);
        speedCard.addView(sectionHead("◴  სიჩქარე"));
        LinearLayout speedConsole=horizontal();
        speedConsole.setGravity(Gravity.CENTER_VERTICAL);
        speedCard.addView(speedConsole,layout(-1,57,10));
        Button decrease=controlButton("−",false);
        decrease.setTextSize(27);
        speedConsole.addView(decrease,layout(71,57,0));
        speedNumber=makeText("6",30,pearl,true);
        speedNumber.setGravity(Gravity.CENTER);
        speedConsole.addView(speedNumber,new LinearLayout.LayoutParams(0,dp(57),1));
        Button increase=controlButton("+",false);
        increase.setTextSize(27);
        speedConsole.addView(increase,layout(71,57,0));
        decrease.setOnClickListener(v->applySpeed(Math.max(1,selectedSpeed-1)));
        increase.setOnClickListener(v->applySpeed(Math.min(6,selectedSpeed+1)));
        speedView=makeText("1–6",10,softText,false);
        speedView.setGravity(Gravity.CENTER);
        speedCard.addView(speedView);

        // Timer chips launch the device immediately (not just selection).
        LinearLayout timedCard=panel(root,13);
        timedCard.addView(sectionHead("◷  დროით გაშვება"));
        LinearLayout timerRow=horizontal();
        timedCard.addView(timerRow,layout(-1,48,12));
        for(int i=0;i<durations.length;i++){
            final int seconds=durations[i];
            Button bt=controlButton(seconds+" წმ",false);
            bt.setTextSize(12);
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(48),1);
            if(i>0)tp.leftMargin=dp(5);
            timerRow.addView(bt,tp);
            timedPresetButtons.add(bt);
            bt.setOnClickListener(v->startTimed(seconds));
        }
        customTimeButton=controlButton("✎  სხვა",false);
        customTimeButton.setTextSize(11);
        LinearLayout.LayoutParams customP=new LinearLayout.LayoutParams(0,dp(48),1.13f);
        customP.leftMargin=dp(5);
        timerRow.addView(customTimeButton,customP);
        customTimeButton.setOnClickListener(v->durationDialog());

        // Lighting: Colors is explicitly pre-selected, without a surprise BLE write.
        LinearLayout lighting=panel(root,13);
        LinearLayout ledHeader=horizontal();
        ledHeader.setGravity(Gravity.CENTER_VERTICAL);
        lighting.addView(ledHeader,layout(-1,44,0));
        ledHeader.addView(sectionHead("✦  განათება"),new LinearLayout.LayoutParams(0,-2,1));
        ledOnButton=controlButton("☀  ON",true);
        ledOffButton=controlButton("☾ OFF",false);
        ledOnButton.setTextSize(12);
        ledOffButton.setTextSize(12);
        ledHeader.addView(ledOnButton,layout(71,41,0));
        LinearLayout.LayoutParams offP=layout(72,41,0);offP.leftMargin=dp(5);
        ledHeader.addView(ledOffButton,offP);
        ledOnButton.setOnClickListener(v->chooseLed(true));
        ledOffButton.setOnClickListener(v->chooseLed(false));
        LinearLayout modes=horizontal();
        lighting.addView(modes,layout(-1,45,11));
        colorsButton=controlButton("Colors",true);
        cctButton=controlButton("CCT",false);
        loopButton=controlButton("Loop",false);
        modes.addView(colorsButton,new LinearLayout.LayoutParams(0,dp(45),1));
        LinearLayout.LayoutParams middle=new LinearLayout.LayoutParams(0,dp(45),1);
        middle.leftMargin=dp(7);
        modes.addView(cctButton,middle);
        LinearLayout.LayoutParams endP=new LinearLayout.LayoutParams(0,dp(45),1);
        endP.leftMargin=dp(7);
        modes.addView(loopButton,endP);
        colorsButton.setOnClickListener(v->chooseLightingMode(0));
        cctButton.setOnClickListener(v->chooseLightingMode(1));
        loopButton.setOnClickListener(v->chooseLightingMode(2));

        LinearLayout levelLabels=horizontal();
        levelLabels.setGravity(Gravity.CENTER_VERTICAL);
        lighting.addView(levelLabels,layout(-1,34,12));
        levelLabels.addView(makeText("☼  სიკაშკაშე",12,softText,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView percent=makeText("100%",12,pearl,true);
        levelLabels.addView(percent);
        SeekBar brightness=new SeekBar(this);
        brightness.setMax(254);
        brightness.setProgress(254);
        if(Build.VERSION.SDK_INT>=21){
            brightness.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff04D5D2));
            brightness.setThumbTintList(android.content.res.ColorStateList.valueOf(0xffE8F6FF));
        }
        lighting.addView(brightness,layout(-1,34,0));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar seek,int position,boolean byUser){
                percent.setText(Math.round((position+1)*100f/255)+"%");
            }
            @Override public void onStartTrackingTouch(SeekBar seek){}
            @Override public void onStopTrackingTouch(SeekBar seek){
                if(ready) sendPacket(8,seek.getProgress()+1,"BRIGHTNESS");
            }
        });

        BluetoothManager manager=(BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
        syncVisualStates();
    }

    // Reusable polished UI primitives.
    private int dp(int value){return (int)(getResources().getDisplayMetrics().density*value+0.5f);}
    private LinearLayout.LayoutParams layout(int width,int height,int marginTop){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));
        lp.topMargin=dp(marginTop);
        return lp;
    }
    private LinearLayout horizontal(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;
    }
    private TextView makeText(String value,int size,int color,boolean bold){
        TextView tv=new TextView(this);
        tv.setText(value);tv.setTextSize(size);tv.setTextColor(color);
        if(bold)tv.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        return tv;
    }
    private GradientDrawable gradient(int[] colors,int radius){
        GradientDrawable shape=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);
        shape.setCornerRadius(dp(radius));return shape;
    }
    private GradientDrawable strokeBackground(int color,int border,int radius){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1),border);
        return shape;
    }
    private LinearLayout panel(LinearLayout root,int marginTop){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14),dp(14),dp(14),dp(15));
        GradientDrawable shape=gradient(new int[]{0xff13223D,0xff0D1A31,0xff131C39},21);
        shape.setStroke(dp(1),0xff2B456C);
        card.setBackground(shape);
        root.addView(card,layout(-1,-2,marginTop));
        return card;
    }
    private TextView sectionHead(String title){
        return makeText(title,16,pearl,true);
    }
    private Button controlButton(String caption,boolean selected){
        Button button=new Button(this);
        button.setAllCaps(false);button.setText(caption);
        button.setGravity(Gravity.CENTER);button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setPadding(dp(4),0,dp(4),0);
        updateControl(button,selected);
        return button;
    }
    private void updateControl(Button button,boolean selected){
        if(button==null)return;
        if(selected){
            button.setTextColor(pearl);
            GradientDrawable selectedBg=gradient(new int[]{0xff07BCE0,0xff126FF9,0xff3948D6},13);
            selectedBg.setStroke(dp(1),0xff45D7FF);
            button.setBackground(selectedBg);
            button.setElevation(dp(3));
        }else{
            button.setTextColor(0xffC5D3EA);
            button.setBackground(strokeBackground(0xff1C2D4B,0xff324866,13));
            button.setElevation(0);
        }
    }
    private void syncVisualStates(){
        if(mainMotorButton!=null){
            mainMotorButton.setRunning(motorUiRunning);
            mainMotorButton.setAlpha(ready?1f:0.55f);
            mainMotorButton.setContentDescription(motorUiRunning?"STOP — ძრავის გაჩერება":"START — ძრავის ჩართვა");
        }
        if(motorChip!=null){
            String label=!ready?"●  გათიშულია":motorUiRunning?"●  მუშაობს":"●  გაჩერებულია";
            motorChip.setText(label);
            motorChip.setTextColor(!ready?0xff8FA1B9:motorUiRunning?0xff52F2DB:0xff7FCBFF);
        }
        if(connectionBadge!=null){
            connectionBadge.setText(ready?"●  ონლაინ":"●  OFFLINE");
            connectionBadge.setTextColor(ready?0xff4EF1C8:0xff9BB0CC);
            connectionBadge.setBackground(strokeBackground(ready?0xff0B302D:0xff10223A,
                    ready?0xff0C977F:0xff294560,28));
        }
        if(speedNumber!=null)speedNumber.setText(String.valueOf(selectedSpeed));
        updateControl(directionAButton,selectedDirection==0);
        updateControl(directionBButton,selectedDirection==1);
        for(int i=0;i<timedPresetButtons.size();i++)
            updateControl(timedPresetButtons.get(i),selectedTimer==durations[i]&&motorUiRunning);
        updateControl(customTimeButton,selectedTimer>0&&selectedTimer!=5&&selectedTimer!=10&&selectedTimer!=15&&selectedTimer!=20&&motorUiRunning);
        updateControl(colorsButton,selectedLightMode==0);
        updateControl(cctButton,selectedLightMode==1);
        updateControl(loopButton,selectedLightMode==2);
        updateControl(ledOnButton,ledUiOn);
        updateControl(ledOffButton,!ledUiOn);
    }
    private void updateMotorState(boolean running){
        motorUiRunning=running;
        syncVisualStates();
    }
    private void toggleMotor(){
        if(!ready){toast("ჯერ დაუკავშირდი 360Tok-ს");return;}
        if(motorUiRunning)stopManual();
        else startManual();
    }
    private void chooseDirection(int direction){
        if(!ready){toast("ჯერ დაუკავშირდი 360Tok-ს");return;}
        selectedDirection=direction;
        sendPacket(3,direction,"DIRECTION "+direction);
        syncVisualStates();
    }
    private void applySpeed(int value){
        if(!ready){toast("ჯერ დაუკავშირდი 360Tok-ს");return;}
        selectedSpeed=value;
        sendPacket(2,value,"SPEED "+value);
        syncVisualStates();
    }
    private void chooseLed(boolean enabled){
        if(!ready){toast("ჯერ დაუკავშირდი 360Tok-ს");return;}
        ledUiOn=enabled;
        sendPacket(6,enabled?1:0,"LED");
        syncVisualStates();
    }
    private void chooseLightingMode(int mode){
        if(!ready){toast("ჯერ დაუკავშირდი 360Tok-ს");return;}
        selectedLightMode=mode;
        sendPacket(7,mode,"LIGHT MODE");
        syncVisualStates();
    }
    private void clearRunHandlers(){
        if(pendingStart!=null){handler.removeCallbacks(pendingStart);pendingStart=null;}
        if(autoUiStop!=null){handler.removeCallbacks(autoUiStop);autoUiStop=null;}
    }
    private void startManual(){
        if(!ready)return;
        clearRunHandlers();
        selectedTimer=0;
        updateMotorState(true);
        sendTimedSequence(0);
    }
    private void startTimed(int seconds){
        if(!ready){toast("ჯერ დაუკავშირდი 360Tok-ს");return;}
        clearRunHandlers();
        selectedTimer=seconds;
        selectedDuration=seconds;
        updateMotorState(true);
        sendTimedSequence(seconds);
        // Display-only countdown: controller itself stops at the selected time.
        autoUiStop=()->{
            autoUiStop=null;
            selectedTimer=0;
            updateMotorState(false);
        };
        handler.postDelayed(autoUiStop,seconds*1000L+650L);
    }
    private void sendTimedSequence(int seconds){
        sendPacket(4,seconds,seconds==0?"DURATION FOREVER":"DURATION "+seconds);
        pendingStart=()->{
            pendingStart=null;
            sendPacket(1,1,seconds==0?"START MANUAL":"START TIMED");
        };
        handler.postDelayed(pendingStart,250);
    }
    private void stopManual(){
        if(!ready)return;
        clearRunHandlers();
        selectedTimer=0;
        updateMotorState(false);
        sendPacket(1,0,"STOP");
    }
    private void durationDialog(){
        EditText e=new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(selectedDuration));
        new AlertDialog.Builder(this).setTitle("სხვა დრო (წამი)")
            .setView(e).setNegativeButton("გაუქმება",null)
            .setPositiveButton("გაშვება",(d,w)->{
                try{
                    int seconds=Integer.parseInt(e.getText().toString().trim());
                    if(seconds<1||seconds>60){toast("შეიყვანე 1–60 წამი");return;}
                    startTimed(seconds);
                }catch(Exception ex){toast("შეიყვანე სწორი დრო");}
            }).show();
    }
    private final class MotorRingButton extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean running;
        MotorRingButton(){
            super(MainActivity.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            setClickable(true);setFocusable(true);
        }
        void setRunning(boolean value){running=value;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float cx=getWidth()/2f,cy=getHeight()/2f;
            float radius=Math.min(getWidth(),getHeight())*0.374f;
            int key=running?0xffFE307C:0xff00BEFF;
            int high=running?0xffFE5A9C:0xff57D9FF;
            paint.reset();paint.setAntiAlias(true);
            paint.setShader(new RadialGradient(cx,cy,radius*1.37f,
                new int[]{running?0x66EA1E66:0x4622A6F3,0x22227BDB,0x00111B30},
                new float[]{0f,0.67f,1f},Shader.TileMode.CLAMP));
            canvas.drawCircle(cx,cy,radius*1.37f,paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(running?0xffE32F77:0xff126DD8);
            paint.setStrokeWidth(dp(2));
            canvas.drawCircle(cx,cy,radius+dp(9),paint);
            paint.setColor(key);
            paint.setStrokeWidth(dp(3));
            paint.setShadowLayer(dp(10),0,0,key);
            canvas.drawCircle(cx,cy,radius+dp(4),paint);
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx-radius*0.2f,cy-radius*0.47f,radius*1.55f,
                running?new int[]{0xffD92B6A,0xff8A174C,0xff210F31}:
                        new int[]{0xff16385D,0xff0A1C3B,0xff07132A},
                null,Shader.TileMode.CLAMP));
            canvas.drawCircle(cx,cy,radius-1,paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(high);paint.setStrokeWidth(dp(2));
            canvas.drawCircle(cx,cy,radius-dp(5),paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(running?0xffFFFFFF:0xff26CEFF);
            paint.setShadowLayer(dp(9),0,0,running?0x88FC6CA5:0x8800CFFF);
            if(running){
                canvas.drawRoundRect(cx-dp(11),cy-dp(32),cx+dp(11),cy-dp(10),dp(3),dp(3),paint);
            }else{
                Path triangle=new Path();
                triangle.moveTo(cx-dp(9),cy-dp(33));
                triangle.lineTo(cx+dp(17),cy-dp(21));
                triangle.lineTo(cx-dp(9),cy-dp(9));
                triangle.close();canvas.drawPath(triangle,paint);
            }
            paint.clearShadowLayer();
            paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            paint.setTextSize(dp(20));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(pearl);
            canvas.drawText(running?"STOP":"START",cx,cy+dp(30),paint);
        }
    }

    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void setConnection(String status) { runOnUiThread(() -> { if(connectionView!=null)connectionView.setText(status); syncVisualStates(); }); }
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
                setConnection("გათიშულია");
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
                setConnection("დაკავშირებულია");
            }
        }
        @Override public void onDescriptorWrite(BluetoothGatt current,BluetoothGattDescriptor desc,int status) {
            addLog("DESCRIPTOR WRITE="+status);
            ready=(status==BluetoothGatt.GATT_SUCCESS);
            setConnection(ready?"დაკავშირებულია":"კავშირის შეცდომა");
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
        if (!FFE1.equals(uuid)||payload==null)return;
        if(payload.length>=13&&(payload[0]&255)==0x11&&(payload[1]&255)==0x22){
            int remoteMotor=payload[2]&255;
            int remoteSpeed=payload[4]&255;
            motor=remoteMotor;speed=remoteSpeed;motorStatusSeen=true;
            runOnUiThread(()->{
                if(remoteMotor==0||remoteMotor==1){
                    if(remoteMotor==0){
                        selectedTimer=0;
                        if(autoUiStop!=null){handler.removeCallbacks(autoUiStop);autoUiStop=null;}
                    }
                    updateMotorState(remoteMotor==1);
                }
                if(remoteSpeed>=1&&remoteSpeed<=6){
                    selectedSpeed=remoteSpeed;
                    syncVisualStates();
                }
            });
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
        clearRunHandlers();
        stopScan();
        ready=false;commandCharacteristic=null;motorStatusSeen=false;
        if(gatt!=null){try{gatt.disconnect();gatt.close();}catch(Exception ignored){}gatt=null;}
        runOnUiThread(()->syncVisualStates());
    }
    @Override protected void onDestroy() {disconnect();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
