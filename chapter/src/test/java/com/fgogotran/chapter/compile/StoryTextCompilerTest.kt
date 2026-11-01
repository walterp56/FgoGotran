package com.fgogotran.chapter.compile

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.packs.*
import java.util.Base64
import java.util.concurrent.CancellationException
import kotlin.test.*

class StoryTextCompilerTest {
    private val fixture = SyntheticStoryAssets()
    private val rich = "Hello [#star:reading][r][%1] [&彼:彼女]!"
    private val text = "＄synthetic\r\n[charaSet A 100 0 Narrator]\r\n[unknown keep 7]\r\n＠A：Speaker\r\n$rich\r\n[k]\r\n？1：First[%2]\r\n＠B：Other\r\nUnchanged [line 3] text.\r\n[k]\r\n？2：Second\r\n？！\r\n[end]\r\n"
    private fun source(raw: String = text, compression: TextCompression = TextCompression.BZIP2) =
        fixture.source(fixture.asset(mapOf("0100000011" to fixture.encoded(raw, compression))))
    private fun compile(source: NativeScriptSource, changes: List<StoryTextChange>, check: () -> Unit = {}) =
        StoryTextCompiler(fixture.scripts).compile(source, StoryScriptPatch("0100000011", changes),
            fixture.scripts.decodedBytes(source).valueOrThrow().sha256.hex, check)
    private fun plain(source: NativeScriptSource, payload: AssetBytes) = fixture.plain(NativeScriptSource(source.binding, source.originalAsset, payload))

    @Test fun `batch edits preserve original offsets commands prefixes choice numbers and CRLF`() {
        val original = source()
        val changes = listOf(
            fixture.change(original, "First[%2]", StoryTextToken.Text("A longer choice "), StoryTextToken.Placeholder("%2")),
            fixture.change(original, rich, StoryTextToken.Ruby("觀星臺", "迦勒底"), StoryTextToken.LineBreak,
                StoryTextToken.Placeholder("%1"), StoryTextToken.Text(" "), StoryTextToken.Conditional("他", "她"), StoryTextToken.Text("！")),
            fixture.change(original, "A：Speaker", StoryTextToken.Text("Localized")),
            fixture.change(original, "Narrator", StoryTextToken.Text("旁白"))
        )
        val output = compile(original, changes).valueOrThrow()
        val expected = text.replace("First[%2]", "A longer choice [%2]").replace(rich, "[#觀星臺:迦勒底][r][%1] [&他:她]！")
            .replace("A：Speaker", "A：Localized").replace("Narrator", "旁白")
        assertEquals(expected, plain(original, output))
        assertEquals(fixture.encoded(text), original.payload)
    }

    @Test fun `both compression modes and outer BOM survive compilation`() {
        TextCompression.entries.forEach { compression ->
            val raw = source(compression = compression)
            val original = NativeScriptSource(raw.binding, raw.originalAsset, AssetBytes.copyOf("\uFEFF".toByteArray() + raw.payload.toByteArray()))
            val output = compile(original, listOf(fixture.change(original, "A：Speaker", StoryTextToken.Text("New")))).valueOrThrow()
            assertTrue(output.toByteArray().toString(Charsets.UTF_8).startsWith('\uFEFF'))
            val cipher = Base64.getDecoder().decode(output.toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF"))
            assertEquals(compression, unpackText(fixture.envelope.decrypt(cipher, 4 * 1024 * 1024), 4 * 1024 * 1024).first)
        }
    }

    @Test fun `pure ruby placeholder and conditional messages remain editable text fields`() {
        val raw = "＄synthetic\n＠Speaker\n[#base:reading]\n[k]\n＠Speaker\n[%1]\n[k]\n＠Speaker\n[&first:second]\n[k]\n"
        val original = source(raw)
        val changes = listOf(
            fixture.change(original, "[#base:reading]", StoryTextToken.Ruby("臺", "台")),
            fixture.change(original, "[%1]", StoryTextToken.Text("Hello "), StoryTextToken.Placeholder("%1")),
            fixture.change(original, "[&first:second]", StoryTextToken.Conditional("一", "二"))
        )
        assertEquals(raw.replace("[#base:reading]", "[#臺:台]").replace("\n[%1]\n", "\nHello [%1]\n").replace("[&first:second]", "[&一:二]"),
            plain(original, compile(original, changes).valueOrThrow()))
    }

    @Test fun `unknown edited inline commands are unsupported but untouched fields remain exact`() {
        val original = source()
        assertTrue(compile(original, listOf(fixture.change(original, "Unchanged [line 3] text.", StoryTextToken.Text("Translated")))) is NativeCodecResult.Success<*>)
        val changed = compile(original, listOf(fixture.change(original, "Second", StoryTextToken.Text("二")))).valueOrThrow()
        assertEquals(text.replace("？2：Second", "？2：二"), plain(original, changed))
        val nested = source("＄synthetic\n＠Speaker\nNested [#base:[&first:second]] text.\n[k]\n")
        val field = fixture.scripts.decode(nested).valueOrThrow().fields.single { it.kind == NativeTextKind.DIALOGUE }
        assertIs<NativeCodecResult.Unsupported>(compile(nested, listOf(fixture.change(nested, field.sourceText, StoryTextToken.Text("x")))))
    }

    @Test fun `placeholders and native conditional positions cannot be dropped duplicated or reordered`() {
        val original = source()
        listOf(
            listOf(StoryTextToken.Text("Dropped")),
            listOf(StoryTextToken.Placeholder("%2"), StoryTextToken.Conditional("a", "b")),
            listOf(StoryTextToken.Placeholder("%1"), StoryTextToken.Placeholder("%1"), StoryTextToken.Conditional("a", "b")),
            listOf(StoryTextToken.Conditional("a", "b"), StoryTextToken.Placeholder("%1")),
            listOf(StoryTextToken.Placeholder("%1")),
            listOf(StoryTextToken.Placeholder("%1"), StoryTextToken.Placeholder("&first:second"))
        ).forEach { tokens -> assertIs<NativeCodecResult.Invalid>(compile(original, listOf(fixture.change(original, rich, *tokens.toTypedArray())))) }
    }

    @Test fun `line marker injection argument splitting wrong hashes kinds and fields fail closed`() {
        val original = source()
        listOf("＠Other", "  ＠Other", "＄new", "？1：new", "？！", "//comment").forEach { marker ->
            val field = fixture.field(original, rich)
            val change = StoryTextChange(field.id.value, StoryFieldKind.DIALOGUE, Sha256Digest.compute(rich.toByteArray()).hex,
                listOf(StoryTextToken.Text(marker), StoryTextToken.Placeholder("%1"), StoryTextToken.Conditional("a", "b")))
            assertIs<NativeCodecResult.Invalid>(compile(original, listOf(change)))
        }
        assertIs<NativeCodecResult.Invalid>(compile(original, listOf(fixture.change(original, "Narrator", StoryTextToken.Text("Two words")))))
        val noAlias = source("＄synthetic\n＠Speaker\nPlain dialogue\n[k]\n")
        assertIs<NativeCodecResult.Invalid>(compile(noAlias, listOf(fixture.change(noAlias, "Speaker", StoryTextToken.Text("A：Not just a name")))))
        val field = fixture.field(original, "Second")
        assertIs<NativeCodecResult.Invalid>(compile(original, listOf(StoryTextChange(field.id.value, StoryFieldKind.CHOICE, "0".repeat(64), listOf(StoryTextToken.Text("x"))))))
        assertIs<NativeCodecResult.Invalid>(compile(original, listOf(StoryTextChange("dialogue:999999", StoryFieldKind.DIALOGUE, "0".repeat(64), listOf(StoryTextToken.Text("x"))))))
        assertIs<NativeCodecResult.Invalid>(StoryTextCompiler(fixture.scripts).compile(original, StoryScriptPatch("0100000011", listOf(fixture.change(original, "Second", StoryTextToken.Text("x")))), "0".repeat(64)))
    }

    @Test fun `identical edits retain exact original compressed bytes and cancellation is propagated`() {
        val original = source()
        assertEquals(original.payload, compile(original, listOf(fixture.change(original, "Second", StoryTextToken.Text("Second")))).valueOrThrow())
        var checks = 0
        assertFailsWith<CancellationException> {
            compile(original, listOf(fixture.change(original, "Second", StoryTextToken.Text("x")))) {
                if (++checks == 3) throw CancellationException("Synthetic cancellation")
            }
        }
        assertEquals(text, fixture.plain(original))
    }
}
