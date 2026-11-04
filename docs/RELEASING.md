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
