# DocForge

Android-приложение для работы с документами **DOCX, XLSX, PPTX и PDF**: чтение текста и свойств,
замена текста по шаблону (`{{client}}=ООО Ромашка`), извлечение картинок (ZIP), водяные знаки для PDF
и создание примеров документов. Всё работает локально, интернет и разрешения не нужны —
файлы открываются и сохраняются через системный выбор файлов.

Автор: RonovaDev · Пакет: `dev.ronova.docforge` · minSdk 26 · Material You

## Как устроено

| Формат | Чем обрабатывается |
|---|---|
| DOCX / XLSX / PPTX | напрямую как Open XML (ZIP + DOM), без Apache POI — POI не работает на Android |
| PDF | [pdfbox-android](https://github.com/TomRoush/PdfBox-Android) (шрифт DejaVu Sans в `assets` для кириллицы) |

Движки лежат в `core/`, `word/`, `excel/`, `pptx/`, `pdf/`; они принимают и возвращают байты,
а чтение/запись файлов — на стороне `MainActivity`.

**Ограничения.** Замена текста в PDF невозможна (только водяной знак). В XLSX значения читаются как записаны
в файле: формулы — по сохранённому результату, даты — числом. Замена в DOCX/PPTX не трогает текст внутри
полей и SDT-элементов.

## Сборка release-APK на GitHub (с автоподписью)

1. Создайте keystore (один раз, в PowerShell на Windows):
   ```
   keytool -genkeypair -v -keystore release.jks -alias docforge -keyalg RSA -keysize 2048 -validity 10000
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
   ```
2. В репозитории: **Settings → Secrets and variables → Actions → New repository secret**:

   | Секрет | Значение |
   |---|---|
   | `KEYSTORE_BASE64` | base64 из буфера обмена (строка из шага 1) |
   | `KEYSTORE_PASSWORD` | пароль хранилища |
   | `KEY_ALIAS` | `docforge` |
   | `KEY_PASSWORD` | пароль ключа |

3. Запустите **Actions → Build release APK → Run workflow** (или сделайте push в `main`).
   Готовый подписанный `DocForge-release.apk` — в артефактах запуска. По тегу `v*` (например `v0.1.0`)
   APK также прикрепляется к GitHub Release.

Сохраните `release.jks` и пароли: приложение с другой подписью нельзя установить поверх старого.

## Локальная сборка (Android Studio, Windows)

Откройте папку проекта в Android Studio (нужен JDK 17 — он встроен в Studio) и нажмите **Run**.
Без переменных окружения `KEYSTORE_*` release-APK получается неподписанным. Для `gradlew` один раз
выполните `gradle wrapper` — Studio и GitHub Actions в нём не нуждаются.

## Лицензия

См. `LICENSE`.
