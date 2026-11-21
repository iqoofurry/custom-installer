package com.iqoofurry.sheetinstaller;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Управление системным установщиком с проверкой инварианта PackageManagerService.
 *
 * На Android 14+ PMS на каждой загрузке выполняет getRequiredInstallerLPr():
 *   queryIntentActivitiesInternal(ACTION_INSTALL_PACKAGE + CATEGORY_DEFAULT + apk-mime,
 *       MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, USER_SYSTEM)
 * и требует ровно один результат. 0 или 2+ => RuntimeException => SystemServer падает.
 *
 * Поэтому любое изменение проверяется тем же запросом и откатывается, если
 * количество системных установщиков перестало быть равным единице.
 */
final class InstallerSwitch {
    private static final String APK_MIME = "application/vnd.android.package-archive";

    static final class Result {
        final boolean ok;
        final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private InstallerSwitch() {
    }

    static List<String> systemInstallers(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setDataAndType(Uri.parse("content://com.example/foo.apk"), APK_MIME);

        Set<String> packages = new LinkedHashSet<>();
        try {
            // MATCH_SYSTEM_ONLY из обычного приложения на этой прошивке даёт 0 результатов,
            // поэтому запрашиваем без флагов и отбираем системные вручную.
            List<ResolveInfo> matches = pm.queryIntentActivities(intent, 0);
            if (matches != null) {
                for (ResolveInfo info : matches) {
                    if (info.activityInfo == null || info.activityInfo.applicationInfo == null) {
                        continue;
                    }
                    int appFlags = info.activityInfo.applicationInfo.flags;
                    boolean system = (appFlags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM
                            | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                    if (system && info.activityInfo.packageName != null) {
                        packages.add(info.activityInfo.packageName);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<>(packages);
    }

    /**
     * Сессионный режим: убирает стоковый установщик для user 0.
     *
     * На этой прошивке (vivo CN, temp root) состояние installed=false не переживает
     * перезагрузку — система поднимает стоковый пакет обратно. Поэтому удаление
     * безопасно ровно до ребута и не является постоянным.
     */
    static Result enableSession(Context context) {
        if (!RootShell.available()) {
            return new Result(false, "Нет root-доступа");
        }

        List<String> before = systemInstallers(context);
        if (before.isEmpty()) {
            return new Result(true, "Стоковый установщик уже убран. Выбери Custom Installer → «Всегда».");
        }

        StringBuilder log = new StringBuilder();
        for (String pkg : before) {
            RootShell.Result result = RootShell.run(
                    "pm uninstall --user 0 " + RootShell.quote(pkg), 60);
            log.append(pkg).append(": ").append(result.output.trim()).append(' ');
        }

        // Даём PMS применить изменение.
        sleep(1500);
        List<String> after = systemInstallers(context);

        if (!after.isEmpty()) {
            return new Result(false, "Не удалось убрать сток: " + join(after) + ". " + log);
        }

        return new Result(true, "Сток убран до перезагрузки.");
    }

    static Result restoreStock(Context context) {
        if (!RootShell.available()) {
            return new Result(false, "Нет root-доступа");
        }
        String[] known = {
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.miui.packageinstaller",
                "com.samsung.android.packageinstaller",
                "com.oplus.packageinstaller"
        };
        StringBuilder sb = new StringBuilder();
        for (String pkg : known) {
            RootShell.Result r = RootShell.run(
                    "pm install-existing --user 0 " + RootShell.quote(pkg), 60);
            String out = r.output.trim();
            if (!out.isEmpty() && !out.toLowerCase(java.util.Locale.ROOT).contains("unknown")) {
                sb.append(pkg).append(": ").append(out).append(' ');
            }
        }
        int count = systemInstallers(context).size();
        if (count == 1) {
            return new Result(true, "Стоковый установщик возвращён.");
        }
        return new Result(false, "Установщиков сейчас: " + count
                + ". " + sb.toString().trim());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }
}