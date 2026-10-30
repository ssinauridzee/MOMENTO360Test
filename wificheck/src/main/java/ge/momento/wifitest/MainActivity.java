package ge.momento.wifitest;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.net.wifi.WifiManager;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private LinearLayout root;
    private String report = "";
    private int dp(float v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0,dp(7),0,dp(7));
        root.addView(view);
        return view;
    }
    private void row(String title, String value, boolean success) {
        TextView label = text(title, 16, Color.rgb(75,85,99));
        TextView status = text(value, 21, success ? Color.rgb(20,125,72) : Color.rgb(185,53,53));
        status.setTypeface(null,1);
    }
    private void draw() {
        root.removeAllViews();
        text("Dual Wi-Fi Check", 29, Color.rgb(20,32,53)).setTypeface(null,1);
        text("MOMENTO 360 • GoPro HERO9-ისთვის", 16, Color.rgb(75,85,99));
        text("ტელეფონი: " + Build.MANUFACTURER + " " + Build.MODEL +
             "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
             15, Color.DKGRAY);
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean local = false, multi = false, hotspot = false;
        String localStr, multiStr, hotspotStr;
        try {
            local = Build.VERSION.SDK_INT >= 31 &&
                    wifi != null && wifi.isStaConcurrencyForLocalOnlyConnectionsSupported();
            localStr = Build.VERSION.SDK_INT < 31 ? "Android 12+ აუცილებელია" :
                    (local ? "YES — მხარდაჭერილია" : "NO — მხარდაჭერა არ არის");
        } catch (Exception e) {
            localStr = "ვერ მოწმდება: " + e.getClass().getSimpleName();
        }
        try {
            multi = Build.VERSION.SDK_INT >= 33 &&
                    wifi != null && wifi.isStaConcurrencyForMultiInternetSupported();
            multiStr = Build.VERSION.SDK_INT < 33 ? "Android 13+ აუცილებელია" :
                    (multi ? "YES — მხარდაჭერილია" : "NO — მხარდაჭერა არ არის");
        } catch (Exception e) {
            multiStr = "ვერ მოწმდება: " + e.getClass().getSimpleName();
        }
        try {
            hotspot = Build.VERSION.SDK_INT >= 30 &&
                    wifi != null && wifi.isStaApConcurrencySupported();
            hotspotStr = Build.VERSION.SDK_INT < 30 ? "Android 11+ აუცილებელია" :
                    (hotspot ? "YES — მხარდაჭერილია" : "NO — მხარდაჭერა არ არის");
        } catch (Exception e) {
            hotspotStr = "ვერ მოწმდება: " + e.getClass().getSimpleName();
        }
        text("1. მთავარი ტესტი — 2 Wi-Fi კავშირი",22, Color.rgb(20,32,53)).setTypeface(null,1);
        row("GoPro ლოკალური Wi-Fi + ინტერნეტიანი Wi-Fi (STA/STA Local Only)", localStr, local);
        text(local ?
                "ტელეფონი აცხადებს ამ რეჟიმის მხარდაჭერას. შემდეგ უნდა შევამოწმოთ ორი რეალური ქსელის ერთდროული მუშაობა." :
                "ტელეფონი ამ რეჟიმის მხარდაჭერას არ აცხადებს. MOMENTO-სთვის ავტომატური Wi-Fi მონაცვლეობა ან მობილური ინტერნეტი ჯობს.",
                15, Color.DKGRAY);
        text("2. დამატებითი ტექნიკური ტესტები",22, Color.rgb(20,32,53)).setTypeface(null,1);
        row("2 ინტერნეტიანი Wi-Fi ქსელი (Multi-Internet)",multiStr,multi);
        row("Wi-Fi + Hotspot (STA/AP) — ეს სხვა ფუნქციაა",hotspotStr,hotspot);
        text("შენიშვნა: Multi-Internet და Hotspot შედეგები GoPro-სთვის მთავარი კრიტერიუმი არ არის. ყველაზე მნიშვნელოვანია პირველი, Local Only.",14,Color.DKGRAY);
        report = "Dual Wi-Fi Check v1.0\n" +
                 "Phone: "+Build.MANUFACTURER+" "+Build.MODEL+"\n"+
                 "Android: "+Build.VERSION.RELEASE+" API "+Build.VERSION.SDK_INT+"\n"+
                 "STA/STA Local Only: "+localStr+"\n"+
                 "STA/STA Multi-Internet: "+multiStr+"\n"+
                 "STA/AP Hotspot: "+hotspotStr;
        Button copy = new Button(this);
        copy.setAllCaps(false);
        copy.setText("შედეგის დაკოპირება");
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Dual WiFi Check",report));
            Toast.makeText(this,"შედეგი დაკოპირებულია",Toast.LENGTH_SHORT).show();
        });
        root.addView(copy);
        Button refresh = new Button(this);
        refresh.setAllCaps(false);
        refresh.setText("განახლება");
        refresh.setOnClickListener(v -> draw());
        root.addView(refresh);
        text("შედეგი ეფუძნება Android WifiManager-ის ოფიციალურ API-ს. ეს არის მხარდაჭერის შემოწმება და არა ორი ქსელის რეალური სატესტო დაკავშირება.",13,Color.GRAY);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(26),dp(22),dp(28));
        root.setBackgroundColor(Color.WHITE);
        scroller.addView(root);
        setContentView(scroller);
        draw();
    }
}
