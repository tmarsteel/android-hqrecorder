package io.github.tmarsteel.hqrecorder.recording

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.time.Duration

/**
 * Is a reference to a completed take (see [TakeRecorderRunnable.Command.FinishTake]) and offers
 * functionality to assure that take is in the devices [android.provider.MediaStore].
 */
class TakeToMediaStoreMover(
    private val context: Context,
    private val takeFiles: List<TakeFile>,
    private val finishedAt: Long,
) {
    @Volatile
    private var state = State.MOVABLE

    /**
     * @throws TakeDiscardedException
     */
    fun assureFilesAreInMediaStore() {
        if (state == State.DISCARDED) {
            throw TakeDiscardedException()
        }

        state = State.MOVED

        takeFiles
            .filter { it.dataFile.exists() /* deleted = already moved */ }
            .forEach(this::moveFileToMediaStore)
    }

    /**
     * @throws TakeMovedException
     */
    fun discard() {
        if (state == State.MOVED) {
            throw TakeMovedException()
        }
        state = State.DISCARDED

        takeFiles.forEach { it.dataFile.delete() }
    }

    fun shouldDiscard(lifetime: Duration): Boolean {
        return finishedAt + lifetime.inWholeNanoseconds < System.nanoTime()
    }

    private fun moveFileToMediaStore(takeFile: TakeFile) {
        val mediaStoreValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, takeFile.displayNameForMediaStore)
            put(MediaStore.Audio.Media.IS_RECORDING, 1)
        }
        val mediaUri = context.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            mediaStoreValues
        )!!
        FileOutputStream(context.contentResolver.openFileDescriptor(mediaUri, "w")!!.fileDescriptor).use { mediaStoreOut ->
            FileInputStream(takeFile.dataFile).use { tmpFileIn ->
                tmpFileIn.copyTo(mediaStoreOut)
            }
        }
        Log.i(javaClass.name, "Copied ${takeFile.dataFile.absolutePath} to $mediaUri (${takeFile.dataFile.length()} bytes)")
        takeFile.dataFile.delete()
    }

    data class TakeFile(
        val dataFile: File,
        val displayNameForMediaStore: String,
    )

    class TakeMovedException : RuntimeException()
    class TakeDiscardedException : RuntimeException()

    private enum class State {
        MOVABLE,
        MOVED,
        DISCARDED,
        ;
    }
}