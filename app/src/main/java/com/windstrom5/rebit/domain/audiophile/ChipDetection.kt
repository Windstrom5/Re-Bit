package com.windstrom5.rebit.domain.audiophile

/** How sure we are about the DAC chip, and where the answer came from. */
enum class ChipConfidence {
    /** The chip's part number appears in the DAC's own USB product/manufacturer string. */
    NAMED_BY_DEVICE,

    /** Matched from the USB vendor + product ID (e.g. TI PCM27xx USB codecs). */
    IDENTIFIED_BY_USB_ID,

    /** Only the USB interface chip is known (from the vendor ID); the converter isn't reported. */
    USB_CONTROLLER_ONLY,

    /** Nothing identifiable. USB Audio Class doesn't require a DAC to report its converter. */
    NOT_REPORTED
}

data class DacChipInfo(
    /** The D/A converter part number, e.g. "ES9038Q2M", when it can be determined. */
    val converterChip: String?,
    val converterMaker: String?,
    /** The chip that speaks USB on the DAC's side, e.g. "XMOS", when the vendor ID reveals it. */
    val usbController: String?,
    val confidence: ChipConfidence
) {
    /** One short line for a stage label: "ES9038Q2M (ESS)" or null if unknown. */
    val shortLabel: String?
        get() = converterChip?.let { chip -> converterMaker?.substringBefore(" (")?.let { "$chip · $it" } ?: chip }

    val note: String
        get() = when (confidence) {
            ChipConfidence.NAMED_BY_DEVICE -> "Read from the DAC's own USB product name."
            ChipConfidence.IDENTIFIED_BY_USB_ID -> "Matched from the DAC's USB vendor and product ID."
            ChipConfidence.USB_CONTROLLER_ONLY ->
                "USB doesn't report the D/A converter. Only the USB interface chip can be told from the vendor ID."
            ChipConfidence.NOT_REPORTED -> "This DAC doesn't report its chip over USB."
        }
}

/**
 * Works out which chip a USB DAC uses, from what the DAC itself tells us.
 *
 * Honest limits: the USB Audio Class descriptors carry no field for the
 * D/A converter, so a part number is only available when the manufacturer put
 * it in the product string (common for dongles, e.g. "... ES9038Q2M") or when
 * the DAC is a USB codec identified by its vendor/product ID. Otherwise we can
 * at best name the USB interface chip from the vendor ID, and say so.
 */
object DacChipIdentifier {

    private data class Rule(val regex: Regex, val maker: String)

    private val RULES = listOf(
        Rule(Regex("""\bES9\d{3}[A-Z0-9]*\b""", RegexOption.IGNORE_CASE), "ESS Technology (Sabre)"),
        Rule(Regex("""\bAK4\d{3}[A-Z0-9]*\b""", RegexOption.IGNORE_CASE), "AKM (Asahi Kasei Microdevices)"),
        Rule(Regex("""\bCS4(?:\d{4}|2L\d+)[A-Z0-9]*\b""", RegexOption.IGNORE_CASE), "Cirrus Logic"),
        Rule(Regex("""\bPCM[1-5]\d{3}[A-Z0-9]*\b""", RegexOption.IGNORE_CASE), "Texas Instruments (Burr-Brown)"),
        Rule(Regex("""\bWM8\d{3}[A-Z0-9]*\b""", RegexOption.IGNORE_CASE), "Wolfson (Cirrus Logic)"),
        Rule(Regex("""\bALC\d{3,4}[A-Z0-9]*\b""", RegexOption.IGNORE_CASE), "Realtek"),
        Rule(Regex("""\bWCD9\d{3}[A-Z0-9]*\b""", RegexOption.IGNORE_CASE), "Qualcomm")
    )

    /** USB vendor IDs of chip makers whose silicon sits on the USB side of many DACs. */
    private val USB_CONTROLLERS = mapOf(
        0x20B1 to "XMOS",
        0x0D8C to "C-Media",
        0x0BDA to "Realtek",
        0x152A to "Thesycon (USB audio firmware)",
        0x08BB to "Texas Instruments (Burr-Brown)"
    )

    /** TI/Burr-Brown USB audio codecs: one chip is both USB receiver and DAC. */
    private val TI_USB_CODECS = mapOf(
        0x2702 to "PCM2702",
        0x2704 to "PCM2704",
        0x2706 to "PCM2706",
        0x2900 to "PCM2900"
    )

    fun identify(vendorId: Int, productId: Int, productName: String?, manufacturerName: String?): DacChipInfo {
        val usbController = USB_CONTROLLERS[vendorId]
        val text = listOfNotNull(productName, manufacturerName).joinToString(" ")

        for (rule in RULES) {
            val hit = rule.regex.find(text) ?: continue
            return DacChipInfo(hit.value.uppercase(), rule.maker, usbController, ChipConfidence.NAMED_BY_DEVICE)
        }
        if (vendorId == 0x08BB) {
            TI_USB_CODECS[productId]?.let {
                return DacChipInfo(it, "Texas Instruments (Burr-Brown)", usbController, ChipConfidence.IDENTIFIED_BY_USB_ID)
            }
        }
        return DacChipInfo(
            converterChip = null,
            converterMaker = null,
            usbController = usbController,
            confidence = if (usbController != null) ChipConfidence.USB_CONTROLLER_ONLY else ChipConfidence.NOT_REPORTED
        )
    }
}

/** Marketing names for phone SoCs, from the codes Android reports. Unknown codes are shown as reported. */
object SocNames {

    private val SNAPDRAGON = mapOf(
        "SM8150" to "Snapdragon 855",
        "SM8250" to "Snapdragon 865",
        "SM8350" to "Snapdragon 888",
        "SM8450" to "Snapdragon 8 Gen 1",
        "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8550" to "Snapdragon 8 Gen 2",
        "SM8650" to "Snapdragon 8 Gen 3",
        "SM8750" to "Snapdragon 8 Elite"
    )

    private fun clean(s: String?): String? =
        s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }

    /**
     * @param socManufacturer / [socModel] Build.SOC_MANUFACTURER / SOC_MODEL (Android 12+), else null.
     * @param hardware Build.HARDWARE, used when the SoC fields aren't available (e.g. "qcom", "mt6893").
     */
    fun friendly(socManufacturer: String?, socModel: String?, hardware: String?, board: String?): String {
        val maker = clean(socManufacturer)
        val model = clean(socModel)
        if (model != null) {
            SNAPDRAGON[model.uppercase()]?.let { return it }
            return if (maker != null && !model.startsWith(maker, ignoreCase = true)) "$maker $model" else model
        }
        val hw = clean(hardware)
        val b = clean(board)
        return when {
            hw != null && b != null && !hw.equals(b, ignoreCase = true) -> "$hw ($b)"
            hw != null -> hw
            b != null -> b
            else -> "Unknown SoC"
        }
    }
}

enum class RouteKind { USB, ANALOG, BLUETOOTH, HDMI, SPEAKER, OTHER }

/** One link in "which silicon is touching the audio right now". */
data class ProcessingStage(
    val task: String,
    val processor: String,
    val detail: String,
    /** True when this stage is skipped entirely on the current path. */
    val bypassed: Boolean = false
)

data class ProcessingInputs(
    val usbDirect: Boolean,
    val socName: String,
    val dspActive: Boolean,
    val routeKind: RouteKind,
    val dacName: String?,
    val dacChip: DacChipInfo?
)

object ProcessingChain {

    fun build(i: ProcessingInputs): List<ProcessingStage> {
        val stages = ArrayList<ProcessingStage>()

        stages += ProcessingStage(
            task = "Decoding",
            processor = "${i.socName} (CPU)",
            detail = "The file is decoded to raw PCM inside this app."
        )

        stages += if (i.dspActive) {
            ProcessingStage("Effects (EQ / DSP)", "${i.socName} (CPU)", "Your enabled effects alter the samples in software.")
        } else {
            ProcessingStage("Effects (EQ / DSP)", "None", "No effect is touching the samples.", bypassed = true)
        }

        if (i.usbDirect) {
            stages += ProcessingStage(
                "Android audio system", "Bypassed",
                "No mixer, resampler or system sound effect sits in the path.", bypassed = true
            )
            stages += ProcessingStage(
                "USB transport", "${i.socName} USB controller",
                "Samples go to the DAC as isochronous USB packets."
            )
            stages += dacStage(i.dacName, i.dacChip, "The DAC converts the samples to analog on its own clock.")
        } else {
            stages += ProcessingStage(
                "Mixing & resampling", "Android AudioFlinger on ${i.socName}",
                "The system mixer may resample to its own rate and apply system sound effects."
            )
            stages += when (i.routeKind) {
                RouteKind.USB -> dacStage(i.dacName, i.dacChip, "Android sends audio to the USB DAC, resampled by the mixer.")
                RouteKind.BLUETOOTH -> ProcessingStage(
                    "D/A conversion", "Your Bluetooth headphones",
                    "Audio is compressed by a lossy Bluetooth codec first, so it can't be bit-perfect."
                )
                RouteKind.HDMI -> ProcessingStage("D/A conversion", "The HDMI display / receiver", "Converted by the device you're connected to.")
                RouteKind.ANALOG -> ProcessingStage(
                    "D/A conversion", "The phone's internal audio codec",
                    "Android doesn't report which codec chip that is."
                )
                RouteKind.SPEAKER, RouteKind.OTHER -> ProcessingStage(
                    "D/A conversion", "The phone's internal audio codec",
                    "Android doesn't report which codec chip that is."
                )
            }
        }
        return stages
    }

    private fun dacStage(name: String?, chip: DacChipInfo?, detail: String): ProcessingStage {
        val who = when {
            chip?.converterChip != null -> "${chip.converterChip}${chip.converterMaker?.let { " · ${it.substringBefore(" (")}" } ?: ""}"
            name != null -> name
            else -> "USB DAC"
        }
        return ProcessingStage("D/A conversion", who, detail)
    }
}
