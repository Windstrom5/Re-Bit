package com.windstrom5.rebit.data.scanner

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.windstrom5.rebit.domain.model.AudioTrackModel

/**
 * Finds and reads lossless (FLAC/WAV) files. Scanning is split in two so large libraries stay fast:
 *
 *  1. **List** ([listMediaStore], [listFolder]) — cheap: no file is opened. Folders are walked with
 *     one provider query per *directory* (not ~5 per file like DocumentFile does).
 *  2. **Read** ([read]) — opens a file and parses its header, but only for files that are new or
 *     changed since the last scan (decided by [ScanPlanner]).
 *
 * The slow MediaMetadataRetriever is no longer used per file; it is only a fallback ([readTagsSlow])
 * for tracks that have no usable tags, run after the library is already visible.
 */
class LocalAudioScanner(private val context: Context) {

    private val extractor = MetadataExtractor(context.contentResolver)

    /** Everything MediaStore knows about. Fast, but only covers files MediaStore has indexed. */
    fun listMediaStore(): List<DiscoveredFile> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED
        ) + (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) arrayOf(MediaStore.Audio.Media.GENRE) else emptyArray())
        // Lossless and DSD: always. Lossy: only real music (not ringtones/recordings) of at least
        // 45 s, so voice notes and notification sounds don't flood the library.
        val lossless = arrayOf(
            "audio/x-wav", "audio/wav", "audio/flac", "audio/x-flac", "audio/aiff", "audio/x-aiff",
            "audio/dsf", "audio/x-dsf", "audio/dff", "audio/x-dff"
        )
        val lossy = arrayOf("audio/mp4", "audio/x-m4a", "audio/m4a", "audio/mpeg", "audio/aac", "audio/ogg", "audio/opus", "audio/vorbis")
        val m = MediaStore.Audio.Media.MIME_TYPE
        val selection = "($m IN (${lossless.joinToString { "?" }})) OR " +
            "(${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 45000 AND $m IN (${lossy.joinToString { "?" }}))"
        val args = lossless + lossy
        val out = ArrayList<DiscoveredFile>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val genreCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
            val durCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val displayName = c.getString(nameCol) ?: ""
                if (!ScanPlanner.isSupportedAudio(displayName) || isMessagingAudio(displayName)) continue
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                out += DiscoveredFile(
                    path = uri.toString(),
                    name = c.getString(nameCol) ?: "Unknown",
                    sizeBytes = c.getLong(sizeCol),
                    // Same unit the library has always stored for MediaStore tracks (DATE_ADDED seconds * 1000),
                    // so unchanged files compare equal on the next scan.
                    dateEpochMs = c.getLong(dateCol) * 1000L,
                    mediaStoreTitle = if (titleCol != -1) c.getString(titleCol) else null,
                    mediaStoreArtist = if (artistCol != -1) c.getString(artistCol) else null,
                    mediaStoreGenre = if (genreCol != -1) c.getString(genreCol) else null,
                    mediaStoreDurationMs = if (durCol != -1) c.getLong(durCol) else 0L
                )
            }
        }
        return out
    }

    /**
     * Walks a folder picked via ACTION_OPEN_DOCUMENT_TREE. One query returns the id, name, type, size
     * and modified time of every child of a directory, so the whole tree costs one query per folder.
     * The file URIs are the same ones DocumentFile produced, so existing library entries still match.
     *
     * @throws SecurityException if access to the folder was revoked (e.g. the SD card was removed).
     */
    fun listFolder(treeUri: Uri, isActive: () -> Boolean = { true }, onFound: (Int) -> Unit = {}): List<DiscoveredFile> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<DiscoveredFile>()
        val pending = ArrayDeque<String>()
        pending.addLast(DocumentsContract.getTreeDocumentId(treeUri))
        while (pending.isNotEmpty() && isActive()) {
            val dirId = pending.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.addLast(docId)
                        continue
                    }
                    if (!ScanPlanner.isSupportedAudio(name)) continue
                    out += DiscoveredFile(
                        path = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                        name = name,
                        sizeBytes = if (c.isNull(3)) 0L else c.getLong(3),
                        dateEpochMs = if (c.isNull(4)) 0L else c.getLong(4)
                    )
                    if (out.size % 50 == 0) onFound(out.size)
                }
            }
        }
        onFound(out.size)
        return out
    }

    /** Voice notes from messaging apps that MediaStore sometimes marks as music. */
    private fun isMessagingAudio(name: String): Boolean {
        val l = name.lowercase()
        val lossy = !(l.endsWith(".flac") || l.endsWith(".wav") || l.endsWith(".aif") || l.endsWith(".aiff") ||
            l.endsWith(".dsf") || l.endsWith(".dff"))
        // WhatsApp "PTT-…"/"AUD-…" and Telegram "audio_…" recordings.
        return lossy && (l.startsWith("ptt-") || l.startsWith("aud-") || l.startsWith("audio_"))
    }

    /** Parses one file's header (and FLAC tags). Null for unreadable or corrupt files. */
    fun read(file: DiscoveredFile): AudioTrackModel? {
        val meta = extractor.extract(Uri.parse(file.path)) ?: probeCompressed(file) ?: return null
        val (title, artist) = TagResolver.resolve(
            fileName = file.name,
            tagTitle = meta.title ?: file.mediaStoreTitle,
            tagArtist = meta.artist ?: file.mediaStoreArtist
        )
        return AudioTrackModel(
            id = 0L,
            filePath = file.path,
            title = title,
            artist = artist,
            durationMs = meta.durationMs.takeIf { it > 0 } ?: file.mediaStoreDurationMs,
            format = meta.format,
            pcm = meta.pcm,
            fileSizeBytes = file.sizeBytes,
            dateAddedEpochMs = file.dateEpochMs,
            genre = com.windstrom5.rebit.data.genre.GenreLogic.normalize(meta.genre ?: file.mediaStoreGenre)
        )
    }

    /**
     * ALAC / AAC / MP3 / Opus / Vorbis: these containers aren't parsed by hand, so ask the
     * platform's extractor for the codec, rate, channels and duration. For m4a it is the codec
     * (audio/alac vs audio/mp4a-latm) that decides whether the file is lossless.
     */
    private fun probeCompressed(file: DiscoveredFile): MetadataExtractor.ExtractedMetadata? {
        if (com.windstrom5.rebit.playback.AudioDecoders.formatForName(file.name) == null) return null
        val ex = android.media.MediaExtractor()
        return try {
            ex.setDataSource(context, Uri.parse(file.path), null)
            val f = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: return null
            val format = when (mime) {
                "audio/alac" -> com.windstrom5.rebit.domain.model.AudioFileFormat.ALAC
                "audio/mp4a-latm", "audio/aac" -> com.windstrom5.rebit.domain.model.AudioFileFormat.AAC
                "audio/mpeg" -> com.windstrom5.rebit.domain.model.AudioFileFormat.MP3
                "audio/opus" -> com.windstrom5.rebit.domain.model.AudioFileFormat.OPUS
                "audio/vorbis" -> com.windstrom5.rebit.domain.model.AudioFileFormat.VORBIS
                else -> return null
            }
            val rate = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val channels = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val bits = if (format == com.windstrom5.rebit.domain.model.AudioFileFormat.ALAC)
                com.windstrom5.rebit.playback.MediaCodecDecoder.declaredBitDepth(f) else 16
            val durationMs = if (f.containsKey(android.media.MediaFormat.KEY_DURATION))
                f.getLong(android.media.MediaFormat.KEY_DURATION) / 1000 else 0L
            MetadataExtractor.ExtractedMetadata(format, com.windstrom5.rebit.domain.model.PcmFormat(rate, bits, channels), durationMs)
        } catch (_: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    /**
     * MediaMetadataRetriever fallback (ID3 in WAV files etc.). Slow — roughly 100 ms+ per file — so it is
     * only used on a time budget for new tracks that still have no artist after the fast pass.
     */
    fun readTagsSlow(uriString: String): Pair<String?, String?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uriString))
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            val artist = TagResolver.cleanArtist(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            )
            title to artist
        } catch (_: Exception) {
            null to null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
