package com.iqoofurry.sheetinstaller;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Определяет формат файла и безопасно распаковывает XAPK/APKS в кэш. */
final class BundleExtractor {
    private static final long MAX_UNPACKED = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 600;
    private static final long MAX_MANIFEST = 4L * 1024 * 1024;

    private BundleExtractor() {
    }

    static AppBundle open(Context context, File file) throws IOException {
        if (file == null || !file.isFile() || file.length() == 0) {
            throw new IOException("Файл APK пуст или недоступен");
        }
        boolean hasRootManifest = false;
        boolean hasNestedApk = false;
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if ("androidmanifest.xml".equals(lower)) {
                    hasRootManifest = true;
                }
                if (!entry.isDirectory() && lower.endsWith(".apk")) {
                    hasNestedApk = true;
                }
            }
        } catch (IOException e) {
            throw new IOException("Файл не является APK или ZIP-архивом", e);
        } finally {
            closeQuietly(zip);
        }
        if (hasRootManifest) {
            return single(context, file);
        }
        if (!hasNestedApk) {
            throw new IOException("В архиве не найдено ни одного APK");
        }
        return extract(context, file);
    }

    private static AppBundle single(Context context, File file) throws IOException {
        ApkInfo info = ApkAnalyzer.analyze(context, file);
        AppBundle bundle = new AppBundle();
        bundle.archive = file;
        bundle.format = AppBundle.FORMAT_APK;
        bundle.baseInfo = info;

        AppBundle.Part part = new AppBundle.Part();
        part.file = file;
        part.entryName = file.getName();
        part.installName = "base.apk";
        part.kind = AppBundle.KIND_MASTER;
        part.module = "base";
        part.size = file.length();
        part.packageName = info.packageName;
        part.versionCode = info.versionCode;
        part.signers = info.signerHashes;
        part.permissions = info.permissions;
        part.minSdk = info.minSdk;
        part.targetSdk = info.targetSdk;

        bundle.all.add(part);
        bundle.selected.add(part);
        bundle.base = part;
        bundle.totalSize = part.size;
        return bundle;
    }

    private static AppBundle extract(Context context, File archive) throws IOException {
        AppBundle bundle = new AppBundle();
        bundle.archive = archive;
        bundle.format = formatOf(archive.getName());

        File workspace = new File(context.getCacheDir(),
                "bundle_" + System.currentTimeMillis());
        if (!workspace.mkdirs() && !workspace.isDirectory()) {
            throw new IOException("Не удалось создать временную папку для распаковки");
        }
        bundle.workspace = workspace;

        ZipFile zip = null;
        try {
            zip = new ZipFile(archive);
            ManifestInfo manifest = readManifest(zip);
            Map<String, String> manifestIds = manifest.splitIds;
            List<AppBundle.Obb> obbs = new ArrayList<>();
            long unpacked = 0;
            int count = 0;

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("__macosx/") || lower.startsWith("meta-inf/")) {
                    continue;
                }
                if (lower.endsWith(".obb")) {
                    AppBundle.Obb obb = new AppBundle.Obb();
                    obb.entryName = name;
                    obb.size = Math.max(0, entry.getSize());
                    String target = manifest.obbNames.get(name);
                    if (target == null) {
                        target = manifest.obbNames.get(baseName(name));
                    }
                    obb.fileName = safeObbName(target);
                    if (obb.fileName == null) {
                        obb.fileName = safeObbName(baseName(name));
                    }
                    if (obb.fileName != null) {
                        obbs.add(obb);
                    }
                    continue;
                }
                // Для Android 5+ они не нужны: ставим split-набор.
                if (lower.contains("standalone") || lower.contains("instant")) {
                    continue;
                }
                if (!lower.endsWith(".apk")) {
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw new IOException("В архиве слишком много APK-частей");
                }
                long declared = entry.getSize();
                if (declared > 0 && unpacked + declared > MAX_UNPACKED) {
                    throw new IOException("Архив слишком большой для распаковки");
                }
                File destination = new File(workspace, safeName(name, count));
                unpacked += copyEntry(zip, entry, destination, unpacked);
                String manifestId = manifestIds.get(name);
                if (manifestId == null) {
                    manifestId = manifestIds.get(baseName(name));
                }
                bundle.all.add(buildPart(context, destination, name, manifestId));
            }
            Map<String, AppBundle.Obb> uniqueObbs = new LinkedHashMap<>();
            for (AppBundle.Obb obb : obbs) {
                if (!uniqueObbs.containsKey(obb.fileName)) {
                    uniqueObbs.put(obb.fileName, obb);
                }
            }
            bundle.obbs.addAll(uniqueObbs.values());
        } catch (IOException e) {
            deleteRecursive(workspace);
            throw e;
        } catch (Throwable t) {
            deleteRecursive(workspace);
            throw new IOException("Не удалось распаковать архив: " + t.getMessage(), t);
        } finally {
            closeQuietly(zip);
        }

        if (bundle.all.isEmpty()) {
            deleteRecursive(workspace);
            throw new IOException("В архиве не найдено APK");
        }

        AppBundle.Part base = null;
        for (AppBundle.Part part : bundle.all) {
            if (part.kind == AppBundle.KIND_UNIVERSAL) {
                bundle.universal = part;
            } else if (part.kind == AppBundle.KIND_MASTER && part.splitId == null) {
                if (base == null || "base".equals(part.module)) {
                    base = part;
                }
            }
        }
        if (base == null) {
            base = bundle.universal;
        }
        if (base == null) {
            deleteRecursive(workspace);
            throw new IOException("В архиве нет base APK — установить набор нельзя");
        }
        bundle.base = base;

        validate(bundle, base);

        try {
            ApkInfo info = ApkAnalyzer.analyze(context, base.file);
            bundle.baseInfo = info;
            SplitSelector.select(bundle);
            mergeMetadata(bundle);
        } catch (IOException e) {
            deleteRecursive(workspace);
            throw e;
        } catch (Throwable t) {
            deleteRecursive(workspace);
            throw new IOException(ErrorText.human(t.toString()), t);
        }
        return bundle;
    }

    private static AppBundle.Part buildPart(Context context, File file, String entryName,
                                            String manifestId) throws IOException {
        PackageManager pm = context.getPackageManager();
        int flags = PackageManager.GET_PERMISSIONS
                | PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo pkg = null;
        try {
            pkg = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        } catch (Throwable t) {
            Log.w("CustomInstaller", "Cannot parse " + entryName, t);
        }
        if (pkg == null) {
            // Некоторые split-APK (например, плотностные) штатный парсер не отдаёт,
            // хотя pm install их принимает. Метаданные возьмём из base.
            Log.w("CustomInstaller", "PackageManager returned null for " + entryName);
        }

        AppBundle.Part part = new AppBundle.Part();
        part.file = file;
        part.entryName = entryName;
        part.size = file.length();
        if (pkg != null) {
            part.packageName = pkg.packageName;
            part.versionCode = pkg.getLongVersionCode();
            part.signers = ApkAnalyzer.signerHashes(pkg);
            part.permissions = pkg.requestedPermissions == null
                    ? new String[0] : pkg.requestedPermissions;
            if (pkg.applicationInfo != null) {
                part.minSdk = pkg.applicationInfo.minSdkVersion;
                part.targetSdk = pkg.applicationInfo.targetSdkVersion;
            }
        }

        String split = pkg == null ? null : splitName(pkg);
        boolean universal = isUniversalName(entryName) || "universal".equalsIgnoreCase(split);
        if (universal) {
            part.kind = AppBundle.KIND_UNIVERSAL;
            part.module = "base";
            part.splitId = "universal";
            part.installName = uniqueName(entryName, file);
            return part;
        }

        String derivedConfig = configFromEntry(entryName);
        boolean looksBase = isBaseName(entryName);
        boolean manifestSplitHint = manifestId != null && !manifestId.isEmpty()
                && !"base".equalsIgnoreCase(manifestId);
        boolean filenameSplit = !looksBase
                && (derivedConfig != null || manifestSplitHint);
        boolean manifestSplit = split != null && !split.isEmpty();
        if (!manifestSplit && !filenameSplit) {
            part.kind = AppBundle.KIND_MASTER;
            part.module = "base";
            part.splitId = null;
            part.installName = "base.apk";
            return part;
        }

        String id = manifestId != null && !manifestId.isEmpty() ? manifestId : split;
        if (id == null || id.isEmpty()) {
            id = "config." + derivedConfig;
        }
        part.splitId = id;
        part.module = moduleFromEntry(entryName);
        part.config = configOf(id);
        if (part.config == null || !isKnownConfig(part.config)) {
            part.config = derivedConfig;
        }
        if (part.config == null || "master".equalsIgnoreCase(part.config)
                || "base".equalsIgnoreCase(part.config)
                || "universal".equalsIgnoreCase(part.config)) {
            part.kind = AppBundle.KIND_MASTER;
            if ("base".equalsIgnoreCase(part.config)
                    || "universal".equalsIgnoreCase(part.config)) {
                part.splitId = null;
            }
            part.config = null;
            if (part.splitId == null) {
                part.module = "base";
            }
        } else if (SplitSelector.isAbi(part.config)) {
            part.kind = AppBundle.KIND_ABI;
        } else if (SplitSelector.isDensity(part.config)) {
            part.kind = AppBundle.KIND_DENSITY;
        } else if (SplitSelector.isLanguage(part.config)) {
            part.kind = AppBundle.KIND_LANGUAGE;
        } else {
            part.kind = AppBundle.KIND_OTHER;
        }
        part.installName = uniqueName(entryName, file);
        return part;
    }

    private static boolean isBaseName(String entryName) {
        String name = baseName(entryName).toLowerCase(Locale.ROOT);
        if (name.endsWith(".apk")) {
            name = name.substring(0, name.length() - 4);
        }
        return "base".equals(name) || "base-master".equals(name) || "master".equals(name);
    }

    private static boolean isKnownConfig(String config) {
        return SplitSelector.isAbi(config) || SplitSelector.isDensity(config)
                || SplitSelector.isLanguage(config) || "master".equalsIgnoreCase(config);
    }

    private static void validate(AppBundle bundle, AppBundle.Part base) throws IOException {
        if (base.packageName == null) {
            throw new IOException("Не удалось разобрать base APK");
        }
        for (AppBundle.Part part : bundle.all) {
            if (part.packageName == null) {
                // Метаданные недоступны штатному парсеру — доверяем base.
                part.packageName = base.packageName;
                part.versionCode = base.versionCode;
                part.signers = base.signers;
                part.minSdk = base.minSdk;
                part.targetSdk = base.targetSdk;
                continue;
            }
            if (!base.packageName.equals(part.packageName)) {
                throw new IOException("Части относятся к разным приложениям: "
                        + base.packageName + " и " + part.packageName);
            }
            if (base.versionCode != part.versionCode) {
                throw new IOException("У частей разный versionCode — набор повреждён");
            }
            if (!base.signers.equals(part.signers)) {
                throw new IOException("Части подписаны разными ключами — набор повреждён");
            }
        }
    }

    private static void mergeMetadata(AppBundle bundle) {
        ApkInfo info = bundle.baseInfo;
        long total = 0;
        Set<String> abis = new TreeSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        for (AppBundle.Part part : bundle.selected) {
            total += part.size;
            abis.addAll(ApkAnalyzer.scanAbis(part.file));
            permissions.addAll(Arrays.asList(part.permissions));
        }
        if (total > 0) {
            info.size = total;
        }
        info.permissions = permissions.toArray(new String[0]);
        info.abis = abis;
        StringBuilder sb = new StringBuilder();
        boolean supported = abis.isEmpty();
        for (String abi : abis) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(abi);
            for (String deviceAbi : Build.SUPPORTED_ABIS) {
                if (deviceAbi.equals(abi)) {
                    supported = true;
                }
            }
        }
        info.abiList = sb.length() == 0 ? null : sb.toString();
        info.abiIncompatible = !supported;
    }

    private static String splitName(PackageInfo pkg) {
        if (pkg.splitNames != null && pkg.splitNames.length > 0) {
            for (String split : pkg.splitNames) {
                if (split != null && !split.isEmpty()) {
                    return split;
                }
            }
        }
        if (pkg.applicationInfo != null && pkg.applicationInfo.splitNames != null) {
            for (String split : pkg.applicationInfo.splitNames) {
                if (split != null && !split.isEmpty()) {
                    return split;
                }
            }
        }
        return null;
    }

    private static final class ManifestInfo {
        final Map<String, String> splitIds = new HashMap<>();
        final Map<String, String> obbNames = new HashMap<>();
    }

    private static ManifestInfo readManifest(ZipFile zip) {
        ManifestInfo result = new ManifestInfo();
        ZipEntry entry = zip.getEntry("manifest.json");
        if (entry == null) {
            entry = zip.getEntry("info.json");
        }
        if (entry == null || entry.getSize() > MAX_MANIFEST) {
            return result;
        }
        InputStream in = null;
        try {
            in = zip.getInputStream(entry);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int read;
            long total = 0;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > MAX_MANIFEST) {
                    return result;
                }
                buffer.write(chunk, 0, read);
            }
            JSONObject root = new JSONObject(buffer.toString("UTF-8"));
            collectIds(root.optJSONArray("split_apks"), result.splitIds);
            collectIds(root.optJSONArray("apks"), result.splitIds);
            collectExpansions(root.optJSONArray("expansions"), result.obbNames);
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(in);
        }
        return result;
    }

    private static void collectIds(JSONArray array, Map<String, String> result) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                continue;
            }
            String file = object.optString("file", "");
            String id = object.optString("id", "");
            if (!file.isEmpty()) {
                result.put(file, id);
                result.put(baseName(file), id);
            }
        }
    }

    /** expansions: file — путь в архиве, install_path — имя файла на устройстве. */
    private static void collectExpansions(JSONArray array, Map<String, String> result) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                continue;
            }
            String file = object.optString("file", "");
            String installPath = object.optString("install_path", "");
            if (file.isEmpty()) {
                continue;
            }
            result.put(file, installPath);
            result.put(baseName(file), installPath);
        }
    }

    /** Имя OBB на устройстве: только basename с расширением .obb. */
    private static String safeObbName(String candidate) {
        if (candidate == null) {
            return null;
        }
        String name = candidate.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        if (name.indexOf('\0') >= 0 || name.indexOf('/') >= 0) {
            return null;
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".obb")) {
            return null;
        }
        return name;
    }

    private static long copyEntry(ZipFile zip, ZipEntry entry, File destination,
                                  long alreadyUnpacked) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        long total = 0;
        try {
            in = zip.getInputStream(entry);
            out = new FileOutputStream(destination);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (alreadyUnpacked + total > MAX_UNPACKED) {
                    throw new IOException("Архив слишком большой для распаковки");
                }
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        if (!destination.isFile() || destination.length() == 0) {
            throw new IOException("Не удалось распаковать " + entry.getName());
        }
        return total;
    }

    private static String configOf(String splitId) {
        if (splitId == null) {
            return null;
        }
        String id = splitId.trim();
        if (id.isEmpty() || "base".equalsIgnoreCase(id)) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("split_config.")) {
            return id.substring("split_config.".length());
        }
        // У фичевых модулей имя сплита выглядит как featureX.config.arm64_v8a.
        int configIndex = lower.lastIndexOf("config.");
        if (configIndex >= 0) {
            return id.substring(configIndex + "config.".length());
        }
        return id;
    }

    private static String configFromEntry(String entryName) {
        String name = baseName(entryName);
        if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.toLowerCase(Locale.ROOT).startsWith("config.")) {
            return name.substring("config.".length());
        }
        int dash = name.indexOf('-');
        if (dash > 0 && dash < name.length() - 1) {
            return name.substring(dash + 1);
        }
        return null;
    }

    private static String moduleFromEntry(String entryName) {
        String name = baseName(entryName);
        if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            name = name.substring(0, name.length() - 4);
        }
        int dash = name.indexOf('-');
        if (dash > 0) {
            String module = name.substring(0, dash);
            if (!module.isEmpty() && !"config".equalsIgnoreCase(module)) {
                return module;
            }
        }
        return "base";
    }

    private static String uniqueName(String entryName, File file) {
        String name = baseName(entryName);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            name = file.getName();
        }
        return name;
    }

    private static boolean isUniversalName(String entryName) {
        String name = baseName(entryName).toLowerCase(Locale.ROOT);
        return name.equals("universal.apk") || name.startsWith("universal-")
                || name.contains("universal");
    }

    private static String safeName(String entryName, int index) {
        String name = baseName(entryName);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(allowed ? c : '_');
        }
        String safe = sb.toString();
        if (!safe.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            safe = safe + ".apk";
        }
        return index + "_" + safe;
    }

    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static int formatOf(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xapk")) {
            return AppBundle.FORMAT_XAPK;
        }
        if (lower.endsWith(".apks")) {
            return AppBundle.FORMAT_APKS;
        }
        return AppBundle.FORMAT_ZIP;
    }

    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }
}