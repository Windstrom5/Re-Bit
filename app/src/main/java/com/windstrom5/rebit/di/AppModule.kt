package com.windstrom5.rebit.di

import com.windstrom5.rebit.benchmark.BenchmarkRunner
import com.windstrom5.rebit.benchmark.CsvExporter
import com.windstrom5.rebit.benchmark.DacRateBenchmark
import com.windstrom5.rebit.benchmark.ExperimentOrchestrator
import com.windstrom5.rebit.benchmark.ValidityThreatsDetector
import com.windstrom5.rebit.data.local.db.AppDatabase
import com.windstrom5.rebit.data.repository.AudioLibraryRepositoryImpl
import com.windstrom5.rebit.data.repository.BenchmarkRepositoryImpl
import com.windstrom5.rebit.data.repository.DacProfileRepositoryImpl
import com.windstrom5.rebit.data.repository.LyricsRepository
import com.windstrom5.rebit.data.scanner.LocalAudioScanner
import com.windstrom5.rebit.data.settings.SettingsRepository
import com.windstrom5.rebit.domain.engine.AdaptiveOptimizationEngine
import com.windstrom5.rebit.domain.engine.AnalyticsEngine
import com.windstrom5.rebit.domain.engine.PlaybackIntegrityEngine
import com.windstrom5.rebit.domain.engine.StatisticsEngine
import com.windstrom5.rebit.domain.repository.AudioLibraryRepository
import com.windstrom5.rebit.domain.repository.BenchmarkRepository
import com.windstrom5.rebit.domain.repository.DacProfileRepository
import com.windstrom5.rebit.domain.usecase.AddWatchedFolderUseCase
import com.windstrom5.rebit.domain.usecase.AnalyzeDacUseCase
import com.windstrom5.rebit.domain.usecase.ExportBenchmarkCsvUseCase
import com.windstrom5.rebit.domain.usecase.FetchDescriptorTreeUseCase
import com.windstrom5.rebit.domain.usecase.GetAnalyticsSummaryUseCase
import com.windstrom5.rebit.domain.usecase.NextTrackUseCase
import com.windstrom5.rebit.domain.usecase.ObserveDacProfilesUseCase
import com.windstrom5.rebit.domain.usecase.ObserveLibraryUseCase
import com.windstrom5.rebit.domain.usecase.ObservePlaybackStateUseCase
import com.windstrom5.rebit.domain.usecase.ObserveQueueUseCase
import com.windstrom5.rebit.domain.usecase.ObserveWatchedFoldersUseCase
import com.windstrom5.rebit.domain.usecase.PausePlaybackUseCase
import com.windstrom5.rebit.domain.usecase.PreviousTrackUseCase
import com.windstrom5.rebit.domain.usecase.RemoveWatchedFolderUseCase
import com.windstrom5.rebit.domain.usecase.RescanLibraryUseCase
import com.windstrom5.rebit.domain.usecase.ResumePlaybackUseCase
import com.windstrom5.rebit.domain.usecase.RunExperimentUseCase
import com.windstrom5.rebit.domain.usecase.SeekPlaybackUseCase
import com.windstrom5.rebit.domain.usecase.SetQueueUseCase
import com.windstrom5.rebit.domain.usecase.StartPlaybackUseCase
import com.windstrom5.rebit.domain.usecase.StopPlaybackUseCase
import com.windstrom5.rebit.domain.usecase.ToggleShuffleUseCase
import com.windstrom5.rebit.domain.usecase.VerifyIntegrityUseCase
import com.windstrom5.rebit.playback.FlacVorbisCommentReader
import com.windstrom5.rebit.playback.PlaybackController
import com.windstrom5.rebit.presentation.viewmodel.AnalyticsViewModel
import com.windstrom5.rebit.presentation.viewmodel.DacViewModel
import com.windstrom5.rebit.presentation.viewmodel.ExperimentViewModel
import com.windstrom5.rebit.presentation.viewmodel.KeepScreenOnViewModel
import com.windstrom5.rebit.presentation.viewmodel.LibraryViewModel
import com.windstrom5.rebit.presentation.viewmodel.PlayerViewModel
import com.windstrom5.rebit.presentation.viewmodel.SettingsViewModel
import com.windstrom5.rebit.usb.DacCapabilityAnalyzer
import com.windstrom5.rebit.usb.UsbDacManager
import com.windstrom5.rebit.usb.UsbDescriptorParser
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val databaseModule = module {
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().audioTrackDao() }
    single { get<AppDatabase>().artistInfoDao() }
    single { com.windstrom5.rebit.data.genre.GenreRepository(get(), get()) }
    single { get<AppDatabase>().dacProfileDao() }
    single { get<AppDatabase>().benchmarkDao() }
    single { get<AppDatabase>().experimentDao() }
    single { get<AppDatabase>().watchedFolderDao() }
}

val dataModule = module {
    single { LocalAudioScanner(androidContext()) }
    single { CsvExporter() }
    single { SettingsRepository(androidContext()) }
    single { LyricsRepository(androidContext()) }
    single { com.windstrom5.rebit.data.wikipedia.WikipediaRepository() }

    // Listening history + favorites (separate DB: see UserDataDatabase)
    single { com.windstrom5.rebit.data.history.UserDataDatabase.getInstance(androidContext()) }
    single { get<com.windstrom5.rebit.data.history.UserDataDatabase>().listeningDao() }
    single { com.windstrom5.rebit.data.history.ListeningRepository(get()) }
    single { com.windstrom5.rebit.data.history.PlayTracker(get(), get()) }

    // Artist news + release alerts
    single { get<com.windstrom5.rebit.data.history.UserDataDatabase>().newsDao() }
    single { com.windstrom5.rebit.data.news.MusicBrainzClient() }
    single { com.windstrom5.rebit.data.news.GdeltClient() }
    single { com.windstrom5.rebit.data.news.NewsPrefs(androidContext()) }
    single { com.windstrom5.rebit.data.news.NewsNotifier(androidContext()) }
    single { com.windstrom5.rebit.data.gear.GearPrefs(androidContext()) }
    single { com.windstrom5.rebit.playback.autoeq.AutoEqCatalog(androidContext()) }
    single { com.windstrom5.rebit.data.audio.TrackAnalysisRepository(androidContext()) }
    single { com.windstrom5.rebit.data.news.ArtistFeedRepository(get(), get(), get(), get(), get(), get()) }
    factory { com.windstrom5.rebit.domain.usecase.PlayTracksUseCase(get(), get(), get(), get(), get()) }
    single<AudioLibraryRepository> { AudioLibraryRepositoryImpl(androidContext(), get(), get(), get()) }
    single<DacProfileRepository> { DacProfileRepositoryImpl(get()) }
    single { com.windstrom5.rebit.data.usb.UsbAutoSetup(get(), get(), get(), get()) }
    single { com.windstrom5.rebit.playback.HardwareVolumeRouter(get(), get(), get(), androidContext()) }
    single<BenchmarkRepository> { BenchmarkRepositoryImpl(get(), get(), get()) }
}

val usbModule = module {
    single { UsbDacManager(androidContext()) }
    single { UsbDescriptorParser() }
    single { DacCapabilityAnalyzer(get()) }
}

val domainModule = module {
    single { PlaybackIntegrityEngine() }
    single { AdaptiveOptimizationEngine() }
    single { StatisticsEngine() }
    single { AnalyticsEngine() }
    single { com.windstrom5.rebit.domain.abx.AbxGameEngine() }
    single { com.windstrom5.rebit.domain.abx.AbxAudioPlayer(get()) }
    single { com.windstrom5.rebit.playback.ai.AiStemExtractor(androidContext(), get()) }
    single { com.windstrom5.rebit.sync.SharedListeningManager(androidContext()) }

    factory { ObserveLibraryUseCase(get()) }
    factory { RescanLibraryUseCase(get()) }
    factory { ObserveWatchedFoldersUseCase(get()) }
    factory { AddWatchedFolderUseCase(get()) }
    factory { RemoveWatchedFolderUseCase(get()) }
    factory { ObserveDacProfilesUseCase(get()) }
    factory { AnalyzeDacUseCase(get(), get(), get()) }
    factory { FetchDescriptorTreeUseCase(get(), get()) }
    factory { StartPlaybackUseCase(get()) }
    factory { StopPlaybackUseCase(get()) }
    factory { SeekPlaybackUseCase(get()) }
    factory { ObservePlaybackStateUseCase(get()) }
    factory { VerifyIntegrityUseCase(get()) }
    factory { PausePlaybackUseCase(get()) }
    factory { ResumePlaybackUseCase(get()) }
    factory { NextTrackUseCase(get()) }
    factory { PreviousTrackUseCase(get()) }
    factory { ToggleShuffleUseCase(get()) }
    factory { SetQueueUseCase(get()) }
    factory { ObserveQueueUseCase(get()) }
    factory { RunExperimentUseCase(get()) }
    factory { ExportBenchmarkCsvUseCase(get()) }
    factory { GetAnalyticsSummaryUseCase(get(), get(), get()) }
}

val benchmarkModule = module {
    single { BenchmarkRunner(androidContext()) }
    single { ValidityThreatsDetector(androidContext()) }
    single { ExperimentOrchestrator(get(), get(), get(), get()) }
    single { DacRateBenchmark(get(), get()) }
}

val playbackModule = module {
    single { FlacVorbisCommentReader(androidContext()) }
    single { com.windstrom5.rebit.streaming.StreamingManager(androidContext()) }
    single { PlaybackController(androidContext(), get(), get(), get(), get(), get(), get(), get(), get<com.windstrom5.rebit.streaming.StreamingManager>()) }
}

val viewModelModule = module {
    viewModel { LibraryViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { DacViewModel(get(), get(), get(), get(), get()) }
    viewModel { PlayerViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ExperimentViewModel(get(), get(), get()) }
    viewModel { AnalyticsViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get()) }
    viewModel { KeepScreenOnViewModel(get(), get()) }
    single { com.windstrom5.rebit.domain.abx.AbxClipLoader(androidContext()) }
    single { com.windstrom5.rebit.domain.eqtrainer.EqTrainerStore(androidContext()) }
    factory { com.windstrom5.rebit.domain.eqtrainer.EqTrainerPlayer() }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.EqTrainerViewModel(get(), get(), get(), get()) }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.GamesViewModel(get()) }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.AbxViewModel(get(), get(), get(), get(), get()) }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.WikipediaViewModel(get(), get()) }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.FavoritesViewModel(get()) }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.InsightsViewModel(get()) }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.IemPresetViewModel(get()) }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.TrackAnalysisViewModel(get()) }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.TestSignalsViewModel() }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.WrappedViewModel(get(), get(), get()) }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.HomeViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.NewsViewModel(get(), get(), androidContext()) }
    viewModel { com.windstrom5.rebit.presentation.viewmodel.StreamingViewModel(get(), get()) }
}

val allModules = listOf(
    databaseModule, dataModule, usbModule, domainModule, benchmarkModule, playbackModule, viewModelModule
)
