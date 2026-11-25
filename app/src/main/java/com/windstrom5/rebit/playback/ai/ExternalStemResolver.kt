package com.windstrom5.rebit.playback.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.windstrom5.rebit.domain.model.AudioFileFormat
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.model.KaraokeModeType

/** A real, pre-separated stem file found next to a track — plays back
 *  bit-perfect with zero real-time DSP, so unlike the on-device DSP-rendered
 *  cache it works even through the Custom USB Direct engine. */
data class ResolvedStem(val uri: String, val format: AudioFileFormat)

/**
 * Finds a genuinely neural-separated stem sitting next to a track — the
 * "Studio Quality" tier: someone ran tools/demucs_separator.py (a real wrapper
 * around Meta's Demucs) on desktop and copied the result onto the phone, or
 * pushed it via that script's --push option. When one is found, playback needs
 * no DSP at all, because the separation already happened offline with an
 * actual trained model — this is the only tier in this app that is real neural
 * separation, as opposed to [SpectralSeparationEngine]'s DSP heuristic.
 *
 * Two independent lookup strategies, since a track's [AudioTrackModel.filePath]
 * can come from either indexing path this app uses:
 *  - MediaStore-indexed tracks (the common case — anything the library scanner
 *    found via the system media index): queried by RELATIVE_PATH + DISPLAY_NAME.
 *  - SAF "watched folder" tracks (manually picked folders, not necessarily
 *    MediaStore-indexed): resolved via DocumentsContract sibling reconstruction,
 *    which only works for providers that embed a parseable tree/document id
 *    (true for AOSP's own ExternalStorageProvider, not guaranteed for others).
 * Both are best-effort and fail safe — any failure just means no stem is
 * found, falling through to the DSP tier, never a crash.
 */
class ExternalStemResolver(private val context: Context) {

    /** Suffixes checked for the "no vocals" file, in priority order. Demucs'
     *  own default output is literally named "no_vocals" — supporting that
     *  directly means a person can push Demucs' output as-is with no renaming. */
    private val instrumentalSuffixes = listOf("_no_vocals", "_instrumental")
    private val vocalSuffixes = listOf("_vocals", "_acapella")
    private val supportedExtensions = listOf("flac", "wav")

    fun resolve(track: AudioTrackModel, mode: KaraokeModeType): ResolvedStem? {
        if (mode == KaraokeModeType.OFF) return null
        val suffixes = if (mode == KaraokeModeType.VOCAL_ISOLATION) vocalSuffixes else instrumentalSuffixes

        return try {
            val uri = Uri.parse(track.filePath)
            when {
                uri.authority == MediaStore.AUTHORITY -> resolveViaMediaStore(uri, suffixes)
                else -> resolveViaDocumentsContract(uri, suffixes)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Stem resolution skipped for ${track.title}: ${e.message}")
            null
        }
    }

    // --- Strategy A: MediaStore-indexed tracks ---------------------------------

    private fun resolveViaMediaStore(uri: Uri, suffixes: List<String>): ResolvedStem? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val (displayName, relativePath) = resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val name = if (nameCol >= 0) cursor.getString(nameCol) else null
            val path = if (pathCol >= 0) cursor.getString(pathCol) else null
            name to path
        } ?: return null

        if (displayName == null) return null
        val baseName = displayName.substringBeforeLast('.')

        // Checked in order: (1) same folder as the original track — for anyone
        // who copies stems next to the source file by hand; (2) the fixed
        // folder tools/demucs_separator.py's --push-to-phone writes to, which
        // doesn't require the stems to share the original track's folder at all.
        val candidateFolders = listOfNotNull(relativePath, FIXED_STEM_RELATIVE_PATH).distinct()

        for (suffix in suffixes) {
            for (ext in supportedExtensions) {
                val candidateName = "$baseName$suffix.$ext"
                for (folder in candidateFolders) {
                    val found = queryByNameInFolder(candidateName, folder)
                    if (found != null) return ResolvedStem(found.toString(), extensionToFormat(ext))
                }
            }
        }
        return null
    }

    private fun queryByNameInFolder(displayName: String, relativePath: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
        return resolver.query(collection, projection, selection, arrayOf(displayName, relativePath), null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            if (idCol < 0) return null
            val id = cursor.getLong(idCol)
            android.content.ContentUris.withAppendedId(collection, id)
        }
    }

    // --- Strategy B: SAF "watched folder" tracks -------------------------------

    /**
     * Reconstructs a sibling document URI by editing the document id embedded in
     * a tree-scoped SAF URI (`.../tree/<treeId>/document/<docId>`). Works when
     * the provider's document id is "root:relative/path" (true for AOSP's
     * ExternalStorageProvider, the common case for on-device folders) — any
     * other id shape or authority just falls through to null.
     */
    private fun resolveViaDocumentsContract(uri: Uri, suffixes: List<String>): ResolvedStem? {
        if (!uri.pathSegments.contains("tree")) return null
        val docId = try { DocumentsContract.getDocumentId(uri) } catch (_: Exception) { return null }
        val colonIdx = docId.indexOf(':')
        if (colonIdx < 0) return null
        val root = docId.substring(0, colonIdx)
        val relPath = docId.substring(colonIdx + 1)
        val lastSlash = relPath.lastIndexOf('/')
        val dir = if (lastSlash >= 0) relPath.substring(0, lastSlash) else ""
        val fileName = if (lastSlash >= 0) relPath.substring(lastSlash + 1) else relPath
        val baseName = fileName.substringBeforeLast('.')

        for (suffix in suffixes) {
            for (ext in supportedExtensions) {
                val candidateRelPath = if (dir.isEmpty()) "$baseName$suffix.$ext" else "$dir/$baseName$suffix.$ext"
                val candidateDocId = "$root:$candidateRelPath"
                val candidateUri = try {
                    DocumentsContract.buildDocumentUriUsingTree(uri, candidateDocId)
                } catch (_: Exception) {
                    null
                } ?: continue

                val exists = try {
                    context.contentResolver.openInputStream(candidateUri)?.use { true } ?: false
                } catch (_: Exception) {
                    false
                }
                if (exists) return ResolvedStem(candidateUri.toString(), extensionToFormat(ext))
            }
        }
        return null
    }

    private fun extensionToFormat(ext: String): AudioFileFormat =
        if (ext.equals("flac", ignoreCase = true)) AudioFileFormat.FLAC else AudioFileFormat.WAV

    companion object {
        private const val TAG = "ExternalStemResolver"
        /** Matches tools/demucs_separator.py's --push-to-phone destination. */
        private const val FIXED_STEM_RELATIVE_PATH = "Music/BitPerfect_Stems/"
    }
}
