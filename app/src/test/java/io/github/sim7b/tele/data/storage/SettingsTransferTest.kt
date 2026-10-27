package io.github.sim7b.tele.data.storage

import android.app.Application
import android.content.Context
import io.github.sim7b.tele.data.bouquet.BouquetPreference
import io.github.sim7b.tele.data.model.CategoryMode
import io.github.sim7b.tele.data.model.PreviewDelay
import io.github.sim7b.tele.data.model.SourceConfig
import io.github.sim7b.tele.data.model.SourceType
import io.github.sim7b.tele.data.transfer.TransferCrypto
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import javax.crypto.KeyGenerator

/** Configuration sent to another TV: exported, encrypted with the code, then imported. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsTransferTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SettingsRepository.secretsFactory = { SecretBox({ key }, cbcTransformation = "AES/CBC/PKCS5Padding") }
    }

    @After
    fun tearDown() {
        SettingsRepository.secretsFactory = { SecretBox.androidKeystore() }
    }

    private fun clearPreferences() =
        app.getSharedPreferences("iptv_dispatcharr_prefs", Context.MODE_PRIVATE).edit().clear().commit()

    @Test
    fun `the whole configuration goes to the other TV`() {
        clearPreferences()
        val sender = SettingsRepository(app)
        sender.saveOrUpdateConfig(SourceConfig(id = "disp", name = "Maison", serverUrl = "https://tv.example", apiKey = "s3cret"))
        sender.saveOrUpdateConfig(SourceConfig(id = "m3u", name = "Liste", serverUrl = "http://list.example/a.m3u", apiKey = "", type = SourceType.M3U))
        sender.toggleFavoriteChannel("tf1")
        sender.setGroupHidden("Radio", true)
        sender.renameCategory(setOf("FR| SPORTS"), "Sport")
        sender.setCategoryOrder(listOf("Sport", "TNT"))
        sender.setCategoryMode(CategoryMode.SEPARATE)
        sender.setBouquet(BouquetPreference.NONE)
        sender.setPreviewDelay(PreviewDelay.FIVE_SECONDS)
        val sealed = TransferCrypto.seal(sender.exportSettings().toString(), "12345678")

        clearPreferences()
        val receiver = SettingsRepository(app)
        receiver.saveOrUpdateConfig(SourceConfig(id = "local", name = "Locale", serverUrl = "http://local.example/x.m3u", apiKey = "", type = SourceType.M3U))
        receiver.importSettings(JSONObject(TransferCrypto.open(sealed, "1234 5678")))

        assertEquals(listOf("local", "disp", "m3u"), receiver.configs.value.map { it.id })
        assertEquals("s3cret", receiver.configs.value.first { it.id == "disp" }.apiKey)
        assertEquals(listOf("tf1"), receiver.favoriteIds.value)
        assertEquals(setOf("Radio"), receiver.hiddenGroups.value)
        assertEquals(mapOf("FR| SPORTS" to "Sport"), receiver.categoryNames.value)
        assertEquals(listOf("Sport", "TNT"), receiver.categoryOrder.value)
        assertEquals(CategoryMode.SEPARATE, receiver.categoryMode.value)
        assertEquals(BouquetPreference.NONE, receiver.bouquet.value)
        assertEquals(PreviewDelay.FIVE_SECONDS, receiver.previewDelay.value)
        assertTrue(receiver.guideSeen.value)
    }
}
