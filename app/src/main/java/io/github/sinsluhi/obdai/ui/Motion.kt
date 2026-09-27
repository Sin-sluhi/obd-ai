package io.github.sinsluhi.obdai.ui

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.Page
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/*
 * «Пульс шины»: живой фон-осциллограф и микроанимации приложения (см. MOTION.md).
 * Единственный покадровый цикл живёт в BusDriver; всё остальное читает BusModel в draw-лямбдах.
 */

/** Сколько анимации разрешено: полная, экономная (энергосбережение) или выключена (система/тумблер). */
enum class Motion { Full, Reduced, Off }

val LocalMotion = compositionLocalOf { Motion.Full }
val LocalBus = staticCompositionLocalOf<BusModel> { error("BusModel не предоставлен") }

/** Настроение фона по состоянию машины: норма, предупреждение, тревога. */
enum class Mood { Ok, Warning, Danger }

/** Если шторка-развёртка на устройстве читается как «wipe из презентации» — выключить, останется вариант Reduced. */
const val REVEAL_WIPE = true

/** Режим шины на текущий кадр; считается в композиции BusDriver, читается циклом через rememberUpdatedState. */
data class BusMode(
    val active: Boolean,        // false на Log и при Motion.Off
    val speedK: Float,
    val spawnMinMs: Int, val spawnMaxMs: Int,
    val alpha: Float, val muted: Boolean,
    val intensity: Float,       // по странице, уже сглаженная
    val fpsDivider: Int,        // 1 / 2 (120 Гц, PowerSave) / 3 (нет адаптера)
    val reduced: Boolean = false,   // Motion.Reduced: дыхание вдвое тише
    val engineOn: Boolean = false   // двигатель работает: сглаженный run для дрожи стрелок
)

private const val TWO_PI = (2.0 * PI).toFloat()
private const val SWEEP_PERIOD = 11f          // с, полный проход развёртки
private val SWEEP_WIDTH = 140.dp

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
    /** Интенсивность и множитель скорости последнего шага — их читает draw фона. */
    var intensity = 1f; var speedK = 1f
    // квантованные State для читателей вне фонового слоя
    val breathQ = mutableFloatStateOf(0f); val pulseQ = mutableFloatStateOf(0f); val rxQ = mutableFloatStateOf(0f)
    val sweepX = mutableFloatStateOf(-1e4f); val sweepAlpha = mutableFloatStateOf(0f)
    val tick = mutableLongStateOf(0L)          // единственный покадровый State фона
    val edgeEpoch = mutableIntStateOf(0)       // «приборка включилась»: карточки повторяют огонёк кромки

    // размеры в px, считаются один раз в layout()
    var bwPx = 5f; private set
    var upPx = 9f; private set
    var strokePx = 1.5f; private set
    var glowR1 = 7f; private set
    var glowR2 = 3.5f; private set
    var glowR3 = 1.5f; private set
    var fadePx = 56f; private set
    var sweepWidthPx = 140f; private set
    private var dieMarginPx = 8f
    private var laneGapPx = 24f
    private var speedPx = 72f

    private val rnd = Random(seed.toLong())
    /** Смещения дорожек в dp — фиксированные, из seed 7. */
    private val jitterDp = FloatArray(10).also { arr ->
        val r = Random(7L)
        for (i in arr.indices) arr[i] = r.nextFloat() * 24f - 12f
    }
    private val laneLast = IntArray(10) { -1 }      // индекс последнего рождённого пакета на дорожке
    private val laneLastBorn = FloatArray(10)       // время его рождения
    private var lastNs = 0L
    private var nextSpawn = 0f
    private var lastEmit = -1f
    private var burstLeft = 0
    private var nextBurst = 0f
    private var revealStart = 0f
    private var frozen = false
    private var curMuted = false
    // развёртка
    private var sweepT = 0f
    private var kick = false
    private var kickDur = 1.6f
    private var kickStrength = 1f

    /** Размер слоя изменился: пересчитать дорожки и px-константы, пул очистить. */
    fun layout(wPx: Int, hPx: Int, density: Float) {
        val nw = wPx.toFloat(); val nh = hPx.toFloat()
        if (nw == w && nh == h && density == this.density) return
        w = nw; h = nh; this.density = density
        bwPx = 5f * density; upPx = 9f * density; strokePx = 1.5f * density
        glowR1 = 7f * density; glowR2 = 3.5f * density; glowR3 = 1.5f * density
        fadePx = 56f * density; dieMarginPx = 8f * density; laneGapPx = 24f * density
        speedPx = 72f * density; sweepWidthPx = 140f * density
        val hDp = h / density
        lanes = (hDp / 96f).roundToInt().coerceIn(5, 10)
        for (i in 0 until lanes) laneY[i] = (i + 0.5f) * h / lanes + jitterDp[i] * density
        reset()
    }

    /** При смене размера: пул очищается, laneReveal заново. */
    fun reset() {
        for (p in 0 until maxPackets) alive[p] = false
        for (i in 0 until 10) {
            laneLast[i] = -1
            laneLastBorn[i] = -1e6f - rnd.nextFloat() * 10f   // случайный порядок первых рождений
            laneReveal[i] = if (frozen) 1f else 0f
            laneLastX[i] = w
        }
        revealStart = time
        burstLeft = 0
    }

    /** Цикл встал (Motion.Off): дорожки статичные, пакетов нет, дыхание на середине. */
    fun freeze() {
        frozen = true
        for (p in 0 until maxPackets) alive[p] = false
        for (i in 0 until 10) laneReveal[i] = 1f
        breathQ.floatValue = 0.5f; pulseQ.floatValue = 0.5f; rxQ.floatValue = 0f
        sweepAlpha.floatValue = 0f
        tick.longValue = tick.longValue + 1
    }

    /** Цикл возобновился после паузы: первый кадр без скачка dt. */
    fun resume() { lastNs = 0L }

    /** Внешняя яркость пакета (A из §2.2 без intensity): 0.55 для accent, 0.30 для muted; draw умножает на intensity. */
    val amp = FloatArray(maxPackets)
    private var curAlpha = 0.55f
    private var sleeping = false

    /** Цикл не шагает (страница Log): гасим развёртку и RX, очередь пачки сбрасываем, пакеты ждут на местах. */
    fun sleep() {
        sleeping = true
        lastNs = 0L
        rxLevel = 0f
        burstLeft = 0
        if (rxQ.floatValue != 0f) rxQ.floatValue = 0f
        if (sweepAlpha.floatValue != 0f) sweepAlpha.floatValue = 0f
    }

    /** Случайный интервал спонтанного рождения по текущему режиму, в секундах. */
    private fun interval(mode: BusMode): Float {
        val lo = mode.spawnMinMs.coerceAtLeast(1)
        val hi = mode.spawnMaxMs.coerceAtLeast(lo)
        return (lo + rnd.nextFloat() * (hi - lo)) / 1000f
    }

    /**
     * Один шаг часов (§2.3): dt ≤ 50 мс, ритмы, прорисовка дорожек, движение и смерть пакетов,
     * очередь пачки, спонтанные рождения, развёртка, квантованные State, tick++.
     */
    fun step(nowNs: Long, mode: BusMode) {
        if (w <= 0f || h <= 0f || lanes == 0) return      // до layout() шагать нечего
        frozen = false
        sleeping = false
        val dt = if (lastNs == 0L) 0f else min((nowNs - lastNs) / 1e9f, 0.05f).coerceAtLeast(0f)
        lastNs = nowNs
        time += dt
        intensity = mode.intensity
        speedK = mode.speedK
        curMuted = mode.muted
        curAlpha = mode.alpha

        // ритмы: дыхание 4,8 с (в Reduced амплитуда вдвое меньше), тревожный пульс 1,6 с, дрейф 20 с, затухание RX
        val breathAmp = if (mode.reduced) 0.25f else 0.5f
        breath = 0.5f + breathAmp * sin(TWO_PI * time / 4.8f)
        pulse = 0.5f + 0.5f * sin(TWO_PI * time / 1.6f)
        drift = sin(TWO_PI * time / 20f)
        rxLevel *= exp(-dt / 0.16f)
        // сглаженный «двигатель работает» 0..1 (постоянная ~0,6 с) — для дрожи стрелок
        val runTarget = if (mode.engineOn) 1f else 0f
        run += (runTarget - run) * (1f - exp(-dt / 0.6f))

        // прорисовка дорожек: 700 мс FastOutSlowIn, каждая следующая на 40 мс позже
        for (i in 0 until lanes) {
            val t = (time - revealStart - i * 0.04f) / 0.7f
            laneReveal[i] = when {
                t <= 0f -> 0f
                t >= 1f -> 1f
                else -> FastOutSlowInEasing.transform(t)
            }
        }

        // движение; смерть, когда хвост ушёл за правый край дальше 8 dp
        for (p in 0 until maxPackets) {
            if (!alive[p]) continue
            x[p] += v[p] * dt
            if (x[p] - len[p] > w + dieMarginPx) alive[p] = false
        }
        for (i in 0 until lanes) {
            val q = laneLast[i]
            laneLastX[i] = if (q >= 0 && alive[q] && lane[q] == i) x[q] - len[q] else w
        }

        // отложенная пачка burst(): по одному пакету с шагом 40 мс
        if (burstLeft > 0 && time >= nextBurst) {
            spawn(curMuted, curAlpha, speedK)
            burstLeft--
            nextBurst = time + 0.04f
        }

        // спонтанные рождения по интервалу режима; если режим стал быстрее — не ждём старый долгий интервал
        val maxWait = mode.spawnMaxMs.coerceAtLeast(1) / 1000f
        if (nextSpawn <= 0f || nextSpawn - time > maxWait) nextSpawn = time + interval(mode)
        if (time >= nextSpawn) {
            spawn(curMuted, curAlpha, speedK)
            nextSpawn = time + interval(mode)
        }

        // развёртка: разовый проход kickSweep либо цикл 11 с по кругу; движение от −140 dp до W
        sweepT += dt
        if (kick && sweepT >= kickDur) { kick = false; sweepT = 0f }
        val sx: Float
        val sa: Float
        if (kick) {
            sx = -sweepWidthPx + (w + sweepWidthPx) * (sweepT / kickDur)
            sa = kickStrength
        } else {
            if (sweepT >= SWEEP_PERIOD) sweepT -= SWEEP_PERIOD
            sx = -sweepWidthPx + (w + sweepWidthPx) * (sweepT / SWEEP_PERIOD)
            sa = 1f
        }
        // sweepX квантуется до пикселя, sweepAlpha учитывает интенсивность страницы (на Log полоса гаснет вместе с фоном)
        val sxq = sx.roundToInt().toFloat()
        if (sxq != sweepX.floatValue) sweepX.floatValue = sxq
        val saq = ((sa * intensity) * 100f).roundToInt() / 100f
        if (saq != sweepAlpha.floatValue) sweepAlpha.floatValue = saq

        // квантованные State — запись только при изменении
        val bq = (breath * 100f).roundToInt() / 100f
        if (bq != breathQ.floatValue) breathQ.floatValue = bq
        val pq = (pulse * 100f).roundToInt() / 100f
        if (pq != pulseQ.floatValue) pulseQ.floatValue = pq
        val rq = (rxLevel * 50f).roundToInt() / 50f
        if (rq != rxQ.floatValue) rxQ.floatValue = rq

        tick.longValue = tick.longValue + 1
    }

    /** Реальная команда адаптеру: вспышка RX всегда, пакет — не чаще одного в 80 мс. */
    fun emitFrame() {
        if (frozen || sleeping || w <= 0f || lanes == 0) return
        // цикл стоит (приложение в фоне) — пакеты не копим
        if (lastNs != 0L && System.nanoTime() - lastNs > 500_000_000L) return
        rxLevel = 1f
        if (lastEmit >= 0f && time - lastEmit < 0.08f) return
        lastEmit = time
        spawn(curMuted, curAlpha, speedK)
    }

    /** Пачка из n пакетов на разных дорожках с шагом 40 мс: очередь внутри модели, разбирает step(). */
    fun burst(n: Int) {
        if (frozen || n <= 0) return
        if (burstLeft <= 0) nextBurst = time
        burstLeft = min(burstLeft + n, maxPackets)
    }

    /** Один проход развёртки от левого края за durationMs с яркостью ×strength, затем обычный цикл. */
    fun kickSweep(strength: Float, durationMs: Int) {
        if (frozen) return
        kick = true
        kickStrength = strength
        kickDur = durationMs.coerceAtLeast(100) / 1000f
        sweepT = 0f
    }

    /** «Приборка включается»: пачка на все дорожки, быстрый проход развёртки, карточки повторяют огонёк кромки. */
    fun connectedFlash() {
        burst(if (lanes > 0) lanes else 6)
        kickSweep(1.4f, 1600)
        edgeEpoch.intValue = edgeEpoch.intValue + 1
    }

    /**
     * Рождение пакета: свободный слот пула, дорожка по §2.4 (последний пакет стартовал раньше всех,
     * его хвост ушёл от левого края дальше 24 dp), биты 8..18 с бит-стаффингом CAN. Нет места — молча пропускаем.
     */
    private fun spawn(mutedPacket: Boolean, alpha: Float, speedFactor: Float): Boolean {
        if (w <= 0f || lanes == 0) return false
        var slot = -1
        for (p in 0 until maxPackets) if (!alive[p]) { slot = p; break }
        if (slot < 0) return false                       // живых уже MAX
        var best = -1
        var bestBorn = Float.MAX_VALUE
        for (i in 0 until lanes) {
            val q = laneLast[i]
            if (q >= 0 && alive[q] && lane[q] == i && x[q] - len[q] < laneGapPx) continue
            if (laneLastBorn[i] < bestBorn) { bestBorn = laneLastBorn[i]; best = i }
        }
        if (best < 0) return false                       // все дорожки заняты у левого края
        // биты: не более пяти одинаковых подряд, после пяти — принудительно противоположный
        val n = 8 + rnd.nextInt(11)
        var mask = 0
        var prev = -1
        var same = 0
        for (b in 0 until n) {
            var bit = if (rnd.nextBoolean()) 1 else 0
            if (bit == prev) {
                if (same >= 5) { bit = 1 - bit; same = 1 } else same++
            } else same = 1
            if (bit == 1) mask = mask or (1 shl b)
            prev = bit
        }
        alive[slot] = true
        lane[slot] = best
        nbits[slot] = n
        bits[slot] = mask
        len[slot] = n * bwPx
        x[slot] = 0f                                     // голова у левого края, хвост ещё за экраном
        v[slot] = speedPx * (0.75f + 0.5f * rnd.nextFloat()) * speedFactor
        born[slot] = time
        muted[slot] = mutedPacket
        amp[slot] = alpha
        laneLast[best] = slot
        laneLastBorn[best] = time
        laneLastX[best] = -len[slot]
        return true
    }
}

// ---------------------------------------------------------------------------------------------
// Compose-обвязка: модель, режим анимаций, режим шины, настроение, единственный покадровый цикл
// ---------------------------------------------------------------------------------------------

/** Пул на 24 пакета, на слабых устройствах — 16. Вызывать ВЫШЕ key(Tr.lang.code), чтобы смена языка не перезапускала шину. */
@Composable
fun rememberBusModel(): BusModel {
    val context = LocalContext.current
    return remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        BusModel(if (am?.isLowRamDevice == true) 16 else 24)
    }
}

/** Системное «отключить анимации» (масштаб длительности аниматора 0). */
private fun animationsDisabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/** Энергосбережение включено. */
private fun powerSaveOn(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

/**
 * Motion из системных настроек и тумблера «Живой фон»: анимации отключены системой или тумблером → Off,
 * энергосбережение → Reduced, иначе Full. Системные значения перечитываются на каждом ON_RESUME.
 */
@Composable
fun rememberMotion(state: AppState): State<Motion> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val systemOff = remember { mutableStateOf(animationsDisabled(context)) }
    val powerSave = remember { mutableStateOf(powerSaveOn(context)) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemOff.value = animationsDisabled(context)
                powerSave.value = powerSaveOn(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember(state) {
        derivedStateOf {
            when {
                systemOff.value || !state.liveBackground -> Motion.Off
                powerSave.value -> Motion.Reduced
                else -> Motion.Full
            }
        }
    }
}

/** Интенсивность фона по странице (§2.4); Log — 0: цикл не шагает, слой не рисуется. */
fun pageIntensity(page: Page): Float = when (page) {
    Page.Home -> 1f
    Page.Sensors -> 0.7f
    Page.Result, Page.Purchase, Page.Details -> 0.6f
    Page.History, Page.Forum, Page.Settings, Page.Garage, Page.Blackbox, Page.Service, Page.Dash -> 0.5f
    Page.Log -> 0f
}

/**
 * Таблица §2.4: скорость, интервалы рождений, цвет и делитель кадров по состоянию машины.
 * Обороты — датчик "rpm" из state.sensors с квантом 500; «двигатель работает» — state.engineOn.
 * intensity здесь сырая по странице; BusDriver подменяет её сглаженной.
 */
fun busMode(state: AppState, page: Page, motion: Motion, hz120: Boolean, powerSave: Boolean): BusMode {
    val busy = state.busy != null
    val reduced = motion == Motion.Reduced
    val rpm = state.sensors.firstOrNull { it.key == "rpm" }?.value
    val rpmQ = if (rpm != null && rpm > 0.0) ((rpm / 500.0).roundToInt() * 500).coerceIn(0, 8000) else -1
    val speedK: Float
    var minMs: Int
    var maxMs: Int
    val alpha: Float
    val muted: Boolean
    when {
        busy -> { speedK = 1.6f; minMs = 110; maxMs = 220; alpha = 0.55f; muted = false }
        !state.connected -> { speedK = 0.6f; minMs = 1800; maxMs = 3200; alpha = 0.30f; muted = true }
        !state.ecuOnline -> { speedK = 1f; minMs = 900; maxMs = 1600; alpha = 0.55f; muted = false }
        state.engineOn -> {
            speedK = if (rpmQ >= 0) 1f + rpmQ / 8000f else 1.25f
            minMs = 260; maxMs = 520; alpha = 0.55f; muted = false
        }
        else -> { speedK = 1f; minMs = 380; maxMs = 720; alpha = 0.55f; muted = false }
    }
    if (reduced) { minMs *= 2; maxMs *= 2 }             // Reduced: рождения вдвое реже
    // делитель кадров: нет адаптера ~20 fps, энергосбережение/Reduced 30 fps, 120 Гц вне проверки — половина
    val base = if (hz120) 2 else 1
    val fpsDivider = when {
        !state.connected && !busy -> 3 * base
        reduced || powerSave -> 2 * base
        hz120 && !busy -> 2
        else -> 1
    }
    val intensity = pageIntensity(page)
    return BusMode(
        active = motion != Motion.Off && intensity > 0f,
        speedK = speedK,
        spawnMinMs = minMs, spawnMaxMs = maxMs,
        alpha = alpha, muted = muted,
        intensity = intensity,
        fpsDivider = fpsDivider,
        reduced = reduced,
        engineOn = state.engineOn
    )
}

/**
 * Настроение по состоянию машины: Danger — опасный вердикт, опасный АКБ или перегрев (мотор работает, ОЖ > 105°);
 * Warning — горит Check Engine, вердикт или АКБ с предупреждением; иначе Ok. derivedStateOf: композиция
 * не дёргается на каждый опрос датчиков, только при смене результата.
 */
@Composable
fun rememberMood(state: AppState): Mood {
    val mood by remember(state) {
        derivedStateOf {
            val diag = state.diagnosis?.level
            val bat = state.battery?.level
            val coolant = state.sensors.firstOrNull { it.key == "coolant" }?.value
            val overheat = state.engineOn && coolant != null && coolant > 105.0
            when {
                diag == "danger" || bat == "danger" || overheat -> Mood.Danger
                state.milOn == true || diag == "warning" || bat == "warning" -> Mood.Warning
                else -> Mood.Ok
            }
        }
    }
    return mood
}

/** Цвет настроения: норма — акцент, предупреждение — Palette.warn, тревога — Palette.danger. */
fun moodColor(mood: Mood, accent: Color): Color = when (mood) {
    Mood.Ok -> accent
    Mood.Warning -> Palette.warn
    Mood.Danger -> Palette.danger
}

/** Tint подсветки, сетки и развёртки: на Result 70 % цвета настроения, на остальных 25 %; плавно за 900 мс. */
@Composable
fun rememberTint(mood: Mood, page: Page, accent: Color): State<Color> {
    val k = if (page == Page.Result) 0.7f else 0.25f
    val target = lerp(accent, moodColor(mood, accent), k)
    return animateColorAsState(targetValue = target, animationSpec = tween(900), label = "busTint")
}

/**
 * Единственный withFrameNanos-цикл приложения плюс мосты событий → модель. Ставится вне key(Tr.lang.code).
 * Цикл живёт только в RESUMED; когда шина неактивна (Log), он спит на snapshotFlow, а не крутит кадры.
 */
@Composable
fun BusDriver(model: BusModel, state: AppState, page: Page, motion: Motion) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val pageS = rememberUpdatedState(page)
    val motionS = rememberUpdatedState(motion)
    val intensity by animateFloatAsState(
        targetValue = pageIntensity(page),
        animationSpec = tween(450),
        label = "busIntensity"
    )
    val intensityS = rememberUpdatedState(intensity)
    val hz120 = remember { mutableStateOf(false) }
    val powerSave = remember { mutableStateOf(powerSaveOn(context)) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) powerSave.value = powerSaveOn(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // режим считается лениво: пересчёт только при смене входов (датчики, страница, busy…), читает его цикл
    val mode = remember(state, model) {
        derivedStateOf {
            val raw = busMode(state, pageS.value, motionS.value, hz120.value, powerSave.value)
            val i = intensityS.value
            raw.copy(intensity = i, active = raw.active && i > 0f)
        }
    }

    LaunchedEffect(model, motion, owner) {
        if (motion == Motion.Off) {
            model.freeze()
            return@LaunchedEffect
        }
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.resume()
            var frame = 0L
            val dts = LongArray(60)
            var nDt = 0
            var prevNs = 0L
            while (true) {
                if (!mode.value.active) {
                    model.sleep()
                    snapshotFlow { mode.value.active }.first { it }
                    model.resume()
                    prevNs = 0L
                }
                withFrameNanos { now ->
                    frame++
                    // 120 Гц: медиана dt первых 60 кадров < 12 мс
                    if (!hz120.value && nDt < 60) {
                        if (prevNs != 0L) {
                            dts[nDt] = now - prevNs
                            nDt++
                            if (nDt == 60) {
                                dts.sort()
                                hz120.value = dts[30] < 12_000_000L
                            }
                        }
                        prevNs = now
                    }
                    val m = mode.value
                    if (m.active && frame % m.fpsDivider == 0L) model.step(now, m)
                }
            }
        }
    }

    // реальная команда адаптеру (rx()) → пакет и вспышка RX
    val rx = state.rxCount
    LaunchedEffect(rx) { if (rx > 0) model.emitFrame() }
    // смена текста этапа проверки → пачка
    val busy = state.busy
    LaunchedEffect(busy) { if (busy != null) model.burst(3) }
    // смена страницы → «подгазовка»
    LaunchedEffect(page) { model.burst(3) }
    // адаптер подключился / ЭБУ ответил (false → true) → «приборка включается»
    val connected = state.connected
    val ecuOnline = state.ecuOnline
    val prev = remember { booleanArrayOf(false, false) }
    LaunchedEffect(connected, ecuOnline) {
        val turnedOn = (connected && !prev[0]) || (ecuOnline && !prev[1])
        prev[0] = connected
        prev[1] = ecuOnline
        if (turnedOn) model.connectedFlash()
    }
}
