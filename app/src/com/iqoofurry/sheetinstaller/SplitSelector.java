package com.iqoofurry.sheetinstaller;

import android.os.Build;
import android.util.DisplayMetrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Выбирает split-части, подходящие конкретному устройству. */
final class SplitSelector {
    private static final String[] ABI_TOKENS = {
            "arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips"
    };
    private static final String[] DENSITY_TOKENS = {
            "ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"
    };
    private static final int[] DENSITY_VALUES = {120, 160, 213, 240, 320, 480, 640};

    private SplitSelector() {
    }

    static void select(AppBundle bundle) throws IOException {
        if (bundle.all.isEmpty()) {
            throw new IOException("В наборе нет APK");
        }
        if (bundle.base != null && bundle.base.kind == AppBundle.KIND_UNIVERSAL) {
            bundle.selected.clear();
            bundle.selected.add(bundle.base);
            bundle.totalSize = bundle.selectedSize();
            return;
        }
        Map<String, List<AppBundle.Part>> modules = new LinkedHashMap<>();
        for (AppBundle.Part part : bundle.all) {
            if (part.kind == AppBundle.KIND_UNIVERSAL) {
                continue;
            }
            List<AppBundle.Part> list = modules.get(part.module);
            if (list == null) {
                list = new ArrayList<>();
                modules.put(part.module, list);
            }
            list.add(part);
        }

        List<AppBundle.Part> selected = new ArrayList<>();
        for (Map.Entry<String, List<AppBundle.Part>> entry : modules.entrySet()) {
            List<AppBundle.Part> parts = entry.getValue();
            List<AppBundle.Part> abiParts = new ArrayList<>();
            List<AppBundle.Part> densityParts = new ArrayList<>();
            List<AppBundle.Part> languageParts = new ArrayList<>();
            AppBundle.Part master = null;
            for (AppBundle.Part part : parts) {
                if (part.kind == AppBundle.KIND_MASTER) {
                    if (master == null || part == bundle.base) {
                        master = part;
                    }
                } else if (part.kind == AppBundle.KIND_OTHER) {
                    selected.add(part);
                } else if (part.kind == AppBundle.KIND_LANGUAGE) {
                    languageParts.add(part);
                } else if (part.kind == AppBundle.KIND_ABI) {
                    abiParts.add(part);
                } else if (part.kind == AppBundle.KIND_DENSITY) {
                    densityParts.add(part);
                }
            }
            if (master != null) {
                selected.add(master);
            }
            // Языковые сплиты: только язык устройства + английский, чтобы не
            // ставить десятки мелких частей. Если таких нет — base использует
            // встроенные ресурсы по умолчанию.
            if (!languageParts.isEmpty()) {
                selected.addAll(chooseLanguages(languageParts));
            }
            if (!abiParts.isEmpty()) {
                AppBundle.Part abi = chooseAbi(abiParts);
                if (abi == null) {
                    if (bundle.universal != null) {
                        bundle.selected.clear();
                        bundle.selected.add(bundle.universal);
                        bundle.totalSize = bundle.selectedSize();
                        return;
                    }
                    throw new IOException("В наборе нет native-библиотек для процессора устройства ("
                            + join(Build.SUPPORTED_ABIS) + ")");
                }
                selected.add(abi);
            }
            if (!densityParts.isEmpty()) {
                selected.add(chooseDensity(densityParts));
            }
        }

        if (selected.isEmpty()) {
            if (bundle.universal == null) {
                throw new IOException("Не удалось собрать split-пакет: нет base APK");
            }
            selected.add(bundle.universal);
        }
        if (bundle.base != null) {
            selected.remove(bundle.base);
            selected.add(0, bundle.base);
        } else if (bundle.universal != null) {
            selected.remove(bundle.universal);
            selected.add(0, bundle.universal);
        }

        bundle.selected.clear();
        bundle.selected.addAll(selected);
        bundle.totalSize = bundle.selectedSize();
    }

    private static AppBundle.Part chooseAbi(List<AppBundle.Part> parts) {
        for (String deviceAbi : Build.SUPPORTED_ABIS) {
            String normalizedDevice = normalize(deviceAbi);
            for (AppBundle.Part part : parts) {
                if (normalizedDevice.equals(normalize(part.config))) {
                    return part;
                }
            }
        }
        return null;
    }

    private static AppBundle.Part chooseDensity(List<AppBundle.Part> parts) {
        DisplayMetrics metrics = android.content.res.Resources.getSystem().getDisplayMetrics();
        int deviceDpi = metrics.densityDpi;
        AppBundle.Part best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (AppBundle.Part part : parts) {
            int dpi = densityValue(part.config);
            if (dpi <= 0) {
                continue;
            }
            int distance = Math.abs(dpi - deviceDpi);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = part;
            }
        }
        if (best == null) {
            best = parts.get(0);
        }
        return best;
    }

    /**
     * Языковые сплиты: берём язык устройства и английский.
     * Ставить все десятки языков не нужно — base содержит ресурсы по умолчанию.
     */
    private static List<AppBundle.Part> chooseLanguages(List<AppBundle.Part> parts) {
        List<AppBundle.Part> picked = new ArrayList<>();
        String deviceLang = "en";
        try {
            android.content.res.Configuration cfg =
                    android.content.res.Resources.getSystem().getConfiguration();
            if (!cfg.getLocales().isEmpty()) {
                deviceLang = cfg.getLocales().get(0).getLanguage();
            }
        } catch (Throwable ignored) {
        }
        String lang = deviceLang == null ? "en" : deviceLang.toLowerCase(Locale.ROOT);
        for (AppBundle.Part part : parts) {
            String config = part.config == null ? "" : part.config.toLowerCase(Locale.ROOT);
            if (config.equals(lang) || config.equals("en")) {
                picked.add(part);
            }
        }
        return picked;
    }

    static boolean isAbi(String config) {
        String normalized = normalize(config);
        for (String token : ABI_TOKENS) {
            if (token.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    static boolean isDensity(String config) {
        String normalized = normalize(config);
        for (String token : DENSITY_TOKENS) {
            if (token.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    static boolean isLanguage(String config) {
        if (config == null || config.isEmpty()) {
            return false;
        }
        return config.matches("[a-z]{2,3}(-r[A-Z]{2})?")
                || config.matches("[a-z]{2,3}")
                || config.matches("[a-z]{2,3}_[a-z]{2}");
    }

    static int densityValue(String config) {
        String normalized = normalize(config);
        for (int i = 0; i < DENSITY_TOKENS.length; i++) {
            if (DENSITY_TOKENS[i].equals(normalized)) {
                return DENSITY_VALUES[i];
            }
        }
        return 0;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String join(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(value);
        }
        return sb.toString();
    }
}