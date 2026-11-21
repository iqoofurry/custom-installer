package com.iqoofurry.sheetinstaller;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

final class Ui {
    /** Пресеты акцента: ключ → цвет. Monet — отдельный ключ "dynamic". */
    static final String[][] ACCENT_PRESETS = {
            {"violet", "#6750A4"},
            {"blue", "#0B57D0"},
            {"green", "#2E7D32"},
            {"red", "#B3261E"},
            {"mono", "#757575"},
    };

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    /** Скругление только сверху — для нижней шторки. */
    static GradientDrawable topRounded(Context context, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        float r = dp(context, radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
        return d;
    }

    static Drawable rippleRounded(Context context, int color, float radiusDp) {
        return new RippleDrawable(
                ColorStateList.valueOf(context.getColor(R.color.md_ripple)),
                rounded(context, color, radiusDp),
                rounded(context, Color.WHITE, radiusDp));
    }

    static boolean isDark(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    static int primary(Context context) {
        String accent = Prefs.accent(context);
        if (!Prefs.ACCENT_DYNAMIC.equals(accent)) {
            for (String[] preset : ACCENT_PRESETS) {
                if (preset[0].equals(accent)) {
                    return Color.parseColor(preset[1]);
                }
            }
        }
        try {
            return context.getColor(android.R.color.system_accent1_500);
        } catch (Throwable ignored) {
            return context.getColor(R.color.md_primary);
        }
    }

    static int presetColor(String key) {
        for (String[] preset : ACCENT_PRESETS) {
            if (preset[0].equals(key)) {
                return Color.parseColor(preset[1]);
            }
        }
        return Color.GRAY;
    }

    static int onPrimary(int primary) {
        return contrast(primary);
    }

    /** Тёмная поверхность: OLED-режим делает её чисто чёрной. */
    static int surface(Context context) {
        if (Prefs.oled(context) && isDark(context)) {
            return Color.BLACK;
        }
        return context.getColor(R.color.md_surface);
    }

    /** Фон карточек и строк. */
    static int container(Context context) {
        if (Prefs.oled(context) && isDark(context)) {
            return 0xFF0B0B0B;
        }
        return context.getColor(R.color.md_surface_container_high);
    }

    /** Фон чипов внутри карточек. */
    static int containerHighest(Context context) {
        if (Prefs.oled(context) && isDark(context)) {
            return 0xFF1C1C1C;
        }
        return context.getColor(R.color.md_surface_container_highest);
    }

    static int cardRadius(Context context) {
        return Prefs.radius(context);
    }

    /** Крупные элементы (шторка, кнопки) скругляются вдвое сильнее, но не больше 28dp. */
    static int largeRadius(Context context) {
        return Math.min(28, Prefs.radius(context) * 2);
    }

    static int primaryContainer(Context context) {
        float ratio = isDark(context) ? 0.30f : 0.80f;
        return blend(primary(context), surface(context), ratio);
    }

    static int onPrimaryContainer(Context context) {
        return contrast(primaryContainer(context));
    }

    static int blend(int a, int b, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        int ar = Math.round(Color.red(a) * (1f - r) + Color.red(b) * r);
        int ag = Math.round(Color.green(a) * (1f - r) + Color.green(b) * r);
        int ab = Math.round(Color.blue(a) * (1f - r) + Color.blue(b) * r);
        return Color.rgb(ar, ag, ab);
    }

    static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(255f * alpha)));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    static int contrast(int color) {
        return Color.luminance(color) > 0.48f ? Color.BLACK : Color.WHITE;
    }

    static GradientDrawable pillChip(Context c, int bgColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(bgColor);
        d.setCornerRadius(dp(c, 999f));
        return d;
    }

    static Drawable filledButton(Context c) {
        int p = primary(c);
        int radius = largeRadius(c);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(contrast(p), 0.18f)),
                rounded(c, p, radius), rounded(c, Color.WHITE, radius));
    }

    static Drawable tonalButton(Context c) {
        int pc = primaryContainer(c);
        int radius = largeRadius(c);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(contrast(pc), 0.16f)),
                rounded(c, pc, radius), rounded(c, Color.WHITE, radius));
    }

    static Drawable mainBackground(Context c) {
        return rounded(c, surface(c), 0f);
    }

    /**
     * Применяет акцент, OLED, скругления и фоны к текущему экрану.
     * Вызывается после setContentView.
     */
    static void applyDynamicTheme(Activity activity) {
        applySystemBars(activity);
        int accent = primary(activity);
        View main = activity.findViewById(R.id.root_main);
        if (main != null) {
            main.setBackground(rounded(activity, surface(activity), 0f));
            applyMain(activity, accent);
        }
        if (activity.findViewById(R.id.root_sheet) != null) {
            applySheet(activity, accent);
        }
        if (activity.findViewById(R.id.root_settings) != null) {
            applySettings(activity);
        }
        tintPrimaryIcons(activity.getWindow().getDecorView(), activity, accent);
        tintProgress(activity, R.id.progress_bar, accent);
        tintImage(activity, R.id.perm_arrow,
                activity.getColor(R.color.md_on_surface_variant));
    }

    private static void applySystemBars(Activity a) {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        WindowInsetsController controller = a.getWindow().getInsetsController();
        if (controller == null) {
            return;
        }
        int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        controller.setSystemBarsAppearance(isDark(a) ? 0 : mask, mask);
    }

    private static void applyMain(Activity a, int accent) {
        style(rounded(a, primaryContainer(a), 999f), R.id.hero_icon, a);
        style(rounded(a, container(a), cardRadius(a)), R.id.status_card, a);
        style(rounded(a, container(a), cardRadius(a)), R.id.how_card, a);
        style(rounded(a, primaryContainer(a), 999f), R.id.step_1, a);
        style(rounded(a, primaryContainer(a), 999f), R.id.step_2, a);
        style(rounded(a, primaryContainer(a), 999f), R.id.step_3, a);
        style(filledButton(a), R.id.btn_choose, a);
        style(tonalButton(a), R.id.btn_check_root, a);
        style(rippleRounded(a, containerHighest(a), 999f), R.id.btn_settings, a);

        View choose = a.findViewById(R.id.btn_choose);
        if (choose instanceof Button) {
            ((Button) choose).setTextColor(onPrimary(accent));
        }
        View check = a.findViewById(R.id.btn_check_root);
        if (check instanceof Button) {
            ((Button) check).setTextColor(onPrimaryContainer(a));
        }
    }

    private static void applySheet(Activity a, int accent) {
        View sheet = a.findViewById(R.id.sheet);
        if (sheet != null) {
            sheet.setBackground(topRounded(a, surface(a), largeRadius(a)));
        }
        int card = cardRadius(a);
        style(rounded(a, container(a), card), R.id.app_card, a);
        style(rounded(a, surface(a), card), R.id.app_icon_container, a);
        style(rounded(a, container(a), card), R.id.info_card, a);
        style(rounded(a, container(a), card), R.id.progress_box, a);
        style(rippleRounded(a, container(a), card), R.id.perm_header, a);
        style(rounded(a, a.getColor(R.color.md_error_container), card),
                R.id.text_warning_container, a);

        style(filledButton(a), R.id.btn_install, a);
        style(tonalButton(a), R.id.btn_cancel, a);
        View count = a.findViewById(R.id.perm_count);
        if (count instanceof TextView) {
            ((TextView) count).setBackground(Ui.pillChip(a, containerHighest(a)));
            ((TextView) count).setTextColor(a.getColor(R.color.md_on_surface));
        }
        View install = a.findViewById(R.id.btn_install);
        if (install instanceof Button) {
            ((Button) install).setTextColor(onPrimary(accent));
        }
        View cancel = a.findViewById(R.id.btn_cancel);
        if (cancel instanceof Button) {
            ((Button) cancel).setTextColor(onPrimaryContainer(a));
        }
    }

    private static void applySettings(Activity a) {
        View root = a.findViewById(R.id.root_settings);
        if (root != null) {
            root.setBackground(rounded(a, surface(a), 0f));
        }
        int card = cardRadius(a);
        style(rounded(a, container(a), card), R.id.settings_card, a);
        style(rounded(a, container(a), card), R.id.delete_card, a);
        style(rippleRounded(a, containerHighest(a), 999f), R.id.btn_back, a);
    }

    private static void style(Drawable background, int id, Activity a) {
        View v = a.findViewById(id);
        if (v != null) {
            v.setBackground(background);
        }
    }

    /** Красит иконки, у которых в разметке стоит tint="@color/md_primary". */
    private static void tintPrimaryIcons(View view, Activity a, int accent) {
        if (view instanceof ImageView) {
            ColorStateList tint = ((ImageView) view).getImageTintList();
            if (tint != null && tint.getDefaultColor() == a.getColor(R.color.md_primary)) {
                ((ImageView) view).setImageTintList(ColorStateList.valueOf(accent));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintPrimaryIcons(group.getChildAt(i), a, accent);
            }
        }
    }

    private static void tintImage(Activity a, int id, int color) {
        View v = a.findViewById(id);
        if (v instanceof ImageView) {
            ((ImageView) v).setImageTintList(ColorStateList.valueOf(color));
        }
    }

    private static void tintProgress(Activity a, int id, int color) {
        View v = a.findViewById(id);
        if (v instanceof ProgressBar) {
            ((ProgressBar) v).setIndeterminateTintList(ColorStateList.valueOf(color));
            ((ProgressBar) v).setProgressTintList(ColorStateList.valueOf(color));
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 0) {
            return "\u2014";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    static int color(Context context, int res) {
        return context.getColor(res);
    }
}