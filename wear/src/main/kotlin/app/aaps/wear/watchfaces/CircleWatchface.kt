@file:Suppress("DEPRECATION")

package app.aaps.wear.watchfaces

import android.util.Log
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.*
import android.os.PowerManager
import android.os.SystemClock
import android.support.wearable.watchface.WatchFaceStyle
import android.util.TypedValue
import android.view.WindowManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.wear.R
import app.aaps.wear.data.RawDisplayData
import app.aaps.wear.interaction.menus.MainMenuActivity
import app.aaps.wear.interaction.utils.Persistence
import com.ustwo.clockwise.common.WatchFaceTime
import com.ustwo.clockwise.wearable.WatchFace
import dagger.android.AndroidInjection
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.*
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class CircleWatchface : WatchFace() {

    private val TAG = "WEAR_FACE"

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var sp: SP
    @Inject lateinit var persistence: Persistence

    private val disposable = CompositeDisposable()
    private val rawData = RawDisplayData()

    private var latestSingleBg: EventData.SingleBg? = null
    private var latestStatus: EventData.Status? = null
    private var latestGraph: EventData.GraphData? = null

    private fun curSingleBg(): EventData.SingleBg = latestSingleBg ?: rawData.singleBg
    private fun curStatus(): EventData.Status = latestStatus ?: rawData.status
    private fun curGraph(): EventData.GraphData = latestGraph ?: rawData.graphData

    private val displaySize = Point()
    private lateinit var rect: RectF
    private lateinit var rectDelete: RectF

    companion object {
        const val PADDING = 20f
        const val CIRCLE_WIDTH = 10f
        const val BIG_HAND_WIDTH = 16
        const val SMALL_HAND_WIDTH = 8
        const val NEAR = 2
        const val ALWAYS_HIGHLIGHT_SMALL = false
        const val fraction = .5
    }

    private var angleBig = 0f
    private var angleSmall = 0f
    private var ringColor = 0
    private var overlapping = false

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = CIRCLE_WIDTH
    }
    private val removePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = CIRCLE_WIDTH
    }
    private val textPaintLarge = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val textPaintMid = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val textPaintSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }

    private val bgDataList = ArrayList<EventData.SingleBg>()

    private var lastInboundElapsed: Long = SystemClock.elapsedRealtime()
    private var lastDrawElapsed: Long = SystemClock.elapsedRealtime()
    private var lastUpdateToInvalidateMs: Long = 0L

    private var tSingleBgMs: Long = 0L
    private var tStatusMs: Long = 0L

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidAPS:CircleWatchface").apply {
            acquire(30_000)
            initGeometryAndScales()
            subscribeToBus()
            rawData.updateFromPersistence(persistence)
            rxBus.send(EventWearToMobile(EventData.ActionResendData("CircleWatchFace::onCreate")))
            release()
        }
    }

    override fun onDestroy() {
        disposable.clear()
        super.onDestroy()
    }

    @Synchronized
    override fun onDraw(canvas: Canvas) {
        aapsLogger.debug(LTag.WEAR, "CircleWatchface: onDraw(); +${SystemClock.elapsedRealtime() - lastInboundElapsed}ms after invalidate()")
        Log.d(TAG, "onDraw(); +${SystemClock.elapsedRealtime() - lastInboundElapsed}ms after invalidate()")

        val bgCol = backgroundColor
        canvas.drawColor(bgCol)

        drawTimeRing(canvas)
        drawTexts(canvas)

        lastDrawElapsed = SystemClock.elapsedRealtime()
    }

    override fun onTimeChanged(oldTime: WatchFaceTime, newTime: WatchFaceTime) {
        if (oldTime.hasMinuteChanged(newTime)) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidAPS:CircleWatchface_onTimeChanged").apply {
                acquire(3_000)
                prepareDrawTime()
                invalidate()
                release()
            }
        }
    }

    // Подписки на события
    private fun subscribeToBus() {
        // Status
        disposable += rxBus.toObservable(EventData.Status::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                latestStatus = it
                tStatusMs = SystemClock.elapsedRealtime()
                redrawWithWakeLock("Status")
            }

        // SingleBg
        disposable += rxBus.toObservable(EventData.SingleBg::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                latestSingleBg = it
                tSingleBgMs = SystemClock.elapsedRealtime()
                prepareDrawTime()
                redrawWithWakeLock("SingleBg")
            }

        // GraphData
        disposable += rxBus.toObservable(EventData.GraphData::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                latestGraph = it
                addToWatchSet()
                redrawWithWakeLock("GraphData")
            }

        // Preferences
        disposable += rxBus.toObservable(EventData.Preferences::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                initTextSizes()
                prepareDrawTime()
                redrawWithWakeLock("Preferences")
            }
    }

    // 🔋 Новый метод — гарантирует invalidate с коротким wakeLock
    private fun redrawWithWakeLock(tag: String) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidAPS:CircleWatchface_redraw")
        wl.acquire(2000) // держим CPU до 2 секунд

        fastRedraw(tag)

        wl.release()
    }

    private fun fastRedraw(tag: String) {
        val now = SystemClock.elapsedRealtime()
        lastUpdateToInvalidateMs = now - lastInboundElapsed
        lastInboundElapsed = now

        val dFromSingle = if (tSingleBgMs != 0L) (now - tSingleBgMs) else -1
        val dFromStatus = if (tStatusMs != 0L) (now - tStatusMs) else -1

        aapsLogger.debug(LTag.WEAR, "CircleWatchface: $tag -> invalidate(); Δinv=${lastUpdateToInvalidateMs}ms; +${dFromSingle}ms since SingleBg; +${dFromStatus}ms since Status")
        Log.d(TAG, "$tag -> invalidate(); Δinv=${lastUpdateToInvalidateMs}ms; +${dFromSingle}ms since SingleBg; +${dFromStatus}ms since Status")

        invalidate()
    }

    // ... (остальная часть файла — отрисовка колец, текста и пр. — без изменений)
    // оставляем твою текущую реализацию drawTimeRing, drawTexts, addReading, цвета и onTapCommand.

    // ——— Геометрия/шрифты
    private fun initGeometryAndScales() {
        val display = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getSize(displaySize)

        rect = RectF(PADDING, PADDING, displaySize.x - PADDING, displaySize.y - PADDING)
        rectDelete = RectF(
            PADDING - CIRCLE_WIDTH / 2,
            PADDING - CIRCLE_WIDTH / 2,
            displaySize.x - PADDING + CIRCLE_WIDTH / 2,
            displaySize.y - PADDING + CIRCLE_WIDTH / 2
        )

        initTextSizes()
        prepareDrawTime()
        addToWatchSet()
    }

    private fun initTextSizes() {
        val big = if (sp.getBoolean(R.string.key_show_big_numbers, false)) 72f else 56f
        val mid = if (sp.getBoolean(R.string.key_show_big_numbers, false)) 28f else 22f
        val small = 18f

        textPaintLarge.textSize = spToPx(big)
        textPaintMid.textSize = spToPx(mid)
        textPaintSmall.textSize = spToPx(small)
        debugPaint.textSize = spToPx(12f)

        val txtCol = textColor
        textPaintLarge.color = txtCol
        textPaintMid.color = txtCol
        textPaintSmall.color = txtCol
        debugPaint.color = txtCol
    }

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    // ——— Расчёт колец/стрелок/цветов
    @Synchronized
    private fun prepareDrawTime() {
        val cal = Calendar.getInstance()
        val hour = cal[Calendar.HOUR_OF_DAY] % 12
        val minute = cal[Calendar.MINUTE]
        angleBig = ((hour + minute / 60f) / 12f * 360 - 90 - BIG_HAND_WIDTH / 2f + 360) % 360
        angleSmall = (minute / 60f * 360 - 90 - SMALL_HAND_WIDTH / 2f + 360) % 360

        ringColor = when (curSingleBg().sgvLevel.toInt()) {
            -1 -> lowColor
            0  -> inRangeColor
            1  -> highColor
            else -> inRangeColor
        }

        circlePaint.color = ringColor
        circlePaint.strokeWidth = CIRCLE_WIDTH

        removePaint.color = backgroundColor
        removePaint.strokeWidth = CIRCLE_WIDTH * 3

        overlapping = ALWAYS_HIGHLIGHT_SMALL || areOverlapping(
            angleSmall, angleSmall + SMALL_HAND_WIDTH + NEAR,
            angleBig, angleBig + BIG_HAND_WIDTH + NEAR
        )
    }

    private fun areOverlapping(aBegin: Float, aEnd: Float, bBegin: Float, bEnd: Float): Boolean =
        bBegin in aBegin..aEnd ||
            (aBegin <= bBegin && bEnd > 360 && bEnd % 360 > aBegin) ||
            aBegin in bBegin..bEnd ||
            (bBegin <= aBegin && aEnd > 360 && aEnd % 360 > bBegin)

    // ——— Рисование кольца времени (как раньше, но без layout)
    private fun drawTimeRing(canvas: Canvas) {
        // внешнее кольцо
        canvas.drawArc(rect, 0f, 360f, false, circlePaint)
        // вырезы под «стрелки»
        canvas.drawArc(rectDelete, angleBig, BIG_HAND_WIDTH.toFloat(), false, removePaint)
        canvas.drawArc(rectDelete, angleSmall, SMALL_HAND_WIDTH.toFloat(), false, removePaint)

        if (overlapping) {
            // подсветка «малой» при наложении
            val strong = Paint(circlePaint).apply { strokeWidth = CIRCLE_WIDTH * 2 }
            canvas.drawArc(rect, angleSmall, SMALL_HAND_WIDTH.toFloat(), false, strong)

            // «внутреннее» стирание
            val innerErase = Paint(removePaint).apply { strokeWidth = CIRCLE_WIDTH }
            canvas.drawArc(rect, angleBig, BIG_HAND_WIDTH.toFloat(), false, innerErase)
            canvas.drawArc(rect, angleSmall, SMALL_HAND_WIDTH.toFloat(), false, innerErase)
        }

        // опциональная история (кольца за 30 минут)
        if (sp.getBoolean(R.string.key_show_ring_history, false) && bgDataList.isNotEmpty()) {
            addIndicator(canvas, 100f, Color.LTGRAY)
            addIndicator(canvas, bgDataList.first().low.toFloat(), lowColor)
            addIndicator(canvas, bgDataList.first().high.toFloat(), highColor)

            val soft = sp.getBoolean("softRingHistory", true)
            bgDataList.forEach { if (soft) addReadingSoft(canvas, it) else addReading(canvas, it) }
        }
    }

    // ——— Рисуем тексты (SGV / Δ / мин назад / статус (кратко) / отладка)
    private fun drawTexts(canvas: Canvas) {
        val cx = displaySize.x / 2f
        val cy = displaySize.y / 2f

        val sbg = curSingleBg()
        val status = curStatus()

        // SGV (крупно)
        canvas.drawText(sbg.sgvString, cx, cy - spToPx(8f), textPaintLarge)

        // Delta (+ avgΔ)
        val deltaLine = buildString {
            if (sp.getBoolean(R.string.key_show_delta, true)) {
                append(if (sp.getBoolean(R.string.key_show_detailed_delta, false)) sbg.deltaDetailed else sbg.delta)
                if (sp.getBoolean(R.string.key_show_avg_delta, true)) {
                    append("  ")
                    append(if (sp.getBoolean(R.string.key_show_detailed_delta, false)) sbg.avgDeltaDetailed else sbg.avgDelta)
                }
            }
        }
        if (deltaLine.isNotEmpty()) {
            canvas.drawText(deltaLine, cx, cy + spToPx(24f), textPaintMid)
        }

        // "Минуты назад"
        if (sp.getBoolean(R.string.key_show_ago, true)) {
            canvas.drawText(minutesFrom(sbg.timeStamp), cx, cy + spToPx(48f), textPaintSmall)
        }

        // Короткий статус (IOB/BGI по настройке)
        if (sp.getBoolean(R.string.key_show_external_status, true)) {
            val detailedIob = sp.getBoolean(R.string.key_show_detailed_iob, false)
            val showBgi = sp.getBoolean(R.string.key_show_bgi, false)
            val iobStr = if (detailedIob) "${status.iobSum} ${status.iobDetail}" else status.iobSum + getString(R.string.units_short)
            val statLine = if (showBgi) "${status.externalStatus}  $iobStr  ${status.bgi}" else "${status.externalStatus}  $iobStr"
            canvas.drawText(statLine, cx, cy + spToPx(68f), textPaintSmall)
        }

        // Отладка: lastUpdate:+Xs
        val sinceInbound = (SystemClock.elapsedRealtime() - lastInboundElapsed) / 1000
        canvas.drawText(
            "lastUpdate: +${sinceInbound}s  Δinv:${lastUpdateToInvalidateMs}ms",
            PADDING,
            displaySize.y - PADDING,
            debugPaint
        )
    }

    // ——— Утилиты истории/рисования чтений
    private fun minutesFrom(ts: Long): String =
        if (ts == 0L) "--'"
        else floor((System.currentTimeMillis() - ts) / 60000.0).toInt().toString() + "'"

    private fun addToWatchSet() {
        bgDataList.clear()
        if (!sp.getBoolean(R.string.key_show_ring_history, false)) return
        val threshold = (System.currentTimeMillis() - 1000L * 60 * 30).toDouble() // 30 мин
        for (e in curGraph().entries) if (e.timeStamp >= threshold) bgDataList.add(e)
        aapsLogger.debug(LTag.WEAR, "addToWatchSet size=${bgDataList.size}")
    }

    private fun darken(color: Int): Int {
        fun dark(c: Int) = max(c - c * fraction, 0.0).toInt()
        return Color.argb(Color.alpha(color), dark(Color.red(color)), dark(Color.green(color)), dark(Color.blue(color)))
    }

    private fun addArch(canvas: Canvas, offset: Float, color: Int, size: Float) {
        val rectTemp = RectF(
            PADDING + offset - CIRCLE_WIDTH / 2,
            PADDING + offset - CIRCLE_WIDTH / 2,
            displaySize.x - PADDING - offset + CIRCLE_WIDTH / 2,
            displaySize.y - PADDING - offset + CIRCLE_WIDTH / 2
        )
        val p = Paint().apply { this.color = color }
        canvas.drawArc(rectTemp, 270f, size, true, p)
    }

    private fun addArch(canvas: Canvas, start: Float, offset: Float, color: Int, size: Float) {
        val rectTemp = RectF(
            PADDING + offset - CIRCLE_WIDTH / 2,
            PADDING + offset - CIRCLE_WIDTH / 2,
            displaySize.x - PADDING - offset + CIRCLE_WIDTH / 2,
            displaySize.y - PADDING - offset + CIRCLE_WIDTH / 2
        )
        val p = Paint().apply { this.color = color }
        canvas.drawArc(rectTemp, start + 270, size, true, p)
    }

    private fun addIndicator(canvas: Canvas, bg: Float, color: Int) {
        val converted = bgToAngle(bg) + 270f
        val offset = 9f
        val rectTemp = RectF(
            PADDING + offset - CIRCLE_WIDTH / 2,
            PADDING + offset - CIRCLE_WIDTH / 2,
            displaySize.x - PADDING - offset + CIRCLE_WIDTH / 2,
            displaySize.y - PADDING - offset + CIRCLE_WIDTH / 2
        )
        val p = Paint().apply { this.color = color }
        canvas.drawArc(rectTemp, converted, 2f, true, p)
    }

    private fun bgToAngle(bg: Float): Float =
        if (bg > 100) ((bg - 100f) / 300f * 225f + 135) else (bg / 100 * 135)

    private fun addReadingSoft(canvas: Canvas, entry: EventData.SingleBg) {
        val color = if (sp.getBoolean(R.string.key_dark, true)) Color.DKGRAY else Color.LTGRAY
        val offsetMultiplier = (displaySize.x / 2f - PADDING) / 12f
        val offset = max(1.0, ceil((System.currentTimeMillis() - entry.timeStamp) / (1000 * 60 * 5.0))).toFloat()
        val size = bgToAngle(entry.sgv.toFloat())
        addArch(canvas, offset * offsetMultiplier + 10, color, size)
        addArch(canvas, size, offset * offsetMultiplier + 10, backgroundColor, (360 - size))
        addArch(canvas, (offset + .8f) * offsetMultiplier + 10, backgroundColor, 360f)
    }

    private fun addReading(canvas: Canvas, entry: EventData.SingleBg) {
        val color = if (sp.getBoolean(R.string.key_dark, true)) Color.DKGRAY else Color.LTGRAY
        var indicatorColor = if (sp.getBoolean(R.string.key_dark, true)) Color.LTGRAY else Color.DKGRAY

        var barColor = Color.GRAY
        if (entry.sgv >= entry.high) {
            indicatorColor = highColor
            barColor = darken(highColor)
        } else if (entry.sgv <= entry.low) {
            indicatorColor = lowColor
            barColor = darken(lowColor)
        }

        val offsetMultiplier = (displaySize.x / 2f - PADDING) / 12f
        val offset = max(1.0, ceil((System.currentTimeMillis() - entry.timeStamp) / (1000 * 60 * 5.0))).toFloat()
        val size = bgToAngle(entry.sgv.toFloat())
        addArch(canvas, offset * offsetMultiplier + 11, barColor, size - 2)            // тёмная полоса
        addArch(canvas, size - 2, offset * offsetMultiplier + 11, indicatorColor, 2f)  // индикатор на конце
        addArch(canvas, size, offset * offsetMultiplier + 11, color, (360f - size))    // тёмная заливка
        addArch(canvas, (offset + .8f) * offsetMultiplier + 11, backgroundColor, 360f)
    }

    // ——— Цвета (зависят от темы)
    private val lowColor: Int
        get() = if (sp.getBoolean(R.string.key_dark, true)) Color.argb(255, 255, 120, 120) else Color.argb(255, 255, 80, 80)
    private val inRangeColor: Int
        get() = if (sp.getBoolean(R.string.key_dark, true)) Color.argb(255, 120, 255, 120) else Color.argb(255, 0, 240, 0)
    private val highColor: Int
        get() = if (sp.getBoolean(R.string.key_dark, true)) Color.argb(255, 255, 255, 120) else Color.argb(255, 255, 200, 0)
    private val backgroundColor: Int
        get() = if (sp.getBoolean(R.string.key_dark, true)) Color.BLACK else Color.WHITE
    private val textColor: Int
        get() = if (sp.getBoolean(R.string.key_dark, true)) Color.WHITE else Color.BLACK

    // ——— Тап по SGV: двойной тап открывает меню (сохраняем поведение)
    private var sgvTapTime: Long = 0
    override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
        // Центр экрана считаем зоной SGV (радиус 100dp)
        if (tapType == TAP_TYPE_TAP) {
            val cx = displaySize.x / 2f
            val cy = displaySize.y / 2f
            val dx = x - cx
            val dy = y - cy
            val radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 100f, resources.displayMetrics)
            if (dx * dx + dy * dy <= radiusPx * radiusPx) {
                if (eventTime - sgvTapTime < 800) {
                    val intent = Intent(this, MainMenuActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                sgvTapTime = eventTime
            }
        }
    }

    override fun getWatchFaceStyle(): WatchFaceStyle =
        WatchFaceStyle.Builder(this)
            .setAcceptsTapEvents(true)
            .build()
}