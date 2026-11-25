package com.windstrom5.rebit.domain.eqtrainer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.windstrom5.rebit.domain.abx.AbxClip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays pink noise or a library excerpt through one hidden parametric EQ band, with a click-free
 * switch between the EQ'd and the flat signal so the listener can compare.
 *
 * Both signals are computed every chunk and cross-faded, so the filter's state is always warm and
 * toggling or changing the band never produces a pop. Output starts with a half-second fade-in.
 */
class EqTrainerPlayer {

    private data class EqParams(val frequencyHz: Float, val gainDb: Float, val q: Float)

    @Volatile private var sampleRate = 48_000
    private val framesPerChunk = 1024
    private val floatsPerChunk = framesPerChunk * 2

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _eqOn = MutableStateFlow(true)
    val eqOn: StateFlow<Boolean> = _eqOn.asStateFlow()

    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var eqEnabled = true
    @Volatile private var pending: EqParams? = null
    @Volatile private var latest: EqParams? = null
    @Volatile private var clip: AbxClip? = null

    private var audioThread: Thread? = null

    /** Pink noise when null; otherwise loops the excerpt. Stops playback, since the sample rate can change. */
    fun setClip(newClip: AbxClip?) {
        stop()
        clip = newClip
    }

    /** Sets the hidden band. Takes effect after a short fade, so it is safe to call while playing. */
    fun setBand(frequencyHz: Int, gainDb: Float, q: Float) {
        val p = EqParams(frequencyHz.toFloat(), gainDb, q)
        latest = p
        pending = p
    }

    fun setEqEnabled(on: Boolean) {
        eqEnabled = on
        _eqOn.value = on
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            sampleRate = clip?.sampleRate ?: 48_000
            running = true
            _isPlaying.value = true
            // A band chosen before the thread existed must be (re)applied when it starts.
            pending = latest
            audioThread = Thread(::audioLoop, "eq-trainer-player").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            _isPlaying.value = false
            audioThread?.interrupt()
            audioThread = null
        }
    }

    fun release() = stop()

    private fun audioLoop() {
        var track: AudioTrack? = null
        try {
            val rate = sampleRate
            val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, floatsPerChunk * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()

            val sourceClip = clip
            var clipFrame = 0
            val noiseL = PinkNoise(11)
            val noiseR = PinkNoise(29)

            val flat = FloatArray(floatsPerChunk)
            val eq = FloatArray(floatsPerChunk)
            val out = FloatArray(floatsPerChunk)

            var filter: PeakingBiquad? = null
            var mix = 0f
            val mixStep = 1f / (0.015f * rate) // 15 ms fade between EQ and flat
            var master = 0f
            val masterStep = 1f / (0.5f * rate) // 500 ms fade-in at start

            while (running && !Thread.currentThread().isInterrupted) {
                // 1. The source signal for this chunk
                if (sourceClip != null) {
                    for (f in 0 until framesPerChunk) {
                        val src = ((clipFrame + f) % sourceClip.frames) * 2
                        flat[f * 2] = sourceClip.samples[src]
                        flat[f * 2 + 1] = sourceClip.samples[src + 1]
                    }
                    clipFrame = (clipFrame + framesPerChunk) % sourceClip.frames
                } else {
                    for (f in 0 until framesPerChunk) {
                        flat[f * 2] = noiseL.next()
                        flat[f * 2 + 1] = noiseR.next()
                    }
                }

                // 2. Swap to a new band only once the EQ'd signal has faded out, so the swap is silent.
                val p = pending
                if (p != null && (filter == null || mix <= 0.001f)) {
                    filter = PeakingBiquad(p.frequencyHz, p.gainDb, p.q, rate)
                    pending = null
                }
                System.arraycopy(flat, 0, eq, 0, floatsPerChunk)
                filter?.processStereo(eq)

                // 3. Cross-fade flat <-> EQ'd, with the start-up fade-in
                val target = if (pending != null) 0f else if (eqEnabled && filter != null) 1f else 0f
                for (f in 0 until framesPerChunk) {
                    mix += (target - mix).coerceIn(-mixStep, mixStep)
                    if (master < 1f) master = minOf(1f, master + masterStep)
                    val i = f * 2
                    out[i] = ((flat[i] * (1f - mix) + eq[i] * mix) * master).coerceIn(-1f, 1f)
                    out[i + 1] = ((flat[i + 1] * (1f - mix) + eq[i + 1] * mix) * master).coerceIn(-1f, 1f)
                }
                track.write(out, 0, floatsPerChunk, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: InterruptedException) {
            // normal exit
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { track?.stop(); track?.release() } catch (_: Exception) {}
            synchronized(lock) {
                if (audioThread == null || audioThread === Thread.currentThread()) {
                    _isPlaying.value = false
                }
            }
        }
    }
}
