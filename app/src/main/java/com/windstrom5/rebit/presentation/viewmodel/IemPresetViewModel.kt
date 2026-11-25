package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.playback.autoeq.AutoEqCatalog
import com.windstrom5.rebit.playback.autoeq.AutoEqIndex
import com.windstrom5.rebit.playback.autoeq.EqPlan
import com.windstrom5.rebit.playback.autoeq.EqPlanner
import com.windstrom5.rebit.playback.autoeq.IemEntry
import com.windstrom5.rebit.playback.autoeq.IemEqApply
import com.windstrom5.rebit.playback.autoeq.ParsedParametricEq
import com.windstrom5.rebit.playback.autoeq.SoundSignatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IemPresetUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val catalogSize: Int = 0,
    val includeOverEar: Boolean = false,
    val query: String = "",
    val results: List<List<IemEntry>> = emptyList(),
    val selectedGroup: List<IemEntry>? = null,
    val selectedSource: IemEntry? = null,
    val presetLoading: Boolean = false,
    val presetError: String? = null,
    val preset: ParsedParametricEq? = null,
    val signatureId: String = "neutral",
    val strength: Float = 1f
) {
    val signature get() = SoundSignatures.byId(signatureId)
    /** Preview of what will be applied (null until an IEM is loaded or a genre is chosen). */
    val plan: EqPlan?
        get() = when {
            preset != null -> EqPlanner.plan(preset.filters, signature, strength)
            signatureId != "neutral" -> EqPlanner.plan(emptyList(), signature, strength)
            else -> null
        }
    val canApply: Boolean get() = plan != null
}

/** Drives the IEM + genre preset browser. */
class IemPresetViewModel(private val catalog: AutoEqCatalog) : ViewModel() {
    private val _state = MutableStateFlow(IemPresetUiState())
    val state: StateFlow<IemPresetUiState> = _state

    private var groupsInEar: List<List<IemEntry>> = emptyList()
    private var groupsAll: List<List<IemEntry>> = emptyList()
    private var presetJob: Job? = null

    init { load() }

    fun load(forceRefresh: Boolean = false) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val entries = catalog.entries(forceRefresh)
                withContext(Dispatchers.Default) {
                    groupsAll = AutoEqIndex.groupByModel(entries)
                    groupsInEar = AutoEqIndex.groupByModel(entries.filter { it.isInEar })
                }
                _state.update { it.copy(loading = false, catalogSize = groupsInEar.size) }
                search(_state.value.query)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load the catalogue.") }
            }
        }
    }

    fun setIncludeOverEar(on: Boolean) {
        _state.update { it.copy(includeOverEar = on) }
        search(_state.value.query)
    }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        val pool = if (_state.value.includeOverEar) groupsAll else groupsInEar
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { AutoEqIndex.search(pool, query) }
            _state.update { if (it.query == query) it.copy(results = r) else it }
        }
    }

    fun selectModel(group: List<IemEntry>) {
        _state.update { it.copy(selectedGroup = group) }
        selectSource(group.first())
    }

    fun selectSource(entry: IemEntry) {
        presetJob?.cancel()
        _state.update { it.copy(selectedSource = entry, presetLoading = true, presetError = null, preset = null) }
        presetJob = viewModelScope.launch {
            try {
                val p = catalog.preset(entry)
                _state.update { if (it.selectedSource == entry) it.copy(presetLoading = false, preset = p) else it }
            } catch (e: Exception) {
                _state.update { if (it.selectedSource == entry) it.copy(presetLoading = false, presetError = e.message ?: "Download failed.") else it }
            }
        }
    }

    fun clearSelection() {
        presetJob?.cancel()
        _state.update { it.copy(selectedGroup = null, selectedSource = null, preset = null, presetError = null, presetLoading = false) }
    }

    fun setSignature(id: String) = _state.update { it.copy(signatureId = id) }
    fun setStrength(s: Float) = _state.update { it.copy(strength = s.coerceIn(0f, 1f)) }

    /** Builds the final payload (runs the 10-band fit; call once). */
    fun buildApply(): IemEqApply? {
        val s = _state.value
        if (!s.canApply) return null
        return EqPlanner.build(s.selectedGroup?.firstOrNull()?.name, s.preset?.filters.orEmpty(), s.signature, s.strength)
    }
}
