package com.mali.nbeta.data.reader

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Manual probe: -Dprobe.html=/path/page.html prints what the extractor makes of a real page. */
class LocalPageProbe {
    @Test fun probe() {
        val path = System.getProperty("probe.html")
        assumeTrue(path != null)
        val a = Readability.extract(File(path!!).readText(), System.getProperty("probe.url") ?: "https://example.com/")
        if (a == null) { println("PROBE: null"); return }
        println("PROBE title=${a.title} words=${a.words} blocks=${a.blocks.size} rtl=${a.rtl}")
        a.blocks.take(12).forEach { println("PROBE  " + it.toString().take(140)) }
    }
}
