package com.windstrom5.rebit.data.scanner

/** A lossless audio file found while listing MediaStore or a watched folder, before it is read. */
data class DiscoveredFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val dateEpochMs: Long,
    val mediaStoreTitle: String? = null,
    val mediaStoreArtist: String? = null,
    val mediaStoreDurationMs: Long = 0L,
    val mediaStoreGenre: String? = null
)

data class ExistingStamp(val id: Long, val sizeBytes: Long, val dateEpochMs: Long)

data class ScanPlan(
    /** New or changed files: the only ones that need to be opened and parsed. */
    val toRead: List<DiscoveredFile>,
    val unchanged: Int,
    val removedPaths: List<String>
)

/** Decides what a scan has to do. Pure, so it is unit-tested on the JVM. */
object ScanPlanner {

    fun plan(
        existing: Map<String, ExistingStamp>,
        discovered: Collection<DiscoveredFile>,
        /** Tracks whose path starts with one of these belong to a source we couldn't read this time. */
        protectedPrefixes: List<String> = emptyList(),
        /** False when a source failed in a way that makes "not seen" meaningless. */
        allowPrune: Boolean = true
    ): ScanPlan {
        val seen = HashSet<String>(discovered.size * 2)
        val toRead = ArrayList<DiscoveredFile>()
        var unchanged = 0
        for (d in discovered) {
            seen += d.path
            val e = existing[d.path]
            if (e != null && e.sizeBytes == d.sizeBytes && e.dateEpochMs == d.dateEpochMs) unchanged++ else toRead += d
        }
        // An empty result is far more likely a transient permission/MediaStore hiccup than an empty library.
        val removed = if (!allowPrune || discovered.isEmpty()) emptyList() else
            existing.keys.filter { path -> path !in seen && protectedPrefixes.none { path.startsWith(it) } }
        return ScanPlan(toRead, unchanged, removed)
    }

    /** Lossless PCM, DSD, and the lossy codecs Android can decode. */
    val SUPPORTED_EXTENSIONS = setOf(
        "flac", "wav", "wave", "aif", "aiff", "aifc", "dsf", "dff",
        "m4a", "alac", "caf", "mp3", "aac", "opus", "ogg", "oga"
    )

    fun isSupportedAudio(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
}

/** Reads the Vorbis-comment block that FLAC (and Ogg) files carry their tags in. */
object VorbisComments {
    /** Keys are upper-cased; the first occurrence of a key wins. Tolerates truncated or hostile data. */
    fun parse(block: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var p = 0
        fun le32(): Int? {
            if (p + 4 > block.size) return null
            val v = (block[p].toInt() and 0xFF) or ((block[p + 1].toInt() and 0xFF) shl 8) or
                ((block[p + 2].toInt() and 0xFF) shl 16) or ((block[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        val vendorLen = le32() ?: return out
        if (vendorLen < 0 || vendorLen > block.size - p) return out   // overflow-safe: never p + len
        p += vendorLen
        val count = le32() ?: return out
        repeat(count.coerceIn(0, 10_000)) {
            val len = le32() ?: return out
            if (len < 0 || len > block.size - p) return out
            val s = String(block, p, len, Charsets.UTF_8)
            p += len
            val eq = s.indexOf('=')
            if (eq > 0) out.putIfAbsent(s.substring(0, eq).uppercase(), s.substring(eq + 1))
        }
        return out
    }

    fun title(c: Map<String, String>): String? = c["TITLE"]?.trim()?.takeIf { it.isNotEmpty() }
    fun genre(c: Map<String, String>): String? = c["GENRE"]?.trim()?.takeIf { it.isNotEmpty() }
    fun artist(c: Map<String, String>): String? =
        (c["ARTIST"] ?: c["ALBUMARTIST"] ?: c["ALBUM ARTIST"])?.trim()?.takeIf { it.isNotEmpty() }
}

/** Title/artist from tags first, then from the file name. */
object TagResolver {
    private val TRACK_NUMBER = Regex("""^\d{1,3}\s*[-._)]+\s*""")

    fun cleanArtist(artist: String?): String? {
        val t = artist?.trim() ?: return null
        if (t.isEmpty() || t.equals("<unknown>", true) || t.equals("Unknown", true) ||
            t.equals("Unknown Artist", true) || t.equals("null", true)
        ) return null
        return t
    }

    fun resolve(fileName: String, tagTitle: String?, tagArtist: String?): Pair<String, String?> {
        val fromTag = tagTitle?.trim()?.takeIf { it.isNotEmpty() }
        var artist = cleanArtist(tagArtist)
        if (fromTag != null) return fromTag to artist

        // No title tag: derive it from the file name ("03 - Title - Artist.flac" -> Title / Artist).
        val raw = fileName.substringBeforeLast('.').trim()
        val noNumber = raw.replace(TRACK_NUMBER, "").trim().ifEmpty { raw }
        var title = noNumber
        if (artist == null && noNumber.contains(" - ")) {
            val parts = noNumber.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                title = parts[0].trim()
                artist = parts[1].trim()
            }
        }
        return title to artist
    }
}
