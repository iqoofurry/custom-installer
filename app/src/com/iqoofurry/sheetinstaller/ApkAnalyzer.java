package com.iqoofurry.sheetinstaller;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ApkAnalyzer {
    private ApkAnalyzer() {
    }

    static ApkInfo analyze(Context context, File apk) throws IOException {
        if (apk == null || !apk.isFile() || apk.length() == 0) {
            throw new IOException("Файл APK пуст или недоступен");
        }
        PackageManager pm = context.getPackageManager();
        int flags = PackageManager.GET_PERMISSIONS
                | PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo pkg;
        try {
            pkg = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        } catch (Throwable t) {
            throw new IOException("Не удалось разобрать APK: " + t.getMessage(), t);
        }
        if (pkg == null || pkg.applicationInfo == null || pkg.packageName == null) {
            throw new IOException("Не удалось разобрать APK");
        }

        ApplicationInfo ai = pkg.applicationInfo;
        ai.sourceDir = apk.getAbsolutePath();
        ai.publicSourceDir = apk.getAbsolutePath();

        ApkInfo info = new ApkInfo();
        info.file = apk;
        info.packageName = pkg.packageName;
        info.versionName = pkg.versionName == null ? "—" : pkg.versionName;
        info.versionCode = pkg.getLongVersionCode();
        info.minSdk = ai.minSdkVersion;
        info.targetSdk = ai.targetSdkVersion;
        info.size = apk.length();
        info.permissions = pkg.requestedPermissions == null ? new String[0] : pkg.requestedPermissions;

        try {
            CharSequence label = pm.getApplicationLabel(ai);
            info.label = label == null ? pkg.packageName : label.toString();
        } catch (Throwable t) {
            info.label = pkg.packageName;
        }
        try {
            info.icon = pm.getApplicationIcon(ai);
        } catch (Throwable t) {
            info.icon = null;
        }

        info.sha256 = fileSha256(apk);
        info.signerHashes = signerHashes(pkg);
        info.abis = scanAbis(apk);
        if (info.abis.isEmpty()) {
            info.abiList = null;
        } else {
            StringBuilder sb = new StringBuilder();
            for (String abi : info.abis) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(abi);
            }
            info.abiList = sb.toString();
            boolean supported = false;
            for (String abi : info.abis) {
                for (String deviceAbi : Build.SUPPORTED_ABIS) {
                    if (deviceAbi.equals(abi)) {
                        supported = true;
                        break;
                    }
                }
                if (supported) {
                    break;
                }
            }
            info.abiIncompatible = !supported;
        }

        detectInstalled(pm, info);
        return info;
    }

    private static void detectInstalled(PackageManager pm, ApkInfo info) {
        try {
            PackageInfo installed = pm.getPackageInfo(info.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_PERMISSIONS);
            info.installed = true;
            info.installedVersionCode = installed.getLongVersionCode();
            info.installedVersionName = installed.versionName == null ? "—" : installed.versionName;
            if (installed.requestedPermissions != null) {
                info.installedPermissions = new HashSet<>(
                        Arrays.asList(installed.requestedPermissions));
            }
            Set<String> installedSigners = signerHashes(installed);
            boolean sameSignature = !info.signerHashes.isEmpty()
                    && info.signerHashes.equals(installedSigners);
            if (!sameSignature) {
                info.type = ApkInfo.TYPE_SIGNATURE_MISMATCH;
            } else if (info.versionCode < info.installedVersionCode) {
                info.type = ApkInfo.TYPE_DOWNGRADE;
            } else if (info.versionCode > info.installedVersionCode) {
                info.type = ApkInfo.TYPE_UPDATE;
            } else {
                info.type = ApkInfo.TYPE_REINSTALL;
            }
        } catch (PackageManager.NameNotFoundException e) {
            info.installed = false;
            info.installedVersionCode = -1;
            info.type = ApkInfo.TYPE_NEW;
        } catch (Throwable t) {
            info.installed = false;
            info.installedVersionCode = -1;
            info.type = ApkInfo.TYPE_NEW;
        }
    }

    static Set<String> signerHashes(PackageInfo pkg) {
        Set<String> result = new HashSet<>();
        Signature[] signatures = null;
        if (pkg != null && pkg.signingInfo != null) {
            signatures = pkg.signingInfo.getApkContentsSigners();
        } else if (pkg != null) {
            signatures = pkg.signatures;
        }
        if (signatures != null) {
            for (Signature signature : signatures) {
                if (signature != null) {
                    result.add(sha256(signature.toByteArray()));
                }
            }
        }
        return result;
    }

    static Set<String> scanAbis(File apk) {
        Set<String> result = new TreeSet<>();
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    String[] parts = name.split("/");
                    if (parts.length >= 3) {
                        result.add(parts[1]);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
        return result;
    }

    /** SHA-256 всего файла: для XAPK/APKS хэшируется архив, а не распакованный base. */
    static String fileSha256(File file) {
        InputStream input = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input = new FileInputStream(file);
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (Throwable t) {
            return "";
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String hex(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    static String permissionLabel(String permission) {
        if (permission == null) {
            return "";
        }
        int dot = permission.lastIndexOf('.');
        return dot >= 0 ? permission.substring(dot + 1) : permission;
    }

    static List<String> sortedAbis(Set<String> abis) {
        return new ArrayList<>(abis);
    }
}
