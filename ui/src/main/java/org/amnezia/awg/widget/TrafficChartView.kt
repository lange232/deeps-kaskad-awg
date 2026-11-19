/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import org.amnezia.awg.R
import org.amnezia.awg.panel.PanelTrafficPoint

/**
 * Traffic history as two bars per bucket: download (server to client) in the brand peach, upload in
 * a muted grey. Drawn by hand to avoid pulling a charting library into a client this small.
 */
class TrafficChartView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val downloadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PEACH }
    private val uploadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UPLOAD }
    private val gridPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GRID
            strokeWidth = 1f
        }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL
            textSize = 10f * resources.displayMetrics.scaledDensity
        }
    private val barRect = RectF()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    private var points: List<PanelTrafficPoint> = emptyList()
    private var spanHours: Int = 24
    private var stepSeconds: Int = 300

    fun setPoints(newPoints: List<PanelTrafficPoint>, hours: Int = 24, step: Int = 300) {
        points = newPoints
        spanHours = hours
        stepSeconds = step.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padLeft = labelPaint.measureText("000 MiB") + 8f
        val padBottom = labelPaint.textSize * 2
        val chartWidth = width - padLeft - 8f
        val chartHeight = height - padBottom - 8f
        if (chartWidth <= 0f || chartHeight <= 0f) return

        if (points.isEmpty()) {
            canvas.drawText(context.getString(R.string.panel_stats_no_history), padLeft, height / 2f, labelPaint)
            return
        }

        val buckets = bucketize(points, spanHours, stepSeconds)
        val maxValue = niceCeiling(buckets.maxOfOrNull { max(it.download, it.upload) } ?: 1L)
        val baseline = 8f + chartHeight

        // Three grid lines: zero, half and the rounded maximum.
        for (step in 0..2) {
            val y = baseline - chartHeight * step / 2f
            canvas.drawLine(padLeft, y, padLeft + chartWidth, y, gridPaint)
            val value = maxValue * step / 2
            canvas.drawText(
                formatBytes(value),
                0f,
                y + labelPaint.textSize / 3f,
                labelPaint,
            )
        }

        val slot = chartWidth / buckets.size
        val barWidth = max(1.5f, slot * 0.36f)
        buckets.forEachIndexed { index, bucket ->
            val center = padLeft + slot * index + slot / 2f
            val downloadHeight = chartHeight * bucket.download / maxValue.toFloat()
            val uploadHeight = chartHeight * bucket.upload / maxValue.toFloat()
            if (downloadHeight <= 0f && uploadHeight <= 0f) return@forEachIndexed
            barRect.set(
                center - barWidth - 0.5f,
                baseline - downloadHeight,
                center - 0.5f,
                baseline,
            )
            canvas.drawRect(barRect, downloadPaint)
            barRect.set(center + 0.5f, baseline - uploadHeight, center + barWidth + 0.5f, baseline)
            canvas.drawRect(barRect, uploadPaint)
        }

        // Time labels at both ends of the range; a long range also needs the date.
        val first = System.currentTimeMillis() / 1000 - spanHours * 3600L
        val last = maxOf(points.maxOf { it.ts }, System.currentTimeMillis() / 1000)
        canvas.drawText(
            if (spanHours >= 24) dateFormat.format(Date(first * 1000)) else timeFormat.format(Date(first * 1000)),
            padLeft,
            height - 2f,
            labelPaint,
        )
        val endLabel = timeFormat.format(Date(last * 1000))
        canvas.drawText(
            endLabel,
            padLeft + chartWidth - labelPaint.measureText(endLabel),
            height - 2f,
            labelPaint,
        )
    }

    private class Bucket(val start: Long, var download: Long, var upload: Long)

    /**
     * Lays the samples onto a fixed grid of the requested range, so a sparse series is drawn where
     * it actually happened instead of being stretched across the whole chart. Dense series are
     * merged into at most [MAX_BARS] columns.
     */
    private fun bucketize(
        source: List<PanelTrafficPoint>,
        hours: Int,
        stepSeconds: Int,
    ): List<Bucket> {
        val rangeSeconds = hours.coerceAtLeast(1) * 3600L
        val nativeBuckets = (rangeSeconds / stepSeconds).toInt().coerceAtLeast(1)
        val bucketCount = minOf(MAX_BARS, nativeBuckets)
        val bucketSeconds = rangeSeconds / bucketCount
        val lastPoint = source.lastOrNull()?.ts ?: (System.currentTimeMillis() / 1000)
        val end = maxOf(lastPoint, System.currentTimeMillis() / 1000)
        val start = end - rangeSeconds
        val buckets = List(bucketCount) { index -> Bucket(start + bucketSeconds * index, 0L, 0L) }
        source.forEach { point ->
            val index = ((point.ts - start) / bucketSeconds).toInt()
            if (index in buckets.indices) {
                buckets[index].download += point.tx
                buckets[index].upload += point.rx
            }
        }
        return buckets
    }

    /** Rounds a value up to a clean number of KiB, MiB or GiB, so the grid reads well. */
    private fun niceCeiling(value: Long): Long {
        if (value <= 0) return 1024
        var unit = 1L
        while (value / unit >= 1024 && unit < 1024L * 1024 * 1024) unit *= 1024
        val scaled = value.toDouble() / unit
        val steps = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0)
        val nice = steps.firstOrNull { it >= scaled } ?: 1000.0
        return (nice * unit).toLong()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (value < 10) {
            String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
        } else {
            String.format(Locale.getDefault(), "%.0f %s", value, units[unit])
        }
    }

    private companion object {
        const val MAX_BARS = 48
        val PEACH = Color.parseColor("#FAB283")
        val UPLOAD = Color.parseColor("#8D9D9F")
        val GRID = Color.parseColor("#29FFFFFF")
        val LABEL = Color.parseColor("#8D9D9F")
    }
}
