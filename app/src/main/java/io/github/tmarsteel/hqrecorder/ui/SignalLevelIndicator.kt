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
import io.github.tmarsteel.hqrecorder.util.sumOfFloats
import java.util.LinkedList
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class SignalLevelIndicator(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var indicatesTrackId: Long? = null

    private var temporaryPeakSampleLastResetAt: Long = System.nanoTime()

    var temporaryPeakDuration: Duration = 2.seconds

    /**
     * true indicates that the signal has clipped
     */
    var signalHasClipped: Boolean = false
        set(value) {
            field = value
            peakLevelTextBackgroundPaint.color = if (field) {
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

    private val paintBackground = Paint().apply {
        color = context.resources.getColor(R.color.signal_level_background, null)
    }
    private val paintLevel = Paint().apply {
        color = context.resources.getColor(com.google.android.material.R.color.design_default_color_primary, null)
    }
    private val peakLevelTextBackgroundPaint = Paint()
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
    private val tempPeakAndTickLineThickness = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.0f, resources.displayMetrics).ceil()
    private val tickMarkMinHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4.0f, resources.displayMetrics).floor()

    private var channelData = arrayOf<ChannelStatus>(ChannelStatus("L"))

    /**
     * Sets the amount of channels being displayed in this indicator and the names of them.
     * This also resets the state of all channels (as if [reset] was called).
     *
     * @param names must not be empty
     */
    fun setChannelNames(names: Array<String>) {
        require(names.isNotEmpty())

        channelData = Array(names.size) { idx ->
            ChannelStatus(names[idx])
        }
        postInvalidate()
    }

    private lateinit var measuredTextTotalPeakSignalLevel: MeasuredText
    private var peakSignalLevelText: String = getSampleLevelAsDecibelText(0.0f)
        set(value) {
            field = value
            measuredTextTotalPeakSignalLevel = MeasuredText.Builder(field.toCharArray())
                .appendStyleRun(textPaint, field.length, false)
                .build()
        }
    private var totalPeakSample: Float = 0.0f
        set(value) {
            field = value.absoluteValue
            peakSignalLevelText = getSampleLevelAsDecibelText(field)
        }

    init {
        // force initialization of total peak sample
        totalPeakSample = 0.0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val reservedSpaceForTickLines = tickMarkMinHeight * 2

        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val height = if (heightMode == MeasureSpec.EXACTLY) heightSize else {
            measuredTextLongestLevelText.getBounds(0, 8, tmpRect)
            val heightForOneChannel = tmpRect.height() * 4 / 3
            val minHeight = heightForOneChannel * channelData.size + reservedSpaceForTickLines

            if (heightMode == MeasureSpec.AT_MOST) {
                minHeight.coerceAtMost(heightSize)
            } else {
                // unspecified
                minHeight
            }
        }
        this.setMeasuredDimension(width, height)

        onMeasureAllocateChannelHeights(height)

        xPosSampleIndicationEnd = width - widthForPeakLevelText
        onLayoutUpdateTicks(height)
    }

    private fun onMeasureAllocateChannelHeights(height: Int) {
        val heightPerChannelFloored = height / channelData.size
        val heightRemainder = height - (channelData.size * heightPerChannelFloored)
        var currentYPos = 0
        channelData.forEachIndexed { channelIndex, channelData ->
            val thisChannelHeight = heightPerChannelFloored + if (channelIndex <= heightRemainder) 1 else 0
            channelData.topY = currentYPos
            channelData.bottomY = currentYPos + thisChannelHeight
            currentYPos = channelData.bottomY
        }
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
        val viewWidth = measuredWidth
        val viewHeight = measuredHeight

        canvas.drawPaint(paintBackground)

        tmpRect.set((viewWidth - peakLevelTextWidth).toInt(), 0, viewWidth, viewHeight)
        canvas.drawRect(tmpRect, peakLevelTextBackgroundPaint)
        measuredTextTotalPeakSignalLevel.getBounds(0, peakSignalLevelText.length, tmpRect)
        canvas.drawText(
            peakSignalLevelText,
            viewWidth - tmpRect.width() - threeSp,
            viewHeight.toFloat() / 2 + tmpRect.height() / 2,
            textPaint
        )

        var rightmostEndXOfChannelNames = 0
        channelData.forEachIndexed { channelIndex, channelData ->
            val currentSampleXPos = sampleToXPosition(channelData.currentSample, 0.0f)
            tmpRect.set(0, channelData.topY, currentSampleXPos, channelData.bottomY)
            canvas.drawRect(tmpRect, paintLevel)

            val horizontalPositionTempPeak = sampleToXPosition(channelData.temporaryPeakSample, tempPeakAndTickLineThickness.toFloat())
            tmpRect.set(horizontalPositionTempPeak, channelData.topY, horizontalPositionTempPeak + tempPeakAndTickLineThickness, channelData.bottomY)
            canvas.drawRect(tmpRect, paintLevel)

            channelData.nameMeasuredText.getBounds(0, channelData.name.length, tmpRect)
            canvas.drawText(
                channelData.name,
                threeSp,
                channelData.bottomY.toFloat() - (channelData.height.toFloat() / 2.0f) + (tmpRect.height().toFloat() / 2.0f),
                textPaint
            )
            val endXThisChannelsName = threeSp.ceil() + tmpRect.width()
            rightmostEndXOfChannelNames = rightmostEndXOfChannelNames.coerceAtMost(endXThisChannelsName)
        }

        if (ticks.isEmpty()) {
            return
        }
        val topTickStartY = 0
        val tickTextVerticalPadding = tickTextHeight / 4.0f
        val topTickEndYWithText = (viewHeight.toFloat() / 2.0f - tickTextHeight / 2.0f - tickTextVerticalPadding).floor()
        val topTickEndYWithoutText = tickMarkMinHeight
        val bottomTickStartYWithText = (viewHeight.toFloat() / 2.0f + tickTextHeight / 2.0f + tickTextVerticalPadding).ceil()
        val bottomTickStartYWithoutText = viewHeight - tickMarkMinHeight
        val bottomTickEndY = viewHeight
        for (tick in ticks) {
            val tickXPos = decibelValueToXPosition(tick.decibels, tempPeakAndTickLineThickness.toFloat())
            tick.measuredText.getBounds(0, tick.text.length, tmpRect)
            val tickTextXPos = decibelValueToXPosition(tick.decibels, tmpRect.width().toFloat())
            val tickHasText = tickTextXPos >= rightmostEndXOfChannelNames + twoSpCeiled
            if (tickHasText) {
                tmpRect.set(tickXPos, topTickStartY, tickXPos + tempPeakAndTickLineThickness, topTickEndYWithText)
                canvas.drawRect(tmpRect, tickTextPaint)
                tmpRect.set(tmpRect.left, bottomTickStartYWithText, tmpRect.right, bottomTickEndY)
                canvas.drawRect(tmpRect, tickTextPaint)

                canvas.drawText(
                    tick.text,
                    tickTextXPos.toFloat(),
                    viewHeight.toFloat() / 2.0f + tickTextHeight / 2.0f,
                    tickTextPaint,
                )
            } else {
                tmpRect.set(tickXPos, topTickStartY, tickXPos + tempPeakAndTickLineThickness, topTickEndYWithoutText)
                canvas.drawRect(tmpRect, tickTextPaint)
                tmpRect.set(tmpRect.left, bottomTickStartYWithoutText, tmpRect.right, bottomTickEndY)
                canvas.drawRect(tmpRect, tickTextPaint)
            }
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

    /**
     * sets the level to display for each channel (matched to the data given to [setChannelNames] by index)
     *
     * @param currentSampleOfEachChannel for each channel: a sample indicative of the current level on the channel.
     * Should be the maximum sample out of of all samples passed through the channel since the last call to [update] (e.g. one buffer full of samples)
     * @param off offset to start reading [currentSampleOfEachChannel] from
     * @param len the number of samples to read from [currentSampleOfEachChannel], must be identical to the number of channels given to [setChannelNames]
     */
    fun update(currentSampleOfEachChannel: FloatArray, off: Int, len: Int) {
        if (currentSampleOfEachChannel.size < off + len) {
            throw ArrayIndexOutOfBoundsException()
        }
        require(len == channelData.size) {
            "The indicator is configured for ${channelData.size} channels, but got $len samples in ${this::update.name}"
        }

        val now = System.nanoTime()
        val timeSinceLastTempPeakSampleUpdate = (now - temporaryPeakSampleLastResetAt).nanoseconds
        val shouldResetTempPeaks:  Boolean
        if (timeSinceLastTempPeakSampleUpdate >= temporaryPeakDuration) {
            shouldResetTempPeaks = true
            temporaryPeakSampleLastResetAt = now
        } else {
            shouldResetTempPeaks = false
        }

        channelData.forEachIndexed { channelIndex, channelData ->
            channelData.update(currentSampleOfEachChannel[off + channelIndex], shouldResetTempPeaks)
        }

        postInvalidate()
    }

    /**
     * Resets the levels for all channels back to silent / -inf and clears [signalHasClipped].
     */
    fun reset() {
        channelData.forEach { it.reset() }
        totalPeakSample = 0.0f
        signalHasClipped = false
        temporaryPeakSampleLastResetAt = System.nanoTime()
        postInvalidate()
    }

    init {
        setOnClickListener {
            reset()
        }
    }

    private data class Tick(
        val decibels: Float,
        val text: String,
        val measuredText: MeasuredText,
    )

    private inner class ChannelStatus(val name: String) {
        var currentSample: Float = 0.0f

        var temporaryPeakSample: Float = 0.0f

        var peakSample: Float = 0.0f

        /**
         * Y coordinate, relative to this views position, where this channel starts
         */
        var topY: Int = 0

        /**
         * Y coordinate, relative to this views position, where this channel ends
         */
        var bottomY: Int = 0

        val height: Int get() = bottomY - topY

        val nameMeasuredText = run {
            val mtb = MeasuredText.Builder(name.toCharArray())
            if (name.isNotBlank()) {
                mtb.appendStyleRun(textPaint, name.length, false)
            }
            mtb.build()
        }

        fun update(currentSample: Float, resetTemporaryPeak: Boolean) {
            this.currentSample = currentSample
            peakSample = peakSample.coerceAtLeast(currentSample)
            totalPeakSample = totalPeakSample.coerceAtLeast(currentSample)
            signalHasClipped = signalHasClipped || peakSample.absoluteValue == Float.MAX_VALUE

            if (resetTemporaryPeak) {
                temporaryPeakSample = 0.0f
            }
            temporaryPeakSample = temporaryPeakSample.coerceAtLeast(currentSample)
        }

        fun reset() {
            currentSample = 0.0f
            peakSample = 0.0f
            temporaryPeakSample = 0.0f
        }
    }
}

private fun Float.ceil(): Int {
    return ceil(toDouble()).toInt()
}

private fun Float.floor(): Int {
    return floor(toDouble()).toInt()
}