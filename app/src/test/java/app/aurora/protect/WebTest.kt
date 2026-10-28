package app.aurora.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebTest {
    @Test
    fun hostsComeFromWhatBrowsersShow() {
        assertEquals("reddit.com", hostOf("reddit.com/r/android"))
        assertEquals("m.youtube.com", hostOf("https://m.youtube.com/watch?v=1"))
        assertEquals("example.com", hostOf("http://user@Example.com:8080/x"))
        assertNull(hostOf("how to focus better"))
        assertNull(hostOf("localhost"))
        assertNull(hostOf(""))
    }

    @Test
    fun aDomainCoversItsSubdomainsOnly() {
        val keys = listOf("web:reddit.com", "web:old.reddit.com", "com.app")
        assertEquals("web:reddit.com", webKeyFor("reddit.com", keys))
        assertEquals("web:reddit.com", webKeyFor("m.reddit.com", keys))
        assertEquals("web:old.reddit.com", webKeyFor("old.reddit.com", keys))
        assertNull(webKeyFor("notreddit.com", keys))
        assertNull(webKeyFor("reddit.co", keys))
    }
}
