package io.nisfeb.talon.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestTest {

    private val sample = """
        {
          "versionCode": 21,
          "versionName": "0.5.0",
          "url": "https://github.com/sneagan/talon/releases/download/v0.5.0/talon-0.5.0.apk",
          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "minSdk": 26,
          "changelog": "Polls fixed; daily digest weather is now Fahrenheit.",
          "mandatory": false
        }
    """.trimIndent()

    @Test fun `parses well-formed manifest`() {
        val m = UpdateManifest.parse(sample)!!
        assertEquals(21, m.versionCode)
        assertEquals("0.5.0", m.versionName)
        assertEquals(
            "https://github.com/sneagan/talon/releases/download/v0.5.0/talon-0.5.0.apk",
            m.url,
        )
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            m.sha256,
        )
        assertEquals(26, m.minSdk)
        assertEquals(false, m.mandatory)
    }

    @Test fun `mandatory defaults to false when missing`() {
        val noMandatory = sample.replace(",\n          \"mandatory\": false", "")
        val m = UpdateManifest.parse(noMandatory)!!
        assertEquals(false, m.mandatory)
    }

    @Test fun `rejects manifest with non-https url`() {
        val httpUrl = sample.replace("https://", "http://")
        assertNull(UpdateManifest.parse(httpUrl))
    }

    @Test fun `rejects manifest with malformed sha256`() {
        val badHash = sample.replace(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "deadbeef",
        )
        assertNull(UpdateManifest.parse(badHash))
    }

    @Test fun `rejects manifest missing a required field`() {
        // Drop versionCode — required.
        val noVersionCode = sample.replace("\"versionCode\": 21,", "")
        assertNull(UpdateManifest.parse(noVersionCode))
        // Drop versionName — required.
        val noVersionName = sample.replace("\"versionName\": \"0.5.0\",", "")
        assertNull(UpdateManifest.parse(noVersionName))
        // Drop url — required.
        val noUrl = sample.replace(
            "\"url\": \"https://github.com/sneagan/talon/releases/download/v0.5.0/talon-0.5.0.apk\",",
            "",
        )
        assertNull(UpdateManifest.parse(noUrl))
        // Drop sha256 — required.
        val noSha = sample.replace(
            "\"sha256\": \"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",",
            "",
        )
        assertNull(UpdateManifest.parse(noSha))
    }

    @Test fun `lowercases sha256 on parse`() {
        val upper = sample.replace(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
        )
        val m = UpdateManifest.parse(upper)!!
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            m.sha256,
        )
    }

    @Test fun `defaults minSdk and changelog when missing`() {
        val stripped = sample
            .replace("\"minSdk\": 26,\n  ", "")
            .replace(
                "\"changelog\": \"Polls fixed; daily digest weather is now Fahrenheit.\",\n  ",
                "",
            )
        val m = UpdateManifest.parse(stripped)!!
        assertEquals(26, m.minSdk)
        assertEquals("", m.changelog)
    }

    @Test fun `rejects valid JSON that isn't an object`() {
        assertNull(UpdateManifest.parse("[]"))
        assertNull(UpdateManifest.parse("\"just a string\""))
        assertNull(UpdateManifest.parse("42"))
    }

    @Test fun `desktop installers are read when listed, and absent when not`() {
        assertEquals(emptyMap<String, UpdateAsset>(), UpdateManifest.parse(sample)!!.desktop)
        val withDesktop = sample.replace(
            "\"mandatory\": false",
            "\"mandatory\": false, \"desktop\": {" +
                "\"appimage\": {\"url\": \"https://x/Talon.AppImage\", \"sha256\": \"" + "ab".repeat(32) + "\"}," +
                "\"deb\": {\"url\": \"http://x/t.deb\", \"sha256\": \"" + "ab".repeat(32) + "\"}," +
                "\"msi\": {\"url\": \"https://x/t.msi\", \"sha256\": \"nope\"}}",
        )
        val d = UpdateManifest.parse(withDesktop)!!.desktop
        assertEquals("http and a bad hash are dropped, not fatal", listOf("appimage"), d.keys.toList())
        assertEquals("https://x/Talon.AppImage", d.getValue("appimage").url)
    }

    @Test fun `a device takes the split for its own ABI, and the universal APK otherwise`() {
        val h = "ab".repeat(32)
        val withSplits = sample.replace(
            "\"mandatory\": false",
            "\"mandatory\": false, \"android\": {" +
                "\"arm64-v8a\": {\"url\": \"https://x/t-arm64-v8a.apk\", \"sha256\": \"$h\"}," +
                "\"armeabi-v7a\": {\"url\": \"https://x/t-armeabi-v7a.apk\", \"sha256\": \"$h\"}}",
        )
        val m = UpdateManifest.parse(withSplits)!!
        // A 64-bit phone lists its 64-bit ABI first.
        assertEquals("https://x/t-arm64-v8a.apk", m.androidAssetFor(listOf("arm64-v8a", "armeabi-v7a")).url)
        assertEquals("https://x/t-armeabi-v7a.apk", m.androidAssetFor(listOf("armeabi-v7a")).url)
        assertEquals("no split fits: the universal one", m.url, m.androidAssetFor(listOf("x86")).url)
        assertEquals("an old manifest", m.url, UpdateManifest.parse(sample)!!.androidAssetFor(listOf("arm64-v8a")).url)
    }
}
