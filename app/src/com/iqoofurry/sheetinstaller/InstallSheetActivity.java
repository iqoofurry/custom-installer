package com.iqoofurry.sheetinstaller;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InstallSheetActivity extends Activity implements InstallEngine.Callback {
    private static final String TAG = "CustomInstaller";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String STATE_PATH = "path";
    private static final String STATE_ARCHIVE = "archive";
    private static final String STATE_INSTALLER = "installer";
    private static final String STATE_RESULT_PACKAGE = "result_package";
    private static final String STATE_SUCCESS = "success";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService io;

    private MaxHeightScrollView scrollContent;
    private ImageView appIcon;
    private TextView appLabel;
    private TextView appPackage;
    private TextView appVersion;
    private TextView appBadge;
    private TextView labelBundle;
    private ViewGroup bundleChips;
    private TextView textWarning;
    private View textWarningContainer;
    private TextView statusTitle;
    private TextView statusDetail;
    private ImageView statusIcon;
    private TextView permCount;
    private ImageView permArrow;
    private ProgressBar progressBar;
    private ViewGroup permContainer;
    private View progressBox;
    private View footer;
    private Button btnInstall;
    private Button btnCancel;

    private volatile ApkInfo info;
    private volatile AppBundle bundle;
    private volatile File bundleDir;
    private volatile String currentPath;
    private volatile String originalPath;
    private volatile String installerPackage;
    private volatile String resultPackage;

    private InstallEngine engine;
    private volatile boolean loading;
    private volatile boolean installing;
    private boolean successState;
    private boolean fatalState;

    private boolean dismissing;
    private boolean dragging;
    private float dragStartRawY;
    private VelocityTracker dragVelocity;
    private String stageText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        window.setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_install_sheet);
        Ui.applyDynamicTheme(this);

        io = Executors.newSingleThreadExecutor();
        io.execute(this::cleanStaleCache);
        bindViews();
        animateSheetIn();
        applyInsets();
        applyHeightLimit();
        wireActions();

        if (savedInstanceState != null) {
            installerPackage = savedInstanceState.getString(STATE_INSTALLER);
            resultPackage = savedInstanceState.getString(STATE_RESULT_PACKAGE);
            successState = savedInstanceState.getBoolean(STATE_SUCCESS, false);
            String archive = savedInstanceState.getString(STATE_ARCHIVE);
            if (archive != null) {
                File file = new File(archive);
                if (file.isFile() && file.length() > 0) {
                    loadFile(file);
                    return;
                }
            }
            String path = savedInstanceState.getString(STATE_PATH);
            if (path != null) {
                File file = new File(path);
                if (file.isFile() && file.length() > 0) {
                    currentPath = path;
                    loadFile(file);
                    return;
                }
            }
        }
        handleIntent(getIntent());
    }

    /** Убирает старые временные копии, оставшиеся после прошлых сессий. */
    private void cleanStaleCache() {
        File[] files = getCacheDir().listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 10L * 60L * 1000L;
        for (File file : files) {
            String name = file.getName();
            if ((name.startsWith("incoming_") || name.startsWith("incoming_su_"))
                    && name.endsWith(".apk") && file.lastModified() < cutoff) {
                file.delete();
            } else if (name.startsWith("bundle_") && file.isDirectory()
                    && file.lastModified() < cutoff) {
                BundleExtractor.deleteRecursive(file);
            }
        }
    }

    private void bindViews() {
        scrollContent = findViewById(R.id.scroll_content);
        appIcon = findViewById(R.id.app_icon);
        appLabel = findViewById(R.id.app_label);
        appPackage = findViewById(R.id.app_package);
        appVersion = findViewById(R.id.app_version);
        appBadge = findViewById(R.id.app_badge);
        labelBundle = findViewById(R.id.label_bundle);
        bundleChips = findViewById(R.id.bundle_chips);
        textWarning = findViewById(R.id.text_warning);
        textWarningContainer = findViewById(R.id.text_warning_container);
        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        statusIcon = findViewById(R.id.status_icon);
        permCount = findViewById(R.id.perm_count);
        permArrow = findViewById(R.id.perm_arrow);
        progressBar = findViewById(R.id.progress_bar);
        permContainer = findViewById(R.id.perm_container);
        progressBox = findViewById(R.id.progress_box);
        footer = findViewById(R.id.footer);
        btnInstall = findViewById(R.id.btn_install);
        btnCancel = findViewById(R.id.btn_cancel);
    }

    private void applyInsets() {
        final View sheet = findViewById(R.id.sheet);
        final int left = sheet.getPaddingLeft();
        final int top = sheet.getPaddingTop();
        final int right = sheet.getPaddingRight();
        final int bottom = sheet.getPaddingBottom();
        sheet.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(left + bars.left, top, right + bars.right, bottom + bars.bottom);
            return insets;
        });
    }

    private void applyHeightLimit() {
        int screenHeight;
        WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        screenHeight = bounds.height();
        int maxScroll = (int) (screenHeight * 0.82f) - Ui.dp(this, 190);
        scrollContent.setMaxHeight(Math.max(Ui.dp(this, 200), maxScroll));
    }

    private void wireActions() {
        View root = findViewById(R.id.root_sheet);
        View sheet = findViewById(R.id.sheet);
        root.setOnClickListener(v -> dismissSheet());
        sheet.setOnClickListener(v -> {
        });
        btnCancel.setOnClickListener(v -> dismissSheet());
        btnInstall.setOnClickListener(v -> {
            if (fatalState) {
                dismissSheet();
            } else if (successState) {
                openInstalled();
            } else {
                startInstall();
            }
        });
        findViewById(R.id.perm_header).setOnClickListener(v -> togglePermissions());
        wireDragHandle();
    }

    private float dimAmount() {
        return Prefs.dim(this) / 100f;
    }

    /** Шторка выезжает снизу целиком, без fade; затемнение под ней плавно проявляется. */
    private void animateSheetIn() {
        final View sheet = findViewById(R.id.sheet);
        final View scrim = findViewById(R.id.scrim);
        sheet.setTranslationY(getResources().getDisplayMetrics().heightPixels);
        sheet.post(() -> {
            sheet.animate()
                    .translationY(0f)
                    .setDuration(320)
                    .setInterpolator(AnimationUtils.loadInterpolator(this,
                            android.R.interpolator.fast_out_slow_in))
                    .start();
            scrim.animate()
                    .alpha(dimAmount())
                    .setDuration(320)
                    .setInterpolator(AnimationUtils.loadInterpolator(this,
                            android.R.interpolator.fast_out_slow_in))
                    .start();
        });
    }

    /** Убирает шторку вниз и плавно снимает затемнение, затем закрывает экран. */
    private void dismissSheet() {
        if (dismissing || isFinishing()) {
            return;
        }
        dismissing = true;
        final View sheet = findViewById(R.id.sheet);
        final View scrim = findViewById(R.id.scrim);
        float target = sheet.getHeight() + Ui.dp(this, 32);
        if (target <= sheet.getTranslationY()) {
            target = sheet.getTranslationY() + Ui.dp(this, 160);
        }
        scrim.animate()
                .alpha(0f)
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        sheet.animate()
                .translationY(target)
                .setDuration(240)
                .setInterpolator(new AccelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    if (Build.VERSION.SDK_INT >= 34) {
                        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
                    } else {
                        overridePendingTransition(0, 0);
                    }
                    finish();
                })
                .start();
    }

    /** Тянем за полоску: шторка едет за пальцем, отпустили ниже порога — уезжает вниз. */
    private void wireDragHandle() {
        final View handle = findViewById(R.id.sheet_handle);
        final View sheet = findViewById(R.id.sheet);
        if (handle == null || sheet == null) {
            return;
        }
        handle.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (dismissing) {
                        return true;
                    }
                    dragStartRawY = event.getRawY();
                    dragging = true;
                    sheet.animate().cancel();
                    if (dragVelocity != null) {
                        dragVelocity.recycle();
                    }
                    dragVelocity = VelocityTracker.obtain();
                    dragVelocity.addMovement(event);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) {
                        return false;
                    }
                    if (dragVelocity != null) {
                        dragVelocity.addMovement(event);
                    }
                    float offset = event.getRawY() - dragStartRawY;
                    sheet.setTranslationY(Math.max(0f, offset));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging) {
                        return false;
                    }
                    dragging = false;
                    float velocity = 0f;
                    if (dragVelocity != null) {
                        dragVelocity.addMovement(event);
                        dragVelocity.computeCurrentVelocity(1000);
                        velocity = dragVelocity.getYVelocity();
                        dragVelocity.recycle();
                        dragVelocity = null;
                    }
                    float offset = sheet.getTranslationY();
                    float threshold = Math.max(Ui.dp(this, 72), sheet.getHeight() * 0.2f);
                    boolean flungDown = velocity > 900f;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                            && (offset > threshold || flungDown)) {
                        dismissSheet();
                    } else {
                        sheet.animate()
                                .translationY(0f)
                                .setDuration(180)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                    }
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    @Override
    public void onBackPressed() {
        dismissSheet();
    }

    private void togglePermissions() {
        boolean show = permContainer.getVisibility() != View.VISIBLE;
        ViewGroup root = findViewById(R.id.sheet);
        TransitionSet set = new TransitionSet()
                .addTransition(new ChangeBounds())
                .addTransition(new Fade());
        set.setDuration(220);
        TransitionManager.beginDelayedTransition(root, set);
        permContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        permArrow.animate().rotation(show ? 180f : 0f).setDuration(220).start();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            showFatal(getString(R.string.no_apk));
            return;
        }
        String extraInstaller = intent.getStringExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME);
        if (extraInstaller != null && extraInstaller.matches("[A-Za-z0-9_.]+")) {
            installerPackage = extraInstaller;
        }
        Uri uri = intent.getData();
        if (uri == null) {
            showFatal(getString(R.string.no_apk));
            return;
        }
        if (SelfApkProvider.AUTHORITY.equals(uri.getAuthority())) {
            // Сюда попадаем, когда пользователь выбрал Custom Installer в системном
            // диалоге для собственного APK — это и есть подтверждение выбора.
            Toast.makeText(this, R.string.default_set_ok, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        startLoading(uri);
    }

    private void startLoading(Uri uri) {
        if (loading || installing) {
            return;
        }
        discardBundle();
        loading = true;
        info = null;
        bundle = null;
        bundleDir = null;
        currentPath = null;
        originalPath = null;
        successState = false;
        fatalState = false;
        showLoading();
        io.execute(() -> {
            try {
                File file = resolveSource(uri);
                AppBundle loaded = BundleExtractor.open(this, file);
                String scheme = uri.getScheme();
                String sourcePath = (scheme == null || "file".equalsIgnoreCase(scheme))
                        ? uri.getPath() : null;
                main.post(() -> {
                    loading = false;
                    if (!isFinishing() && !isDestroyed()) {
                        applyLoaded(loaded, sourcePath);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "APK load failed", t);
                main.post(() -> {
                    loading = false;
                    if (!isFinishing() && !isDestroyed()) {
                        showFatal(ErrorText.human(t.getMessage()));
                    }
                });
            }
        });
    }

    private void loadFile(File file) {
        loading = true;
        originalPath = null;
        successState = false;
        fatalState = false;
        showLoading();
        io.execute(() -> {
            try {
                AppBundle loaded = BundleExtractor.open(this, file);
                main.post(() -> {
                    loading = false;
                    if (!isFinishing() && !isDestroyed()) {
                        applyLoaded(loaded, null);
                    }
                });
            } catch (Throwable t) {
                main.post(() -> {
                    loading = false;
                    if (!isFinishing() && !isDestroyed()) {
                        showFatal(ErrorText.human(t.getMessage()));
                    }
                });
            }
        });
    }

    /** Запоминает источник и показывает данные base APK (для бандла — с общим размером). */
    private void applyLoaded(AppBundle loaded, String sourcePath) {
        bundle = loaded;
        bundleDir = loaded.workspace;
        originalPath = sourcePath;
        currentPath = loaded.workspace == null
                ? loaded.base.file.getAbsolutePath() : null;
        bindInfo(loaded.baseInfo);
    }

    private void discardBundle() {
        if (bundleDir != null) {
            BundleExtractor.deleteRecursive(bundleDir);
            bundleDir = null;
        }
        bundle = null;
    }

    private File resolveSource(Uri uri) throws IOException {
        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme)) {
            File destination = new File(getCacheDir(), "incoming_" + System.currentTimeMillis() + ".apk");
            InputStream in = null;
            OutputStream out = null;
            try {
                in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new IOException("Нет доступа к содержимому файла");
                }
                out = new FileOutputStream(destination);
                copyStream(in, out);
                out.flush();
            } catch (SecurityException e) {
                throw new IOException("Провайдер не выдал доступ к APK", e);
            } finally {
                closeQuietly(in);
                closeQuietly(out);
            }
            if (!destination.isFile() || destination.length() == 0) {
                throw new IOException("Получен пустой файл");
            }
            return destination;
        }
        if ("file".equalsIgnoreCase(scheme) || scheme == null) {
            File source = new File(uri.getPath());
            if (source.isFile() && source.canRead()) {
                return source;
            }
            File destination = new File(getCacheDir(), "incoming_su_" + System.currentTimeMillis() + ".apk");
            if (RootShell.copyToCache(source.getAbsolutePath(), destination)) {
                return destination;
            }
            throw new IOException("Нет доступа к file:// — нужен root-доступ");
        }
        File destination = new File(getCacheDir(), "incoming_" + System.currentTimeMillis() + ".apk");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IOException("Неподдерживаемый источник APK");
            }
            out = new FileOutputStream(destination);
            copyStream(in, out);
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        if (destination.length() == 0) {
            throw new IOException("Получен пустой файл");
        }
        return destination;
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void bindInfo(ApkInfo analyzed) {
        beginStateTransition();
        info = analyzed;

        if (analyzed.icon != null) {
            appIcon.setImageTintList(null);
            appIcon.setImageDrawable(analyzed.icon);
        } else {
            appIcon.setImageResource(R.drawable.ic_install);
            appIcon.setImageTintList(ColorStateList.valueOf(getColor(R.color.md_primary)));
        }
        appLabel.setText(analyzed.label);
        appPackage.setText(analyzed.packageName);
        bindVersion(analyzed);
        findViewById(R.id.value_size).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.value_size)).setText(Ui.formatSize(analyzed.size));
        ((TextView) findViewById(R.id.value_sdk)).setText(getString(R.string.value_sdk,
                analyzed.minSdk, analyzed.targetSdk));
        bindSignature(analyzed);
        bindAbis(analyzed);
        bindBundle(analyzed);
        bindObb();

        applyWarnings(analyzed);
        populatePermissions(analyzed);

        progressBox.setVisibility(View.GONE);
        footer.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        btnInstall.setText(R.string.install);
        btnCancel.setText(R.string.cancel);
        btnCancel.setVisibility(View.VISIBLE);
        successState = false;
        fatalState = false;

        boolean blocked = analyzed.type == ApkInfo.TYPE_SIGNATURE_MISMATCH
                || analyzed.minSdk > Build.VERSION.SDK_INT;
        btnInstall.setEnabled(!blocked);
        btnInstall.setAlpha(blocked ? 0.38f : 1f);

        scrollContent.post(() -> scrollContent.scrollTo(0, 0));
    }

    /** Версия: для обновления/понижения показываем «было → стало». */
    private void bindVersion(ApkInfo analyzed) {
        String current = getString(R.string.value_version, analyzed.versionName,
                analyzed.versionCode);
        boolean upgradable = analyzed.installed && analyzed.installedVersionName != null
                && (analyzed.type == ApkInfo.TYPE_UPDATE
                || analyzed.type == ApkInfo.TYPE_DOWNGRADE);
        if (!upgradable) {
            appVersion.setText(current);
            return;
        }
        String previous = getString(R.string.value_version, analyzed.installedVersionName,
                analyzed.installedVersionCode);
        String arrow = " → ";
        SpannableString text = new SpannableString(previous + arrow + current);
        int start = previous.length() + arrow.length();
        int color = analyzed.type == ApkInfo.TYPE_UPDATE
                ? Ui.primary(this) : getColor(R.color.md_error);
        text.setSpan(new ForegroundColorSpan(color), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        appVersion.setText(text);
    }

    /** Статус подписи относительно установленной версии. */
    private void bindSignature(ApkInfo analyzed) {
        TextView value = findViewById(R.id.value_signature);
        if (!analyzed.installed) {
            value.setText(R.string.sig_new);
            value.setTextColor(getColor(R.color.md_on_surface_variant));
        } else if (analyzed.type == ApkInfo.TYPE_SIGNATURE_MISMATCH) {
            value.setText(R.string.sig_mismatch);
            value.setTextColor(getColor(R.color.md_error));
        } else {
            value.setText(R.string.sig_match);
            value.setTextColor(Ui.primary(this));
        }
    }

    /** ABI показываем чипами: все в одну строку за счёт уменьшенного паддинга. */
    private void bindAbis(ApkInfo analyzed) {
        FlowLayout chips = findViewById(R.id.abi_chips);
        chips.removeAllViews();
        if (analyzed.abis == null || analyzed.abis.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.value_abi_none);
            none.setTextAppearance(R.style.TextAppearanceCIValue);
            chips.addView(none);
            return;
        }
        for (String abi : analyzed.abis) {
            int padH = Ui.dp(this, 6); // уменьшенный паддинг
            int padV = Ui.dp(this, 4);
            TextView chip = new TextView(this);
            chip.setText(abi);
            chip.setTextAppearance(R.style.TextAppearanceCILabel);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            chip.setLetterSpacing(0.02f);
            chip.setTextColor(getColor(R.color.md_on_surface));
            chip.setBackground(Ui.pillChip(this, Ui.containerHighest(this)));
            chip.setPadding(padH, padV, padH, padV);
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(Ui.dp(this, 2));
            lp.setMarginEnd(Ui.dp(this, 2));
            lp.bottomMargin = Ui.dp(this, 4);
            chips.addView(chip, lp);
        }
    }

    /** Бейдж формата и состав выбранных частей. */
    private void bindBundle(ApkInfo analyzed) {
        AppBundle current = bundle;
        if (current == null || !current.isMulti()) {
            appBadge.setVisibility(View.GONE);
            labelBundle.setVisibility(View.GONE);
            bundleChips.setVisibility(View.GONE);
            bundleChips.removeAllViews();
            return;
        }
        int count = current.selected.size();
        String parts = getResources().getQuantityString(R.plurals.bundle_parts, count, count);
        appBadge.setText(getString(R.string.bundle_badge, current.formatName(), parts));
        appBadge.setTextColor(Ui.onPrimaryContainer(this));
        appBadge.setBackground(Ui.pillChip(this, Ui.primaryContainer(this)));
        appBadge.setVisibility(View.VISIBLE);

        bundleChips.removeAllViews();
        java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
        int languages = 0;
        for (AppBundle.Part part : current.selected) {
            if (part.kind == AppBundle.KIND_LANGUAGE) {
                languages++;
            } else {
                labels.add(part.label());
            }
        }
        if (languages > 0) {
            labels.add(getResources().getQuantityString(
                    R.plurals.bundle_languages, languages, languages));
        }
        int shown = 0;
        int hidden = 0;
        for (String label : labels) {
            if (shown >= 8) {
                hidden++;
                continue;
            }
            addChip(bundleChips, label);
            shown++;
        }
        if (hidden > 0) {
            addChip(bundleChips, getString(R.string.bundle_more, hidden));
        }
        labelBundle.setVisibility(View.VISIBLE);
        bundleChips.setVisibility(View.VISIBLE);
    }

    /** Строка OBB: сколько файлов и их суммарный размер. */
    private void bindObb() {
        View row = findViewById(R.id.row_obb);
        AppBundle current = bundle;
        if (current == null || current.obbs.isEmpty()) {
            row.setVisibility(View.GONE);
            return;
        }
        int count = current.obbs.size();
        long total = 0;
        for (AppBundle.Obb obb : current.obbs) {
            total += Math.max(0, obb.size);
        }
        String files = getResources().getQuantityString(R.plurals.obb_files, count, count);
        ((TextView) findViewById(R.id.value_obb)).setText(
                getString(R.string.obb_value, files, Ui.formatSize(total)));
        row.setVisibility(View.VISIBLE);
    }

    private void addChip(ViewGroup chips, String text) {
        int padH = Ui.dp(this, 9);
        int padV = Ui.dp(this, 4);
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextAppearance(R.style.TextAppearanceCILabel);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        chip.setLetterSpacing(0.02f);
        chip.setTextColor(getColor(R.color.md_on_surface));
        chip.setBackground(Ui.pillChip(this, Ui.containerHighest(this)));
        chip.setPadding(padH, padV, padH, padV);
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Ui.dp(this, 2));
        lp.setMarginEnd(Ui.dp(this, 2));
        lp.bottomMargin = Ui.dp(this, 4);
        chips.addView(chip, lp);
    }

    private void applyWarnings(ApkInfo analyzed) {
        List<String> warnings = new ArrayList<>();
        if (analyzed.type == ApkInfo.TYPE_SIGNATURE_MISMATCH) {
            warnings.add(getString(R.string.warning_signature));
        }
        if (analyzed.type == ApkInfo.TYPE_DOWNGRADE) {
            warnings.add(getString(R.string.warning_downgrade));
        }
        if (analyzed.minSdk > Build.VERSION.SDK_INT) {
            warnings.add(getString(R.string.warning_sdk, analyzed.minSdk, Build.VERSION.SDK_INT));
        }
        if (analyzed.abiIncompatible && analyzed.abiList != null) {
            warnings.add(getString(R.string.warning_abi, analyzed.abiList));
        }
        if (warnings.isEmpty()) {
            textWarningContainer.setVisibility(View.GONE);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String warning : warnings) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(warning);
            }
            textWarning.setText(sb.toString());
            textWarningContainer.setVisibility(View.VISIBLE);
        }
    }

    private void populatePermissions(ApkInfo analyzed) {
        permContainer.removeAllViews();
        permArrow.setRotation(0f);
        permContainer.setVisibility(View.GONE);
        int count = analyzed.permissions == null ? 0 : analyzed.permissions.length;
        permCount.setText(String.valueOf(count));
        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.permissions_empty);
            empty.setTextAppearance(R.style.TextAppearanceCIBody);
            int pad = Ui.dp(this, 12);
            empty.setPadding(pad, pad, pad, pad);
            permContainer.addView(empty);
            return;
        }
        PackageManager pm = getPackageManager();
        for (String permission : analyzed.permissions) {
            permContainer.addView(permissionRow(pm, permission));
        }
    }

    /** Строка разрешения: короткое имя, полное имя и бейдж «Опасно»/«Обычное». */
    private View permissionRow(PackageManager pm, String permission) {
        boolean dangerous = isDangerousPermission(pm, permission);
        int pad = Ui.dp(this, 12);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.rounded(this, Ui.containerHighest(this), Ui.cardRadius(this)));
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(rowLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(textLp);

        String prefix = "android.permission.";
        String shortName = permission.startsWith(prefix)
                ? permission.substring(prefix.length()) : permission;

        TextView name = new TextView(this);
        name.setText(shortName);
        name.setTextAppearance(R.style.TextAppearanceCILabel);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        name.setTextColor(getColor(R.color.md_on_surface));
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);

        if (!shortName.equals(permission)) {
            TextView full = new TextView(this);
            full.setText(permission);
            full.setTextAppearance(R.style.TextAppearanceCIMono);
            full.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            full.setMaxLines(1);
            full.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            full.setAlpha(0.7f);
            LinearLayout.LayoutParams fullLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            fullLp.topMargin = Ui.dp(this, 2);
            text.addView(full, fullLp);
        }
        row.addView(text);

        TextView badge = new TextView(this);
        badge.setText(dangerous ? R.string.perm_danger : R.string.perm_normal);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        badge.setLetterSpacing(0.04f);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (dangerous) {
            badge.setTextColor(getColor(R.color.md_on_error_container));
            badge.setBackground(Ui.pillChip(this,
                    getColor(R.color.md_error_container)));
        } else {
            badge.setTextColor(getColor(R.color.md_on_surface_variant));
            badge.setBackground(Ui.pillChip(this, Ui.surface(this)));
        }
        int bpH = Ui.dp(this, 8);
        int bpV = Ui.dp(this, 3);
        badge.setPadding(bpH, bpV, bpH, bpV);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.setMarginStart(Ui.dp(this, 10));
        row.addView(badge, badgeLp);

        return row;
    }

    private static final String[] SENSITIVE_PERMISSIONS = {
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.PACKAGE_USAGE_STATS",
    };

    private boolean isDangerousPermission(PackageManager pm, String permission) {
        for (String sensitive : SENSITIVE_PERMISSIONS) {
            if (sensitive.equals(permission)) {
                return true;
            }
        }
        try {
            PermissionInfo info = pm.getPermissionInfo(permission, 0);
            return (info.protectionLevel & PermissionInfo.PROTECTION_DANGEROUS) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Плавная смена состояния шторки: контент/прогресс/кнопки. */
    private void beginStateTransition() {
        View root = findViewById(R.id.root_sheet);
        Fade fade = new Fade();
        fade.setDuration(180);
        fade.excludeTarget(R.id.scrim, true);
        ChangeBounds bounds = new ChangeBounds();
        bounds.setDuration(240);
        TransitionSet set = new TransitionSet()
                .addTransition(fade)
                .addTransition(bounds);
        TransitionManager.beginDelayedTransition((ViewGroup) root, set);
    }

    private void showLoading() {
        beginStateTransition();
        progressBox.setVisibility(View.VISIBLE);
        footer.setVisibility(View.VISIBLE);
        statusTitle.setText(R.string.loading);
        statusDetail.setText(R.string.preparing);
        setStatusIcon(R.drawable.ic_info, R.color.md_primary);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        btnInstall.setText(R.string.install);
        btnInstall.setEnabled(false);
        btnInstall.setAlpha(0.38f);
        btnCancel.setText(R.string.cancel);
        btnCancel.setVisibility(View.VISIBLE);
        textWarningContainer.setVisibility(View.GONE);
    }

    private void startInstall() {
        ApkInfo current = info;
        AppBundle currentBundle = bundle;
        if (current == null || currentBundle == null || installing || loading) {
            return;
        }
        if (current.type == ApkInfo.TYPE_SIGNATURE_MISMATCH
                || current.minSdk > Build.VERSION.SDK_INT) {
            return;
        }
        if (engine != null) {
            engine.dispose();
            engine = null;
        }
        installing = true;
        successState = false;
        fatalState = false;
        stageText = null;
        beginStateTransition();
        progressBox.setVisibility(View.VISIBLE);
        footer.setVisibility(View.GONE);
        statusTitle.setText(R.string.installing);
        statusDetail.setText(R.string.preparing);
        setStatusIcon(R.drawable.ic_info, R.color.md_primary);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);

        engine = new InstallEngine(this, this);
        engine.start(currentBundle, installerPackage);
    }

    @Override
    public void onProgress(int percent, boolean indeterminate) {
        if (!installing) {
            return;
        }
        progressBar.setIndeterminate(indeterminate);
        if (!indeterminate) {
            progressBar.setProgress(percent);
            if (stageText != null) {
                statusDetail.setText(getString(R.string.stage_percent, stageText, percent));
            } else {
                statusDetail.setText(getString(R.string.percent, percent));
            }
        }
    }

    @Override
    public void onStage(String text) {
        if (!installing) {
            return;
        }
        stageText = text;
        if (text != null) {
            statusDetail.setText(text);
        }
    }

    @Override
    public void onFinished(boolean success, String friendlyMessage, String rawMessage,
                           String packageName) {
        installing = false;
        successState = success;
        resultPackage = packageName != null ? packageName
                : (info != null ? info.packageName : null);
        if (engine != null) {
            engine.dispose();
            engine = null;
        }
        if (success) {
            showSuccess(friendlyMessage);
        } else {
            showError(friendlyMessage, rawMessage);
        }
    }

    private void showSuccess(String note) {
        beginStateTransition();
        progressBox.setVisibility(View.VISIBLE);
        statusTitle.setText(R.string.installed);
        statusDetail.setText(note != null ? note : getString(R.string.install_success_detail));
        setStatusIcon(R.drawable.ic_check, R.color.md_primary);
        progressBar.setVisibility(View.GONE);
        footer.setVisibility(View.VISIBLE);
        btnInstall.setText(R.string.open);
        btnInstall.setEnabled(true);
        btnInstall.setAlpha(1f);
        btnCancel.setText(R.string.done);
        btnCancel.setVisibility(View.VISIBLE);
        cleanupSource();
    }

    /** После успеха убираем временную папку и, если включено, исходный архив. */
    private void cleanupSource() {
        final File workspace = bundleDir;
        bundleDir = null;
        final boolean auto = Prefs.autoDelete(this);
        final String copy = currentPath;
        final String archive = bundle != null && bundle.archive != null
                ? bundle.archive.getAbsolutePath() : null;
        final String source = originalPath;
        if (workspace == null && !auto) {
            return;
        }
        new Thread(() -> {
            if (workspace != null) {
                BundleExtractor.deleteRecursive(workspace);
            }
            boolean deleted = false;
            if (archive != null) {
                deleted = deleteCacheCopy(archive) || deleted;
            }
            if (copy != null && !copy.equals(archive)) {
                deleted = deleteCacheCopy(copy) || deleted;
            }
            if (auto && source != null) {
                deleted = deleteSourceFile(source) || deleted;
            }
            if (deleted && auto) {
                main.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.apk_deleted, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "ci-cleanup").start();
    }

    private boolean deleteCacheCopy(String path) {
        if (path == null) {
            return false;
        }
        File file = new File(path);
        if (!file.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath())) {
            return false;
        }
        return file.delete();
    }

    /** Сначала обычное удаление, затем root — для путей, закрытых scoped storage. */
    private boolean deleteSourceFile(String path) {
        try {
            File file = new File(path);
            if (file.isFile() && file.delete()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            RootShell.Result result = RootShell.run("rm -f -- " + RootShell.quote(path), 15);
            return result.code == 0 && !new File(path).exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void showError(String friendlyMessage, String rawMessage) {
        beginStateTransition();
        progressBox.setVisibility(View.VISIBLE);
        statusTitle.setText(R.string.error);
        setStatusIcon(R.drawable.ic_warning, R.color.md_error);
        String message = friendlyMessage == null || friendlyMessage.isEmpty()
                ? getString(R.string.install_result_unknown) : friendlyMessage;
        if (rawMessage != null && !rawMessage.isEmpty() && !message.contains(rawMessage)
                && message.startsWith("Неизвестная")) {
            message = message + "\n\n" + rawMessage;
        }
        statusDetail.setText(message);
        progressBar.setVisibility(View.GONE);
        footer.setVisibility(View.VISIBLE);
        btnInstall.setText(R.string.retry);
        btnInstall.setEnabled(true);
        btnInstall.setAlpha(1f);
        btnCancel.setText(R.string.close);
        btnCancel.setVisibility(View.VISIBLE);
        Log.w(TAG, "Install error: " + friendlyMessage + " raw=" + rawMessage);
    }

    private void showFatal(String message) {
        info = null;
        installing = false;
        loading = false;
        successState = false;
        fatalState = true;
        beginStateTransition();
        progressBox.setVisibility(View.VISIBLE);
        statusTitle.setText(R.string.error);
        setStatusIcon(R.drawable.ic_warning, R.color.md_error);
        statusDetail.setText(message == null ? getString(R.string.install_result_unknown) : message);
        progressBar.setVisibility(View.GONE);
        footer.setVisibility(View.VISIBLE);
        btnInstall.setText(R.string.close);
        btnInstall.setEnabled(true);
        btnInstall.setAlpha(1f);
        btnCancel.setVisibility(View.GONE);
    }

    private void setStatusIcon(int drawable, int colorRes) {
        if (statusIcon == null) {
            return;
        }
        statusIcon.setImageResource(drawable);
        statusIcon.setImageTintList(ColorStateList.valueOf(getColor(colorRes)));
    }

    private void openInstalled() {
        String pkg = resultPackage != null ? resultPackage
                : (info != null ? info.packageName : null);
        if (pkg == null) {
            finish();
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Toast.makeText(this, R.string.no_launcher, Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launch);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.no_launcher, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (installing) {
            Toast.makeText(this, R.string.install_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }
        if (engine != null) {
            engine.dispose();
            engine = null;
        }
        loading = false;
        successState = false;
        fatalState = false;
        resultPackage = null;
        discardBundle();
        handleIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PATH, currentPath);
        outState.putString(STATE_ARCHIVE, bundle != null && bundle.archive != null
                ? bundle.archive.getAbsolutePath() : null);
        outState.putString(STATE_INSTALLER, installerPackage);
        outState.putString(STATE_RESULT_PACKAGE, resultPackage);
        outState.putBoolean(STATE_SUCCESS, successState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && !installing) {
            if (bundleDir != null) {
                BundleExtractor.deleteRecursive(bundleDir);
                bundleDir = null;
            }
            deleteCacheCopy(currentPath);
            if (bundle != null && bundle.archive != null) {
                deleteCacheCopy(bundle.archive.getAbsolutePath());
            }
        }
        if (engine != null) {
            engine.dispose();
            engine = null;
        }
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
    }
}
