package com.leonardos.spikestream.streaming

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SponsorVideoTest {
    @Test fun preparesW3SchoolsWithMultipleAudioAndDataTracks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File(instrumentation.targetContext.cacheDir, "sponsor-multitrack-test.mp4")
        instrumentation.context.assets.open("sponsor-multitrack.mp4").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        try {
            val inspect = SponsorMedia.javaClass.getDeclaredMethod("inspectVideo", File::class.java, kotlin.jvm.functions.Function0::class.java)
            inspect.isAccessible = true
            val media = inspect.invoke(SponsorMedia, target, {}) as PreparedSponsor
            assertEquals(320, media.width)
            assertEquals(176, media.height)
            assertNotNull(media.pcm)
            assertTrue(media.pcm!!.size in (48000 * 4 * 9)..(48000 * 4 * 11))
        } finally { target.delete() }
    }

    @Test fun downloadsAndPreparesTheReportedHttpsUrl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val asset = SponsorAsset(UUID.randomUUID().toString(), "Spot", "https://www.w3schools.com/tags/mov_bbb.mp4", true, 15, "TR")
        val media = SponsorMedia.prepare(asset, File(context.cacheDir, "sponsor-url-test"), {}, { false })
        try {
            assertNotNull(media.pcm)
            assertEquals(320, media.width)
        } finally { SponsorMedia.release(media.file) }
    }
}
