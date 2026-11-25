package com.windstrom5.rebit.presentation.screen

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import com.windstrom5.rebit.presentation.components.ChoiceChip
import com.windstrom5.rebit.presentation.components.EngravedLabel
import com.windstrom5.rebit.presentation.components.LedDot
import com.windstrom5.rebit.presentation.components.SegmentedTabs
import com.windstrom5.rebit.presentation.components.audioPanel
import com.windstrom5.rebit.presentation.components.AudioPanelDefaults
import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.VolumeUp
import com.windstrom5.rebit.presentation.dialog.TutorialDialog
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.domain.model.BufferMode
import androidx.compose.animation.AnimatedVisibility
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.domain.model.PlaybackConfig
import com.windstrom5.rebit.domain.model.UsbProtocolPreference
import com.windstrom5.rebit.domain.model.UsbTransferStrategy
import com.windstrom5.rebit.domain.model.UserSettings
import com.windstrom5.rebit.domain.model.WatchedFolder
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.StatusError
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.OutlineSubtle
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.StatusGood
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.SurfacePanelHigh
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.theme.TextTertiary
import com.windstrom5.rebit.presentation.theme.StatusWarn
import com.windstrom5.rebit.presentation.viewmodel.SettingsViewModel
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val displayName = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: "Folder"
            viewModel.addFolder(uri, displayName)
        }
    }

    var showTutorial by remember { mutableStateOf(false) }
    var tutorialInitialStep by remember { mutableIntStateOf(0) }

    if (showTutorial) {
        TutorialDialog(
            initialStepIndex = tutorialInitialStep,
            onDismiss = { dontShowAgain ->
                if (dontShowAgain) {
                    viewModel.setTutorialCompleted(true)
                }
                showTutorial = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { com.windstrom5.rebit.presentation.components.ScreenTitle("Settings", "Audio engine and library") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundInk,
                    titleContentColor = TextPrimary
                )
            )
        },
        containerColor = BackgroundInk
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Help & User Guides ──
            SectionHeader("HELP & USER GUIDES")
            Text(
                "Interactive tutorials explaining Bit-Perfect audio mechanics, DAC setup, and streaming service integration FAQ.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { tutorialInitialStep = 0; showTutorial = true },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, HifiBrass.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = HifiBrass, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Beginner Guide", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextPrimary)
                }

                OutlinedButton(
                    onClick = { tutorialInitialStep = 5; showTutorial = true },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SignalTeal.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Radio, contentDescription = null, tint = SignalTeal, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Streaming FAQ", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextPrimary)
                }
            }
            Spacer(modifier = Modifier.height(18.dp))

            // ── Audio Driver ──
            // One unified picker (like a dedicated USB audio player app's driver
            // list) over what used to be two separate binary toggles here
            // (Playback Engine, USB Driver Strategy) — same two underlying
            // settings, just presented as the concrete profiles a person
            // actually picks between, with a plain description of what each
            // one trades off instead of two unlabeled acronyms.
            SectionHeader("AUDIO DRIVER")
            Text(
                "How audio reaches your DAC. Bit-perfect options bypass the Android mixer entirely; System Audio goes through it, which is required for effects like karaoke's real-time DSP.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // All three USB Direct profiles run on the native C++ isochronous engine
                // (usbfs, like UAPP / libusb); they differ only in clocking and queue depth.
                val usb = state.userSettings.defaultEngine == EngineType.CUSTOM_USB_DIRECT
                val strategy = state.userSettings.usbTransferStrategy
                AudioDriverOption(
                    title = "USB Direct — DAC-clocked",
                    description = "Bit-perfect. Native C++ isochronous driver that talks to the DAC through the kernel's usbfs, bypassing Android's mixer, HAL and audio driver. Packets are paced per USB (micro)frame and sized from your DAC's own clock via its asynchronous feedback, so the stream never drifts. Queue depth follows USB buffer latency below. Recommended.",
                    badge = "BIT-PERFECT",
                    badgeColor = SignalTeal,
                    selected = usb && (strategy == UsbTransferStrategy.NATIVE ||
                        (strategy == UsbTransferStrategy.PIPELINED && state.userSettings.useAdaptiveClockCorrection)),
                    onClick = {
                        viewModel.setDefaultEngine(EngineType.CUSTOM_USB_DIRECT)
                        viewModel.setUsbTransferStrategy(UsbTransferStrategy.PIPELINED)
                        viewModel.setUseAdaptiveClockCorrection(true)
                    }
                )
                AudioDriverOption(
                    title = "USB Direct — Fixed rate",
                    description = "Bit-perfect. Same native driver, but ignores the DAC's feedback and sends the exact nominal rate. For synchronous/adaptive DACs, or ones whose feedback readings are unreliable.",
                    badge = "BIT-PERFECT",
                    badgeColor = SignalBlue,
                    selected = usb && strategy == UsbTransferStrategy.PIPELINED && !state.userSettings.useAdaptiveClockCorrection,
                    onClick = {
                        viewModel.setDefaultEngine(EngineType.CUSTOM_USB_DIRECT)
                        viewModel.setUsbTransferStrategy(UsbTransferStrategy.PIPELINED)
                        viewModel.setUseAdaptiveClockCorrection(false)
                    }
                )
                AudioDriverOption(
                    title = "USB Direct — Low latency",
                    description = "Bit-perfect, DAC-clocked, with only an 8 ms transfer queue. Quicker response to play/seek, but more sensitive to the phone being busy — use the DAC-clocked profile if you hear clicks.",
                    badge = "BIT-PERFECT",
                    badgeColor = SignalBlue,
                    selected = usb && strategy == UsbTransferStrategy.SYNCHRONOUS,
                    onClick = {
                        viewModel.setDefaultEngine(EngineType.CUSTOM_USB_DIRECT)
                        viewModel.setUsbTransferStrategy(UsbTransferStrategy.SYNCHRONOUS)
                    }
                )
                AudioDriverOption(
                    title = "Android Hi-Res Direct",
                    description = "Direct / Low-Latency Bit-Perfect HAL. Bypasses Android 48kHz mixer on devices with internal Hi-Res DACs (LG QuadDAC, Sony, Samsung UHQ, DAC dongles) supporting native 44.1k/96k/192k 24/32-bit output.",
                    badge = "HI-RES DIRECT",
                    badgeColor = SignalTeal,
                    selected = state.userSettings.defaultEngine == EngineType.ANDROID_HIRES_DIRECT,
                    onClick = {
                        viewModel.setDefaultEngine(EngineType.ANDROID_HIRES_DIRECT)
                        viewModel.setHiResDirectOutputEnabled(true)
                    }
                )
                AudioDriverOption(
                    title = "System Audio",
                    description = "Standard Android AudioTrack output. Works without a USB DAC attached, and is required for karaoke's real-time DSP and other live effects to run.",
                    badge = "STANDARD",
                    badgeColor = HifiBrass,
                    selected = state.userSettings.defaultEngine == EngineType.ANDROID_AUDIOTRACK,
                    onClick = { viewModel.setDefaultEngine(EngineType.ANDROID_AUDIOTRACK) }
                )
            }

            AnimatedVisibility(visible = state.userSettings.defaultEngine == EngineType.CUSTOM_USB_DIRECT) {
                Column {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "USB PROTOCOL",
                        fontFamily = TelemetryFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = TextTertiary,
                        letterSpacing = 0.4.sp
                    )
                    Text(
                        "Only matters on a DAC that exposes both UAC1 and UAC2 altsettings for the same format — forcing one can fix a device with quirky or ambiguous descriptors. Has no effect on a DAC that only speaks one protocol version.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        UsbProtocolChip("Auto", UsbProtocolPreference.AUTO, state.userSettings.usbProtocolPreference) {
                            viewModel.setUsbProtocolPreference(it)
                        }
                        UsbProtocolChip("Force UAC 1.0", UsbProtocolPreference.FORCE_UAC1, state.userSettings.usbProtocolPreference) {
                            viewModel.setUsbProtocolPreference(it)
                        }
                        UsbProtocolChip("Force UAC 2.0", UsbProtocolPreference.FORCE_UAC2, state.userSettings.usbProtocolPreference) {
                            viewModel.setUsbProtocolPreference(it)
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = OutlineSubtle)

            // ── Buffer Mode ──
            SectionHeader("BUFFER OPTIMIZATION")
            Text(
                "Adaptive dynamic tuning balances latency and stability. Manual locks it to a constant value.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val adaptiveSelected = state.userSettings.bufferMode == BufferMode.ADAPTIVE
                FilterChip(
                    selected = adaptiveSelected,
                    onClick = { viewModel.setBufferMode(BufferMode.ADAPTIVE) },
                    label = { Text("ADAPTIVE (AUTO)", fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SignalTeal.copy(alpha = 0.15f),
                        selectedLabelColor = SignalTeal
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = OutlineSubtle,
                        selectedBorderColor = SignalTeal.copy(alpha = 0.5f),
                        enabled = true,
                        selected = adaptiveSelected
                    )
                )
                val manualSelected = state.userSettings.bufferMode == BufferMode.MANUAL
                FilterChip(
                    selected = manualSelected,
                    onClick = { viewModel.setBufferMode(BufferMode.MANUAL) },
                    label = { Text("MANUAL LOCK", fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StatusWarn.copy(alpha = 0.15f),
                        selectedLabelColor = StatusWarn
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = OutlineSubtle,
                        selectedBorderColor = StatusWarn.copy(alpha = 0.5f),
                        enabled = true,
                        selected = manualSelected
                    )
                )
            }

            if (state.userSettings.bufferMode == BufferMode.MANUAL) {
                Text(
                    "STATIC BUFFER VALUE: ${state.userSettings.manualBufferSizeBytes} B",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 11.sp,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BUFFER_SIZE_OPTIONS) { size ->
                        val selected = state.userSettings.manualBufferSizeBytes == size
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setManualBufferSize(size) },
                            label = { Text("${size}B", fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SignalTeal.copy(alpha = 0.15f),
                                selectedLabelColor = SignalTeal
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = OutlineSubtle,
                                selectedBorderColor = SignalTeal.copy(alpha = 0.5f),
                                enabled = true,
                                selected = selected
                            )
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = OutlineSubtle)

            // ── USB Buffering Depth ──
            SectionHeader("USB BUFFER LATENCY")
            Text(
                "How much audio the native USB driver keeps queued with the kernel. More rides out a busy phone without clicks; less makes play, pause and seek respond faster. 32 ms suits most DACs.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (ms in UserSettings.USB_BUFFER_PRESETS_MS) {
                    val selected = state.userSettings.usbBufferLatencyMs == ms
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setUsbBufferLatency(ms) },
                        label = { Text("$ms ms", fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SignalTeal.copy(alpha = 0.15f),
                            selectedLabelColor = SignalTeal
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = OutlineSubtle,
                            selectedBorderColor = SignalTeal.copy(alpha = 0.5f),
                            enabled = true,
                            selected = selected
                        )
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = OutlineSubtle)

            // ── Keep Screen On ──
            SectionHeader("WAKELOCK CORE CONTROL")
            Text(
                "Keeping the screen awake prevents low-power CPU throttling and scheduling degradation.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PREVENT DOZE THROTTLING",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 11.sp,
                    color = TextPrimary
                )
                Switch(
                    checked = state.userSettings.keepScreenOnDuringPlayback,
                    onCheckedChange = { viewModel.setKeepScreenOn(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SignalTeal,
                        checkedTrackColor = SignalTeal.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = OutlineSubtle
                    )
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = OutlineSubtle)

            OutputQualitySection(
                settings = state.userSettings,
                onGapless = viewModel::setGaplessEnabled,
                onResampler = viewModel::setResamplerQuality,
                onDither = viewModel::setDitherMode,
                onPreventClipping = viewModel::setPreventClipping
            )

            RoomAndLoudnessSection(
                settings = state.userSettings,
                onPickIr = { uri, name -> viewModel.setConvolutionIr(uri, name) },
                onClearIr = { viewModel.setConvolutionIr(null, null) },
                onConvolution = viewModel::setConvolutionEnabled,
                onLoudness = viewModel::setLoudnessCompensation,
                onLoudnessStrength = viewModel::setLoudnessCompensationStrength
            )

            // ── Audiophile DSP & Bit-Perfect Auto-Bypass ──
            SectionHeader("AUDIOPHILE DSP & BIT-PERFECT AUTO-BYPASS")
            Text(
                "When Custom USB Direct Engine is active, Bit-Perfect Auto-Bypass automatically disables all software DSP (EQ, Crossfeed, ReplayGain) to preserve 100% bit-exact PCM stream integrity.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AUTO-BYPASS DSP IN BIT-PERFECT MODE",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 11.sp,
                    color = TextPrimary
                )
                Switch(
                    checked = state.userSettings.autoBypassDspInBitPerfect,
                    onCheckedChange = { viewModel.toggleAutoBypassDsp(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SignalTeal,
                        checkedTrackColor = SignalTeal.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = OutlineSubtle
                    )
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "AUDIOPHILE EQ PRESET (AUDIOTRACK MODE ONLY)",
                fontFamily = TelemetryFontFamily,
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(com.windstrom5.rebit.domain.model.EqPreset.entries) { preset ->
                    val selected = state.userSettings.eqPreset == preset
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setEqPreset(preset) },
                        label = { Text(preset.label, fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SignalTeal.copy(alpha = 0.15f),
                            selectedLabelColor = SignalTeal
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = OutlineSubtle,
                            selectedBorderColor = SignalTeal.copy(alpha = 0.5f),
                            enabled = true,
                            selected = selected
                        )
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = OutlineSubtle)

            // ── DSD Remastering & Audiophile Engine ──
            SectionHeader("DSD REMASTERING & AUDIOPHILE ENGINE")
            Text(
                "HQPlayer & TEAC style real-time Sigma-Delta Modulator (SDM). Converts any PCM stream into high-rate 1-bit DSD bitstream with ultrasonic noise shaping.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ENABLE DSD REMASTERING",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 11.sp,
                    color = TextPrimary
                )
                Switch(
                    checked = state.userSettings.dsdRemasteringEnabled,
                    onCheckedChange = { viewModel.setDsdRemasteringEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SignalTeal,
                        checkedTrackColor = SignalTeal.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = OutlineSubtle
                    )
                )
            }

            AnimatedVisibility(visible = state.userSettings.dsdRemasteringEnabled) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "DSD TARGET RATE",
                        fontFamily = TelemetryFontFamily,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(com.windstrom5.rebit.domain.model.DsdRemasterTarget.entries) { target ->
                            val selected = state.userSettings.dsdRemasterTarget == target
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setDsdRemasterTarget(target) },
                                label = { Text(target.label, fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SignalBlue.copy(alpha = 0.15f),
                                    selectedLabelColor = SignalBlue
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "NOISE SHAPING PROFILE",
                        fontFamily = TelemetryFontFamily,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(com.windstrom5.rebit.domain.model.DsdNoiseShaperProfile.entries) { profile ->
                            val selected = state.userSettings.dsdNoiseShaperProfile == profile
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setDsdNoiseShaperProfile(profile) },
                                label = { Text(profile.label, fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SignalBlue.copy(alpha = 0.15f),
                                    selectedLabelColor = SignalBlue
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Analog Vacuum Tube Warmth ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TRIODE TUBE WARMTH (12AX7)",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 11.sp,
                    color = TextPrimary
                )
                Switch(
                    checked = state.userSettings.tubeWarmthEnabled,
                    onCheckedChange = { viewModel.setTubeWarmthEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = HifiBrass,
                        checkedTrackColor = HifiBrass.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = OutlineSubtle
                    )
                )
            }

            AnimatedVisibility(visible = state.userSettings.tubeWarmthEnabled) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "TUBE DRIVE INTENSITY: ${(state.userSettings.tubeWarmthDrive * 100).toInt()}%",
                        fontFamily = TelemetryFontFamily,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Slider(
                        value = state.userSettings.tubeWarmthDrive,
                        onValueChange = { viewModel.setTubeWarmthDrive(it) },
                        valueRange = 0.05f..1.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = HifiBrass,
                            activeTrackColor = HifiBrass,
                            inactiveTrackColor = OutlineSubtle
                        )
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = OutlineSubtle)


            // ── Digital Volume ──
            SectionHeader("DIGITAL VOLTAGE ATTENUATION")
            Text(
                "Bit-perfect delivery requires exactly 0 dB (unity gain) in the digital domain. Scaling multiplies every sample, altering the payload.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            OutlinedButton(
                onClick = { normalizeSystemVolume(context) },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, SignalTeal.copy(alpha = 0.3f))
            ) {
                Icon(
                    Icons.Filled.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                    tint = SignalTeal
                )
                Text(
                    "CALIBRATE SYSTEM VOLUME (0 DB)",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 11.sp,
                    color = SignalTeal,
                    letterSpacing = 0.4.sp
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = OutlineSubtle)

            // ── Library Sources ──
            SectionHeader("INDEXING DIRECTORIES")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "WATCHED DIRECTORIES (${state.watchedFolders.size})",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    enabled = !state.isBusy,
                    border = BorderStroke(1.dp, SignalTeal.copy(alpha = 0.3f))
                ) {
                    Icon(
                        Icons.Filled.CreateNewFolder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = SignalTeal
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "ADD",
                        fontFamily = TelemetryFontFamily,
                        fontSize = 11.sp,
                        color = SignalTeal,
                        letterSpacing = 0.4.sp
                    )
                }
            }
            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp), color = SignalTeal)
            }
            state.error?.let {
                Text(it, color = StatusError, fontFamily = TelemetryFontFamily, fontSize = 11.sp)
            }
            if (state.watchedFolders.isEmpty()) {
                Text(
                    "No custom directories watched. MediaStore default scanner active.",
                    color = TextSecondary.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.watchedFolders.forEach { folder ->
                        WatchedFolderCard(folder, onRemove = { viewModel.removeFolder(folder.id) })
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = OutlineSubtle)

            // ── About ──
            SectionHeader("ABOUT")
            val appVersion = remember { com.windstrom5.rebit.data.audio.AppVersion.read(context) }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(appVersion.appLabel, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    Text(
                        appVersion.display,
                        fontFamily = TelemetryFontFamily, fontSize = 12.sp, color = TextSecondary
                    )
                }
                if (appVersion.isBeta) {
                    Text(
                        "BETA",
                        fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        color = HifiBrass,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(HifiBrass.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
            if (appVersion.isBeta) {
                Text(
                    "First beta. Expect rough edges, and please report anything odd you hear or see.",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            SectionHeader("SPECIFICATIONS")
            Text(
                "BitPerfect USB Audio Client — direct user-space UAC1/UAC2 Direct JNI endpoints, async feedback loop drift telemetry, and Welch/ANOVA validation instrumentation.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun WatchedFolderCard(folder: WatchedFolder, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().audioPanel(RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = SignalTeal, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            folder.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Remove folder", tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    EngravedLabel(sentenceCase(text), modifier = Modifier.padding(top = 6.dp))
}

private fun sentenceCase(t: String): String =
    if (t.any { it.isLetter() } && t == t.uppercase()) t.lowercase().replaceFirstChar { it.uppercase() } else t

/** One option in the Audio Driver picker: a faceplate card with an LED that lights when selected. */
@Composable
private fun AudioDriverOption(
    title: String,
    description: String,
    badge: String,
    badgeColor: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .audioPanel(RoundedCornerShape(20.dp))
            .then(
                if (selected) Modifier.border(1.5.dp, badgeColor.copy(alpha = 0.7f), RoundedCornerShape(20.dp)) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.padding(top = 5.dp).size(10.dp), contentAlignment = Alignment.Center) {
            if (selected) LedDot(badgeColor, size = 9.dp)
            else Box(Modifier.size(9.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(
                    badge,
                    fontFamily = TelemetryFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = badgeColor,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(badgeColor.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

private val BUFFER_SIZE_OPTIONS = listOf(1024, 2048, 4096, 8192, 16384, 32768)
    .also { require(it.first() >= PlaybackConfig.MIN_BUFFER_BYTES && it.last() <= PlaybackConfig.MAX_BUFFER_BYTES) }

@Composable
private fun UsbProtocolChip(
    label: String,
    value: UsbProtocolPreference,
    current: UsbProtocolPreference,
    onSelect: (UsbProtocolPreference) -> Unit
) {
    ChoiceChip(label = label, selected = current == value, onClick = { onSelect(value) }, accent = SignalTeal)
}

private fun normalizeSystemVolume(context: Context) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
}
