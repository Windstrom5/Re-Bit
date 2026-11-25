package com.windstrom5.rebit.domain.repository

import android.net.Uri
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.model.WatchedFolder
import com.windstrom5.rebit.domain.model.ScanProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface AudioLibraryRepository {
    fun observeTracks(): Flow<List<AudioTrackModel>>
    /** Runs a scan right now and returns the library size. Used by the background worker. */
    suspend fun rescanLibrary(): Int

    /** Live scan state (listing / reading / done). */
    val scanProgress: StateFlow<ScanProgress> get() = MutableStateFlow(ScanProgress())

    /** Queues a scan in the background (WorkManager) and returns immediately. */
    fun requestBackgroundRescan() {}
    suspend fun getTrack(id: Long): AudioTrackModel?

    fun observeWatchedFolders(): Flow<List<WatchedFolder>>
    suspend fun addWatchedFolder(uri: Uri, displayName: String)
    suspend fun removeWatchedFolder(id: Long)
}
