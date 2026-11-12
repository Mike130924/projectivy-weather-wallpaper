package tv.projectivy.plugin.wallpaperprovider.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Draws the weather panel into a 1920x1080 bitmap and writes it to cacheDir.
 *
 * Layout sits top-left: Projectivy's clock/status is top-right, the app row
 * takes the bottom fifth, and TV overscan eats the outer few percent on some
 * sets. Optional panels stack downward from the detail line, so enabling one
 * never shifts anything above it.
 */
object WeatherRenderer {

    private const val W = 1920
    private const val H = 1080
    private const val MARGIN = 120f

    /**
     * Anti burn-in pixel shift.
     *
     * A slow walk around an 8-point circle, one step every SHIFT_INTERVAL_MS
     * of real elapsed time — not render count, since burn-in risk tracks how
     * long the screen has actually shown this content, not how often the
     * plugin happened to redraw it. Whichever step is "current" gets used
     * whenever a render does happen; there's no need to track continuity
     * across periods where the wallpaper wasn't even on screen; e.g. while
     * something else was playing, since there's no burn-in risk to offset
     * during that time regardless.
     *
     * Kept to whole pixels via a plain canvas translate wrapped around the
     * panel/clock/banner draw calls only — never the background, which
     * already varies enough with conditions and time of day, and never fed
     * into layout decisions like whether the forecast strips fit, which are
     * computed independently and have far more clearance than this shift's
     * range needs.
     */
    private const val SHIFT_RADIUS = 4f
    private const val SHIFT_INTERVAL_MS = 12 * 60 * 1000L  // 12 minutes/step
    private val SHIFT_PATTERN: List<Pair<Float, Float>> = (0 until 8).map { i ->
        val angle = Math.toRadians(i * 45.0)
        (Math.cos(angle).toFloat() * SHIFT_RADIUS) to (Math.sin(angle).toFloat() * SHIFT_RADIUS)
    }

    /**
     * Which step of the shift pattern is current, as a plain counter.
     *
     * Public so the render-cache key in WallpaperProviderService can include
     * it directly rather than duplicating SHIFT_INTERVAL_MS as a second
     * hardcoded constant that would have to be kept in sync by hand — exactly
     * the kind of silent drift this codebase has been bitten by before.
     */
    fun burnInBucket(): Long = System.currentTimeMillis() / SHIFT_INTERVAL_MS

    private fun burnInOffset(): Pair<Float, Float> {
        if (!PreferencesManager.reduceBurnIn) return 0f to 0f
        val step = burnInBucket() % SHIFT_PATTERN.size
        return SHIFT_PATTERN[step.toInt()]
    }

    /**
     * Where the launcher's app row starts. Nothing is drawn past it.
     *
     * A setting rather than a measurement: the plugin API exposes no layout
     * information, so there is nothing to detect. When the launcher reports
     * itself idle, nothing is covering the wallpaper and the full frame is
     * available instead.
     */
    private fun safeBottom(): Float =
        if (PreferencesManager.idleFullFrame && PreferencesManager.launcherIdle) {
            H - 60f
        } else {
            H * (PreferencesManager.safeBottomPercent / 100f)
        }

    /** Height of the hourly/daily strip panels, including the precip row. */
    private const val STRIP_HEIGHT = 142f

    /**
     * Output files carry a timestamp rather than a fixed name.
     *
     * The launcher loads the wallpaper through an image loader that caches by
     * URI. With a constant filename the content:// URI was identical on every
     * render, so a freshly drawn bitmap was discarded in favour of the cached
     * one and the wallpaper appeared frozen — including after settings changes.
     */
    private const val OUTPUT_PREFIX = "weather_wallpaper_"
    private const val OVERLAY_PREFIX = "weather_overlay_"

    private lateinit var light: Typeface
    private lateinit var medium: Typeface

    /**
     * Set by the service before rendering. Kept here rather than threaded
     * through every signature because both render paths need it and neither
     * owns the fetch.
     */
    @Volatile
    var currentAlert: NwsAlertsClient.Alert? = null

    /** Set by the service alongside the alert. Null when nothing to report. */
    @Volatile
    var currentAurora: AuroraClient.Conditions? = null

    @Volatile
    var currentMarine: MarineClient.Conditions? = null

    @Volatile
    var currentAir: AirQualityClient.Reading? = null

    private fun gradientFor(bucket: String, isDay: Boolean): Pair<Int, Int> = when {
        !isDay -> Color.parseColor("#0B1026") to Color.parseColor("#1C2541")
        bucket == "clear" -> Color.parseColor("#1E6FB8") to Color.parseColor("#7EC8E3")
        bucket == "cloud" -> Color.parseColor("#3E4A5B") to Color.parseColor("#8A9BA8")
        bucket == "rain" -> Color.parseColor("#243B53") to Color.parseColor("#4A6D8C")
        bucket == "snow" -> Color.parseColor("#4A5A6B") to Color.parseColor("#B8C6D1")
        bucket == "storm" -> Color.parseColor("#1A1A2E") to Color.parseColor("#3D3D5C")
        else -> Color.parseColor("#2B2B2B") to Color.parseColor("#5A5A5A")
    }

    /** Set once per render from the user's size preference. */
    private var scale = 1f

    /** Scaled type size. */
    private fun sz(v: Float) = v * scale

    /** Scaled vertical step. */
    private fun dy(v: Float) = v * scale

    /**
     * A uniquely named output file, with older generations removed.
     *
     * Keeps the two most recent: the launcher may still be reading the previous
     * one when the next render lands.
     */
    private fun freshOutput(context: Context, prefix: String, ext: String): File {
        val dir = context.cacheDir
        dir.listFiles { f -> f.name.startsWith(prefix) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(1)
            ?.forEach { runCatching { it.delete() } }
        return File(dir, "$prefix${System.currentTimeMillis()}.$ext")
    }

    private fun stripHeight() = STRIP_HEIGHT * scale

    private fun paint(size: Float, face: Typeface, alpha: Int = 255) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = face
            color = Color.WHITE
            this.alpha = alpha
            setShadowLayer(10f, 0f, 3f, Color.argb(140, 0, 0, 0))
        }

    /**
     * World weather watch: a notable weather event somewhere else in the world.
     *
     * Laid out deliberately unlike the local view. A header names the feature,
     * the affected country is prominent, and the caption strip runs along the
     * bottom rather than the panel sitting top-left. Someone glancing at the
     * screen has to be able to tell at once that this is not their own weather
     * — a Red flood alert mistaken for local would be actively misleading.
     */
    fun renderWorldEvent(
        context: Context,
        event: WorldEventsClient.Event,
        conditions: OpenMeteoClient.Conditions?,
        phase: ThemeResolver.Phase
    ): File {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        light = Typeface.create("sans-serif-light", Typeface.NORMAL)
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        scale = PreferencesManager.panelScaleFactor

        // Cyclones and droughts span huge areas; floods and fires are local.
        val zoom = when (event.typeCode) {
            "TC", "DR" -> 4
            else -> 5
        }
        val map = Backgrounds.eventMap(
            context, W, H, phase, event.latitude, event.longitude, zoom
        )
        if (map != null) {
            canvas.drawBitmap(map, 0f, 0f, null)
            map.recycle()
        } else {
            canvas.drawColor(Color.parseColor("#0A1420"))
        }

        // Top and bottom scrims: the caption sits low, the header high.
        val topScrim = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, H * 0.22f,
                Color.argb(200, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H * 0.22f, topScrim)

        val bottomScrim = Paint().apply {
            shader = LinearGradient(
                0f, H.toFloat(), 0f, H * 0.52f,
                Color.argb(215, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, H * 0.52f, W.toFloat(), H.toFloat(), bottomScrim)

        // Everything from here down is fixed-position, high-contrast text —
        // the same burn-in exposure as the local panel — so it gets the same
        // shift, wrapped around the whole block up to the credit line.
        val (shiftX, shiftY) = burnInOffset()
        canvas.save()
        canvas.translate(shiftX, shiftY)

        // Header: says plainly what this is.
        val header = context.getString(R.string.world_watch_header)
        canvas.drawText(header, MARGIN, MARGIN + 24f, paint(34f, medium, 215))

        // Alert-level chip beside the header.
        val chipLabel = context.getString(R.string.world_watch_alert_chip, event.alertLevel.uppercase())
        val chipPaint = paint(26f, medium, 240)
        val chipW = chipPaint.measureText(chipLabel) + 44f
        val chipLeft = MARGIN + paint(34f, medium).measureText(header) + 40f
        val chipRect = RectF(chipLeft, MARGIN - 4f, chipLeft + chipW, MARGIN + 38f)
        canvas.drawRoundRect(chipRect, 19f, 19f, Paint().apply {
            color = WorldEventsClient.colorFor(event.alertLevel)
        })
        canvas.drawText(chipLabel, chipLeft + 22f, MARGIN + 26f, chipPaint)

        // Caption block, bottom left, above the app shelf.
        var y = H - 250f
        canvas.drawText(event.countries.uppercase(), MARGIN, y, paint(32f, medium, 200))
        y += 84f
        canvas.drawText(event.name, MARGIN, y, paint(76f, light))

        // Named cyclones get a type line; a generated name like "Flood in
        // Nepal" already contains it, so printing "Flood" again reads oddly.
        if (!event.nameIsGenerated) {
            y += 52f
            canvas.drawText(event.type, MARGIN, y, paint(38f, light, 210))
        }

        if (event.severityText.isNotEmpty()) {
            y += 46f
            canvas.drawText(event.severityText, MARGIN, y, paint(30f, light, 175))
        }

        // Conditions at the event location, right-aligned so it can't be
        // confused with the local panel's left-aligned block.
        conditions?.let { c ->
            val temp = "${c.temperature.roundToInt()}${c.unitSuffix}"
            val tp = paint(96f, light, 235)
            canvas.drawText(temp, W - MARGIN - tp.measureText(temp), H - 268f, tp)

            val desc = OpenMeteoClient.describe(context, c.weatherCode)
            val dp = paint(36f, light, 200)
            canvas.drawText(desc, W - MARGIN - dp.measureText(desc), H - 214f, dp)

            WeatherIcons.draw(
                canvas, c.weatherCode, phase.isDay,
                cx = W - MARGIN - 62f, cy = H - 392f, size = 104f
            )
        }

        val credit = "Events: GDACS (UN/EC) \u00B7 Radar: RainViewer \u00B7 Map: Natural Earth"
        canvas.drawText(credit, MARGIN, H - 46f, paint(24f, light, 125))
        canvas.restore()

        val out = freshOutput(context, OUTPUT_PREFIX, "png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return out
    }

    /**
     * Panel only, on a transparent canvas.
     *
     * Used when the background is an animation the launcher renders itself: we
     * can't composite onto that, so the panel is embedded into the Lottie as an
     * video frames instead. See VideoEncoder.
     */
    fun renderOverlay(context: Context, c: OpenMeteoClient.Conditions, placeLabel: String): File {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        light = Typeface.create("sans-serif-light", Typeface.NORMAL)
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        scale = PreferencesManager.panelScaleFactor

        // The Christmas card has its own contained contrast surface. Preserve
        // every pixel of the user's wallpaper outside the card.
        val overlayPhase = ThemeResolver.resolve(c)

        val (shiftX1, shiftY1) = burnInOffset()
        canvas.save()
        canvas.translate(shiftX1, shiftY1)
        drawPanel(
            canvas, c, placeLabel, attribution = null,
            alert = currentAlert, phaseIsDay = overlayPhase.isDay, context = context
        )
        canvas.restore()

        val out = freshOutput(context, OVERLAY_PREFIX, "png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return out
    }

    /**
     * The full still scene as a Bitmap, over a supplied background.
     *
     * Used by the animated radar path: the map and panel are composited once
     * here, then each radar observation is drawn over a copy of it.
     */
    fun composeScene(
        context: Context,
        c: OpenMeteoClient.Conditions,
        placeLabel: String,
        background: Bitmap?,
        alert: NwsAlertsClient.Alert?
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        light = Typeface.create("sans-serif-light", Typeface.NORMAL)
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        scale = PreferencesManager.panelScaleFactor

        val phase = ThemeResolver.resolve(c)
        if (background != null) {
            canvas.drawBitmap(background, 0f, 0f, null)
            // The Christmas card provides its own contrast surface.
        } else {
            SceneBackgrounds.draw(canvas, W, H, c, phase)
            // The Christmas card provides its own contrast surface.
        }
        val (shiftX2, shiftY2) = burnInOffset()
        canvas.save()
        canvas.translate(shiftX2, shiftY2)
        drawPanel(
            canvas, c, placeLabel,
            "Radar: RainViewer \u00B7 Map: Natural Earth \u00B7 Places: GeoNames (CC BY)",
            alert, phase.isDay, context
        )
        canvas.restore()
        return bitmap
    }

    fun render(context: Context, c: OpenMeteoClient.Conditions, placeLabel: String): File {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        light = Typeface.create("sans-serif-light", Typeface.NORMAL)
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        scale = PreferencesManager.panelScaleFactor

        val bucket = OpenMeteoClient.bucket(c.weatherCode)
        val source = PreferencesManager.backgroundSource
        val phase = ThemeResolver.resolve(c)

        val resolved = if (source == Backgrounds.SOURCE_SCENE || source == Backgrounds.SOURCE_GRADIENT) {
            null
        } else {
            Backgrounds.resolveCached(context, source, c, W, H, phase)
        }
        var attribution: String? = null

        val theme = HolidayThemes.current()

        // With a theme active the background is drawn to its own bitmap and
        // composited through a ColorMatrix. Without one it draws straight to
        // the canvas, so the extra 8 MB is only allocated when it's needed.
        val target: Canvas
        val layer: Bitmap?
        if (theme != null) {
            layer = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            target = Canvas(layer)
        } else {
            layer = null
            target = canvas
        }

        if (resolved != null) {
            target.drawBitmap(resolved.first, 0f, 0f, null)
            resolved.first.recycle()
            attribution = resolved.second
        } else if (source == Backgrounds.SOURCE_GRADIENT) {
            val (top, bottom) = gradientFor(bucket, c.isDay)
            target.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 0f, W * 0.4f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP
                )
            })
        } else {
            SceneBackgrounds.draw(target, W, H, c, phase)
        }

        if (layer != null && theme != null) {
            canvas.drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(HolidayThemes.matrixFor(theme))
            })
            layer.recycle()
            if (theme.flecks) drawFlecks(canvas, theme)
        }

        // No full-frame scrim: keep the user's background artwork unchanged.

        val (shiftX3, shiftY3) = burnInOffset()
        canvas.save()
        canvas.translate(shiftX3, shiftY3)
        drawPanel(canvas, c, placeLabel, attribution, currentAlert, phase.isDay, context)
        canvas.restore()

        val out = freshOutput(context, OUTPUT_PREFIX, "png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return out
    }

    /** Everything above the background: readings, icon, optional panels. */
    /**
     * Christmas GSD edition: a centred burgundy-and-gold glass weather card.
     * Reference geometry is 4K X=1080, Y=160, W=1680, H=920, rendered here
     * at half scale on the 1920x1080 canvas. Everything outside the card is
     * transparent so the user's wallpaper remains completely untouched.
     */
    private fun drawPanel(
        canvas: Canvas,
        c: OpenMeteoClient.Conditions,
        placeLabel: String,
        attribution: String?,
        alert: NwsAlertsClient.Alert? = null,
        phaseIsDay: Boolean = false,
        context: Context? = null
    ) {
        // Approved compact Christmas GSD card, fitted inside the central window.
        // Transform the entire composition so the glass surface, border, typography,
        // icons, forecast and spacing all share the same compact layout bounds.
        canvas.save()
        canvas.translate(875f, 12.5f)
        canvas.scale(0.5506f, 0.4402f)
        canvas.translate(-540f, -80f)

        val left = 540f
        val top = 80f
        val width = 840f
        val height = 460f
        val right = left + width
        val bottom = top + height

        val gold = Color.rgb(226, 190, 112)
        val paleGold = Color.rgb(248, 226, 178)
        val cream = Color.rgb(255, 244, 224)
        val muted = Color.rgb(221, 202, 177)

        // Contained glass panel; no full-screen scrim or colour grading.
        val panel = RectF(left, top, right, bottom)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                left, top, right, bottom,
                Color.argb(226, 42, 10, 22),
                Color.argb(214, 20, 16, 28),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(panel, 28f, 28f, fill)
        canvas.drawRoundRect(panel, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = gold
            alpha = 235
        })
        canvas.drawRoundRect(
            RectF(left + 10f, top + 10f, right - 10f, bottom - 10f),
            22f, 22f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = paleGold
                alpha = 105
            }
        )

        // Header: location on the left, live 24-hour clock and date on the right.
        val titlePaint = paint(30f, medium).apply { color = paleGold; alpha = 255 }
        canvas.drawText(placeLabel.uppercase(), left + 38f, top + 52f, titlePaint)
        val now = java.util.Calendar.getInstance()
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now.time)
        val date = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(now.time)
        val timePaint = paint(42f, medium).apply { color = cream; alpha = 255 }
        val datePaint = paint(22f, light).apply { color = muted; alpha = 235 }
        canvas.drawText(time, right - 38f - timePaint.measureText(time), top + 48f, timePaint)
        canvas.drawText(date, right - 38f - datePaint.measureText(date), top + 68f, datePaint)
        canvas.drawLine(left + 30f, top + 76f, right - 30f, top + 76f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; alpha = 175; strokeWidth = 2f })

        // Main conditions: temperature, icon and description on one clean row.
        val temp = "${c.temperature.roundToInt()}${c.unitSuffix}"
        val tempPaint = paint(112f, light).apply { color = cream; alpha = 255 }
        canvas.drawText(temp, left + 42f, top + 202f, tempPaint)
        WeatherIcons.draw(canvas, c.weatherCode, phaseIsDay,
            cx = left + 332f, cy = top + 163f, size = 86f)
        val description = OpenMeteoClient.describe(context, c.weatherCode)
        canvas.drawText(description, left + 405f, top + 164f,
            paint(38f, medium).apply { color = paleGold; alpha = 255 })

        // Four separate metric cells avoid the old long, colliding detail line.
        val metricTop = top + 224f
        val metricLeft = left + 30f
        val metricWidth = (width - 60f) / 4f
        val metricValues = listOf(
            "FEELS LIKE" to "${c.apparentTemperature.roundToInt()}${c.unitSuffix}",
            "HIGH" to "${c.high.roundToInt()}${c.unitSuffix}",
            "LOW" to "${c.low.roundToInt()}${c.unitSuffix}",
            "WIND" to "${c.windSpeed.roundToInt()} ${OpenMeteoClient.compass(c.windDirection)}"
        )
        metricValues.forEachIndexed { index, pair ->
            val x = metricLeft + index * metricWidth
            if (index > 0) {
                canvas.drawLine(x, metricTop + 4f, x, metricTop + 62f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; alpha = 85; strokeWidth = 1.5f })
            }
            canvas.drawText(pair.first, x + 16f, metricTop + 22f,
                paint(16f, medium).apply { color = muted; alpha = 235 })
            canvas.drawText(pair.second, x + 16f, metricTop + 54f,
                paint(27f, medium).apply { color = cream; alpha = 255 })
        }

        // Six future days plus today's high/low above: seven calendar days total.
        // Divide the strip by the actual entry count so no empty forecast column
        // is left at the end when Open-Meteo returns six future days.
        val forecastTop = top + 310f
        canvas.drawLine(left + 30f, forecastTop, right - 30f, forecastTop,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; alpha = 130; strokeWidth = 1.5f })
        val entries = c.daily.take(6)
        val innerLeft = left + 26f
        val colWidth = (width - 52f) / entries.size.coerceAtLeast(1)
        entries.forEachIndexed { index, day ->
            val cx = innerLeft + colWidth * index + colWidth / 2f
            val dayPaint = paint(19f, medium).apply { color = paleGold; alpha = 245 }
            val highPaint = paint(20f, medium).apply { color = cream; alpha = 255 }
            val lowPaint = paint(18f, light).apply { color = muted; alpha = 235 }
            canvas.drawText(day.label, cx - dayPaint.measureText(day.label) / 2f,
                forecastTop + 30f, dayPaint)
            WeatherIcons.draw(canvas, day.weatherCode, true, cx, forecastTop + 66f, 34f)
            val high = "${day.high.roundToInt()}°"
            val low = "${day.low.roundToInt()}°"
            canvas.drawText(high, cx - 17f - highPaint.measureText(high) / 2f,
                forecastTop + 112f, highPaint)
            canvas.drawText(low, cx + 18f - lowPaint.measureText(low) / 2f,
                forecastTop + 112f, lowPaint)
            if (index > 0) {
                val x = innerLeft + colWidth * index
                canvas.drawLine(x, forecastTop + 12f, x, bottom - 20f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; alpha = 55; strokeWidth = 1f })
            }
        }

        alert?.let {
            val warning = it.event.uppercase()
            val warningPaint = paint(17f, medium).apply {
                color = Color.rgb(255, 205, 120)
                alpha = 255
            }
            canvas.drawText(warning, left + 42f, bottom - 18f, warningPaint)
        }
        canvas.restore()
    }

    private fun drawClock(canvas: Canvas, context: Context, alert: Boolean) {
        val cal = java.util.Calendar.getInstance()
        val base = sz(PreferencesManager.clockBaseSize)
        // Pushed down when an alert banner occupies the top of the frame.
        val offsetY = if (alert) 92f else 0f

        if (PreferencesManager.clockStyle == PreferencesManager.CLOCK_ANALOGUE) {
            drawAnalogueClock(canvas, cal, base, offsetY)
        } else {
            drawDigitalClock(canvas, context, cal, base, offsetY)
        }
    }

    private fun use24Hour(context: Context): Boolean =
        when (PreferencesManager.clockHours) {
            PreferencesManager.CLOCK_HOURS_12 -> false
            PreferencesManager.CLOCK_HOURS_24 -> true
            // The system setting is right for most people, but a TV's locale is
            // often wrong for the household using it, hence the override above.
            else -> android.text.format.DateFormat.is24HourFormat(context)
        }

    private fun dateText(cal: java.util.Calendar): String =
        java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
            .format(cal.time)

    private fun drawDigitalClock(
        canvas: Canvas,
        context: Context,
        cal: java.util.Calendar,
        base: Float,
        offsetY: Float
    ) {
        val h24 = use24Hour(context)
        val hour = if (h24) cal.get(java.util.Calendar.HOUR_OF_DAY)
        else cal.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }
        val time = String.format("%d:%02d", hour, cal.get(java.util.Calendar.MINUTE))
        val suffix = if (h24) null else
            if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"

        val face = if (PreferencesManager.clockStyle == PreferencesManager.CLOCK_DIGITAL_BOLD)
            medium else light
        val timePaint = paint(base, face)
        val suffixPaint = paint(base * 0.35f, medium, 225)
        val datePaint = paint(base * 0.31f, light, 195)

        val timeWidth = timePaint.measureText(time)
        val suffixWidth = suffix?.let { suffixPaint.measureText(it) + base * 0.12f } ?: 0f
        val showDate = PreferencesManager.showClockDate
        val date = if (showDate) dateText(cal) else null

        when (PreferencesManager.clockPosition) {
            PreferencesManager.CLOCK_TOP_CENTRE -> {
                val centre = W / 2f
                val y = MARGIN * 0.5f + base * 0.75f + offsetY
                canvas.drawText(time, centre - (timeWidth + suffixWidth) / 2f, y, timePaint)
                suffix?.let {
                    canvas.drawText(
                        it,
                        centre + (timeWidth + suffixWidth) / 2f - suffixPaint.measureText(it),
                        y, suffixPaint
                    )
                }
                date?.let {
                    canvas.drawText(
                        it, centre - datePaint.measureText(it) / 2f, y + base * 0.42f, datePaint
                    )
                }
            }
            PreferencesManager.CLOCK_WITH_PANEL -> {
                // Above the location label, reading as one group with it.
                val y = MARGIN - base * 0.10f + offsetY
                canvas.drawText(time, MARGIN, y, timePaint)
                suffix?.let {
                    canvas.drawText(it, MARGIN + timeWidth + base * 0.10f, y, suffixPaint)
                }
            }
            else -> {
                val right = W - MARGIN
                val y = MARGIN + base * 0.62f + offsetY
                canvas.drawText(time, right - timeWidth - suffixWidth, y, timePaint)
                suffix?.let {
                    canvas.drawText(it, right - suffixPaint.measureText(it), y, suffixPaint)
                }
                date?.let {
                    canvas.drawText(
                        it, right - datePaint.measureText(it), y + base * 0.40f, datePaint
                    )
                }
            }
        }
    }

    /**
     * Analogue face.
     *
     * Charming but less legible across a room than digits, which is why the
     * setting suggests pairing it with the large size.
     */
    private fun drawAnalogueClock(
        canvas: Canvas,
        cal: java.util.Calendar,
        base: Float,
        offsetY: Float
    ) {
        val r = base * 0.62f
        val (cx, cy) = when (PreferencesManager.clockPosition) {
            PreferencesManager.CLOCK_TOP_CENTRE -> (W / 2f) to (MARGIN * 0.4f + r + offsetY)
            PreferencesManager.CLOCK_WITH_PANEL -> (MARGIN + r) to (MARGIN - r * 0.1f + offsetY)
            else -> (W - MARGIN - r) to (MARGIN + r * 0.5f + offsetY)
        }

        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, r * 0.045f)
            color = Color.WHITE
            setShadowLayer(8f, 0f, 2f, Color.argb(140, 0, 0, 0))
        }
        canvas.drawCircle(cx, cy, r, rim)

        // Hour marks, longer at the quarters.
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 240, 240, 240)
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90.0)
            val inner = if (i % 3 == 0) r * 0.84f else r * 0.91f
            tick.strokeWidth = if (i % 3 == 0) maxOf(2f, r * 0.06f) else maxOf(1f, r * 0.03f)
            canvas.drawLine(
                cx + (Math.cos(a) * inner).toFloat(), cy + (Math.sin(a) * inner).toFloat(),
                cx + (Math.cos(a) * r * 0.97).toFloat(), cy + (Math.sin(a) * r * 0.97).toFloat(),
                tick
            )
        }

        val minute = cal.get(java.util.Calendar.MINUTE)
        val hourAngle = Math.toRadians(
            ((cal.get(java.util.Calendar.HOUR) % 12) + minute / 60.0) * 30.0 - 90.0
        )
        val minuteAngle = Math.toRadians(minute * 6.0 - 90.0)

        val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(8f, 0f, 2f, Color.argb(150, 0, 0, 0))
        }
        hand.strokeWidth = maxOf(3f, r * 0.075f)
        canvas.drawLine(
            cx, cy,
            cx + (Math.cos(hourAngle) * r * 0.52).toFloat(),
            cy + (Math.sin(hourAngle) * r * 0.52).toFloat(), hand
        )
        hand.strokeWidth = maxOf(2f, r * 0.048f)
        canvas.drawLine(
            cx, cy,
            cx + (Math.cos(minuteAngle) * r * 0.80).toFloat(),
            cy + (Math.sin(minuteAngle) * r * 0.80).toFloat(), hand
        )
        canvas.drawCircle(cx, cy, r * 0.055f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })

        if (PreferencesManager.showClockDate) {
            val datePaint = paint(base * 0.30f, light, 195)
            val text = dateText(cal)
            canvas.drawText(
                text, cx - datePaint.measureText(text) / 2f, cy + r + base * 0.28f, datePaint
            )
        }
    }

    /**
     * Severe weather banner, top of screen and full width.
     *
     * Deliberately the loudest element on the wallpaper: if there's a tornado
     * warning out, that matters more than the temperature does.
     */
    private fun drawAlertBanner(canvas: Canvas, alert: NwsAlertsClient.Alert) {
        val h = 92f
        val bg = Paint().apply { color = NwsAlertsClient.colorFor(alert.severity) }
        canvas.drawRect(0f, 0f, W.toFloat(), h, bg)

        // Bright rule along the bottom edge lifts it off the wallpaper.
        canvas.drawRect(0f, h - 4f, W.toFloat(), h, Paint().apply {
            color = Color.argb(190, 255, 255, 255)
        })

        val title = paint(42f, medium)
        canvas.drawText(alert.event.uppercase(), MARGIN, 60f, title)

        val until = NwsAlertsClient.untilLabel(alert.ends)
        if (until.isNotEmpty()) {
            val p = paint(32f, light, 210)
            canvas.drawText(until, W - MARGIN - p.measureText(until), 58f, p)
        }
    }

    // ------------------------------------------------------------------ lines

    /**
     * "4\u00B0 warmer than yesterday", or null when it's within a degree.
     *
     * A one-degree difference isn't worth a line, and reporting it would make
     * the comparison feel like noise rather than information.
     */
    private fun yesterdayPhrase(c: OpenMeteoClient.Conditions): String? {
        val y = c.yesterdayHigh ?: return null
        val delta = (c.high - y).roundToInt()
        return when {
            delta >= 2 -> "$delta\u00B0 warmer than yesterday"
            delta <= -2 -> "${-delta}\u00B0 cooler than yesterday"
            else -> null
        }
    }

    private fun statsLine(context: Context?, c: OpenMeteoClient.Conditions): String? {
        val parts = mutableListOf<String>()
        if (c.humidity >= 0) parts.add("Humidity ${c.humidity}%")
        if (!c.uvIndexMax.isNaN()) parts.add("UV ${c.uvIndexMax.roundToInt()}")
        if (!c.dewPoint.isNaN()) parts.add("Dew ${c.dewPoint.roundToInt()}\u00B0")
        if (!c.visibility.isNaN()) {
            // Open-Meteo returns visibility in metres regardless of unit choice.
            val v = if (c.metric) "${(c.visibility / 1000).roundToInt()} km"
            else "${(c.visibility / 1609.34).roundToInt()} mi"
            parts.add("Visibility $v")
        }
        if (!c.pressure.isNaN()) {
            val p = if (c.metric) "${c.pressure.roundToInt()} hPa"
            else String.format("%.2f inHg", c.pressure * 0.02953)
            parts.add(p)
        }
        if (PreferencesManager.showAirQuality) {
            currentAir?.let { air ->
                parts.add(AirQualityClient.shortLabel(air))
                // Only present where the pollen model has coverage.
                AirQualityClient.pollenLabel(context, air)?.let { parts.add(it) }
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString("   \u00B7   ")
    }

    private fun sunLine(c: OpenMeteoClient.Conditions): String? {
        val rise = OpenMeteoClient.formatTime(c.sunrise)
        val set = OpenMeteoClient.formatTime(c.sunset)
        if (rise.isEmpty() && set.isEmpty()) return null

        val parts = mutableListOf<String>()
        if (rise.isNotEmpty()) parts.add("Sunrise $rise")
        if (set.isNotEmpty()) parts.add("Sunset $set")

        // Only meaningful while the sun is still up.
        if (c.isDay && c.sunset.isNotEmpty()) {
            val nowIso = c.sunset.substring(0, 11) + nowClock()
            OpenMeteoClient.minutesUntil(c.sunset, nowIso)?.let { mins ->
                val h = mins / 60
                val m = mins % 60
                parts.add(if (h > 0) "${h}h ${m}m of daylight left" else "${m}m of daylight left")
            }
        }
        return parts.joinToString("   \u00B7   ")
    }

    /** Local wall clock as "HH:mm", to compare against Open-Meteo's local times. */
    private fun nowClock(): String {
        val cal = java.util.Calendar.getInstance()
        return String.format(
            "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }

    // ----------------------------------------------------------------- strips

    /** Returns the x coordinate just past the strip it drew. */
    private fun hourlyStrip(
        canvas: Canvas,
        c: OpenMeteoClient.Conditions,
        startX: Float,
        top: Float
    ): Float {
        val colWidth = 118f * scale
        val entries = c.hourly.take(6)

        val panel = RectF(
            startX - dy(26f), top - dy(14f),
            startX + colWidth * entries.size + dy(10f), top + dy(128f)
        )
        canvas.drawRoundRect(panel, dy(18f), dy(18f), panelPaint())

        entries.forEachIndexed { i, hour ->
            val cx = startX + colWidth * i + colWidth / 2f - dy(12f)
            canvas.drawText(hour.label, cx - labelHalf(hour.label, sz(28f)), top + dy(22f), paint(sz(28f), light, 190).apply { color = Color.parseColor("#FF8FAEFF") })
            WeatherIcons.draw(canvas, hour.weatherCode, hour.isDay, cx, top + dy(60f), sz(46f))
            val t = "${hour.temperature.roundToInt()}\u00B0"
            canvas.drawText(t, cx - labelHalf(t, sz(36f)), top + dy(108f), paint(sz(36f), light, 240).apply { color = Color.parseColor("#FF00D9FF") })

            // Only show precipitation chance when it's worth knowing.
            if (hour.precipChance >= 20) {
                val p = "${hour.precipChance}%"
                canvas.drawText(
                    p, cx - labelHalf(p, sz(24f)), top + dy(128f),
                    paint(sz(24f), light, 190).apply { color = Color.parseColor("#FF00D9FF") }
                )
            }
        }
        return panel.right
    }

    private fun dailyStrip(
        canvas: Canvas,
        c: OpenMeteoClient.Conditions,
        startX: Float,
        top: Float
    ) {
        val entries = c.daily.take(7)
        if (entries.isEmpty()) return

        // Seven days must fit even when the hourly strip is enabled to the left.
        // Use the available width instead of silently dropping the whole forecast.
        val available = W - startX - dy(70f)
        val colWidth = minOf(124f * scale, available / entries.size)
        if (colWidth < 66f * scale) return
        val compact = (colWidth / (124f * scale)).coerceIn(0.58f, 1f)
        val panel = RectF(
            startX - dy(26f), top - dy(14f),
            startX + colWidth * entries.size + dy(10f), top + dy(128f * compact)
        )
        canvas.drawRoundRect(panel, dy(18f), dy(18f), panelPaint())

        entries.forEachIndexed { i, day ->
            val cx = startX + colWidth * i + colWidth / 2f - dy(12f * compact)
            val labelSize = sz(28f * compact)
            val iconSize = sz(46f * compact)
            val highSize = sz(34f * compact)
            val lowSize = sz(30f * compact)
            canvas.drawText(day.label, cx - labelHalf(day.label, labelSize), top + dy(22f * compact), paint(labelSize, light, 190).apply { color = Color.parseColor("#FF8FAEFF") })
            WeatherIcons.draw(canvas, day.weatherCode, true, cx, top + dy(60f * compact), iconSize)
            val hi = "${day.high.roundToInt()}\\u00B0"
            val lo = "${day.low.roundToInt()}\\u00B0"
            canvas.drawText(hi, cx - labelHalf(hi, highSize) - dy(22f * compact), top + dy(108f * compact), paint(highSize, light, 240).apply { color = Color.parseColor("#FF00D9FF") })
            canvas.drawText(lo, cx - labelHalf(lo, lowSize) + dy(24f * compact), top + dy(108f * compact), paint(lowSize, light, 180).apply { color = Color.parseColor("#FF8FAEFF") })
        }
    }

    private fun panelPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(52, 0, 0, 0)
    }

    /** Half the rendered width of a label, for centring without a layout pass. */
    private fun labelHalf(text: String, size: Float): Float =
        paint(size, light).measureText(text) / 2f

    // ------------------------------------------------------------------ scrim

    /**
     * Sparse drifting flecks for the wintery themes. Seeded by the day so they
     * hold still between refreshes rather than jumping every 15 minutes.
     */
    private fun drawFlecks(canvas: Canvas, theme: HolidayThemes.Theme) {
        val rng = kotlin.random.Random(System.currentTimeMillis() / 86_400_000L)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(90) {
            val x = rng.nextFloat() * W
            val y = rng.nextFloat() * H
            val depth = rng.nextFloat()
            paint.color = Color.argb(
                (70 + depth * 90).toInt(),
                Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent)
            )
            canvas.drawCircle(x, y, 1.5f + depth * 4f, paint)
        }
    }

    /**
     * Full-height scrim. Drawn across the whole canvas on purpose: an earlier
     * version stopped the rect at 65% height while its gradient was still
     * faintly opaque there, and the rect's own edge showed as a hard seam.
     */
    private fun drawScrim(canvas: Canvas, strong: Boolean, extraDark: Boolean = false) {
        val boost = if (extraDark) 45 else 0
        val topAlpha = (if (strong) 190 else 140) + boost
        val midAlpha = (if (strong) 95 else 60) + boost
        val midStop = if (strong) 0.38f else 0.30f
        val endStop = if (strong) 0.78f else 0.68f

        val scrim = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                intArrayOf(
                    Color.argb(topAlpha, 0, 0, 0),
                    Color.argb(midAlpha, 0, 0, 0),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, midStop, endStop),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), scrim)

        if (strong) {
            val side = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, W * 0.62f, 0f,
                    Color.argb(120 + boost, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), side)
        }
    }
}
