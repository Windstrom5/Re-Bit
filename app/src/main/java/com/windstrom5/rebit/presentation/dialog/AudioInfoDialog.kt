package com.windstrom5.rebit.presentation.dialog

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.windstrom5.rebit.data.audio.DeviceAudio
import com.windstrom5.rebit.domain.audiophile.AudioFacts
import com.windstrom5.rebit.domain.audiophile.LibraryInsights
import com.windstrom5.rebit.domain.audiophile.SignalPath
import com.windstrom5.rebit.domain.audiophile.SignalPathLogic
import com.windstrom5.rebit.domain.audiophile.TrackAnalysis
import com.windstrom5.rebit.domain.model.BufferMode
import com.windstrom5.rebit.domain.model.BufferRecommendation
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.presentation.components.AudioPanel
import com.windstrom5.rebit.presentation.components.ChoiceChip
import com.windstrom5.rebit.presentation.components.EngravedLabel
import com.windstrom5.rebit.presentation.components.LedDot
import com.windstrom5.rebit.presentation.components.PurityRing
import com.windstrom5.rebit.presentation.components.QualityBadge
import com.windstrom5.rebit.presentation.components.qualityOf
import com.windstrom5.rebit.presentation.screen.player.SignalPathTimeline
import com.windstrom5.rebit.presentation.screen.player.formatTime
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.AnalysisUi
import com.windstrom5.rebit.presentation.viewmodel.PlayerUiState

private val Warn = Color(0xFFFF6B6B)

/**
 * Everything the player knows about the signal, using only real data: the file's format, the
 * measured dynamics, the actual Android route / DAC, transfer verification and the live stream.
 * (The old dialog's hard-coded output text, invented "<1 ps jitter", and "SIMULATED" bit matrix
 * are gone.)
 */
@Composable
fun AudioInfoDialog(
    state: PlayerUiState,
    analysis: AnalysisUi,
    path: SignalPath,
    onMeasure: () -> Unit,
    onSelectEngine: (EngineType) -> Unit,
    onSetBufferMode: (BufferMode) -> Unit,
    onSetManualBuffer: (Int) -> Unit,
    onSetHardwareVolume: (Float) -> Unit,
    onDismiss: () -> Unit,
    usbSetup: com.windstrom5.rebit.data.usb.UsbSetupState = com.windstrom5.rebit.data.usb.UsbSetupState(),
    onRetryUsb: () -> Unit = {}
) {
    val track = state.playback.currentTrack
    val ctx = LocalContext.current
    val usb = state.selectedEngine == EngineType.CUSTOM_USB_DIRECT
    val route = remember(state.selectedEngine, track?.filePath) { DeviceAudio.currentRoute(ctx) }
    val mixerRate = remember(state.selectedEngine) { DeviceAudio.mixerRateHz() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.93f).padding(vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            color = SurfacePanel
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Audio info", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        Text(
                            track?.title ?: "Nothing playing",
                            style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = TextSecondary) }
                }

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ── Verdict + chain ──
                    item {
                        AudioPanel {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PurityRing(path.purity)
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Signal path", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                                        Text(path.verdict, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                                        if (track != null) {
                                            Spacer(Modifier.height(6.dp))
                                            QualityBadge(
                                                qualityOf(track.pcm.sampleRateHz, track.pcm.bitDepth),
                                                detail = "${track.pcm.bitDepth}/${SignalPathLogic.khz(track.pcm.sampleRateHz).removeSuffix(" kHz")}"
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                SignalPathTimeline(path)
                            }
                        }
                    }

                    // ── Source ──
                    if (track != null) item { SourceSection(state) }

                    // ── Measured dynamics ──
                    item { DynamicsSection(analysis, onMeasure) }

                    // ── Spectrogram and plain-language quality checks (like a file analyser) ──
                    if (track != null) {
                        analysis.result?.takeIf { it.spectrogram != null }?.let { r ->
                            item { SpectrogramSection(track, r) }
                        }
                        item { QualitySection(track, analysis.result) }
                    }

                    // ── Output ──
                    item { OutputSection(state, route, mixerRate, usb, onSelectEngine, usbSetup, onRetryUsb) }

                    // ── Which chips are touching the audio ──
                    item { ChipsSection(state, route, usb) }

                    // ── Transport & verification ──
                    item { TransportSection(state) }

                    // ── Buffer & hardware ──
                    item { BufferSection(state, onSetBufferMode, onSetManualBuffer, onSetHardwareVolume) }
                }
            }
        }
    }
}

// ───────────────────────────── building blocks ─────────────────────────────

@Composable
private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    AudioPanel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EngravedLabel(title)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = TextPrimary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.weight(0.42f))
        Text(
            value, style = MaterialTheme.typography.bodyMedium, color = valueColor,
            textAlign = TextAlign.End, modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
private fun Note(text: String, color: Color = TextSecondary) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** A rate / depth chip: lit when it is the one in use, dim otherwise. */
@Composable
private fun StatChip(label: String, active: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) SignalTeal.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LedDot(if (active) SignalTeal else TextSecondary.copy(alpha = 0.35f), size = 5.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            label, fontFamily = TelemetryFontFamily, fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) TextPrimary else TextSecondary
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

// ───────────────────────────── sections ─────────────────────────────

@Composable
private fun SourceSection(state: PlayerUiState) {
    val track = state.playback.currentTrack ?: return
    val rate = track.pcm.sampleRateHz
    val bits = track.pcm.bitDepth
    val ch = track.pcm.channels
    val fileName = Uri.decode(track.filePath).substringAfterLast('/').substringAfterLast(':')
    val sizePct = AudioFacts.sizePercentOfPcm(track.fileSizeBytes, track.durationMs, rate, bits, ch)

    InfoSection("Source file") {
        InfoRow("File", fileName)
        val fmt = track.format
        InfoRow(
            "Format",
            when {
                fmt.isDsd -> "${fmt.label} · ${com.windstrom5.rebit.domain.model.dsdRateLabel(rate) ?: "DSD"} (${"%.4f".format(rate / 1_000_000.0)} MHz 1-bit) · ${AudioFacts.channelLabel(ch)}"
                !fmt.isLossless -> "${fmt.label} · ${SignalPathLogic.khz(rate)} · ${AudioFacts.channelLabel(ch)} · lossy"
                else -> "${fmt.label} · $bits-bit · ${SignalPathLogic.khz(rate)} · ${AudioFacts.channelLabel(ch)}"
            }
        )
        track.genre?.let { InfoRow("Genre", if (track.genreIsAuto) "$it (auto-detected)" else it) }
        InfoRow("Duration", formatTime(track.durationMs))
        InfoRow("File size", LibraryInsights.formatSize(track.fileSizeBytes) + (sizePct?.let { " ($it% of raw PCM)" } ?: ""))
        InfoRow("Uncompressed rate", "${"%,d".format(AudioFacts.nominalBitrateKbps(rate, bits, ch))} kbps")
        when {
            fmt.isDsd -> {
                InfoRow("Playback", "DoP to a DoP DAC over USB Direct; otherwise converted to 24-bit 88.2 kHz PCM")
                InfoRow("Format dynamic range", "≈120 dB in the audio band (noise-shaped 1-bit)")
            }
            !fmt.isLossless -> InfoRow("Format dynamic range", "Set by the encoder's bitrate (lossy)")
            else -> {
                InfoRow("Nyquist (highest frequency)", "${AudioFacts.nyquistKhz(rate)} kHz")
                InfoRow("Format dynamic range", "${AudioFacts.theoreticalDynamicRangeDb(bits)} dB (theoretical)")
            }
        }
    }
}

@Composable
private fun DynamicsSection(analysis: AnalysisUi, onMeasure: () -> Unit) {
    InfoSection("Measured dynamics") {
        val r: TrackAnalysis? = analysis.result
        when {
            analysis.running -> {
                Text("Analysing the track… ${(analysis.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = SignalBlue)
                LinearProgressIndicator(
                    progress = { analysis.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = SignalBlue, trackColor = Color.White.copy(alpha = 0.1f)
                )
            }
            r != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "DR${r.drScore}", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Black, fontSize = 34.sp,
                        color = when { r.drScore >= 11 -> SignalTeal; r.drScore >= 8 -> HifiBrass; else -> Warn }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(r.drRating, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text("TT dynamic-range method", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
                r.integratedLufs?.let { lufs ->
                    InfoRow("Loudness (EBU R128)", "${"%.1f".format(lufs)} LUFS", if (lufs > -10.0) Warn else TextPrimary)
                }
                r.loudnessRangeLu?.let { InfoRow("Loudness range", "${"%.1f".format(it)} LU") }
                r.truePeakDbtp?.let { tp ->
                    InfoRow("True peak", "${if (tp > 0) "+" else ""}${"%.1f".format(tp)} dBTP", if (tp > 0.0) Warn else TextPrimary)
                }
                r.plrDb?.let { InfoRow("Peak-to-loudness (PLR)", "${"%.1f".format(it)} dB") }
                InfoRow("Peak level", "${"%.1f".format(r.peakDb)} dBFS")
                InfoRow("Headroom", "${"%.1f".format(r.headroomDb)} dB")
                InfoRow("Average level (RMS)", "${"%.1f".format(r.rmsDb)} dBFS")
                InfoRow("Crest factor", "${"%.1f".format(r.crestDb)} dB")
                InfoRow(
                    "Clipping",
                    if (r.hasClipping) "${"%,d".format(r.clippedSamples)} samples at full scale" else "None detected",
                    if (r.hasClipping) Warn else SignalTeal
                )
                InfoRow(
                    "DC offset", "${"%.2f".format(r.dcOffsetPercent)}%",
                    if (r.dcOffsetPercent > 0.5) HifiBrass else TextPrimary
                )
                if (r.bandwidthHz > 0) InfoRow("Bandwidth", "${"%.1f".format(r.bandwidthHz / 1000.0)} kHz")
                InfoRow("Stereo (M/S ratio)", if (r.effectivelyMono) "Mono" else "%.2f".format(r.midSideRatio))
                if (r.effectiveBitDepth > 0) InfoRow("Effective bit depth", "${r.effectiveBitDepth}-bit")
                Note("Sample peak, not inter-sample (true) peak. Measured from the decoded audio.")
                TextButton(onClick = onMeasure) { Text("Measure again", color = SignalBlue) }
            }
            else -> {
                Note(
                    analysis.error
                        ?: "Decode the whole track once to measure its real dynamic range, peak, clipping, spectrogram, bandwidth and stereo image. Results are saved."
                )
                Button(
                    onClick = onMeasure,
                    colors = ButtonDefaults.buttonColors(containerColor = HifiBrass, contentColor = Color(0xFF0B0B10)),
                    shape = RoundedCornerShape(50)
                ) { Text("Measure dynamics", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun UsbSetupBanner(setup: com.windstrom5.rebit.data.usb.UsbSetupState, onRetry: () -> Unit) {
    val phase = setup.phase
    val busy = phase == com.windstrom5.rebit.data.usb.UsbSetupPhase.WAITING_PERMISSION ||
        phase == com.windstrom5.rebit.data.usb.UsbSetupPhase.ANALYZING
    val failed = phase == com.windstrom5.rebit.data.usb.UsbSetupPhase.FAILED
    if (!busy && !failed) return
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SignalTeal)
        Column(Modifier.weight(1f)) {
            Text(
                setup.deviceName?.let { "$it detected" } ?: "USB DAC",
                style = MaterialTheme.typography.titleSmall, color = TextPrimary
            )
            Text(
                setup.message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) Warn else TextSecondary
            )
        }
        if (failed) androidx.compose.material3.OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun OutputSection(
    state: PlayerUiState,
    route: com.windstrom5.rebit.data.audio.OutputRoute,
    mixerRate: Int,
    usb: Boolean,
    onSelectEngine: (EngineType) -> Unit,
    usbSetup: com.windstrom5.rebit.data.usb.UsbSetupState,
    onRetryUsb: () -> Unit
) {
    val track = state.playback.currentTrack
    val srcRate = track?.pcm?.sampleRateHz
    val srcBits = track?.pcm?.bitDepth
    val dac = state.selectedDac

    InfoSection("Output") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("USB Direct", usb, { onSelectEngine(EngineType.CUSTOM_USB_DIRECT) }, accent = SignalTeal)
            ChoiceChip("Android mixer", state.selectedEngine == EngineType.ANDROID_AUDIOTRACK, { onSelectEngine(EngineType.ANDROID_AUDIOTRACK) }, accent = HifiBrass)
            if (state.selectedEngine == EngineType.ANDROID_HIRES_DIRECT) {
                ChoiceChip("Hi-Res direct", true, { onSelectEngine(EngineType.ANDROID_HIRES_DIRECT) }, accent = SignalTeal)
            }
        }
        UsbSetupBanner(usbSetup, onRetryUsb)
        if (usb) {
            if (dac == null) {
                Note("No USB DAC detected. Connect one, or switch to the Android mixer.", Warn)
            } else {
                InfoRow("DAC", dac.productName)
                dac.manufacturerName?.let { InfoRow("Manufacturer", it) }
                InfoRow("Interface", "USB Audio Class ${if (dac.isUac2) "2.0" else "1.0"}")
                InfoRow("USB ID", "0x%04X : 0x%04X".format(dac.vendorId, dac.productId))
                InfoRow(
                    "Clocking",
                    if (dac.hasAsyncFeedbackEndpoint) "Asynchronous: the DAC's own clock paces the data" else "Adaptive: the DAC follows the host's data rate",
                    if (dac.hasAsyncFeedbackEndpoint) SignalTeal else TextPrimary
                )
                InfoRow("Max channels", "${dac.maxChannels}")
                InfoRow("Max packet size", "${dac.maxPacketSizeBytes} B")
                Text("Sample rates", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                ChipRow { dac.supportedSampleRates.sorted().forEach { StatChip(SignalPathLogic.khz(it).removeSuffix(" kHz"), it == srcRate) } }
                Text("Bit depths", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                ChipRow { dac.supportedBitDepths.sorted().forEach { StatChip("$it-bit", it == srcBits) } }
                if (srcRate != null) {
                    val ok = srcRate in dac.supportedSampleRates
                    InfoRow(
                        "Rate match",
                        if (ok) "${SignalPathLogic.khz(srcRate)} sent as-is" else "${SignalPathLogic.khz(srcRate)} unsupported: must be resampled",
                        if (ok) SignalTeal else Warn
                    )
                }
            }
        } else {
            InfoRow("Route", route.deviceName?.let { "${route.label} · $it" } ?: route.label)
            InfoRow("Android mixer rate", SignalPathLogic.khz(mixerRate))
            if (srcRate != null) {
                val same = srcRate == mixerRate
                InfoRow(
                    "Rate match",
                    if (same) "Source matches the mixer rate" else "${SignalPathLogic.khz(srcRate)} → ${SignalPathLogic.khz(mixerRate)} (resampled by Android)",
                    if (same) SignalTeal else HifiBrass
                )
            }
            if (route.ratesHz.isNotEmpty()) {
                Text("Device-reported rates", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                ChipRow { route.ratesHz.forEach { StatChip(SignalPathLogic.khz(it).removeSuffix(" kHz"), it == mixerRate) } }
            }
            if (route.bitDepths.isNotEmpty()) InfoRow("Device-reported depths", route.bitDepths.joinToString(" · "))
            if (route.lossy) Note("Bluetooth uses a lossy codec: not bit-perfect, whatever the source.", Warn)
            else Note("Android's mixer may apply resampling and system sound effects. USB Direct bypasses it.")
        }
    }
}

/** Detects, for the song that is playing, which silicon decodes, processes and converts the audio. */
@Composable
private fun ChipsSection(
    state: PlayerUiState,
    route: com.windstrom5.rebit.data.audio.OutputRoute,
    usb: Boolean
) {
    val ctx = LocalContext.current
    val phone = remember { com.windstrom5.rebit.data.audio.AndroidChipDetector.detect(ctx) }
    val dac = state.selectedDac
    val dacChip = remember(dac?.vendorId, dac?.productId, dac?.productName, dac?.manufacturerName) {
        dac?.let {
            com.windstrom5.rebit.domain.audiophile.DacChipIdentifier
                .identify(it.vendorId, it.productId, it.productName, it.manufacturerName)
        }
    }
    val ai = state.aiState
    val dspActive = state.eqEnabled || state.crossfeedEnabled || state.replayGainEnabled ||
        state.normalizationEnabled || state.tubeWarmthEnabled || state.dsdRemasteringEnabled ||
        ai.isAutoPilotActive || ai.isDseeUpscalerActive || ai.isVocalSuppressionActive || ai.karaokeModeEnabled
    val stages = remember(usb, phone.socName, dspActive, route.kind, dac?.productName, dacChip) {
        com.windstrom5.rebit.domain.audiophile.ProcessingChain.build(
            com.windstrom5.rebit.domain.audiophile.ProcessingInputs(
                usbDirect = usb,
                socName = phone.socName,
                dspActive = dspActive,
                routeKind = route.kind,
                dacName = dac?.productName ?: route.deviceName,
                dacChip = dacChip
            )
        )
    }

    InfoSection("Chips processing this song") {
        stages.forEach { stage ->
            Column(Modifier.fillMaxWidth()) {
                Text(stage.task, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Text(
                    if (stage.bypassed) "${stage.processor} · skipped" else stage.processor,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (stage.bypassed) TextSecondary else TextPrimary
                )
                Text(stage.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }

        Text("Phone", style = MaterialTheme.typography.labelLarge, color = HifiBrass)
        InfoRow("SoC", phone.socName)
        InfoRow("Device", phone.deviceModel)
        InfoRow("Android", phone.androidVersion)
        if (phone.proAudio || phone.lowLatency) {
            InfoRow("Audio features", listOfNotNull("Pro audio".takeIf { phone.proAudio }, "Low latency".takeIf { phone.lowLatency }).joinToString(" · "))
        }
        if (!usb && phone.systemEffects.isNotEmpty()) {
            Text("System audio effects registered", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            phone.systemEffects.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = TextPrimary) }
        }

        if (dac != null && dacChip != null) {
            Text("USB DAC", style = MaterialTheme.typography.labelLarge, color = SignalTeal)
            InfoRow("DAC chip", dacChip.shortLabel ?: "Not reported by this DAC", if (dacChip.converterChip != null) SignalTeal else TextPrimary)
            dacChip.usbController?.let { InfoRow("USB interface chip", it) }
            Note(dacChip.note)
        }
    }
}

@Composable
private fun TransportSection(state: PlayerUiState) {
    val p = state.playback
    val track = p.currentTrack
    val v = p.verification
    val usbWorking = state.selectedEngine == EngineType.CUSTOM_USB_DIRECT

    InfoSection("Transport & verification") {
        // Native USB link, live from the C++ isochronous engine.
        p.usbLink?.let { link ->
            InfoRow("USB link", "${link.packetsPerSecond} packets/s · ${if (link.packetsPerSecond >= 8000) "high-speed (125 µs)" else "full-speed (1 ms)"}")
            InfoRow(
                "DAC clock",
                if (link.feedbackActive) "${"%.3f".format(link.dacClockHz / 1000.0)} kHz via async feedback (${"%+.0f".format(link.clockPpm)} ppm)"
                else "Nominal ${"%.1f".format(link.nominalHz / 1000.0)} kHz (no feedback)",
                if (link.feedbackActive) SignalTeal else TextPrimary
            )
            InfoRow("Queued to kernel", "${"%.1f".format(link.queueMs)} ms · ${"%.0f".format(link.bufferedMs)} ms total buffer")
            InfoRow(
                "Underruns / packet errors", "${link.underruns} / ${link.packetErrors}",
                if (link.underruns + link.packetErrors == 0L) SignalTeal else Warn
            )
        }
        // What the DSP chain is doing to the samples right now.
        val proc = p.processing
        if (p.isPlaying) {
            InfoRow(
                "Signal chain",
                if (proc.isTransparent) "Untouched — every sample reaches the output unaltered"
                else (listOfNotNull(proc.resampling?.let { "SRC $it" }) + proc.stages + listOfNotNull(proc.dither?.let { "Dither $it" })).joinToString(" → "),
                if (proc.isTransparent) SignalTeal else HifiBrass
            )
            proc.output?.let { InfoRow("Output", it) }
            if (p.limiterReductionDb >= 0.1f) InfoRow("Limiter", "−${"%.1f".format(p.limiterReductionDb)} dB gain reduction", Warn)
        }
        InfoRow("Buffer", "${p.bufferSizeBytes} B")
        InfoRow("Latency", "${"%.1f".format(p.liveLatencyMs)} ms")
        InfoRow("Stream rate", "${"%.1f".format(p.transferRateBytesPerSec / 1024.0)} KB/s")
        p.stability?.let { s ->
            InfoRow("Dropouts", "${s.dropouts}", if (s.dropouts == 0) SignalTeal else Warn)
            InfoRow("Stability", "${"%.1f".format(s.stabilityPercentage)}%")
            InfoRow(
                "Buffer advice",
                when (s.recommendation) {
                    BufferRecommendation.INCREASE_FOR_STABILITY -> "Increase the buffer"
                    BufferRecommendation.DECREASE_FOR_LATENCY -> "Buffer could be smaller"
                    BufferRecommendation.MAINTAIN -> "Well sized"
                }
            )
        }
        if (v != null && usbWorking) {
            InfoRow(
                "Transfer check",
                when (v.verified) {
                    true -> "Verified: ${"%.1f".format(v.transmittedBytes / 1_048_576.0)} MB reached the DAC unaltered"
                    false -> "MISMATCH: data changed before the DAC"
                    null -> "Checking…"
                },
                when (v.verified) { true -> SignalTeal; false -> Warn; null -> TextSecondary }
            )
            if (v.verified == true) InfoRow("CRC32", "%08X".format(v.sourceChecksum and 0xFFFFFFFFL))
        }
        // Real active-bit readout (OR of every sample so far). Hidden until there is data.
        val bits = track?.pcm?.bitDepth
        val mask = v?.activeBitMask ?: 0
        val effective = if (bits != null) AudioFacts.effectiveBits(mask, bits) else null
        if (bits != null && bits > 1 && effective != null && usbWorking) {
            Spacer(Modifier.height(2.dp))
            Text("Active bits in the stream", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            ChipRow {
                (bits - 1 downTo 0).forEach { b ->
                    val on = ((mask ushr b) and 1) == 1
                    Box(
                        Modifier.size(width = 9.dp, height = 18.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (on) SignalTeal else Color.White.copy(alpha = 0.08f))
                    )
                }
            }
            InfoRow(
                "Effective resolution", "$effective of $bits bits in use",
                if (effective < bits) HifiBrass else SignalTeal
            )
            if (effective < bits) Note("The lowest ${bits - effective} bits never carry data: this looks like a $effective-bit master stored as $bits-bit.", HifiBrass)
        }
        val reasons = p.integrity?.reasons.orEmpty()
        if (reasons.isNotEmpty()) {
            Text("Engine notes", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            reasons.forEach { Note("• $it", HifiBrass) }
        }
    }
}

@Composable
private fun BufferSection(
    state: PlayerUiState,
    onSetBufferMode: (BufferMode) -> Unit,
    onSetManualBuffer: (Int) -> Unit,
    onSetHardwareVolume: (Float) -> Unit
) {
    val track = state.playback.currentTrack
    val rate = track?.pcm?.sampleRateHz ?: 48_000
    val bits = track?.pcm?.bitDepth ?: 16
    val ch = track?.pcm?.channels ?: 2
    val dac = state.selectedDac

    InfoSection("Buffer & hardware") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Adaptive", state.bufferMode == BufferMode.ADAPTIVE, { onSetBufferMode(BufferMode.ADAPTIVE) }, accent = SignalBlue)
            ChoiceChip("Manual", state.bufferMode == BufferMode.MANUAL, { onSetBufferMode(BufferMode.MANUAL) }, accent = SignalBlue)
        }
        if (state.bufferMode == BufferMode.MANUAL) {
            ChipRow {
                listOf(512, 1024, 2048, 4096).forEach { bytes ->
                    val ms = AudioFacts.bufferLatencyMs(bytes, rate, bits, ch)
                    ChoiceChip("$bytes B · ${"%.1f".format(ms)} ms", state.manualBufferSizeBytes == bytes, { onSetManualBuffer(bytes) }, accent = SignalBlue)
                }
            }
            Note("Latency is calculated for the current stream (${SignalPathLogic.khz(rate)}, $bits-bit, ${AudioFacts.channelLabel(ch)}). Larger buffers resist dropouts; smaller ones respond faster.")
        } else {
            Note("The buffer is sized automatically from live stability.")
        }

        var hw by remember(state.hardwareVolumePercent) { mutableFloatStateOf(state.hardwareVolumePercent) }
        val supported = dac?.supportsHardwareVolume == true
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { EngravedLabel("DAC hardware volume") }
            Text(
                if (supported) "${(hw * 100).toInt()}%" else "Not supported",
                fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                color = if (supported) HifiBrass else TextSecondary
            )
        }
        Slider(
            value = hw,
            onValueChange = { hw = it },
            onValueChangeFinished = { onSetHardwareVolume(hw) },
            enabled = supported,
            colors = SliderDefaults.colors(
                thumbColor = HifiBrass, activeTrackColor = HifiBrass.copy(alpha = 0.8f),
                inactiveTrackColor = Color.White.copy(alpha = 0.16f)
            )
        )
        if (dac != null && !supported) Note("This DAC has no USB volume control. Use the DAC's own knob or your system volume.")
    }
}

// ───────────────────────── Spectrogram ─────────────────────────

@Composable
private fun SpectrogramSection(track: com.windstrom5.rebit.domain.model.AudioTrackModel, result: TrackAnalysis) {
    val spec = result.spectrogram ?: return
    val bitmap = remember(spec) { spectrogramBitmap(spec) }
    val nyquistKhz = spec.nyquistHz / 1000f

    fun freqLabel(khz: Float) = if (khz < 0.05f) "0" else if (khz < 10f) "%.1fk".format(khz) else "%.0fk".format(khz)

    InfoSection("Spectrogram") {
        Row(Modifier.fillMaxWidth().height(200.dp)) {
            Column(
                Modifier.width(34.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                listOf(1f, 0.75f, 0.5f, 0.25f, 0f).forEach { f ->
                    Text(freqLabel(nyquistKhz * f), fontFamily = TelemetryFontFamily, fontSize = 9.sp, color = TextSecondary)
                }
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Spectrogram: frequency against time",
                    contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                    modifier = Modifier.fillMaxSize()
                )
                if (result.bandwidthHz > 0) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val y = size.height * (1f - result.bandwidthHz / spec.nyquistHz.toFloat())
                        drawLine(
                            color = SignalTeal,
                            start = androidx.compose.ui.geometry.Offset(0f, y),
                            end = androidx.compose.ui.geometry.Offset(size.width, y),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0:00", fontFamily = TelemetryFontFamily, fontSize = 9.sp, color = TextSecondary)
            Text(formatTime(track.durationMs / 2), fontFamily = TelemetryFontFamily, fontSize = 9.sp, color = TextSecondary)
            Text(formatTime(track.durationMs), fontFamily = TelemetryFontFamily, fontSize = 9.sp, color = TextSecondary)
        }
        Note(
            "Brighter is louder. The dashed line marks where real content ends" +
                (if (result.bandwidthHz > 0) " (${"%.1f".format(result.bandwidthHz / 1000.0)} kHz)" else "") +
                ". A sharp cut-off near 16 or 19 kHz often means the audio started as MP3 or AAC. " +
                "This is a sampled overview of the whole track, not a full-resolution analysis."
        )
    }
}

private fun spectrogramBitmap(spec: com.windstrom5.rebit.domain.audiophile.SpectrogramData): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(spec.columns, spec.rows, android.graphics.Bitmap.Config.ARGB_8888)
    val px = IntArray(spec.columns * spec.rows)
    for (c in 0 until spec.columns) {
        for (r in 0 until spec.rows) {
            // Row 0 is the lowest frequency, so it goes at the bottom of the image.
            px[(spec.rows - 1 - r) * spec.columns + c] = heatColor(spec.db(c, r))
        }
    }
    bmp.setPixels(px, 0, spec.columns, 0, 0, spec.columns, spec.rows)
    return bmp
}

private val HEAT_STOPS = arrayOf(
    floatArrayOf(0.00f, 6f, 6f, 24f),
    floatArrayOf(0.25f, 52f, 22f, 120f),
    floatArrayOf(0.50f, 158f, 38f, 126f),
    floatArrayOf(0.75f, 245f, 128f, 40f),
    floatArrayOf(1.00f, 252f, 240f, 170f)
)

/** Dark blue (quiet) through magenta and orange to pale yellow (loud), over -110..-20 dBFS. */
private fun heatColor(db: Float): Int {
    val t = ((db + 110f) / 90f).coerceIn(0f, 1f)
    var i = 0
    while (i < HEAT_STOPS.size - 2 && t > HEAT_STOPS[i + 1][0]) i++
    val a = HEAT_STOPS[i]
    val b = HEAT_STOPS[i + 1]
    val f = ((t - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f)
    val r = (a[1] + (b[1] - a[1]) * f).toInt()
    val g = (a[2] + (b[2] - a[2]) * f).toInt()
    val bl = (a[3] + (b[3] - a[3]) * f).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}

// ───────────────────────── Quality assessment ─────────────────────────

@Composable
private fun QualitySection(track: com.windstrom5.rebit.domain.model.AudioTrackModel, result: TrackAnalysis?) {
    val items = remember(track.id, track.filePath, track.format, track.pcm, result) {
        com.windstrom5.rebit.domain.audiophile.QualityAssessment.build(
            formatName = track.format.label,
            isLossless = track.format.isLossless,
            sampleRateHz = track.pcm.sampleRateHz,
            bitDepth = track.pcm.bitDepth,
            a = result,
            isDsd = track.format.isDsd
        )
    }
    InfoSection("Quality assessment") {
        items.forEach { QualityRow(it) }
        if (result == null) {
            Note("Run \"Measure dynamics\" above to also check spectral content, clipping, dynamic range and stereo image.")
        }
    }
}

@Composable
private fun QualityRow(item: com.windstrom5.rebit.domain.audiophile.QualityItem) {
    val (icon, color) = when (item.level) {
        com.windstrom5.rebit.domain.audiophile.QualityLevel.GOOD -> androidx.compose.material.icons.Icons.Filled.Check to SignalTeal
        com.windstrom5.rebit.domain.audiophile.QualityLevel.INFO -> androidx.compose.material.icons.Icons.Filled.Info to SignalBlue
        com.windstrom5.rebit.domain.audiophile.QualityLevel.WARN -> androidx.compose.material.icons.Icons.Filled.WarningAmber to HifiBrass
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { androidx.compose.material3.Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
