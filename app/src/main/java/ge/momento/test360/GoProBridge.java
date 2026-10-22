package ge.momento.test360;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;

/**
 * HERO9 BLE remote based on the official Open GoPro protocol.
 * Requires Video mode and a usable SD card configured on the camera.
 *
 * GoPro service FEA6 / Command GP-0072 / Response GP-0073.
 * Shutter ON  03 01 01 01, shutter OFF 03 01 01 00.
 * Keep-alive: settings GP-0074 03 5B 01 42 every 3 seconds.
 */
public final class GoProBridge {
    public interface Listener {
        void onCameraStatus(String status, boolean connected);
        void onRecordingState(boolean recording, String status);
    }
    public interface ShutterResult {
        void onResult(boolean success, String detail);
    }

    private static final String TAG="MOMENTO-GoPro";
    private static final UUID SERVICE=UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb");
    private static final UUID COMMAND=UUID.fromString("b5f90072-aa8d-11e3-9046-0002a5d5c51b");
    private static final UUID RESPONSE=UUID.fromString("b5f90073-aa8d-11e3-9046-0002a5d5c51b");
    private static final UUID SETTINGS=UUID.fromString("b5f90074-aa8d-11e3-9046-0002a5d5c51b");
    private static final UUID SETTINGS_RESPONSE=UUID.fromString("b5f90075-aa8d-11e3-9046-0002a5d5c51b");
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Activity activity;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice camera;
    private BluetoothGattCharacteristic commandChar, settingsChar, commandResponseChar, settingsResponseChar;
    private boolean scanning, ready, bondedReceiverInstalled, recording, destroyed, writePending;
    private boolean commandResponseSubscribed, settingsResponseSubscribed;
    private boolean setupStarted;
    private Runnable scanTimeout, keepAlive;
    private final ArrayDeque<Operation> operations=new ArrayDeque<>();
    private final ArrayDeque<ShutterRequest> shutterRequests=new ArrayDeque<>();
    private Operation currentWrite;
    private String discoveredName="GoPro HERO9";

    private static final class Operation {
        final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor;
        final byte[] bytes;
        Operation(BluetoothGattCharacteristic c,byte[] b){characteristic=c;descriptor=null;bytes=b;}
        Operation(BluetoothGattDescriptor d,byte[] b){characteristic=null;descriptor=d;bytes=b;}
    }
    private static final class ShutterRequest {
        final boolean on;
        final ShutterResult callback;
        Runnable watchdog;
        ShutterRequest(boolean on,ShutterResult callback){this.on=on;this.callback=callback;}
    }

    public GoProBridge(Activity activity,Listener listener){
        this.activity=activity;
        this.listener=listener;
        BluetoothManager manager=(BluetoothManager)activity.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
        IntentFilter filter=new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        activity.registerReceiver(bondReceiver,filter);
        bondedReceiverInstalled=true;
        status("გათიშულია",false);
    }
    public boolean isReady(){return ready;}
    public boolean isRecording(){return recording;}
    public String cameraName(){return discoveredName;}
    private void status(String message,boolean connected){
        handler.post(()->{if(!destroyed)listener.onCameraStatus(message,connected);});
        Log.d(TAG,message);
    }
    private boolean allowed(){
        return Build.VERSION.SDK_INT<31 || 
            (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
             activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);
    }
    public void connect(){
        if(destroyed)return;
        if(!allowed()){status("Bluetooth ნებართვა აკლია",false);return;}
        if(adapter==null||!adapter.isEnabled()){status("ჩართე Bluetooth",false);return;}
        disconnect();
        camera=null;
        scanner=adapter.getBluetoothLeScanner();
        if(scanner==null){status("BLE სკანერი მიუწვდომელია",false);return;}
        try {
            scanning=true;
            scanner.startScan(scanCallback);
            status("GoPro-ს ძებნა…",false);
            scanTimeout=()->{
                if(scanning){
                    stopScanning();
                    status("GoPro ვერ მოიძებნა — ჩართე Pairing",false);
                }
            };
            handler.postDelayed(scanTimeout,18000);
        }catch(Exception e){
            scanning=false;
            status("ძებნის შეცდომა: "+e.getMessage(),false);
        }
    }
    private void stopScanning(){
        if(scanTimeout!=null){handler.removeCallbacks(scanTimeout);scanTimeout=null;}
        if(scanning&&scanner!=null){
            try{scanner.stopScan(scanCallback);}catch(Exception ignored){}
        }
        scanning=false;
    }
    private boolean advertisesGopro(ScanResult result){
        if(result.getScanRecord()==null)return false;
        java.util.List<ParcelUuid> services=result.getScanRecord().getServiceUuids();
        if(services!=null)for(ParcelUuid u:services)if(SERVICE.equals(u.getUuid()))return true;
        return false;
    }
    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int callbackType,ScanResult result){
            if(!scanning||destroyed)return;
            BluetoothDevice dev=result.getDevice();
            if(dev==null)return;
            String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();
            if(name==null)try{name=dev.getName();}catch(Exception ignored){}
            boolean supported=(name!=null&&name.toLowerCase(Locale.ROOT).startsWith("gopro"))||advertisesGopro(result);
            if(!supported)return;
            if(name!=null&&!name.trim().isEmpty())discoveredName=name;
            stopScanning();
            camera=dev;
            status("დაკავშირება: "+discoveredName,false);
            connectGatt(dev);
        }
        @Override public void onScanFailed(int errorCode){
            scanning=false;
            status("GoPro სკანირების შეცდომა "+errorCode,false);
        }
    };
    private void connectGatt(BluetoothDevice dev){
        if(destroyed)return;
        try{
            if(gatt!=null){gatt.close();gatt=null;}
            gatt=dev.connectGatt(activity,false,gattCallback,BluetoothDevice.TRANSPORT_LE);
            if(gatt==null)status("GoPro კავშირი ვერ შეიქმნა",false);
        }catch(Exception e){status("GoPro BLE: "+e.getMessage(),false);}
    }
    private final BroadcastReceiver bondReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction()))return;
            BluetoothDevice dev;
            if(Build.VERSION.SDK_INT>=33)
                dev=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class);
            else
                dev=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if(dev==null||camera==null||!dev.getAddress().equals(camera.getAddress()))return;
            int state=intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.BOND_NONE);
            handler.post(()->{
                if(state==BluetoothDevice.BOND_BONDED){
                    status("დაწყვილებულია — ვამზადებთ GoPro-ს",false);
                    if(gatt!=null){setupStarted=false;gatt.discoverServices();}
                    else connectGatt(dev);
                }else if(state==BluetoothDevice.BOND_NONE){
                    status("დაწყვილება არ დასრულდა — სცადე ხელახლა",false);
                }
            });
        }
    };
    private final BluetoothGattCallback gattCallback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt current,int status,int newState){
            handler.post(()->{
                if(destroyed||gatt!=current)return;
                if(status==BluetoothGatt.GATT_SUCCESS&&newState==BluetoothProfile.STATE_CONNECTED){
                    GoProBridge.this.status("GoPro სერვისების შემოწმება…",false);
                    current.discoverServices();
                }else if(newState==BluetoothProfile.STATE_DISCONNECTED||status!=BluetoothGatt.GATT_SUCCESS){
                    ready=false;recording=false;setupStarted=false;
                    failShutters("GoPro გაითიშა");
                    clearQueue();
                    stopAlive();
                    GoProBridge.this.status("GoPro გათიშულია ("+status+")",false);
                    listener.onRecordingState(false,"კავშირი გაწყდა");
                    try{current.close();}catch(Exception ignored){}
                    if(gatt==current)gatt=null;
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt current,int status){
            handler.post(()->{
                if(destroyed||gatt!=current)return;
                if(status!=BluetoothGatt.GATT_SUCCESS){
                    GoProBridge.this.status("GoPro სერვისების შეცდომა "+status,false);return;
                }
                BluetoothGattService service=current.getService(SERVICE);
                if(service==null){GoProBridge.this.status("Open GoPro FEA6 არ ჩანს",false);return;}
                commandChar=service.getCharacteristic(COMMAND);
                settingsChar=service.getCharacteristic(SETTINGS);
                commandResponseChar=service.getCharacteristic(RESPONSE);
                settingsResponseChar=service.getCharacteristic(SETTINGS_RESPONSE);
                if(commandChar==null||commandResponseChar==null){
                    GoProBridge.this.status("GoPro ბრძანებების არხი მიუწვდომელია",false);return;
                }
                if(camera!=null&&camera.getBondState()!=BluetoothDevice.BOND_BONDED){
                    GoProBridge.this.status("დაადასტურე Bluetooth pairing GoPro-ზე",false);
                    try{
                        if(camera.getBondState()==BluetoothDevice.BOND_NONE)camera.createBond();
                    }catch(Exception e){GoProBridge.this.status("Pairing შეცდომა: "+e.getMessage(),false);}
                    return;
                }
                setupSubscriptions();
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt current,BluetoothGattDescriptor desc,int status){
            handler.post(()->{
                if(gatt!=current)return;
                if(status!=BluetoothGatt.GATT_SUCCESS){
                    GoProBridge.this.status("GoPro notify შეცდომა "+status,false);
                    if(RESPONSE.equals(desc.getCharacteristic().getUuid())){ready=false;}
                }else{
                    if(RESPONSE.equals(desc.getCharacteristic().getUuid()))commandResponseSubscribed=true;
                    if(SETTINGS_RESPONSE.equals(desc.getCharacteristic().getUuid()))settingsResponseSubscribed=true;
                }
                writePending=false;currentWrite=null;
                if(operations.isEmpty())finishSetupIfReady();
                processQueue();
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt current,BluetoothGattCharacteristic ch,int status){
            handler.post(()->{
                if(gatt!=current)return;
                if(status!=BluetoothGatt.GATT_SUCCESS){
                    GoProBridge.this.status("GoPro BLE write შეცდომა "+status,ready);
                }
                writePending=false;currentWrite=null;processQueue();
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt current,BluetoothGattCharacteristic ch,byte[] value){
            handler.post(()->acceptNotification(ch.getUuid(),value));
        }
        @Override public void onCharacteristicChanged(BluetoothGatt current,BluetoothGattCharacteristic ch){
            if(Build.VERSION.SDK_INT<33){
                byte[] value=ch.getValue();
                handler.post(()->acceptNotification(ch.getUuid(),value));
            }
        }
    };
    private void setupSubscriptions(){
        if(setupStarted)return;
        setupStarted=true;
        operations.clear();writePending=false;currentWrite=null;
        commandResponseSubscribed=false;settingsResponseSubscribed=false;
        try{
            if(!queueNotify(commandResponseChar)){
                status("GoPro პასუხების გამოწერა ვერ მოხერხდა",false);return;
            }
            if(settingsResponseChar!=null)queueNotify(settingsResponseChar);
            processQueue();
        }catch(Exception e){status("GoPro notify: "+e.getMessage(),false);}
    }
    private boolean queueNotify(BluetoothGattCharacteristic characteristic){
        if(characteristic==null)return false;
        if(!gatt.setCharacteristicNotification(characteristic,true))return false;
        BluetoothGattDescriptor cccd=characteristic.getDescriptor(CCCD);
        if(cccd==null)return false;
        operations.add(new Operation(cccd,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE));
        return true;
    }
    private void finishSetupIfReady(){
        if(commandResponseSubscribed&&!ready){
            ready=true;
            status(discoveredName+" • მზადაა",true);
            if(settingsChar!=null)startAlive();
        }
    }
    private void processQueue(){
        if(destroyed||gatt==null||writePending)return;
        if(operations.isEmpty()){finishSetupIfReady();return;}
        Operation op=operations.poll();
        currentWrite=op;writePending=true;
        try{
            boolean ok;
            if(op.descriptor!=null){
                if(Build.VERSION.SDK_INT>=33)
                    ok=gatt.writeDescriptor(op.descriptor,op.bytes)==BluetoothGatt.GATT_SUCCESS;
                else{
                    op.descriptor.setValue(op.bytes);
                    ok=gatt.writeDescriptor(op.descriptor);
                }
            }else if(Build.VERSION.SDK_INT>=33){
                ok=gatt.writeCharacteristic(op.characteristic,op.bytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothGatt.GATT_SUCCESS;
            }else{
                op.characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                op.characteristic.setValue(op.bytes);
                ok=gatt.writeCharacteristic(op.characteristic);
            }
            if(!ok){
                writePending=false;currentWrite=null;
                status("GoPro BLE ბრძანება ვერ გაიგზავნა",ready);
                handler.postDelayed(this::processQueue,100);
            }
        }catch(Exception e){
            writePending=false;currentWrite=null;
            status("GoPro ბრძანების შეცდომა: "+e.getMessage(),ready);
            handler.postDelayed(this::processQueue,120);
        }
    }
    private void startAlive(){
        stopAlive();
        keepAlive=new Runnable(){
            @Override public void run(){
                if(!ready||destroyed)return;
                if(settingsChar!=null&&operations.size()<3){
                    operations.add(new Operation(settingsChar,new byte[]{0x03,0x5B,0x01,0x42}));
                    processQueue();
                }
                handler.postDelayed(this,3000);
            }
        };
        handler.postDelayed(keepAlive,1000);
    }
    private void stopAlive(){
        if(keepAlive!=null){handler.removeCallbacks(keepAlive);keepAlive=null;}
    }
    public void setShutter(boolean on,ShutterResult callback){
        handler.post(()->{
            if(!ready||gatt==null||commandChar==null){
                if(callback!=null)callback.onResult(false,"GoPro ჯერ არ არის დაკავშირებული");
                return;
            }
            ShutterRequest req=new ShutterRequest(on,callback);
            shutterRequests.add(req);
            operations.add(new Operation(commandChar,new byte[]{0x03,0x01,0x01,(byte)(on?1:0)}));
            req.watchdog=()->{
                if(shutterRequests.remove(req)){
                    if(callback!=null)callback.onResult(false,"GoPro პასუხი არ მოვიდა (5 წმ)");
                    status("GoPro პასუხი ვერ მივიღეთ",ready);
                }
            };
            handler.postDelayed(req.watchdog,5000);
            processQueue();
        });
    }
    private void acceptNotification(UUID characteristic,byte[] data){
        if(destroyed||data==null)return;
        if(RESPONSE.equals(characteristic)&&data.length>=3){
            // Open GoPro: header (length=2), command ID 0x01, status 0=success.
            int commandId=data[1]&0xFF;
            int code=data[2]&0xFF;
            if(commandId==1&&!shutterRequests.isEmpty()){
                ShutterRequest req=shutterRequests.poll();
                if(req.watchdog!=null)handler.removeCallbacks(req.watchdog);
                boolean success=(code==0);
                if(success){
                    recording=req.on;
                    listener.onRecordingState(recording,recording?"REC":"ჩაწერა დასრულდა");
                }else{
                    listener.onRecordingState(recording,"GoPro შეცდომა "+code);
                }
                if(req.callback!=null)req.callback.onResult(success,success?"OK":"GoPro error "+code);
            }
        }
    }
    private void failShutters(String error){
        while(!shutterRequests.isEmpty()){
            ShutterRequest req=shutterRequests.poll();
            if(req.watchdog!=null)handler.removeCallbacks(req.watchdog);
            if(req.callback!=null)req.callback.onResult(false,error);
        }
    }
    private void clearQueue(){
        operations.clear();writePending=false;currentWrite=null;
    }
    public void disconnect(){
        stopScanning();stopAlive();failShutters("GoPro გათიშულია");
        ready=false;recording=false;setupStarted=false;
        clearQueue();
        commandChar=null;commandResponseChar=null;settingsChar=null;settingsResponseChar=null;
        commandResponseSubscribed=false;settingsResponseSubscribed=false;
        if(gatt!=null){try{gatt.disconnect();gatt.close();}catch(Exception ignored){}gatt=null;}
        status("გათიშულია",false);
    }
    public void close(){
        disconnect();
        destroyed=true;
        if(bondedReceiverInstalled){
            try{activity.unregisterReceiver(bondReceiver);}catch(Exception ignored){}
            bondedReceiverInstalled=false;
        }
    }
}
