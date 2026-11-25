package com.windstrom5.rebit.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.windstrom5.rebit.domain.audiophile.SignalConfig
import com.windstrom5.rebit.domain.audiophile.TestSignalGenerator
import kotlin.math.max

/**
 * Plays [TestSignalGenerator] output through a streaming AudioTrack. Config changes apply live;
 * [stop] fades out and the worker winds down once silence has been written.
 */
class TestSignalPlayer {
    private val sampleRate = 48_000
    private val lock = Any()

    @Volatile private var config = SignalConfig()
    @Volatile private var running = false
    private var thread: Thread? = null

    fun setConfig(c: SignalConfig) { config = c }

    fun start() = synchronized(lock) {
        running = true
        if (thread == null) {
            thread = Thread(::loop, "test-signal").also { it.priority = Thread.MAX_PRIORITY; it.start() }
        }
    }

    /** Fades out; the thread exits on its own after ~200 ms of silence. */
    fun stop() { running = false }

    fun release() { running = false }

    private fun loop() {
        var track: AudioTrack? = null
        try {
            val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBytes, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()

            val gen = TestSignalGenerator(sampleRate)
            val frames = 1024
            val buf = ShortArray(frames * 2)
            var silentFrames = 0
            while (true) {
                val on = running
                gen.fill(buf, frames, config, on)
                track.write(buf, 0, buf.size) // blocking write paces the loop
                silentFrames = if (on) 0 else silentFrames + frames
                if (!on && silentFrames >= sampleRate / 5) {
                    synchronized(lock) {
                        if (!running) { thread = null; return@loop }
                    }
                    silentFrames = 0 // start() raced in: keep going
                }
            }
        } catch (e: Exception) {
            synchronized(lock) { thread = null; running = false }
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
        }
    }
}
