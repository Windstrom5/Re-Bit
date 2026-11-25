package com.windstrom5.rebit.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.windstrom5.rebit.domain.model.DitherMode
import com.windstrom5.rebit.domain.model.ResamplerQuality
import com.windstrom5.rebit.domain.model.UserSettings
import com.windstrom5.rebit.presentation.components.AudioPanel
import com.windstrom5.rebit.presentation.components.ChoiceChip
import com.windstrom5.rebit.presentation.components.EngravedLabel
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.OutlineSubtle
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary

/**
 * Settings › Output quality: everything that decides what happens to the samples on their way
 * out — gapless joins, the sample-rate converter, dither, and the clip-protection limiter.
 */
@Composable
internal fun OutputQualitySection(
    settings: UserSettings,
    onGapless: (Boolean) -> Unit,
    onResampler: (ResamplerQuality) -> Unit,
    onDither: (DitherMode) -> Unit,
    onPreventClipping: (Boolean) -> Unit
) {
    EngravedLabel("Output quality", modifier = Modifier.padding(top = 6.dp))
    Text(
        "How the player handles track joins, rate conversion and bit-depth reduction. " +
            "None of this touches USB Direct, which stays bit-perfect.",
        color = TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
    )

    AudioPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ToggleRow(
                title = "Gapless playback",
                subtitle = "Consecutive tracks in the same format play with no silence between them — " +
                    "the next file is opened 15 s early and spliced on at the exact sample.",
                checked = settings.gaplessEnabled,
                accent = SignalTeal,
                onChange = onGapless
            )

            Divider()

            OptionGroup(
                title = "Sample-rate converter",
                subtitle = "Used only when your phone's audio output can't run at the track's own rate.",
                options = ResamplerQuality.entries,
                selected = settings.resamplerQuality,
                label = { it.label },
                description = { it.description },
                accent = HifiBrass,
                onSelect = onResampler
            )

            Divider()

            OptionGroup(
                title = "Dither",
                subtitle = "Applied when processed audio is reduced to a 16- or 24-bit output.",
                options = DitherMode.entries,
                selected = settings.ditherMode,
                label = { it.label },
                description = { it.description },
                accent = SignalBlue,
                onSelect = onDither
            )

            Divider()

            ToggleRow(
                title = "Prevent clipping",
                subtitle = "A transparent look-ahead limiter catches overs from EQ boosts, ReplayGain and " +
                    "effects, instead of letting them hard-clip. Inactive when no processing is on.",
                checked = settings.preventClipping,
                accent = SignalTeal,
                onChange = onPreventClipping
            )
        }
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun Divider() {
    androidx.compose.material3.HorizontalDivider(color = OutlineSubtle)
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accent,
                checkedTrackColor = accent.copy(alpha = 0.3f),
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = OutlineSubtle
            )
        )
    }
}

@Composable
private fun <T> OptionGroup(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: (T) -> String,
    accent: Color,
    onSelect: (T) -> Unit
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                ChoiceChip(
                    label = label(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    accent = accent,
                    led = if (option == selected) accent else null
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(description(selected), style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

/**
 * Settings › Room correction & loudness: FIR convolution with a user-chosen impulse response,
 * and equal-loudness compensation that follows the media volume. Both run on the Android
 * output path; USB Direct stays bit-perfect and never applies them.
 */
@Composable
internal fun RoomAndLoudnessSection(
    settings: UserSettings,
    onPickIr: (uri: String, name: String?) -> Unit,
    onClearIr: () -> Unit,
    onConvolution: (Boolean) -> Unit,
    onLoudness: (Boolean) -> Unit,
    onLoudnessStrength: (Float) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
            onPickIr(uri.toString(), name)
        }
    }

    EngravedLabel("Room correction & loudness", modifier = Modifier.padding(top = 6.dp))
    Text(
        "Applied on the Android output path. USB Direct stays bit-perfect and skips them.",
        color = TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
    )
    AudioPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text("Convolution", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(
                    "Load an impulse response (WAV, FLAC or AIFF — e.g. from REW, DRC or a headphone target) to correct your room or headphones. " +
                        "It's resampled to the output rate and normalised so it never adds gain; up to 65 536 taps.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChoiceChip(
                        label = settings.convolutionIrName ?: if (settings.convolutionIrUri != null) "Impulse response" else "Choose file…",
                        selected = settings.convolutionIrUri != null,
                        onClick = { picker.launch(arrayOf("audio/*")) },
                        accent = SignalTeal,
                        led = if (settings.convolutionEnabled && settings.convolutionIrUri != null) SignalTeal else null
                    )
                    Spacer(Modifier.weight(1f))
                    if (settings.convolutionIrUri != null) {
                        Text(
                            "Remove",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary,
                            modifier = Modifier.clickable(onClick = onClearIr).padding(8.dp)
                        )
                        Switch(
                            checked = settings.convolutionEnabled,
                            onCheckedChange = onConvolution,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SignalTeal, checkedTrackColor = SignalTeal.copy(alpha = 0.3f),
                                uncheckedThumbColor = TextSecondary, uncheckedTrackColor = OutlineSubtle
                            )
                        )
                    }
                }
            }

            Divider()

            ToggleRow(
                title = "Loudness compensation",
                subtitle = "At low volume the ear loses bass and extreme treble first (ISO 226). This adds them back in proportion to how far the volume is turned down — none at full volume.",
                checked = settings.loudnessCompensationEnabled,
                accent = HifiBrass,
                onChange = onLoudness
            )
            if (settings.loudnessCompensationEnabled) {
                Column {
                    val label = when {
                        settings.loudnessCompensationStrength < 0.8f -> "Gentle"
                        settings.loudnessCompensationStrength <= 1.2f -> "ISO 226"
                        else -> "Strong"
                    }
                    Row {
                        Text("Strength", style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.weight(1f))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = HifiBrass)
                    }
                    com.windstrom5.rebit.presentation.components.HifiSlider(
                        value = settings.loudnessCompensationStrength,
                        onValueChange = onLoudnessStrength,
                        accent = HifiBrass,
                        valueRange = 0.5f..1.5f
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}
