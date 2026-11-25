package com.windstrom5.rebit.data.settings

import android.content.Context
import com.windstrom5.rebit.domain.model.BufferMode
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.domain.model.PlaybackConfig
import com.windstrom5.rebit.domain.model.UsbTransferStrategy
import com.windstrom5.rebit.domain.model.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the audiophile-tuning settings (Section: "add more settings for
 * audiophile features"). Plain SharedPreferences rather than DataStore — this
 * is a handful of simple values changed rarely by direct user action, so a
 * synchronous cached StateFlow over SharedPreferences is simpler and avoids
 * pulling in another dependency for no real benefit at this scale.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadFromDisk())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    val current: UserSettings get() = _settings.value

    fun setBufferMode(mode: BufferMode) {
        prefs.edit().putString(KEY_BUFFER_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(bufferMode = mode)
    }

    fun setManualBufferSize(bytes: Int) {
        val clamped = bytes.coerceIn(PlaybackConfig.MIN_BUFFER_BYTES, PlaybackConfig.MAX_BUFFER_BYTES)
        prefs.edit().putInt(KEY_MANUAL_BUFFER, clamped).apply()
        _settings.value = _settings.value.copy(manualBufferSizeBytes = clamped)
    }

    fun setDefaultEngine(engine: EngineType) {
        prefs.edit().putString(KEY_DEFAULT_ENGINE, engine.name).apply()
        _settings.value = _settings.value.copy(defaultEngine = engine)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
        _settings.value = _settings.value.copy(keepScreenOnDuringPlayback = enabled)
    }

    fun setUsbRequestPoolSize(size: Int) {
        val clamped = size.coerceIn(UserSettings.USB_REQUEST_POOL_RANGE.first, UserSettings.USB_REQUEST_POOL_RANGE.last)
        prefs.edit().putInt(KEY_USB_POOL_SIZE, clamped).apply()
        _settings.value = _settings.value.copy(usbRequestPoolSize = clamped)
    }

    fun setUsbTransferStrategy(strategy: UsbTransferStrategy) {
        prefs.edit().putString(KEY_TRANSFER_STRATEGY, strategy.name).apply()
        _settings.value = _settings.value.copy(usbTransferStrategy = strategy)
    }

    fun setUseAdaptiveClockCorrection(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ADAPTIVE_CLOCK_CORRECTION, enabled).apply()
        _settings.value = _settings.value.copy(useAdaptiveClockCorrection = enabled)
    }

    fun setUsbProtocolPreference(preference: com.windstrom5.rebit.domain.model.UsbProtocolPreference) {
        prefs.edit().putString(KEY_USB_PROTOCOL_PREFERENCE, preference.name).apply()
        _settings.value = _settings.value.copy(usbProtocolPreference = preference)
    }

    fun setEqEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EQ_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(eqEnabled = enabled)
    }

    fun setEqGains(gains: List<Float>) {
        val serialized = gains.joinToString(",") { java.lang.String.format(java.util.Locale.US, "%.2f", it) }
        // Editing the graphic bands leaves parametric mode: the sliders now own the response.
        prefs.edit().putString(KEY_EQ_GAINS, serialized).remove(KEY_EQ_FILTERS).putString(KEY_EQ_SIGNATURE, "").apply()
        _settings.value = _settings.value.copy(eqGains = gains, eqFilters = emptyList(), eqSignature = "")
    }

    /**
     * Applies an exact parametric correction. [displayGains] is a 10-band approximation so the
     * sliders still show something meaningful; the engine uses [filters] directly.
     */
    fun applyParametricEq(
        profileName: String?,
        filters: List<com.windstrom5.rebit.domain.model.EqFilter>,
        preampDb: Float,
        displayGains: List<Float>,
        signature: String,
        signatureStrength: Float
    ) {
        val gainsSerialized = displayGains.joinToString(",") { java.lang.String.format(java.util.Locale.US, "%.2f", it) }
        prefs.edit()
            .putString(KEY_SELECTED_IEM_PROFILE, profileName)
            .putString(KEY_EQ_GAINS, gainsSerialized)
            .putString(KEY_EQ_FILTERS, encodeFilters(filters))
            .putFloat(KEY_EQ_FILTER_PREAMP, preampDb)
            .putString(KEY_EQ_SIGNATURE, signature)
            .putFloat(KEY_EQ_SIGNATURE_STRENGTH, signatureStrength)
            .putBoolean(KEY_EQ_ENABLED, true)
            .apply()
        _settings.value = _settings.value.copy(
            selectedIemProfile = profileName,
            eqGains = displayGains,
            eqFilters = filters,
            eqFilterPreampDb = preampDb,
            eqSignature = signature,
            eqSignatureStrength = signatureStrength,
            eqEnabled = true
        )
    }

    private fun encodeFilters(filters: List<com.windstrom5.rebit.domain.model.EqFilter>): String =
        filters.joinToString("|") {
            java.lang.String.format(java.util.Locale.US, "%s:%.2f:%.2f:%.3f", it.type.name, it.fcHz, it.gainDb, it.q)
        }

    private fun decodeFilters(raw: String?): List<com.windstrom5.rebit.domain.model.EqFilter> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("|").mapNotNull { part ->
            val p = part.split(":")
            if (p.size != 4) return@mapNotNull null
            val type = try { com.windstrom5.rebit.domain.model.EqFilterType.valueOf(p[0]) } catch (_: Exception) { return@mapNotNull null }
            val fc = p[1].toFloatOrNull() ?: return@mapNotNull null
            val g = p[2].toFloatOrNull() ?: return@mapNotNull null
            val q = p[3].toFloatOrNull() ?: return@mapNotNull null
            com.windstrom5.rebit.domain.model.EqFilter(type, fc, g, q)
        }
    }

    fun setSelectedIemProfile(name: String?, gains: List<Float>? = null) {
        val editor = prefs.edit().putString(KEY_SELECTED_IEM_PROFILE, name)
        if (gains != null) {
            val serialized = gains.joinToString(",") { java.lang.String.format(java.util.Locale.US, "%.2f", it) }
            editor.putString(KEY_EQ_GAINS, serialized)
            editor.remove(KEY_EQ_FILTERS).putString(KEY_EQ_SIGNATURE, "")
            if (name != null) {
                editor.putString("iem_eq_$name", serialized)
            }
            editor.apply()
            _settings.value = _settings.value.copy(selectedIemProfile = name, eqGains = gains, eqFilters = emptyList(), eqSignature = "")
        } else {
            editor.apply()
            _settings.value = _settings.value.copy(selectedIemProfile = name)
        }
    }

    fun getSavedIemGains(name: String): List<Float>? {
        val str = prefs.getString("iem_eq_$name", null) ?: return null
        return str.split(",").mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 10 }
    }

    fun setEqPreampDb(preampDb: Float) {
        val clamped = preampDb.coerceIn(-12.0f, 6.0f)
        prefs.edit().putFloat(KEY_EQ_PREAMP, clamped).apply()
        _settings.value = _settings.value.copy(eqPreampDb = clamped)
    }

    fun setCrossfeedEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CROSSFEED_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(crossfeedEnabled = enabled)
    }

    fun setCrossfeedStrength(strength: Float) {
        val clamped = strength.coerceIn(0f, 1f)
        prefs.edit().putFloat(KEY_CROSSFEED_STRENGTH, clamped).apply()
        _settings.value = _settings.value.copy(crossfeedStrength = clamped)
    }

    fun setReplayGainEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REPLAY_GAIN_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(replayGainEnabled = enabled)
    }

    fun setNormalizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NORMALIZATION_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(normalizationEnabled = enabled)
    }

    fun setEqPreset(preset: com.windstrom5.rebit.domain.model.EqPreset) {
        prefs.edit().putString(KEY_EQ_PRESET, preset.name).apply()
        setEqGains(preset.gains)
        _settings.value = _settings.value.copy(eqPreset = preset, eqGains = preset.gains)
    }

    fun setAutoBypassDspInBitPerfect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BYPASS_DSP, enabled).apply()
        _settings.value = _settings.value.copy(autoBypassDspInBitPerfect = enabled)
    }

    fun setUsbBufferLatency(latencyMs: Int) {
        prefs.edit().putInt(KEY_USB_BUFFER_LATENCY, latencyMs).apply()
        _settings.value = _settings.value.copy(usbBufferLatencyMs = latencyMs)
    }

    fun setHardwareVolumeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HW_VOL_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(hardwareVolumeEnabled = enabled)
    }

    /** True once a DAC volume level has ever been set, i.e. the stored level reflects what the DAC was last told. */
    fun hasStoredHardwareVolume(): Boolean = prefs.contains(KEY_HW_VOL_STEP_INDEX)

    fun setHardwareVolumeStepIndex(step: Int) {
        prefs.edit().putInt(KEY_HW_VOL_STEP_INDEX, step).apply()
        _settings.value = _settings.value.copy(hardwareVolumeStepIndex = step)
    }

    fun setHardwareVolumeSteps(steps: Int) {
        prefs.edit().putInt(KEY_HW_VOL_STEPS, steps).apply()
        _settings.value = _settings.value.copy(hardwareVolumeSteps = steps)
    }

    fun setDsdMode(mode: com.windstrom5.rebit.domain.model.DsdPlaybackMode) {
        prefs.edit().putString(KEY_DSD_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(dsdMode = mode)
    }

    fun setSpatialWidth(width: Float) {
        val clamped = width.coerceIn(0.0f, 2.0f)
        prefs.edit().putFloat(KEY_SPATIAL_WIDTH, clamped).apply()
        _settings.value = _settings.value.copy(spatialWidth = clamped)
    }

    fun setReverbEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REVERB_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(reverbEnabled = enabled)
    }

    fun setReverbPreset(preset: com.windstrom5.rebit.domain.model.ReverbPreset) {
        prefs.edit().putString(KEY_REVERB_PRESET, preset.name).apply()
        _settings.value = _settings.value.copy(reverbPreset = preset)
    }

    fun setReverbWetDry(wetDry: Float) {
        val clamped = wetDry.coerceIn(0.0f, 1.0f)
        prefs.edit().putFloat(KEY_REVERB_WET_DRY, clamped).apply()
        _settings.value = _settings.value.copy(reverbWetDry = clamped)
    }

    fun setReplayGainMode(mode: com.windstrom5.rebit.domain.model.ReplayGainMode) {
        prefs.edit().putString(KEY_REPLAY_GAIN_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(replayGainMode = mode)
    }

    fun setReplayGainPreampDb(preampDb: Float) {
        val clamped = preampDb.coerceIn(-12.0f, 12.0f)
        prefs.edit().putFloat(KEY_REPLAY_GAIN_PREAMP, clamped).apply()
        _settings.value = _settings.value.copy(replayGainPreampDb = clamped)
    }

    fun setPreventClipping(prevent: Boolean) {
        prefs.edit().putBoolean(KEY_PREVENT_CLIPPING, prevent).apply()
        _settings.value = _settings.value.copy(preventClipping = prevent)
    }

    fun setAiAutoPilotEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_AUTO_PILOT, enabled).apply()
        _settings.value = _settings.value.copy(aiAutoPilotEnabled = enabled)
    }

    fun setAiDseeUpscalerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_DSEE, enabled).apply()
        _settings.value = _settings.value.copy(aiDseeUpscalerEnabled = enabled)
    }

    fun setAiVocalSuppressMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_VOCAL_SUPPRESS, enabled).apply()
        _settings.value = _settings.value.copy(aiVocalSuppressMode = enabled, karaokeModeEnabled = enabled)
    }

    fun setKaraokeModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KARAOKE_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(karaokeModeEnabled = enabled, aiVocalSuppressMode = enabled)
    }

    fun setKaraokeModeType(type: com.windstrom5.rebit.domain.model.KaraokeModeType) {
        val isEnabled = type != com.windstrom5.rebit.domain.model.KaraokeModeType.OFF
        prefs.edit()
            .putString(KEY_KARAOKE_TYPE, type.name)
            .putBoolean(KEY_KARAOKE_ENABLED, isEnabled)
            .putBoolean(KEY_AI_VOCAL_SUPPRESS, isEnabled)
            .apply()
        _settings.value = _settings.value.copy(
            karaokeModeType = type,
            karaokeModeEnabled = isEnabled,
            aiVocalSuppressMode = isEnabled
        )
    }

    fun setKaraokeVocalSuppressionStrength(strength: Float) {
        val clamped = strength.coerceIn(0.0f, 1.0f)
        prefs.edit().putFloat(KEY_KARAOKE_STRENGTH, clamped).apply()
        _settings.value = _settings.value.copy(karaokeVocalSuppressionStrength = clamped)
    }

    fun setKaraokeBassPreservation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KARAOKE_BASS_PRESERVE, enabled).apply()
        _settings.value = _settings.value.copy(karaokeBassPreservation = enabled)
    }

    fun setKaraokeKeyShiftSemitones(semitones: Int) {
        val clamped = semitones.coerceIn(-6, 6)
        prefs.edit().putInt(KEY_KARAOKE_KEY_SHIFT, clamped).apply()
        _settings.value = _settings.value.copy(karaokeKeyShiftSemitones = clamped)
    }

    fun setKaraokeMicScoringEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KARAOKE_MIC_SCORE, enabled).apply()
        _settings.value = _settings.value.copy(karaokeMicScoringEnabled = enabled)
    }

    fun setDsdRemasteringEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DSD_REMASTER_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(dsdRemasteringEnabled = enabled)
    }

    fun setDsdRemasterTarget(target: com.windstrom5.rebit.domain.model.DsdRemasterTarget) {
        prefs.edit().putString(KEY_DSD_REMASTER_TARGET, target.name).apply()
        _settings.value = _settings.value.copy(dsdRemasterTarget = target)
    }

    fun setDsdNoiseShaperProfile(profile: com.windstrom5.rebit.domain.model.DsdNoiseShaperProfile) {
        prefs.edit().putString(KEY_DSD_NOISE_SHAPER, profile.name).apply()
        _settings.value = _settings.value.copy(dsdNoiseShaperProfile = profile)
    }

    fun setTubeWarmthEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TUBE_WARMTH_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(tubeWarmthEnabled = enabled)
    }

    fun setTubeWarmthDrive(drive: Float) {
        val clamped = drive.coerceIn(0.0f, 1.0f)
        prefs.edit().putFloat(KEY_TUBE_WARMTH_DRIVE, clamped).apply()
        _settings.value = _settings.value.copy(tubeWarmthDrive = clamped)
    }

    fun setFirFilterType(type: com.windstrom5.rebit.domain.model.FirFilterType) {
        prefs.edit().putString(KEY_FIR_FILTER_TYPE, type.name).apply()
        _settings.value = _settings.value.copy(firFilterType = type)
    }

    fun setHiResDirectOutputEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIRES_DIRECT_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(hiResDirectOutputEnabled = enabled)
    }

    fun setGaplessEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GAPLESS_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(gaplessEnabled = enabled)
    }

    fun setResamplerQuality(quality: com.windstrom5.rebit.domain.model.ResamplerQuality) {
        prefs.edit().putString(KEY_RESAMPLER_QUALITY, quality.name).apply()
        _settings.value = _settings.value.copy(resamplerQuality = quality)
    }

    fun setConvolution(enabled: Boolean, uri: String? = _settings.value.convolutionIrUri, name: String? = _settings.value.convolutionIrName) {
        prefs.edit()
            .putBoolean(KEY_CONVOLUTION_ENABLED, enabled)
            .putString(KEY_CONVOLUTION_URI, uri)
            .putString(KEY_CONVOLUTION_NAME, name)
            .apply()
        _settings.value = _settings.value.copy(convolutionEnabled = enabled, convolutionIrUri = uri, convolutionIrName = name)
    }

    fun setLoudnessCompensation(enabled: Boolean, strength: Float = _settings.value.loudnessCompensationStrength) {
        prefs.edit()
            .putBoolean(KEY_LOUDNESS_COMP_ENABLED, enabled)
            .putFloat(KEY_LOUDNESS_COMP_STRENGTH, strength)
            .apply()
        _settings.value = _settings.value.copy(loudnessCompensationEnabled = enabled, loudnessCompensationStrength = strength)
    }

    fun setDitherMode(mode: com.windstrom5.rebit.domain.model.DitherMode) {
        prefs.edit().putString(KEY_DITHER_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(ditherMode = mode)
    }

    private fun loadFromDisk(): UserSettings {
        val bufferModeName = prefs.getString(KEY_BUFFER_MODE, BufferMode.ADAPTIVE.name) ?: BufferMode.ADAPTIVE.name
        val transferStratName = prefs.getString(KEY_TRANSFER_STRATEGY, UsbTransferStrategy.PIPELINED.name) ?: UsbTransferStrategy.PIPELINED.name
        val usbProtocolPrefName = prefs.getString(KEY_USB_PROTOCOL_PREFERENCE, com.windstrom5.rebit.domain.model.UsbProtocolPreference.AUTO.name) ?: com.windstrom5.rebit.domain.model.UsbProtocolPreference.AUTO.name
        val engineName = prefs.getString(KEY_DEFAULT_ENGINE, EngineType.CUSTOM_USB_DIRECT.name) ?: EngineType.CUSTOM_USB_DIRECT.name
        val dsdModeName = prefs.getString(KEY_DSD_MODE, com.windstrom5.rebit.domain.model.DsdPlaybackMode.DOP.name) ?: com.windstrom5.rebit.domain.model.DsdPlaybackMode.DOP.name
        val dsdRemasterTargetName = prefs.getString(KEY_DSD_REMASTER_TARGET, com.windstrom5.rebit.domain.model.DsdRemasterTarget.DSD64.name) ?: com.windstrom5.rebit.domain.model.DsdRemasterTarget.DSD64.name
        val dsdNoiseShaperName = prefs.getString(KEY_DSD_NOISE_SHAPER, com.windstrom5.rebit.domain.model.DsdNoiseShaperProfile.FIFTH_ORDER_PSYCHOACOUSTIC.name) ?: com.windstrom5.rebit.domain.model.DsdNoiseShaperProfile.FIFTH_ORDER_PSYCHOACOUSTIC.name
        val firFilterName = prefs.getString(KEY_FIR_FILTER_TYPE, com.windstrom5.rebit.domain.model.FirFilterType.MINIMUM_PHASE_SLOW.name) ?: com.windstrom5.rebit.domain.model.FirFilterType.MINIMUM_PHASE_SLOW.name
        val reverbPresetName = prefs.getString(KEY_REVERB_PRESET, com.windstrom5.rebit.domain.model.ReverbPreset.STUDIO_ROOM.name) ?: com.windstrom5.rebit.domain.model.ReverbPreset.STUDIO_ROOM.name
        val replayGainModeName = prefs.getString(KEY_REPLAY_GAIN_MODE, com.windstrom5.rebit.domain.model.ReplayGainMode.TRACK.name) ?: com.windstrom5.rebit.domain.model.ReplayGainMode.TRACK.name
        val eqPresetName = prefs.getString(KEY_EQ_PRESET, com.windstrom5.rebit.domain.model.EqPreset.FLAT.name) ?: com.windstrom5.rebit.domain.model.EqPreset.FLAT.name
        val karaokeTypeName = prefs.getString(KEY_KARAOKE_TYPE, com.windstrom5.rebit.domain.model.KaraokeModeType.INSTRUMENTAL_ONLY.name) ?: com.windstrom5.rebit.domain.model.KaraokeModeType.INSTRUMENTAL_ONLY.name

        val savedGainsStr = prefs.getString(KEY_EQ_GAINS, null)
        val gains = if (savedGainsStr != null) {
            savedGainsStr.split(",").mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 10 }
                ?: listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        } else {
            listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        return UserSettings(
            bufferMode = try { BufferMode.valueOf(bufferModeName) } catch (_: Exception) { BufferMode.ADAPTIVE },
            manualBufferSizeBytes = prefs.getInt(KEY_MANUAL_BUFFER, PlaybackConfig.DEFAULT_BUFFER_BYTES),
            defaultEngine = try { EngineType.valueOf(engineName) } catch (_: Exception) { EngineType.CUSTOM_USB_DIRECT },
            keepScreenOnDuringPlayback = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
            usbRequestPoolSize = prefs.getInt(KEY_USB_POOL_SIZE, 4).coerceIn(UserSettings.USB_REQUEST_POOL_RANGE),
            usbTransferStrategy = try { UsbTransferStrategy.valueOf(transferStratName) } catch (_: Exception) { UsbTransferStrategy.PIPELINED },
            useAdaptiveClockCorrection = prefs.getBoolean(KEY_ADAPTIVE_CLOCK_CORRECTION, true),
            usbProtocolPreference = try { com.windstrom5.rebit.domain.model.UsbProtocolPreference.valueOf(usbProtocolPrefName) } catch (_: Exception) { com.windstrom5.rebit.domain.model.UsbProtocolPreference.AUTO },
            usbBufferLatencyMs = prefs.getInt(KEY_USB_BUFFER_LATENCY, 32),
            hardwareVolumeEnabled = prefs.getBoolean(KEY_HW_VOL_ENABLED, true),
            hardwareVolumeSteps = prefs.getInt(KEY_HW_VOL_STEPS, 100),
            hardwareVolumeStepIndex = prefs.getInt(KEY_HW_VOL_STEP_INDEX, 85),
            dsdMode = try { com.windstrom5.rebit.domain.model.DsdPlaybackMode.valueOf(dsdModeName) } catch (_: Exception) { com.windstrom5.rebit.domain.model.DsdPlaybackMode.DOP },
            dsdRemasteringEnabled = prefs.getBoolean(KEY_DSD_REMASTER_ENABLED, false),
            dsdRemasterTarget = try { com.windstrom5.rebit.domain.model.DsdRemasterTarget.valueOf(dsdRemasterTargetName) } catch (_: Exception) { com.windstrom5.rebit.domain.model.DsdRemasterTarget.DSD64 },
            dsdNoiseShaperProfile = try { com.windstrom5.rebit.domain.model.DsdNoiseShaperProfile.valueOf(dsdNoiseShaperName) } catch (_: Exception) { com.windstrom5.rebit.domain.model.DsdNoiseShaperProfile.FIFTH_ORDER_PSYCHOACOUSTIC },
            tubeWarmthEnabled = prefs.getBoolean(KEY_TUBE_WARMTH_ENABLED, false),
            tubeWarmthDrive = prefs.getFloat(KEY_TUBE_WARMTH_DRIVE, 0.35f),
            firFilterType = try { com.windstrom5.rebit.domain.model.FirFilterType.valueOf(firFilterName) } catch (_: Exception) { com.windstrom5.rebit.domain.model.FirFilterType.MINIMUM_PHASE_SLOW },
            hiResDirectOutputEnabled = prefs.getBoolean(KEY_HIRES_DIRECT_ENABLED, true),
            eqEnabled = prefs.getBoolean(KEY_EQ_ENABLED, false),
            eqGains = gains,
            eqPreset = try { com.windstrom5.rebit.domain.model.EqPreset.valueOf(eqPresetName) } catch (_: Exception) { com.windstrom5.rebit.domain.model.EqPreset.FLAT },
            selectedIemProfile = prefs.getString(KEY_SELECTED_IEM_PROFILE, null),
            eqPreampDb = prefs.getFloat(KEY_EQ_PREAMP, 0.0f),
            eqFilters = decodeFilters(prefs.getString(KEY_EQ_FILTERS, null)),
            eqFilterPreampDb = prefs.getFloat(KEY_EQ_FILTER_PREAMP, 0.0f),
            eqSignature = prefs.getString(KEY_EQ_SIGNATURE, "") ?: "",
            eqSignatureStrength = prefs.getFloat(KEY_EQ_SIGNATURE_STRENGTH, 1.0f),
            crossfeedEnabled = prefs.getBoolean(KEY_CROSSFEED_ENABLED, false),
            crossfeedStrength = prefs.getFloat(KEY_CROSSFEED_STRENGTH, 0.3f),
            spatialWidth = prefs.getFloat(KEY_SPATIAL_WIDTH, 1.0f),
            reverbEnabled = prefs.getBoolean(KEY_REVERB_ENABLED, false),
            reverbPreset = try { com.windstrom5.rebit.domain.model.ReverbPreset.valueOf(reverbPresetName) } catch (_: Exception) { com.windstrom5.rebit.domain.model.ReverbPreset.STUDIO_ROOM },
            reverbWetDry = prefs.getFloat(KEY_REVERB_WET_DRY, 0.25f),
            replayGainEnabled = prefs.getBoolean(KEY_REPLAY_GAIN_ENABLED, false),
            replayGainMode = try { com.windstrom5.rebit.domain.model.ReplayGainMode.valueOf(replayGainModeName) } catch (_: Exception) { com.windstrom5.rebit.domain.model.ReplayGainMode.TRACK },
            replayGainPreampDb = prefs.getFloat(KEY_REPLAY_GAIN_PREAMP, 0.0f),
            preventClipping = prefs.getBoolean(KEY_PREVENT_CLIPPING, true),
            normalizationEnabled = prefs.getBoolean(KEY_NORMALIZATION_ENABLED, false),
            autoBypassDspInBitPerfect = prefs.getBoolean(KEY_AUTO_BYPASS_DSP, true),
            gaplessEnabled = prefs.getBoolean(KEY_GAPLESS_ENABLED, true),
            convolutionEnabled = prefs.getBoolean(KEY_CONVOLUTION_ENABLED, false),
            convolutionIrUri = prefs.getString(KEY_CONVOLUTION_URI, null),
            convolutionIrName = prefs.getString(KEY_CONVOLUTION_NAME, null),
            loudnessCompensationEnabled = prefs.getBoolean(KEY_LOUDNESS_COMP_ENABLED, false),
            loudnessCompensationStrength = prefs.getFloat(KEY_LOUDNESS_COMP_STRENGTH, 1.0f),
            resamplerQuality = prefs.getString(KEY_RESAMPLER_QUALITY, null)
                ?.let { runCatching { com.windstrom5.rebit.domain.model.ResamplerQuality.valueOf(it) }.getOrNull() }
                ?: com.windstrom5.rebit.domain.model.ResamplerQuality.BALANCED,
            ditherMode = prefs.getString(KEY_DITHER_MODE, null)
                ?.let { runCatching { com.windstrom5.rebit.domain.model.DitherMode.valueOf(it) }.getOrNull() }
                ?: com.windstrom5.rebit.domain.model.DitherMode.TPDF,
            aiAutoPilotEnabled = prefs.getBoolean(KEY_AI_AUTO_PILOT, false),
            aiDseeUpscalerEnabled = prefs.getBoolean(KEY_AI_DSEE, true),
            aiVocalSuppressMode = prefs.getBoolean(KEY_AI_VOCAL_SUPPRESS, false),
            karaokeModeEnabled = prefs.getBoolean(KEY_KARAOKE_ENABLED, false) || prefs.getBoolean(KEY_AI_VOCAL_SUPPRESS, false),
            karaokeModeType = try { com.windstrom5.rebit.domain.model.KaraokeModeType.valueOf(karaokeTypeName) } catch (_: Exception) { com.windstrom5.rebit.domain.model.KaraokeModeType.INSTRUMENTAL_ONLY },
            karaokeVocalSuppressionStrength = prefs.getFloat(KEY_KARAOKE_STRENGTH, 0.90f),
            karaokeBassPreservation = prefs.getBoolean(KEY_KARAOKE_BASS_PRESERVE, true),
            karaokeKeyShiftSemitones = prefs.getInt(KEY_KARAOKE_KEY_SHIFT, 0),
            karaokeMicScoringEnabled = prefs.getBoolean(KEY_KARAOKE_MIC_SCORE, false)
        )
    }

    fun hasCompletedTutorial(): Boolean = prefs.getBoolean(KEY_HAS_COMPLETED_TUTORIAL, false)

    fun setTutorialCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_COMPLETED_TUTORIAL, completed).apply()
    }

    companion object {
        private const val PREFS_NAME = "bitperfect_user_settings"
        private const val KEY_HAS_COMPLETED_TUTORIAL = "has_completed_tutorial"
        private const val KEY_BUFFER_MODE = "buffer_mode"
        private const val KEY_MANUAL_BUFFER = "manual_buffer_bytes"
        private const val KEY_DEFAULT_ENGINE = "default_engine"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_USB_POOL_SIZE = "usb_request_pool_size"
        private const val KEY_TRANSFER_STRATEGY = "usb_transfer_strategy"
        private const val KEY_ADAPTIVE_CLOCK_CORRECTION = "usb_adaptive_clock_correction"
        private const val KEY_USB_PROTOCOL_PREFERENCE = "usb_protocol_preference"
        private const val KEY_USB_BUFFER_LATENCY = "usb_buffer_latency"
        private const val KEY_HW_VOL_ENABLED = "hw_vol_enabled"
        private const val KEY_HW_VOL_STEPS = "hw_vol_steps"
        private const val KEY_HW_VOL_STEP_INDEX = "hw_vol_step_index"
        private const val KEY_DSD_MODE = "dsd_mode"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_GAINS = "eq_gains"
        private const val KEY_EQ_PRESET = "eq_preset"
        private const val KEY_SELECTED_IEM_PROFILE = "selected_iem_profile"
        private const val KEY_EQ_PREAMP = "eq_preamp_db"
        private const val KEY_EQ_FILTERS = "eq_filters"
        private const val KEY_EQ_FILTER_PREAMP = "eq_filter_preamp_db"
        private const val KEY_EQ_SIGNATURE = "eq_signature"
        private const val KEY_EQ_SIGNATURE_STRENGTH = "eq_signature_strength"
        private const val KEY_CROSSFEED_ENABLED = "crossfeed_enabled"
        private const val KEY_CROSSFEED_STRENGTH = "crossfeed_strength"
        private const val KEY_SPATIAL_WIDTH = "spatial_width"
        private const val KEY_REVERB_ENABLED = "reverb_enabled"
        private const val KEY_REVERB_PRESET = "reverb_preset"
        private const val KEY_REVERB_WET_DRY = "reverb_wet_dry"
        private const val KEY_REPLAY_GAIN_ENABLED = "replay_gain_enabled"
        private const val KEY_REPLAY_GAIN_MODE = "replay_gain_mode"
        private const val KEY_REPLAY_GAIN_PREAMP = "replay_gain_preamp"
        private const val KEY_PREVENT_CLIPPING = "prevent_clipping"
        private const val KEY_NORMALIZATION_ENABLED = "normalization_enabled"
        private const val KEY_AUTO_BYPASS_DSP = "auto_bypass_dsp"
        private const val KEY_AI_AUTO_PILOT = "ai_auto_pilot"
        private const val KEY_AI_DSEE = "ai_dsee"
        private const val KEY_AI_VOCAL_SUPPRESS = "ai_vocal_suppress"
        private const val KEY_KARAOKE_ENABLED = "karaoke_enabled"
        private const val KEY_KARAOKE_TYPE = "karaoke_type"
        private const val KEY_KARAOKE_STRENGTH = "karaoke_strength"
        private const val KEY_KARAOKE_BASS_PRESERVE = "karaoke_bass_preserve"
        private const val KEY_KARAOKE_KEY_SHIFT = "karaoke_key_shift"
        private const val KEY_KARAOKE_MIC_SCORE = "karaoke_mic_score"
        private const val KEY_DSD_REMASTER_ENABLED = "dsd_remaster_enabled"
        private const val KEY_DSD_REMASTER_TARGET = "dsd_remaster_target"
        private const val KEY_DSD_NOISE_SHAPER = "dsd_noise_shaper"
        private const val KEY_TUBE_WARMTH_ENABLED = "tube_warmth_enabled"
        private const val KEY_TUBE_WARMTH_DRIVE = "tube_warmth_drive"
        private const val KEY_FIR_FILTER_TYPE = "fir_filter_type"
        private const val KEY_HIRES_DIRECT_ENABLED = "hires_direct_enabled"
        private const val KEY_GAPLESS_ENABLED = "gapless_enabled"
        private const val KEY_RESAMPLER_QUALITY = "resampler_quality"
        private const val KEY_DITHER_MODE = "dither_mode"
        private const val KEY_CONVOLUTION_ENABLED = "convolution_enabled"
        private const val KEY_CONVOLUTION_URI = "convolution_ir_uri"
        private const val KEY_CONVOLUTION_NAME = "convolution_ir_name"
        private const val KEY_LOUDNESS_COMP_ENABLED = "loudness_comp_enabled"
        private const val KEY_LOUDNESS_COMP_STRENGTH = "loudness_comp_strength"
    }
}
