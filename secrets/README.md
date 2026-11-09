# secrets/

Всё, что никогда не должно попасть на GitHub, лежит здесь. Одна папка — значит,
резервная копия делается одним `cp -a secrets/ …`, и ничего не забыто в домашней
папке.

`.gitignore` игнорирует `/secrets/*` и пропускает только этот README: папка и
пояснение едут вместе с репозиторием, ключи — никогда.

## Что здесь лежит

| Файл | Что это | Если потерять |
|---|---|---|
| `recgram-release.jks` | Ключ подписи RecGram (PKCS12, alias `recgram`, RSA 4096, до 2056 г.). SHA-256 сертификата: `A1:16:F9:46:5E:D8:A2:98:66:B3:F2:1D:6F:A7:58:BF:81:BA:24:DD:24:18:42:B6:70:D4:0C:A2:76:BC:69:D9` | Обновления для установленных телефонов станут невозможны: Android не принимает APK с другой подписью. **Держите копию вне компьютера, минимум в двух местах.** |
| `android-local.properties` | Четыре значения `RECGRAM_KEY*` для подписи. Встроенного API-ключа Telegram у RecGram нет — каждый пользователь вводит свой при первом запуске | Пароль ключа подписи — только здесь. |

## Ссылка для Gradle

Gradle ищет `local.properties` в корне проекта, поэтому настоящий файл лежит здесь,
а в корне — ссылка на него:

```
local.properties -> secrets/android-local.properties
```

В WSL-копии для сборки (`~/Development/recgram`) так же; там в файл добавлена ещё
строка `sdk.dir=…`. Путь к ключу в файле относительный (`../secrets/recgram-release.jks`
считается от модуля `TMessagesProj_App`), поэтому одинаковый в Windows и WSL.

## Новая машина

1. Скопировать папку `secrets/` из резервной копии.
2. `ln -s secrets/android-local.properties local.properties`
3. Добавить в файл `sdk.dir=<путь к Android SDK>`.

Без ключа подписи сборка тоже работает — APK подписывается тестовым ключом из
репозитория Telegram, но такой APK нельзя поставить поверх версии с нашей подписью.

## GitHub Actions

Workflow `.github/workflows/release.yml` подписывает релизы тем же ключом. Секреты репозитория
(Settings → Secrets and variables → Actions):

| Секрет | Значение |
|---|---|
| `RECGRAM_KEYSTORE_BASE64` | `base64 -w0 secrets/recgram-release.jks` |
| `RECGRAM_KEYSTORE_PASSWORD`, `RECGRAM_KEY_ALIAS`, `RECGRAM_KEY_PASSWORD` | из `android-local.properties` |

Без `RECGRAM_KEYSTORE_BASE64` workflow останавливается, а не подписывает тестовым ключом Telegram.
После сборки он сверяет SHA-256 сертификата APK с указанным выше.
