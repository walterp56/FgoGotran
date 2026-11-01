package com.fgogotran.chapter.storage

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.fgogotran.chapter.install.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Separate from the browser connection. Each explicit operation releases its shell service. */
internal class ShizukuChapterInstaller(private val context: Context,
    private val transferFile: (ChapterInstallRecord, Boolean, Boolean) -> java.io.File) : ChapterInstallTransport {
    constructor(context: Context, store: ChapterBackupStore) : this(context, store::transferFile)
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ChapterInstallService::class.java))
        .tag("chapter-install-test-v1").daemon(false).processNameSuffix("chapter_install")
        .version((context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime % Int.MAX_VALUE).toInt())

    suspend fun writeAsset(filename: String, bytes: ByteArray) = remote { service ->
        val temp = java.io.File.createTempFile("asset-", ".bin", context.cacheDir)
        try {
            temp.writeBytes(bytes)
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> service.writeAsset(filename, fd) }
        } finally {
            temp.delete()
        }
    }
    private suspend fun <T> remote(block: (IChapterInstallService) -> T): T {
        var connection: ServiceConnection? = null
        try {
            val service = withContext(Dispatchers.Main.immediate) {
                check(Shizuku.pingBinder() && Shizuku.getVersion() >= 13 && Shizuku.getUid() == 2000 &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Connect and authorise non-root Shizuku first" }
                withTimeout(15_000) {
                    suspendCancellableCoroutine<IChapterInstallService> { continuation ->
                        val next = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                                if (continuation.isActive) continuation.resume(IChapterInstallService.Stub.asInterface(binder))
                            }
                            override fun onServiceDisconnected(name: ComponentName) {
                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Installer disconnected"))
                            }
                            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                        }
                        connection = next
                        try { Shizuku.bindUserService(args, next) }
                        catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                    }
                }
            }
            return withContext(Dispatchers.IO) { block(service) }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                connection?.let {
                    // remove=true alone leaves Shizuku's client-side cached connection until
                    // its asynchronous death callback. A rapid rebind can inherit that dying
                    // connection and lose the next RPC. Detach/clear it before remote teardown.
                    try { Shizuku.unbindUserService(args, it, false) }
                    finally { runCatching { Shizuku.unbindUserService(args, it, true) } }
                }
            }
        }
    }
    override suspend fun environment(): ChapterGameStatus = remote { service ->
        val text = service.environment()
        check(text.length <= 2048)
        val obj = Json.parseToJsonElement(text).jsonObject
        ChapterGameStatus(ChapterGameIdentity(obj.getValue("name").jsonPrimitive.content, obj.getValue("code").jsonPrimitive.long,
            obj.getValue("updated").jsonPrimitive.long, obj.getValue("uid").jsonPrimitive.int), obj.getValue("stopped").jsonPrimitive.boolean)
    }
    override suspend fun inspect(record: ChapterInstallRecord): ChapterPairState = remote { service ->
        val encoded = ChapterInstallRecordCodec.encode(record)
        ChapterPairState.valueOf(if (record.mode != null && record.writeProtocol == ChapterWriteProtocol.IN_PLACE_V1) {
            descriptor(record, false, false) { original -> descriptor(record, false, true) { prepared -> service.inspectKnown(encoded, original, prepared) } }
        } else service.inspect(encoded))
    }
    override suspend fun stage(record: ChapterInstallRecord) = transfer(record, true) { service, bundle, manifest -> service.stage(ChapterInstallRecordCodec.encode(record), bundle, manifest) }
    override suspend fun commit(record: ChapterInstallRecord) = remote { it.commit(ChapterInstallRecordCodec.encode(record)) }
    override suspend fun restore(record: ChapterInstallRecord) = transfer(record, false) { service, bundle, manifest ->
        if (record.writeProtocol == ChapterWriteProtocol.IN_PLACE_V1) descriptor(record, false, true) { prepared ->
            service.restoreKnown(ChapterInstallRecordCodec.encode(record), bundle, manifest, prepared)
        } else service.restore(ChapterInstallRecordCodec.encode(record), bundle, manifest)
    }
    private fun <T> descriptor(record: ChapterInstallRecord, bundle: Boolean, prepared: Boolean, block: (ParcelFileDescriptor) -> T): T =
        ParcelFileDescriptor.open(transferFile(record, bundle, prepared), ParcelFileDescriptor.MODE_READ_ONLY).use(block)
    private suspend fun transfer(record: ChapterInstallRecord, prepared: Boolean, block: (IChapterInstallService, ParcelFileDescriptor, ParcelFileDescriptor) -> Unit) = remote { service ->
        ParcelFileDescriptor.open(transferFile(record, true, prepared), ParcelFileDescriptor.MODE_READ_ONLY).use { bundle ->
            ParcelFileDescriptor.open(transferFile(record, false, prepared), ParcelFileDescriptor.MODE_READ_ONLY).use { manifest -> block(service, bundle, manifest) }
        }
    }
}
