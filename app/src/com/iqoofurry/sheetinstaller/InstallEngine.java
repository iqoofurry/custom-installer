package com.iqoofurry.sheetinstaller;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.FileInputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class InstallEngine implements InstallResultReceiver.Listener {
    static final String ACTION_INSTALL_RESULT = "com.iqoofurry.sheetinstaller.INSTALL_RESULT";
    static final String EXTRA_SESSION_ID = "session_id";
    private static final String TAG = "CustomInstaller";
    private static final Pattern SESSION_ID = Pattern.compile("\\[(\\d+)\\]");

    interface Callback {
        void onProgress(int percent, boolean indeterminate);

        /** Текущий этап работы, например «Копирование OBB». */
        void onStage(String text);

        void onFinished(boolean success, String friendlyMessage, String rawMessage, String packageName);
    }

    private final Context appContext;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean done;
    private volatile int sessionId = -1;
    private volatile String packageName;
    private volatile AppBundle bundle;
    private volatile String installerPackage;
    private volatile boolean universalAttempt;

    private PackageInstaller installer;
    private PackageInstaller.SessionCallback sessionCallback;

    InstallEngine(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
        InstallResultReceiver.setListener(this);
    }

    void start(AppBundle bundle, String installerPackage) {
        if (bundle == null || bundle.baseInfo == null || bundle.selected.isEmpty()) {
            finish(false, "Не удалось определить APK", null, null);
            return;
        }
        this.bundle = bundle;
        this.installerPackage = installerPackage;
        this.packageName = bundle.baseInfo.packageName;
        this.universalAttempt = false;
        startInternal();
    }

    private List<AppBundle.Part> currentParts() {
        if (universalAttempt && bundle.universal != null) {
            return Collections.singletonList(bundle.universal);
        }
        return bundle.selected;
    }

    private void startInternal() {
        List<AppBundle.Part> parts = currentParts();
        boolean privileged = appContext.checkSelfPermission(
                android.Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED;
        if (privileged) {
            try {
                startPrivileged(parts);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "Privileged install failed, trying root", t);
                if (!RootShell.available()) {
                    finish(false, ErrorText.human(t.toString()), t.toString(), packageName);
                    return;
                }
            }
        }
        startRoot(parts);
    }

    private void startPrivileged(List<AppBundle.Part> parts) throws Exception {
        installer = appContext.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        String installerName = installerPackage != null && !installerPackage.isEmpty()
                ? installerPackage : appContext.getPackageName();
        try {
            params.setInstallerPackageName(installerName);
        } catch (Throwable t) {
            params.setInstallerPackageName(appContext.getPackageName());
        }
        long total = totalSize(parts);
        params.setSize(total);
        try {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        } catch (Throwable ignored) {
        }
        if (bundle.baseInfo.type == ApkInfo.TYPE_DOWNGRADE && !setRequestDowngrade(params)) {
            throw new UnsupportedOperationException("setRequestDowngrade is unavailable");
        }

        sessionId = installer.createSession(params);
        final PackageInstaller.Session session = installer.openSession(sessionId);
        registerSessionCallback();

        Intent resultIntent = new Intent(ACTION_INSTALL_RESULT)
                .setClass(appContext, InstallResultReceiver.class)
                .putExtra(EXTRA_SESSION_ID, sessionId);
        PendingIntent pending = PendingIntent.getBroadcast(appContext, sessionId, resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        callback.onProgress(0, false);
        Thread worker = new Thread(() -> {
            OutputStream out = null;
            try {
                long written = 0;
                int lastPercent = -1;
                for (AppBundle.Part part : parts) {
                    out = session.openWrite(part.installName, 0, part.size);
                    try (FileInputStream in = new FileInputStream(part.file)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            written += read;
                            int percent = total > 0
                                    ? (int) Math.min(90, written * 90 / total) : 0;
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                progress(percent);
                            }
                        }
                    }
                    session.fsync(out);
                    out.close();
                    out = null;
                }
                session.commit(pending.getIntentSender());
                progress(100);
            } catch (Throwable t) {
                Log.w(TAG, "Session write failed", t);
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    session.abandon();
                } catch (Throwable ignored) {
                }
                handleFailure(ErrorText.human(t.toString()), t.toString());
            }
        }, "ci-session-writer");
        worker.start();
    }

    private boolean setRequestDowngrade(PackageInstaller.SessionParams params) {
        try {
            Method method = PackageInstaller.SessionParams.class
                    .getMethod("setRequestDowngrade", boolean.class);
            method.invoke(params, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void registerSessionCallback() {
        sessionCallback = new PackageInstaller.SessionCallback() {
            @Override
            public void onCreated(int id) {
            }

            @Override
            public void onBadgingChanged(int id) {
            }

            @Override
            public void onActiveChanged(int id, boolean active) {
            }

            @Override
            public void onProgressChanged(int id, float progress) {
                if (id == sessionId) {
                    int percent = Math.max(0, Math.min(100, Math.round(progress * 100f)));
                    progress(percent);
                }
            }

            @Override
            public void onFinished(int id, boolean success) {
                if (id != sessionId) {
                    return;
                }
                main.postDelayed(() -> {
                    if (!done) {
                        if (success) {
                            finishSuccess(null);
                        } else if (!tryUniversalFallback("SessionCallback failure", true)) {
                            finish(false, "Установка не удалась", "SessionCallback failure",
                                    packageName);
                        }
                    }
                }, 2500);
            }
        };
        try {
            installer.registerSessionCallback(sessionCallback, main);
        } catch (Throwable t) {
            sessionCallback = null;
        }
    }

    private void startRoot(List<AppBundle.Part> parts) {
        if (!RootShell.available()) {
            finish(false, "Root-доступ недоступен", "su unavailable", packageName);
            return;
        }
        callback.onProgress(0, true);
        Thread worker = new Thread(() -> {
            try {
                if (parts.size() == 1) {
                    installSingleRoot(parts.get(0));
                } else {
                    installMultiRoot(parts);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Root install failed", t);
                handleFailure(ErrorText.human(t.toString()), t.toString());
            }
        }, "ci-root-install");
        worker.start();
    }

    private void installSingleRoot(AppBundle.Part part) {
        String tag = "ci_" + Long.toHexString(System.currentTimeMillis());
        String remote = "/data/local/tmp/" + tag + ".apk";
        String source = part.file.getAbsolutePath();
        StringBuilder command = new StringBuilder();
        command.append("cp ").append(RootShell.quote(source)).append(' ')
                .append(RootShell.quote(remote)).append(" && ");
        command.append("chmod 644 ").append(RootShell.quote(remote)).append(" && ");
        command.append("pm install -r -d");
        if (installerPackage != null && !installerPackage.isEmpty()) {
            command.append(" -i ").append(RootShell.quote(installerPackage));
        }
        command.append(" --user 0 ").append(RootShell.quote(remote));
        command.append("; __ci_rc=$?; rm -f ").append(RootShell.quote(remote))
                .append("; exit $__ci_rc");

        RootShell.Result result = RootShell.run(command.toString(), 300);
        if (done) {
            return;
        }
        String output = result.output == null ? "" : result.output.trim();
        Log.d(TAG, "root pm install exit=" + result.code + " out=" + output);
        if (result.code == RootShell.CODE_TIMEOUT) {
            finish(false, "Истекло время ожидания установки", output, packageName);
        } else if (!failed(result)) {
            finishSuccess(output);
        } else {
            String raw = output.isEmpty() ? ("exit code " + result.code) : output;
            handleFailure(ErrorText.human(raw), raw);
        }
    }

    private void installMultiRoot(List<AppBundle.Part> parts) {
        String tag = "ci_" + Long.toHexString(System.currentTimeMillis());
        String dir = "/data/local/tmp/" + tag;
        String[] remote = new String[parts.size()];
        StringBuilder prep = new StringBuilder();
        prep.append("mkdir -p ").append(RootShell.quote(dir));
        for (int i = 0; i < parts.size(); i++) {
            AppBundle.Part part = parts.get(i);
            remote[i] = dir + "/" + part.installName;
            prep.append(" && cp ").append(RootShell.quote(part.file.getAbsolutePath()))
                    .append(' ').append(RootShell.quote(remote[i]));
            prep.append(" && chmod 644 ").append(RootShell.quote(remote[i]));
        }
        RootShell.Result copy = RootShell.run(prep.toString(), 600);
        if (done) {
            cleanupRemote(dir, null);
            return;
        }
        if (failed(copy)) {
            cleanupRemote(dir, null);
            String raw = copy.output.isEmpty() ? "copy failed" : copy.output;
            handleFailure(ErrorText.human(raw), raw);
            return;
        }
        progress(15);

        long total = totalSize(parts);
        StringBuilder create = new StringBuilder("pm install-create -r -d -S ")
                .append(total).append(" -i ").append(RootShell.quote(installerName()))
                .append(" --user 0");
        RootShell.Result created = RootShell.run(create.toString(), 120);
        if (failed(created)) {
            cleanupRemote(dir, null);
            String raw = created.output.isEmpty() ? "install-create failed" : created.output;
            handleFailure(ErrorText.human(raw), raw);
            return;
        }
        int id = parseSessionId(created.output);
        if (id < 0) {
            cleanupRemote(dir, null);
            handleFailure("Не удалось создать сессию установки", created.output);
            return;
        }

        for (int i = 0; i < parts.size(); i++) {
            AppBundle.Part part = parts.get(i);
            String command = "pm install-write -S " + part.size + " " + id + " "
                    + RootShell.quote(part.installName) + " " + RootShell.quote(remote[i]);
            RootShell.Result write = RootShell.run(command, 300);
            if (done) {
                cleanupRemote(dir, id);
                return;
            }
            if (failed(write)) {
                cleanupRemote(dir, id);
                String raw = write.output.isEmpty() ? "install-write failed" : write.output;
                handleFailure(ErrorText.human(raw), raw);
                return;
            }
            progress(15 + (int) (75L * (i + 1) / parts.size()));
        }

        RootShell.Result commit = RootShell.run("pm install-commit " + id, 300);
        cleanupRemote(dir, failed(commit) ? id : null);
        if (done) {
            return;
        }
        String output = commit.output == null ? "" : commit.output.trim();
        Log.d(TAG, "root split install exit=" + commit.code + " out=" + output);
        if (commit.code == RootShell.CODE_TIMEOUT) {
            finish(false, "Истекло время ожидания установки", output, packageName);
        } else if (!failed(commit)) {
            finishSuccess(output);
        } else {
            String raw = output.isEmpty() ? ("exit code " + commit.code) : output;
            handleFailure(ErrorText.human(raw), raw);
        }
    }

    private void cleanupRemote(String dir, Integer abandonSession) {
        StringBuilder command = new StringBuilder();
        if (abandonSession != null) {
            command.append("pm install-abandon ").append(abandonSession).append(" >/dev/null 2>&1; ");
        }
        command.append("rm -rf ").append(RootShell.quote(dir));
        RootShell.run(command.toString(), 30);
    }

    private String installerName() {
        return installerPackage != null && !installerPackage.isEmpty()
                ? installerPackage : appContext.getPackageName();
    }

    private static int parseSessionId(String output) {
        if (output == null) {
            return -1;
        }
        Matcher matcher = SESSION_ID.matcher(output);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static boolean failed(RootShell.Result result) {
        if (result == null || result.code == RootShell.CODE_TIMEOUT) {
            return true;
        }
        String lower = result.output.toLowerCase(Locale.ROOT);
        return result.code != 0 || lower.contains("failure") || lower.contains("exception")
                || lower.contains("error:");
    }

    private static long totalSize(List<AppBundle.Part> parts) {
        long total = 0;
        for (AppBundle.Part part : parts) {
            total += part.size;
        }
        return total;
    }

    /** Если набор не подошёл устройству, пробуем universal APK один раз. */
    private boolean tryUniversalFallback(String raw, boolean force) {
        if (universalAttempt || bundle == null || bundle.universal == null) {
            return false;
        }
        String upper = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        boolean splitProblem = force
                || upper.contains("MISSING_SPLIT")
                || upper.contains("INCOMPATIBLE")
                || upper.contains("NO_MATCHING_ABIS")
                || upper.contains("SPLIT");
        if (!splitProblem) {
            return false;
        }
        universalAttempt = true;
        packageName = bundle.universal.packageName;
        Log.w(TAG, "Split install failed, retrying universal: " + raw);
        prepareRetry();
        main.post(() -> {
            if (!done) {
                callback.onProgress(0, true);
            }
        });
        startInternal();
        return true;
    }

    private void prepareRetry() {
        if (installer != null && sessionCallback != null) {
            try {
                installer.unregisterSessionCallback(sessionCallback);
            } catch (Throwable ignored) {
            }
        }
        sessionCallback = null;
        sessionId = -1;
    }

    private void handleFailure(String friendly, String raw) {
        if (tryUniversalFallback(raw, false)) {
            return;
        }
        finish(false, friendly, raw, packageName);
    }

    private void progress(int percent) {
        if (done) {
            return;
        }
        main.post(() -> {
            if (!done) {
                callback.onProgress(percent, false);
            }
        });
    }

    @Override
    public void onInstallResult(Intent intent) {
        if (done || intent == null) {
            return;
        }
        int incomingSession = intent.getIntExtra(EXTRA_SESSION_ID, -1);
        if (sessionId != -1 && incomingSession != sessionId) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            finishSuccess(null);
            return;
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    appContext.startActivity(confirm);
                } catch (Throwable ignored) {
                }
            }
            finish(false, "Требуется подтверждение установки", "PENDING_USER_ACTION", packageName);
            return;
        }
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        handleFailure(ErrorText.human(message), message);
    }

    /** Установка удалась: если в наборе есть OBB, копируем их и только потом показываем успех. */
    private void finishSuccess(final String raw) {
        if (bundle == null || bundle.obbs.isEmpty()) {
            finish(true, null, raw, packageName);
            return;
        }
        Thread worker = new Thread(() -> {
            String note;
            try {
                note = copyObbs();
            } catch (Throwable t) {
                Log.w(TAG, "OBB copy failed", t);
                note = "OBB не скопированы: " + ErrorText.human(t.toString());
            }
            if (!done) {
                finish(true, note, raw, packageName);
            }
        }, "ci-obb-copy");
        worker.start();
    }

    /** Копирует OBB из архива прямо в Android/obb через root. Возвращает замечание или null. */
    private String copyObbs() {
        if (!RootShell.available()) {
            return appContext.getString(R.string.obb_no_root);
        }
        if (packageName == null) {
            return appContext.getString(R.string.obb_no_package);
        }
        RootShell.Result uidResult = RootShell.run(
                "stat -c %u " + RootShell.quote("/data/user/0/" + packageName), 20);
        String uid = digits(uidResult.output);
        if (uid == null) {
            uid = resolveAppUid();
        }
        Log.d(TAG, "OBB uid result: code=" + uidResult.code + " out=" + uidResult.output
                + " resolved=" + uid);
        if (uid == null) {
            return appContext.getString(R.string.obb_no_uid);
        }
        if (bundle.archive == null || !bundle.archive.isFile()) {
            return appContext.getString(R.string.obb_no_archive);
        }

        String root = "/storage/emulated/0/Android/obb";
        RootShell.Result gidResult = RootShell.run(
                "stat -c %g " + RootShell.quote(root), 20);
        String gid = digits(gidResult.output);
        if (gid == null) {
            gid = "1079"; // ext_obb_rw
        }
        String dir = root + "/" + packageName;

        long total = 0;
        for (AppBundle.Obb obb : bundle.obbs) {
            total += Math.max(0, obb.size);
        }
        final long obbTotal = total;

        stage(appContext.getString(R.string.stage_obb));
        progress(0);

        ZipFile zip = null;
        long copied = 0;
        try {
            zip = new ZipFile(bundle.archive);
            for (AppBundle.Obb obb : bundle.obbs) {
                if (done) {
                    return null;
                }
                ZipEntry entry = zip.getEntry(obb.entryName);
                if (entry == null) {
                    return "OBB не найден в архиве: " + obb.fileName;
                }
                String target = dir + "/" + obb.fileName;
                String command = "mkdir -p " + RootShell.quote(dir)
                        + " && cat > " + RootShell.quote(target);
                final long offset = copied;
                RootShell.Result result = RootShell.runWithInput(command,
                        zip.getInputStream(entry), 1800, bytes -> {
                            if (obbTotal > 0) {
                                int percent = (int) Math.min(99,
                                        (offset + bytes) * 100 / obbTotal);
                                progress(percent);
                            }
                        });
                if (result.code == RootShell.CODE_TIMEOUT) {
                    return appContext.getString(R.string.obb_timeout);
                }
                if (failed(result)) {
                    return "OBB не скопированы (" + obb.fileName + "): "
                            + firstLine(result.output);
                }
                copied += Math.max(0, obb.size);
            }
        } catch (Throwable t) {
            Log.w(TAG, "OBB stream failed", t);
            return "OBB не скопированы: " + ErrorText.human(t.toString());
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }

        String fix = "chown -R " + RootShell.quote(uid + ":" + gid) + " "
                + RootShell.quote(dir)
                + " && find " + RootShell.quote(dir) + " -type d -exec chmod 2770 {} +"
                + " && find " + RootShell.quote(dir) + " -type f -exec chmod 0660 {} +";
        RootShell.Result fixed = RootShell.run(fix, 180);
        if (failed(fixed)) {
            return "OBB скопированы, но права не выставлены: " + firstLine(fixed.output);
        }
        progress(100);
        return null;
    }

    /** UID установленного приложения: data-каталог, затем pm list packages -U. */
    private String resolveAppUid() {
        String[] paths = {"/data/user/0/" + packageName, "/data/data/" + packageName};
        for (String path : paths) {
            RootShell.Result result = RootShell.run(
                    "stat -c %u " + RootShell.quote(path), 20);
            String uid = digits(result.output);
            if (uid != null) {
                return uid;
            }
        }
        RootShell.Result listed = RootShell.run(
                "pm list packages -U " + RootShell.quote(packageName), 30);
        Matcher matcher = Pattern.compile("uid:(\\d+)").matcher(listed.output);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String digits(String output) {
        if (output == null) {
            return null;
        }
        String first = firstLine(output).trim();
        if (first.isEmpty()) {
            return null;
        }
        for (int i = 0; i < first.length(); i++) {
            if (!Character.isDigit(first.charAt(i))) {
                return null;
            }
        }
        return first;
    }

    private static String firstLine(String output) {
        if (output == null) {
            return "";
        }
        int newline = output.indexOf('\n');
        String line = newline >= 0 ? output.substring(0, newline) : output;
        return line.trim();
    }

    private void stage(String text) {
        if (done) {
            return;
        }
        main.post(() -> {
            if (!done) {
                callback.onStage(text);
            }
        });
    }

    private void finish(boolean success, String friendly, String raw, String pkg) {
        if (done) {
            return;
        }
        done = true;
        cleanup();
        main.post(() -> callback.onFinished(success, friendly, raw, pkg));
    }

    void dispose() {
        done = true;
        cleanup();
    }

    private void cleanup() {
        if (installer != null && sessionCallback != null) {
            try {
                installer.unregisterSessionCallback(sessionCallback);
            } catch (Throwable ignored) {
            }
        }
        sessionCallback = null;
        InstallResultReceiver.setListener(null);
    }
}