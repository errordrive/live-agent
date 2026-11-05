package com.nayem.liveagent.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure parsing helpers behind GeminiRestClient.testKeyDetailed.
 * No MockWebServer is in the dependency catalog, so the network call itself is not
 * tested here — only the parsing/decision logic it relies on, plus the RMS helper
 * behind the talking-animation amplitude callbacks.
 *
 * NOTE: these tests need `testImplementation(libs.junit)` in app/build.gradle.kts
 * (JUnit 4.13.2 is already in gradle/libs.versions.toml; it is not yet wired in).
 */
class GeminiRestClientTest {

    private val listJson = """
        {
          "models": [
            {"name": "models/gemini-3.8-live", "displayName": "Gemini 3.8 Live"},
            {"name": "models/gemini-3.8-flash", "displayName": "Gemini 3.8 Flash"},
            {"name": "models/gemini-2.5-flash-live", "displayName": "Gemini 2.5 Flash Live"},
            {"name": "models/gemini-2.0-flash", "displayName": "LIVE Voice Preview"},
            {"name": "models/gemini-2.0-flash-lite", "displayName": "Gemini 2.0 Flash-Lite"}
          ],
          "nextPageToken": "page-2-token"
        }
    """.trimIndent()

    @Test
    fun parseModelsList_countsAllAndFindsLiveCaseInsensitively() {
        val (total, live) = parseModelsList(listJson)
        assertEquals(5, total)
        // Live via name (x2) + Live via displayName only ("LIVE Voice Preview").
        assertEquals(3, live.size)
        assertTrue(live.contains("gemini-3.8-live"))
        assertTrue(live.contains("gemini-2.5-flash-live"))
        assertTrue(live.contains("gemini-2.0-flash"))
        assertFalse(live.contains("gemini-3.8-flash"))
        assertFalse(live.contains("gemini-2.0-flash-lite"))
    }

    @Test
    fun parseModelsList_stripsModelsPrefix() {
        val (total, live) = parseModelsList(
            """{"models": [{"name": "models/gemini-3.8-live", "displayName": "Gemini 3.8 Live"}]}"""
        )
        assertEquals(1, total)
        assertEquals(listOf("gemini-3.8-live"), live)
    }

    @Test
    fun parseModelsList_emptyMissingOrMalformedYieldsZero() {
        assertEquals(0 to emptyList<String>(), parseModelsList("{}"))
        assertEquals(0 to emptyList<String>(), parseModelsList("""{"models": []}"""))
        assertEquals(0 to emptyList<String>(), parseModelsList("not json at all"))
        assertEquals(0 to emptyList<String>(), parseModelsList(""))
    }

    @Test
    fun parseNextPageToken_presentAbsentAndBlank() {
        assertEquals("page-2-token", parseNextPageToken(listJson))
        assertNull(parseNextPageToken("""{"models": []}"""))
        assertNull(parseNextPageToken("""{"nextPageToken": "  "}"""))
        assertNull(parseNextPageToken("garbage"))
    }

    @Test
    fun parseErrorMessage_extractsRealServerMessage() {
        val body = """{"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.", "status": "INVALID_ARGUMENT"}}"""
        assertEquals(
            "API key not valid. Please pass a valid API key.",
            parseErrorMessage(body, "HTTP 400")
        )
    }

    @Test
    fun parseErrorMessage_fallsBackWhenNoMessage() {
        assertEquals("HTTP 403", parseErrorMessage("""{"error": {}}""", "HTTP 403"))
        assertEquals("HTTP 500", parseErrorMessage("not json", "HTTP 500"))
        assertEquals("HTTP 500", parseErrorMessage("", "HTTP 500"))
    }

    @Test
    fun keyTestMessage_exactStrings() {
        assertEquals(
            "Key OK — Live ready (gemini-3.8-live available)",
            keyTestMessage(totalModels = 42, liveReady = true)
        )
        assertEquals(
            "Key OK — 42 models available, Live not listed",
            keyTestMessage(totalModels = 42, liveReady = false)
        )
        assertEquals(
            "Key OK — 1 models available, Live not listed",
            keyTestMessage(totalModels = 1, liveReady = false)
        )
    }

    @Test
    fun isLiveModel_matchesNameOrDisplayNameCaseInsensitively() {
        assertTrue(isLiveModel("models/gemini-3.8-live", "Gemini 3.8 Live"))
        assertTrue(isLiveModel("models/gemini-2.0-flash", "LIVE preview"))
        assertTrue(isLiveModel("models/gemini-2.0-flash-Live", "Gemini"))
        assertFalse(isLiveModel("models/gemini-3.8-flash", "Gemini 3.8 Flash"))
        assertFalse(isLiveModel("", ""))
    }

    @Test
    fun keyTestResult_carriesDetailedFields() {
        val result = KeyTestResult(
            totalModels = 5,
            liveModels = listOf("gemini-3.8-live"),
            liveReady = true,
            message = keyTestMessage(5, true)
        )
        assertEquals(5, result.totalModels)
        assertEquals(listOf("gemini-3.8-live"), result.liveModels)
        assertTrue(result.liveReady)
        assertEquals("Key OK — Live ready (gemini-3.8-live available)", result.message)
    }

    @Test
    fun rmsAmplitude_silenceIsZero() {
        assertEquals(0f, rmsAmplitude(ByteArray(3200)), 0f)
        assertEquals(0f, rmsAmplitude(ByteArray(0)), 0f)
        assertEquals(0f, rmsAmplitude(byteArrayOf(0x01)), 0f)
    }

    @Test
    fun rmsAmplitude_fullScaleIsNearOne() {
        // 0x7FFF repeated = max positive PCM16 sample.
        val pcm = ByteArray(200) { i -> if (i % 2 == 0) 0xFF.toByte() else 0x7F.toByte() }
        assertEquals(1f, rmsAmplitude(pcm), 0.001f)
    }

    @Test
    fun rmsAmplitude_halfScaleIsNearHalf() {
        // 0x4000 = 16384 = exactly half of full scale.
        val pcm = ByteArray(200) { i -> if (i % 2 == 0) 0x00.toByte() else 0x40.toByte() }
        assertEquals(0.5f, rmsAmplitude(pcm), 0.001f)
    }
}
