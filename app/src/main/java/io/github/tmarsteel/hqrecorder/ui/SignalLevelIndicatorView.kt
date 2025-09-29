package io.github.tmarsteel.hqrecorder.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.text.MeasuredText
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.util.getRelationToInDecibels
import io.github.tmarsteel.hqrecorder.util.getSampleLevelAsDecibelText
import kotlin.math.absoluteValue

class SignalLevelIndicatorView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var indicatesTrackId: Long? = null
    /** false = left, true = true */
    var indicatesLeftOrRight: Boolean = false

    private var measuredTextChannelIndicator: MeasuredText = MeasuredText.Builder(charArrayOf())
        .build()
    private var measuredTextPeakSignalLevel: MeasuredText = measuredTextChannelIndicator
    private var peakSignalLevelText: String = getSampleLevelAsDecibelText(0.0f)

    var channelIndicator: String = "L"
        set(value) {
            field = value
            var mtBuilder = MeasuredText.Builder(value.toCharArray())
            if (value.isNotEmpty()) {
                mtBuilder = mtBuilder.appendStyleRun(textPaint, value.length, false)
            }
            measuredTextChannelIndicator = mtBuilder.build()
            postInvalidate()
        }

    /**
     * the level to display, as a sample value adjusted to 32BIT integer
     */
    var sampleValue: Float = 0.0f
        set(value) {
            field = value.absoluteValue
            postInvalidate()
        }

    var peakSample: Float = 0.0f
        set(value) {
            field = value
            peakSignalLevelText = getSampleLevelAsDecibelText(value.absoluteValue)
            val textChars = peakSignalLevelText.toCharArray()
            measuredTextPeakSignalLevel = MeasuredText.Builder(textChars)
                .appendStyleRun(textPaint, textChars.size, false)
                .build()
            postInvalidate()
        }

    /**
     * true indicates that the signal has clipped
     */
    var clipIndicator: Boolean = false
        set(value) {
            field = value
            postInvalidate()
            levelTextBackgroundPaint.color = if (field) {
                context.resources.getColor(R.color.signal_level_db_background_clipped, null)
            } else {
                context.resources.getColor(R.color.signal_level_db_background, null)
            }
        }

    var minVolumeInDecibels: Float = -80.0f

    private var tmpRect = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val height = if (heightMode == MeasureSpec.EXACTLY) heightSize else {
            measuredTextChannelIndicator.getBounds(0, channelIndicator.length, tmpRect)
            var minHeight = tmpRect.height()
            measuredTextPeakSignalLevel.getBounds(0, peakSignalLevelText.length, tmpRect)
            minHeight = minHeight.coerceAtLeast(tmpRect.height())
            minHeight += minHeight / 3 // for spacing

            if (heightMode == MeasureSpec.AT_MOST) {
                minHeight.coerceAtMost(heightSize)
            } else {
                // unspecified
                minHeight
            }
        }
        this.setMeasuredDimension(width, height)
    }

    private val paintBackground = Paint().apply {
        color = context.resources.getColor(R.color.signal_level_background, null)
    }
    private val paintLevel = Paint().apply {
        color = context.resources.getColor(com.google.android.material.R.color.design_default_color_primary, null)
    }
    private val levelTextBackgroundPaint = Paint()
    private val textPaint = Paint().apply {
        color = context.resources.getColor(com.google.android.material.R.color.design_default_color_on_primary, null)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14.0f, resources.displayMetrics)
    }
    private val threeSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 3.0f, resources.displayMetrics)
    private val measuredTextLongestLevelText = MeasuredText.Builder("-100.0dB".toCharArray())
        .appendStyleRun(textPaint, 8, false)
        .build()

    init {
        // assure initialization, needs the paint objects
        peakSample = peakSample
        channelIndicator = channelIndicator
        clipIndicator = clipIndicator
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(paintBackground)

        canvas.getClipBounds(tmpRect)
        val canvasWidth = tmpRect.width()
        val canvasHeight = tmpRect.height()

        val levelTextWidth = measuredTextLongestLevelText.getWidth(0, 4) + threeSp * 2

        val sampleDecibels = sampleValue.getRelationToInDecibels(Float.MAX_VALUE).coerceAtLeast(minVolumeInDecibels)
        val nPixelsForLevel = ((1.0f - sampleDecibels / minVolumeInDecibels) * canvasWidth.toFloat() - levelTextWidth).toInt()
        tmpRect.set(tmpRect.left, tmpRect.top, tmpRect.left + nPixelsForLevel, tmpRect.bottom)
        canvas.drawRect(tmpRect, paintLevel)

        tmpRect.set((canvasWidth - levelTextWidth).toInt(), 0, canvasWidth, canvasHeight)
        canvas.drawRect(tmpRect, levelTextBackgroundPaint)

        measuredTextPeakSignalLevel.getBounds(0, peakSignalLevelText.length, tmpRect)
        canvas.drawText(
            peakSignalLevelText,
            canvasWidth - tmpRect.width() - threeSp,
            canvasHeight.toFloat() / 2 + tmpRect.height() / 2,
            textPaint
        )

        measuredTextChannelIndicator.getBounds(0, channelIndicator.length, tmpRect)
        canvas.drawText(
            channelIndicator,
            threeSp,
            canvasHeight.toFloat() / 2 + tmpRect.height() / 2,
            textPaint
        )
    }

    fun update(maxSampleOfBatch: Float) {
        if (sampleValue == maxSampleOfBatch) {
            return
        }

        sampleValue = maxSampleOfBatch
        peakSample = peakSample.coerceAtLeast(maxSampleOfBatch)
        clipIndicator = clipIndicator || peakSample.absoluteValue == Float.MAX_VALUE
        postInvalidate()
    }

    fun reset() {
        sampleValue = 0.0f
        peakSample = 0.0f
        clipIndicator = false
        postInvalidate()
    }
}