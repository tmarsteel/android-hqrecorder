package io.github.tmarsteel.hqrecorder.recording

import android.media.AudioFormat
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.lang.AutoCloseable
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

class WavFileWriter(
    val targetFile: File,
    val stereo: Boolean,
    val audioFormat: AudioFormat,
) : AutoCloseable {
    init {
        require(audioFormat.encoding in WAV_FORMAT_TAG_BY_ANDROID_FORMAT) {
            "The given encoding (${audioFormat.encoding}) is not supported by WAV"
        }
    }
    private var preambleWritten = false

    val raf = RandomAccessFile(targetFile, "rw")

    fun assurePreamble() {
        check(!closed)
        if (preambleWritten) {
            return
        }
        preambleWritten = true

        val bitsPerSample = BITS_PER_SAMPLE.getValue(audioFormat.encoding)
        val blockAlign = ((bitsPerSample + 7u) / 8u).toUShort()

        raf.write("RIFF\u0000\u0000\u0000\u0000WAVEfmt \u0010\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII))
        raf.writeLE(WAV_FORMAT_TAG_BY_ANDROID_FORMAT.getValue(audioFormat.encoding))
        raf.writeLE((if (stereo) 2 else 1).toUShort())
        raf.writeLE(audioFormat.sampleRate)
        raf.writeLE(audioFormat.sampleRate * blockAlign.toInt())
        raf.writeLE(blockAlign)
        raf.writeLE(bitsPerSample)
        raf.write("data\u0000\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII))
    }

    private var writeBuffer = ByteBuffer.allocate(4096)
    init {
        check(writeBuffer.hasArray())
    }
    private var bufferFlushThreshold = writeBuffer.capacity() / 80

    private fun flushBuffer() {
        writeBuffer.flip()
        raf.write(writeBuffer.array(), writeBuffer.arrayOffset() + writeBuffer.position(), writeBuffer.limit() - writeBuffer.position())
        writeBuffer.clear()
    }

    /**
     * Writes the given data to the file, depleting [data]. The data must be in the format
     * as given in [audioFormat] and respect [stereo].
     */
    fun writeSampleData(data: ByteArray, off: Int, len: Int) {
        assurePreamble()
        try {
            writeBuffer.put(data, off, len)
        }
        catch (_: BufferOverflowException) {
            val nRemaining = writeBuffer.remaining()
            writeSampleData(data, off, nRemaining)
            writeSampleData(data, off + nRemaining, len - nRemaining)
            return
        }

        if (writeBuffer.remaining() <= bufferFlushThreshold) {
            flushBuffer()
        }
    }

    private var closed = false
    override fun close() {
        check(!closed)
        closed = true
        flushBuffer()
        val fileSize = raf.length()
        raf.seek(0x04)
        raf.writeLE(fileSize.toInt() - 8)
        raf.seek(0x28)
        raf.writeLE(fileSize.toInt() - 44)
        raf.close()
    }

    private companion object {
        val WAV_FORMAT_TAG_BY_ANDROID_FORMAT: Map<Int, UShort> = mapOf(
            AudioFormat.ENCODING_PCM_8BIT to 0x0001u,
            AudioFormat.ENCODING_PCM_16BIT to 0x0001u,
            AudioFormat.ENCODING_PCM_24BIT_PACKED to 0x0001u,
            AudioFormat.ENCODING_PCM_32BIT to 0x0001u,
            AudioFormat.ENCODING_PCM_FLOAT to 0x0003u,
        )

        val BITS_PER_SAMPLE: Map<Int, UShort> = mapOf(
            AudioFormat.ENCODING_PCM_8BIT to 8u,
            AudioFormat.ENCODING_PCM_16BIT to 16u,
            AudioFormat.ENCODING_PCM_24BIT_PACKED to 24u,
            AudioFormat.ENCODING_PCM_32BIT to 32u,
            AudioFormat.ENCODING_PCM_FLOAT to 32u,
        )
    }
}

private fun RandomAccessFile.writeLE(value: UShort) {
    write(value.toInt() and 0xFF)
    write(value.toInt() shr 8)
}

private fun RandomAccessFile.writeLE(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 24) and 0xFF)
}