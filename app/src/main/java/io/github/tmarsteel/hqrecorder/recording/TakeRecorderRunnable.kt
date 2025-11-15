package io.github.tmarsteel.hqrecorder.recording

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Messenger
import android.util.Log
import io.github.tmarsteel.hqrecorder.util.convertSampleToFloat32
import io.github.tmarsteel.hqrecorder.util.extractSampleBytes
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.measureTime

class TakeRecorderRunnable private constructor(
    val context: Context,
    val tracks: List<RecordingConfig.InputTrackConfig>,
    val channelMask: ChannelMask,
    val audioRecord: AudioRecord,
    val subscribers: Set<Messenger>,
    val buffer: ByteBuffer,
) : Runnable {
    @Volatile
    private lateinit var rcChannel: NonBlockingThreadRemoteControl

    private var stopped = false

    @Volatile
    var isRecording = false
        private set

    private fun initializeTrackListeningStates(): List<TrackInListening> {
        val sourceFormat = audioRecord.format
        return tracks.map { track ->
            TrackInListening(
                this@TakeRecorderRunnable.context,
                track,
                sourceFormat,
                channelMask.channels.indexOf(track.leftOrMonoDeviceChannel),
                track.rightDeviceChannel?.let(channelMask.channels::indexOf),
            )
        }
    }

    override fun run() {
        val sampleSizeInBytes = SAMPLE_SIZE_IN_BYTES_BY_ENCODING[audioRecord.format.encoding]
            ?: throw RuntimeException("Unsupported recording ${audioRecord.format.encoding}, shouldn't have been queued.")
        val trackStates = initializeTrackListeningStates()

        Log.i(javaClass.name, "Starting to listen")
        try {
            var currentTakeStartedAtNanos: Long = 0
            listenLoop@while (true) {
                buffer.clear()
                val readResult = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_NON_BLOCKING)
                if (readResult < 0) {
                    Log.e(javaClass.name, "Failed to read from ${AudioRecord::class.simpleName}: $readResult")
                    result = Result.ERROR
                    return
                }
                buffer.limit(readResult)
                val frameSizeInBytes = audioRecord.format.frameSizeInBytes
                val framesInBuffer = buffer.remaining() / frameSizeInBytes
                val timeInBuffer = (framesInBuffer * 1000 / audioRecord.format.sampleRate).milliseconds
                trackStates.forEach { it.resetStatusAccumulator() }
                val timeSpentWriting = measureTime {
                    bufferProcessingLoop@while (buffer.remaining() > 0) {
                        check(buffer.remaining() % frameSizeInBytes == 0)
                        for (trackState in trackStates) {
                            trackState.useDataFromFrame(buffer, sampleSizeInBytes)
                        }
                        if (buffer.position() + frameSizeInBytes < buffer.limit()) {
                            buffer.position(buffer.position() + frameSizeInBytes)
                        } else {
                            break@bufferProcessingLoop
                        }
                    }
                }
                val loadPercentage = if (timeInBuffer.isPositive()) {
                    ((timeSpentWriting / timeInBuffer) * 100.0).toUInt()
                } else 0u
                Log.d(javaClass.name, "Processing $timeInBuffer of audio in $timeSpentWriting (ioLoad = $loadPercentage)")

                var commandNeedsListeningToStop = false
                rcChannel.processNextCommand { nextCommand ->
                    val pendingMove: TakeToMediaStoreMover?
                    if (isRecording) {
                        val finishedAt = System.nanoTime()
                        pendingMove = TakeToMediaStoreMover(
                            context,
                            trackStates.map {
                                it.finishTake()
                            },
                            finishedAt,
                        )
                        isRecording = false
                    } else {
                        pendingMove = null
                    }

                    when (nextCommand) {
                        is Command.FinishTake -> {
                            if (nextCommand.startNext) {
                                val timestamp = LocalDateTime.now()
                                trackStates.forEach {
                                    it.startNewTake(timestamp)
                                }
                                currentTakeStartedAtNanos = System.nanoTime()
                                isRecording = true
                            }
                        }
                        Command.StopListening -> {
                            if (!isRecording) {
                                commandNeedsListeningToStop = true
                            }
                        }
                    }

                    return@processNextCommand Command.UnsavedTakeResponse(pendingMove)
                }

                if (commandNeedsListeningToStop) {
                    break@listenLoop
                }

                val statusMessage = RecordingStatusServiceMessage(
                    true,
                    isRecording,
                    loadPercentage,
                    trackStates.associate { trackState ->
                        trackState.track.id to Pair(
                            trackState.leftStatusSample,
                            trackState.rightStatusSample.takeIf { trackState.track.rightDeviceChannel != null }
                        )
                    },
                    if (isRecording) {
                        (System.nanoTime() - currentTakeStartedAtNanos).nanoseconds
                    } else {
                        0.milliseconds
                    }
                )
                subscribers.forEach {
                    it.send(RecordingStatusServiceMessage.buildMessage(statusMessage))
                }

                Thread.sleep(AUDIO_POLL_DELAY.inWholeMilliseconds)
            }
        }
        catch (e: Throwable) {
            result = Result.ERROR
            throw e
        }
        finally {
            for (trackState in trackStates) {
                try {
                    trackState.flushDataToDisk()
                }
                catch (suppressed: Throwable) {}
            }

            stopped = true
        }
        result = Result.SUCCESS
    }

    fun <R> executeCommandSync(command: NonBlockingThreadRemoteControl.RcCommand<R>): R {
        check(command is Command)
        return rcChannel.executeCommand(command, AUDIO_POLL_DELAY * 1.1)
    }

    @Volatile
    var result: Result? = null
        private set

    enum class Result {
        SUCCESS,
        ERROR,
        ;
    }

    sealed class Command {
        data class FinishTake(val startNext: Boolean) : Command(), NonBlockingThreadRemoteControl.RcCommand<UnsavedTakeResponse>
        object StopListening : Command(), NonBlockingThreadRemoteControl.RcCommand<UnsavedTakeResponse>

        data class UnsavedTakeResponse(val mover: TakeToMediaStoreMover?)
    }

    private data class TrackInListening(
        val context: Context,
        val track: RecordingConfig.InputTrackConfig,
        val sourceFormat: AudioFormat,
        val leftOrMonoChannelSampleIndexInFrame: Int,
        val rightChannelSampleIndexInFrame: Int?,
    ) {
        private var currentTake: TakeInRecording? = null

        var leftStatusSample: Float = 0.0f
            private set
        var rightStatusSample: Float = 0.0f
            private set

        /**
         * see [leftStatusSample] and [rightStatusSample]
         */
        fun resetStatusAccumulator() {
            leftStatusSample = 0.0f
            rightStatusSample = 0.0f
        }

        private val copyBuffer = ByteArray(if (rightChannelSampleIndexInFrame != null) 8 else 4)

        /**
         * Extracts the samples relevant for this track from [compoundFrame]. Accumulates the samples into the internal
         * status accumulator. If currently recording, also writes the samples to the current file.
         */
        fun useDataFromFrame(compoundFrame: ByteBuffer, sampleSizeInBytes: Int) {
            extractSampleBytes(compoundFrame, sampleSizeInBytes, leftOrMonoChannelSampleIndexInFrame, copyBuffer, 0)
            val leftSample = convertSampleToFloat32(copyBuffer, 0, sampleSizeInBytes, sourceFormat.encoding)
            leftStatusSample = accumulateSampleForStatus(leftStatusSample, leftSample)
            var allSamplesSizeInBytes = sampleSizeInBytes

            if (rightChannelSampleIndexInFrame != null) {
                extractSampleBytes(compoundFrame, sampleSizeInBytes, rightChannelSampleIndexInFrame, copyBuffer, sampleSizeInBytes)
                val rightSample = convertSampleToFloat32(copyBuffer, sampleSizeInBytes, sampleSizeInBytes, sourceFormat.encoding)
                rightStatusSample = accumulateSampleForStatus(rightStatusSample, rightSample)
                allSamplesSizeInBytes += sampleSizeInBytes
            }

            currentTake?.writeSampleData(copyBuffer, 0, allSamplesSizeInBytes)
        }

        private fun accumulateSampleForStatus(accumulator: Float, nextSample: Float): Float {
            return max(accumulator, nextSample)
        }

        fun flushDataToDisk() {
            currentTake?.flush()
        }

        fun finishTake(): TakeToMediaStoreMover.TakeFile {
            check(currentTake != null)
            currentTake!!.close()
            val data = currentTake!!.moverData
            currentTake = null
            return data
        }

        fun startNewTake(timestamp: LocalDateTime) {
            check(currentTake == null)
            currentTake = TakeInRecording(timestamp)
        }

        private inner class TakeInRecording(
            takeTimestamp: LocalDateTime,
        ) : Closeable {
            private val writer = WavFileWriter(
                File.createTempFile("take_${TIMESTAMP_FORMAT.format(takeTimestamp)}_track${track.id}", "wav"),
                track.rightDeviceChannel != null,
                sourceFormat,
            )

            val moverData = TakeToMediaStoreMover.TakeFile(
                writer.targetFile,
                "${TIMESTAMP_FORMAT.format(takeTimestamp)}_${track.label}.wav",
            )

            fun writeSampleData(src: ByteArray, off: Int, len: Int) {
                writer.writeSampleData(src, off, len)
            }

            fun flush() {
                writer.flush()
            }

            override fun close() {
                writer.close()
            }
        }
    }

    companion object {
        val TIMESTAMP_FORMAT = DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral('_')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .toFormatter(Locale.US)

        val SAMPLE_SIZE_IN_BYTES_BY_ENCODING = mapOf(
            AudioFormat.ENCODING_PCM_8BIT to 1,
            AudioFormat.ENCODING_PCM_16BIT to 2,
            AudioFormat.ENCODING_PCM_24BIT_PACKED to 3,
            AudioFormat.ENCODING_PCM_32BIT to 4,
            AudioFormat.ENCODING_PCM_FLOAT to 4,
        )

        const val MAX_SAMPLE_24BIT_INT = 0x00FFFFFF.toFloat()
        const val MAX_SAMPLE_32BIT_INT = 0xFFFFFFFF.toDouble()

        /**
         * Delay between two polls to [AudioRecord.read]
         */
        val AUDIO_POLL_DELAY = 40.milliseconds

        fun setUpNewThread(
            context: Context,
            tracks: List<RecordingConfig.InputTrackConfig>,
            channelMask: ChannelMask,
            audioRecord: AudioRecord,
            subscribers: Set<Messenger>,
            buffer: ByteBuffer = ByteBuffer.allocateDirect(audioRecord.format.frameSizeInBytes * audioRecord.format.sampleRate),
            threadName: String = "TakeRecorder"
        ): Pair<Thread, TakeRecorderRunnable> {
            val runnable = TakeRecorderRunnable(
                context,
                tracks,
                channelMask,
                audioRecord,
                subscribers,
                buffer,
            )
            val thread = Thread(runnable, threadName)
            runnable.rcChannel = NonBlockingThreadRemoteControl(thread)
            return Pair(thread, runnable)
        }
    }
}