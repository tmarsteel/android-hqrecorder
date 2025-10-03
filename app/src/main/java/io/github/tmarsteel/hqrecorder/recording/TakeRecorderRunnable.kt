package io.github.tmarsteel.hqrecorder.recording

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.net.Uri
import android.os.Messenger
import android.provider.MediaStore
import android.util.Log
import io.github.tmarsteel.hqrecorder.util.convertSampleToFloat32
import io.github.tmarsteel.hqrecorder.util.extractSampleBytes
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.collections.List
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
                    if (nextCommand == Command.FinishTake || (nextCommand == Command.NextTake && isRecording)) {
                        trackStates.forEach {
                            it.finishTake()
                        }
                        isRecording = false
                    }
                    if (nextCommand == Command.NextTake) {
                        isRecording = true
                        val timestamp = LocalDateTime.now()
                        trackStates.forEach {
                            it.startNewTake(timestamp)
                        }
                        currentTakeStartedAtNanos = System.nanoTime()
                    }
                    if (nextCommand == Command.StopListening) {
                        if (isRecording) {
                            Log.e(javaClass.name, "Cannot stop listening, still recording!!")
                        } else {
                            commandNeedsListeningToStop = true
                        }
                    }
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
            stopped = true
        }
        result = Result.SUCCESS
    }

    fun executeCommandSync(command: Command) {
        rcChannel.executeCommand(command, AUDIO_POLL_DELAY)
    }

    @Volatile
    var result: Result? = null
        private set

    enum class Result {
        SUCCESS,
        ERROR,
        ;
    }

    sealed class Command : NonBlockingThreadRemoteControl.RcCommand<Unit> {
        object NextTake : Command()
        object FinishTake : Command()
        object StopListening : Command()
    }

    private data class TrackInListening(
        val context: Context,
        val track: RecordingConfig.InputTrackConfig,
        val sourceFormat: AudioFormat,
        val leftOrMonoChannelSampleIndexInFrame: Int,
        val rightChannelSampleIndexInFrame: Int?,
    ) : Closeable {
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

        fun finishTake() {
            currentTake?.close()
            currentTake = null
        }

        fun startNewTake(timestamp: LocalDateTime) {
            if (currentTake != null) {
                finishTake()
            }

            currentTake = TakeInRecording(timestamp)
        }

        override fun close() {
            finishTake()
        }

        private inner class TakeInRecording(
            val takeTimestamp: LocalDateTime,
        ) : Closeable {
            private val writer = WavFileWriter(
                File.createTempFile("take_${TIMESTAMP_FORMAT.format(takeTimestamp)}", track.label),
                track.rightDeviceChannel != null,
                sourceFormat,
            )

            val mediaUri: Uri
            init {
                val mediaStoreValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "${TIMESTAMP_FORMAT.format(takeTimestamp)}_${track.label}.wav")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                    put(MediaStore.Audio.Media.IS_RECORDING, 1)
                }
                mediaUri = context.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    mediaStoreValues
                )!!
                Log.d(javaClass.name, "Recording track ${track.id} of take $takeTimestamp to $mediaUri")
            }

            fun writeSampleData(src: ByteArray, off: Int, len: Int) {
                writer.writeSampleData(src, off, len)
            }

            override fun close() {
                writer.close()
                FileOutputStream(context.contentResolver.openFileDescriptor(mediaUri, "w")!!.fileDescriptor).use { mediaStoreOut ->
                    FileInputStream(writer.targetFile).use { tmpFileIn ->
                        tmpFileIn.copyTo(mediaStoreOut)
                    }
                }
                context.contentResolver.update(
                    mediaUri,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, false)
                    },
                    null
                )
                Log.i(javaClass.name, "Copied ${writer.targetFile.absolutePath} to $mediaUri (${writer.targetFile.length()} bytes)")
                writer.targetFile.delete()
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
        val AUDIO_POLL_DELAY = 50.milliseconds

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