export const meta = {
  name: 'obdai-i18n-translate',
  description: 'Перевод OBD AI на 16 языков по кускам ru.json, проверка каждого языка, финальный пуш и сборка',
  phases: [
    { title: 'Translate', detail: '16 языков × куски по 250 ключей' },
    { title: 'Verify', detail: 'merge --lang, check.py, починка, финальный push и сборка' },
  ],
}

const REPO = 'C:\\Users\\logik\\obd-ai'
const GH = '"/c/Program Files/GitHub CLI/gh.exe"'
const TRAILER = 'Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>'
const CHUNKS = (args && args.chunks) || 5

const LANGS = [
  ['en', 'English (английский)', 'one/other'], ['es', 'Español (испанский)', 'one/other'], ['zh', '简体中文 (китайский упрощённый)', 'other'],
  ['fr', 'Français (французский)', 'one/other (one для 0 и 1)'], ['de', 'Deutsch (немецкий)', 'one/other'],
  ['uk', 'Українська (украинский)', 'one/few/many'], ['be', 'Беларуская (белорусский)', 'one/few/many'],
  ['kk', 'Қазақша (казахский)', 'other'], ['ky', 'Кыргызча (киргизский)', 'other'], ['uz', 'Oʻzbekcha (узбекский, латиница)', 'other'],
  ['tg', 'Тоҷикӣ (таджикский)', 'other'], ['tk', 'Türkmençe (туркменский, латиница)', 'other'], ['hy', 'Հայերեն (армянский)', 'other'],
  ['az', 'Azərbaycanca (азербайджанский, латиница)', 'other'], ['ro', 'Română (румынский, Молдова)', 'one/few/other'],
  ['ka', 'ქართული (грузинский)', 'other'],
]

phase('Translate')
const chunkIds = Array.from({ length: CHUNKS }, (_, i) => String(i + 1).padStart(2, '0'))
const jobs = []
for (const [code, name, plural] of LANGS) for (const id of chunkIds) jobs.push({ code, name, plural, id })
const T = { type: 'object', properties: { code: { type: 'string' }, chunk: { type: 'string' }, keys: { type: 'number' } }, required: ['code', 'chunk', 'keys'] }
const translated = await pipeline(jobs, j =>
  agent(`Переведи кусок интерфейса Android-приложения OBD AI (диагностика автомобиля через ELM327; тон простой, дружелюбный, без канцелярита) с русского на язык: ${j.name} (код ${j.code}).
Вход: ${REPO}\\tools\\i18n\\chunks\\${j.id}.json — объект {ключ: русский текст}. Выход: ${REPO}\\tools\\i18n\\parts\\${j.code}\\${j.id}.json — объект с ТЕМИ ЖЕ ключами и переводами (создай папку; UTF-8 без BOM; валидный JSON; пиши файл одним Write).
Правила: плейсхолдеры {0}, {1}… сохраняй все и ровно столько же; коды ошибок (P0300), VIN, названия марок/моделей не переводить; единицы (км/ч, В, °C, л/ч, об/мин) — как принято в языке; «OBIDI AI», «OBD AI», «Check Engine», «ELM327» не переводить; переносы \\n сохраняй; длину держи близкой к русской (мобильный интерфейс). Множественное число: у русского ключи вида key.one/key.few/key.many; формы этого языка: ${j.plural} — оставь только нужные (one/other → key.one и key.other; other → только key.other; one/few/many → все три; one/few/other → key.one, key.few, key.other), {0} — число. Не добавляй ключей, которых нет во входе, не пропускай ни одного. Не читай другие файлы. Верни число ключей.`,
    { label: `tr:${j.code}:${j.id}`, phase: 'Translate', schema: T, effort: 'low' }))
log(`переведено кусков: ${translated.filter(Boolean).length}/${jobs.length}`)

phase('Verify')
const V = { type: 'object', properties: { code: { type: 'string' }, keys: { type: 'number' }, problems: { type: 'number' } }, required: ['code', 'keys', 'problems'] }
const verified = (await parallel(LANGS.map(([code, name]) => () =>
  agent(`Проект OBD AI, ${REPO}. Собери и проверь перевод на ${name} (код ${code}): в Bash cd /c/Users/logik/obd-ai && python tools/i18n/merge.py --lang ${code} && python tools/i18n/check.py. В строке ${code}.json должно быть: нет 0, пустых 0, плейсхолдеры 0. Если есть проблемы — почини их прямо в app/src/main/assets/i18n/${code}.json (недостающие ключи переведи сам по ru.json, плейсхолдеры выровняй), повтори проверку. Ничего не коммить. Верни число ключей и оставшихся проблем.`,
    { label: `verify:${code}`, phase: 'Verify', schema: V, effort: 'low' })))).filter(Boolean)
log(`языков: ${verified.length}, проблем: ${verified.reduce((s, v) => s + v.problems, 0)}`)

const CI = { type: 'object', properties: { ok: { type: 'boolean' }, note: { type: 'string' } }, required: ['ok', 'note'] }
const ci = await agent(`Проект OBD AI, ${REPO}. Закоммить переводы и собрать. Bash (Git Bash; gh: ${GH}): запиши через Write в C:\\Users\\logik\\AppData\\Local\\Temp\\wfmsg.txt сообщение «i18n: переводы на 16 языков (5 мировых + СНГ)» с последней строкой «${TRAILER}»; затем cd /c/Users/logik/obd-ai && git add app/src/main/assets/i18n && git commit -q -F /c/Users/logik/AppData/Local/Temp/wfmsg.txt && git pull -q --rebase origin main && git push -q origin main. Подожди 30 с; id: ${GH} run list -R Sin-sluhi/obd-ai --workflow build.yml -L 1 --json databaseId --jq '.[0].databaseId'; ${GH} run watch <id> -R Sin-sluhi/obd-ai --exit-status. Верни ok и короткую заметку (если упало — первые строки ошибок).`,
  { label: 'ci:final', phase: 'Verify', schema: CI })
return { translated: translated.filter(Boolean).length, verified, ci }
