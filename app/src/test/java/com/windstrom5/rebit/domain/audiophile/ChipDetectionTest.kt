package com.windstrom5.rebit.domain.audiophile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipDetectionTest {

    @Test
    fun chipNamedInProductString_isRead() {
        val i = DacChipIdentifier.identify(0x1234, 0x0001, "FiiO KA3 (ES9038Q2M)", "FiiO")
        assertEquals("ES9038Q2M", i.converterChip)
        assertEquals(ChipConfidence.NAMED_BY_DEVICE, i.confidence)
        assertEquals("ES9038Q2M · ESS Technology", i.shortLabel)
    }

    @Test
    fun cirrusAndAkmAndTiParts_areRecognised() {
        assertEquals("CS43131", DacChipIdentifier.identify(1, 1, "Dongle CS43131", null).converterChip)
        assertEquals("AK4493SEQ", DacChipIdentifier.identify(1, 1, "Desk DAC ak4493seq", null).converterChip)
        assertEquals("PCM5102A", DacChipIdentifier.identify(1, 1, "I2S bridge PCM5102A", null).converterChip)
    }

    @Test
    fun tiUsbCodec_isIdentifiedFromUsbId() {
        val i = DacChipIdentifier.identify(0x08BB, 0x2704, "USB Audio DAC", "Burr-Brown from TI")
        assertEquals("PCM2704", i.converterChip)
        assertEquals(ChipConfidence.IDENTIFIED_BY_USB_ID, i.confidence)
    }

    @Test
    fun vendorIdAlone_onlyRevealsTheUsbController_neverGuessesTheDac() {
        val i = DacChipIdentifier.identify(0x20B1, 0x0001, "Generic DAC", null)
        assertNull(i.converterChip)
        assertEquals("XMOS", i.usbController)
        assertEquals(ChipConfidence.USB_CONTROLLER_ONLY, i.confidence)
        assertNull(i.shortLabel)
    }

    @Test
    fun unknownDac_isHonestlyNotReported() {
        val i = DacChipIdentifier.identify(0x9999, 0x0001, "Mystery DAC", null)
        assertNull(i.converterChip)
        assertEquals(ChipConfidence.NOT_REPORTED, i.confidence)
    }

    @Test
    fun socNames_mapKnownSnapdragonCodes() {
        assertEquals("Snapdragon 8 Gen 2", SocNames.friendly("Qualcomm", "SM8550", "qcom", "kalama"))
        assertEquals("Snapdragon 8 Elite", SocNames.friendly("QTI", "sm8750", null, null))
    }

    @Test
    fun socNames_fallBackToReportedValues() {
        assertEquals("Google Tensor G3", SocNames.friendly("Google", "Tensor G3", "tensor", "shiba"))
        assertEquals("mt6893 (k6893v1)", SocNames.friendly(null, null, "mt6893", "k6893v1"))
        assertEquals("Unknown SoC", SocNames.friendly("UNKNOWN", "unknown", "", ""))
    }

    private fun inputs(usb: Boolean, dsp: Boolean = false, route: RouteKind = RouteKind.SPEAKER, chip: DacChipInfo? = null) =
        ProcessingInputs(usb, "Snapdragon 8 Gen 2", dsp, route, "Test DAC", chip)

    @Test
    fun usbDirect_bypassesAndroidMixer_andNamesTheDacChip() {
        val chip = DacChipIdentifier.identify(1, 1, "KA3 ES9038Q2M", null)
        val stages = ProcessingChain.build(inputs(usb = true, chip = chip))
        assertTrue(stages.first { it.task == "Android audio system" }.bypassed)
        assertTrue(stages.last().processor.startsWith("ES9038Q2M"))
        assertEquals("D/A conversion", stages.last().task)
    }

    @Test
    fun mixerPath_attributesMixingToAndroid_andBluetoothIsNotTheDac() {
        val stages = ProcessingChain.build(inputs(usb = false, route = RouteKind.BLUETOOTH))
        assertTrue(stages.any { it.processor.startsWith("Android AudioFlinger") })
        assertEquals("Your Bluetooth headphones", stages.last().processor)
    }

    @Test
    fun dspFlag_movesEffectsOntoTheCpu() {
        assertFalse(ProcessingChain.build(inputs(usb = true, dsp = true)).first { it.task.startsWith("Effects") }.bypassed)
        assertTrue(ProcessingChain.build(inputs(usb = true, dsp = false)).first { it.task.startsWith("Effects") }.bypassed)
    }
}
