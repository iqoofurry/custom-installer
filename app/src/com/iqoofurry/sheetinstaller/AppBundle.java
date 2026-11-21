package com.iqoofurry.sheetinstaller;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Набор APK: одиночный файл, XAPK или APKS с выбранными split-частями. */
final class AppBundle {
    static final int FORMAT_APK = 0;
    static final int FORMAT_XAPK = 1;
    static final int FORMAT_APKS = 2;
    static final int FORMAT_ZIP = 3;

    static final int KIND_MASTER = 0;
    static final int KIND_ABI = 1;
    static final int KIND_DENSITY = 2;
    static final int KIND_LANGUAGE = 3;
    static final int KIND_UNIVERSAL = 4;
    static final int KIND_OTHER = 5;

    static final class Part {
        File file;
        String entryName;
        String installName;
        String splitId;
        String module = "base";
        String config;
        int kind = KIND_OTHER;
        long size;
        String packageName;
        long versionCode;
        Set<String> signers = new HashSet<>();
        String[] permissions = new String[0];
        int minSdk;
        int targetSdk;

        /** Короткая подпись для строки «Состав». */
        String label() {
            if (kind == KIND_MASTER) {
                return module;
            }
            if (config != null && !config.isEmpty()) {
                return config;
            }
            return module;
        }
    }

    /** OBB из XAPK: не распаковываем в кэш, копируем из архива при установке. */
    static final class Obb {
        String entryName;
        String fileName;
        long size;
    }

    File archive;
    File workspace;
    int format = FORMAT_APK;
    ApkInfo baseInfo;
    final List<Part> all = new ArrayList<>();
    final List<Part> selected = new ArrayList<>();
    Part base;
    Part universal;
    final List<Obb> obbs = new ArrayList<>();
    long totalSize;

    boolean isMulti() {
        return format != FORMAT_APK || selected.size() > 1;
    }

    String formatName() {
        switch (format) {
            case FORMAT_XAPK:
                return "XAPK";
            case FORMAT_APKS:
                return "APKS";
            case FORMAT_ZIP:
                return "ZIP";
            default:
                return "APK";
        }
    }

    long selectedSize() {
        long total = 0;
        for (Part part : selected) {
            total += part.size;
        }
        return total;
    }
}