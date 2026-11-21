# Custom Installer

**Custom Installer** — аккуратный установщик APK, XAPK и APKS в нижней панели.  
Ставит приложения через root, не трогая системный установщик.

Релиз тестировался на **vivo V2352GA, Android 16 (SDK 36)**.

---

## Возможности

- **Установка APK** — одиночные файлы, `.xapk` (с манифестом), `.apks` (включая `.apks` с `universal.apk`)
- **Split APK** — установка минимального набора сплитов (base + язык + ABI + плотность), fallback на universal если сплит не подходит
- **OBB** — автоматическое копирование `.obb` в `Android/obb/<package>/` через root после установки (ошибка не фатальна)
- **Root-установка** — сессионный `pm install-create` / `install-write` / `install-commit`, с `install-abandon` при ошибке
- **Замена стока** — в один клик удаляет `com.android.packageinstaller` на сессию (`pm uninstall --user 0`), после перезагрузки сток восстанавливается
- **Безопасность** — не редактирует `/system`, не запрашивает `INSTALL_PACKAGES`, не использует `pm disable`

### Интерфейс

- **Material Design 3** с динамической темой (Monet)
- **Тёмная тема** с режимом чистого чёрного (OLED)
- **Акцентные цвета** — Monet (системный) или фиксированные (фиолетовый, синий, зелёный, красный, монохром)
- **Скругление** шторки — регулируется (8–28 dp)
- **Затемнение** фона — регулируется (0–60%)
- **Автоудаление** APK после установки (при наличии прямого пути или root)

### Детальная информация о приложении

- Иконка, название, пакет
- Тип установки (новое / обновление / даунгрейд / несовпадение подписи)
- Версия (имя + код), размер
- Минимальный и целевой SDK
- Статус подписи (совпадает / не совпадает / новая)
- Поддерживаемые ABI
- Список разрешений (опасные выделяются)
- Состав бандла (для XAPK/APKS)

---

## Сборка

### Требования

- **Android SDK Platform 37**
- **Build-tools 36.0.0**
- **JDK 17+**
- **Gradle 8.14.8+** (опционально, для Compose-сборки)

### Легаси-сборка (aapt2 + javac, без Compose)

```bash
bash tools/build.sh
```

Результат: `dist/CustomInstaller.apk` (~115 КБ).

### Gradle-сборка (с Compose Multiplatform и MIUIX)

```bash
GRADLE_USER_HOME=/tmp/gradle-home /tmp/gradle-9.9.2/bin/gradle :assembleRelease --no-daemon
```

Результат: `build-gradle/outputs/apk/release/CustomInstaller-release.apk` (~21 МБ).

---

## Установка на устройство

```bash
adb root
adb shell su -c "pm install -r -t /data/local/tmp/CustomInstaller.apk"
```

Или после установки запустить через:

```bash
adb shell monkey -p com.iqoofurry.sheetinstaller 1
```

---

## Лицензия

Проект распространяется под лицензией **MIT**.  
Автор: **iqoofurry**
