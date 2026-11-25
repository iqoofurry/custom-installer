package com.iqoofurry.sheetinstaller;

import android.graphics.drawable.Drawable;

import java.io.File;
import java.util.Set;

final class ApkInfo {
    static final int TYPE_NEW = 0;
    static final int TYPE_UPDATE = 1;
    static final int TYPE_DOWNGRADE = 2;
    static final int TYPE_REINSTALL = 3;
    static final int TYPE_SIGNATURE_MISMATCH = 4;

    File file;
    String packageName;
    String label;
    String versionName;
    long versionCode;
    long installedVersionCode;
    String installedVersionName;
    int minSdk;
    int targetSdk;
    long size;
    Drawable icon;
    String[] permissions;
    Set<String> installedPermissions;
    String sha256;
    Set<String> signerHashes;
    Set<String> abis;
    int type;
    boolean installed;
    boolean abiIncompatible;
    String abiList;
}
