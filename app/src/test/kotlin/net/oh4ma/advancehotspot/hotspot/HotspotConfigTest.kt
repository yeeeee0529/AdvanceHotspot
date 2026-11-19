package net.oh4ma.advancehotspot.hotspot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotConfigTest {
    private val capability = HotspotCapability(
        maxSupportedClients = 32,
        channels = mapOf(
            Band.BAND_2GHZ to listOf(1, 6, 11),
            Band.BAND_5GHZ to listOf(36, 149),
            Band.BAND_6GHZ to listOf(5, 37),
        ),
    )
    private val valid = HotspotConfig(
        ssid = "test-ap",
        passphrase = "12345678",
        securityType = SecurityType.WPA2,
        band = Band.BAND_5GHZ,
        channel = 149,
        hidden = false,
        maxClients = 8,
        autoShutdown = true,
        clientIsolation = false,
    )

    @Test
    fun acceptsValidConfig() {
        assertNull(valid.validate(capability))
        assertNull(valid.copy(band = Band.AUTO_2GHZ_5GHZ, channel = 0, maxClients = 0).validate(capability))
        assertNull(valid.copy(securityType = SecurityType.OPEN, passphrase = "").validate(capability))
    }

    @Test
    fun measuresSsidLengthInUtf8Bytes() {
        assertEquals(ConfigError.SSID_EMPTY, valid.copy(ssid = "").validate(capability))
        // 每個中文字佔 3 位元組，11 個字即超過 32 位元組上限
        assertEquals(ConfigError.SSID_TOO_LONG, valid.copy(ssid = "熱".repeat(11)).validate(capability))
        assertNull(valid.copy(ssid = "熱".repeat(10)).validate(capability))
    }

    @Test
    fun rejectsInvalidPassphrase() {
        assertEquals(ConfigError.PASSPHRASE_LENGTH, valid.copy(passphrase = "1234567").validate(capability))
        assertEquals(
            ConfigError.PASSPHRASE_LENGTH,
            valid.copy(securityType = SecurityType.WPA3, passphrase = "").validate(capability),
        )
        assertNull(valid.copy(securityType = SecurityType.WPA3, passphrase = "short").validate(capability))
    }

    @Test
    fun rejectsUnsupportedBandAndChannelCombinations() {
        assertEquals(
            ConfigError.SIX_GHZ_REQUIRES_WPA3,
            valid.copy(band = Band.BAND_6GHZ, channel = 37).validate(capability),
        )
        assertEquals(ConfigError.CHANNEL_UNSUPPORTED, valid.copy(channel = 100).validate(capability))
        assertEquals(
            ConfigError.CHANNEL_UNSUPPORTED,
            valid.copy(band = Band.AUTO_2GHZ_5GHZ, channel = 6).validate(capability),
        )
    }

    @Test
    fun rejectsClientLimitAboveCapability() {
        assertEquals(ConfigError.MAX_CLIENTS_RANGE, valid.copy(maxClients = 33).validate(capability))
        assertEquals(ConfigError.MAX_CLIENTS_RANGE, valid.copy(maxClients = -1).validate(capability))
    }

    @Test
    fun convertsFrequencyToChannel() {
        assertEquals(1, frequencyToChannel(2412))
        assertEquals(11, frequencyToChannel(2462))
        assertEquals(14, frequencyToChannel(2484))
        assertEquals(36, frequencyToChannel(5180))
        assertEquals(149, frequencyToChannel(5745))
        assertEquals(37, frequencyToChannel(6135))
        assertNull(frequencyToChannel(900))
    }
}
