package com.fgogotran.chapter.compile

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import java.util.concurrent.CancellationException
import kotlin.test.*

class NativeBatchCodecTest {
    private val fixture = SyntheticStoryAssets()
    private val text = "＄synthetic\n＠Speaker\nSynthetic dialogue\n[k]\n"

    @Test fun `serialized batch growth shrink and no-op preserve all other payloads and opaque tail`() {
        val original = fixture.serialized(linkedMapOf("first" to AssetBytes.copyOf(ByteArray(80) { 1 }),
            "second" to AssetBytes.copyOf(ByteArray(100) { 2 }), "untouched" to AssetBytes.copyOf(byteArrayOf(8, 9, 10))))
        val before = UnityTextAssets.decode(original)
        val first = before.texts.single { it.name == "first" }; val second = before.texts.single { it.name == "second" }
        val grow = ByteArray(131) { 7 }; val shrink = byteArrayOf(5)
        val changed = before.replaceTexts(linkedMapOf(second.pathId to (second.payload to shrink), first.pathId to (first.payload to grow)))
        val after = UnityTextAssets.decode(changed)
        assertEquals(before.texts.map { it.pathId to it.name }, after.texts.map { it.pathId to it.name })
        assertContentEquals(grow, after.texts.single { it.name == "first" }.payload)
        assertContentEquals(shrink, after.texts.single { it.name == "second" }.payload)
        assertContentEquals(before.texts.single { it.name == "untouched" }.payload, after.texts.single { it.name == "untouched" }.payload)
        assertContentEquals(original.takeLast(4).toByteArray(), changed.takeLast(4).toByteArray())
        assertContentEquals(original, before.replaceTexts(before.texts.associate { it.pathId to (it.payload to it.payload) }))
        assertFails { before.replaceTexts(mapOf(first.pathId to (byteArrayOf(9) to grow))) }
        assertFails { before.replaceTexts(mapOf(9999L to (byteArrayOf() to grow))) }
        assertFails { before.replaceTexts(mapOf(42L to (byteArrayOf(4, 9, 3, 7) to grow))) }
    }

    @Test fun `multiple Unity nodes are reconstructed together with untouched node bytes and gaps retained`() {
        val nodes = listOf(fixture.serialized(mapOf("first" to fixture.encoded(text))),
            fixture.serialized(mapOf("second" to fixture.encoded(text))), fixture.serialized(mapOf("third" to fixture.encoded(text))))
        val original = UnityFsArchive.decode(fixture.unity(nodes))
        val first = UnityTextAssets.decode(nodes[0]).texts.single()
        val second = UnityTextAssets.decode(nodes[1]).texts.single()
        val updated0 = UnityTextAssets.decode(nodes[0]).replaceText(first.pathId, first.payload, ByteArray(11) { 3 })
        val updated1 = UnityTextAssets.decode(nodes[1]).replaceText(second.pathId, second.payload, ByteArray(611) { 4 })
        val after = UnityFsArchive.decode(original.replaceNodes(mapOf(1 to updated1, 0 to updated0)))
        assertEquals(original.nodes.map { it.name to it.flags }, after.nodes.map { it.name to it.flags })
        listOf(updated0, updated1, nodes[2]).forEachIndexed { index, expected ->
            val node = after.nodes[index]; assertContentEquals(expected, after.data.copyOfRange(node.offset, node.offset + node.size))
        }
        for (index in 1 until original.nodes.size) {
            val a = original.nodes[index - 1]; val b = original.nodes[index]
            val c = after.nodes[index - 1]; val d = after.nodes[index]
            assertContentEquals(original.data.copyOfRange(a.offset + a.size, b.offset), after.data.copyOfRange(c.offset + c.size, d.offset))
        }
    }

    @Test fun `container batch validates every original binding and supports multiple serialized nodes`() {
        val original = fixture.asset(listOf(mapOf("0100000011" to fixture.encoded(text)), mapOf("0100000010" to fixture.encoded(text))))
        val before = fixture.containers.decode(original).valueOrThrow()
        val changes = before.scripts.map { source ->
            FgoJpContainerCodec.ScriptReplacement(source, fixture.encoded(text.replace("Synthetic dialogue", source.binding.scriptId.value)))
        }
        val output = fixture.containers.replaceScripts(original, changes).valueOrThrow()
        val after = fixture.containers.decode(OriginalAsset.capture(original.fingerprint.path, output.toByteArray())).valueOrThrow()
        assertEquals(before.scripts.map { it.binding }, after.scripts.map { it.binding })
        after.scripts.forEach { assertEquals(text.replace("Synthetic dialogue", it.binding.scriptId.value), fixture.plain(it)) }
        assertIs<NativeCodecResult.Invalid>(fixture.containers.replaceScripts(original, changes + changes.first()))
        val source = changes.first().source
        val forged = NativeScriptSource(source.binding, source.originalAsset, fixture.encoded(text.replace("dialogue", "forged")))
        assertIs<NativeCodecResult.Invalid>(fixture.containers.replaceScripts(original, listOf(FgoJpContainerCodec.ScriptReplacement(forged, source.payload))))
        assertFailsWith<CancellationException> { fixture.containers.replaceScripts(original, changes) { throw CancellationException("Synthetic") } }
        assertEquals(before.scripts.map { it.payload }, fixture.containers.decode(original).valueOrThrow().scripts.map { it.payload })
    }
}
