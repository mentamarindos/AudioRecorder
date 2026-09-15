package com.dimowner.audiorecorder.v2.audio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.dimowner.audiorecorder.v2.data.model.Record
import com.dimowner.audiorecorder.v2.di.qualifiers.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces a playable snapshot of a recording that is still in progress.
 *
 * A file being recorded cannot be played as-is:
 *  - WAV is written behind a 44-byte all-zero placeholder header that is only patched on stop.
 *  - M4A / 3GP are written by [android.media.MediaRecorder], which only writes the container's
 *    `moov` atom when the recording is stopped.
 *
 * To play the audio captured so far we copy the current file into the app cache and hand the copy
 * to [BrokenRecordRestorer], which knows how to turn each of those shapes into a readable file.
 * The original recording file is never modified, so an interrupted or resumed recording keeps the
 * exact state the recorder expects.
 *
 * Recordings live either as a plain file path or as a `content://` uri (a user picked SAF folder),
 * so the copy goes through [ContentResolver] whenever the source is not a plain file.
 */
@Singleton
class RecordingPreviewProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val restorer: BrokenRecordRestorer,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Copies [record]'s file and makes the copy playable.
     *
     * @return the playable file, or null when a preview could not be produced (missing/empty
     *         source, copy failure, or an unrecoverable container).
     */
    suspend fun createPreviewFile(record: Record): File? = withContext(ioDispatcher) {
        clearPreviewFiles()

        val directory = File(context.cacheDir, PREVIEW_DIRECTORY).apply { mkdirs() }
        // Unique name per preview: a late cleanup of an earlier preview can then never delete the
        // file the current preview is playing.
        val copy = File(directory, "${PREVIEW_FILE_NAME}_${uniqueSuffix()}.${previewExtension(record)}")

        if (!copySourceTo(record.path, copy)) {
            copy.delete()
            return@withContext null
        }

        // For WAV this rewrites the placeholder header in place; for M4A/3GP it re-muxes the
        // partial container into a valid one. It replaces its input, so the playable result is
        // still at [copy] when it succeeds.
        val result = restorer.restoreFile(
            filePath = copy.absolutePath,
            sampleRate = record.sampleRate,
            channelCount = record.channelCount,
            bitrate = record.bitrate,
        )

        val isPlayable = result is BrokenRecordRestorer.RestoreResult.Success ||
            result is BrokenRecordRestorer.RestoreResult.AlreadyReadable

        if (isPlayable && copy.exists() && copy.length() > 0L) {
            Timber.d("Preview: ready (${copy.length()} bytes) from ${record.path}")
            copy
        } else {
            Timber.e("Preview: could not make the recording playable: $result")
            copy.delete()
            null
        }
    }

    /**
     * Copies [source] (a file path or a `content://` uri) into [target].
     *
     * @return true when [target] holds a non-empty copy of the source.
     */
    private fun copySourceTo(source: String, target: File): Boolean {
        return try {
            when (Uri.parse(source).scheme?.lowercase()) {
                null, "", ContentResolver.SCHEME_FILE -> {
                    val file = File(source)
                    if (!file.exists() || file.length() <= 0L) {
                        Timber.e("Preview: source recording is missing or empty: $source")
                        false
                    } else {
                        file.copyTo(target, overwrite = true)
                        true
                    }
                }
                else -> {
                    val input = context.contentResolver.openInputStream(Uri.parse(source))
                    if (input == null) {
                        Timber.e("Preview: cannot open source recording: $source")
                        false
                    } else {
                        input.use { sourceStream ->
                            target.outputStream().use { targetStream ->
                                sourceStream.copyTo(targetStream)
                            }
                        }
                        target.exists() && target.length() > 0L
                    }
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "Preview: failed to copy the in-progress recording")
            false
        } catch (e: SecurityException) {
            Timber.e(e, "Preview: no access to the in-progress recording")
            false
        }
    }

    /**
     * Extension for the cached copy. [Record.format] holds the recorder's format name, but it can
     * carry a mime type or a dotted extension depending on how the record was created, so only the
     * last, alphanumeric segment is kept.
     */
    private fun previewExtension(record: Record): String {
        val raw = record.format
            .substringAfterLast('/')
            .substringAfterLast('.')
            .lowercase()
        return if (raw.isNotEmpty() && raw.all { it.isLetterOrDigit() }) raw else DEFAULT_EXTENSION
    }

    /** Deletes a single file produced by [createPreviewFile]. Safe to call at any time. */
    fun deletePreviewFile(file: File) {
        if (!file.exists()) return
        if (!file.delete()) {
            Timber.w("Preview: failed to delete cached file ${file.absolutePath}")
        }
    }

    /** Removes every file produced by [createPreviewFile]. Safe to call at any time. */
    fun clearPreviewFiles() {
        val directory = File(context.cacheDir, PREVIEW_DIRECTORY)
        if (!directory.exists()) return
        directory.listFiles()?.forEach { file ->
            if (!file.delete()) {
                Timber.w("Preview: failed to delete cached file ${file.absolutePath}")
            }
        }
    }

    /** Keeps every preview copy on its own file name, see [createPreviewFile]. */
    private fun uniqueSuffix(): Long = System.nanoTime()

    companion object {
        private const val PREVIEW_DIRECTORY = "recording_preview"
        private const val PREVIEW_FILE_NAME = "preview"
        private const val DEFAULT_EXTENSION = "wav"
    }
}
