package com.windstrom5.rebit.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.windstrom5.rebit.data.settings.SettingsRepository
import com.windstrom5.rebit.domain.model.DitherMode
import com.windstrom5.rebit.domain.model.KaraokeModeType
import com.windstrom5.rebit.domain.model.PcmFormat
import com.windstrom5.rebit.domain.model.ProcessingSummary
import com.windstrom5.rebit.domain.model.UserSettings
import com.windstrom5.rebit.playback.dsp.DitherQuantizer
import com.windstrom5.rebit.playback.dsp.LevelMeter
import com.windstrom5.rebit.playback.dsp.PcmCodec
import com.windstrom5.rebit.playback.dsp.PeakLimiter
import com.windstrom5.rebit.playback.dsp.SincResampler
import com.windstrom5.rebit.playback.dsp.SpatialSoundstageProcessor
import com.windstrom5.rebit.playback.dsp.StudioReverbProcessor
import com.windstrom5.rebit.playback.dsp.TenBandBiquadEqualizer
import com.windstrom5.rebit.playback.dsp.TubeWarmthProcessor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/**
 * Standard `AudioTrack` playback engine with a 32-bit float DSP chain.
 *
 * Output format negotiation (unchanged): the native rate/depth is tried first when Hi-Res Direct
 * Output is on, then progressively safer fallbacks down to 16-bit/48 kHz — see
 * [buildCandidateConfigs] for why "audible and correctly labelled" beats "bit-perfect but silent".
 *
 * Signal chain, all in float, in this order:
 *
 *  1. **Decode** to float with a symmetric 2^(n-1) scale (exactly reversible).
 *  2. **Resample** with [SincResampler] — only when Android won't take the native rate.
 *  3. **Karaoke** vocal suppression / isolation / key shift (needs the untouched stereo image).
 *  4. **ReplayGain / normalization**, with preamp and peak-based clip protection.
 *  5. **EQ** (10-band graphic or AutoEq parametric).
 *  6. **Crossfeed** (bs2b-style, with inter-aural delay), **stereo width**, **reverb**, **tube**.
 *  7. **Peak limiter** — once any stage has altered the signal and "Prevent clipping" is on.
 *  8. **Level meter**, then **quantize** to the output depth with TPDF / noise-shaped dither.
 *
 * With every stage off, a source that already fits the output depth is passed through
 * sample-for-sample (no dither, no limiter): the float round trip is exact.
 */
class AudioTrackPlaybackEngine(
    private val context: android.content.Context? = null,
    private val settingsRepository: SettingsRepository
) : PlaybackEngine {

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val running = AtomicBoolean(false)
    @Volatile private var bufferSizeBytes = 4096
    @Volatile private var bytesPerSecond = 1
    private val dropouts = AtomicInteger(0)
    private var lastUnderrunCount = 0
    private val verifier = TransferVerifier()
    /** Bytes actually written to the AudioTrack so far this session — read from
     *  the monitor-loop thread via [currentPositionMs], written from the
     *  playback thread, so this needs to be safe across that boundary. */
    private val totalBytesWrittenAtomic = AtomicLong(0L)
    private val vocalIsolator = com.windstrom5.rebit.playback.ai.AiVocalIsolator(context)
    private val tubeWarmthProcessor = TubeWarmthProcessor()
    var isPlayingCachedStem: Boolean = false

    // ── DSP chain state (playback thread only, except where @Volatile) ──────────────────────
    private var biquadEqualizer: TenBandBiquadEqualizer? = null
    private var resampler: SincResampler? = null
    private var quantizer: DitherQuantizer? = null
    private var limiter: PeakLimiter? = null
    private var crossfeed: CrossfeedProcessor? = null
    private var crossfeedStrength = -1f
    private var spatial = SpatialSoundstageProcessor()
    private var reverb: StudioReverbProcessor? = null
    /** Sticky per session: once the signal has been altered, the limiter stays in the path so
     *  toggling an effect mid-song doesn't add/remove its 1.5 ms delay (an audible click). */
    private var signalAltered = false
    private var karaokeWasActive = false
    private var loudnessComp: com.windstrom5.rebit.playback.dsp.LoudnessCompensation? = null
    private var volumeCheckCountdown = 0
    private var volumeAttenuationDb = 0f
    /** Convolution engine for the current IR + output rate; loaded off the audio thread. */
    @Volatile private var convolver: com.windstrom5.rebit.playback.dsp.ConvolutionEngine? = null
    @Volatile private var convolverKey: String? = null
    @Volatile private var convolverLoading: String? = null
    @Volatile private var tagGain = 1f
    @Volatile private var summary = ProcessingSummary()
    private val meter = LevelMeter()

    override val dropoutCount: Int get() = dropouts.get()
    override val levelMeter: LevelMeter get() = meter

    override fun detectedPitch(): Pair<Float, String> =
        vocalIsolator.detectedPitchHz to vocalIsolator.detectedMusicalNote

    override fun takeLimiterReductionDb(): Float = limiter?.takeMaxReductionDb() ?: 0f

    override fun processingSummary(): ProcessingSummary = summary

    /** Not private — [CrossfeedProcessor]'s legacy integer path discriminates on this. */
    enum class BitDepthMode {
        PCM_16,
        PCM_24_PACKED,
        PCM_FLOAT,
        PCM_32_INT
    }

    private data class AudioTrackConfig(
        val sampleRateHz: Int,
        val encoding: Int,
        val bitDepthMode: BitDepthMode
    )

    override fun start(
        decoder: AudioDecoder,
        format: PcmFormat,
        bufferSizeBytes: Int,
        onDropout: () -> Unit,
        onCompletion: () -> Unit,
        onError: (Throwable) -> Unit,
        replayGainDb: Float?,
        trackPeak: Float?
    ) {
        this.bufferSizeBytes = bufferSizeBytes
        verifier.reset()
        meter.reset()
        running.set(true)
        updateTrackGain(replayGainDb, trackPeak)

        playbackThread = Thread({
            try {
                runPlaybackLoop(decoder, format, onDropout, onCompletion)
            } catch (t: Throwable) {
                Log.e(TAG, "AudioTrack playback loop failed", t)
                running.set(false)
                onError(t)
            }
        }, "audiotrack-playback").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * ReplayGain and Normalization are independent gain sources, multiplied together when both
     * are on (see UserSettings.normalizationEnabled). The ReplayGain preamp is added in dB, and
     * with "Prevent clipping" the result is capped so the tagged peak can't exceed full scale —
     * the standard ReplayGain clipping rule. The limiter catches anything else downstream.
     */
    override fun updateTrackGain(replayGainDb: Float?, trackPeak: Float?) {
        tagGain = computeTagGain(settingsRepository.current, replayGainDb, trackPeak)
    }

    private fun runPlaybackLoop(
        decoder: AudioDecoder,
        format: PcmFormat,
        onDropout: () -> Unit,
        onCompletion: () -> Unit
    ) {
        val channelMask = if (format.channels <= 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val candidates = buildCandidateConfigs(format)

        var trackAndConfig: Pair<AudioTrack, AudioTrackConfig>? = null
        for (candidate in candidates) {
            val minBuf = AudioTrack.getMinBufferSize(candidate.sampleRateHz, channelMask, candidate.encoding)
            if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE || minBuf <= 0) continue

            try {
                val trackBuilder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(candidate.encoding)
                            .setSampleRate(candidate.sampleRateHz)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, bufferSizeBytes))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .apply {
                        // Best-effort hint for the direct (mixer-bypassing) path; degrades
                        // silently to PERFORMANCE_MODE_NONE where unsupported.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        }
                    }

                val t = trackBuilder.build()
                if (t.state == AudioTrack.STATE_INITIALIZED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val negotiatedRate = t.format.sampleRate
                        if (negotiatedRate != candidate.sampleRateHz) {
                            Log.w(
                                TAG, "HAL RESAMPLED: requested ${candidate.sampleRateHz}Hz → negotiated ${negotiatedRate}Hz. " +
                                    "Connect a USB DAC and use the USB Direct engine for bit-perfect hi-res playback."
                            )
                        } else {
                            Log.i(TAG, "HAL accepted: ${negotiatedRate}Hz / encoding=${t.format.encoding}")
                        }
                    }
                    trackAndConfig = t to candidate
                    break
                } else {
                    t.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack failed to initialize candidate $candidate: ${e.message}")
            }
        }

        val (track, activeConfig) = trackAndConfig ?: error("Failed to initialize AudioTrack for format $format on this device.")
        audioTrack = track

        val outRate = activeConfig.sampleRateHz
        val outBits = when (activeConfig.bitDepthMode) {
            BitDepthMode.PCM_16 -> 16
            BitDepthMode.PCM_24_PACKED -> 24
            BitDepthMode.PCM_32_INT -> 32
            BitDepthMode.PCM_FLOAT -> 32
        }
        val settings = settingsRepository.current
        setUpChain(format, activeConfig, settings)

        val bytesPerSample = when (activeConfig.bitDepthMode) {
            BitDepthMode.PCM_16 -> 2
            BitDepthMode.PCM_24_PACKED -> 3
            BitDepthMode.PCM_FLOAT, BitDepthMode.PCM_32_INT -> 4
        }
        bytesPerSecond = outRate * format.channels * bytesPerSample

        lastUnderrunCount = track.underrunCount
        Log.d(TAG, "AudioTrack ready: source=$format, out=$activeConfig, resampler=${resampler?.let { "${it.inRate}->${it.outRate}" }}")
        track.play()
        try { track.setVolume(1.0f) } catch (_: Exception) { /* best-effort */ }

        val srcBytesPerSample = (format.bitDepth / 8).coerceAtLeast(1)
        val frameSize = format.channels * srcBytesPerSample
        val alignedBufferSize = (this.bufferSizeBytes.coerceAtLeast(1024) / frameSize) * frameSize
        val readBuffer = ByteArray(alignedBufferSize)
        totalBytesWrittenAtomic.set(0L)

        while (running.get()) {
            val n = decoder.read(readBuffer)
            if (n == -1) {
                // Push out what the resampler still holds (its filter delay) before finishing.
                resampler?.flush()?.takeIf { it.isNotEmpty() }?.let { tail ->
                    val bytes = processAndEncode(tail, tail.size, format, activeConfig, outBits, alreadyResampled = true)
                    writeOut(track, activeConfig, bytes)
                }
                Log.d(TAG, "Decoder returned EOF (-1)")
                onCompletion()
                break
            }
            if (n <= 0) continue

            verifier.recordSourceBytes(readBuffer, n)
            val floats = PcmCodec.toFloat(readBuffer, n, format.bitDepth)
            val toWrite = processAndEncode(floats, floats.size, format, activeConfig, outBits, alreadyResampled = false)
            writeOut(track, activeConfig, toWrite)

            val underruns = track.underrunCount
            if (underruns > lastUnderrunCount) {
                repeat(underruns - lastUnderrunCount) { onDropout() }
                dropouts.addAndGet(underruns - lastUnderrunCount)
                lastUnderrunCount = underruns
            }
        }
    }

    private fun writeOut(track: AudioTrack, config: AudioTrackConfig, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // A float-encoded track only accepts the float[] overload (byte[] returns
        // ERROR_INVALID_OPERATION), so float output goes through writeFloatBuffer.
        val fullyWritten = if (config.bitDepthMode == BitDepthMode.PCM_FLOAT) {
            writeFloatBuffer(track, bytes)
        } else {
            writeByteBuffer(track, bytes)
        }
        if (fullyWritten) {
            verifier.recordTransmittedBytes(bytes, bytes.size)
            totalBytesWrittenAtomic.addAndGet(bytes.size.toLong())
        }
    }

    /** Builds the per-session processors for the negotiated output format. */
    private fun setUpChain(format: PcmFormat, config: AudioTrackConfig, settings: UserSettings) {
        val outRate = config.sampleRateHz
        biquadEqualizer = TenBandBiquadEqualizer(outRate)
        resampler = if (format.sampleRateHz != outRate) {
            SincResampler(format.channels, format.sampleRateHz, outRate, settings.resamplerQuality)
        } else null
        quantizer = when (config.bitDepthMode) {
            BitDepthMode.PCM_16 -> DitherQuantizer(16, format.channels, settings.ditherMode, outRate)
            BitDepthMode.PCM_24_PACKED -> DitherQuantizer(24, format.channels, settings.ditherMode, outRate)
            BitDepthMode.PCM_32_INT -> DitherQuantizer(32, format.channels, DitherMode.OFF, outRate)
            BitDepthMode.PCM_FLOAT -> null
        }
        limiter = PeakLimiter(format.channels, outRate)
        meter.sampleRateHz = outRate
        loudnessComp = com.windstrom5.rebit.playback.dsp.LoudnessCompensation(outRate, format.channels)
        volumeCheckCountdown = 0
        convolver?.reset()
        reverb = StudioReverbProcessor(sampleRateHz = outRate)
        crossfeed = null
        crossfeedStrength = -1f
        spatial = SpatialSoundstageProcessor()
        signalAltered = false
        summaryStages = null
    }

    /**
     * Runs the float DSP chain over [input] and encodes the result for the AudioTrack.
     * @param alreadyResampled true for the resampler's own flushed tail.
     */
    private fun processAndEncode(
        input: FloatArray,
        length: Int,
        format: PcmFormat,
        config: AudioTrackConfig,
        outBits: Int,
        alreadyResampled: Boolean
    ): ByteArray {
        val s = settingsRepository.current
        val channels = format.channels
        val stereo = channels == 2
        val outRate = config.sampleRateHz
        val stages = ArrayList<String>(8)

        var x = input
        var n = length
        val rs = resampler
        if (rs != null && !alreadyResampled) {
            x = rs.process(x, n)
            n = x.size
        }
        var altered = rs != null

        // Karaoke first: centre-channel extraction needs the untouched stereo image.
        val karaokeActive = (s.karaokeModeEnabled || s.aiVocalSuppressMode) && s.karaokeModeType != KaraokeModeType.OFF
        if (karaokeActive && stereo && !karaokeWasActive) {
            // Switched on mid-song: drop whatever stale audio the separator still holds.
            vocalIsolator.reset()
        }
        karaokeWasActive = karaokeActive && stereo
        if (karaokeActive && stereo) {
            if (!isPlayingCachedStem) {
                vocalIsolator.processFloats(
                    floats = x, sampleRate = outRate, mode = s.karaokeModeType,
                    strength = s.karaokeVocalSuppressionStrength, preserveBass = s.karaokeBassPreservation,
                    keyShiftSemitones = s.karaokeKeyShiftSemitones
                )
                stages += "Karaoke"; altered = true
            } else if (s.karaokeKeyShiftSemitones != 0) {
                vocalIsolator.processFloats(
                    floats = x, sampleRate = outRate, mode = KaraokeModeType.OFF, strength = 0f,
                    preserveBass = true, keyShiftSemitones = s.karaokeKeyShiftSemitones
                )
                stages += "Key shift"; altered = true
            }
        }

        val gain = tagGain
        if (gain != 1f) {
            for (i in 0 until n) x[i] *= gain
            stages += if (s.replayGainEnabled) "ReplayGain" else "Normalize"
            altered = true
        }

        if (s.eqEnabled && stereo) {
            biquadEqualizer?.let { eq ->
                eq.applySettings(s.eqGains, s.eqFilters, s.eqFilterPreampDb)
                val active = s.eqFilters.isNotEmpty() || s.eqGains.any { it != 0f }
                if (active) {
                    eq.processStereo(if (n == x.size) x else x.copyOf(n).also { x = it })
                    stages += if (s.eqFilters.isNotEmpty()) "Parametric EQ" else "EQ"
                    altered = true
                }
            }
        }

        if (s.loudnessCompensationEnabled) {
            loudnessComp?.let { lc ->
                // Poll the media volume a few times a second (it's a binder call).
                if (--volumeCheckCountdown <= 0) {
                    volumeCheckCountdown = (outRate / 4) / (n / channels).coerceAtLeast(1)
                    volumeAttenuationDb = currentVolumeAttenuationDb()
                }
                lc.configure(volumeAttenuationDb, s.loudnessCompensationStrength)
                if (lc.isActive) {
                    lc.process(x, n)
                    stages += "Loudness +${"%.0f".format(lc.bassBoostDb)} dB bass"
                    altered = true
                }
            }
        }

        if (s.crossfeedEnabled && stereo && s.crossfeedStrength > 0f) {
            if (crossfeed == null || crossfeedStrength != s.crossfeedStrength) {
                crossfeed = CrossfeedProcessor(s.crossfeedStrength, outRate)
                crossfeedStrength = s.crossfeedStrength
            }
            crossfeed?.processFloats(x, n)
            stages += "Crossfeed"; altered = true
        }

        if (stereo && s.spatialWidth != 1f) {
            spatial.width = s.spatialWidth
            spatial.process(x, n / 2)
            stages += "Width ${"%.1f".format(s.spatialWidth)}×"; altered = true
        }

        if (s.reverbEnabled && stereo) {
            reverb?.let { r ->
                r.enabled = true
                r.preset = s.reverbPreset
                r.wetDry = s.reverbWetDry
                r.process(x, n / 2)
                stages += "Reverb"; altered = true
            }
        }

        if (s.tubeWarmthEnabled) {
            tubeWarmthProcessor.setDrive(s.tubeWarmthDrive)
            tubeWarmthProcessor.process(if (n == x.size) x else x.copyOf(n).also { x = it })
            stages += "Tube"; altered = true
        }

        val irUri = s.convolutionIrUri
        if (s.convolutionEnabled && irUri != null && stereo) {
            val key = "$irUri@$outRate"
            val conv = convolver
            if (conv != null && convolverKey == key) {
                conv.process(x, n)
                stages += "Convolution"
                altered = true
            } else if (convolverLoading != key) {
                loadConvolverAsync(irUri, outRate, key)
            }
        }

        if (altered) signalAltered = true
        if (signalAltered && s.preventClipping) {
            limiter?.process(x, n)
            stages += "Limiter"
        }

        meter.measure(x, n, channels)

        // Dither whenever the samples are no longer exactly representable at the output depth.
        val needsDither = signalAltered || format.bitDepth > outBits
        val q = quantizer
        val bytes = when {
            config.bitDepthMode == BitDepthMode.PCM_FLOAT -> PcmCodec.toFloat32Bytes(x, n)
            q != null -> PcmCodec.fromFloat(x, n, q.bits, channels, q, dither = needsDither)
            else -> PcmCodec.toFloat32Bytes(x, n)
        }

        // Rebuilt only when the chain changes, not on every buffer.
        if (stages != summaryStages || needsDither != summaryDither) {
            summaryStages = stages
            summaryDither = needsDither
            summary = ProcessingSummary(
                resampling = rs?.let {
                    "${khz(it.inRate)} → ${khz(it.outRate)} kHz · ${s.resamplerQuality.label} sinc"
                },
                dither = q?.takeIf { needsDither && it.mode != DitherMode.OFF && it.bits <= 24 }
                    ?.let { "${it.bits}-bit · ${if (it.isNoiseShaped) "Noise-shaped" else "TPDF"}" },
                stages = stages,
                output = "${outputLabel(config.bitDepthMode)} · ${khz(outRate)} kHz"
            )
        }
        return bytes
    }

    private var summaryStages: List<String>? = null
    private var summaryDither = false

    private fun loadConvolverAsync(uri: String, outRate: Int, key: String) {
        val ctx = context ?: return
        convolverLoading = key
        Thread({
            val ir = com.windstrom5.rebit.playback.dsp.ImpulseResponseLoader.load(ctx, uri, outRate)
            if (ir != null && convolverLoading == key) {
                convolver = com.windstrom5.rebit.playback.dsp.ConvolutionEngine(ir.left, ir.right)
                convolverKey = key
                Log.i(TAG, "Convolution ready: ${ir.left.size} taps at $outRate Hz (${ir.channels} ch IR, source ${ir.sourceRateHz} Hz)")
            } else if (ir == null) {
                Log.w(TAG, "Convolution disabled for this session: impulse response couldn't be loaded")
            }
        }, "ir-loader").apply { isDaemon = true; start() }
    }

    private fun currentVolumeAttenuationDb(): Float {
        val am = context?.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return 0f
        return try {
            com.windstrom5.rebit.playback.dsp.LoudnessCompensation.attenuationForVolume(
                am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),
                am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            )
        } catch (_: Exception) { 0f }
    }

    /** Writes via the byte[] overload — valid for PCM_16 / PCM_24_PACKED / PCM_32_INT. */
    private fun writeByteBuffer(track: AudioTrack, data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size && running.get()) {
            val written = track.write(data, offset, data.size - offset)
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write returned error code: $written")
                return false
            }
            if (written == 0) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                continue
            }
            offset += written
        }
        return true
    }

    /** Writes via the float[] overload — the only one a float-encoded track accepts. */
    private fun writeFloatBuffer(track: AudioTrack, data: ByteArray): Boolean {
        val floats = java.nio.ByteBuffer.wrap(data)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } }
        var offset = 0
        while (offset < floats.size && running.get()) {
            val written = track.write(floats, offset, floats.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write (float) returned error code: $written")
                return false
            }
            if (written == 0) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                continue
            }
            offset += written
        }
        return true
    }

    private fun buildCandidateConfigs(format: PcmFormat): List<AudioTrackConfig> {
        val list = mutableListOf<AudioTrackConfig>()

        // ── Candidate ordering rationale ──────────────────────────────────────────
        // AudioTrack.Builder returns STATE_INITIALIZED whether or not the HAL really supports
        // the requested rate. On phones whose internal DAC only runs at 44.1/48 kHz (e.g. the
        // TECNO LJ7), a 96 kHz track initializes, accepts writes, and plays silence. So:
        //   1. Native rate + FLOAT first, only when Hi-Res Direct Output is on.
        //   2. The family base rate (44.1 or 48 kHz), which every device supports.
        //   3. Hard 16-bit fallbacks.
        // "Audible and labelled RESAMPLED" beats "bit-perfect but silent".
        // ─────────────────────────────────────────────────────────────────────────
        val settings = settingsRepository.current
        val targetHardwareRate = if (format.sampleRateHz % 44100 == 0) 44100 else 48000

        if (settings.hiResDirectOutputEnabled) {
            list.add(AudioTrackConfig(format.sampleRateHz, AudioFormat.ENCODING_PCM_FLOAT, BitDepthMode.PCM_FLOAT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list.add(AudioTrackConfig(format.sampleRateHz, AudioFormat.ENCODING_PCM_24BIT_PACKED, BitDepthMode.PCM_24_PACKED))
            }
            list.add(AudioTrackConfig(format.sampleRateHz, AudioFormat.ENCODING_PCM_16BIT, BitDepthMode.PCM_16))
        }

        list.add(AudioTrackConfig(targetHardwareRate, AudioFormat.ENCODING_PCM_16BIT, BitDepthMode.PCM_16))
        if (format.sampleRateHz != targetHardwareRate) {
            list.add(AudioTrackConfig(format.sampleRateHz, AudioFormat.ENCODING_PCM_16BIT, BitDepthMode.PCM_16))
        }
        list.add(AudioTrackConfig(targetHardwareRate, AudioFormat.ENCODING_PCM_FLOAT, BitDepthMode.PCM_FLOAT))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(AudioTrackConfig(format.sampleRateHz, AudioFormat.ENCODING_PCM_24BIT_PACKED, BitDepthMode.PCM_24_PACKED))
        }

        if (format.sampleRateHz != 48000) {
            list.add(AudioTrackConfig(48000, AudioFormat.ENCODING_PCM_16BIT, BitDepthMode.PCM_16))
        }
        if (format.sampleRateHz != 44100) {
            list.add(AudioTrackConfig(44100, AudioFormat.ENCODING_PCM_16BIT, BitDepthMode.PCM_16))
        }
        return list
    }

    override fun stop() {
        running.set(false)
        val thread = playbackThread
        if (thread != null) {
            thread.join(500)
            if (thread.isAlive) {
                // Most likely blocked in AudioTrack.write() on a stalled HAL or in the decoder.
                // The caller closes the decoder right after this returns, so give it one more
                // bounded wait rather than racing MediaCodec teardown.
                thread.interrupt()
                thread.join(500)
            }
        }
        playbackThread = null
        audioTrack?.let {
            try { it.stop() } catch (_: IllegalStateException) { /* already stopped */ }
            try { it.release() } catch (_: Throwable) { /* best-effort cleanup */ }
        }
        audioTrack = null
    }

    override fun setBufferSize(bytes: Int) {
        bufferSizeBytes = bytes
    }

    override fun currentLatencyEstimateMs(): Double {
        val bufferLatency = if (bytesPerSecond > 0) (bufferSizeBytes.toDouble() / bytesPerSecond) * 1000.0 else 0.0
        return bufferLatency + MIXER_OVERHEAD_MS
    }

    override fun currentPositionMs(): Long {
        if (bytesPerSecond <= 0) return 0L
        return (totalBytesWrittenAtomic.get() / bytesPerSecond.toDouble() * 1000.0).toLong()
    }

    override fun verificationSnapshot() = verifier.snapshot()

    companion object {
        private const val MIXER_OVERHEAD_MS = 60.0
        private const val TAG = "AudioTrackEngine"

        /** Linear gain from ReplayGain/normalization tags — see [updateTrackGain]. */
        fun computeTagGain(settings: UserSettings, replayGainDb: Float?, trackPeak: Float?): Float {
            var gain = 1f
            if (settings.replayGainEnabled && replayGainDb != null) {
                gain = 10f.pow((replayGainDb + settings.replayGainPreampDb) / 20f)
                if (settings.preventClipping && trackPeak != null && trackPeak > 0f) {
                    gain = gain.coerceAtMost(1f / trackPeak)
                }
            }
            if (settings.normalizationEnabled && trackPeak != null && trackPeak > 0f) {
                // Capped so a corrupt near-zero peak tag can't become an enormous boost.
                gain *= (1f / trackPeak).coerceAtMost(4f)
            }
            return gain.coerceIn(0.01f, 8f)
        }

        private fun khz(hz: Int): String {
            val k = hz / 1000.0
            return if (k % 1.0 == 0.0) "%.0f".format(k) else "%.1f".format(k)
        }

        private fun outputLabel(mode: BitDepthMode) = when (mode) {
            BitDepthMode.PCM_16 -> "16-bit"
            BitDepthMode.PCM_24_PACKED -> "24-bit"
            BitDepthMode.PCM_32_INT -> "32-bit"
            BitDepthMode.PCM_FLOAT -> "Float32"
        }
    }
}
