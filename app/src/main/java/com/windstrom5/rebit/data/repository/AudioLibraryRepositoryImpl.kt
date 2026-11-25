package com.windstrom5.rebit.data.repository

import android.content.Context
import android.net.Uri
import com.windstrom5.rebit.data.local.db.dao.AudioTrackDao
import com.windstrom5.rebit.data.local.db.dao.WatchedFolderDao
import com.windstrom5.rebit.data.local.db.entity.AudioTrackEntity
import com.windstrom5.rebit.data.local.db.entity.WatchedFolderEntity
import com.windstrom5.rebit.data.scanner.DiscoveredFile
import com.windstrom5.rebit.data.scanner.ExistingStamp
import com.windstrom5.rebit.data.scanner.LibraryScanScheduler
import com.windstrom5.rebit.data.scanner.LocalAudioScanner
import com.windstrom5.rebit.data.scanner.ScanPlanner
import com.windstrom5.rebit.domain.model.ScanPhase
import com.windstrom5.rebit.domain.model.ScanProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.windstrom5.rebit.domain.model.AudioFileFormat
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.model.PcmFormat
import com.windstrom5.rebit.domain.model.WatchedFolder
import com.windstrom5.rebit.domain.repository.AudioLibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val READ_PARALLELISM = 4
private const val BATCH_SIZE = 100
private const val ENRICH_MAX = 300
private const val ENRICH_BUDGET_MS = 60_000L

class AudioLibraryRepositoryImpl(
    private val context: Context,
    private val dao: AudioTrackDao,
    private val watchedFolderDao: WatchedFolderDao,
    private val scanner: LocalAudioScanner
) : AudioLibraryRepository {

    override fun observeTracks(): Flow<List<AudioTrackModel>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeWatchedFolders(): Flow<List<WatchedFolder>> =
        watchedFolderDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun addWatchedFolder(uri: Uri, displayName: String) = withContext(Dispatchers.IO) {
        // Persist permission across app/device restarts — without this, the URI stops
        // being readable the next time the process is killed and relaunched.
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers don't support persistable permissions; the folder will
            // still work for this session, just won't survive a process restart.
        }
        watchedFolderDao.insert(
            WatchedFolderEntity(uriString = uri.toString(), displayName = displayName, dateAddedEpochMs = System.currentTimeMillis())
        )
        Unit
    }

    override suspend fun removeWatchedFolder(id: Long) = withContext(Dispatchers.IO) {
        watchedFolderDao.deleteById(id)
        Unit
    }

    private val _progress = MutableStateFlow(ScanProgress())
    override val scanProgress: StateFlow<ScanProgress> = _progress
    private val scanMutex = Mutex()

    override fun requestBackgroundRescan() = LibraryScanScheduler.runNow(context)

    /**
     * Incremental scan: list everything cheaply, then open only files that are new or changed, in
     * parallel batches that are written to the DB as they finish (so tracks appear while scanning).
     *
     * Pruning is deliberately conservative: it is skipped if a source failed, tracks from an
     * unreadable watched folder (e.g. a removed SD card) are kept, and deletes are chunked so a big
     * library can't hit SQLite's bound-variable limit.
     */
    override suspend fun rescanLibrary(): Int = scanMutex.withLock {
        withContext(Dispatchers.IO) { runScan() }
    }

    private suspend fun runScan(): Int = coroutineScope {
        _progress.value = ScanProgress(phase = ScanPhase.LISTING)
        try {
            val existingEntities = dao.getAllEntities()
            val existing = existingEntities.associate { it.filePath to ExistingStamp(it.id, it.fileSizeBytes, it.dateAddedEpochMs) }

            // ── 1. List (no file is opened) ──
            val discovered = LinkedHashMap<String, DiscoveredFile>()
            var allowPrune = true
            try {
                scanner.listMediaStore().forEach { discovered.putIfAbsent(it.path, it) }
            } catch (e: Exception) {
                allowPrune = false // can't tell what's missing if MediaStore itself failed
            }
            _progress.update { it.copy(discovered = discovered.size) }

            val protectedPrefixes = ArrayList<String>()
            for (folder in watchedFolderDao.getAll()) {
                ensureActive()
                val uri = Uri.parse(folder.uriString)
                try {
                    val base = discovered.size
                    scanner.listFolder(uri, isActive = { isActive }) { n -> _progress.update { it.copy(discovered = base + n) } }
                        .forEach { discovered.putIfAbsent(it.path, it) }
                } catch (e: Exception) {
                    // Permission revoked / storage unmounted: keep this folder's tracks, don't treat them as deleted.
                    protectedPrefixes += uri.toString() + "/document/"
                }
            }

            // ── 2. Plan: only new or changed files get read ──
            val plan = ScanPlanner.plan(existing, discovered.values, protectedPrefixes, allowPrune)
            _progress.value = ScanProgress(
                phase = ScanPhase.READING, discovered = discovered.size,
                total = plan.toRead.size, unchanged = plan.unchanged
            )

            // ── 3. Read in parallel batches, saving as we go ──
            val limiter = Semaphore(READ_PARALLELISM)
            var added = 0
            for (batch in plan.toRead.chunked(BATCH_SIZE)) {
                ensureActive()
                val read = batch.map { f ->
                    async(Dispatchers.IO) { limiter.withPermit { runCatching { scanner.read(f) }.getOrNull() } }
                }.awaitAll()
                val entities = read.filterNotNull().map { it.toEntity(existing[it.filePath]?.id ?: 0L) }
                if (entities.isNotEmpty()) dao.insertAll(entities)
                added += entities.count { existing[it.filePath] == null }
                _progress.update { it.copy(processed = it.processed + batch.size, added = added) }
            }

            // ── 4. Remove files that are gone (chunked: no SQLite variable-limit crash) ──
            plan.removedPaths.chunked(500).forEach { dao.deleteByPaths(it) }

            // ── 5. Slow tag fallback for NEW tracks that still have no artist (time-boxed) ──
            enrichNewTracks(plan.toRead.map { it.path }.filter { existing[it] == null })

            val count = dao.count()
            _progress.value = ScanProgress(
                phase = ScanPhase.DONE, discovered = discovered.size, total = plan.toRead.size,
                processed = plan.toRead.size, unchanged = plan.unchanged, added = added,
                removed = plan.removedPaths.size, libraryCount = count
            )
            count
        } catch (e: CancellationException) {
            _progress.value = ScanProgress(phase = ScanPhase.IDLE)
            throw e
        } catch (t: Throwable) {
            _progress.value = ScanProgress(phase = ScanPhase.FAILED, error = t.message ?: "Scan failed.")
            throw t
        }
    }

    /**
     * MediaMetadataRetriever is slow (~100 ms/file), so it only runs for new tracks whose header gave
     * no artist, capped in count and time. The library is already fully visible by now.
     */
    private suspend fun enrichNewTracks(newPaths: List<String>) = coroutineScope {
        if (newPaths.isEmpty()) return@coroutineScope
        val needing = newPaths.chunked(500).flatMap { dao.getByPaths(it) }.filter { it.artist == null }.take(ENRICH_MAX)
        if (needing.isEmpty()) return@coroutineScope
        _progress.update { it.copy(phase = ScanPhase.ENRICHING, total = needing.size, processed = 0) }
        val deadline = System.currentTimeMillis() + ENRICH_BUDGET_MS
        for (track in needing) {
            ensureActive()
            if (System.currentTimeMillis() > deadline) break
            val (title, artist) = scanner.readTagsSlow(track.filePath)
            if (artist != null || title != null) dao.updateTags(track.id, title ?: track.title, artist)
            _progress.update { it.copy(processed = it.processed + 1) }
        }
    }

    override suspend fun getTrack(id: Long): AudioTrackModel? = withContext(Dispatchers.IO) {
        dao.getById(id)?.toDomain()
    }

    private fun AudioTrackEntity.toDomain() = AudioTrackModel(
        id = id,
        filePath = filePath,
        title = title,
        artist = artist,
        durationMs = durationMs,
        format = AudioFileFormat.valueOf(format),
        pcm = PcmFormat(sampleRateHz, bitDepth, channels),
        fileSizeBytes = fileSizeBytes,
        dateAddedEpochMs = dateAddedEpochMs,
        genre = genre,
        genreIsAuto = genreSource == "AUTO"
    )

    private fun AudioTrackModel.toEntity(preservedId: Long) = AudioTrackEntity(
        id = preservedId,
        filePath = filePath,
        title = title,
        artist = artist,
        durationMs = durationMs,
        format = format.name,
        sampleRateHz = pcm.sampleRateHz,
        bitDepth = pcm.bitDepth,
        channels = pcm.channels,
        fileSizeBytes = fileSizeBytes,
        dateAddedEpochMs = dateAddedEpochMs,
        genre = genre,
        genreSource = if (genre != null) (if (genreIsAuto) "AUTO" else "TAG") else null
    )

    private fun WatchedFolderEntity.toDomain() = WatchedFolder(
        id = id,
        uriString = uriString,
        displayName = displayName,
        dateAddedEpochMs = dateAddedEpochMs
    )
}
