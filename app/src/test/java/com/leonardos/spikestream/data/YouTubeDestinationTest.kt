package com.leonardos.spikestream.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeDestinationTest {
    private val rtmp = "rtmps://a.rtmps.youtube.com/live2/private-key"
    private val destination = YouTubeStreamDestination(
        id = "stream", title = "Default stream key", label = "Default stream key",
        rtmpUrl = rtmp, protocol = "RTMPS", status = "ready",
        youtubeChannelId = "UC_channel", youtubeChannelTitle = "Volley Verona",
        youtubeBroadcastId = "match-video"
    )

    @Test fun importedDestinationIncludesNameAndEvent() {
        val body = JSONObject().apply { putYouTubeDestination(destination, rtmp) }
        assertEquals("Volley Verona", body.getString("youtubeChannelTitle"))
        assertEquals("match-video", body.getString("youtubeBroadcastId"))
        assertFalse(body.toString().contains("private-key"))
    }

    @Test fun manualRtmpDoesNotInventMetadata() {
        val body = JSONObject().apply { putYouTubeDestination(null, rtmp) }
        assertEquals(0, body.length())
    }

    @Test fun editedRtmpDoesNotInheritAnotherChannel() {
        val body = JSONObject().apply { putYouTubeDestination(destination, "$rtmp-edited") }
        assertEquals(0, body.length())
    }

    @Test fun oldBackendAndNullableDatabaseFieldsAreHandled() {
        assertNull(JSONObject().optionalYouTubeString("youtubeChannelTitle"))
        assertNull(JSONObject().put("youtubeChannelTitle", JSONObject.NULL).optionalYouTubeString("youtubeChannelTitle"))
        assertNull(JSONObject().put("youtubeChannelTitle", "  ").optionalYouTubeString("youtubeChannelTitle"))
    }

    @Test fun cachedChannelWorksWithoutStaleEvent() {
        val body = JSONObject().apply { putYouTubeDestination(destination.copy(youtubeBroadcastId = null), rtmp) }
        assertEquals("UC_channel", body.getString("youtubeChannelId"))
        assertFalse(body.has("youtubeBroadcastId"))
        assertNull(youtubeWatchUrl("UC_channel", null))
        assertEquals("https://www.youtube.com/channel/UC_channel", youtubeChannelUrl("UC_channel"))
    }

    @Test fun linksStayOnYoutubeAndContainNoStreamCredential() {
        assertEquals("https://www.youtube.com/watch?v=match-video", youtubeWatchUrl("UC_channel", "match-video"))
        assertNull(youtubeChannelUrl("../evil?key=private-key"))
        assertNull(youtubeWatchUrl("UC_channel", "https://evil.invalid"))
        assertNull(youtubeWatchUrl(null, "match-video"))
    }
}
