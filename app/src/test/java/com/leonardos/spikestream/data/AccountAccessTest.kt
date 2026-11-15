package com.leonardos.spikestream.data
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class AccountAccessTest {
    private fun account(): JSONObject = JSONObject().put("plan","plus").put("allowed",JSONObject().put("custom",true).put("youtubeManaged",true))
        .put("youtubeManagedAccess",JSONObject().put("allowed",true))
    @Test fun expiredSubscriptionLocksStaleClaims() {
        val data=account().put("subscriptionExpiresAt","2000-01-01T00:00:00.000Z")
        assertFalse(AccountAccess(data).canCreate("custom"))
        assertFalse(AccountAccess(data).canPrepareYouTube())
    }
    @Test fun refreshedExpiredPlanUsesServerFreeLimits() {
        val data=account().put("subscriptionExpired",true).put("subscriptionExpiresAt","2000-01-01T00:00:00.000Z")
        assertTrue(AccountAccess(data).canCreate("custom"))
        data.getJSONObject("youtubeManagedAccess").put("allowed",false)
        assertFalse(AccountAccess(data).canPrepareYouTube())
    }
    @Test fun expiryAndCapacityAreSeparateFromExistingEventPermission() {
        val data=account();data.getJSONObject("allowed").put("youtubeManaged",false)
        assertFalse(AccountAccess(data).canCreate("youtubeManaged"));assertTrue(AccountAccess(data).canPrepareYouTube())
        data.getJSONObject("youtubeManagedAccess").put("expiresAt","2000-01-01T00:00:00.000Z")
        assertFalse(AccountAccess(data).canPrepareYouTube())
    }
    @Test fun manualSocialUrlsUseSocialCapacity() {
        assertEquals("youtubeDirect",StreamDestination(StreamPlatform.CUSTOM,"manual","rtmps://a.rtmps.youtube.com/key").accountKind())
        assertEquals("facebook",StreamDestination(StreamPlatform.CUSTOM,"manual","rtmps://live-api-s.facebook.com/key").accountKind())
        assertEquals("custom",StreamDestination(StreamPlatform.CUSTOM,"manual","rtmps://youtube.com.example.invalid/key").accountKind())
    }
    @Test fun missingOrMalformedAccessCannotEnableFeatures() {
        assertFalse(AccountAccess(JSONObject()).canCreate("custom"))
        assertFalse(AccountAccess(account().put("subscriptionExpiresAt","malformed")).canCreate("custom"))
    }
}
