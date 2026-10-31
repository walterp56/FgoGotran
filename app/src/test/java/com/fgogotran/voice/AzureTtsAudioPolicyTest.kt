package com.fgogotran.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AzureTtsAudioPolicyTest {
    // Sizes from the captured Azure HTTP-200 Yunyi failure and identical successful retry.
    private val partial = ByteArray(17_280)
    private val complete = ByteArray(66_240)
    private val line = "各位，請確認通訊狀態。我們準備出發。"

    @Test fun `observed 864 ms partial clip fails but the 3312 ms full retry passes`() {
        assertNotNull(AzureTtsAudioPolicy.suspiciousReason(partial, line, "+5%"))
        assertNull(AzureTtsAudioPolicy.suspiciousReason(complete, line, "+5%"))
    }

    @Test fun `short names exclamations and creature sounds remain valid`() {
        for (text in listOf("各位。", "好！", "芙，芙嗚！", "Senpai!", "Yes.", "12345",
            "啊啊啊啊啊啊啊啊啊啊啊啊啊啊！", "Ha ha ha ha ha ha ha ha ha ha ha ha!")) {
            assertNull(AzureTtsAudioPolicy.suspiciousReason(ByteArray(6_000), text, "+0%"), text)
        }
        assertNotNull(AzureTtsAudioPolicy.suspiciousReason(byteArrayOf(), "好", "1.0"))
    }

    @Test fun `faster user speech lowers the floor without making slow speech stricter`() {
        assertNull(AzureTtsAudioPolicy.suspiciousReason(partial, line, "+100%"))
        assertNull(AzureTtsAudioPolicy.suspiciousReason(partial, line, "2.0"))
        assertNull(AzureTtsAudioPolicy.suspiciousReason(partial, line, "x-fast"))
        assertNotNull(AzureTtsAudioPolicy.suspiciousReason(partial, line, "-50%"))
        assertNull(AzureTtsAudioPolicy.suspiciousReason(ByteArray(20_000), line, "-50%"))
        assertNotNull(AzureTtsAudioPolicy.suspiciousReason(partial, line, "NaN"))
    }

    @Test fun `long English and Japanese lines receive the same gross truncation guard`() {
        assertNotNull(AzureTtsAudioPolicy.suspiciousReason(partial,
            "Please check the communication equipment before we set out together this morning.", "+0%"))
        assertNotNull(AzureTtsAudioPolicy.suspiciousReason(partial,
            "みなさん、つうしんじょうたいをかくにんしてください。", "+0%"))
        assertNull(AzureTtsAudioPolicy.suspiciousReason(complete, line, "+0%"))
    }

    @Test fun `normal audio uses one provider request`() = runBlocking {
        var calls = 0
        val result = AzureTtsAudioPolicy.synthesize(line, "1.05") { calls++; complete }
        assertSame(complete, result)
        assertEquals(1, calls)
    }

    @Test fun `suspicious audio is discarded and the same request is retried only once`() = runBlocking {
        var calls = 0
        val result = AzureTtsAudioPolicy.synthesize(line, "1.05") { if (++calls == 1) partial else complete }
        assertSame(complete, result)
        assertEquals(2, calls)
    }

    @Test fun `two suspicious responses never reach the caller cache write`() = runBlocking {
        var calls = 0
        var writes = 0
        assertFailsWith<IllegalStateException> {
            AzureTtsAudioPolicy.synthesize(line, "1.05") { calls++; partial }.also { writes++ }
        }
        assertEquals(2, calls)
        assertEquals(0, writes)
    }

    @Test fun `HTTP failure is not turned into an audio retry`() = runBlocking {
        var calls = 0
        val error = IllegalStateException("Azure TTS failed: HTTP 401")
        assertSame(error, assertFailsWith<IllegalStateException> {
            AzureTtsAudioPolicy.synthesize(line, "1.05") { calls++; throw error }
        })
        assertEquals(1, calls)
    }

    @Test fun `cancelled scene cannot start a second synthesis`() = runBlocking {
        var calls = 0
        val child = async {
            AzureTtsAudioPolicy.synthesize(line, "1.05") {
                calls++
                currentCoroutineContext().cancel()
                partial
            }
        }
        assertFailsWith<CancellationException> { child.await() }
        assertEquals(1, calls)
    }

    @Test fun `pre validation cache keys cannot reuse old partial clips`() {
        val profile = VoiceProfile("test", "azure", "zh-CN", "zh-CN-YunzeNeural", "", "0%", "1.00", "100", "mature_male")
        val request = VoiceSynthesisRequest("A", line, profile, azureSpeechRegion = "southeastasia")
        val oldKey = "|southeastasia|100|test|zh-CN-YunzeNeural|||0%|1.00|||||A|$line"
        assertNotEquals(oldKey, request.cacheMaterial())
        assertTrue(request.cacheMaterial().startsWith(AzureTtsAudioPolicy.CACHE_VERSION + "|"))
        assertEquals(oldKey, request.cacheMaterial().removePrefix(AzureTtsAudioPolicy.CACHE_VERSION + "|"))
    }
}
