package com.leonardos.spikestream.streaming

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class SponsorAsset(val id: String, val name: String, val url: String,
    val video: Boolean, val width: Int, val corner: String)
internal data class PreparedSponsor(val file: File, val width: Int, val height: Int,
    val image: Bitmap? = null, val pcm: ByteArray? = null)

internal object SponsorMedia {
    private const val CACHE_LIMIT = 80L * 1024 * 1024
    private val reservations = mutableMapOf<File, Long>()

    @Synchronized fun release(file: File) {
        reservations.remove(file)
        file.delete()
    }
    fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host.orEmpty().lowercase()
        value.length <= 2048 && uri.scheme == "https" && uri.rawUserInfo == null &&
            uri.fragment == null && uri.port in listOf(-1, 443) &&
            host.matches(Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,63}")) &&
            listOf(".local", ".localhost", ".internal", ".test", ".invalid", ".home.arpa", ".onion").none { host.endsWith(it) }
    }.getOrDefault(false)

    // Bind the actual OkHttp connection to vetted DNS answers. No system HTTP proxy.
    internal fun publicAddress(address: InetAddress): Boolean {
        val bytes = address.address.map { it.toInt() and 255 }
        val a = bytes[0]
        val b = bytes[1]
        if (bytes.size == 16) return a in 0x20..0x3f &&
            !(a == 0x20 && b == 2) && !(a == 0x20 && b == 1 && (bytes[2] < 2 || bytes[2] == 13 && bytes[3] == 184)) &&
            !(a == 0x3f && b == 0xff && bytes[2] < 16) &&
            (bytes.take(6) != listOf(0x26,0x20,0,0x4f,0x80,0))
        return a !in listOf(0, 10, 127) && a < 224 &&
            !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
            !(a == 172 && b in 16..31) && !(a == 192 && (b == 168 || b == 0 && bytes[2] in listOf(0,2) || b == 88 && bytes[2] == 99)) &&
            !(a == 192 && (b == 31 && bytes[2] == 196 || b == 52 && bytes[2] == 193 || b == 175 && bytes[2] == 48)) &&
            !(a == 198 && (b in 18..19 || b == 51 && bytes[2] == 100)) && !(a == 203 && b == 0 && bytes[2] == 113)
    }
    internal fun redirect(url: String, location: String): String =
        URI(url).resolve(location).toString().also { require(validUrl(it)) }

    internal fun boundedCopy(input: java.io.InputStream, output: java.io.OutputStream,
        limit: Long, check: () -> Unit, live: () -> Boolean): Long {
        val buffer = ByteArray(16384)
        var total = 0L
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            require(total <= limit)
            output.write(buffer, 0, count)
            if (live()) Thread.sleep(80)
        }
    }

    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .dns { host -> InetAddress.getAllByName(host).toList().also { addresses ->
            require(addresses.isNotEmpty() && addresses.all(::publicAddress))
        } }.followRedirects(false).followSslRedirects(false)
        .callTimeout(220, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    fun prepare(asset: SponsorAsset, directory: File, check: () -> Unit, live: () -> Boolean): PreparedSponsor {
        val limit = if (asset.video) 32L shl 20 else 2L shl 20
        val target = File(directory, UUID.randomUUID().toString())
        directory.mkdirs()
        val stored = File(directory, "verified").apply { mkdirs() }
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest((asset.id + asset.url).toByteArray()).joinToString("") { "%02x".format(it) }
        val entry = File(stored, hash)
        synchronized(this) {
            val now = System.currentTimeMillis()
            directory.listFiles()?.filter { it.isFile && it !in reservations }?.forEach { it.delete() }
            require(reservations.values.sum() + limit <= CACHE_LIMIT)
            reservations[target] = limit
            if (entry.isFile && entry.length() in 1..limit && now - entry.lastModified() < 86_400_000) {
                runCatching { android.system.Os.link(entry.path, target.path) }
            }
        }
        if (target.exists()) {
            try { return inspect(asset, target, check) }
            catch (e: kotlinx.coroutines.CancellationException) { release(target); throw e }
            catch (e: Exception) { target.delete(); entry.delete() }
        }
        var url = asset.url
        try {
            for (hop in 0..3) {
                check()
                require(validUrl(url))
                client.newCall(Request.Builder().url(url)
                    .header("User-Agent", "SpikeStream/3.0 (Android; media preload)")
                    .header("Accept-Encoding", "identity").build()).execute().use { response ->
                    if (response.code() in listOf(301,302,303,307,308)) {
                        url = redirect(url, requireNotNull(response.header("Location")))
                    } else {
                        require(response.code() == 200)
                        val body = requireNotNull(response.body())
                        require(body.contentLength() <= limit)
                        val total = target.outputStream().buffered().use { out ->
                            boundedCopy(body.byteStream(), out, limit, check, live)
                        }
                        require(body.contentLength() < 0 || total == body.contentLength())
                        check()
                        val media = inspect(asset, target, check)
                        synchronized(this) {
                            val files = stored.listFiles().orEmpty().sortedBy { it.lastModified() }
                            var size = files.sumOf { it.length() } + total
                            for (file in files) {
                                if (size <= CACHE_LIMIT && file != entry) continue
                                val bytes = file.length()
                                if (file.delete()) size -= bytes
                            }
                            runCatching { android.system.Os.link(target.path, entry.path) }
                        }
                        return media
                    }
                }
            }
            error("Redirect limit")
        } catch (e: Exception) {
            release(target)
            throw e
        }
    }

    private fun inspect(asset: SponsorAsset, target: File, check: () -> Unit): PreparedSponsor {
        if (asset.video) return inspectVideo(target, check)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, options)
        require(options.outMimeType in listOf("image/png", "image/jpeg") &&
            options.outWidth in 1..4096 && options.outHeight in 1..4096 &&
            options.outWidth.toLong() * options.outHeight <= 4_000_000)
        options.inJustDecodeBounds = false
        val longestSide = (asset.width * 26).coerceIn(256, 720)
        while (maxOf(options.outWidth, options.outHeight) / maxOf(1, options.inSampleSize) > longestSide)
            options.inSampleSize = maxOf(1, options.inSampleSize) * 2
        val bitmap = requireNotNull(BitmapFactory.decodeFile(target.path, options))
        return PreparedSponsor(target, bitmap.width, bitmap.height, bitmap)
    }

    internal fun displaySize(w: Int, h: Int, rotation: Int): Pair<Int,Int> {
        require(w in 1..1920 && h in 1..1920 && w.toLong() * h <= 1920 * 1080)
        return when (rotation) {
            0,180 -> w to h
            90,270 -> h to w
            else -> error("Invalid rotation")
        }
    }

    internal fun checkContainer(file: File) {
        val header = ByteArray(12)
        java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
        require(String(header, 4, 4, Charsets.US_ASCII) == "ftyp")
    }

    private fun inspectVideo(file: File, check: () -> Unit): PreparedSponsor {
        checkContainer(file)
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.path)
            fun number(key: Int) = metadata.extractMetadata(key)?.toIntOrNull() ?: 0
            val w = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            require(number(MediaMetadataRetriever.METADATA_KEY_DURATION) in 1..60000)
            val size = displaySize(w, h, number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
            val extractor = MediaExtractor()
            val audio = try {
                extractor.setDataSource(file.path)
                require(extractor.trackCount in 1..16)
                val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                require(tracks.count { it?.startsWith("video/") == true } == 1 &&
                    tracks.contains("video/avc"))
                require(tracks.filter { it?.startsWith("audio/") == true }.all { it == "audio/mp4a-latm" })
                val index = tracks.indexOf("audio/mp4a-latm")
                if (index >= 0) SponsorAudio.decode(extractor, index, check) else null
            } finally { extractor.release() }
            requireNotNull(metadata.getFrameAtTime(0)).recycle()
            return PreparedSponsor(file, size.first, size.second, pcm = audio)
        } finally { metadata.release() }
    }
}
