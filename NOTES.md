# Заметки по устройству (проверенные факты)

## MIUIX-дизайн (Compose Multiplatform)

- Реализован как отдельные Activity: `MiuixMainActivity`, `MiuixSettingsActivity`,
  `MiuixInstallSheetActivity` — используют настоящие компоненты
  `top.yukonga.miuix.kmp:miuix-ui:0.9.4`.
- Переключение между MD3 и MIUIX: `Prefs.design()` / `setDesign()`.
  Сохраняется в `installer_settings`. Настройки и главный экран MD3 имеют
  переключатель дизайна; MIUIX-экраны также позволяют вернуться на MD3.
- При старте MD3-активности проверяют `Prefs.isMiuix()` и форвардят интент
  на соответствующую MIUIX-активность; MIUIX-активности делают обратное.
- Тема: `MiuixAppTheme` — обёртка над `MiuixTheme(ThemeController(...))`.
  Акцентный цвет берётся из `Ui.primary(context)` (динамический или пресет).
  При включённом OLED и тёмной теме `colors.withOled()` заменяет
  `background`/`surface`/`surfaceContainer` на `Color.Black`.
- Compose-версия InstallSheetActivity использует кастомную анимацию:
  `Animatable`+`snapTo` для отслеживания касания и `tween` для закрытия.
  Скрим (затемнение) считается через `animateFloatAsState`.
- Компоненты-утилиты: `MiuixCard`, `MiuixSection`, `MiuixInfoRow`,
  `MiuixPill`, `MiuixChips`, `DrawablePainter` — в `MiuixUi.kt`.

## Сборка

- `bash tools/build.sh` — легаси-сборка через aapt2 + javac,
  не включает Compose/Kotlin.
- Gradle-сборка через AGP 9.5.2 + Kotlin 2.4.21 + Compose 1.12.2:
  `GRADLE_USER_HOME=/tmp/gradle-home /tmp/gradle-9.9.2/bin/gradle :assembleRelease --no-daemon`
  Результат: `build-gradle/outputs/apk/release/CustomInstaller-release.apk`.
- Требования: `compileSdk=37` (Android SDK Platform 37),
  JDK 17, `android.builtInKotlin=false`, `android.newDsl=false`.
- APK подписывается `keystore/custominstaller.jks` (пароль `android`).
- Размер APK ~21 MB (с Compose runtime и MIUIX).

Устройство: vivo V2352GA, Android 16 (SDK 36), прошивка CN, заблокированный загрузчик,
temp root (в сессии `su` даёт `u:r:ksu:s0`, после ребута рут исчезает).

## Стоковый установщик

- Пакет: `com.android.packageinstaller` (`/system/priv-app/PackageInstaller`, versionName 12.4.1.9.2).
- У него `priority=1` на apk-фильтрах (`ACTION_VIEW` и `ACTION_INSTALL_PACKAGE`).
- `com.google.android.packageinstaller` на этом устройстве отсутствует.

## Удаление стока — только на сессию

- `su -c "pm uninstall --user 0 com.android.packageinstaller"` → `Success`,
  `dumpsys` показывает `installed=false`.
- Проверка PMS (`ACTION_INSTALL_PACKAGE` + `MATCH_SYSTEM_ONLY`) в этой сессии даёт 0 совпадений.
- **После перезагрузки система поднимает сток обратно и грузится нормально** (проверено пользователем).
  То есть удаление действует только до ребута.
- Бутлуп вызывал именно `pm disable` (флаг `enabled=disabled`), а не `uninstall`.
  `pm disable` в модулях/скриптах не использовать.

## Почему сторонний установщик не перехватывает тап по APK

- В нашем APK `android:priority=500`, но система парсит фильтры с `mPriority=0`:
  vivo обнуляет приоритет intent-filter для несистемных приложений.
- Сток с `priority=1` всегда выигрывает резолвер, поэтому диалога «Открыть с помощью» нет.
- По коду AOSP 16 (`ResolveIntentHelper.chooseBestActivity`) preferred-activity проверяется
  только если у двух лучших кандидатов **одинаковый** priority. Поэтому «Всегда»,
  выбранное при удалённом стоке, перестаёт работать, когда сток возвращается с priority=1 —
  отсюда «через время опять надо выбирать».

## Рабочая схема (реализована в приложении)

Одна кнопка, которая меняет смысл по состоянию:

- сток установлен → **«Удалить стоковый установщик»** (красная):
  `su -c pm uninstall --user 0 com.android.packageinstaller`;
  затем сразу открывается системный диалог выбора для apk-интента
  (собственный APK отдаётся через `SelfApkProvider`) — выбираем
  Custom Installer → «Всегда».
- сток убран → **«Вернуть стоковый установщик»** (акцентная):
  `pm install-existing --user 0 com.android.packageinstaller`.

Тапы по APK открывают наш установщик до перезагрузки.
Состояние читается через `InstallerSwitch.systemInstallers()`:
запрос `ACTION_INSTALL_PACKAGE` без флагов + отбор по `FLAG_SYSTEM`,
потому что `MATCH_SYSTEM_ONLY` из обычного приложения на этой прошивке
возвращает 0 результатов.

## UI

- Стиль: MD3 + Monet (`system_accent1_500`), сплошные цвета без градиентов и без обводок.
- Шторка установщика (`InstallSheetActivity`) выезжает снизу целиком (только translateY,
  без fade) и убирается вниз так же. Window-анимации отключены (`@anim/no_animation`),
  чтобы не было двойного движения.
- Затемнение фона — отдельный `scrim` внутри разметки (window dim отключён и в светлой,
  и в ночной теме): плавно проявляется при открытии и плавно исчезает при закрытии
  (200 мс, быстрее шторки), поэтому нет резкого скачка яркости в конце.
- Полоска сверху шторки (`sheet_handle`) — drag-to-dismiss: шторка едет за пальцем,
  при отпускании ниже порога (20% высоты или 72dp) либо при флинге вниз закрывается
  с уездом вниз; иначе возвращается на место.
- В шторке нет отдельного заголовка и чипа режима — сразу три тональных блока
  (`bg_row_tonal`, как блок разрешений): приложение (иконка + название + версия),
  информация (размер, SDK, подпись, ABI) и разрешения.
- ABI показывается компактными чипами (11sp) по архитектурам (`FlowLayout`, перенос
  строк, строки центрируются по горизонтали); четыре типовые архитектуры помещаются
  в одну строку. Подписи и иконки ABI нет. При отсутствии native-кода по центру
  пишется «Нет native-кода».
- Обновление показывается как «1.0 (1) → 2.0 (2)», новая версия акцентным жирным;
  понижение — красным. Рядом в блоке инфо есть строка «Подпись»:
  «Совпадает» (акцент), «Не совпадает» (красный) или «Проверена» для новой установки.
- Список разрешений: короткое имя + полное имя моноширинным, справа бейдж
  «Опасно» (мягкий красный) для dangerous/специальных разрешений и «Обычное» (серый).
- Смена состояний (загрузка → карточка, установка → прогресс → «Готово»/«Открыть»)
  анимируется общим Fade + ChangeBounds на `root_sheet`; scrim из перехода исключён.

## Настройки

- Экран `SettingsActivity` (шестерёнка справа сверху на главном): акцент, скругление,
  затемнение, OLED, автоудаление APK и сброс. Хранится в `SharedPreferences`
  (`installer_settings`, класс `Prefs`), применяется в `Ui.applyDynamicTheme`.
- Акцент: «Monet» (`system_accent1_500`) + пресеты фиолетовый/синий/зелёный/красный/
  графит. Все акцентные элементы (кнопки, чипы, иконки, прогресс, seekbar, switch)
  берут цвет из `Ui.primary()`.
- Скругление: ползунок 8–28 dp для карточек; крупные элементы (шторка, кнопки)
  получают `min(28, radius * 2)`.
- Затемнение: 0–60 %, значение уходит в alpha `scrim` при открытии шторки.
- OLED: в тёмной теме surface = `#000000`, карточки `#0B0B0B`, чипы `#1C1C1C`.
- Автоудаление: временная копия в кэше удаляется всегда при закрытии/после установки;
  исходник по `file://` удаляется только при включённой опции (сначала обычным
  `delete()`, затем `su rm` для путей вне доступа). `content://` не удаляется.
- При старте шторка чистит `cache/incoming_*.apk` старше 10 минут.
- Системные бары: `Ui.applySystemBars` явно ставит светлые/тёмные иконки по теме.

## Split APK (XAPK / APKS)

- `BundleExtractor` определяет формат по содержимому ZIP: корневой `AndroidManifest.xml` —
  обычный APK, иначе ищутся вложенные `*.apk`. XAPK/APKS распаковываются в
  `cache/bundle_<ts>/` (лимит 2 ГБ распакованного, 600 частей, защита от `../`,
  пропуск `__MACOSX` и `META-INF`).
- Из `manifest.json` (XAPK) или `info.json` (APKM) читаются имена split-частей; `toc.pb`
  игнорируется. `standalone-*`/`instant-*` из `.apks` пропускаются — ставим split-набор.
- `SplitSelector`: base + все языковые части + одна ABI (первая из `Build.SUPPORTED_ABIS`)
  + одна плотность (ближайшая к `densityDpi`); неизвестные конфиги включаются. Если
  подходящей ABI нет, но есть `universal.apk`, ставится universal.
- `PackageManager.getPackageArchiveInfo` на этой прошивке часто возвращает null для
  split-APK. Тогда package/version/подпись берутся из base, а конфиг — из имени файла
  или `manifest.json`.
- Установка: одиночный APK — прежний `pm install -r -d`; бандл — `pm install-create -r -d
  -S <сумма>` → `pm install-write` по частям → `pm install-commit`. Временная папка
  `/data/local/tmp/ci_<tag>` удаляется всегда, при ошибке делается `pm install-abandon`.
- Fallback: при `MISSING_SPLIT`, `NO_MATCHING_ABIS` и похожих ошибках один раз
  пробуется `universal.apk`, если он есть в архиве.
- UI: бейдж `XAPK/APKS · N частей`, строка «Состав» (base + ABI + плотность + N языков),
  общий размер всех выбранных частей, строка «OBB» (число файлов и суммарный размер).
- OBB: не распаковываются в кэш и не занимают место дважды. После успешной установки
  `InstallEngine` читает `.obb` прямо из ZIP и через `su` пишет в
  `/storage/emulated/0/Android/obb/<package>/`, затем выставляет владельца
  `<uid приложения>:<gid ext_obb_rw>`, каталогу — `2770`, файлам — `0660`.
  Имя берётся из `expansions[].install_path` (XAPK) либо из basename записи; путь
  всегда ограничен `Android/obb/<package>/`, поэтому `install_location` и `../`
  не могут увести запись наружу. Пока идёт копирование, в карточке прогресса
  отображается «Копирование OBB… N%». Если root недоступен или UID не определился,
  приложение всё равно считается установленным, а в успехе показывается причина.
- В манифест добавлены фильтры `.xapk`, `.apks`, `.apkm`; выбор файла — `*/*` с MIME
  apk/zip/octet-stream.
- Чистка: папки `cache/bundle_*` старше 10 минут удаляются при открытии шторки; папка
  текущего бандла удаляется после успеха и при закрытии. Исходный `.xapk`/`.apks`
  удаляется по тому же правилу, что и APK (`file://` + включённое автоудаление).
- Ограничения v1: `.apkm` и ручной мультивыбор — позже, экзотические имена split-частей
  могут не распознаться (тогда выручает universal). OBB копируются только при наличии
  root и для текущего пользователя (user 0).

## Сборка

- SDK: `/tmp/android/sdk` (build-tools 36.0.0, platforms/android-36).
- `bash tools/build.sh` → `dist/CustomInstaller.apk` (1.3 / code 5).
- Установка на устройство: `adb push` + `su -c "pm install -r ..."`
  (обычный `adb install` vivo блокирует: `INSTALL_FAILED_ABORTED: User rejected permissions`).