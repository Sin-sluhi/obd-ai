<!-- Спецификация анимаций OBD AI. Получена конкурсом концепций 27.09.2026 (4 концепции, 3 судьи, синтез). Реализация: ui/Motion.kt и точки подключения из раздела 4. -->

# Спецификация: «Пульс шины» — живой фон и микроанимации OBD AI

Победитель — «Пульс шины» (осциллограф на CAN-шине). Привито: дышащая подсветка приборки и проход света поверх карточек (из «Света по стеклу»), одни часы на всё приложение и цвет настроения (из «Пульса мотора»), механика стрелок, одометр цифр, радар и тумблер «Живой фон» (из «Ночной трассы»). Убрано как спорное: цветные «аварийные» пакеты, аритмия и кольца, перспективная дорога и sway, мигание значка Check Engine (мигающий MIL у водителя означает пропуски зажигания), покадровые чтения в композиции (shadow по кадру у BigCheckButton), самотест стрелок при каждом входе на вкладку.

## 1. Идея

Фон всего приложения — тёмный экран осциллографа, подключённого к шине машины. По тонким горизонтальным дорожкам слева направо бегут короткие цифровые пакеты: ступенчатая осциллограмма с яркой светящейся головой и гаснущим «фосфорным» хвостом. Поток честный, а не декоративный: пока адаптер не подключён — шина дремлет (редкие тусклые пакеты, дышит только подсветка приборки вверху экрана), подключился — пошёл трафик, завёл двигатель — ускорился в такт оборотам, идёт проверка — сыплется пачками, и каждая реальная команда адаптеру (в демо-режиме тоже) рождает свой пакет, мигает «светодиодом RX» на карточке связи и подсвечивает дугу большой кнопки. Раз в 11 секунд по экрану проходит мягкая полоса развёртки — она идёт поверх карточек, как отсвет по стеклу приборки, поэтому эффект виден и на плотных экранах, а не только в полях. Подсветка приборки медленно дышит (4,8 с) и дрейфует (20 с); при опасном вердикте она и сетка дорожек уходят в цвет тревоги — «что-то не так» читается ещё на главной.

Один мотив — «яркий отрезок, бегущий по линии» — размножен на все микроанимации: развёртка-шторка при смене экрана, огонёк по верхней кромке карточки при её появлении, импульс по контуру кнопки при нажатии, скользящая по рельсу таблетка в нижней панели, бегущий по шкале блик и механический перелёт стрелки на приборах. Всё живое питается от одного withFrameNanos-цикла (BusModel): пакеты, дыхание, развёртка, вспышки RX. Правило иерархии: бьётся только живое — точки в истории и карточки прошлых проверок статичны; «всё в порядке» спокойно, тревога дышит.

## 2. Фон: точный алгоритм

### 2.1. Где живёт

Корневой `Box` в `MainActivity.App()` получает три слоя-сиблинга (сверху вниз по z):

1. `SweepOverlay(model)` — узкий `Box` шириной 140 dp во всю высоту, свой `graphicsLayer { translationX = model.sweepX; alpha = model.sweepAlpha }`, рисует полосу развёртки ПОВЕРХ контента. Не перехватывает касания (нет pointerInput).
2. `AnimatedContent(page)` — страницы, прозрачные (см. §4).
3. `BusBackground(model, tint)` — полноэкранный `Box(Modifier.fillMaxSize().graphicsLayer().drawWithCache { … })`: градиент `Palette.background`, дышащая подсветка, дорожки, пакеты. Свой слой обязателен: иначе покадровая инвалидация переписывает display list корня со всеми страницами.

`BusModel` создаётся `remember`-ом ВЫШЕ `key(Tr.lang.code)` (MainActivity.kt:185), чтобы смена языка не перезапускала шину. Слой не пересоздаётся при смене страниц — шина «течёт» сквозь переходы.

### 2.2. Геометрия (dp, в px через density один раз в `layout()`)

- **Дорожки**: `n = clamp(round(H_dp / 96), 5, 10)`; `y_i = (i + 0.5)·H/n + jitter_i`, `jitter_i ∈ [−12, +12] dp` фиксированный из seed 7. Базовая линия: 1 px, `gridColor` α 0.05 (gridColor = accent в норме, см. настроение). При первом запуске линии прорисовываются слева направо за 700 мс FastOutSlowIn, каждая следующая на 40 мс позже (`laneReveal_i` 0→1, линия рисуется до `W·laneReveal_i`).
- **Пакет**: `nbits ∈ [8, 18]`; бит — горизонтальный отрезок шириной `bw = 5 dp` на уровне «низ» (`y_i`) или «верх» (`y_i − 9 dp`); при смене уровня — вертикальная кромка. Толщина 1.5 dp, `StrokeCap.Butt`. Голова = бит 0 (справа), хвост влево, `len = nbits·bw` (40–90 dp). Биты хранятся Int-маской; генерация с правилом бит-стаффинга CAN: не более 5 одинаковых подряд (после пяти — принудительно противоположный), чтобы пакет не вырождался в полку.
- **Свечение головы** (без Brush, три круга в `(x_head, y_head)`): r 7 dp α 0.10·A; r 3.5 dp α 0.30·A; r 1.5 dp α 1.0·A цветом `lighten(color, 0.35)`.
- **Фосфорное затухание**: для бита b (0 — голова) `a(b) = A·(1 − b/nbits)^1.6`, вертикальные кромки `0.85·a(b)`. `A = 0.55·intensity` для accent-пакетов; в режиме «нет адаптера» `A = 0.30·intensity`, цвет `Palette.muted`.
- **Появление/уход**: первые 160 мс жизни α растёт 0→1 линейно; последние 56 dp перед правым краем α 1→0; в последние 150 мс жизни радиусы свечения ×2 при падающей α («пакет прибыл»). Пакет умирает, когда `x_head − len > W + 8 dp`.
- **Подсветка приборки** (заменяет `glowTop`): радиальный градиент `tint α0.16 → tint α0.05 (0.5) → 0`, кисть с центром `Offset.Zero`, радиус `R = 0.95·W`, создаётся в `drawWithCache` (ключи: размер, tint). Рисуется `withTransform({ translate(0.5·W + drift·0.06·W, −0.18·W); scale(s, s, Offset.Zero) }) { drawCircle(brush, R, Offset.Zero, alpha = (0.75 + 0.25·breath)·intensity) }`, `s = 0.96 + 0.08·breath`.
- **Развёртка** (overlay): полоса 140 dp, горизонтальный градиент `0 → tint α0 · 0.5 → tint α0.045 · 1 → tint α0`, единственный Brush, создаётся в `drawWithCache` слоя-оверлея при смене размера/tint; движение — только `translationX` слоя, display list не переписывается. Цикл: от `x = −140 dp` до `x = W` за 11 с линейно, по кругу. `kickSweep(strength, durationMs)`: один проход от левого края за `durationMs` с alpha ×strength, затем обычный цикл. RTL-локали: `translationX = W − 140 dp − sweepX`, фон рисуется внутри `scale(−1f, 1f)` — пакеты бегут справа налево.

### 2.3. Часы (единственный withFrameNanos-цикл в приложении)

В `step(nowNs, mode)`:
```
dt = min((now − last)/1e9, 0.05); time += dt
breath = 0.5 + 0.5·sin(2π·time/4.8)      // общее дыхание
pulse  = 0.5 + 0.5·sin(2π·time/1.6)      // тревожный ритм (красная зона прибора, danger-свечение)
drift  = sin(2π·time/20)
rxLevel *= exp(−dt/0.16)                 // вспышка RX
```
Наружу для читателей вне фонового слоя — квантованные State: `breathQ` (шаг 1/100), `rxQ` (шаг 1/50), `sweepX`, `sweepAlpha` — пишутся только при изменении квантованного значения. `tick: MutableLongState` инкрементируется в конце `step()` — его читает только draw-лямбда фона.

### 2.4. Динамика от состояния машины (`BusMode`, считается в композиции `BusDriver`)

| Состояние | speedK | Интервал спонтанного рождения | Цвет, A |
|---|---|---|---|
| Адаптер не подключён | 0.6 | 1.8–3.2 с | muted, 0.30 |
| Подключён, ЭБУ не отвечает | 1.0 | 0.9–1.6 с | accent, 0.55 |
| ЭБУ онлайн, двигатель стоит | 1.0 | 0.38–0.72 с | accent, 0.55 |
| Двигатель работает | 1 + rpmQ/8000 (rpmQ — обороты с квантом 500; нет rpm — 1.25) | 0.26–0.52 с | accent, 0.55 |
| Идёт проверка (`state.busy != null`) | 1.6 | 0.11–0.22 с | accent, 0.55 |

- Скорость пакета `v = 72 dp/с × U(0.75, 1.25) × speedK`.
- **Реальный трафик**: `state.rx()` → `model.emitFrame()` рождает пакет немедленно; не чаще 1 пакета в 80 мс; живых ≤ MAX (24; 16 при `ActivityManager.isLowRamDevice`); лишние события молча отбрасываются.
- **Пачка** `burst(n)`: n пакетов на разных дорожках с шагом 40 мс — при смене текста `state.busy` (3) и при каждой смене страницы (3, «подгазовка»).
- **«Приборка включается»**: `connected` или `ecuOnline` false→true — `burst(n_lanes)` + `kickSweep(1.4, 1600)` + `edgeEpoch++` (все видимые карточки повторяют огонёк кромки со своим стаггером).
- **Выбор дорожки**: та, где последний пакет стартовал раньше всех, при условии что его хвост ушёл от левого края дальше 24 dp (без наложений); нет такой — пакет пропускается.
- **Настроение (mood)**: `Danger` — `diagnosis.level == "danger"` или `battery.level == "danger"` или (`engineOn` и coolant > 105); `Warning` — `milOn == true` или `diagnosis.level == "warning"` или `battery.level == "warning"`; иначе `Ok`. `moodColor`: Ok → accent, Warning → `Palette.warn`, Danger → `Palette.danger`. `tint = lerp(accent, moodColor, 0.7)` на странице Result, `lerp(accent, moodColor, 0.25)` на остальных; `animateColorAsState(tween(900))`. Tint красит подсветку, сетку дорожек и полосу развёртки. Пакеты всегда accent (никаких красных пакетов).
- **Интенсивность по страницам** (`animateFloatAsState(tween 450)`): Home 1.0; Sensors 0.7; Result, Purchase, Details 0.6; History, Forum, Settings, Garage, Blackbox, Service, Dash 0.5; Log 0 — цикл не шагает, слой не рисуется (LogScreen оставляет свой непрозрачный фон).

### 2.5. Порядок отрисовки кадра (фоновый слой)

1. `drawRect(Palette.background)`.
2. Подсветка приборки (1 круг с кистью).
3. Дорожки: `drawLine(gridColor α0.05, (0, y_i), (W·laneReveal_i, y_i), 1 px)`.
4. Пакеты: цикл по пулу — до 18 горизонталей + до 17 вертикалей + 3 круга головы (drawLine/drawCircle, `Color.copy(alpha)` и `Offset` — value-классы).

Бюджет: 24 пакета × ~38 примитивов ≈ 900 вызовов + 12 прочих, < 1 мс на аппаратном канвасе. Ноль аллокаций: массивы модели (`FloatArray/IntArray/BooleanArray`) выделены один раз; ни Path, ни Brush, ни списков в draw.

## 3. Микроанимации

| Экран / элемент | Что и как |
|---|---|
| **App(): переход между страницами** | `PageReveal`: новый экран проявляется слева направо — `clipRect(right = W·p)`, p 0→1 за 280 мс FastOutSlowIn; по кромке вертикальная линия accent 1.5 dp α 0.5·(1−p) со шлейфом 28 dp (`drawRect` accent α 0.06·(1−p)). Старый экран `fadeOut(tween(200))`, `enter = EnterTransition.None`. Клипуется только входящий контент; шина под обоими общая, поэтому это луч осциллографа, а не дырка. Одновременно `model.burst(3)`. Motion.Reduced: `fadeIn(200) + scaleIn(0.97, 200)` с `fadeOut(150)`; Motion.Off: `fadeIn(150)/fadeOut(150)`. Константа `REVEAL_WIPE = true` — если на устройстве шторка прочитается как «wipe из презентации», переключить на вариант Reduced. |
| **Card (Theme.kt) — все карточки** | «Пакет пришёл в карточку»: при появлении по верхней кромке (`Palette.edge`) один раз пробегает отрезок 64 dp (accent α 0.6→0) с яркой головой 6 dp (`lighten(accent, 0.4)` α 0.9→0), слева направо, 420 мс LinearEasing, старт через `edgePulseDelay` мс (стаггер в списках); повтор при `edgeEpoch` (подключение). Две `drawLine` в существующем `drawBehind`. Свечение `glow` (VerdictCard, поездка): для warn/danger дышит α 0.22 + 0.10·breathQ, для ok — статично 0.28; рисуется в отдельном дочернем `Box(matchParentSize().graphicsLayer().drawBehind{…})` первым ребёнком, чтобы покадровое чтение не переписывало слой карточки с текстом. Карточки с `onClick`: `pressPulse` масштаб 0.985. |
| **PrimaryButton, SecondaryButton, SquareIconButton, JournalTile** | `pressPulse`: при нажатии (`collectIsPressedAsState`) масштаб 0.97 (Primary/Secondary) / 0.96 (Square/Tile) через `spring(stiffness = StiffnessMedium)`; по контуру один раз обегает светящийся отрезок 30 % периметра за 480 мс LinearEasing (`PathMeasure.getSegment` по remembered Path скруглённого прямоугольника; `seg.reset()` каждый кадр). Отпустил раньше — отрезок дожигает круг. Цвет: `lighten(accent, 0.4)` для Primary, accent для остальных. Ripple отключён (`indication = null`). |
| **BigCheckButton** | Убрать `pulse` из композиции (сейчас `shadow((14 + 22·pulse).dp)` — рекомпозиция каждый кадр). Тень статичная 10 dp; ореол — в Canvas: `drawCircle(radialGradient accent→transparent, radius 118 dp)` из `drawWithCache`, масштаб `0.92 + 0.12·breathQ` через `scale()`, alpha `0.35 + 0.45·breathQ`; кольцо α `0.15 + 0.25·breathQ`. Спиннер busy — единственный оставшийся `rememberInfiniteTransition` (spin 1300 мс Linear); яркость дуги `0.8 + 0.2·rxQ` — кнопка дышит в такт пакетам. Нажатие: `pressPulse` 0.96. Canvas — в собственном `graphicsLayer()`. |
| **Dot(live = true) → LiveDot** (ConnRow на главной при ok, чип «Обновляется» на Датчиках при connected, точка идущей поездки) | Светодиод RX: ореол дышит (радиус 6→10 dp по breathQ, α 0.18) и вспыхивает на каждую команду (α + 0.25·rxQ). Два `drawCircle` в `drawBehind` вместо `Modifier.shadow`; узел обёрнут в `graphicsLayer()`. Без связи — серая статичная точка. Точки в Истории и ModuleRow — обычный `Dot`, статика. |
| **HomeScreen: текст под большой кнопкой** (Screens.kt:254) | `AnimatedContent(targetState = state.busy ?: hint)`: новый текст въезжает снизу на 8 dp + fadeIn 180 мс, старый fadeOut 120 мс — как строка терминала. В тот же момент фон получает пачку из 3 пакетов. |
| **HomeScreen: плашка Check Engine** | `Card(glow = Palette.warn)` — тёплое дышащее свечение (α 0.22 + 0.10·breathQ), значок статичный. Карточка «без ошибок» — `glow = accent`, статично. |
| **HomeScreen: InfoTile «Аккумулятор», «Протокол»** | `RollingText`: новая цифра выезжает снизу, старая уходит вверх (`AnimatedContent`, `slideInVertically { it/2 } + fadeIn(180)` / `slideOutVertically { −it/2 } + fadeOut(120)`, `SizeTransform(clip = false)`); на первом показе без анимации. |
| **BottomBar / TabItem** | Одна таблетка на всю панель скользит по рельсу: `idx by animateFloatAsState(current.ordinal, spring(dampingRatio 0.85, stiffness MediumLow))`, рисуется в `drawBehind` Row: `drawRoundRect(accent α0.14, topLeft (idx·slotW, 0), size (slotW, h), radius 20 dp)` + обводка accent α0.35 1 dp (Stroke и CornerRadius — в `drawWithCache`). TabItem без своего фона/обводки; цвет иконки и подписи `animateColorAsState(200 мс)`; иконка выбранной — «кивок» 1→1.12→1 (`Animatable`, `spring(dampingRatio 0.45)`), ореол под иконкой — `drawBehind`-круг вместо shadow. |
| **RoundGauge (Gauges.kt), RpmGauge (Theme.kt)** | Стрелка на `spring(dampingRatio 0.6, stiffness 150)` вместо `tween(500)` — механический перелёт. Самотест «включили зажигание» ОДИН раз за подключение (`state.gaugeSelfTest` сбрасывается в `disconnect()`): стрелка 0→max за 650 мс, max→значение за 750 мс FastOutSlowIn, стаггер 90 мс × order по сетке (0..7). Цифра значения докручивается: `animateFloatAsState(value, tween(450))`, форматируется анимированное число. Пока `live` (connected и Motion.Full): по дорожке шкалы (240°) циклически бежит блик 18° цветом `skin.glow` α 0.12, период 2400 мс Linear, фаза `0.125·order`; дрожь стрелки `sin(time·41)·0.35°·run·(0.4 + rpmNorm)` при работающем моторе (run — сглаженный 0..1); в красной зоне красная дуга α `0.55 + 0.45·pulse` вместо 0.9. Обязательное условие: статичный циферблат (ободок, деления, цифры через nativeCanvas) записывается в `rememberGraphicsLayer()` и перерисовывается только при смене размера/skin/шкалы; покадрово — `drawLayer(dial)` + дуга значения + стрелка + блик (~15 примитивов). Подписка на `model.tick` — только при `live`. |
| **SensorsScreen** | `RoundGauge(order = i, live = state.connected)`; чип «Обновляется» — `LiveDot`; карточка поездки — `LiveDot` + `glow = accent` (статично); `TripStat`/`TrimValue` — `RollingText`. |
| **ResultScreen** | Стаггер входа: элемент i — `Modifier.enterStagger(i)` (alpha 0→1, translationX −12 dp→0, 260 мс FastOutSlowIn, задержка `min(i, 8)·45` мс) и `Card(edgePulseDelay = min(i, 8)·45)`. VerdictCard: `glow = main`, для warn/danger дышит; квадрат с иконкой при danger — масштаб `1 + 0.04·pulse` через `graphicsLayer` (без аритмии). «Итого ремонт»: `animateIntAsState(total, tween(600))` моноширинным шрифтом. |
| **CodeCard: «Как это устроено и к чему ведёт»** | `if (more)` → `AnimatedVisibility(visible = more, enter = expandVertically(spring(stiffness = MediumLow)) + fadeIn(), exit = shrinkVertically() + fadeOut())`; «▲/▼» — один глиф «▼» с `rotationZ` 0→180° (`animateFloatAsState`, 200 мс). Пилюля «Серьёзно» — статична. |
| **HistoryScreen** | Карточки поездок и проверок — `enterStagger(index)` + `edgePulseDelay`, cap 8 ступеней; `JournalTile(warn = true)` — обводка warn α `0.25 + 0.20·breathQ` в `drawBehind` (свой `graphicsLayer()`). Удаление: `key(id) { AnimatedVisibility(visible, exit = shrinkVertically(220) + fadeOut(180)) }`; `state.deleteTrip/deleteHistory` вызывается после `delay(230)` в `LaunchedEffect(visible)`, страховка — `DisposableEffect.onDispose { if (!visible && !deleted) delete() }`. |
| **SettingsScreen** | Под «Цвет акцента» — `ToggleRow("Живой фон", "Пакеты данных на фоне бегут в такт связи с машиной. Выключи, если экономишь батарею", state.liveBackground)`. Выбранный кружок акцента: масштаб 1→1.12 `spring(dampingRatio 0.5, stiffness 500)`, тень 4→14 dp `animateDpAsState`. Смена акцента: `LocalAccent` подаётся через `animateColorAsState(Palette.accent(index), tween(500))` — фон, пакеты, развёртка, кнопки и таббар перекрашиваются плавно (30 кадров рекомпозиции один раз по действию пользователя — допустимо). |
| **DevicePickerDialog** | Вместо `CircularProgressIndicator` — `Radar(14.dp)`: `rememberInfiniteTransition` rotate 0→360 за 1400 мс Linear, `drawArc(sweepGradient(Transparent→accent), sweep 108°, stroke 2 dp)` — тот же язык, что дуга BigCheckButton (второй допустимый infinite transition — живёт только пока открыт диалог). |
| **Journal.kt: Sparkline** | При открытии прорисовывается слева направо за 600 мс (`clipRect(right = W·p)`, `Animatable`) с бегущей точкой-головой 3 dp на конце — та же осциллограмма, что на фоне. Path строится один раз в `remember(row)`. |
| **ForumScreen** | Кнопка отправки — `pressPulse`; пузыри сообщений без анимаций (LazyColumn с частыми добавлениями). |
| **LogScreen** | Ничего: свой непрозрачный фон, интенсивность 0, цикл фона стоит. |

Все длительности и spring'и — через `LocalMotion`: `Off` — карточки без edgePulse, `enterStagger`/самотест/блики отключены (`snapTo(1f)`), переходы простым fade; `Reduced` — интервалы рождения ×2, 30 fps, переходы fade+scale.

## 4. План реализации по файлам

### 4.1. Новый файл `app/src/main/java/io/github/sinsluhi/obdai/ui/Motion.kt`

```kotlin
enum class Motion { Full, Reduced, Off }
val LocalMotion = compositionLocalOf { Motion.Full }
val LocalBus = staticCompositionLocalOf<BusModel> { error("BusModel не предоставлен") }

enum class Mood { Ok, Warning, Danger }

/** Режим шины на текущий кадр; считается в композиции BusDriver, читается циклом через rememberUpdatedState. */
data class BusMode(
    val active: Boolean,        // false на Log и при Motion.Off
    val speedK: Float,
    val spawnMinMs: Int, val spawnMaxMs: Int,
    val alpha: Float, val muted: Boolean,
    val intensity: Float,       // по странице, уже сглаженная
    val fpsDivider: Int         // 1 / 2 (120 Гц, PowerSave) / 3 (нет адаптера)
)

/** Чистая модель без Compose: пул пакетов, дорожки, часы. Один экземпляр на приложение. */
class BusModel(val maxPackets: Int, seed: Int = 7) {
    // геометрия в px
    var w = 0f; var h = 0f; var density = 1f; var lanes = 0
    val laneY = FloatArray(10); val laneReveal = FloatArray(10); val laneLastX = FloatArray(10)
    // пул
    val alive = BooleanArray(maxPackets); val lane = IntArray(maxPackets); val nbits = IntArray(maxPackets)
    val bits = IntArray(maxPackets); val x = FloatArray(maxPackets); val v = FloatArray(maxPackets)
    val born = FloatArray(maxPackets); val len = FloatArray(maxPackets); val muted = BooleanArray(maxPackets)
    // часы (обычные var — читаются только внутри draw фонового слоя после чтения tick)
    var time = 0f; var breath = 0f; var pulse = 0f; var drift = 0f; var rxLevel = 0f; var run = 0f
    // квантованные State для читателей вне фонового слоя
    val breathQ = mutableFloatStateOf(0f); val pulseQ = mutableFloatStateOf(0f); val rxQ = mutableFloatStateOf(0f)
    val sweepX = mutableFloatStateOf(-1e4f); val sweepAlpha = mutableFloatStateOf(0f)
    val tick = mutableLongStateOf(0L)          // единственный покадровый State фона
    val edgeEpoch = mutableIntStateOf(0)       // «приборка включилась»: карточки повторяют огонёк кромки

    fun layout(wPx: Int, hPx: Int, density: Float)
    fun step(nowNs: Long, mode: BusMode)        // dt, часы, движение, спонтанные рождения, развёртка, tick++
    fun emitFrame()                             // реальная команда адаптеру; rate-limit 80 мс
    fun burst(n: Int)                           // пачка на n дорожках, шаг 40 мс
    fun kickSweep(strength: Float, durationMs: Int)
    fun connectedFlash()                        // burst(lanes) + kickSweep(1.4f, 1600) + edgeEpoch++
    fun reset()                                 // при смене размера: пул очищается, laneReveal заново
}

@Composable fun rememberBusModel(): BusModel   // MAX = if (isLowRamDevice) 16 else 24; remember ВЫШЕ key(Tr.lang.code)

/** Motion из системных настроек и тумблера; перечитывается на каждом RESUMED. */
@Composable fun rememberMotion(state: AppState): State<Motion>

/** Единственный withFrameNanos-цикл + мосты событий. Ставится вне key(Tr.lang.code). */
@Composable fun BusDriver(model: BusModel, state: AppState, page: Page, motion: Motion)
//  внутри: val mode = rememberUpdatedState(busMode(state, page, motion, hz120))
//  LaunchedEffect(model, motion) { if (motion == Off) return; lifecycle.repeatOnLifecycle(RESUMED) {
//      var frame = 0L; while (true) withFrameNanos { now -> frame++; val m = mode.value
//          if (m.active && frame % m.fpsDivider == 0L) model.step(now, m) } } }
//  RxBridge(model, state)                     // val c = state.rxCount; LaunchedEffect(c) { if (c > 0) model.emitFrame() }
//  LaunchedEffect(state.busy) { if (state.busy != null) model.burst(3) }
//  LaunchedEffect(page) { model.burst(3) }
//  LaunchedEffect(state.connected, state.ecuOnline) { if (был false → true) model.connectedFlash() }

fun busMode(state: AppState, page: Page, motion: Motion, hz120: Boolean, powerSave: Boolean): BusMode
fun pageIntensity(page: Page): Float

@Composable fun rememberMood(state: AppState): Mood
fun moodColor(mood: Mood, accent: Color): Color
@Composable fun rememberTint(mood: Mood, page: Page, accent: Color): State<Color>   // animateColorAsState(tween(900))

/** Нижний слой: градиент, подсветка приборки, дорожки, пакеты. Свой graphicsLayer(). */
@Composable fun BusBackground(model: BusModel, tint: Color, modifier: Modifier = Modifier)
private fun DrawScope.drawBus(model: BusModel, accent: Color, tint: Color, glow: Brush)

/** Верхний слой: полоса развёртки поверх контента; двигается только translationX слоя. */
@Composable fun SweepOverlay(model: BusModel, tint: Color)

/** Шторка-развёртка входящей страницы; при Reduced/Off — просто content(). */
@Composable fun AnimatedContentScope.PageReveal(motion: Motion, content: @Composable () -> Unit)
fun pageTransition(motion: Motion): ContentTransform   // Full: None togetherWith fadeOut(200); Reduced: fadeIn+scaleIn(0.97) / fadeOut; Off: fade 150

/** Нажатие: масштаб + светящийся отрезок по контуру. Ставится ПЕРЕД .shadow, clickable получает interaction. */
fun Modifier.pressPulse(radius: Dp, color: Color, interaction: MutableInteractionSource, scaleDown: Float = 0.97f): Modifier  // composed

/** Каскадный вход элемента списка. */
fun Modifier.enterStagger(index: Int, cap: Int = 8): Modifier   // composed; Off → snapTo(1f)

/** Огонёк по верхней кромке: используется внутри Card. */
@Composable fun rememberEdgePulse(delayMs: Int, epoch: Int, motion: Motion): Animatable<Float, AnimationVector1D>
fun DrawScope.drawEdgePulse(p: Float, accent: Color, widthPx: Float, strokePx: Float)

@Composable fun LiveDot(color: Color, size: Dp = 8.dp, live: Boolean)
@Composable fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier)
@Composable fun Radar(size: Dp, color: Color, modifier: Modifier = Modifier)

/** Статичный циферблат в GraphicsLayer: record при смене ключей, drawLayer каждый кадр. */
@Composable fun rememberCachedDial(vararg keys: Any?, record: DrawScope.() -> Unit): GraphicsLayer
```

### 4.2. `MainActivity.kt → App()`

- До `key(Tr.lang.code)`: `val model = rememberBusModel()`, `val motion by rememberMotion(state)`, `BusDriver(model, state, page, motion)`.
- `val accent by animateColorAsState(Palette.accent(state.accentIndex), tween(500), label = "accent")`; `val mood = rememberMood(state)`; `val tint by rememberTint(mood, page, accent)`.
- `CompositionLocalProvider(LocalAccent provides accent, LocalMotion provides motion, LocalBus provides model)`.
- Корневой `Box(Modifier.fillMaxSize().background(Palette.bg))` (плоский цвет остаётся как подложка первого кадра): дети по порядку — `BusBackground(model, tint)`, `AnimatedContent(page, transitionSpec = { pageTransition(motion) }, label = "page") { p -> PageReveal(motion) { when (p) { … как сейчас … } } }`, `SweepOverlay(model, tint)`, затем диалоги без изменений.
- `LaunchedEffect(page) { model.burst(3) }` живёт в BusDriver (получает page параметром).

### 4.3. `Screens.kt`

- `Screen()` (:97): `Column(Modifier.fillMaxSize())` — убрать `.background(Palette.background).glowTop()`. `LogScreen` (:1207) оставляет свой фон. Других корневых фонов в проекте нет (проверено grep по `Palette.background` и `.background(Palette.bg)`: Extra.kt, Journal.kt, GarageScreen.kt, PurchaseScreen.kt, ForumScreen.kt идут через `Screen()`).
- `HomeScreen`: текст под `BigCheckButton` → `AnimatedContent`; `ConnRow` → `LiveDot(live = ok)`; плашка Check Engine → `Card(glow = Palette.warn, …)`, «без ошибок» → `glow = accent`; `InfoTile` → `RollingText`.
- `ResultScreen`: карточки и `CodeCard` через `forEachIndexed` + `enterStagger(i)` + `Card(edgePulseDelay)`; «Итого ремонт» — `animateIntAsState`.
- `VerdictCard`: `glow = main` (дыхание внутри Card по mood-цвету), иконка при danger — `graphicsLayer { scale = 1 + 0.04·pulseQ }`.
- `CodeCard`: `AnimatedVisibility` + поворот глифа.
- `JournalTile`: `pressPulse` + дышащая обводка при `warn`.
- `SensorsScreen`: `RoundGauge(…, order = i, live = state.connected)`; `LiveDot` в чипе и карточке поездки; `TripStat`/`TrimValue` — `RollingText`.
- `HistoryScreen`: стаггер, удаление через `AnimatedVisibility`.
- `SettingsScreen`: `ToggleRow("Живой фон", …)` после «Цвет акцента»; пружина кружка акцента.
- `DevicePickerDialog`: `Radar(14.dp, accent)` вместо `CircularProgressIndicator`.
- Новые строки интерфейса — по-русски, как остальные строки Screens.kt; при переводе получают ключи `settings_live_bg`, `settings_live_bg_hint`. Слов Claude/Anthropic нет.

### 4.4. `Theme.kt`

- `Card(…, glow: Color? = null, edgePulse: Boolean = true, edgePulseDelay: Int = 0, onClick)`: `val edge = rememberEdgePulse(edgePulseDelay, LocalBus.current.edgeEpoch.intValue, LocalMotion.current)`; в существующем `drawBehind` — `drawEdgePulse(edge.value, accent, 64.dp.toPx(), 1.5.dp.toPx())`; свечение переезжает в дочерний `Box(Modifier.matchParentSize().graphicsLayer().drawBehind { … alpha = if (glow == warn/danger) 0.22 + 0.10·breathQ else 0.28 })` — только когда `glow != null`; `onClick` — через `interaction` + `pressPulse(radius, accent, interaction, 0.985f)` первым в цепочке (до `shadow`, чтобы масштаб захватил тень). Порядок: `pressPulse → fillMaxWidth → shadow → clip → background → drawBehind → border → clickable(interaction, indication = null)`.
- `Dot(color, size)` остаётся статичным; `LiveDot` — в Motion.kt.
- `PrimaryButton/SecondaryButton/SquareIconButton`: `val interaction = remember { MutableInteractionSource() }`; `Modifier.pressPulse(16.dp / 16.dp / 12.dp, …)` первым; `clickable(interactionSource = interaction, indication = null, …)`.
- `glowTop()` — удалить (подсветку рисует `BusBackground`).
- `BigCheckButton`: удалить `pulse`; ореол и кольцо — в Canvas по `breathQ`; дуга busy — `accent.copy(alpha = 0.8f + 0.2f·rxQ)`; тень статичная 10 dp; Canvas в `graphicsLayer()`; внутренний круг — `pressPulse(88.dp, …, 0.96f)`.
- `BottomBar/TabItem`: `Box` + `drawBehind` таблетки, `animateFloatAsState` индекса, TabItem без фона/обводки, `animateColorAsState` цвета, кивок иконки.
- `RpmGauge`: spring стрелки, самотест (один раз за подключение), кэш статики через `rememberCachedDial`, блик по шкале при `live`, дрожь при работающем моторе.

### 4.5. `Gauges.kt → RoundGauge(…, order: Int = 0, live: Boolean = false)`

- Статика (ободок, циферблат, зоны, дорожка шкалы, деления, цифры nativeCanvas) — `rememberCachedDial(size, skin, min, max, majorStep, redFrom, coldTo) { … }`; в Canvas: `drawLayer(dial)`.
- Стрелка: `needle = remember { Animatable(0f) }`; самотест по `state.gaugeSelfTest` (флаг в AppState: `false` до первого показа приборов после подключения; `SensorsScreen` ставит `true` после запуска), иначе `LaunchedEffect(fraction) { needle.animateTo(fraction, spring(0.6f, 150f)) }`.
- Покадровое (только при `live && Motion.Full`, читается `LocalBus.current.tick` в draw): блик 18°, дрожь, пульс красной зоны. Без `live` — прибор перерисовывается только при смене значения.
- Цифра: `animateFloatAsState(value, tween(450))`.
- Кисти градиентов (`bezel`, `dial`, ступица) — внутри записи слоя, не в покадровом draw.

### 4.6. `AppState.kt`, `Elm327.kt`, `Prefs.kt`, `build.gradle.kts`

- `AppState`: `var rxCount by mutableLongStateOf(0L)`; `fun rx() = ui { rxCount++ }`; `var liveBackground by mutableStateOf(prefs.liveBackground)`; `fun updateLiveBackground(v)`; `var gaugeSelfTest by mutableStateOf(false)` — сброс в `connect()/connectDemo()`, выставляется приборами.
- Где вызывать `rx()` (единой обёртки над `link.send` нет, DemoLink не ходит через `send()`): (а) `Elm327` получает `@Volatile var onCommand: (() -> Unit)? = null`, вызывается в начале `send()` (:113); AppState ставит `elm.onCommand = { rx() }` при подключении — реальный адаптер даёт ~10 команд/с в опросе; (б) для `DemoLink` — в цикле `startPolling()` (:769) после `readSensors` и `readVoltage` (только если `link is DemoLink`), и в `runCheck()` (:441–484) по одному `rx()` на каждый шаг `busy` плюс на каждый `progress` `scanModules`. Rate-limit 80 мс живёт в модели, поэтому лишние вызовы безопасны.
- `Prefs`: `var liveBackground: Boolean` (`"live_bg"`, default true).
- `build.gradle.kts`: `implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")` — даёт `androidx.lifecycle.compose.LocalLifecycleOwner` и `repeatOnLifecycle` явно (AndroidX, не анимационная библиотека; приходит транзитивно с compose-ui 1.7, но сборка только в CI — не рисковать). Больше ничего: `withFrameNanos`, `mutableLongStateOf`, `drawWithCache`, `PathMeasure`, `rememberGraphicsLayer`/`drawLayer`, `AnimatedVisibility`, `graphicsLayer`, `collectIsPressedAsState` — всё в compose-bom 2024.12.01.

## 5. Правила производительности

1. **Один цикл на всё приложение.** Единственный `withFrameNanos` — в `BusDriver`. `rememberInfiniteTransition` остаётся только у спиннера busy в BigCheckButton и у `Radar` в диалоге. Все остальные ритмы (дыхание, пульс, RX, блик шкалы, дрожь стрелки) читают `BusModel`.
2. **Пауза при невидимости.** Цикл живёт внутри `lifecycle.repeatOnLifecycle(RESUMED)`; кроме того frame clock Compose сам встаёт на паузу на ON_STOP, поэтому `withFrameNanos` не тикает в фоне. Проверка: `adb shell dumpsys gfxinfo io.github.sinsluhi.obdai` после сворачивания — новых кадров нет; CPU приложения в Profiler — 0 %. На странице Log (`intensity = 0`) и при `Motion.Off` `step()` не вызывается.
3. **Чтение State только в draw/graphicsLayer.** `model.tick`, `breathQ`, `pulseQ`, `rxQ`, `sweepX`, `sweepAlpha`, `Animatable.value` читаются исключительно внутри `drawWithCache/onDrawBehind`, `drawBehind`, `Canvas {}` и `graphicsLayer {}` — никогда в теле composable, в параметрах `shadow/size/padding/border` и в `remember`-ключах. Единственные покадровые рекомпозиции — `RxBridge` (пустышка) и `LaunchedEffect(rxCount)` у LiveDot. Проверка: Layout Inspector → счётчик рекомпозиций `App()`, `Card`, `RoundGauge` не растёт от часов.
4. **Каждый покадровый читатель — в своём `graphicsLayer()`.** Фоновый слой, оверлей развёртки, Canvas большой кнопки, LiveDot, glow-подслой Card, обводка JournalTile, приборы при `live`. Иначе инвалидация переписывает display list ближайшего слоя-предка (у Card это shadow-слой со всем текстом, у корня — все страницы).
5. **Ноль аллокаций в кадре.** В draw допустимы только `Color.copy(alpha)`, `Offset`, `Size`, `CornerRadius` (value-классы), `drawLine/drawCircle/drawRect/drawArc/drawLayer`, `translate/scale/rotate/clipRect/withTransform`. Запрещены: `Path()`, `Brush.*`, `Stroke()`, списки, лямбды с захватом, `String.format`. Все Brush/Stroke/Path/PathMeasure/Paint — в `drawWithCache` или `remember` с ключами размера и цвета; массивы модели выделяются в конструкторе; `seg.reset()` вместо нового Path; биты пакета — Int-маска.
6. **Бюджет кадра.** Фон: ≤ 24 пакета × 38 примитивов + 12 ≈ 930 вызовов, < 1 мс; на `isLowRamDevice` MAX = 16. Оверлей — 1 rect, движение матрицей слоя. Приборы при `live` — ~15 примитивов каждый поверх кэшированного слоя. Проверка: «Profile GPU rendering» — кадры < 8 мс на minSdk-26 устройстве; janky < 2 %.
7. **Экономия по режимам.** Нет адаптера — `fpsDivider = 3` (~20 fps, движение медленное и тусклое, глазом не видно). 120 Гц (медиана dt первых 60 кадров < 12 мс) — `fpsDivider = 2` вне проверки. `PowerManager.isPowerSaveMode` → `Motion.Reduced`: интервалы рождения ×2, 30 fps, переходы fade+scale, амплитуда дыхания вдвое меньше. Квантование `breathQ/pulseQ/rxQ` — запись State только при изменении квантованного значения (вдвое меньше инвалидаций читателей).
8. **Системное «уменьшить/отключить анимацию».** `Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f` или `!state.liveBackground` → `Motion.Off`: цикл не запускается, `breathQ = 0.5`, дорожки статичные без пакетов и развёртки, карточки без edgePulse, `enterStagger`/самотест/блики — `snapTo(1f)`, переходы простым fade 150 мс. Значение перечитывается при каждом RESUMED (пользователь мог поменять в настройках разработчика). Штатные `tween/spring/Animatable` Compose сами масштабируются по `MotionDurationScale`, но наш цикл — нет, поэтому проверка обязательна.
9. **Читаемость.** Карточки непрозрачные (`Palette.surface`); шина видна в полях 20 dp, зазорах 18 dp, вокруг большой кнопки и в шапке. Максимальная α любого фонового слоя ≤ 0.55 для 1.5-dp линии, подсветка ≤ 0.16, оверлей развёртки ≤ 0.045·1.4 над карточками — контраст текста #EEF0F4 на #1B1F26 падает менее чем на 5 %. Проверить на реальном OLED бандинг полосы (5 стопов, оттенок акцента) и все 4 акцента (жёлтый и салатовый — самые светлые). Если на Result шина всё же отвлекает — `pageIntensity(Result)` 0.6 → 0.4, не трогая остальное.
10. **Демо-режим.** `DemoLink` опрашивается постоянно (150/400 мс), обороты 812→~4000 при «поездке» — шина непрерывно «шумит» и ускоряется, самотест приборов и «приборка включается» срабатывают при `connectDemo()`: демо показывает всё. При `isPowerSaveMode` — режим Reduced, как и с живым адаптером.

## Что не делаем

Цветные warn/danger-пакеты; аритмию и расходящиеся кольца; перспективную дорогу и sway; мигающий значок Check Engine; самотест при каждом входе на «Датчики»; отложенное удаление без страховки в `onDispose`; чтение часов в композиции; `Modifier.shadow` с покадровым радиусом; новые сторонние библиотеки (Lottie и т. п.).
