package com.iqoofurry.sheetinstaller;

import java.util.Locale;

final class ErrorText {
    private ErrorText() {
    }

    static String human(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "Неизвестная ошибка установки";
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
            return "Подписи установленной и новой версии не совпадают. Удалите приложение, чтобы установить эту версию.";
        }
        if (upper.contains("INSTALL_PARSE_FAILED_NO_CERTIFICATES")
                || upper.contains("INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES")) {
            return "APK подписан некорректно или повреждён.";
        }
        if (upper.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) {
            return "Понижение версии запрещено системой. Нужен root-доступ.";
        }
        if (upper.contains("INSTALL_FAILED_OLDER_SDK")) {
            return "Приложение не поддерживает эту версию Android.";
        }
        if (upper.contains("INSTALL_FAILED_NEWER_SDK")) {
            return "Приложение требует более новую версию Android.";
        }
        if (upper.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) {
            return "Недостаточно свободного места на устройстве.";
        }
        if (upper.contains("INSTALL_FAILED_ALREADY_EXISTS")) {
            return "Приложение уже установлено.";
        }
        if (upper.contains("INSTALL_FAILED_CONFLICTING_PROVIDER")) {
            return "Конфликт ContentProvider с другим приложением.";
        }
        if (upper.contains("INSTALL_FAILED_DUPLICATE_PERMISSION")) {
            return "Разрешение уже объявлено другим приложением.";
        }
        if (upper.contains("INSTALL_FAILED_MISSING_SHARED_LIBRARY")) {
            return "Не найдена необходимая системная библиотека.";
        }
        if (upper.contains("INSTALL_FAILED_MISSING_SPLIT")) {
            return "Набор split-APK неполный: не хватает частей для этого устройства. Попробуйте universal-версию.";
        }
        if (upper.contains("INSTALL_FAILED_NO_MATCHING_ABIS")) {
            return "В наборе нет native-библиотек для процессора устройства.";
        }
        if (upper.contains("INSTALL_FAILED_INVALID_APK")) {
            return "APK повреждён или имеет неверную структуру.";
        }
        if (upper.contains("INSTALL_FAILED_INVALID_URI")) {
            return "Некорректный путь к APK.";
        }
        if (upper.contains("INSTALL_FAILED_DEXOPT")) {
            return "Ошибка оптимизации кода приложения.";
        }
        if (upper.contains("INSTALL_FAILED_USER_RESTRICTED")) {
            return "Установка ограничена настройками устройства.";
        }
        if (upper.contains("INSTALL_FAILED_VERIFICATION_FAILURE")) {
            return "Проверка пакета не пройдена.";
        }
        if (upper.contains("INSTALL_FAILED_PRIVILEGED")) {
            return "Не хватает привилегий для установки.";
        }
        if (upper.contains("PERMISSION DENIAL") || upper.contains("SECURITYEXCEPTION")) {
            return "Нет прав на установку. Проверьте привилегии или root-доступ.";
        }
        if (upper.contains("TIMEOUT") || upper.contains("ТАЙМАУТ")) {
            return "Истекло время ожидания установки.";
        }
        if (upper.contains("SU:") || upper.contains("NOT FOUND")
                || upper.contains("PERMISSION DENIED")) {
            return "Root-доступ недоступен или отклонён.";
        }
        if (upper.contains("FAILURE [")) {
            int start = upper.indexOf("FAILURE [");
            int end = upper.indexOf(']', start);
            if (end > start) {
                return "Установка не удалась: " + raw.substring(start + 9, end).trim();
            }
        }
        if (upper.contains("INSTALL_FAILED")) {
            return raw.trim();
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 400) {
            trimmed = trimmed.substring(0, 400) + "…";
        }
        return "Установка не удалась: " + trimmed;
    }
}
