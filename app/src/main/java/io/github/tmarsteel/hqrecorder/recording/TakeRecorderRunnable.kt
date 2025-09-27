package io.github.tmarsteel.hqrecorder.recording

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.provider.MediaStore
import android.util.Log
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
    val audioRecord: AudioRecord,
    val buffer: ByteBuffer,
) : Runnable {
    private var stopSignal = AtomicBoolean(false)

    override fun run() {
        val takeTimestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT)
        val trackWriters = tracks.associateWith { trackConfig ->
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "${takeTimestamp}_${trackConfig.label}.wav")
                put(MediaStore.Audio.Media.IS_PENDING, 0)
                put(MediaStore.Audio.Media.IS_RECORDING, 1)
            }
            /*val targetUri = context.applicationContext.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                values
            )!!*/

            //val fd = context.applicationContext.contentResolver.openFileDescriptor(targetUri, "w")!!
            //val outputStream = FileOutputStream(fd.fileDescriptor)
            val file = context.externalCacheDir!!.toPath().resolve("${takeTimestamp}_${trackConfig.label}.wav").toFile()
            val outputStream = RandomAccessFile(file, "rw")
            Log.i(javaClass.name, "Writing to $file")
            WavFileWriter(outputStream, trackConfig.rightDeviceChannel != null, audioRecord.format)
        }

        val channelMappings = Channel.getChannelToSampleIndexMapping(audioRecord.format)

        try {
            while (!stopSignal.getAcquire()) {
                buffer.clear()
                val readResult = audioRecord.read(buffer, buffer.capacity(), AudioRecord.READ_NON_BLOCKING)
                if (readResult < 0) {
                    result = Result.ERROR
                    return
                }
                buffer.limit(readResult)
                val framesInBuffer = buffer.remaining() / audioRecord.format.frameSizeInBytes
                val timeInBuffer = (framesInBuffer * 1000 / audioRecord.format.sampleRate).milliseconds
                val timeSpentWriting = measureTime {
                    if (trackWriters.size == 1) {
                        val (config, writer) = trackWriters.entries.single()
                        val leftIsFirst = channelMappings[config.leftOrMonoDeviceChannel] == 0
                        val rightIsAbsentOrSecond = config.rightDeviceChannel == null || channelMappings[config.rightDeviceChannel!!] == 1
                        if (leftIsFirst && rightIsAbsentOrSecond) {
                            // we can write the data to to file as-is
                            writer.writeSampleData(buffer)
                        }
                    }
                }
                Log.i(TakeRecorderRunnable::class.qualifiedName, "Wrote $timeInBuffer in $timeSpentWriting")

                Thread.sleep(50)
            }
        }
        finally {
            try {
                trackWriters.values.forEach {
                    it.close()
                }
            }
            catch (e: Throwable) {
                result = Result.ERROR
                throw e
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

    private fun extractSampleBytes(
        compoundFrame: ByteBuffer,
        audioFormat: AudioFormat,
        channel: Channel,
        into: ByteArray
    ) {
        TODO()
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
    }
}