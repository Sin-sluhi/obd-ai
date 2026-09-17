# Идеи функций для OBD AI из анализа конкурентов

Дата анализа: 17.09.2026. Просмотрено ~40 приложений и инструментов: Torque Pro, Car Scanner, OBD Fusion,
OBDLink, DashCommand, OBD Auto Doctor, EOBD Facile, inCarDoc, Infocar, Piston, MotorData, ScanMaster,
Carly, Carista, OBDeleven, BimmerLink, FORScan, AlfaOBD, GMProg, ThinkDiag, Launch, Dr. Prius, LeafSpy,
VAG DPF, Hondash, OpenDiag Mobile, Lada Diag, Multitronics, FIXD, BlueDriver, Innova, CarMD, MECH AI,
OBDAI/ARIA, Skanyx, Fixlith, DashOrNOT, EngineEar, Автотека, Авто.ру, Дром, AutoPulse (4pda).

Отбор: только редкие и умные фичи. Базовое (чтение кодов, приборы, freeze frame) пропущено.
Уже есть в OBD AI и потому не в списке: DTC по всем блокам, VIN, датчики, коррекции, поездки, история,
вердикт с ценами и ссылками на drive2/drom, консоль, шаринг, демо.

Обозначения: **ELM v1.5** — работает ли на дешёвом клоне (да / частично / нет).
**Правила** — не противоречит ли правилам продукта (магия, одна кнопка, факты с форумов).

---

## Топ-12: что брать в первую очередь

| # | Фича | Откуда | Почему для нас | ELM v1.5 | Объём |
|---|---|---|---|---|---|
| 1 | **Счётчики ошибок: сколько раз, когда последний раз, сколько циклов назад** (UDS 19 06 extended data: occurrence/aging counter; 19 04 snapshot с пробегом) | VCDS, Carly, ISO 14229 | У нас уже есть 19 02. Вердикт станет честнее: «архив, 1 раз, 40 циклов назад» против «11 раз за месяц». Прямо кормится в промпт | да | малый |
| 2 | **Пробег из каждого блока + VIN из каждого блока (22 F190)** → проверка скрутки и «донорских» блоков | Carly Used Car Check, OpenDiag (ABS на Vesta) | Address sweep уже есть, нужны DID. В РФ каждая пятая машина скручена. Ни у одного западного приложения нет связки с Автотекой | частично (DID по маркам) | средний |
| 3 | **Режим «Проверка перед покупкой»**: пробег по блокам + VIN-согласованность + архив ошибок с возрастом + отзывные (easy.gost.ru) + вердикт «брать / торговаться / бежать» | Carly, Автотека AI, Дром Ассист, OPENLANE Code Boost IQ | Одна кнопка, готовый вердикт, сильный повод скачать приложение перед осмотром машины | да | средний |
| 4 | **Здоровье АКБ и генератора по PID 0x42**: фазы прокрутка / езда / заряд, просадка при пуске, история 30 дней, предупреждение «батарея умирает» | OBD2 Car Scanner (Atalay), патент Repairify US 12097777 | Без доп. железа, спрос огромный, данные уже опрашиваются | да | малый |
| 5 | **Режим 06 с пропусками по цилиндрам + IUPT-счётчики** (результаты самотестов ЭБУ против порогов) | OBD Auto Doctor, ScanMaster, OBDAI | Почти никто в мобильных не показывает по-человечески. Даёт вердикту «цилиндр 3 троит» до появления кода | да | малый |
| 6 | **«Помогло ли?»: после сброса ошибок следить за readiness-мониторами и повторным появлением кода, затем автоматически пересобрать вердикт** | OBD Auto Doctor (since clear / this cycle), FIXD Emissions Precheck, Innova before/after | Закрывает цикл «нашли → починили → проверили» без ручных шагов | да | малый |
| 7 | **Прогноз «что сломается дальше» по модели и пробегу** из drive2/drom (типовые болячки на 100–150 тыс.) | FIXD Issue Forecast (650 млн сканов), Carly related faults | Прямое продолжение правила «факты с форумов»; можно даже без ошибок, по VIN и пробегу | да | средний (промпт + поиск) |
| 8 | **Эталон «здоровой машины» и сравнение с самой собой**: дрейф коррекций, температуры, напряжения между проверками → аномалия до кода | Skanyx baseline, Carly Vista, Fixlith | История уже хранится; нужен только diff и строка в промпте | да | малый |
| 9 | **Сервисные функции Hyundai/Kia, подтверждённые на ELM v1.5**: сброс адаптации АКПП и ЭБУ, прокачка топливной CRDi, интервал ТО. За флагом «экспериментальные» | Car Scanner, GaragePro, drive2 (Grand Santa Fe) | У дилера платно, владельцы Tucson ищут именно это. Sweep 7A0–7DF уже есть | да | средний |
| 10 | **Фото приборки → какая лампа горит → вердикт** (модель со зрением) | DashOrNOT, CarSight, MECH Vision | Работает без адаптера, вход в приложение для тех, у кого ELM ещё нет. У Groq есть бесплатные vision-модели | да | малый |
| 11 | **Итоговая цена «привести машину в порядок»** (сумма по кодам) + **заявка в сервис**: что сказать, что не давать менять | BlueDriver Total Cost, Tesla self-diagnosis, Service Buddy | priceFrom/priceTo уже есть в карточках, сложить и оформить | да | малый |
| 12 | **Тест адаптера**: настоящий ли ELM, какие AT-команды и протоколы поддерживает, что не будет работать | EOBD Facile, FORScan (уровни D/C/P), ELM327 Test | Снимает половину вопросов «не подключается / не читает блоки» | да | малый |

---

## Полный список по категориям

### 1. Глубокая диагностика (стандартный OBD и UDS)

- **Occurrence / aging counters, дата и пробег ошибки** — VCDS показывает Priority, Frequency, Reset counter; по UDS это 19 06. Отличает единичный глюк от хронической болезни. ELM: да.
  https://www.ross-tech.com/vcds/tour/dtc_screen.php , https://uds.readthedocs.io/en/latest/pages/knowledge_base/dtc.html
- **Режим 06: misfire per cylinder, результаты тестов катализатора, EVAP, лямбд** — OBD Auto Doctor расшифровывает TID/CID в текст. ELM: да.
  https://www.obdautodoctor.com/tutorials/using-obd2-mode-06-for-advanced-car-diagnostics/
- **IUPT-счётчики** (сколько раз система прошла тест / сколько было условий) — OBD Auto Doctor. ELM: да.
  https://www.obdautodoctor.com/tutorials/understanding-in-use-performance-tracking-counters/
- **Readiness в двух группах: «с момента сброса» и «в этом ездовом цикле»** — OBD Auto Doctor. ELM: да.
  https://www.obdautodoctor.com/features/
- **Двунаправленные команды по стандарту (режим 08)**: тест утечки EVAP, регенерация сажевого, сброс AdBlue — OBD Auto Doctor. ELM: да.
  https://www.obdautodoctor.com/features/
- **Режим 05: тесты кислородных датчиков** — ScanMaster. ELM: да.
- **Гистограмма распределения любого PID + min/avg/max за сессию** — OBD Auto Doctor. Ловит плавающие проблемы. ELM: да.
- **Сниффер CAN-шины в приложении** — EOBD Facile. ELM: частично.
  https://www.klavkarr.com/software-eobd-facile-elm327.php
- **Справочник контрольных ламп приборки** (147 ламп Toyota) — MotorData плагин Car Indicators.
  https://motordata.net/en_en/projects/motorData-OBD2-mobile-app/
- **Цветовая карта модулей** вместо списка (Topology) — Autel MaxiSys S2. У нас 10 блоков на Tucson, просится схема.
  https://www.vxdas.com/blogs/news/how-ai-assisted-repair-guidance-is-reshaping-independent-diagnostic-workflows-in-2026

### 2. Проверка б/у машины

- **Пробег из 4+ блоков, красный флаг при расхождении** — Carly. ELM: частично.
  https://www.mycarly.com/blog/car-check/detect-odometer-rollback/
- **Правдоподобие пробега по моточасам** (средняя скорость = пробег / моточасы) — Carly. На Lada M74/M86 моточасы есть.
  https://www.mycarly.com/blog/car-check/how-to-detect-odometer-fraud/
- **VIN в каждом блоке (22 F190)** → выявление замененных после ДТП блоков — Carly. ELM: да.
- **Пробег из приборки Hyundai/Kia**: кластер 0x720, DID 22 B002 на 7C6 у EV/HEV — форумы.
  https://www.hyundai-forums.com/threads/obdii-elm327-software.472794/page-2 , https://github.com/JejuSoul/OBD-PIDs-for-HKMC-EVs
- **Пробег из ABS как эталон на Lada Vesta** (ABS не скручивают) — OpenDiag, drive2.
  https://www.drive2.ru/l/573588004371695232/
- **Интеграция VIN-истории**: Автотека API, АвтоПроверка (ДТП, владельцы, залоги, история пробега по объявлениям).
  https://autoteka.ru/forpartners , https://avtoproverka.com/api/
- **Отзывные кампании по VIN** — easy.gost.ru, Дром.
  https://easy.gost.ru/ , https://vin.drom.ru/proverka/otzyvnyye-kompanii/
- **Сравнение машины с одноклассниками того же года по пробегу и ДТП** — Автотека.
  https://lifehacker.ru/avtoteka-sravnenie-probega-i-dtp/
- **Код ≠ ремонт: обучение на исходах** (какие коды реально привели к ремонту) — OPENLANE Code Boost IQ.
  https://corporate.openlane.com/openlane-transforms-condition-reports-with-actionable-obd2-intelligence/
- **Сертификат теста батареи для сделки** — Dr. Prius печатает документ покупателю.
  https://apkpure.com/dr-prius-dr-hybrid/com.nexcell.app

### 3. Слой ИИ и данных (то, что нам ближе всего)

- **Issue Forecast: прогноз проблем по модели и пробегу** — FIXD Premium (650 млн сканов).
  https://cartipsdaily.com/is-fixd-worth-it
- **Predicted Repairs на 12 месяцев + стоимость владения 5 лет**, работает даже без сканера по VIN и пробегу — Innova RS2.
  https://www.prnewswire.com/news-releases/innova-releases-redesigned-repairsolutions2-app-to-benefit-more-vehicle-owners-301592570.html
- **Related faults и предсказание следующей ошибки** — Carly Smart Mechanic.
  https://support.mycarly.com/hc/en-us/articles/360021843859-What-is-Carly-Smart-Mechanic
- **Отклонение от «ритма машины» = будущий отказ**, срочность, последствия, если не чинить — Carly Vista.
  https://enterprise.mycarly.com/carly-solution-predictive-maintenance.html
- **Предсказание отказа АКБ, топливного насоса, стартера** — GM OnStar Proactive Alerts.
  https://www.onstar.com/support/faq/advanced-diagnostics
- **Адаптивный интервал ТО по реальной эксплуатации** (короткие поездки, стоп-старт) — Hyundai Bluelink.
- **Топ-3 гипотезы с ценой от/до, нормо-часами и последствиями** — Carly Repair Costs.
  https://www.motorverso.com/carly-repair-cost-feature/
- **Время и сложность ремонта («45 минут, рутина»)** — Carly Virtual AI Mechanic.
  https://enterprise.mycarly.com/en/carly-enterprise-virtual-ai-mechanic/
- **Ранжирование фиксов по частоте реальных исходов** (Top / Frequently / Other Reported Fixes) — BlueDriver Repair Reports. Наш аналог: статистика «что помогло» по drive2.
  https://obdadvisor.com/bluedriver/
- **Total Cost to Repair в отчёте** — BlueDriver MAX.
  https://www.bluedrivermax.com/newsroom
- **Артикулы и ссылки на покупку с проверкой применимости** — MECH AI. В РФ: Exist, Autodoc, Ozon.
  https://mechai.app/
- **Аудит сметы из сервиса**: фото сметы → построчная сверка с честными ценами, список вопросов механику — Service Buddy, Car AI, FairRide.
  https://www.servicebuddy.help/ , https://www.car-ai.app/
- **Дерево решений «что проверить до покупки детали»** — MECH AI. Снижает галлюцинации «просто замени датчик».
- **Пробег последнего появления ошибки + связанные ошибки в карточке** — Carly Smart Mechanic.
  https://www.fastcar.co.uk/tuning-tech-guides/carly-smart-mechanic-is-your-ultimate-car-companion/
- **Машина сама формирует заявку в сервис с пунктами из бюллетеней** — Tesla.
  https://electrek.co/2022/07/18/tesla-new-self-diagnostic-feature-app/
- **Память о машине между сканами**: что нашли, что починили, от чего отказались — OBDAI/ARIA.
  https://obdai.app/
- **ИИ сам выбирает, какие PID писать во время тест-драйва под гипотезу** — OBDAI/ARIA. Агентный подход: вердикт → гипотеза → нужные датчики → поездка → подтверждение.
- **Честный health score только из режима 06 и readiness, без «выдуманных» баллов** — OBDAI. Контраст: Skanyx / Fixlith рисуют 0–100 по подсистемам.
  https://skanyx.com/blog/best-obd2-scanner-apps-2025
- **Baseline-скан здоровой машины** для сравнения — Skanyx.
- **Живой механик как второй уровень после ИИ** — FIXD Mechanic Hotline, Autobrain. В РФ: партнёрская СТО (FIT SERVICE, онлайн-запись).
  https://www.rustore.ru/catalog/app/com.garpix.fitapp
- **ML-оценка рыночной стоимости своей машины** — Авито Гараж. Связка «сколько стоит машина и как повлияет неремонт».
  https://www.cnews.ru/news/line/2025-11-06_nejroseti_avito_pomogut
- **Отзывы владельцев по 17 параметрам** — Авто.ру AI. Готовая схема структурирования болячек по годам.
  https://vc.ru/autoru/2309008-iskusstvennyj-intellekt-avto-ru-kak-vybrat-i-prodat-avtomobil

### 4. Мультимодальность

- **Фото приборки → лампы, серьёзность, «можно ли ехать»** — DashOrNOT, CarSight. DashOrNOT без бэкенда вообще.
  https://dashornot.com/ , https://carsight.net/en/dashboard-lights
- **Фото детали или повреждения → оценка ремонта** — MECH Vision.
- **ИИ сам просит фото, когда это поможет** — OBDAI/ARIA.
- **Звук двигателя 5–10 с → причины** (19 состояний: стук шатуна, ступица, колодки, выхлоп) — EngineEar, Fix My Car Sound. Единственные данные, которых нет в OBD; сочетается со «стук при…» из форумов. Точность спорная.
  https://play.google.com/store/apps/details?id=com.engineear.ai , https://fixmycarsound.com/
- **Камера на моторный отсек → где щуп, фильтр, пробка** — Gemini Live.
- **Голос: «запусти проверку» с грязными руками под капотом** — Autel MaxiSys.
- **Надиктовать, что сказал механик → смета и оценка** — Car AI.

### 5. Сервисные функции и кодирование через ELM327

Hyundai/Kia (подтверждено владельцами на клонах v1.5):
- **Сброс адаптации АКПП A4CF / A6MF / A6GF / A6LF** — Car Scanner, drive2 Grand Santa Fe, my-elantra.ru.
  https://www.carscanner.info/hyundai-kia-service-functions/ , https://www.drive2.ru/l/597337180653759769/
- **Сброс адаптаций ЭБУ двигателя** — Car Scanner, GaragePro.
- **Прокачка топливной CRDi после замены фильтра** — Car Scanner Pro.
- **Интервал ТО: км, месяцы, тип, сброс напоминания** — Car Scanner.
- **Прокачка тормозов через HCU** (5 платформ) — Car Scanner, GaragePro. ELM: частично.
- **Калибровка датчика угла руля (SAS), обучение ЭУР** — GaragePro. ELM: частично.
- **Актуаторные тесты ABS поколёсно, тест герметичности топливной рампы** — GaragePro. ELM: частично.
- **Регистрация нового АКБ (IBS)** для AGM/Start-Stop — BimmerLink, OBDeleven, Autel. ELM: частично.

Другие марки (для расширения):
- **Сброс деградации масла CVT** (Nissan JF011E, Mitsubishi Outlander) — Car Scanner.
- **Сброс адаптации дросселя GM Sirius D42, service reminder GM** (Cruze, Aveo, Lacetti) — Car Scanner.
  https://www.carscanner.info/coding/
- **Регенерация DPF стоя и в движении, дата последней регенерации, масса золы с трендом** — Car Scanner, BimmerLink, VAG DPF.
  https://www.hypermiler.co.uk/dpf-diesel-particulate-filter/vag-dpf-review-andriod-vag-dpf-monitor-app
- **Сервисный режим электроручника (EPB)** — BimmerLink, FORScan.
- **Комфорт-кодировки: пищалка ремня, автозапирание, DRL, зеркала, окна с брелка** — Carista (300+ на марку), Car Scanner (Toyota).
  https://carista.com/en-us
- **One-Click Apps: сообщество пишет кодировки** — OBDeleven OCA builder.
  https://obdeleven.com/one-click-apps
- **Скрытые вкладки приборки, русификация** — GMProg. ELM: частично (SW-CAN).
- **Тумблеры гибридов: пищалка заднего хода, скорость вентилятора ВВБ, maintenance mode** — Dr. Prius.
- **Активные тесты старых Toyota 1998–2010** — ELMScan Toyota.

Обязательные паттерны, если делать любую запись в блоки:
- **Флаг «экспериментальные функции» + предупреждение о клонах** — Car Scanner.
- **Авто-бэкап конфигурации перед записью и откат в сток** — GMProg, BimmerCode.
- **Градация адаптера по возможностям (D / C / P)** — FORScan.
  https://forscan.org/forum/viewtopic.php?t=6142

### 6. Гибриды и электромобили

- **Открытые PID Hyundai/Kia HEV/PHEV/EV**: SOC BMS, SOC display, SOH, поячеечные напряжения, 12V — JejuSoul на GitHub, готово к встраиванию.
  https://github.com/JejuSoul/OBD-PIDs-for-HKMC-EVs
- **Life Expectancy Test и внутреннее сопротивление блоков ВВБ**, платный тест как in-app purchase — Dr. Prius.
  https://priusapp.com/
- **96 пар ячеек, индикатор балансировки, Hx (проводимость), счётчики быстрых зарядок** — LeafSpy Pro. Эталон визуализации.
  https://www.electricvehiclewiki.com/leaf-spy-pro/
- **Role Override: переназначить, какой PID играет роль «HV Current»** для расчётов — Car Scanner. Умный приём для универсальных формул.
  https://www.macheforum.com/site/threads/tips-on-using-carscanner-elm-app-with-obd2-for-custom-data-display-ver-1-87-1-and-later.12627/
- **EV Battery Health Analysis** — Carista (beta).

### 7. Здоровье 12В АКБ

- **Battery Health Monitor**: тренд напряжения, автоклассификация фаз прокрутка / езда / заряд, 30 дней min/max/avg, алерт до отказа — OBD2 Car Scanner (Metin Atalay).
  https://apps.apple.com/us/app/obd2-car-scanner-elm-327-dtc/id6759842542
- **Предсказание недозаряда генератора по времени между пиком при пуске и пиком заряда** — патент US 12097777. Реализуемо на PID 0x42 + время.
  https://patents.justia.com/patent/12097777
- **Калибруемый вольтметр** (клоны врут на 0.2–0.5 В) — ScanMaster, LeafSpy.

### 8. Расширенные брендовые параметры

- **Hyundai/Kia: температура ATF, детонация по цилиндрам, ~15 параметров** — CarBit, Advanced EX for KIA/HYUNDAI.
  https://play.google.com/store/apps/details?id=com.ideeo.kyadvanced
- **Список «неочевидных» блоков Hyundai/Kia по CAN**: CUBIS, keyless, сиденья, blind spot, lane keeping, EPB — MotorData плагины. Помогает назвать 7C3.
  https://motordata-obd.com/plagin-hyundai/
- **Баланс по цилиндрам на ЭБУ ВАЗ** — Lada Diag (RuStore).
  https://www.rustore.ru/catalog/app/ru.ALKmk.vazdiag
- **VTEC-дистанция, мультибак бензин/LPG** — Hondash. LPG актуален в РФ.
  https://www.hondash.net/p/faq.html

### 9. Lada / ГАЗ / УАЗ

- **Управление исполнительными механизмами**: форсунки, РХХ, вентилятор, бензонасос, лампа CE — OpenDiag Mobile (30+ ЭБУ: Январь, Bosch M7.9.7 / M17.9.7, Ителма M74 / M86, Sirius, Микас, СОАТЭ). ELM: да, но оригинальный.
  https://www.opendiag.pro/android
- **Немоторные блоки Lada**: ABS Bosch 5.3–9.1, подушки Autoliv / Takata, АМТ ZF, Jatco, ЭУР, климат, электропакет, приборка Гранта — OpenDiag. Адреса для нашего sweep.
- **Иммобилайзер: диагностика и обучение ключей, сброс ЭБУ с инициализацией** — OpenDiag, MotorData. ELM: частично (K-line).
  https://www.drive2.ru/b/1635173/
- **Сушка свечей, порог включения вентилятора** — Multitronics (K-line ВАЗ). Никто из мобильных не повторил.
  https://www.multitronics.ru/catalog/bortovye_kompyutery/ux10/
- **Инициализация УАЗ Bosch ME17.9.7** — OpenDiag.

### 10. Поездки, замеры, телеметрия

- **Replay поездки: карта + графики синхронно, экспорт CSV** — Car Scanner. У нас есть запись, нет воспроизведения.
  https://www.carscanner.info/category/features/
- **Маршрут на карте, раскрашенный по PID (расход, скорость), место парковки** — OBD Fusion.
  https://www.obdsoftware.net/software/obdfusion
- **Driving score: безопасность и эко, события на карте** — Infocar, Vyncs. Монетизация через скидку на КАСКО (Т-Драйв до 25–30 %).
  https://apkpure.com/infocar-obd2-elm-scanner/mureung.obdproject , https://sapportum.ru/blog/kasko-telematika-skidka-25
- **0–100, 1/4 мили с промежуточными сплитами, торможение 100–0, личный рекорд** — OBDLink, Car Scanner.
  https://support.obdlink.com/support/solutions/articles/43000712517-get-started-with-performance
- **Дино: мощность и момент по массе и ускорению** — Torque Pro.
- **Skid pad (боковые G), инклинометр для оффроуда, круги трека** — DashCommand.
  https://apps.apple.com/us/app/dashcommand-obd-ii-gauges/id321293183
- **Детонация из стандартных PID** (timing advance / throttle / RPM) — Knock Detector for Torque.
- **Дисбаланс амортизаторов и тормозной путь по IMU телефона** — OBDAssistant.
  https://www.obd2assistant.com/
- **Расход через volumetric efficiency для машин без MAF** — inCarDoc Economizer. Закрывает нашу открытую задачу.
  https://apkpure.com/incardoc-pro-elm327-obd2/com.pnn.obdcardoctor_full
- **Overlay расхода поверх навигатора** — inCarDoc.
- **Условные триггеры записи и голосовые алармы** («антифриз выше 110») — DashCommand, Torque.
  https://xdaforums.com/t/torque-pro-speech-alert.4534885/
- **Видеорегистратор с наложением OBD и GPS** — Track Recorder (Torque), Infocar.
- **Разметка поездок «работа / личная» свайпом** — nonda ZUS.

### 11. Интеграции и экосистема

- **Android intents для Tasker: подключился, отключился, сработал аларм** — Torque Pro.
  https://wiki.torque-bhp.com/view/PluginDocumentation
- **HTTP / MQTT телеметрия на свой сервер** — Torque, torque-dash на GitHub.
  https://github.com/davekrejci/torque-dash
- **OBD2 Repeater: один телефон держит адаптер, магнитола и планшет через него** — Torque плагин. Решает нашу боль «Car Scanner держит адаптер».
  https://torque-bhp.com/community/main-forum/multiple-tablets-for-gauges/
- **Android Auto** — OBD Fusion.
- **HUD с зеркальным режимом** — Car Scanner.
- **Виджеты на домашнем экране** — Torque.
- **Профили подключения, обновляемые отдельно от приложения** — Car Scanner. Поддержка новых машин без релиза.
  https://www.carscanner.info/profile-database-changelog/
- **Облачный бэкап истории по логину** — Piston.
- **Запись к партнёрской СТО из отчёта** — Innova + RepairPal. В РФ: FIT SERVICE.
- **OCR чека или заказ-наряда → запись в журнал ТО** — VinSnap, Vehicle Logbook (локальный OCR).
  https://vinsnap.net/maintenance-tracker
- **Единая лента: ремонты, диагностики, ТО** — MECH AI Vehicle Timeline.
- **Гараж с напоминаниями ТО и PDF-отчётом** — OBD2 Car Scanner (Atalay).

---

## Конкуренты, за которыми стоит следить

- **AutoPulse** (4pda): был прямым российским конкурентом с ИИ-анализом (пакет com.autopulse.obd2, версия 2.0.1). По состоянию на 17.09.2026 тема на 4pda закрыта «нет активности», сайт autome.ru не отвечает. Проект, судя по всему, умер: ниша свободна.
  https://4pda.to/forum/index.php?showtopic=1122051
- **OBDAI / ARIA**: ближайший по философии (агент, память, честный health score, режим 06).
  https://obdai.app/
- **OBD2 Car Scanner ELM 327 DTC** (Metin Atalay): ближайший по набору (гараж, батарея, PDF, демо).
- **Carly**: эталон Used Car Check и Smart Mechanic.

Наблюдение: репозиторий Sin-sluhi/obd-ai уже всплывает в поиске рядом с 4pda. Стоит проверить, что README не раскрывает лишнего.

## Чего не делать (по правилам продукта)

- Не вводить чат «спроси ИИ» как отдельный шаг. Уточняющий вопрос допустим только как кнопка внутри готового вердикта.
- Не рисовать «здоровье 0–100» из воздуха. Если и делать балл, то только из режима 06, readiness и коррекций, с пояснением, откуда.
- Не давать запись в блоки без флага «экспериментально», бэкапа и проверки адаптера.
- Запись пробега и программирование ключей (AlfaOBD) — только чтение, никогда не запись.
