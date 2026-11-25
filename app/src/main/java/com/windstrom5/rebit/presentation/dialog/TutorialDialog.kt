package com.windstrom5.rebit.presentation.dialog

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.windstrom5.rebit.presentation.components.audioPanel
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.OutlineSubtle
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.StatusError
import com.windstrom5.rebit.presentation.theme.StatusWarn
import com.windstrom5.rebit.presentation.theme.SurfaceInk
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.SurfacePanelHigh
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.theme.TextTertiary

private data class TutorialStep(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: Color
)

private val TutorialSteps = listOf(
    TutorialStep("Bit-Perfect 101", "Why standard Android audio is degraded", Icons.Default.Tune, SignalTeal),
    TutorialStep("Connecting USB DAC", "Hardware setup & USB permissions", Icons.Default.Usb, HifiBrass),
    TutorialStep("Library & Formats", "Scanning FLAC, WAV, DSD & CUE files", Icons.Default.Folder, SignalBlue),
    TutorialStep("Driver Engines", "Pipelined, Synchronous & Native C++", Icons.Default.Speed, SignalTeal),
    TutorialStep("Audiophile DSP & AI", "Parametric AutoEq, Tubes & Stem AI", Icons.Default.AutoAwesome, HifiBrass),
    TutorialStep("Streaming", "Qobuz, Tidal & Spotify with your own account", Icons.Default.Radio, Color(0xFFE11D48))
)

@Composable
fun TutorialDialog(
    initialStepIndex: Int = 0,
    onOpenStreaming: (() -> Unit)? = null,
    onDismiss: (dontShowAgain: Boolean) -> Unit
) {
    var currentStep by remember { mutableIntStateOf(initialStepIndex.coerceIn(0, TutorialSteps.size - 1)) }
    var dontShowAgain by remember { mutableStateOf(false) }
    val step = TutorialSteps[currentStep]
    val accent by animateColorAsState(step.accent, label = "tutorial_accent")

    Dialog(
        onDismissRequest = { onDismiss(dontShowAgain) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.93f)
                .clip(RoundedCornerShape(28.dp))
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.35f)), RoundedCornerShape(28.dp)),
            color = BackgroundInk
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Hero header: accent wash, glowing icon, step counter ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), BackgroundInk)))
                        .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.08f))))
                                .border(BorderStroke(1.dp, accent.copy(alpha = 0.6f)), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(step.icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "STEP ${currentStep + 1} OF ${TutorialSteps.size}",
                                fontFamily = TelemetryFontFamily,
                                fontSize = 10.sp,
                                letterSpacing = 1.6.sp,
                                color = accent,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                step.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(step.subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        IconButton(
                            onClick = { onDismiss(dontShowAgain) },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(SurfacePanel.copy(alpha = 0.8f))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // ── Segmented progress: tap any segment to jump ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TutorialSteps.forEachIndexed { index, s ->
                        val reached = index <= currentStep
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (reached) s.accent else SurfacePanelHigh)
                                .clickable { currentStep = index }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Slide content, sliding in the direction you are navigating ──
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .audioPanel(RoundedCornerShape(20.dp))
                ) {
                    AnimatedContent(
                        targetState = currentStep,
                        transitionSpec = {
                            val forward = targetState > initialState
                            (fadeIn() + slideInHorizontally { w -> if (forward) w / 5 else -w / 5 }) togetherWith
                                (fadeOut() + slideOutHorizontally { w -> if (forward) -w / 5 else w / 5 })
                        },
                        label = "tutorial_step_transition"
                    ) { index ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(18.dp)
                        ) {
                            when (index) {
                                0 -> BitPerfect101Step()
                                1 -> ConnectingUsbDacStep()
                                2 -> LibraryAndFormatsStep()
                                3 -> DriverEnginesStep()
                                4 -> AudiophileDspStep()
                                5 -> StreamingStep(onOpenStreaming)
                            }
                        }
                    }
                }

                // ── Bottom controls ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { dontShowAgain = !dontShowAgain }
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = dontShowAgain,
                            onCheckedChange = { dontShowAgain = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = HifiBrass,
                                uncheckedColor = TextSecondary,
                                checkmarkColor = BackgroundInk
                            ),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Don't show this guide on startup",
                            fontFamily = TelemetryFontFamily,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (currentStep > 0) {
                            OutlinedButton(
                                onClick = { currentStep-- },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                                border = BorderStroke(1.dp, OutlineSubtle),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Back", fontFamily = TelemetryFontFamily, fontSize = 12.sp)
                            }
                        }

                        if (currentStep < TutorialSteps.size - 1) {
                            Button(
                                onClick = { currentStep++ },
                                colors = ButtonDefaults.buttonColors(containerColor = accent),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(if (currentStep > 0) 1.4f else 1f)
                                    .height(48.dp)
                            ) {
                                Text("Next", fontFamily = TelemetryFontFamily, fontSize = 12.sp, color = BackgroundInk, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = BackgroundInk, modifier = Modifier.size(16.dp))
                            }
                        } else {
                            Button(
                                onClick = { onDismiss(dontShowAgain) },
                                colors = ButtonDefaults.buttonColors(containerColor = SignalTeal),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(if (currentStep > 0) 1.6f else 1f)
                                    .height(48.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = BackgroundInk, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Start Listening", fontFamily = TelemetryFontFamily, fontSize = 12.sp, color = BackgroundInk, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SLIDE 1: BIT-PERFECT 101
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun BitPerfect101Step() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "What is Bit-Perfect Audio?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = SignalTeal
        )
        Text(
            "Normally, Android's internal audio system (AudioFlinger) forces all sound through a software mixer and resamples everything to a fixed 48 kHz. This Sample Rate Conversion (SRC) introduces clock jitter, harmonic distortion, and alters your music before it ever reaches your headphones.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            lineHeight = 20.sp
        )

        // Comparison Diagram Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceInk),
            border = BorderStroke(1.dp, OutlineSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "SIGNAL PATH COMPARISON",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 10.sp,
                    color = HifiBrass,
                    fontWeight = FontWeight.Bold
                )

                // Standard Android Path
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(StatusError.copy(alpha = 0.08f))
                        .padding(10.dp)
                ) {
                    Text("STANDARD ANDROID (Resampled / Lossy):", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = StatusError, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "96 kHz / 24-bit FLAC ➔ AudioFlinger (Resampled to 48 kHz + Jitter) ➔ DAC",
                        fontFamily = TelemetryFontFamily,
                        fontSize = 11.sp,
                        color = TextPrimary
                    )
                }

                // Bit-Perfect Path
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SignalTeal.copy(alpha = 0.12f))
                        .padding(10.dp)
                ) {
                    Text("BITPERFECT USB (True Studio Master):", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = SignalTeal, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "96 kHz / 24-bit FLAC ➔ Direct Isochronous USB Stream ➔ DAC (Unaltered 96 kHz)",
                        fontFamily = TelemetryFontFamily,
                        fontSize = 11.sp,
                        color = TextPrimary
                    )
                }
            }
        }

        HighlightPoint(
            icon = Icons.Default.Check,
            title = "Zero OS Resampling",
            desc = "44.1 kHz CDs play at 44.1 kHz; 192 kHz high-res files stream at exactly 192 kHz without losing a single bit."
        )
        HighlightPoint(
            icon = Icons.Default.Check,
            title = "Direct Hardware Control",
            desc = "Accesses your USB DAC's physical chips directly using custom asynchronous USB drivers."
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SLIDE 2: HARDWARE SETUP & USB DAC
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ConnectingUsbDacStep() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Connecting Your USB DAC or Dongle",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = HifiBrass
        )
        Text(
            "BitPerfectUSB communicates directly with class-compliant USB Audio Class 1 (UAC1) and Class 2 (UAC2) DACs via USB-C or USB OTG cables.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceInk),
            border = BorderStroke(1.dp, OutlineSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "3 STEPS TO MAXIMUM AUDIO QUALITY",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 10.sp,
                    color = HifiBrass,
                    fontWeight = FontWeight.Bold
                )

                SetupStepItem(
                    number = "1",
                    title = "Plug in your DAC",
                    desc = "Connect your USB-C dongle (e.g. Apple Dongle, Moondrop Dawn, Fiio KA, iFi, Chord, Topping) to your phone's port."
                )
                SetupStepItem(
                    number = "2",
                    title = "Grant USB Host Permission",
                    desc = "When Android displays: 'Allow BitPerfectUSB to access [Your DAC]?', check 'Always allow' and tap OK. This transfers hardware control to the app."
                )
                SetupStepItem(
                    number = "3",
                    title = "Use Hardware Volume",
                    desc = "Enable 'Direct Hardware Volume' in Settings so volume adjustments change the DAC's analog amplifier chip instead of truncating digital bits."
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(HifiBrass.copy(alpha = 0.10f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = HifiBrass, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Tip: If no sound comes out, ensure 'OTG connection' is toggled ON in your phone's system settings (common on OnePlus/Oppo/Vivo).",
                fontFamily = TelemetryFontFamily,
                fontSize = 11.sp,
                color = TextPrimary
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SLIDE 3: LIBRARY & AUDIO FORMATS
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun LibraryAndFormatsStep() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Music Library & Supported Formats",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = SignalBlue
        )
        Text(
            "BitPerfectUSB includes custom lossless decoders that feed uncompressed PCM or direct DSD streams straight to your DAC without transcoding.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceInk),
            border = BorderStroke(1.dp, OutlineSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "SUPPORTED AUDIO STANDARDS",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 10.sp,
                    color = SignalBlue,
                    fontWeight = FontWeight.Bold
                )

                FormatRow(badge = "FLAC / ALAC", desc = "16-bit, 24-bit, 32-bit integer PCM up to 768 kHz")
                FormatRow(badge = "WAV / AIFF", desc = "Uncompressed studio master broadcasts & multi-channel")
                FormatRow(badge = "DSD (.dsf / .dff)", desc = "Direct Stream Digital via DoP (DSD-over-PCM) or Native DSD")
                FormatRow(badge = "CUE Sheets", desc = "Full cue-sheet parsing for single-file lossless album rips")
                FormatRow(badge = "MP3 / AAC / OGG", desc = "Standard compressed audio with optional AI DSEE upscaling")
            }
        }

        HighlightPoint(
            icon = Icons.Default.Folder,
            title = "Adding Music Folders",
            desc = "Go to the 'Library' tab ➔ tap '+ Add Folder' ➔ select your internal storage or micro-SD card music directory ➔ tap 'Force Re-scan'."
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SLIDE 4: DRIVER ENGINES
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DriverEnginesStep() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Understanding Bit-Perfect Driver Profiles",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = SignalTeal
        )
        Text(
            "Under Settings ➔ Audio Driver, you can choose how USB isochronous audio packets are scheduled. All three options are 100% bit-perfect:",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceInk),
            border = BorderStroke(1.dp, OutlineSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EngineExplainer(
                    title = "USB Direct — Pipelined (Recommended)",
                    badge = "DEFAULT",
                    badgeColor = SignalTeal,
                    desc = "Queues isochronous transfers in advance to absorb CPU scheduling spikes, and performs closed-loop adaptive clock correction. Most stable for everyday listening."
                )
                EngineExplainer(
                    title = "USB Direct — Synchronous",
                    badge = "FIXED CLOCK",
                    badgeColor = SignalBlue,
                    desc = "Pushes fixed-size packets synchronously with strict hardware timings. Excellent for reference DACs with stable internal master clocks."
                )
                EngineExplainer(
                    title = "USB Direct — Native (C++ / NDK)",
                    badge = "ULTRA-LOW LATENCY",
                    badgeColor = HifiBrass,
                    desc = "Written in pure C++ using Android NDK. Bypasses JVM Garbage Collection (GC) pauses completely during high-rate 384kHz and DSD playback."
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SLIDE 5: AUDIOPHILE DSP & AI
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AudiophileDspStep() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Precision DSP & On-Device AI Studio",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = HifiBrass
        )
        Text(
            "While Bit-Perfect mode bypasses alterations for pure transparency, you also have studio-grade DSP tools at your fingertips:",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceInk),
            border = BorderStroke(1.dp, OutlineSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HighlightPoint(
                    icon = Icons.Default.Headphones,
                    title = "2,500+ AutoEq Parametric Profiles",
                    desc = "Instant one-tap acoustic frequency calibration for Sennheiser, Moondrop, Sony, Beyerdynamic, Focal, Truthear, and more."
                )
                HighlightPoint(
                    icon = Icons.Default.Tune,
                    title = "Analog Vacuum Tube Warmth",
                    desc = "Simulates triode tube saturation, adding even-order musical harmonics to digital recordings."
                )
                HighlightPoint(
                    icon = Icons.Default.Speed,
                    title = "Real-Time DSD Remastering",
                    desc = "Delta-Sigma upsampler converts standard PCM audio into 2.8 MHz (DSD64) or 5.6 MHz (DSD128) streams on the fly."
                )
                HighlightPoint(
                    icon = Icons.Default.AutoAwesome,
                    title = "AI Neural Studio (Stem Extractor & Karaoke)",
                    desc = "Runs an on-device neural model to isolate vocals (Acapella) or suppress vocals (pure Instrumental) with real-time mic scoring."
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SLIDE 6: STREAMING SERVICES & FREE HI-RES FAQ
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StreamingStep(onOpenStreaming: (() -> Unit)?) {
    val rose = Color(0xFFE11D48)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Bring your own subscription",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = rose
        )
        Text(
            "Sign in with your own Qobuz or Tidal account and the music is decoded and sent to your DAC by the same bit-perfect USB driver as your local files. Spotify can be searched and started from here too, but it plays inside the Spotify app.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            lineHeight = 21.sp
        )

        // Provider cards
        ProviderCard(
            name = "Qobuz",
            accent = Color(0xFF2BA4F0),
            badge = "BIT-PERFECT",
            badgeColor = SignalTeal,
            quality = "FLAC up to 24-bit / 192 kHz (CD quality on lower plans)",
            needs = "Your login + the app id & secret Qobuz issued to you"
        )
        ProviderCard(
            name = "Tidal",
            accent = Color(0xFF22D3EE),
            badge = "BIT-PERFECT",
            badgeColor = SignalTeal,
            quality = "Lossless FLAC, 16-bit / 44.1 kHz (Hi-Res DASH not supported yet)",
            needs = "Approve a code in your browser + your own Tidal client id"
        )
        ProviderCard(
            name = "Spotify",
            accent = Color(0xFF1DB954),
            badge = "VIA SPOTIFY APP",
            badgeColor = HifiBrass,
            quality = "Lossy, up to 320 kbps. Spotify never shares its audio with other apps",
            needs = "Premium account + your Spotify client id"
        )

        // How it works timeline
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceInk)
                .border(BorderStroke(1.dp, OutlineSubtle), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text(
                "HOW IT WORKS",
                fontFamily = TelemetryFontFamily,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                color = rose,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            SetupStepItem("1", "Open Streaming", "Tap the Streaming card on Home and pick a service.", isLast = false, accent = rose)
            SetupStepItem("2", "Sign in once", "Enter your own account details. Tokens are stored encrypted in the Android Keystore; passwords are never saved.", isLast = false, accent = rose)
            SetupStepItem("3", "Search and tap a track", "The track buffers to temporary storage, then plays through your DAC.", isLast = false, accent = rose)
            SetupStepItem("4", "Check the Signal Path", "The Player shows the real sample rate and bit depth the service delivered.", isLast = true, accent = rose)
        }

        if (onOpenStreaming != null) {
            Button(
                onClick = onOpenStreaming,
                colors = ButtonDefaults.buttonColors(containerColor = rose),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.Radio, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Streaming", fontFamily = TelemetryFontFamily, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }

        // Honest FAQ
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(StatusWarn.copy(alpha = 0.08f))
                .border(BorderStroke(1.dp, StatusWarn.copy(alpha = 0.35f)), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = StatusWarn, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "GOOD TO KNOW",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 11.sp,
                    color = StatusWarn,
                    fontWeight = FontWeight.Bold
                )
            }
            ServiceExplanationItem(
                "Is it free?",
                "No. You need your own paid subscription. This app doesn't unlock free streams or bypass any protection."
            )
            ServiceExplanationItem(
                "Terms of service",
                "Each service's terms decide how third-party apps may connect. Check yours before signing in; that's your call and your account."
            )
            ServiceExplanationItem(
                "Free lossless radio",
                "Radio Paradise offers legal, ad-free FLAC streams, and you can serve your own library with Navidrome or Jellyfin."
            )
        }
    }
}

@Composable
private fun ProviderCard(
    name: String,
    accent: Color,
    badge: String,
    badgeColor: Color,
    quality: String,
    needs: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceInk)
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.35f)), RoundedCornerShape(16.dp))
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .fillMaxHeight()
                .background(accent)
        )
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.18f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(badge, fontFamily = TelemetryFontFamily, fontSize = 9.sp, color = badgeColor, fontWeight = FontWeight.Bold)
                }
            }
            Text(quality, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
            Text("You need: $needs", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HELPER COMPONENTS
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun HighlightPoint(icon: ImageVector, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(SignalTeal.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = SignalTeal, modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun SetupStepItem(
    number: String,
    title: String,
    desc: String,
    isLast: Boolean = true,
    accent: Color = HifiBrass
) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.2f))
                    .border(BorderStroke(1.dp, accent.copy(alpha = 0.6f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(number, fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(accent.copy(alpha = 0.25f))
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.padding(bottom = if (isLast) 0.dp else 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun FormatRow(badge: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(100.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SurfacePanelHigh)
                .padding(vertical = 4.dp, horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(badge, fontFamily = TelemetryFontFamily, fontSize = 10.sp, color = HifiBrass, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun EngineExplainer(title: String, badge: String, badgeColor: Color, desc: String) {
    // The badge sits on its own line: beside a long title it was squeezed to a sliver and wrapped one
    // letter per line, stretching the whole row.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(badgeColor.copy(alpha = 0.07f))
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(badgeColor)
        )
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                badge,
                fontFamily = TelemetryFontFamily,
                fontSize = 10.sp,
                color = badgeColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = TextSecondary, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun ServiceExplanationItem(name: String, reason: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, fontFamily = TelemetryFontFamily, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(reason, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
