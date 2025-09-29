package io.github.tmarsteel.hqrecorder.recording

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.net.Uri
import android.os.Messenger
import android.provider.MediaStore
import android.util.Log
import io.github.tmarsteel.hqrecorder.recording.TakeRecorderRunnable.Companion.SAMPLE_SIZE_IN_BYTES_BY_ENCODING
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

class TakeRecorderRunnable(
    val context: Context,
    val tracks: List<RecordingConfig.InputTrackConfig>,
    val channelMask: ChannelMask,
    val audioRecord: AudioRecord,
    val subscribers: Set<Messenger>,
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(audioRecord.format.frameSizeInBytes * audioRecord.format.sampleRate),
) : Runnable {
    private var stopped = false

    @Volatile
    var isRecording = false
        private set

    private val commandQueue = LinkedBlockingQueue<Command>()

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
                val loadPercentage = ((timeSpentWriting / timeInBuffer) * 100).toUInt()
                Log.d(javaClass.name, "Processing $timeInBuffer of audio in $timeSpentWriting (ioLoad = $loadPercentage)")

                val nextCommand = commandQueue.poll()
                if (nextCommand == Command.FINISH_TAKE || (nextCommand == Command.NEXT_TAKE && isRecording)) {
                    trackStates.forEach {
                        it.finishTake()
                    }
                    isRecording = false
                }
                if (nextCommand == Command.NEXT_TAKE) {
                    isRecording = true
                    val timestamp = LocalDateTime.now()
                    trackStates.forEach {
                        it.startNewTake(timestamp)
                    }
                }
                if (nextCommand == Command.STOP_LISTENING) {
                    if (isRecording) {
                        Log.e(javaClass.name, "Cannot stop listening, still recording!!")
                    } else {
                        break@listenLoop
                    }
                }

                val statusMessage = RecordingStatusServiceMessage.buildMessage(RecordingStatusServiceMessage(
                    true,
                    isRecording,
                    loadPercentage,
                    trackStates.associate { trackState ->
                        trackState.track.id to Pair(
                            trackState.leftStatusSample,
                            trackState.rightStatusSample.takeIf { trackState.track.rightDeviceChannel != null }
                        )
                    }
                ))
                subscribers.forEach {
                    it.send(statusMessage)
                }

                Thread.sleep(50)
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

    fun sendCommand(command: Command) {
        check(!stopped)
        commandQueue.put(command)
    }

    @Volatile
    var result: Result? = null
        private set

    enum class Result {
        SUCCESS,
        ERROR,
        ;
    }

    enum class Command {
        NEXT_TAKE,
        FINISH_TAKE,
        STOP_LISTENING,
        ;
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
            val leftSample = convertSampleToFloat32(copyBuffer, 0, sampleSizeInBytes)
            leftStatusSample = accumulateSampleForStatus(leftStatusSample, leftSample)

            if (rightChannelSampleIndexInFrame != null) {
                extractSampleBytes(compoundFrame, sampleSizeInBytes, rightChannelSampleIndexInFrame, copyBuffer, sampleSizeInBytes)
                val rightSample = convertSampleToFloat32(copyBuffer, sampleSizeInBytes, sampleSizeInBytes)
                rightStatusSample = accumulateSampleForStatus(rightStatusSample, rightSample)
            }

            currentTake?.writeSampleData(copyBuffer, 0, copyBuffer.size)
        }

        /**
         * reads one sample of the size [sampleSizeInBytes] from [src] starting at [off] and converts it to a sample of the format
         * [AudioFormat.ENCODING_PCM_FLOAT].
         */
        private fun convertSampleToFloat32(src: ByteArray, off: Int, sampleSizeInBytes: Int): Float {
            return when (sourceFormat.encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> {
                    assert(sampleSizeInBytes == 1)
                    (src[off].toFloat().absoluteValue / Byte.MAX_VALUE.toFloat()) * Float.MAX_VALUE
                }
                AudioFormat.ENCODING_PCM_16BIT -> {
                    assert(sampleSizeInBytes == 2)
                    // the input data is LE, but java runs on BE
                    val byte0 = src[off + 0].toInt() and 0xFF
                    val byte1 = src[off + 1].toInt() and 0xFF
                    val value = ((byte1 shl 8) or byte0).toShort()
                    (value.toFloat().absoluteValue / (Short.MAX_VALUE.toFloat())) * Float.MAX_VALUE
                }
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    assert(sampleSizeInBytes == 2)
                    // the input data is LE, but java runs on BE
                    val byte0 = src[off + 0].toInt() and 0xFF
                    val byte1 = src[off + 1].toInt() and 0xFF
                    val byte2 = src[off + 2].toInt() and 0xFF
                    val value = (byte2 shl 16) or (byte1 shl 8) or byte0
                    (value.toFloat().absoluteValue / MAX_SAMPLE_24BIT_INT) * Float.MAX_VALUE
                }
                AudioFormat.ENCODING_PCM_32BIT -> {
                    // the input data is LE, but java runs on BE
                    val byte0 = src[off + 0].toInt() and 0xFF
                    val byte1 = src[off + 1].toInt() and 0xFF
                    val byte2 = src[off + 2].toInt() and 0xFF
                    val byte3 = src[off + 3].toInt() and 0xFF
                    val value = (byte3 shl 24) or (byte2 shl 16) or (byte1 shl 8) or byte0
                    ((value.toDouble().absoluteValue / MAX_SAMPLE_32BIT_INT) * Float.MAX_VALUE.toDouble()).toFloat()
                }
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val byte0 = src[off + 0].toInt() and 0xFF
                    val byte1 = src[off + 1].toInt() and 0xFF
                    val byte2 = src[off + 2].toInt() and 0xFF
                    val byte3 = src[off + 3].toInt() and 0xFF
                    Float.fromBits((byte3 shl 24) or (byte2 shl 16) or (byte1 shl 8) or byte0)
                }
                else -> throw RuntimeException("unsupported format")
            }
        }

        private fun accumulateSampleForStatus(accumulator: Float, nextSample: Float): Float {
            return max(accumulator, nextSample)
        }

        /**
         * Given a buffer that points to the start of a frame where a single sample is [sampleSizeInBytes] long,
         * writes the bytes that correspond to the sample with index [sampleIndexInFrame] to [dst], starting at index [dstOff].
         * @param sampleSizeInBytes see [AudioFormat.getEncoding] and [SAMPLE_SIZE_IN_BYTES_BY_ENCODING]
         * @param dst should be 4 bytes of size to be able to accommodate all encodings
         */
        private fun extractSampleBytes(
            compoundFrame: ByteBuffer,
            sampleSizeInBytes: Int,
            sampleIndexInFrame: Int,
            dst: ByteArray,
            dstOff: Int,
        ) {
            val indexOfFirst = sampleIndexInFrame * sampleSizeInBytes
            val indexOfLast = indexOfFirst + sampleSizeInBytes - 1
            if (compoundFrame.remaining() < indexOfLast) {
                throw BufferUnderflowException()
            }

            for (byteIndex in 0 until sampleSizeInBytes) {
                dst[dstOff + byteIndex] = compoundFrame.get(compoundFrame.position() + indexOfFirst + byteIndex)
            }
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
    }
}