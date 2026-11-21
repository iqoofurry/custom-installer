package com.iqoofurry.testapp;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String versionName = "?";
        long versionCode = 0;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = info.versionName;
            versionCode = info.getLongVersionCode();
        } catch (Throwable ignored) {
        }
        TextView view = new TextView(this);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(24f);
        view.setText("Test App\nv" + versionName + " (" + versionCode + ")");
        setContentView(view);
    }
}