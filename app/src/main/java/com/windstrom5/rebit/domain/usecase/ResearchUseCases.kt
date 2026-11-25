package com.windstrom5.rebit.domain.usecase

import com.windstrom5.rebit.benchmark.ExperimentOrchestrator
import com.windstrom5.rebit.domain.engine.AnalyticsEngine
import com.windstrom5.rebit.domain.model.AnalyticsSummary
import com.windstrom5.rebit.domain.model.DacProfile
import com.windstrom5.rebit.domain.model.ExperimentReport
import com.windstrom5.rebit.domain.model.ExperimentType
import com.windstrom5.rebit.domain.repository.BenchmarkRepository
import com.windstrom5.rebit.domain.repository.DacProfileRepository

class RunExperimentUseCase(private val orchestrator: ExperimentOrchestrator) {
    suspend operator fun invoke(
        type: ExperimentType,
        dac: DacProfile?,
        onProgress: (String) -> Unit = {}
    ): ExperimentReport = orchestrator.run(type, dac, onProgress)
}

class ExportBenchmarkCsvUseCase(private val repository: BenchmarkRepository) {
    suspend fun exportSessions(destinationPath: String): Int = repository.exportSessionsToCsv(destinationPath)
    suspend fun exportExperiment(type: ExperimentType, destinationPath: String): Int =
        repository.exportExperimentRunsToCsv(type, destinationPath)
}

/**
 * Computes the historical Analytics dashboard summary from all logged sessions
 * (the "analytical value" layer requested on top of the thesis's raw feature set).
 */
class GetAnalyticsSummaryUseCase(
    private val benchmarkRepository: BenchmarkRepository,
    private val dacProfileRepository: DacProfileRepository,
    private val analyticsEngine: AnalyticsEngine
) {
    suspend operator fun invoke(): AnalyticsSummary {
        val sessions = benchmarkRepository.getAllSessionsSnapshot()

        val dacLabels = mutableMapOf<Long, String>()
        sessions.mapNotNull { it.dacProfileId }.distinct().forEach { id ->
            dacProfileRepository.getProfile(id)?.let { dacLabels[id] = "${it.productName} (${it.usbIdLabel})" }
        }

        return analyticsEngine.summarize(sessions, dacLabels)
    }
}
