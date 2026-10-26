package com.github.asm0dey.typewriter.record

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class BatchNamingTest {

    @Test
    fun `strips numbers and pascal-cases`() {
        assertEquals("UserService.kt", cleanClassName("03-user-service.kt"))
        assertEquals("Hello.java", cleanClassName("01_hello.java"))
        assertEquals("Intro.kt", cleanClassName("01.intro.kt"))
        assertEquals("MyHTTPClient.java", cleanClassName("02 myHTTPClient.java"))
        assertEquals("ÜberDemo.kt", cleanClassName("über-demo.kt"))
        assertEquals("Snippet.java", cleanClassName("01.java"))
        assertEquals("Query.sql", cleanClassName("query.sql"))
        assertEquals("Readme", cleanClassName("readme"))
    }

    @Test
    fun `glob is case-insensitive, star crosses slash, metacharacters are literal`() {
        assertTrue(globMatches("*cyberjar*", "talk/CyberJar-01.kt"))
        assertTrue(globMatches("0?-*", "01-a.kt"))
        assertFalse(globMatches("0?-*", "talk/01-a.kt"))
        assertTrue(globMatches("*v1.2*", "demo-v1.2.kt"))
        assertFalse(globMatches("*v1.2*", "demo-v1x2.kt"))
        assertTrue(globMatches("*(draft)*", "a(draft).kt"))
        assertTrue(globMatches("*[wip]*", "a[wip].kt"))
        assertFalse(globMatches("", "a.kt"))
    }

    @Test
    fun `targets mirror subdirectories and disambiguate clashes`() {
        val dir = Path.of("/out")
        assertEquals(
            mapOf(
                "java/01-hello.java" to dir.resolve("java/01-hello.java.mp4"),
                "java/01-hello.kt" to dir.resolve("java/01-hello.kt.mp4"),
                "Hello.kt" to dir.resolve("Hello.kt.mp4"),
                "hello.kt" to dir.resolve("hello.kt.mp4"),
                "02-other.sql" to dir.resolve("02-other.mp4"),
            ),
            batchTargets(
                listOf("java/01-hello.java", "java/01-hello.kt", "Hello.kt", "hello.kt", "02-other.sql"),
                dir, VideoFormat.MP4,
            ),
        )
        assertEquals(dir.resolve("a.gif"), batchTargets(listOf("a.kt"), dir, VideoFormat.GIF)["a.kt"])
    }
}
