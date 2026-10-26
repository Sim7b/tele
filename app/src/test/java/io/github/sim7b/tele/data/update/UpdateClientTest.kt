package io.github.sim7b.tele.data.update

import io.github.sim7b.tele.data.UserFacingException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateClientTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val github = MockWebServer()

    @Before
    fun setUp() = github.start()

    @After
    fun tearDown() = github.close()

    private val prefix = "https://github.com/Sim7b/tele/releases/download/"

    private fun releaseJson(
        tag: String = "v0.2.1",
        url: String = "${prefix}v0.2.1/tele-v0.2.1.apk",
        size: Long = 1234,
        prerelease: Boolean = false
    ) = """
        {"tag_name": "$tag", "draft": false, "prerelease": $prerelease,
         "body": "## [0.2.1](https://github.com/Sim7b/tele/compare/v0.2.0...v0.2.1) (2026-10-26)\n\n### Features\n\n* **branding:** rename the app to Télé ([01f3f7f](https://github.com/Sim7b/tele/commit/01f3f7f))\n\n### Bug Fixes\n\n* **playback:** play chosen streams through the [proxy](https://example.org) ([73333a5](https://github.com/Sim7b/tele/commit/73333a5))",
         "assets": [
           {"name": "notes.txt", "browser_download_url": "${prefix}v0.2.1/notes.txt", "size": 10},
           {"name": "tele-v0.2.1.apk", "browser_download_url": "$url", "size": $size}
         ]}
    """.trimIndent()

    @Test
    fun `compares semantic versions`() {
        assertTrue(AppVersion.isNewer("0.2.1", "0.2.0"))
        assertTrue(AppVersion.isNewer("v0.10.0", "0.9.9"))
        assertFalse(AppVersion.isNewer("0.2.0", "0.2.0"))
        assertFalse(AppVersion.isNewer("0.1.9", "0.2.0"))
        // Invalid versions never offer an update
        assertFalse(AppVersion.isNewer("latest", "0.2.0"))
        assertFalse(AppVersion.isNewer("0.3.0", "dev"))
        assertTrue(AppVersion.isNewer("0.3.0", "0.2.0-rc.1"))
    }

    @Test
    fun `parses the release APK and readable highlights`() {
        val release = UpdateClient.parseRelease(releaseJson())!!

        assertEquals("0.2.1", release.version)
        assertEquals("${prefix}v0.2.1/tele-v0.2.1.apk", release.apkUrl)
        assertEquals(1234L, release.apkSize)
        assertEquals(
            listOf("branding: rename the app to Télé", "playback: play chosen streams through the proxy"),
            release.highlights
        )
    }

    @Test
    fun `ignores pre-releases, foreign download URLs and oversized APKs`() {
        assertNull(UpdateClient.parseRelease(releaseJson(prerelease = true)))
        assertNull(UpdateClient.parseRelease(releaseJson(url = "https://evil.example/tele.apk")))
        assertNull(UpdateClient.parseRelease(releaseJson(url = "https://github.com/someone/tele/releases/download/x.apk")))
        assertNull(UpdateClient.parseRelease(releaseJson(size = 500L * 1024 * 1024)))
        assertNull(UpdateClient.parseRelease(releaseJson(tag = "nightly")))
    }

    @Test
    fun `downloads the APK with its progress`() = runBlocking {
        val apk = ByteArray(200_000) { it.toByte() }
        github.enqueue(MockResponse.Builder().body(okio.Buffer().write(apk)).build())
        val release = AppRelease("0.2.1", github.url("/tele.apk").toString(), apk.size.toLong(), emptyList())
        val target = folder.root.resolve("updates/update.apk")
        var lastProgress = 0f

        UpdateClient(downloadPrefix = "").download(release, target) { lastProgress = it }

        assertArrayEquals(apk, target.readBytes())
        assertEquals(1f, lastProgress)
    }

    @Test
    fun `rejects a download that does not match the announced size`() = runBlocking {
        github.enqueue(MockResponse.Builder().body("too short").build())
        val release = AppRelease("0.2.1", github.url("/tele.apk").toString(), 1234, emptyList())

        try {
            UpdateClient().download(release, folder.root.resolve("update.apk")) {}
            fail("An incomplete download must fail")
        } catch (_: UserFacingException) {
        }
    }

    @Test
    fun `reads the latest release from the API`() = runBlocking {
        github.enqueue(MockResponse.Builder().body(releaseJson()).build())

        val release = UpdateClient(latestReleaseUrl = github.url("/latest").toString()).latestRelease()

        assertEquals("0.2.1", release?.version)
        assertEquals("application/vnd.github+json", github.takeRequest().headers["Accept"])
    }
}
