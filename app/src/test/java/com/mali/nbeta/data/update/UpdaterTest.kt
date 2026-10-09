package com.mali.nbeta.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTest {
    @Test fun parsesLatestRelease() {
        val json = """{"tag_name":"v1.2","html_url":"https://github.com/x/y/releases/tag/v1.2","body":"Notes",
            "assets":[{"name":"checksums.txt","browser_download_url":"https://x/c"},
                      {"name":"nbeta-3.apk","browser_download_url":"https://x/nbeta-3.apk"}]}"""
        val r = Updater.parse(json)!!
        assertEquals("1.2", r.versionName)
        assertEquals(3, r.versionCode)
        assertEquals("https://x/nbeta-3.apk", r.apkUrl)
        assertEquals("Notes", r.notes)
    }

    @Test fun ignoresReleasesWithoutApk() {
        assertNull(Updater.parse("""{"tag_name":"v9","assets":[{"name":"source.zip","browser_download_url":"https://x/s"}]}"""))
    }
}
