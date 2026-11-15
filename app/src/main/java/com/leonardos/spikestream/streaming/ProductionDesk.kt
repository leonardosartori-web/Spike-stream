package com.leonardos.spikestream.streaming

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.pedro.encoder.input.audio.NoAudioEffect


import com.leonardos.spikestream.streaming.AdaptiveRtmpCamera
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class ProductionDesk(context: Context, private val scoreAlpha: (Boolean) -> Unit,
    private val confirmScore: (() -> Unit) -> Unit,
    private val publish: (JSONObject) -> Unit) {
    private var base = ""
    private val cache = File(context.cacheDir, "production-media")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = mutableMapOf<String, Job>()
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val prepared = androidx.compose.runtime.mutableStateMapOf<String, PreparedSponsor>()
    private val failed = androidx.compose.runtime.mutableStateListOf<String>()
    private val visible = mutableSetOf<String>()
    private val filters = mutableMapOf<String, LiveImageFilter>()
    private var pendingFrame: String? = null
    var assets by androidx.compose.runtime.mutableStateOf(emptyList<SponsorAsset>())
        private set
    var selected = ""
        private set
    private var camera: AdaptiveRtmpCamera? = null
    private var frameRatio = 16f / 9f
    private var score = true
    @Volatile private var live = false
    private var revision = uuid()
    private var applied = ""
    private var playing: String? = null
    val spotActive get() = playing != null
    var replayAttached = false
    private var starting = false
    private var player: MediaPlayer? = null
    private var audio: SponsorAudio? = null
    private val syncAudio = object : Runnable {
        override fun run() {
            val current = player ?: return
            val effect = audio ?: return
            runCatching { effect.sync(current.currentPosition) }
            timer.postDelayed(this, 500)
        }
    }
    private var surface: SpotFilter? = null
    private val timer = Handler(Looper.getMainLooper())
    private val timeout = Runnable { playing?.let { failed.add(it); stop() } }



    fun receiveConfig(config: JSONObject) {
        val next = mutableListOf<SponsorAsset>()
        runCatching {
            require(config.optBoolean("ok"))
            val version = config.getString("revision")
            require(version.isEmpty() || UUID.fromString(version).toString() == version)
            val choice = config.getString("selected")
            val rows = config.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until minOf(10, rows.length())) {
                val row = rows.getJSONObject(i)
                val asset = SponsorAsset(row.getString("id"), row.getString("name"), row.getString("url"),
                    row.getBoolean("video"), row.getInt("width"), row.getString("corner"))
                require(valid(asset) && next.none { it.id == asset.id })
                next.add(asset)
            }
            require(rows.length() <= 10 && next.count { it.video } <= 2 && (choice.isEmpty() || next.any { it.id == choice && it.video }))
            val changedMedia = next.filter { n -> n.id in failed || assets.none { it.id == n.id && it.url == n.url && it.video == n.video } }
            if (playing != null && (choice != selected || changedMedia.any { it.id == playing })) stop()
            assets.filter { old -> next.none { it.id == old.id } || changedMedia.any { it.id == old.id } }.forEach { forget(it.id) }
            assets.filter { old -> next.any { it.id == old.id && (it.width != old.width || it.corner != old.corner) } }.forEach { old -> filters.remove(old.id)?.let { camera?.glInterface?.removeFilter(it) } }
            selected = choice
            assets = next
            base = version
            changedMedia.sortedByDescending { it.id == choice }.forEach(::prepare)
            banners()
            changed()
        }
    }

    private fun uuid() = UUID.randomUUID().toString()
    private fun valid(a: SponsorAsset) = runCatching { UUID.fromString(a.id).toString() == a.id }.getOrDefault(false) &&
        a.name.isNotBlank() && a.name.length <= 64 && a.name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() } &&
        SponsorMedia.validUrl(a.url) && a.width in 5..45 && a.corner in listOf("TL","TR","BL","BR")

    private fun forget(id: String) {
        pending.remove(id)?.cancel()
        prepared.remove(id)?.file?.let(::discard)
        failed.remove(id)
        visible.remove(id)
        filters.remove(id)?.let { camera?.glInterface?.removeFilter(it) }
    }
    private fun discard(file: File) { cleanup.execute { SponsorMedia.release(file) } }
    private companion object { val cleanup = java.util.concurrent.Executors.newSingleThreadExecutor() }
    private fun prepare(a: SponsorAsset) {
        pending[a.id] = scope.launch {
            var temp: PreparedSponsor? = null
            try {
                withContext(worker) {
                    val job = coroutineContext.job
                    temp = SponsorMedia.prepare(a, cache, { job.ensureActive() }, { live })
                }
                prepared[a.id] = requireNotNull(temp)
                temp = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed.add(a.id) }
            finally { temp?.file?.let(::discard) }
            changed()
        }
    }

    fun snapshot() = JSONObject().put("configRevision",base).put("revision", revision).put("applied",applied).put("live", live).put("score",score)
        .put("assets", JSONArray().also { rows -> assets.forEach { a ->
            val status = when {
                a.id in failed -> "error"
                a.id == playing -> if (starting) "preparing" else "onAir"
                playing == null && a.id in visible && live -> "onAir"
                a.id in prepared -> "ready"
                else -> "preparing"
            }
            rows.put(JSONObject().put("id",a.id).put("name",a.name).put("type",if(a.video) "spot" else "banner")
                .put("selected",selected == a.id).put("status",status))
        } })
    fun changed() { revision = uuid(); publish(snapshot()) }
    fun setLive(value: Boolean) {
        if (live == value) return
        live = value
        if (value) changed() else stop()
    }
    fun command(v: JSONObject) {
        if (!live || camera == null) return
        if (v.optString("revision") != revision) { publish(snapshot()); return }
        val commandId = v.optString("commandId")
        if (runCatching { UUID.fromString(commandId).toString() }.getOrNull() != commandId || commandId == applied) return
        val id = v.optString("id")
        when (v.optString("action")) {
            "score" -> { score = v.opt("visible") as? Boolean ?: return; scoreAlpha(score && playing == null) }
            "banner" -> {
                if (playing != null || prepared[id]?.image == null) return
                if (v.opt("visible") == true) visible.add(id) else if (v.opt("visible") == false) visible.remove(id) else return
                banners()
            }
            "start" -> { if (id != selected || playing != null || id in failed) return; start(id) }
            "stop" -> { if (playing != id) return; stop() }
            else -> return
        }
        val action = v.optString("action")
        if (action == "score" || action == "banner") {
            pendingFrame = commandId
            revision = uuid()
            val confirmed = {
                if (live && pendingFrame == commandId) {
                    pendingFrame = null
                    applied = commandId
                    changed()
                }
            }
            if (action == "score") confirmScore(confirmed) else filters[id]?.confirmFrame(confirmed) ?: confirmScore(confirmed)
        } else {
            applied = commandId
            changed()
        }
    }

    fun attach(next: AdaptiveRtmpCamera, width: Int, height: Int) {
        if (camera != null) detach()
        camera = next
        frameRatio = width.toFloat() / height
        scoreAlpha(score && playing == null)
        banners()
        playing?.let { id ->
            val media = prepared[id] ?: return@let
            val existing = player
            if (existing == null) start(id) else {
                val filter = SpotFilter(media.width.toFloat() / media.height, frameRatio) { active, target ->
                    if (surface === active && player === existing) runCatching { existing.setSurface(target) }
                        .onFailure { failed.add(id); stop() }
                }
                surface = filter
                next.glInterface.addFilter(filter)
            }
        }
        if (live) changed()
    }
    fun detach(transfer: Boolean = false) {
        pendingFrame = null
        if (transfer) {
            player?.setSurface(null)
            surface?.let { camera?.glInterface?.removeFilter(it) }
            surface = null
        } else if (playing != null) stop()
        filters.values.forEach { camera?.glInterface?.removeFilter(it) }
        filters.clear()
        camera = null
    }
    private fun banners() {
        val gl = camera?.glInterface ?: return
        assets.filter { !it.video && it.id in visible }.forEach { a ->
            val media = prepared[a.id] ?: return@forEach
            if (a.id !in filters) {
                val image = media.image ?: return@forEach
                val h = minOf(45f, a.width * frameRatio * media.height / media.width)
                val w = h * media.width / media.height / frameRatio
                val f = LiveImageFilter().apply {
                    setImage(image.copy(Bitmap.Config.ARGB_8888,false))
                    setScale(w,h)
                    setPosition(if (a.corner.endsWith("R")) 98f-w else 2f, if (a.corner.startsWith("B")) 98f-h else 2f)
                }
                filters[a.id] = f
                val earlier = assets.takeWhile { it.id != a.id }.count { it.id in filters }
                gl.addFilter((if (replayAttached) 2 else 1) + earlier, f)
            }
        }
        filters.forEach { (id, f) -> f.setAlpha(if (playing == null && id in visible) 1f else 0f) }
    }

    private fun start(id: String) {
        val media = prepared[id] ?: return
        val cam = camera ?: return
        playing = id
        starting = true
        scoreAlpha(false)
        banners()
        try {
            val filter = SpotFilter(media.width.toFloat() / media.height, frameRatio) { active, target ->
                if (surface === active) runCatching {
                    val p = MediaPlayer()
                    player = p
                    p.setVolume(0f,0f)
                    p.setSurface(target)
                    p.setDataSource(media.file.path)
                    p.setOnErrorListener { _, _, _ -> if (player === p) { failed.add(id); stop() }; true }
                    p.setOnCompletionListener { if (player === p) stop() }
                    p.setOnInfoListener { _, what, _ ->
                        if (player === p && starting && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                            starting = false
                            timer.removeCallbacks(timeout)
                            timer.postDelayed(timeout, 62000)
                            changed()
                        }
                        false
                    }
                    p.setOnPreparedListener {
                        if (player === p) {
                            media.pcm?.let { samples ->
                                audio = SponsorAudio(samples).also { cam.setCustomAudioEffect(it) }
                                timer.post(syncAudio)
                            }
                            runCatching { p.start() }.onFailure { failed.add(id); stop() }
                        }
                    }
                    p.prepareAsync()
                }.onFailure { failed.add(id); stop() }
            }
            surface = filter
            cam.glInterface.addFilter(filter)
            timer.postDelayed(timeout,8000)
        } catch (e: Exception) { failed.add(id); stop() }
    }
    private fun stop() {
        timer.removeCallbacks(timeout)
        timer.removeCallbacks(syncAudio)
        audio = null
        player?.release()
        player = null
        if (playing != null) camera?.setCustomAudioEffect(NoAudioEffect())
        surface?.let { camera?.glInterface?.removeFilter(it) }

        surface = null

        playing = null
        starting = false
        scoreAlpha(score)
        banners()
        changed()
    }
    fun close() {
        live = false
        detach()
        scope.cancel()
        prepared.values.forEach { discard(it.file) }
        prepared.clear()
    }
}
