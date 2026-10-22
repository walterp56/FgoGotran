package com.fgogotran.chapter.install

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import java.io.File
import java.util.Base64
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** Synthetic encrypted indexes and JVM files only; no Android/native-playback claim. */
class ChapterManifestRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val envelope = RijndaelEnvelope(AssetBytes.copyOf(ByteArray(32) { it.toByte() }),
        AssetBytes.copyOf(ByteArray(32) { (it + 32).toByte() }))
    private val codec = FgoInstalledManifestCodec(envelope)
    private val bundle = ByteArray(90) { it.toByte() }
    private val replacement = ByteArray(110) { (it + 10).toByte() }
    private val version = "@FgoDataVersion0066,synthetic,baseline"
    private val originalRow = "1,opaque,${bundle.size},${crc32(bundle)},${ChapterInstallPolicy.GROUP}"
    private val preparedRow = "1,opaque,${replacement.size},${crc32(replacement)},${ChapterInstallPolicy.GROUP}"
    private val unrelated = "0,unrelated,4,5,Other/Fake"
    private fun index(row: String, other: String = unrelated, header: String = version, timestamp: Int = 1): ByteArray {
        val body = "$header\n$row\n$other\n"
        val plain = "~${crc32(body.toByteArray())}\n$body".toByteArray()
        val gzip = packText(plain, TextCompression.GZIP)
        repeat(4) { gzip[4 + it] = (timestamp ushr (it * 8)).toByte() }
        return ("\uFEFF" + Base64.getEncoder().encodeToString(envelope.encrypt(gzip))).toByteArray()
    }
    private val original get() = index(originalRow)
    private val prepared get() = index(preparedRow)
    private fun record() = InstallSamples.record().copy(originalBundle = InstallFileHash.of(bundle),
        originalManifest = InstallFileHash.of(original), replacementBundle = InstallFileHash.of(replacement),
        replacementManifest = InstallFileHash.of(prepared))
    private fun policy(record: ChapterInstallRecord = record()) = ChapterManifestRecovery(record, original, prepared, codec)
    private fun validate(bundle: ByteArray, index: ByteArray) {
        codec.verifyAsset(asset(index), OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.BUNDLE), bundle)).valueOrThrow()
    }
    private fun asset(index: ByteArray) = OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.MANIFEST), index)
    private fun text(index: ByteArray) = codec.diagnosticLayers(asset(index)).valueOrThrow().decodedText
    private lateinit var root: File
    private lateinit var files: TestChapterInstallFiles
    private fun engine(record: ChapterInstallRecord = record(), adapter: ChapterInstallFiles? = null): ChapterTransactionEngine {
        if (adapter == null) {
            root = temporary.newFolder()
            files = TestChapterInstallFiles(root).also { it.putOriginals(bundle, original) }
        }
        return ChapterTransactionEngine(adapter ?: files, { ChapterGameStatus(record.game, true) }, ::validate, policy(record))
    }
    private fun install(engine: ChapterTransactionEngine, record: ChapterInstallRecord = record()) {
        engine.stage(record, replacement, prepared)
        engine.commit(record.copy(state = ChapterInstallState.COMMITTING))
    }
    private fun rewrite(index: ByteArray) {
        File(root, ChapterInstallPolicy.MANIFEST).writeBytes(index)
        val old = files.identity(ChapterInstallPolicy.MANIFEST)
        files.accessOverrides[ChapterInstallPolicy.MANIFEST] = old.copy(inode = old.inode + 1)
    }
    private fun restore(engine: ChapterTransactionEngine) = engine.restore(record().copy(state = ChapterInstallState.RESTORING), bundle, original)

    @Test fun `references must match both journal hashes and change only target size and CRC`() {
        assertFails { ChapterManifestRecovery(record(), original, byteArrayOf(1), codec) }
        val extra = index(preparedRow, "1,changed,4,5,Other/Fake")
        assertFails { ChapterManifestRecovery(record().copy(replacementManifest = InstallFileHash.of(extra)), original, extra, codec) }
        val opaque = index(preparedRow.replace("opaque", "other"))
        assertFails { ChapterManifestRecovery(record().copy(replacementManifest = InstallFileHash.of(opaque)), original, opaque, codec) }
    }
    @Test fun `equivalent encoding is recognised while unknown row version and corrupt indexes are rejected`() {
        assertEquals(0, policy().state(index(originalRow, timestamp = 2)))
        assertEquals(1, policy().state(index(preparedRow, timestamp = 2)))
        for (bad in listOf(index(preparedRow.replace("opaque", "other")), index(preparedRow.replace("1,opaque", "0,opaque")),
            index(preparedRow, header = "$version-new"), index(preparedRow, other = preparedRow), byteArrayOf(0))) {
            assertNull(policy().state(bad))
        }
    }
    @Test fun `completed install permits game resaved inode with known content then restores original bytes`() {
        val engine = engine(); install(engine); rewrite(index(preparedRow, timestamp = 3))
        assertEquals(ChapterPairState.REPLACEMENT, engine.inspect(record().copy(state = ChapterInstallState.INSTALLED)))
        restore(engine)
        assertContentEquals(bundle, files.read(ChapterInstallPolicy.BUNDLE))
        assertContentEquals(original, files.read(ChapterInstallPolicy.MANIFEST))
    }
    @Test fun `restoration preserves unrelated installed rows and new rows in current index`() {
        val engine = engine(); install(engine)
        val other = "1,unrelated,4,5,Other/Fake\n1,new-token,7,9,Other/New"
        rewrite(index(preparedRow, other, timestamp = 8))
        restore(engine)
        val restored = files.read(ChapterInstallPolicy.MANIFEST)
        assertNotEquals(InstallFileHash.of(original), InstallFileHash.of(restored))
        assertEquals(text(index(originalRow, other)), text(restored))
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record().copy(state = ChapterInstallState.RESTORING)))
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record().copy(state = ChapterInstallState.RESTORED)))
    }
    @Test fun `game returned original bundle is not mistaken for rebuilt playback`() {
        val engine = engine(); install(engine)
        File(root, ChapterInstallPolicy.BUNDLE).writeBytes(bundle)
        rewrite(index(originalRow, timestamp = 4))
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record().copy(state = ChapterInstallState.INSTALLED)))
        restore(engine)
        assertContentEquals(original, files.read(ChapterInstallPolicy.MANIFEST))
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record().copy(state = ChapterInstallState.RESTORING)))
    }
    @Test fun `already original target keeps newer unrelated rows without rewriting the index`() {
        val engine = engine(); install(engine)
        File(root, ChapterInstallPolicy.BUNDLE).writeBytes(bundle)
        rewrite(index(originalRow, "1,unrelated,4,5,Other/Fake", timestamp = 4))
        val saved = files.read(ChapterInstallPolicy.MANIFEST); val writes = files.replacements
        restore(engine)
        assertEquals(writes, files.replacements)
        assertContentEquals(saved, files.read(ChapterInstallPolicy.MANIFEST))
        assertEquals(ChapterPairState.ORIGINAL, engine.inspect(record().copy(state = ChapterInstallState.RESTORING)))
    }
    @Test fun `unknown bundle changed target row or changed version blocks restore without live writes`() {
        for (change in listOf("bundle", "row", "version")) {
            val engine = engine(); install(engine)
            when (change) {
                "bundle" -> File(root, ChapterInstallPolicy.BUNDLE).writeText("unknown bundle")
                "row" -> rewrite(index(preparedRow.replace("opaque", "wrong")))
                "version" -> rewrite(index(preparedRow, header = "$version-new"))
            }
            assertEquals(ChapterPairState.UNKNOWN, engine.inspect(record().copy(state = ChapterInstallState.INSTALLED)))
            val writes = files.replacements
            assertFails { restore(engine) }; assertEquals(writes, files.replacements)
        }
    }
    @Test fun `incomplete write cannot use completed lifecycle identity allowance`() {
        val engine = engine(); engine.stage(record(), replacement, prepared); files.failDuringWrite = 2
        assertFails { engine.commit(record().copy(state = ChapterInstallState.COMMITTING)) }
        val old = files.identity(ChapterInstallPolicy.MANIFEST)
        files.accessOverrides[ChapterInstallPolicy.MANIFEST] = old.copy(inode = old.inode + 1)
        files.failDuringWrite = 0; val writes = files.replacements
        assertFails { restore(engine) }; assertEquals(writes, files.replacements)
    }
    @Test fun `completed lifecycle never excuses changed owner group or access mode`() {
        for (change in listOf("owner", "group", "mode")) {
            val engine = engine(); install(engine); rewrite(index(preparedRow, timestamp = 9))
            val name = ChapterInstallPolicy.MANIFEST; val old = files.identity(name)
            files.accessOverrides[name] = when (change) {
                "owner" -> old.copy(uid = 2000)
                "group" -> old.copy(gid = old.gid + 1)
                else -> old.copy(mode = 384)
            }
            val writes = files.replacements
            assertFails { restore(engine) }; assertEquals(writes, files.replacements)
        }
    }
    @Test fun `adaptive restoration resumes from partial index write after engine restart`() {
        val engine = engine(); install(engine)
        val other = "1,unrelated,4,5,Other/Fake\n1,new-token,7,9,Other/New"
        rewrite(index(preparedRow, other, timestamp = 9))
        files.failDuringWrite = 4; files.partialBytes = 17
        assertFails { restore(engine) }
        files.failDuringWrite = 0
        val reopened = engine(adapter = files)
        assertEquals(ChapterPairState.INTERRUPTED, reopened.inspect(record().copy(state = ChapterInstallState.RESTORING)))
        restore(reopened)
        assertEquals(text(index(originalRow, other)), text(files.read(ChapterInstallPolicy.MANIFEST)))
    }
    @Test fun `completed installation evidence is required before semantic replacement is trusted`() {
        val engine = engine()
        files.putOriginals(replacement, index(preparedRow, timestamp = 4))
        assertEquals(ChapterPairState.UNKNOWN, engine.inspect(record().copy(state = ChapterInstallState.INSTALLED)))
        val writes = files.replacements
        assertFails { restore(engine) }; assertEquals(writes, files.replacements)
    }
    @Test fun `late unrelated index edit is preserved by refusing a stale restore target`() {
        val engine = engine(); install(engine)
        val wrapped = object : ChapterInstallFiles by files {
            override fun openLive(name: String, gameUid: Int): ChapterLiveFile {
                if (name == ChapterInstallPolicy.MANIFEST) File(root, name).writeBytes(index(preparedRow, "1,unrelated,4,5,Other/Fake", timestamp = 5))
                return files.openLive(name, gameUid)
            }
        }
        val protected = engine(adapter = wrapped)
        assertFails { restore(protected) }
        assertTrue(text(files.read(ChapterInstallPolicy.MANIFEST)).contains("1,unrelated,4,5,Other/Fake"))
    }
}
