package com.fgogotran.chapter.install

internal class TestInstallTransport(
    val files: TestChapterInstallFiles,
    private val store: ChapterBackupStore,
    validator: (ByteArray, ByteArray) -> Unit = InstallSamples::validate
) : ChapterInstallTransport {
    var game = ChapterGameStatus(InstallSamples.game, true)
    val engine = ChapterTransactionEngine(files, { game }, validator)
    var stages = 0
    var commits = 0
    var restores = 0
    var inspections = 0
    var onStage: suspend () -> Unit = {}
    var onCommit: suspend () -> Unit = {}
    override suspend fun environment() = game
    override suspend fun inspect(record: ChapterInstallRecord): ChapterPairState { inspections++; return engine.inspect(record) }
    override suspend fun stage(record: ChapterInstallRecord) {
        stages++; onStage()
        engine.stage(record, store.prepared(record, true), store.prepared(record, false))
    }
    override suspend fun commit(record: ChapterInstallRecord) {
        check(store.load()?.state == ChapterInstallState.COMMITTING) { "Durable commit intent must precede live replacement" }
        commits++; onCommit(); engine.commit(record)
    }
    override suspend fun restore(record: ChapterInstallRecord) {
        check(store.load()?.state == ChapterInstallState.RESTORING)
        restores++; engine.restore(record, store.original(record, true), store.original(record, false))
    }
}
