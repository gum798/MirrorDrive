package com.example.mirrordrive

import org.junit.Assert.assertEquals
import org.junit.Test

class AirplayTxtTest {
    private val pk = "a".repeat(64)

    @Test fun airplayTxt_hasExactIdentityValues() {
        val txt = airplayTxt(deviceId = "aa:bb:cc:dd:ee:ff", pkHex = pk)
        assertEquals("0x5A7FFEE6,0x0", txt["features"])
        assertEquals("0x4", txt["flags"])
        assertEquals("AppleTV3,2", txt["model"])
        assertEquals("220.68", txt["srcvers"])
        assertEquals("2e388006-13ba-4041-9a67-25dd4a43d536", txt["pi"])
        assertEquals("aa:bb:cc:dd:ee:ff", txt["deviceid"])  // lowercase colon MAC
        assertEquals(pk, txt["pk"])
    }

    @Test fun raopInstanceName_isUppercaseNoColonAtName() {
        assertEquals("AABBCCDDEEFF@MirrorDrive",
            raopInstanceName(deviceId = "aa:bb:cc:dd:ee:ff", friendly = "MirrorDrive"))
    }

    @Test fun raopTxt_usesFtKeyAndSameFeatures() {
        val txt = raopTxt(pkHex = pk)
        assertEquals("0x5A7FFEE6,0x0", txt["ft"])   // note: key is 'ft' for raop
        assertEquals("44100", txt["sr"])
        assertEquals("0,1,2,3", txt["cn"])
        assertEquals(pk, txt["pk"])
    }
}
