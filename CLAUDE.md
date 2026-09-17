# OBD AI — заметки для Claude

Android-приложение (Kotlin, Jetpack Compose) для диагностики машины через дешёвый ELM327 по Bluetooth
с разбором ошибок нейросетью на основе опыта владельцев с drive2.ru и drom.ru.
Владелец: Мирослав (GitHub `Sin-sluhi`). Общение по-русски.

## Как собирать и выпускать

- Локально Android SDK нет. Сборка только в GitHub Actions (`.github/workflows/build.yml`), при каждом пуше в `main`.
- Результат: debug-APK в Releases под тегом `latest`:
  https://github.com/Sin-sluhi/obd-ai/releases/download/latest/app-debug.apk
- `gh` установлен в `C:\Program Files\GitHub CLI` (не в PATH Git Bash: `export PATH="$PATH:/c/Program Files/GitHub CLI"`), залогинен.
- Ждать сборку: `gh run watch <id> --exit-status`, ошибки: `gh run view <id> --log-failed | grep "e: file"`.
- Секреты репозитория: `AI_API_KEY` (ключ Groq, задан), переменные `AI_PROVIDER` (по умолчанию `groq`), `AI_MODEL`.
  Они попадают в `BuildConfig` через `app/build.gradle.kts`.
- Версия: `versionCode`/`versionName` в `app/build.gradle.kts` и строка `"OBD AI 0.x"` в настройках (`ui/Screens.kt`).

## Продуктовые правила (от владельца, не нарушать)

1. **Магия.** Пользователь не видит ни провайдера, ни модели, ни ключей. Всё это только в режиме разработчика
   (7 нажатий на версию внизу настроек). Тексты этапов и ошибок нейтральные: «Готовлю разбор»,
   «Подробный разбор временно недоступен».
2. **Одна кнопка, ноль ручных шагов.** Подключился → «Проверить машину» → готовый вердикт. Никаких «скопируй
   отчёт и спроси в чате».
3. **Объяснения по фактам с форумов.** Причины и решения по каждому коду берутся из опыта владельцев такой же
   модели (drive2.ru, drom.ru), со ссылками. Не общие описания кодов.
4. **Без упоминания Claude/Anthropic в интерфейсе.** Если нужно имя функции ИИ — «OBIDI AI».
5. Владелец не платит за API: подписка Claude в приложение не подключается (нет API), иностранной карты нет.
   Поэтому провайдер по умолчанию Groq (бесплатно), ключ встроен в сборку.

## Архитектура

- `ObdDecoder.kt` — разбор ответов ELM327 (DTC, VIN, PID, режим 09), `ModuleDecoder` (UDS 19 02 / KWP 18 00),
  `ModuleMap` (адреса блоков, имена по маркам, полный перебор 7A0–7DF для Hyundai/Kia).
- `Elm327.kt` — Bluetooth SPP, инициализация (ATZ, ATE0, ATL0, ATS1, ATH0, ATAT1, ATSP0), чтение, опрос блоков
  (`scanModules`: ATSH/ATCRA/ATFCSH/ATFCSM, 1902FF, потом 1800FF00; сырые ответы пишутся в лог).
- `ObdLink.kt` — интерфейс источника данных + `DemoLink` (выдуманная Lada Granta, через 15 с «едет»).
- `VinDecoder.kt` — марка по WMI, год по 10-му символу, модели Lada/Hyundai/Kia. Результат уходит нейронке как факт.
- `AiConfig.kt` — провайдеры: groq (по умолчанию, `groq/compound` со встроенным поиском), yandex, openrouter,
  mistral, anthropic, custom.
- `OpenAiClient.kt` — любой OpenAI-совместимый `/chat/completions`; для Groq compound `search_settings`
  с `include_domains` drive2/drom; ссылки из `executed_tools`.
- `ForumSearch.kt` — свой поиск (DuckDuckGo html → Bing) и вырезка текста записей для провайдеров без поиска.
- `AiClient.kt` — оркестратор: Groq одним вызовом (при неудаче запасная модель `openai/gpt-oss-120b` без поиска),
  остальные в два этапа, Anthropic через web_search + structured outputs. Промпт `ROLE` + схема `SCHEMA_TEXT`.
- `AppState.kt` — синглтон на процесс (`AppState.get`), состояние для Compose, фоновые задачи, история,
  поездки (`TripLive`/`Trip`), опрос датчиков (`startPolling`).
- `TripService.kt` — foreground-сервис записи поездки (тип connectedDevice, уведомление).
- `Prefs.kt` — SharedPreferences: ключи по провайдерам, история, поездки, dev-режим.
- `ui/Theme.kt` — палитра «приборная панель ночью», шрифты (Unbounded/Manrope/JetBrains Mono из `res/font`),
  объёмные карточки, кнопки, живая кнопка проверки, спидометр.
- `ui/Gauges.kt` — круглые приборы со стрелками и скины по маркам (`Skins.forCar`).
- `ui/Screens.kt` — экраны: главный, результат, датчики, история, настройки, консоль.
- `MainActivity.kt` — навигация (`AnimatedContent`), разрешения Bluetooth/уведомлений, поездки, шаринг.

## Что проверено на живой машине (17.09.2026)

Hyundai Tucson 2019 (VIN KMHJ381ADKU…), ELM327 v1.5, ISO 15765-4 CAN 11/500. Ответили 10 блоков:
двигатель, КПП, ABS/ESC, подушки, ЭУР, приборка, парктроники, BCM, климат, неизвестный 7C3.
Архив двигателя: P2504, P0504, P0820, P0524, P1171; активная P2400-20. Стандартный режим 03 ошибок не даёт,
их видно только через UDS 19 02 FF на 7E0.

## Известные грабли

- В Bash-инструменте heredoc с кириллицей иногда ломается («unexpected EOF»): писать блок через Write в
  scratchpad и вставлять `sed -i "<N>r file"`.
- В perl-заменах `\n` внутри Kotlin-строк превращается в настоящий перенос и ломает компиляцию.
  Kotlin-строки с `\n`/`\r` править только через Write/Edit или файл-вставку.
- Write иногда записывает `\u0000` как сырой NUL-байт: в `Elm327.send` должно быть `replace("\\u0000", "")`.
- Cloudflare в консоли Groq не проходится из встроенного браузера и часто из Chrome с VPN: ключ создавать
  с телефона на мобильном интернете.
- Компилятор только в CI: после каждого изменения пуш и `gh run watch`.

## Открытые задачи

- Разобраться, почему compound иногда не отдаёт JSON (нужен лог из консоли: кнопка «Лог»).
- Названия для неизвестных блоков (7C3 на Tucson).
- Расход в поездках проверить по одометру; у машин без MAF расход не считается.
- Позже: свой сервер с ключом вместо ключа в APK, подписанная release-сборка.
