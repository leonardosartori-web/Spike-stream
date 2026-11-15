package com.leonardos.spikestream.data
import org.junit.Assert.*
import org.junit.Test
class AppAuthCallbackTest {
 @Test fun acceptsOnlyExactHttpsCallback() {
  assertEquals("a.b.c",AppAuthCallback.token("https://spikestream.tooolky.com/auth/app-auth?token=a.b.c"))
  for(url in listOf("http://spikestream.tooolky.com/auth/app-auth?token=a.b.c","https://evil.invalid/auth/app-auth?token=a.b.c","https://spikestream.tooolky.com/auth/app-auth/extra?token=a.b.c","https://spikestream.tooolky.com:444/auth/app-auth?token=a.b.c","https://user@spikestream.tooolky.com/auth/app-auth?token=a.b.c","https://spikestream.tooolky.com/auth/app-auth?token=a.b.c&token=d.e.f","https://spikestream.tooolky.com/auth/app-auth?token=a.b.c#x","https://spikestream.tooolky.com/auth/app-auth?token=invalid")) assertNull(url,AppAuthCallback.token(url))
 }
 @Test fun requiresMatchingRecentLocallyStartedLogin() {
  val nonce="a".repeat(64)
  assertTrue(AppAuthCallback.matches(nonce,nonce,1000,2000))
  assertFalse(AppAuthCallback.matches(null,nonce,1000,2000))
  assertFalse(AppAuthCallback.matches(nonce,"b".repeat(64),1000,2000))
  assertFalse(AppAuthCallback.matches(nonce,nonce,1000,601001))
  assertFalse(AppAuthCallback.matches(nonce,nonce,2000,1000))
 }
}
