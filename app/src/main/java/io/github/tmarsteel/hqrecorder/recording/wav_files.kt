package io.github.tmarsteel.hqrecorder.recording

import android.media.AudioFormat
import io.github.tmarsteel.hqrecorder.util.convertSampleToFloat32
import io.github.tmarsteel.hqrecorder.util.extractSampleBytes
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.lang.AutoCloseable
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Spliterator
import java.util.function.Consumer
import java.util.stream.Stream
import java.util.stream.StreamSupport
import kotlin.jvm.optionals.getOrNull
import kotlin.math.absoluteValue

private data object WavConstants {
    val WAV_FORMAT_TAG_BY_ANDROID_FORMAT: Map<Int, UShort> = mapOf(
        AudioFormat.ENCODING_PCM_8BIT to 0x0001u,
        AudioFormat.ENCODING_PCM_16BIT to 0x0001u,
        AudioFormat.ENCODING_PCM_24BIT_PACKED to 0x0001u,
        AudioFormat.ENCODING_PCM_32BIT to 0x0001u,
        AudioFormat.ENCODING_PCM_FLOAT to 0x0003u,
    )

    val ANDROID_ENCODING_TO_BITS_PER_SAMPLE: Map<Int, UShort> = mapOf(
        AudioFormat.ENCODING_PCM_8BIT to 8u,
        AudioFormat.ENCODING_PCM_16BIT to 16u,
        AudioFormat.ENCODING_PCM_24BIT_PACKED to 24u,
        AudioFormat.ENCODING_PCM_32BIT to 32u,
        AudioFormat.ENCODING_PCM_FLOAT to 32u,
    )

    val SIGIL_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    val SIGIL_WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
    val SIGIL_FORMAT = "fmt ".toByteArray(Charsets.US_ASCII)
    val SIGIL_DATA = "data".toByteArray(Charsets.US_ASCII)

    fun computeFrameSize(bitsPerSample: UShort, nChannels: UShort): Int {
        return (nChannels * ((bitsPerSample + 7u) / 8u)).toInt()
    }
}

class WavFileWriter(
    val targetFile: File,
    val stereo: Boolean,
    val audioFormat: AudioFormat,
) : AutoCloseable {
    init {
        require(audioFormat.encoding in WavConstants.WAV_FORMAT_TAG_BY_ANDROID_FORMAT) {
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

        val nChannels = (if (stereo) 2 else 1).toUShort()
        val bitsPerSample = WavConstants.ANDROID_ENCODING_TO_BITS_PER_SAMPLE.getValue(audioFormat.encoding)
        val blockAlign = WavConstants.computeFrameSize(bitsPerSample, nChannels).toUShort()

        raf.write(WavConstants.SIGIL_RIFF)
        raf.writeLEInt(0) // will later be fileSize - header size
        raf.write(WavConstants.SIGIL_WAVE)
        raf.write(WavConstants.SIGIL_FORMAT)
        raf.writeLEInt(16) // length of the remaining header data, fixed
        raf.writeLEUShort(WavConstants.WAV_FORMAT_TAG_BY_ANDROID_FORMAT.getValue(audioFormat.encoding))
        raf.writeLEUShort(nChannels)
        raf.writeLEInt(audioFormat.sampleRate)
        raf.writeLEInt(audioFormat.sampleRate * blockAlign.toInt())
        raf.writeLEUShort(blockAlign)
        raf.writeLEUShort(bitsPerSample)
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
        raf.writeLEInt(fileSize.toInt() - 8)
        raf.seek(0x28)
        raf.writeLEInt(fileSize.toInt() - 44)
        raf.close()
    }
}

/**
 * A stream providing audio data. Could be a [WavInputStream], but also just a wrapper around some in-memory audio data.
 */
abstract class AudioInputStream : InputStream() {
    /**
     * the number of channels in the WAV stream, a [ChannelMask] suitable for playback
     * has to be constructed externally
     */
    abstract val nChannels: UShort

    /**
     * @see [AudioFormat.getEncoding]
     */
    abstract val encoding: Int

    /**
     * The number of bytes that comprise one sample; matching with [encoding]
     */
    abstract val sampleSizeInBytes: Int

    /**
     * @see [AudioFormat.getSampleRate]
     */
    abstract val sampleRate: UInt

    /**
     * alias block-align in WAV speak
     */
    abstract val frameSizeInBytes: Int
}

class WavInputStream(val wavDataIn: InputStream) : AudioInputStream() {
    override val nChannels: UShort
    override val encoding: Int
    override val sampleSizeInBytes: Int
    override val sampleRate: UInt
    override val frameSizeInBytes: Int

    init {
        val sigilBuffer = ByteArray(4)
        readAndCheckSigil(WavConstants.SIGIL_RIFF, sigilBuffer)
        forceSkip(4) // file size minus header size, ignored
        readAndCheckSigil(WavConstants.SIGIL_WAVE, sigilBuffer)
        readAndCheckSigil(WavConstants.SIGIL_FORMAT, sigilBuffer)
        val headerSize = readLEInt()
        if (headerSize < 16) {
            throw InvalidWavFileException("Invalid header, must be at leas 16 bytes long")
        }
        val formatTag = readLEUShort().toUInt()
        nChannels = readLEUShort()
        sampleRate = readLEInt().toUInt()
        val bytesPerSecond = readLEInt()
        frameSizeInBytes = readLEUShort().toInt()
        val bitsPerSample = readLEUShort()
        forceSkip(headerSize - 16L)
        readAndCheckSigil(WavConstants.SIGIL_DATA, sigilBuffer)
        forceSkip(4) // skip data block length

        val samplesAreIntegers = when (formatTag) {
            1u -> true
            3u -> false
            else -> throw UnsupportedWavFileException("Unsupported format tag $formatTag, only 0x01 (PCM) and 0x03 (IEEE floats) are supported")
        }

        encoding = when(bitsPerSample.toUInt()) {
            8u -> if (samplesAreIntegers) AudioFormat.ENCODING_PCM_8BIT else throw UnsupportedWavFileException("Format is 8bit floats, only 32bit floats are supported")
            16u -> if (samplesAreIntegers) AudioFormat.ENCODING_PCM_16BIT else throw UnsupportedWavFileException("Format is 16bit floats, only 32bit floats are supported")
            24u -> if (samplesAreIntegers) AudioFormat.ENCODING_PCM_24BIT_PACKED else throw UnsupportedWavFileException("Format is 24bit floats, only 32bit floats are supported")
            32u -> if (samplesAreIntegers) AudioFormat.ENCODING_PCM_32BIT else AudioFormat.ENCODING_PCM_FLOAT
            else -> throw UnsupportedWavFileException("Sample size of $bitsPerSample is not supported, only 8, 16 and 32 are supported")
        }
        sampleSizeInBytes = (WavConstants.ANDROID_ENCODING_TO_BITS_PER_SAMPLE.getValue(encoding) / 8u).toInt()

        val computedFrameSizeInBytes = WavConstants.computeFrameSize(bitsPerSample, nChannels)
        if (computedFrameSizeInBytes != frameSizeInBytes) {
            throw InvalidWavFileException("The declared frame size/block align is incorrect; at $bitsPerSample bits/sample and $nChannels channels, it should be $computedFrameSizeInBytes but is $frameSizeInBytes")
        }
    }

    fun stream(): Stream<FloatArray> = StreamSupport.stream(FloatNormalizedSampleSpliterator(this), false)

    override fun read(): Int {
        return wavDataIn.read()
    }

    override fun read(b: ByteArray?): Int {
        return wavDataIn.read(b)
    }

    override fun read(b: ByteArray?, off: Int, len: Int): Int {
        return wavDataIn.read(b, off, len)
    }

    override fun available(): Int {
        return wavDataIn.available()
    }

    override fun close() {
        wavDataIn.close()
    }

    override fun mark(readlimit: Int) {
        wavDataIn.mark(readlimit)
    }

    override fun markSupported(): Boolean {
        return wavDataIn.markSupported()
    }

    override fun readAllBytes(): ByteArray? {
        return wavDataIn.readAllBytes()
    }

    override fun readNBytes(b: ByteArray?, off: Int, len: Int): Int {
        return wavDataIn.readNBytes(b, off, len)
    }

    override fun readNBytes(len: Int): ByteArray? {
        return wavDataIn.readNBytes(len)
    }

    override fun reset() {
        wavDataIn.reset()
    }

    override fun skip(n: Long): Long {
        return wavDataIn.skip(n)
    }
}

/**
 * Reads samples off of a wrapped [AudioInputStream], converts them to [AudioFormat.ENCODING_PCM_FLOAT] and emits them
 * as a [FloatArray], with one element per source channel.
 *
 * **this class always emits the same array object and changes the array whenever it retrieves new data. Users must make
 * copies of the array if the sample data should be retained.**
 */
class FloatNormalizedSampleSpliterator(val audioIn: AudioInputStream) : Spliterator<FloatArray> {
    override fun characteristics(): Int {
        return Spliterator.NONNULL
    }

    override fun estimateSize(): Long {
        return Long.MAX_VALUE
    }

    private val combinedSampleHolder = FloatArray(audioIn.nChannels.toInt())
    private val frameBufferArray = ByteArray(audioIn.frameSizeInBytes)
    private val frameBufferObj = ByteBuffer.wrap(frameBufferArray)
    private val sampleBufferArray = ByteArray(audioIn.sampleSizeInBytes)

    override fun tryAdvance(action: Consumer<in FloatArray>): Boolean {
        val nBytesRead = audioIn.readNBytes(frameBufferArray, 0, frameBufferArray.size)
        if (nBytesRead < frameBufferArray.size) {
            // EOF
            return false
        }

        frameBufferObj.clear()
        for (channelIdx in 0 until audioIn.nChannels.toInt()) {
            extractSampleBytes(frameBufferObj, audioIn.sampleSizeInBytes, channelIdx, sampleBufferArray, 0)
            val sampleValue = convertSampleToFloat32(sampleBufferArray, 0, sampleBufferArray.size, audioIn.encoding)
            combinedSampleHolder[channelIdx] = sampleValue
        }

        action.accept(combinedSampleHolder)
        return true
    }

    override fun trySplit(): Spliterator<FloatArray?>? {
        return null
    }
}

open class UnsupportedWavFileException(message: String?, cause: Throwable? = null) : IOException(message, cause)
class InvalidWavFileException(message: String?, cause: Throwable? = null) : UnsupportedWavFileException(message, cause)

private fun RandomAccessFile.writeLEUShort(value: UShort) {
    write(value.toInt() and 0xFF)
    write(value.toInt() shr 8)
}

private fun RandomAccessFile.writeLEInt(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 24) and 0xFF)
}

private fun InputStream.readLEUShort(): UShort {
    val byte1 = read().eofIfMinus1()
    val byte2 = read().eofIfMinus1()
    return ((byte2.toUInt() shl 8) or byte1.toUInt()).toUShort()
}

private fun InputStream.readLEInt(): Int {
    val byte1 = read().eofIfMinus1()
    val byte2 = read().eofIfMinus1()
    val byte3 = read().eofIfMinus1()
    val byte4 = read().eofIfMinus1()

    return ((byte4 shl 24) and -16777216) or ((byte3 shl 16) and 0x00FF0000) or ((byte2 shl 8) and 0x0000FF00) or (byte1 and 0x000000FF)
}

private fun InputStream.readAndCheckSigil(sigilToCheck: ByteArray, sigilBuffer: ByteArray) {
    check(sigilBuffer.size >= sigilToCheck.size)
    var nBytesRead = 0
    while (nBytesRead < sigilToCheck.size) {
        val nBytesReadThisTurn = read(sigilBuffer, nBytesRead, sigilToCheck.size - nBytesRead)
        if (nBytesReadThisTurn == -1) {
            throw InvalidWavFileException("Unexpected EOF when looking for sigil ${sigilToCheck.contentToString()}")
        }
        nBytesRead += nBytesReadThisTurn
    }

    val sigilIsCorrect = Arrays.equals(sigilToCheck, 0, sigilToCheck.size, sigilBuffer, 0, sigilToCheck.size)
    if (!sigilIsCorrect) {
        throw InvalidWavFileException("Expected sigil ${sigilToCheck.contentToString()}, but found different data")
    }
}

private fun Int.eofIfMinus1(): Int {
    if (this == -1) {
        throw InvalidWavFileException("Unexpected EOF", EOFException())
    }
    return this
}

private fun InputStream.forceSkip(n: Long) {
    // skipNBytes requires higher API level than targeted, so copied from JDK sources
    var nRemaining = n
    while (nRemaining > 0) {
        val ns = skip(nRemaining)
        if (ns > 0 && ns <= nRemaining) {
            // adjust number to skip
            nRemaining -= ns
        } else if (ns == 0L) { // no bytes skipped
            // read one byte to check for EOS
            if (read() == -1) {
                throw InvalidWavFileException(null, EOFException())
            }
            // one byte read so decrement number to skip
            nRemaining--
        } else { // skipped negative or too many bytes
            throw IOException("Unable to skip exactly")
        }
    }
}