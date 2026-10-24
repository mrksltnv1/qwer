package com.qwer.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quick exclusions must hit the apps users actually need outside the tunnel, and must
 * NOT sweep in unrelated packages. A false positive here silently removes an app from
 * the VPN — the hardest kind of bug for a user to diagnose.
 */
class QuickExclusionsTest {

    @Test
    fun `exact package matches`() {
        assertTrue(QuickExclusions.matches("Сбербанк", "ru.sberbankmobile"))
        assertTrue(QuickExclusions.matches("Госуслуги", "ru.rostel"))
        assertTrue(QuickExclusions.matches("Ozon", "ru.ozon.app.android"))
    }

    @Test
    fun `keyword matches localised label`() {
        // A re-signed build with a different package but the same name.
        assertTrue(QuickExclusions.matches("Тинькофф", "com.example.bank"))
        assertTrue(QuickExclusions.matches("Яндекс Карты", "com.example.maps"))
    }

    @Test
    fun `keyword matches package name`() {
        assertTrue(QuickExclusions.matches("Bank", "ru.sberbank.online"))
    }

    @Test
    fun `unrelated app is not matched`() {
        assertFalse(QuickExclusions.matches("Мой VPN", "com.qwer.app"))
        assertFalse(QuickExclusions.matches("Камера", "com.android.camera"))
        assertFalse(QuickExclusions.matches("Телеграм", "org.telegram.messenger"))
    }

    @Test
    fun `short keywords need word boundaries`() {
        // "mir" inside "mirror", "mts" inside "admtsx" must not match.
        assertFalse(QuickExclusions.matches("Mirror Pro", "com.example.mirrorapp"))
        assertFalse(QuickExclusions.matches("Nothing", "com.admtsx.tool"))
    }

    @Test
    fun `short keyword still matches as a whole word`() {
        assertTrue(QuickExclusions.matches("МТС", "ru.mts"))
        assertTrue(QuickExclusions.matches("MIR Pay", "ru.nspk.mirpay"))
    }

    @Test
    fun `filter keeps order and drops non matches`() {
        val candidates = listOf(
            "Сбербанк" to "ru.sberbankmobile",
            "Камера" to "com.android.camera",
            "Ozon" to "ru.ozon.app.android",
        )
        val out = QuickExclusions.filter(candidates)
        assertTrue(out.size == 2)
        assertTrue(out[0].second == "ru.sberbankmobile")
        assertTrue(out[1].second == "ru.ozon.app.android")
    }

    @Test
    fun `filter on empty list is empty`() {
        assertTrue(QuickExclusions.filter(emptyList()).isEmpty())
    }

    @Test
    fun `case insensitive`() {
        assertTrue(QuickExclusions.matches("SBERBANK", "RU.SBERBANKMOBILE"))
    }
}
