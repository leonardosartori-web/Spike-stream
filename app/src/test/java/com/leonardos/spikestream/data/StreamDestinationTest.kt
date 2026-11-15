package com.leonardos.spikestream.data

import com.leonardos.spikestream.utils.RtmpUrlValidator
import org.junit.Assert.*
import org.junit.Test

class StreamDestinationTest {
    private fun manual(index: Int) = StreamDestination(StreamPlatform.CUSTOM, "Court $index", "rtmps://stream.example/live/key$index")

    @Test fun sixthDestinationKeepsNewestAndDropsOldest() {
        val history = (1..6).fold(emptyList<StreamDestination>()) { history, index ->
            StreamDestinationHistory.record(history, manual(index), index.toLong())
        }
        assertEquals(listOf("Court 6", "Court 5", "Court 4", "Court 3", "Court 2"), history.map { it.name })
    }

    @Test fun reusingDestinationMovesItToFrontWithoutDuplicates() {
        val initial = (1..5).fold(emptyList<StreamDestination>()) { history, index ->
            StreamDestinationHistory.record(history, manual(index), index.toLong())
        }
        val updated = StreamDestinationHistory.record(initial, manual(1).copy(name = "Renamed court"), 6)
        assertEquals(5, updated.size)
        assertEquals("Renamed court", updated.first().name)
        assertEquals(1, updated.count { it.rtmpUrl == manual(1).rtmpUrl })
    }

    @Test fun savedYoutubePreservesChannelButNeverReusesAnOldBroadcast() {
        val yt = YouTubeStreamDestination("stream", "Match key", "Match key",
            "rtmps://a.rtmps.youtube.com/live2/private-key", "RTMPS", "active",
            "UC_channel", "Volley Club", "old-event")
        val original = StreamDestination(StreamPlatform.YOUTUBE, "Match key", yt.rtmpUrl, youtube = yt,
            facebookAccessToken = "must-not-persist", youtubeManaged = true, youtubeMadeForKids = true)
        val encoded = StreamDestinationHistory.encode(listOf(original))
        val restored = StreamDestinationHistory.decode(encoded).single()
        assertEquals("UC_channel", restored.youtube?.youtubeChannelId)
        assertEquals("Volley Club", restored.youtube?.youtubeChannelTitle)
        assertEquals(original.rtmpUrl, restored.rtmpUrl)
        assertNull(restored.youtube?.youtubeBroadcastId)
        assertNull(restored.facebookAccessToken)
        assertTrue(restored.youtubeManaged)
        assertTrue(restored.youtubeMadeForKids)
        val legacy = StreamDestinationHistory.decode(encoded.replace("\"youtubeManaged\":true,", "").replace(",\"youtubeManaged\":true", "" )).single()
        assertFalse(legacy.youtubeManaged)
        val experimental = StreamDestinationHistory.decode(encoded.replace("youtubeManaged", "youtubeAutomatic")).single()
        assertFalse(experimental.youtubeManaged)
        assertFalse(encoded.contains("old-event"))
        assertFalse(encoded.contains("must-not-persist"))
        assertEquals("", restored.youtube?.status)
    }

    @Test fun facebookLiveCredentialsAreNeverSavedAsReusableHistory() {
        val facebook = StreamDestination(StreamPlatform.FACEBOOK, "Club",
            "rtmps://live-api-s.facebook.com:443/rtmp/event-key",
            facebook = FacebookDestination("123", "Club", "page"), facebookAccessToken = "fb-secret")
        assertTrue(facebook.isReady)
        assertFalse(facebook.copy(facebookAccessToken = null).isReady)
        assertEquals(emptyList<StreamDestination>(), StreamDestinationHistory.record(emptyList(), facebook, 10))
        assertEquals("[]", StreamDestinationHistory.encode(listOf(facebook)))
    }

    @Test fun malformedOrNonRtmpSavedDestinationsCannotBeSelected() {
        assertTrue(StreamDestinationHistory.decode("not-json").isEmpty())
        val invalid = manual(1).copy(rtmpUrl = "https://youtube.com/watch?v=public")
        assertFalse(invalid.isReady)
        assertTrue(StreamDestinationHistory.decode(StreamDestinationHistory.encode(listOf(invalid))).isEmpty())
    }

    @Test fun displayHostNeverExposesPathQueryOrUserInfoCredentials() {
        assertEquals("stream.example", streamDestinationHost("rtmps://user:password@stream.example/live/private-key?token=secret"))
        assertEquals("", streamDestinationHost("invalid url"))
    }

    @Test fun manualEntryAcceptsServerPlusKeyAndCompleteUrlWithoutDuplicatingSeparators() {
        assertEquals("rtmps://a.rtmps.youtube.com/live2/private-key",
            completeStreamUrl(" rtmps://a.rtmps.youtube.com/live2/ ", " /private-key "))
        assertEquals("rtmps://live-api-s.facebook.com:443/rtmp/key?ds=1",
            completeStreamUrl("rtmps://live-api-s.facebook.com:443/rtmp/", "key?ds=1"))
        val complete = "rtmps://a.rtmps.youtube.com/live2/private-key"
        assertEquals(complete, completeStreamUrl(" $complete ", ""))
        assertFalse(RtmpUrlValidator.isValid(completeStreamUrl("rtmps://a.rtmps.youtube.com/live2", "")))
        assertTrue(RtmpUrlValidator.isValid(completeStreamUrl("rtmps://a.rtmps.youtube.com/live2", "private-key")))
    }

    @Test fun youtubeCacheIsNotTruncatedToRecentHistoryLimit() {
        val encoded = StreamDestinationHistory.encode((1..12).map(::manual))
        assertEquals(5, StreamDestinationHistory.decode(encoded).size)
        assertEquals(12, StreamDestinationHistory.decode(encoded, Int.MAX_VALUE).size)
    }

    @Test fun oldDirectYouTubeDestinationNeedsVerificationButManagedKeepsAutomaticEvents() {
        val saved = StreamDestination(StreamPlatform.YOUTUBE, "SpikeStream", "rtmps://a.rtmps.youtube.com/live2/test-key")
        assertTrue(saved.requiresYouTubeKeyVerification)
        assertFalse(saved.copy(youtubeManaged = true).requiresYouTubeKeyVerification)
    }
    @Test fun verifiedStudioKeySurvivesHistoryWithoutBeingConvertedToManaged() {
        val key = YouTubeStreamDestination("studio-key", "SpikeStream", "SpikeStream", "rtmps://a.rtmps.youtube.com/live2/test-key", "RTMPS", "inactive", preconfigured = true)
        val saved = StreamDestination(StreamPlatform.YOUTUBE, key.title, key.rtmpUrl, youtube = key)
        val decoded = StreamDestinationHistory.decode(StreamDestinationHistory.encode(listOf(saved))).single()
        assertFalse(decoded.requiresYouTubeKeyVerification)
        assertFalse(decoded.youtubeManaged)
        assertTrue(decoded.youtube!!.preconfigured)
    }
}
