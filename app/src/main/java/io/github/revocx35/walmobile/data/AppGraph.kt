package io.github.revocx35.walmobile.data

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import io.github.revocx35.walmobile.BuildConfig
import io.github.revocx35.walmobile.core.ApiException
import io.github.revocx35.walmobile.core.userMessage
import io.github.revocx35.walmobile.engine.Notifier
import io.github.revocx35.walmobile.engine.WaEngine
import io.github.revocx35.walmobile.media.AudioController
import io.github.revocx35.walmobile.media.MediaAccess
import io.github.revocx35.walmobile.media.WalmImageFetcher
import io.github.revocx35.walmobile.security.Vault
import io.github.revocx35.walmobile.store.Db
import io.github.revocx35.walmobile.store.EventBus
import io.github.revocx35.walmobile.store.LocalApi
import io.github.revocx35.walmobile.store.MediaFiles
import io.github.revocx35.walmobile.store.Presenter
import io.github.revocx35.walmobile.store.Repo
import io.github.revocx35.walmobile.store.SettingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** App-wide singletons (created once in [io.github.revocx35.walmobile.WaLoggerApp]). */
class AppGraph(val context: Context, val diagnostics: Diagnostics) {
    val prefs = Prefs(context)
    /** Background work must never take the app down: record unexpected failures instead. */
    val errors = CoroutineExceptionHandler { ctx, e -> diagnostics.recordNonFatal(ctx[CoroutineName]?.name ?: "app", e) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)

    val db = Db(context)
    val repo = Repo(db)
    val vault = Vault(repo)
    val settings = SettingsStore(repo)
    val events = EventBus()
    val files = MediaFiles(File(context.noBackupFilesDir, "media"), repo) { context.getDatabasePath(Db.NAME) }
    val mediaAccess = MediaAccess(files, vault)

    /** Only for chat pictures from WhatsApp's CDN (WhatsApp Web itself uses the WebView's network stack). */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "wa_logger-mobile/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})").build())
        }
        .build()

    val engine = WaEngine(context, repo, vault, settings, events, files, http, scope) { where, e -> diagnostics.recordNonFatal(where, e) }
    val api = LocalApi(repo, vault, settings, engine, events, files)
    val sender = io.github.revocx35.walmobile.engine.LocalSender(
        context.applicationContext,
        { op, args, timeout -> engine.pageCall(op, args, timeout) },
        { engine.linked },
        repo,
        vault,
        files,
        settings,
        events,
        { chatId -> engine.serial { engine.ingest.ensureChat(chatId, force = true) } },
    )
    val controller = AppController(this)

    init {
        engine.ingest.deletionListener = io.github.revocx35.walmobile.engine.DeletionListener { chatId, _ -> onDeleted(chatId) }
    }

    /* ------------------------------------------- deleted-message notices */

    private val pendingDeleted = HashMap<String, Int>()
    private var deletedJob: Job? = null

    /** Batches deletions for a few seconds, then notifies per chat (name only if it can be read). */
    private fun onDeleted(chatId: String) {
        if (!prefs.notifyDeleted.value) return
        scope.launch {
            pendingDeleted[chatId] = (pendingDeleted[chatId] ?: 0) + 1
            if (deletedJob?.isActive == true) return@launch
            deletedJob = scope.launch {
                delay(3000)
                val batch = HashMap(pendingDeleted)
                pendingDeleted.clear()
                for ((id, count) in batch) {
                    val name = withContext(Dispatchers.IO) {
                        runCatching {
                            val chat = repo.getChat(id) ?: return@runCatching null
                            if (vault.unlocked) Presenter(repo, vault.reader()).chatName(chat) else null
                        }.getOrNull()
                    }
                    Notifier.deleted(context, name, count)
                }
            }
        }
    }
}

/**
 * What the screens use while the app is unlocked: the local API, live events, the image loader and the
 * shared audio player. Closed on lock, which also drops every decrypted image from memory.
 */
class Session(private val graph: AppGraph) {
    val id: String = UUID.randomUUID().toString()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + graph.errors)
    val api: LocalApi get() = graph.api
    val events: EventBus get() = graph.events
    val media: MediaAccess get() = graph.mediaAccess

    val imageLoader: ImageLoader = ImageLoader.Builder(graph.context)
        .components {
            add(WalmImageFetcher.Factory(graph.mediaAccess))
            if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(graph.context, 0.2).build() }
        .diskCache(null)
        .build()

    private var audioUsed = false

    /** Shared player for voice messages (created on first use). */
    val audio: AudioController by lazy {
        audioUsed = true
        AudioController(graph.context, graph.mediaAccess, scope)
    }

    /** UI text for a failed operation; unexpected (non-API) failures also go into the crash report. */
    fun fail(where: String, e: Throwable): String {
        if (e !is ApiException && e !is kotlinx.coroutines.CancellationException) graph.diagnostics.recordNonFatal(where, e)
        return e.userMessage()
    }

    /** Media URLs are already local (`walm://media/<id>`). */
    fun mediaUrl(url: String?): String? = url?.takeIf { it.startsWith("walm://media/") }

    fun close() {
        if (audioUsed) audio.release()
        imageLoader.memoryCache?.clear()
        imageLoader.shutdown()
        scope.cancel()
    }
}
