package io.yiner.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSignInTest {
    @Test fun detectsGoogleSignInPages() {
        assertTrue(GoogleSignIn.isSignInUrl("https://accounts.google.com/v3/signin/identifier?continue=x"))
        assertTrue(GoogleSignIn.isSignInUrl("https://accounts.google.com/o/oauth2/v2/auth?client_id=1"))
        assertTrue(GoogleSignIn.isSignInUrl("https://accounts.google.com/ServiceLogin"))
    }

    @Test fun ignoresOtherPages() {
        assertFalse(GoogleSignIn.isSignInUrl("https://accounts.google.com/"))
        assertFalse(GoogleSignIn.isSignInUrl("https://myaccount.google.com/signin"))
        assertFalse(GoogleSignIn.isSignInUrl("http://accounts.google.com/signin"))
        assertFalse(GoogleSignIn.isSignInUrl("https://evil.example/accounts.google.com/signin"))
    }
}

class SessionCodecFormatTest {
    @Test fun oldGeckoStateIsNotTreatedAsWebViewState() {
        assertFalse(SessionCodec.isWebViewState("""{"history":{"entries":[]}}"""))
        assertFalse(SessionCodec.isWebViewState(null))
        assertFalse(SessionCodec.isWebViewState("wv1:"))
        assertTrue(SessionCodec.isWebViewState("wv1:AAAA"))
    }
}

class PrivateProfilesNamingTest {
    @Test fun eachSessionGetsAFreshPrivateName() {
        val a = PrivateProfiles.newName(1000)
        val b = PrivateProfiles.newName(2000)
        assertTrue(PrivateProfiles.isPrivateName(a))
        assertTrue(a != b)
        assertFalse(PrivateProfiles.isPrivateName("Default"))
    }
}

class BlobDownloadsTest {
    @Test fun dataUrlMime() {
        assertEquals("text/plain", BlobDownloads.dataUrlMime("data:text/plain;base64,SGk="))
        assertNull(BlobDownloads.dataUrlMime("data:,hello"))
    }

    @Test fun plainDataUrl() {
        assertEquals("hello world", String(BlobDownloads.decodeDataUrl("data:,hello%20world")!!))
        assertNull(BlobDownloads.decodeDataUrl("https://x/y"))
    }
}
