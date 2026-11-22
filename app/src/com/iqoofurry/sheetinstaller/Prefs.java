package com.iqoofurry.sheetinstaller;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки внешнего вида и поведения установщика. */
final class Prefs {
    private static final String FILE = "installer_settings";

    static final String ACCENT_DYNAMIC = "dynamic";
    static final int DEFAULT_DIM = 32;
    static final int DEFAULT_RADIUS = 14;

    private Prefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String accent(Context context) {
        return prefs(context).getString("accent", ACCENT_DYNAMIC);
    }

    static void setAccent(Context context, String value) {
        prefs(context).edit().putString("accent", value).apply();
    }

    static int dim(Context context) {
        return prefs(context).getInt("dim", DEFAULT_DIM);
    }

    static void setDim(Context context, int value) {
        prefs(context).edit().putInt("dim", Math.max(0, Math.min(60, value))).apply();
    }

    static int radius(Context context) {
        return prefs(context).getInt("radius", DEFAULT_RADIUS);
    }

    static void setRadius(Context context, int value) {
        prefs(context).edit().putInt("radius", Math.max(8, Math.min(28, value))).apply();
    }

    static boolean oled(Context context) {
        return prefs(context).getBoolean("oled", false);
    }

    static void setOled(Context context, boolean value) {
        prefs(context).edit().putBoolean("oled", value).apply();
    }

    static boolean autoDelete(Context context) {
        return prefs(context).getBoolean("auto_delete", false);
    }

    static void setAutoDelete(Context context, boolean value) {
        prefs(context).edit().putBoolean("auto_delete", value).apply();
    }

    static void reset(Context context) {
        prefs(context).edit().clear().apply();
    }
}