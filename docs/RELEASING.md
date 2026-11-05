# Как выпускать релизы

Релиз собирается автоматически: GitHub Actions реагирует на тег `v*`.

```bash
git tag v0.2.0
git push origin v0.2.0
```

Workflow [`release.yml`](../.github/workflows/release.yml) делает следующее:
1. Прогоняет все тесты.
2. Собирает release-APK (R8).
3. Проверяет, что в APK нет сетевых разрешений.
4. Подписывает APK.
5. Публикует на странице **Releases** файлы `Lightneet-<версия>.apk` и `.sha256`.

Версия берётся из тега: `v1.2.3` → `versionName 1.2.3`, `versionCode 10203`. Тег с дефисом
(например, `v0.2.0-beta1`) публикуется как pre-release.

## Ключ подписи — обязательно перед первым «настоящим» релизом

Android разрешает обновить приложение только сборкой с **тем же** ключом. Если ключ поменяется,
придётся удалить приложение, а вместе с ним удалится и хранилище.

Пока ключ не настроен, релизы подписываются временным debug-ключом, который меняется при каждой
сборке, и помечаются как предварительные.

### 1. Создать ключ (один раз, на своём компьютере)

```bash
keytool -genkeypair -v -keystore lightneet-release.jks -alias lightneet \
  -keyalg RSA -keysize 4096 -validity 36500
```

Сохраните `lightneet-release.jks` и пароли в надёжном месте, например в менеджере паролей, и
сделайте резервную копию. **Потеря ключа = невозможность выпускать обновления.**

**Никогда не коммитьте ключ в репозиторий.**

### 2. Добавить секреты в GitHub

Откройте **Settings → Secrets and variables → Actions → New repository secret**:

| Имя | Значение |
|---|---|
| `LIGHTNEET_KEYSTORE_BASE64` | вывод `base64 -w0 lightneet-release.jks` (на macOS: `base64 -i lightneet-release.jks`) |
| `LIGHTNEET_KEYSTORE_PASSWORD` | пароль хранилища ключей |
| `LIGHTNEET_KEY_ALIAS` | `lightneet` |
| `LIGHTNEET_KEY_PASSWORD` | пароль ключа |

После этого следующий тег соберётся с вашим постоянным ключом. Сборки, подписанные временным
ключом, обновить им не получится: их придётся удалить и установить заново.

## Проверка APK пользователем

В описании каждого релиза указаны SHA-256 файла и сертификата подписи.

```bash
sha256sum -c Lightneet-0.2.0.apk.sha256
apksigner verify --print-certs Lightneet-0.2.0.apk
```

Сертификат должен совпадать от релиза к релизу.

## Хеши зависимостей

Gradle проверяет SHA-256 каждой библиотеки и плагина по файлу `gradle/verification-metadata.xml`,
а дистрибутив Gradle — по `distributionSha256Sum`. Если артефакт подменили, сборка упадёт.

После изменения зависимостей хеши нужно обновить:

```bash
./gradlew --write-verification-metadata sha256 \
  :core:jvmTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Затем проверьте diff: новые хеши должны относиться только к тем зависимостям, которые вы меняли.

Для `aapt2` в файл вручную добавлены хеши сборок под macOS и Windows, чтобы проект собирался на
любой ОС. При обновлении AGP их тоже нужно обновить.

## F-Droid и Google Play

Описание, значок, скриншоты и списки изменений лежат в
[`fastlane/metadata/android/`](../fastlane/metadata/android) — в формате, который читают и
F-Droid, и `fastlane supply` для Google Play. Список изменений называется по `versionCode`
(`v0.3.0` → `300.txt`). Добавляйте новый файл к каждому релизу.

### F-Droid

F-Droid собирает приложение сам, из исходников. Чтобы его туда добавить:

1. Сделайте форк [fdroiddata](https://gitlab.com/fdroid/fdroiddata) на GitLab.
2. Добавьте файл `metadata/io.github.myfreezeart.lightneet.yml`:
   ```yaml
   Categories:
     - Security
   License: GPL-3.0-only
   SourceCode: https://github.com/myfreeze-art/Lightneet
   IssueTracker: https://github.com/myfreeze-art/Lightneet/issues
   AutoName: Lightneet
   RepoType: git
   Repo: https://github.com/myfreeze-art/Lightneet.git
   Builds:
     - versionName: 0.3.0
       versionCode: 300
       commit: v0.3.0
       subdir: app
       gradle:
         - yes
   AutoUpdateMode: Version
   UpdateCheckMode: Tags
   ```
   Без переменных окружения сборка берёт версию из `app/build.gradle.kts`, поэтому перед тегом
   обновите там `versionName`.
3. Проверьте локально: `fdroid build -v -l io.github.myfreezeart.lightneet`, затем откройте
   merge request.

Что может спросить ревьюер F-Droid: lazysodium-android и JNA приходят из Maven Central с готовыми
`.so`. Если потребуют собирать libsodium из исходников, это делается в `prebuild` (скрипт сборки
libsodium для Android есть в её репозитории).

### Google Play

1. Настройте постоянный ключ (см. выше): тогда в релиз попадает `Lightneet-<версия>.aab`.
2. В Play Console создайте приложение `io.github.myfreezeart.lightneet` и включите
   **Play App Signing**. Загрузите AAB — ключ из GitHub станет ключом загрузки.
3. Заполните анкеты: приложение не собирает данные и не имеет доступа в сеть. Разрешение
   `CAMERA` нужно для встроенной камеры.
4. Тексты и скриншоты можно загрузить вручную или командой
   `fastlane supply --aab Lightneet-0.3.0.aab --track internal` (нужен JSON-ключ сервисного
   аккаунта Play Console; в репозиторий его не кладите).
