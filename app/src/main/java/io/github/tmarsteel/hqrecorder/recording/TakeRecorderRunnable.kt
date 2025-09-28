package io.github.tmarsteel.hqrecorder.recording

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

class TakeRecorderRunnable(
    val context: Context,
    val tracks: List<RecordingConfig.InputTrackConfig>,
    val channelMask: ChannelMask,
    val audioRecord: AudioRecord,
    val buffer: ByteBuffer,
) : Runnable {
    private var stopSignal = AtomicBoolean(false)

    private fun initializeTrackRecordingStates(): List<TrackInRecording> {
        val takeTimestamp = LocalDateTime.now()

        return tracks.map { track ->
            val targetFile = File.createTempFile("take_$takeTimestamp", track.label)
            TrackInRecording(
                this@TakeRecorderRunnable.context,
                takeTimestamp = takeTimestamp,
                track = track,
                writer = WavFileWriter(targetFile, track.rightDeviceChannel != null, audioRecord.format),
                channelMask.channels.indexOf(track.leftOrMonoDeviceChannel),
                track.rightDeviceChannel?.let(channelMask.channels::indexOf),
            )
        }
    }

    override fun run() {
        val sampleSizeInBytes = SAMPLE_SIZE_IN_BYTES_BY_ENCODING[audioRecord.format.encoding]
            ?: throw RuntimeException("Unsupported recording ${audioRecord.format.encoding}, shouldn't have been queued.")
        val trackStates = initializeTrackRecordingStates()

        try {
            while (!stopSignal.getAcquire()) {
                buffer.clear()
                val readResult = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_NON_BLOCKING)
                if (readResult < 0) {
                    result = Result.ERROR
                    return
                }
                buffer.limit(readResult)
                val frameSizeInBytes = audioRecord.format.frameSizeInBytes
                val framesInBuffer = buffer.remaining() / frameSizeInBytes
                val timeInBuffer = (framesInBuffer * 1000 / audioRecord.format.sampleRate).milliseconds
                val timeSpentWriting = measureTime {
                    while (buffer.remaining() > 0) {
                        check(buffer.remaining() % frameSizeInBytes == 0)
                        for (trackState in trackStates) {
                            trackState.writeDataFromFrame(buffer, sampleSizeInBytes)
                        }
                        if (buffer.position() + frameSizeInBytes < buffer.limit()) {
                            buffer.position(buffer.position() + frameSizeInBytes)
                        }
                    }
                }

                Thread.sleep(50)
            }
        }
        finally {
            var closeEx: Exception? = null
            trackStates.forEach { trackState ->
                try {
                    trackState.close()
                } catch (ex: Exception) {
                    if (closeEx == null) {
                        closeEx = ex
                    } else {
                        closeEx.addSuppressed(ex)
                    }
                }
            }
            if (closeEx != null) {
                result = Result.ERROR
                throw closeEx
            }
        }
        result = Result.SUCCESS
        Log.i(javaClass.name, "Finished writing")
    }

    fun stop() {
        stopSignal.setRelease(true)
    }

    @Volatile
    var result: Result? = null
        private set

    enum class Result {
        SUCCESS,
        ERROR,
        ;
    }

    companion object {
        val TIMESTAMP_FORMAT = DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendValue(ChronoField.MONTH_OF_YEAR)
            .appendValue(ChronoField.DAY_OF_MONTH)
            .appendLiteral('_')
            .appendValue(ChronoField.HOUR_OF_DAY)
            .appendValue(ChronoField.MINUTE_OF_HOUR)
            .appendValue(ChronoField.SECOND_OF_MINUTE)
            .toFormatter(Locale.US)

        val SAMPLE_SIZE_IN_BYTES_BY_ENCODING = mapOf(
            AudioFormat.ENCODING_PCM_8BIT to 1,
            AudioFormat.ENCODING_PCM_16BIT to 2,
            AudioFormat.ENCODING_PCM_24BIT_PACKED to 3,
            AudioFormat.ENCODING_PCM_32BIT to 4,
            AudioFormat.ENCODING_PCM_FLOAT to 4,
        )
    }

    private data class TrackInRecording(
        val context: Context,
        val takeTimestamp: LocalDateTime,
        val track: RecordingConfig.InputTrackConfig,
        val writer: WavFileWriter,
        val leftOrMonoChannelSampleIndexInFrame: Int,
        val rightChannelSampleIndexInFrame: Int?,
    ) : Closeable {
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

        private val copyBuffer = ByteArray(if (rightChannelSampleIndexInFrame != null) 8 else 4)
        fun writeDataFromFrame(compoundFrame: ByteBuffer, sampleSizeInBytes: Int) {
            extractSampleBytes(compoundFrame, sampleSizeInBytes, leftOrMonoChannelSampleIndexInFrame, copyBuffer, 0)
            if (rightChannelSampleIndexInFrame != null) {
                extractSampleBytes(compoundFrame, sampleSizeInBytes, rightChannelSampleIndexInFrame, copyBuffer, sampleSizeInBytes)
            }
            writer.writeSampleData(copyBuffer, 0, copyBuffer.size)
        }

        /**
         * Given a buffer that points to the start of a frame where a single sample is [sampleSizeInBytes] long,
         * writes the bytes that correspond to the sample with index [sampleIndexInFrame] to [target], starting at index [off].
         * @param sampleSizeInBytes see [AudioFormat.getEncoding] and [SAMPLE_SIZE_IN_BYTES_BY_ENCODING]
         * @param target should be 4 bytes of size to be able to accommodate all encodings
         */
        private fun extractSampleBytes(
            compoundFrame: ByteBuffer,
            sampleSizeInBytes: Int,
            sampleIndexInFrame: Int,
            target: ByteArray,
            off: Int,
        ) {
            val indexOfFirst = sampleIndexInFrame * sampleSizeInBytes
            val indexOfLast = indexOfFirst + sampleSizeInBytes - 1
            if (compoundFrame.remaining() < indexOfLast) {
                throw BufferUnderflowException()
            }

            compoundFrame.get(target, off + indexOfFirst, sampleSizeInBytes)
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
            writer.targetFile.delete()
        }
    }
}