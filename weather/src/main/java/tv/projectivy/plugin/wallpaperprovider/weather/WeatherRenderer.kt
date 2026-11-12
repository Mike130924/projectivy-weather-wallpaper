    /**
     * Christmas GSD edition: red neon glass weather card.
     * The card is deliberately self-contained so the wallpaper artwork outside
     * its bounds remains untouched.
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
        val left = 460f
        val top = 65f
        val width = 1000f
        val height = 550f
        val right = left + width
        val bottom = top + height
        val radius = 30f

        val neonRed = Color.rgb(255, 38, 65)
        val brightRed = Color.rgb(255, 105, 120)
        val white = Color.rgb(250, 249, 250)
        val muted = Color.rgb(190, 194, 205)

        val panel = RectF(left, top, right, bottom)

        // Soft red neon halo, followed by the dark translucent glass surface.
        canvas.drawRoundRect(panel, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT
            style = Paint.Style.STROKE
            strokeWidth = 8f
            setShadowLayer(25f, 0f, 0f, Color.argb(220, 255, 20, 55))
        })
        canvas.drawRoundRect(panel, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                left, top, right, bottom,
                Color.argb(226, 13, 15, 24),
                Color.argb(218, 5, 7, 14),
                Shader.TileMode.CLAMP
            )
        })
        canvas.drawRoundRect(panel, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = neonRed
            setShadowLayer(18f, 0f, 0f, Color.argb(230, 255, 20, 55))
        })
        canvas.drawRoundRect(
            RectF(left + 11f, top + 11f, right - 11f, bottom - 11f),
            radius - 10f, radius - 10f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = brightRed
                alpha = 115
            }
        )

        // Brighter lower-left edge detail, matching the reference's corner treatment.
        canvas.drawLine(
            left + radius, bottom - 2f, left + 92f, bottom - 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = brightRed
                strokeWidth = 3f
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(14f, 0f, 0f, neonRed)
            }
        )
        canvas.drawLine(
            left + 2f, bottom - radius, left + 2f, bottom - 92f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = brightRed
                strokeWidth = 3f
                strokeCap = 
Paint.Cap.ROUND
                setShadowLayer(14f, 0f, 0f, neonRed)
            }
        )

        // Header: location left, local clock and date right.
        val titlePaint = paint(31f, medium).apply { color = white; alpha = 255 }
        canvas.drawText(placeLabel.uppercase(), left + 42f, top + 54f, titlePaint)
        val now = java.util.Calendar.getInstance()
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now.time)
        val date = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(now.time)
        val timePaint = paint(42f, medium).apply { color = white; alpha = 255 }
        val datePaint = paint(22f, light).apply { color = muted; alpha = 235 }
        canvas.drawText(time, right - 42f - timePaint.measureText(time), top + 48f, timePaint)
        canvas.drawText(date, right - 42f - datePaint.measureText(date), top + 72f, datePaint)
        canvas.drawLine(left + 34f, top + 88f, right - 34f, top + 88f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = neonRed; alpha = 190; strokeWidth = 2f })

        // Current conditions.
        val temp = "${c.temperature.roundToInt()}${c.unitSuffix}"
        val tempPaint = paint(122f, light).apply { color = white; alpha = 255 }
        canvas.drawText(temp, left + 48f, top + 226f, tempPaint)
        WeatherIcons.draw(canvas, c.weatherCode, phaseIsDay,
            cx = left + 365f, cy = top + 185f, size = 96f)
        val description = OpenMeteoClient.describe(context, c.weatherCode)
        canvas.drawText(description, left + 445f, top + 187f,
            paint(39f, medium).apply { color = white; alpha = 255 })

        // Four clear metric cells.
        val metricTop = top + 258f
        val metricLeft = left + 32f
        val metricWidth = (width - 64f) / 4f
        val metricValues = listOf(
            "FEELS LIKE" to "${c.apparentTemperature.roundToInt()}${c.unitSuffix}",
            "HIGH" to "${c.high.roundToInt()}${c.unitSuffix}",
            "LOW" to "${c.low.roundToInt()}$
{c.unitSuffix}",
            "WIND" to "${c.windSpeed.roundToInt()} ${OpenMeteoClient.compass(c.windDirection)}"
        )
        metricValues.forEachIndexed { index, pair ->
            val x = metricLeft + index * metricWidth
            if (index > 0) {
                canvas.drawLine(x, metricTop + 4f, x, metricTop + 65f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = neonRed; alpha = 100; strokeWidth = 1.5f
                    })
            }
            canvas.drawText(pair.first, x + 16f, metricTop + 23f,
                paint(17f, medium).apply { color = muted; alpha = 245 })
            canvas.drawText(pair.second, x + 16f, metricTop + 58f,
                paint(29f, medium).apply { color = white; alpha = 255 })
        }

        // Exactly seven entries are expected from OpenMeteoClient.parseDaily:
        // the next complete Monday-to-Sunday calendar 
week.
        val forecastTop = top + 345f
        canvas.drawLine(left + 34f, forecastTop, right - 34f, forecastTop,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = neonRed; alpha = 210; strokeWidth = 2f
            })
        val entries = c.daily.take(7)
        val innerLeft = left + 26f
        val colWidth = (width - 52f) / 7f
        entries.forEachIndexed { index, day ->
            val cx = innerLeft + colWidth * index + colWidth / 2f
            val dayPaint = paint(27f, 
medium).apply { color = white; alpha = 255 }
            val highPaint = paint(32f, medium).apply { color = white; alpha = 255 }
            canvas.drawText(day.label, cx - dayPaint.measureText(day.label) / 2f,
                forecastTop + 42f, dayPaint)
            WeatherIcons.draw(canvas, day.weatherCode, true, cx, forecastTop + 98f, 46f)
            val high = "${day.high.roundToInt()}${c.unitSuffix}"
            canvas.drawText(high, cx - highPaint.measureText(high) / 2f,
                forecastTop + 158f, highPaint)
            if (index > 0) {
                val x = innerLeft + colWidth * index
                canvas.drawLine(x, forecastTop + 14f, x, bottom - 24f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = neonRed; alpha = 105; strokeWidth = 1.3f
                    })
            }
        }

        alert?.let {
            val warning = it.event.uppercase()
            canvas.drawText(warning, 
left + 42f, bottom - 18f,
                paint(17f, medium).apply {
                    color = Color.rgb(255, 190, 120)
                    alpha = 255
                })
        }
    }
