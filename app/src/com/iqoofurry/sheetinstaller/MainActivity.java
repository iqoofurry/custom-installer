package com.iqoofurry.sheetinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_APK = 1001;
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private TextView chipMode;
    private TextView hint;
    private TextView statusError;
    private TextView pillRoot;
    private TextView pillInstall;
    private TextView pillSafe;
    private Button checkRootButton;
    private Button removeStockButton;
    private int installerCount = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        window.setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_main);
        Ui.applyDynamicTheme(this);

        final View root = findViewById(R.id.root_main);
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

        chipMode = findViewById(R.id.chip_mode);
        hint = findViewById(R.id.text_mode_hint);
        statusError = findViewById(R.id.text_status_error);
        pillRoot = findViewById(R.id.pill_mode);
        pillInstall = findViewById(R.id.pill_priv);
        pillSafe = findViewById(R.id.pill_stock);

        Button choose = findViewById(R.id.btn_choose);
        choose.setOnClickListener(v -> pickApk());
        checkRootButton = findViewById(R.id.btn_check_root);
        checkRootButton.setOnClickListener(v -> checkRoot());
        removeStockButton = findViewById(R.id.btn_remove_stock);
        removeStockButton.setOnClickListener(v -> onStockButtonClicked());
        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.applyDynamicTheme(this);
        refreshStatus();
    }

    private void refreshStatus() {
        new Thread(() -> {
            final boolean privileged = checkSelfPermission(
                    android.Manifest.permission.INSTALL_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED;
            final boolean rootAvailable = RootShell.available();
            final int installers = InstallerSwitch.systemInstallers(getApplicationContext()).size();
            runOnUiThread(() -> renderStatus(privileged, rootAvailable, installers));
        }, "ci-status").start();
    }

    private void renderStatus(boolean privileged, boolean rootAvailable, int installers) {
        boolean ready = privileged || rootAvailable;
        chipMode.setText(privileged ? R.string.mode_privileged : R.string.mode_root);
        hint.setText(ready
                ? (privileged ? R.string.mode_hint_privileged : R.string.mode_hint_root)
                : R.string.mode_hint_no_root);

        applyPill(pillRoot,
                rootAvailable ? R.string.pill_yes : R.string.pill_no, rootAvailable);
        applyPill(pillInstall,
                privileged ? R.string.pill_priv : R.string.pill_root, privileged);
        pillSafe.setText(getString(R.string.pill_installers, installers));
        if (installers == 1) {
            pillSafe.setBackground(Ui.pillChip(this, Ui.primaryContainer(this)));
            pillSafe.setTextColor(Ui.onPrimaryContainer(this));
        } else {
            pillSafe.setBackground(Ui.pillChip(this, Ui.color(this, R.color.md_error_container)));
            pillSafe.setTextColor(Ui.color(this, R.color.md_on_error_container));
        }

        installerCount = installers;
        if (removeStockButton != null) {
            boolean removed = installers == 0;
            removeStockButton.setEnabled(rootAvailable);
            removeStockButton.setText(removed ? R.string.restore_stock : R.string.remove_stock);
            removeStockButton.setTextColor(removed
                    ? Ui.primary(this) : getColor(R.color.md_error));
        }
        if (statusError != null) {
            if (!ready) {
                statusError.setText(R.string.mode_hint_no_root);
                statusError.setVisibility(View.VISIBLE);
            } else {
                // В рабочем режиме блок ошибок не нужен: он остаётся только
                // для сообщений о неудачных операциях.
                statusError.setVisibility(View.GONE);
            }
        }
    }

    private void applyPill(TextView pill, int textRes, boolean accent) {
        if (pill == null) {
            return;
        }
        pill.setText(textRes);
        if (accent) {
            pill.setBackground(Ui.pillChip(this, Ui.primaryContainer(this)));
            pill.setTextColor(Ui.onPrimaryContainer(this));
        } else {
            pill.setBackground(Ui.pillChip(this, getColor(R.color.md_surface_container_high)));
            pill.setTextColor(getColor(R.color.md_on_surface_variant));
        }
    }

    /** Одна кнопка: убрать сток, если он есть, иначе вернуть его. */
    private void onStockButtonClicked() {
        if (installerCount == 0) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.restore_stock)
                    .setMessage(R.string.restore_stock_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.restore_stock_ok, (d, w) -> runToggleStock(false))
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_stock)
                .setMessage(R.string.remove_stock_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove_stock_ok, (d, w) -> runToggleStock(true))
                .show();
    }

    private void runToggleStock(boolean remove) {
        removeStockButton.setEnabled(false);
        if (statusError != null) {
            statusError.setVisibility(View.GONE);
        }
        new Thread(() -> {
            final InstallerSwitch.Result result = remove
                    ? InstallerSwitch.enableSession(getApplicationContext())
                    : InstallerSwitch.restoreStock(getApplicationContext());
            runOnUiThread(() -> {
                Toast.makeText(this,
                        result.ok
                                ? getString(remove
                                        ? R.string.remove_stock_done_toast
                                        : R.string.restore_stock_done_toast)
                                : result.message,
                        Toast.LENGTH_LONG).show();
                refreshStatus();
                if (remove && result.ok) {
                    openInstallerChooser();
                }
            });
        }, remove ? "ci-remove-stock" : "ci-restore-stock").start();
    }

    /** Открывает системный диалог выбора приложения для apk-интента. */
    private void openInstallerChooser() {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(SelfApkProvider.SELF_URI, APK_MIME);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_chooser, Toast.LENGTH_LONG).show();
        }
    }

    private void checkRoot() {
        checkRootButton.setEnabled(false);
        new Thread(() -> {
            RootShell.Result result = RootShell.run("id", 8);
            boolean ok = result.code == 0 && result.output.contains("uid=0");
            runOnUiThread(() -> {
                checkRootButton.setEnabled(true);
                String text = ok ? getString(R.string.root_ok)
                        : getString(R.string.root_fail, result.output.trim());
                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                if (statusError != null) {
                    statusError.setText(text);
                    statusError.setVisibility(View.VISIBLE);
                }
                refreshStatus();
            });
        }, "ci-root-check").start();
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                APK_MIME, "application/zip", "application/x-zip-compressed",
                "application/octet-stream"
        });
        try {
            startActivityForResult(intent, REQUEST_PICK_APK);
        } catch (ActivityNotFoundException e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            fallback.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    APK_MIME, "application/zip", "application/x-zip-compressed",
                    "application/octet-stream"
            });
            try {
                startActivityForResult(fallback, REQUEST_PICK_APK);
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_APK || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            Toast.makeText(this, R.string.no_apk, Toast.LENGTH_LONG).show();
            return;
        }
        int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (takeFlags != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
            } catch (Throwable ignored) {
            }
        }
        Intent sheet = new Intent(this, InstallSheetActivity.class);
        sheet.setAction(Intent.ACTION_VIEW);
        sheet.setDataAndType(uri, "*/*");
        sheet.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(sheet);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_access, Toast.LENGTH_LONG).show();
        }
    }
}