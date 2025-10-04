package io.github.tmarsteel.hqrecorder.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.text.MeasuredText
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.util.getRelationToInDecibels
import io.github.tmarsteel.hqrecorder.util.getSampleLevelAsDecibelText
import io.github.tmarsteel.hqrecorder.util.sumOfFloats
import java.util.LinkedList
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

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
        }

    var temporaryPeakSampleLastResetAt: Long = System.nanoTime()
        private set
    var temporaryPeakSample: Float = 0.0f
        set(value) {
            field = value.absoluteValue
        }

    var peakSample: Float = 0.0f
        set(value) {
            field = value
            peakSignalLevelText = getSampleLevelAsDecibelText(value.absoluteValue)
            val textChars = peakSignalLevelText.toCharArray()
            measuredTextPeakSignalLevel = MeasuredText.Builder(textChars)
                .appendStyleRun(textPaint, textChars.size, false)
                .build()
        }

    var temporaryPeakDuration: Duration = 2.seconds

    /**
     * true indicates that the signal has clipped
     */
    var clipIndicator: Boolean = false
        set(value) {
            field = value
            levelTextBackgroundPaint.color = if (field) {
                context.resources.getColor(R.color.signal_level_db_background_clipped, null)
            } else {
                context.resources.getColor(R.color.signal_level_db_background, null)
            }
        }

    var minVolumeInDecibels: Float = -80.0f
        set(value) {
            require(value < 0.0f)
            field = value
        }

    var preferredTickSpacingInDecibels: UInt = 6u
        set(value) {
            require(value > 0u)
            field = value
        }

    private var tmpRect = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val reservedSpaceForTickLines = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10.0f, resources.displayMetrics).floor()

        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val height = if (heightMode == MeasureSpec.EXACTLY) heightSize else {
            measuredTextChannelIndicator.getBounds(0, channelIndicator.length, tmpRect)
            var minHeight = tmpRect.height() + reservedSpaceForTickLines
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

        xPosSampleIndicationEnd = width - widthForPeakLevelText
        onLayoutUpdateTicks(height)
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
    private val tickTextPaint = Paint().apply {
        color = textPaint.color
        textSize = textPaint.textSize * 0.66f
    }
    private val threeSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 3.0f, resources.displayMetrics)
    private val twoSpCeiled = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 2.0f, resources.displayMetrics).ceil()
    private val measuredTextLongestLevelText = MeasuredText.Builder("-100.0dB".toCharArray())
        .appendStyleRun(textPaint, 8, false)
        .build()

    init {
        // assure initialization, needs the paint objects
        peakSample = peakSample
        channelIndicator = channelIndicator
        clipIndicator = clipIndicator
    }

    private val xPosSampleIndicationStart: Int = 0
    private val peakLevelTextWidth: Float = measuredTextLongestLevelText.getWidth(0, 8)
    private var widthForPeakLevelText: Int = (peakLevelTextWidth + threeSp * 2).ceil()
    private var xPosSampleIndicationEnd: Int = 0

    private var ticks = emptyList<Tick>()
    private val minSpacingBetweenTicks: Float = threeSp
    private val tickTextHeight = tickTextPaint.textSize
    private var tickIndicatorHeight = 5.0f
    private var topTickIndicatorEndY = 0
    private var bottomTickIndicatorStartY = 0

    private fun onLayoutUpdateTicks(height: Int) {
        val widthOfAreaIndicatedWithTicks = xPosSampleIndicationEnd - xPosSampleIndicationStart
        val newTicks = (0 downTo minVolumeInDecibels.floor().toInt())
            .step(preferredTickSpacingInDecibels.toInt())
            .map { levelForTick ->
                val text = levelForTick.toString(10) + "dB"
                val mt = MeasuredText.Builder(text.toCharArray())
                    .appendStyleRun(tickTextPaint, text.length, false)
                    .build()

                Tick(levelForTick.toFloat(), text, mt)
            }
            .toCollection(LinkedList())

        while (newTicks.isNotEmpty()) {
            val widthNeededForTicks: Float = newTicks.sumOfFloats { it.measuredText.getWidth(0, it.text.length) } + (minSpacingBetweenTicks * (newTicks.size - 1).toFloat())

            if (widthNeededForTicks <= widthOfAreaIndicatedWithTicks.toFloat()) {
                break
            }

            val ticksListIt = newTicks.listIterator()
            while (ticksListIt.hasNext()) {
                ticksListIt.next()
                ticksListIt.remove()
                if (ticksListIt.hasNext()) {
                    ticksListIt.next()
                }
            }
        }

        ticks = newTicks

        tickIndicatorHeight = ((height.toFloat() - tickTextHeight - tickTextHeight / 2.0f) / 2.0f)
        topTickIndicatorEndY = tickIndicatorHeight.ceil()
        bottomTickIndicatorStartY = (height.toFloat() - tickIndicatorHeight).ceil()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(paintBackground)

        canvas.getClipBounds(tmpRect)
        val canvasWidth = tmpRect.width()
        val canvasHeight = tmpRect.height()

        val currentSampleXPos = sampleToXPosition(sampleValue, 0.0f)
        tmpRect.set(tmpRect.left, tmpRect.top, tmpRect.left + currentSampleXPos, tmpRect.bottom)
        canvas.drawRect(tmpRect, paintLevel)

        tmpRect.set((canvasWidth - peakLevelTextWidth).toInt(), 0, canvasWidth, canvasHeight)
        canvas.drawRect(tmpRect, levelTextBackgroundPaint)

        val horizontalPositionPeakDecibel = sampleToXPosition(temporaryPeakSample, twoSpCeiled.toFloat())
        tmpRect.set(horizontalPositionPeakDecibel, 0, horizontalPositionPeakDecibel + twoSpCeiled, canvasHeight)
        canvas.drawRect(tmpRect, paintLevel)

        measuredTextPeakSignalLevel.getBounds(0, peakSignalLevelText.length, tmpRect)
        canvas.drawText(
            peakSignalLevelText,
            canvasWidth - tmpRect.width() - threeSp,
            canvasHeight.toFloat() / 2 + tmpRect.height() / 2,
            textPaint
        )

        measuredTextChannelIndicator.getBounds(0, channelIndicator.length, tmpRect)
        val channelIndicatorEndX = (threeSp * 2.0f).ceil() + tmpRect.width()
        canvas.drawText(
            channelIndicator,
            threeSp,
            canvasHeight.toFloat() / 2 + tmpRect.height() / 2,
            textPaint
        )

        for (tick in ticks) {
            val topAndBottomIndicatorsXPos = decibelValueToXPosition(tick.decibels, twoSpCeiled.toFloat())
            tmpRect.set(topAndBottomIndicatorsXPos, 0, topAndBottomIndicatorsXPos + twoSpCeiled, topTickIndicatorEndY)
            canvas.drawRect(tmpRect, tickTextPaint)

            tmpRect.set(topAndBottomIndicatorsXPos, bottomTickIndicatorStartY, topAndBottomIndicatorsXPos + twoSpCeiled, height)
            canvas.drawRect(tmpRect, tickTextPaint)

            tick.measuredText.getBounds(0, tick.text.length, tmpRect)
            val textLeftEdge = decibelValueToXPosition(tick.decibels, tmpRect.width().toFloat())
            if (textLeftEdge < channelIndicatorEndX && channelIndicator.isNotBlank()) {
                // tick label would overlap channel indicator
                continue
            }

            canvas.drawText(
                tick.text,
                textLeftEdge.toFloat(),
                canvasHeight.toFloat() / 2 + tmpRect.height() / 2,
                tickTextPaint
            )
        }
    }

    /**
     * see [decibelValueToXPosition]; used after converting [sampleValue] to a decibel value
     */
    private fun sampleToXPosition(sampleValue: Float, indicatorWidth: Float): Int {
        val sampleDecibels = sampleValue.getRelationToInDecibels(Float.MAX_VALUE).coerceIn(minVolumeInDecibels, 0.0f)
        return decibelValueToXPosition(sampleDecibels, indicatorWidth)
    }

    /**
     * @param indicatorWidth may be 0
     * @return the pixel between [xPosSampleIndicationStart] and [xPosSampleIndicationEnd]
     * where to start drawing an indicator of width [indicatorWidth] so that its center aligns
     * as closely as possible with the [decibels], according to [minVolumeInDecibels].
     */
    private fun decibelValueToXPosition(decibels: Float, indicatorWidth: Float): Int {
        val centerPositionOfIndicator = xPosSampleIndicationStart + ((1.0f - decibels / minVolumeInDecibels) * (xPosSampleIndicationEnd - xPosSampleIndicationStart).toFloat())
        val indicatorLeftEdge = (centerPositionOfIndicator.ceil() - indicatorWidth / 2.0f).floor()

        return indicatorLeftEdge
    }

    fun update(peakSampleOfBatch: Float) {
        if (sampleValue == peakSampleOfBatch) {
            return
        }

        sampleValue = peakSampleOfBatch
        peakSample = peakSample.coerceAtLeast(peakSampleOfBatch)
        clipIndicator = clipIndicator || peakSample.absoluteValue == Float.MAX_VALUE

        val now = System.nanoTime()
        val timeSinceLastTempPeakSampleUpdate = (now - temporaryPeakSampleLastResetAt).nanoseconds
        if (timeSinceLastTempPeakSampleUpdate >= temporaryPeakDuration) {
            temporaryPeakSample = 0.0f
            temporaryPeakSampleLastResetAt = now
        }
        temporaryPeakSample = temporaryPeakSample.coerceAtLeast(peakSampleOfBatch)

        postInvalidate()
    }

    fun reset() {
        sampleValue = 0.0f
        peakSample = 0.0f
        temporaryPeakSample = 0.0f
        clipIndicator = false
        postInvalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) {
            return false
        }

        reset()
        return true
    }

    private data class Tick(
        val decibels: Float,
        val text: String,
        val measuredText: MeasuredText,
    )
}

private fun Float.ceil(): Int {
    return ceil(toDouble()).toInt()
}

private fun Float.floor(): Int {
    return floor(toDouble()).toInt()
}