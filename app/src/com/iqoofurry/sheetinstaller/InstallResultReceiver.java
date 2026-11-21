package com.iqoofurry.sheetinstaller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.lang.ref.WeakReference;

public class InstallResultReceiver extends BroadcastReceiver {
    interface Listener {
        void onInstallResult(Intent intent);
    }

    private static WeakReference<Listener> sListener;

    static void setListener(Listener listener) {
        sListener = listener == null ? null : new WeakReference<>(listener);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        WeakReference<Listener> ref = sListener;
        Listener listener = ref == null ? null : ref.get();
        if (listener != null) {
            listener.onInstallResult(intent);
        }
    }
}
