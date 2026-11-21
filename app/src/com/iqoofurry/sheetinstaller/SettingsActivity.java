package com.iqoofurry.sheetinstaller;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class SettingsActivity extends Activity {
    private static final int PICK_BG_REQUEST = 100;

    private FlowLayout accentChips;
    private SeekBar seekRadius;
    private SeekBar seekDim;
    private TextView valueRadius;
    private TextView valueDim;
    private Switch switchOled;
    private Switch switchAutoDelete;
    private Button btnCustomBg;
    private Button btnResetCustomBg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        window.setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_settings);
        Ui.applyDynamicTheme(this);

        final View root = findViewById(R.id.root_settings);
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            return insets;
        });

        accentChips = findViewById(R.id.accent_chips);
        seekRadius = findViewById(R.id.seek_radius);
        seekDim = findViewById(R.id.seek_dim);
        valueRadius = findViewById(R.id.value_radius);
        valueDim = findViewById(R.id.value_dim);
        switchOled = findViewById(R.id.switch_oled);
        switchAutoDelete = findViewById(R.id.switch_auto_delete);
        btnCustomBg = findViewById(R.id.btn_custom_bg);
        btnResetCustomBg = findViewById(R.id.btn_reset_custom_bg);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_reset).setOnClickListener(v -> resetAll());

        btnCustomBg.setOnClickListener(v -> pickCustomBg());
        btnResetCustomBg.setOnClickListener(v -> resetCustomBg());

        setupRadius();
        setupDim();
        setupSwitches();
        buildAccentChips();
        tintControls();
        updateCustomBgButtons();
    }

    private void setupRadius() {
        seekRadius.setMax(20);
        seekRadius.setProgress(Prefs.radius(this) - 8);
        valueRadius.setText(getString(R.string.value_radius, Prefs.radius(this)));
        seekRadius.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int radius = 8 + progress;
                Prefs.setRadius(SettingsActivity.this, radius);
                valueRadius.setText(getString(R.string.value_radius, radius));
                Ui.applyDynamicTheme(SettingsActivity.this);
            }
        });
    }

    private void setupDim() {
        seekDim.setMax(60);
        seekDim.setProgress(Prefs.dim(this));
        valueDim.setText(getString(R.string.value_dim, Prefs.dim(this)));
        seekDim.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Prefs.setDim(SettingsActivity.this, progress);
                valueDim.setText(getString(R.string.value_dim, progress));
            }
        });
    }

    private void setupSwitches() {
        switchOled.setChecked(Prefs.oled(this));
        switchOled.setOnCheckedChangeListener((buttonView, checked) -> {
            Prefs.setOled(this, checked);
            Ui.applyDynamicTheme(this);
            tintControls();
        });
        switchAutoDelete.setChecked(Prefs.autoDelete(this));
        switchAutoDelete.setOnCheckedChangeListener(
                (buttonView, checked) -> Prefs.setAutoDelete(this, checked));
    }

    /** Monet + фиксированные палитры. Выбранный чип залит своим акцентом. */
    private void buildAccentChips() {
        if (accentChips == null) {
            return;
        }
        accentChips.removeAllViews();
        String selected = Prefs.accent(this);

        accentChips.addView(accentChip(Prefs.ACCENT_DYNAMIC, R.string.accent_dynamic,
                Ui.primary(this), selected));

        for (String[] preset : Ui.ACCENT_PRESETS) {
            int label = labelFor(preset[0]);
            accentChips.addView(accentChip(preset[0], label,
                    Color.parseColor(preset[1]), selected));
        }
    }

    private TextView accentChip(String key, int labelRes, int color, String selected) {
        boolean active = key.equals(selected);
        TextView chip = new TextView(this);
        chip.setText(labelRes);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        chip.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 9),
                Ui.dp(this, 14), Ui.dp(this, 9));
        if (active) {
            chip.setBackground(Ui.pillChip(this, color));
            chip.setTextColor(Ui.contrast(color));
        } else {
            chip.setBackground(Ui.pillChip(this, Ui.containerHighest(this)));
            chip.setTextColor(getColor(R.color.md_on_surface));
        }
        chip.setOnClickListener(v -> {
            Prefs.setAccent(this, key);
            buildAccentChips();
            Ui.applyDynamicTheme(this);
            tintControls();
        });
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int gap = Ui.dp(this, 4);
        lp.setMargins(gap, gap, gap, gap);
        chip.setLayoutParams(lp);
        return chip;
    }

    private static int labelFor(String key) {
        switch (key) {
            case "violet":
                return R.string.accent_violet;
            case "blue":
                return R.string.accent_blue;
            case "green":
                return R.string.accent_green;
            case "red":
                return R.string.accent_red;
            default:
                return R.string.accent_mono;
        }
    }

    private void tintControls() {
        int accent = Ui.primary(this);
        int track = Ui.containerHighest(this);
        for (SeekBar bar : new SeekBar[]{seekRadius, seekDim}) {
            bar.setProgressTintList(ColorStateList.valueOf(accent));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(track));
            bar.setThumbTintList(ColorStateList.valueOf(accent));
        }
        ColorStateList thumb = new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{accent, getColor(R.color.md_on_surface_variant)});
        ColorStateList bar = new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{Ui.withAlpha(accent, 0.55f),
                        Ui.withAlpha(getColor(R.color.md_on_surface_variant), 0.35f)});
        for (Switch sw : new Switch[]{switchOled, switchAutoDelete}) {
            sw.setThumbTintList(thumb);
            sw.setTrackTintList(bar);
        }
        View back = findViewById(R.id.btn_back);
        if (back instanceof ImageView) {
            ((ImageView) back).setImageTintList(
                    ColorStateList.valueOf(getColor(R.color.md_on_surface_variant)));
        }
        Button reset = findViewById(R.id.btn_reset);
        if (reset != null) {
            reset.setTextColor(accent);
        }
        Button bg = findViewById(R.id.btn_custom_bg);
        if (bg != null) bg.setTextColor(accent);
        Button bgReset = findViewById(R.id.btn_reset_custom_bg);
        if (bgReset != null) bgReset.setTextColor(accent);
    }

    private void pickCustomBg() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_BG_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_BG_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                saveCustomBg(uri);
            }
        }
    }

    private void saveCustomBg(Uri uri) {
        try {
            // Копируем выбранное изображение в внутреннее хранилище
            File bgDir = new File(getFilesDir(), "custom_bg");
            bgDir.mkdirs();
            File bgFile = new File(bgDir, "bg.png");

            InputStream input = getContentResolver().openInputStream(uri);
            if (input == null) {
                Toast.makeText(this, R.string.no_access, Toast.LENGTH_SHORT).show();
                return;
            }
            FileOutputStream output = new FileOutputStream(bgFile);
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.close();
            input.close();

            Prefs.setCustomBg(this, bgFile.getAbsolutePath());
            applyCustomBg();
            Toast.makeText(this, R.string.custom_bg_applied, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.no_access) + ": " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void resetCustomBg() {
        String path = Prefs.customBg(this);
        if (path.isEmpty()) return;
        File bgFile = new File(path);
        if (bgFile.exists()) bgFile.delete();
        Prefs.setCustomBg(this, null);
        applyCustomBg();
        Toast.makeText(this, R.string.custom_bg_reset, Toast.LENGTH_SHORT).show();
    }

    /** Применяет кастомный фон к root-вью, если файл есть, иначе убирает. */
    private void applyCustomBg() {
        String path = Prefs.customBg(this);
        View root = findViewById(R.id.root_settings);
        if (root == null) return;
        if (path.isEmpty() || !new File(path).exists()) {
            root.setBackgroundResource(R.drawable.bg_main_gradient);
        } else {
            Bitmap bm = BitmapFactory.decodeFile(path);
            if (bm != null) {
                root.setBackground(new BitmapDrawable(getResources(), bm));
            } else {
                root.setBackgroundResource(R.drawable.bg_main_gradient);
            }
        }
        updateCustomBgButtons();
    }

    private void updateCustomBgButtons() {
        boolean hasBg = !Prefs.customBg(this).isEmpty();
        if (btnResetCustomBg != null) {
            btnResetCustomBg.setEnabled(hasBg);
            btnResetCustomBg.setAlpha(hasBg ? 1f : 0.4f);
        }
        if (btnCustomBg != null) {
            btnCustomBg.setText(hasBg ? R.string.settings_custom_bg : R.string.set_custom_bg);
        }
    }

    private void resetAll() {
        Prefs.reset(this);
        seekRadius.setProgress(Prefs.radius(this) - 8);
        seekDim.setProgress(Prefs.dim(this));
        valueRadius.setText(getString(R.string.value_radius, Prefs.radius(this)));
        valueDim.setText(getString(R.string.value_dim, Prefs.dim(this)));
        switchOled.setChecked(Prefs.oled(this));
        switchAutoDelete.setChecked(Prefs.autoDelete(this));
        // Сброс кастомного фона
        String oldBg = Prefs.customBg(this);
        if (!oldBg.isEmpty()) {
            File bgFile = new File(oldBg);
            if (bgFile.exists()) bgFile.delete();
            Prefs.setCustomBg(this, null);
        }
        buildAccentChips();
        applyCustomBg();
        Ui.applyDynamicTheme(this);
        tintControls();
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}