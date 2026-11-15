package com.leonardos.spikestream.streaming

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.io.ByteArrayOutputStream

class SponsorMediaTest {
    @Test fun rejectsPrivateDestinationsAndUnsafeRedirects() {
        for (ip in listOf("127.0.0.1","10.2.3.4","100.64.0.1","169.254.169.254","192.168.1.1","198.18.1.1","224.0.0.1","::1","fc00::1","fe80::1","2001:db8::1","2002::1","::ffff:127.0.0.1")) {
            assertFalse(ip, SponsorMedia.publicAddress(InetAddress.getByName(ip)))
        }
        assertTrue(SponsorMedia.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(SponsorMedia.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
        val source = "https://cdn.example.org/media/ad"
        for (target in listOf("http://cdn.example.org/a","//127.0.0.1/a","https://user:pass@cdn.example.org","//x.local/a","https://[::1]/")) {
            assertTrue(target, runCatching { SponsorMedia.redirect(source,target) }.isFailure)
        }
        assertEquals("https://cdn.example.org/new.mp4", SponsorMedia.redirect(source, "../new.mp4"))
    }

    @Test fun boundsActualBytesAndHonorsCancellation() {
        val bytes = ByteArray(17000)
        val output = ByteArrayOutputStream()
        assertEquals(17000L,SponsorMedia.boundedCopy(bytes.inputStream(),output,17000,{}, {false}))
        output.reset()
        assertTrue(runCatching { SponsorMedia.boundedCopy(bytes.inputStream(),output,16999,{}, {false}) }.isFailure)
        assertTrue(output.size() <= 16999)
        output.reset()
        assertTrue(runCatching { SponsorMedia.boundedCopy(bytes.inputStream(),output,17000,{ error("cancelled") }, {false}) }.isFailure)
        assertEquals(0,output.size())
    }
    @Test fun rotatedVideosUseDisplayDimensions() {
        for (rotation in listOf(90,270)) assertEquals(1080 to 1920, SponsorMedia.displaySize(1920,1080,rotation))
        for (rotation in listOf(0,180)) assertEquals(1920 to 1080, SponsorMedia.displaySize(1920,1080,rotation))
        assertTrue(runCatching { SponsorMedia.displaySize(4000,4000,0) }.isFailure)
        assertTrue(runCatching { SponsorMedia.displaySize(100,100,45) }.isFailure)
    }

    @Test fun truncatedCacheAndNetworkLossCannotCompletePreparation() {
        val file = java.io.File.createTempFile("sponsor-test", ".part")
        try {
            file.writeBytes(ByteArray(5))
            assertTrue(runCatching { SponsorMedia.checkContainer(file) }.isFailure)
            val broken = object : java.io.InputStream() {
                override fun read(): Int = throw java.io.IOException("lost")
            }
            assertTrue(runCatching {
                file.outputStream().use { SponsorMedia.boundedCopy(broken,it,1024,{}, {false}) }
            }.isFailure)
            assertTrue(runCatching { SponsorMedia.checkContainer(file) }.isFailure)
        } finally { file.delete() }
    }
}
