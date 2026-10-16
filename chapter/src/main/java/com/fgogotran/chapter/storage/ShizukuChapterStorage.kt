package com.fgogotran.chapter.storage

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.fgogotran.chapter.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

enum class ChapterAccess { MISSING, STOPPED, UNSUPPORTED, PERMISSION, DENIED, CONNECTING, READY, ERROR }

/** Feature-local connection. Merely opening the app never binds the file service. */
class ShizukuChapterStorage(context: Context, private val scope: CoroutineScope) : ChapterOriginalStorage {
    private val context = context.applicationContext
    private val mutableAccess = MutableStateFlow(ChapterAccess.STOPPED)
    val access = mutableAccess.asStateFlow()
    @Volatile private var remote: IChapterFileService? = null
    private var connection: ServiceConnection? = null
    private var timeout: Job? = null
    private var foreground = false
    private var permissionPending = false
    private var closed = false
    @Volatile private var generation = 0
    private val args = Shizuku.UserServiceArgs(ComponentName(this.context, ChapterFileService::class.java))
        .tag("chapter-read-only-v1").daemon(false).processNameSuffix("chapter_read")
        .version((this.context.packageManager.getPackageInfo(this.context.packageName, 0).lastUpdateTime % Int.MAX_VALUE).toInt())
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener { disconnect(); mutableAccess.value = ChapterAccess.STOPPED }
    private val permission = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (request == REQUEST) {
            permissionPending = result == PackageManager.PERMISSION_GRANTED
            if (permissionPending && foreground) connect() else refresh()
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        refresh()
    }

    fun onForeground() {
        foreground = true
        refresh()
        if (permissionPending) connect()
    }

    fun onBackground() { foreground = false; disconnect() }

    fun refresh() {
        if (closed) return
        val status = try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            when {
                !Shizuku.pingBinder() -> ChapterAccess.STOPPED
                Shizuku.getVersion() < 13 || Shizuku.getUid() != 2000 || android.os.Process.myUid() / 100000 != 0 -> ChapterAccess.UNSUPPORTED
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ChapterAccess.PERMISSION
                remote != null -> ChapterAccess.READY
                connection != null -> ChapterAccess.CONNECTING
                else -> ChapterAccess.PERMISSION // Permission is granted; Connect still explicitly starts the reader.
            }
        } catch (_: PackageManager.NameNotFoundException) { ChapterAccess.MISSING }
        catch (_: Exception) { ChapterAccess.ERROR }
        if (status !in listOf(ChapterAccess.READY, ChapterAccess.CONNECTING) && remote != null) disconnect()
        mutableAccess.value = status
    }

    fun connect() {
        if (!foreground || closed) return
        refresh()
        if (mutableAccess.value in listOf(ChapterAccess.MISSING, ChapterAccess.STOPPED, ChapterAccess.UNSUPPORTED)) return
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                permissionPending = false
                if (Shizuku.shouldShowRequestPermissionRationale()) mutableAccess.value = ChapterAccess.DENIED
                else Shizuku.requestPermission(REQUEST)
                return
            }
            permissionPending = false
            if (connection != null) return
            val token = ++generation
            val next = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    scope.launch {
                        val reader = IChapterFileService.Stub.asInterface(binder)
                        val valid = withContext(Dispatchers.IO) { runCatching { reader.serviceUid == 2000 }.getOrDefault(false) }
                        if (token != generation || closed || !foreground) return@launch
                        timeout?.cancel()
                        if (valid) { remote = reader; mutableAccess.value = ChapterAccess.READY }
                        else { disconnect(); mutableAccess.value = ChapterAccess.ERROR }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) = lost(ChapterAccess.STOPPED)
                override fun onBindingDied(name: ComponentName) = lost(ChapterAccess.STOPPED)
                override fun onNullBinding(name: ComponentName) = lost(ChapterAccess.ERROR)
                private fun lost(status: ChapterAccess) {
                    // A late callback from an old binding must not tear down a new reader.
                    if (token == generation) { disconnect(); mutableAccess.value = status }
                }
            }
            connection = next
            mutableAccess.value = ChapterAccess.CONNECTING
            Shizuku.bindUserService(args, next)
            timeout = scope.launch { delay(15_000); if (token == generation && remote == null) { disconnect(); mutableAccess.value = ChapterAccess.ERROR } }
        } catch (_: Exception) { disconnect(); mutableAccess.value = ChapterAccess.ERROR }
    }

    fun disconnect() {
        generation++
        timeout?.cancel(); timeout = null
        remote = null
        val old = connection
        connection = null
        if (old != null) runCatching { Shizuku.unbindUserService(args, old, true) }
        if (!closed) mutableAccess.value = ChapterAccess.STOPPED
    }

    fun close() {
        closed = true
        permissionPending = false
        disconnect()
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
    }

    override suspend fun capability(): ChapterStorageCapability = if (remote != null && mutableAccess.value == ChapterAccess.READY)
        ChapterStorageCapability.ReadOnly(ChapterAssetPolicy.ROOT_ID)
    else ChapterStorageCapability.Unavailable(StorageUnavailableReason.PERMISSION_DENIED)

    override suspend fun readOriginal(path: GameAssetPath): OriginalAssetReadResult = withContext(Dispatchers.IO) {
        ChapterAssetPolicy.requireFilename(path.value)
        val token = generation
        val reader = remote ?: return@withContext OriginalAssetReadResult.Unavailable(StorageUnavailableReason.PERMISSION_DENIED)
        try {
            check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
            ensureActive()
            val before = reader.fingerprint(path.value)
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(reader.openOriginal(path.value)).use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size().toLong() + count <= ChapterAssetPolicy.MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            ensureActive()
            val original = OriginalAsset.capture(path, bytes)
            val after = reader.fingerprint(path.value)
            check(before == after && after == "${original.fingerprint.sha256.hex}:${bytes.size}") { "Asset changed during reading; scan again" }
            check(token == generation && remote === reader) { "Reader was disconnected" }
            OriginalAssetReadResult.Read(original)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { OriginalAssetReadResult.Unavailable(StorageUnavailableReason.ROOT_NOT_VERIFIED) }
    }

    private companion object { const val REQUEST = 7101 }
}
