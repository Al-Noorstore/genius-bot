package com.alnoor.autobot;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView tv = new TextView(this);
        tv.setText("Genius Bot install test OK — signing aur app id theek hain. Ab asli APK ka masla size/permission ka tha.");
        tv.setPadding(40,120,40,40);
        tv.setTextSize(16);
        setContentView(tv);
    }
}
