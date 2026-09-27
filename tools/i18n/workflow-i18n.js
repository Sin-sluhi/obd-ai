export const meta = {
  name: 'obdai-i18n',
  description: 'Локализация OBD AI: вынос русских строк в tr() по файлам, ru.json, компиляция в CI с починкой, перевод на 16 языков, проверка',
  phases: [
    { title: 'Extract', detail: 'по агенту на файл: русские литералы → tr("key"), ключи в tools/i18n/keys/<prefix>.json' },
    { title: 'Merge', detail: 'ru.json из ключей, остаток литералов, нарезка на куски' },
    { title: 'Compile', detail: 'push → CI; ошибки компиляции чинятся агентами по кругу' },
    { title: 'Translate', detail: '16 языков × куски по 250 ключей' },
    { title: 'Verify', detail: 'check.py --strict по языкам, починка, финальный push и сборка' },
  ],
}

const REPO = 'C:\\Users\\logik\\obd-ai'
const GH = '"/c/Program Files/GitHub CLI/gh.exe"'
const TRAILER = 'Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>'

const FILES = [
  ['ui/Screens.kt', 'scr'], ['ui/Extra.kt', 'ext'], ['ui/ForumScreen.kt', 'forum'], ['ui/GarageScreen.kt', 'garage'],
  ['ui/PurchaseScreen.kt', 'purch'], ['ui/Journal.kt', 'journal'], ['ui/FuelCard.kt', 'fuelcard'], ['ui/Theme.kt', 'theme'],
  ['ui/Gauges.kt', 'gauge'], ['MainActivity.kt', 'main'], ['TripService.kt', 'trip'], ['AppState.kt', 'state'],
  ['Models.kt', 'model'], ['Purchase.kt', 'buy'], ['Service.kt', 'svc'], ['Health.kt', 'health'], ['Drive.kt', 'drive'],
  ['Fuel.kt', 'fuel'], ['Blackbox.kt', 'bb'], ['Deep.kt', 'deep'], ['ObdDecoder.kt', 'obd'], ['ObdLink.kt', 'demo'],
  ['Elm327.kt', 'elm'], ['Transport.kt', 'link'], ['Garage.kt', 'gar'], ['Forum.kt', 'frm'], ['OpenAiClient.kt', 'ai'],
  ['AiConfig.kt', 'aicfg'], ['Catalog.kt', 'cat'],
]

const LANGS = [
  ['en', 'English (английский)', 'one/other'], ['es', 'Español (испанский)', 'one/other'], ['zh', '简体中文 (китайский упрощённый)', 'other'],
  ['fr', 'Français (французский)', 'one/other (one для 0 и 1)'], ['de', 'Deutsch (немецкий)', 'one/other'],
  ['uk', 'Українська (украинский)', 'one/few/many'], ['be', 'Беларуская (белорусский)', 'one/few/many'],
  ['kk', 'Қазақша (казахский)', 'other'], ['ky', 'Кыргызча (киргизский)', 'other'], ['uz', 'Oʻzbekcha (узбекский, латиница)', 'other'],
  ['tg', 'Тоҷикӣ (таджикский)', 'other'], ['tk', 'Türkmençe (туркменский, латиница)', 'other'], ['hy', 'Հայերեն (армянский)', 'other'],
  ['az', 'Azərbaycanca (азербайджанский, латиница)', 'other'], ['ro', 'Română (румынский, Молдова)', 'one/few/other'],
  ['ka', 'ქართული (грузинский)', 'other'],
]

const RULES = `Инфраструктура уже есть (файл ${REPO}\\app\\src\\main\\java\\io\\github\\sinsluhi\\obdai\\I18n.kt): функции верхнего уровня
tr(key, vararg args) и trPlural(key, n, vararg extra) в пакете io.github.sinsluhi.obdai, объект Tr (Tr.lang.locale — локаль для дат,
Tr.isRussian). Текст берётся из assets/i18n/ru.json по ключу, плейсхолдеры {0}, {1}…; для множественного числа ключи key.one / key.few / key.many.
ПРАВИЛА ВЫНОСА (соблюдать буквально, компилятора локально нет — каждая ошибка стоит круга CI):
1. Каждый русский строковый литерал, который видит пользователь (текст на экране, уведомления, голос, тосты, строки лога — их видно на экране «Лог»,
   подписи датчиков/мониторов/блоков, тексты ошибок), замени на tr("<prefix>_<смысл_латиницей_snake_case>"). Шаблоны "$x км" → tr("key", x) и в тексте
   ключа "{0} км"; "${a} и ${b}" → tr("key", a, b) с {0}, {1}. Выражения внутри ${…} передавай аргументами как есть.
2. Ручные склонения («ошибка/ошибки/ошибок») → trPlural("key", n) и три ключа key.one/key.few/key.many, в тексте {0} — число.
3. НЕ трогай: строки для сравнения/парсинга (contains("…"), regex, when по строкам), ключи Prefs/JSON, имена файлов, форматы SimpleDateFormat,
   промпты нейронке, названия марок/моделей, единицы измерения внутри данных с адаптера, «OBIDI AI». Locale("ru") у форматов дат → Tr.lang.locale.
   Если литерал оставляешь намеренно, допиши в конце строки комментарий // i18n-ignore.
4. const val с русским текстом → обычное val с get() = tr(...) либо функция; в data class/enum-конструкторах, где значение уходит в другие файлы
   (например Provider.title, Provider.hint), НЕ меняй сигнатуру — оставь литерал с // i18n-ignore, это сделаем отдельно.
5. В Compose не оборачивай tr() в remember { } (иначе смена языка не перерисует). tr() можно звать где угодно, он потокобезопасен.
6. Файлы пакета io.github.sinsluhi.obdai.ui должны импортировать: import io.github.sinsluhi.obdai.tr, import io.github.sinsluhi.obdai.trPlural,
   import io.github.sinsluhi.obdai.Tr (только те, что используешь). В корневом пакете импорт не нужен.
7. Ключи уникальны в пределах файла и начинаются с префикса файла. Одинаковый текст в файле — один ключ. Не переиспользуй ключи других файлов.
8. Итог по файлу запиши в tools/i18n/keys/<prefix>.json: объект {ключ: русский текст} со ВСЕМИ ключами, которые ты ввёл (включая формы .one/.few/.many),
   ensure_ascii=False, в UTF-8 без BOM. Русский текст — ровно тот, что был в коде (с теми же ё, кавычками «», знаками), только $x заменён на {n}.
9. После правок запусти: python tools/i18n/check.py — в строке твоего файла должно остаться 0 литералов (или только помеченные // i18n-ignore).
   Перечитай свои правки на баланс кавычек и скобок, экранирование ($ внутри tr-ключей не нужно), корректность аргументов.
Правь ТОЛЬКО свой файл и свой keys-файл. Ничего не коммить.`

const EXTRACT_SCHEMA = {
  type: 'object',
  properties: {
    file: { type: 'string' }, keys: { type: 'number' }, leftovers: { type: 'number' },
    ignored: { type: 'number', description: 'сколько литералов оставлено с i18n-ignore' },
    notes: { type: 'string', description: 'что было сложным, что оставлено для отдельного шага (кросс-файловые строки и т. п.)' },
  },
  required: ['file', 'keys', 'leftovers', 'ignored', 'notes'],
}

phase('Extract')
const extracted = (await parallel(FILES.map(([file, prefix]) => () =>
  agent(`Ты выносишь русские строки в локализацию в проекте OBD AI (${REPO}). Твой файл: app/src/main/java/io/github/sinsluhi/obdai/${file}, префикс ключей: "${prefix}".\n\n${RULES}\n\nСначала прочитай файл целиком, затем правь. Если файл длинный, работай последовательно сверху вниз, не пропуская литералов. Пиши по-русски.`,
    { label: `extract:${prefix}`, phase: 'Extract', schema: EXTRACT_SCHEMA })
))).filter(Boolean)
log(`вынесено файлов: ${extracted.length}/${FILES.length}, ключей: ${extracted.reduce((s, e) => s + e.keys, 0)}, остатков: ${extracted.reduce((s, e) => s + e.leftovers, 0)}`)

phase('Merge')
const MERGE_SCHEMA = {
  type: 'object',
  properties: { keys: { type: 'number' }, chunks: { type: 'number' }, leftovers: { type: 'number' }, notes: { type: 'string' } },
  required: ['keys', 'chunks', 'leftovers', 'notes'],
}
const merged = await agent(`Проект OBD AI, ${REPO}. Собери ru.json: запусти python tools/i18n/merge.py (конфликты ключей — переименуй ключ в одном из keys-файлов И в соответствующем Kotlin-файле, повтори). Затем python tools/i18n/check.py: посмотри остаток русских литералов по файлам; если где-то остались НЕпомеченные литералы, вынеси их сам по тем же правилам (${RULES.split('\n').slice(0, 4).join(' ')}) и добавь ключи в keys-файл того файла, снова merge. Потом python tools/i18n/split.py — напечатает число кусков. Также добавь в .gitignore строки tools/i18n/chunks/ и tools/i18n/parts/ (если их нет). Ничего не коммить. Верни числа. Отчёты агентов по файлам: ${JSON.stringify(extracted)}`,
  { label: 'merge', phase: 'Merge', schema: MERGE_SCHEMA })
log(`ru.json: ${merged.keys} ключей, кусков ${merged.chunks}, литералов вне tr(): ${merged.leftovers}`)

phase('Compile')
const CI_SCHEMA = {
  type: 'object',
  properties: {
    ok: { type: 'boolean' }, runId: { type: 'string' },
    errors: { type: 'array', items: { type: 'object', properties: { file: { type: 'string' }, line: { type: 'number' }, text: { type: 'string' } }, required: ['file', 'line', 'text'] } },
  },
  required: ['ok', 'runId', 'errors'],
}
const ciPrompt = (msg) => `Проект OBD AI, ${REPO}. Закоммить и собрать в CI (компилятор только там). Шаги в Bash (Git Bash, gh лежит по пути ${GH}):
cd /c/Users/logik/obd-ai && git add -A && git commit -q -F <файл с сообщением> (сообщение: «${msg}», последняя строка «${TRAILER}»; сообщение пиши через Write в файл, heredoc с кириллицей ломается) && git pull -q --rebase origin main && git push -q origin main
Подожди 30 с, возьми id последнего запуска: ${GH} run list -R Sin-sluhi/obd-ai --workflow build.yml -L 1 --json databaseId --jq '.[0].databaseId'; затем ${GH} run watch <id> -R Sin-sluhi/obd-ai --exit-status.
Если сборка упала: ${GH} run view <id> -R Sin-sluhi/obd-ai --log-failed | grep "e: file" — верни КАЖДУЮ ошибку как {file (путь относительно корня репозитория), line, text}. Сам ничего не чини. Если нечего коммитить (нет изменений), просто собери/проверь последний запуск.`
let ci = null
for (let round = 0; round < 8; round++) {
  ci = await agent(ciPrompt(round === 0 ? 'i18n: русские строки вынесены в tr(), assets/i18n/ru.json' : `i18n: починка компиляции, круг ${round}`), { label: `ci:${round}`, phase: 'Compile', schema: CI_SCHEMA })
  if (!ci || ci.ok) break
  const byFile = {}
  for (const e of ci.errors) (byFile[e.file] = byFile[e.file] || []).push(e)
  log(`круг ${round}: ошибок ${ci.errors.length} в ${Object.keys(byFile).length} файлах`)
  await parallel(Object.entries(byFile).map(([file, errs]) => () =>
    agent(`Проект OBD AI, ${REPO}. Почини ошибки компиляции Kotlin в файле ${file} (появились после выноса строк в tr(); правила выноса: ${RULES}). Ошибки:\n${JSON.stringify(errs, null, 1)}\nПрочитай файл вокруг каждой строки, исправь причину (не удаляй функциональность, не возвращай русские литералы — если нужен новый ключ, добавь его в tools/i18n/keys/<prefix>.json и в app/src/main/assets/i18n/ru.json). Ничего не коммить. Верни краткий список что поменял.`,
      { label: `fix:${file.split('/').pop()}`, phase: 'Compile' })))
}
if (!ci || !ci.ok) log('ВНИМАНИЕ: компиляция не позеленела за 8 кругов — остановка перед переводом')
if (!ci || !ci.ok) return { extracted, merged, ci }

phase('Translate')
const chunkIds = Array.from({ length: merged.chunks }, (_, i) => String(i + 1).padStart(2, '0'))
const jobs = []
for (const [code, name, plural] of LANGS) for (const id of chunkIds) jobs.push({ code, name, plural, id })
const T_SCHEMA = { type: 'object', properties: { code: { type: 'string' }, chunk: { type: 'string' }, keys: { type: 'number' } }, required: ['code', 'chunk', 'keys'] }
const translated = await pipeline(jobs, j =>
  agent(`Переведи кусок интерфейса Android-приложения OBD AI (диагностика автомобиля через ELM327; тон — простой, дружелюбный, без канцелярита) с русского на язык: ${j.name} (код ${j.code}).
Вход: ${REPO}\\tools\\i18n\\chunks\\${j.id}.json — объект {ключ: русский текст}. Выход: ${REPO}\\tools\\i18n\\parts\\${j.code}\\${j.id}.json — объект с ТЕМИ ЖЕ ключами и переводами (создай папку, UTF-8 без BOM, ensure_ascii=False, валидный JSON).
Правила: плейсхолдеры {0}, {1}… сохраняй все и ровно столько же; коды ошибок (P0300), VIN, названия марок/моделей, единицы вроде км/ч, В, °C переводи как принято в языке (km/h, V), «OBIDI AI» не переводить; переносы \\n сохраняй; длину держи близкой к русской (это мобильный интерфейс). Множественное число: у русского ключи key.one/key.few/key.many; у этого языка формы: ${j.plural} — оставь только нужные формы этого языка (например для one/other → key.one и key.other; для other → только key.other; для one/few/many → все три; для one/few/other → key.one, key.few, key.other), {0} — число. Числа в примерах не переводи. Не добавляй ключей, которых нет во входе. Верни число ключей.`,
    { label: `tr:${j.code}:${j.id}`, phase: 'Translate', schema: T_SCHEMA }))
log(`переведено кусков: ${translated.filter(Boolean).length}/${jobs.length}`)

phase('Verify')
const V_SCHEMA = { type: 'object', properties: { code: { type: 'string' }, keys: { type: 'number' }, problems: { type: 'number' }, notes: { type: 'string' } }, required: ['code', 'keys', 'problems', 'notes'] }
const verified = (await parallel(LANGS.map(([code, name]) => () =>
  agent(`Проект OBD AI, ${REPO}. Собери и проверь перевод на ${name} (код ${code}): python tools/i18n/merge.py --lang ${code}, затем python tools/i18n/check.py. В строке ${code}.json должно быть: нет 0, пустых 0, плейсхолдеры 0. Если есть проблемы — почини в app/src/main/assets/i18n/${code}.json (недостающие ключи переведи сам, плейсхолдеры выровняй по русскому из ru.json), повтори проверку. Ничего не коммить. Верни число ключей и число оставшихся проблем.`,
    { label: `verify:${code}`, phase: 'Verify', schema: V_SCHEMA })))).filter(Boolean)
log(`языков проверено: ${verified.length}, проблем: ${verified.reduce((s, v) => s + v.problems, 0)}`)

const finalCi = await agent(ciPrompt('i18n: переводы на 16 языков (5 мировых + СНГ)'), { label: 'ci:final', phase: 'Verify', schema: CI_SCHEMA })
return { extracted, merged, translated: translated.filter(Boolean).length, verified, finalCi }
