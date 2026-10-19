package com.br.resonance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testCompareSemanticVersions() {
        // v1 > v2
        assertTrue(AppUpdateManager.compareSemanticVersions("1.0.1", "1.0.0") > 0)
        assertTrue(AppUpdateManager.compareSemanticVersions("1.1.0", "1.0.9") > 0)
        assertTrue(AppUpdateManager.compareSemanticVersions("2.0.0", "1.9.9") > 0)
        assertTrue(AppUpdateManager.compareSemanticVersions("v1.0.1", "1.0.0") > 0)

        // v1 == v2
        assertEquals(0, AppUpdateManager.compareSemanticVersions("1.0.0", "1.0.0"))
        assertEquals(0, AppUpdateManager.compareSemanticVersions("v1.0.0", "1.0.0"))

        // v1 < v2
        assertTrue(AppUpdateManager.compareSemanticVersions("1.0.0", "1.0.1") < 0)
        assertTrue(AppUpdateManager.compareSemanticVersions("0.9.9", "1.0.0") < 0)
    }

    @Test
    fun testIsNewerVersionWithExplicitVersionCode() {
        val releaseNewerCode = ReleaseInfo(
            tagName = "v1.0.0",
            versionName = "1.0.0",
            versionCode = 2,
            name = "Resonance Update",
            releaseNotes = "versionCode: 2",
            htmlUrl = "https://github.com/1ambharath/resonance/releases/tag/v1.0.0",
            apkDownloadUrl = null
        )
        // Explicit versionCode 2 > 1 => Newer
        assertTrue(AppUpdateManager.isNewerVersion(releaseNewerCode, currentVersionCode = 1, currentVersionName = "1.0.0"))

        val releaseSameCode = releaseNewerCode.copy(versionCode = 1)
        assertFalse(AppUpdateManager.isNewerVersion(releaseSameCode, currentVersionCode = 1, currentVersionName = "1.0.0"))

        val releaseOlderCode = releaseNewerCode.copy(versionCode = 1)
        assertFalse(AppUpdateManager.isNewerVersion(releaseOlderCode, currentVersionCode = 2, currentVersionName = "1.0.0"))
    }

    @Test
    fun testIsNewerVersionWithSemanticFallback() {
        val releaseNoCode = ReleaseInfo(
            tagName = "v1.0.1",
            versionName = "1.0.1",
            versionCode = 0,
            name = "Resonance v1.0.1",
            releaseNotes = "Bug fixes and improvements",
            htmlUrl = "https://github.com/1ambharath/resonance/releases/tag/v1.0.1",
            apkDownloadUrl = "https://github.com/1ambharath/resonance/releases/download/v1.0.1/Resonance.apk"
        )
        // Semantic 1.0.1 > 1.0.0
        assertTrue(AppUpdateManager.isNewerVersion(releaseNoCode, currentVersionCode = 1, currentVersionName = "1.0.0"))

        // Semantic 1.0.0 is not newer than 1.0.0
        val releaseCurrent = releaseNoCode.copy(tagName = "v1.0.0", versionName = "1.0.0")
        assertFalse(AppUpdateManager.isNewerVersion(releaseCurrent, currentVersionCode = 1, currentVersionName = "1.0.0"))
    }

    @Test
    fun testDraftAndPrereleasesIgnored() {
        val draftJson = """
            {
                "tag_name": "v1.0.2",
                "name": "Resonance Draft",
                "draft": true,
                "prerelease": false,
                "body": "Draft notes"
            }
        """.trimIndent()
        assertNull(AppUpdateManager.parseReleaseJson(draftJson))

        val prereleaseJson = """
            {
                "tag_name": "v1.0.2-beta",
                "name": "Resonance Beta",
                "draft": false,
                "prerelease": true,
                "body": "Beta notes"
            }
        """.trimIndent()
        assertNull(AppUpdateManager.parseReleaseJson(prereleaseJson))
    }

    @Test
    fun testValidStableReleaseParsed() {
        val validJson = """
            {
                "tag_name": "v1.0.1",
                "name": "Resonance v1.0.1 - Latency Fixes",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/1ambharath/resonance/releases/tag/v1.0.1",
                "body": "versionCode: 2\n\n- Fixed audio buffer sync\n- Improved discovery speed",
                "assets": [
                    {
                        "name": "Resonance-v1.0.1.apk",
                        "browser_download_url": "https://github.com/1ambharath/resonance/releases/download/v1.0.1/Resonance-v1.0.1.apk"
                    }
                ]
            }
        """.trimIndent()

        val parsed = AppUpdateManager.parseReleaseJson(validJson)
        assertNotNull(parsed)
        assertEquals("v1.0.1", parsed!!.tagName)
        assertEquals("1.0.1", parsed.versionName)
        assertEquals(2, parsed.versionCode)
        assertEquals("Resonance v1.0.1 - Latency Fixes", parsed.name)
        assertEquals("https://github.com/1ambharath/resonance/releases/download/v1.0.1/Resonance-v1.0.1.apk", parsed.apkDownloadUrl)
    }

    @Test
    fun testMalformedJsonReturnsNull() {
        val malformedJson = "{ not a valid json"
        assertNull(AppUpdateManager.parseReleaseJson(malformedJson))
    }
}
