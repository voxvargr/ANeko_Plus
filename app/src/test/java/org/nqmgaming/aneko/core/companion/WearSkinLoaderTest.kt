package org.nqmgaming.aneko.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WearSkinLoaderTest {
    @Test
    fun `sampling bounds largest decoded dimension without enlarging small frames`() {
        assertEquals(1, WearSkinLoader.sampleSize(32, 48, 256))
        assertEquals(16, WearSkinLoader.sampleSize(4096, 2048, 256))
        assertEquals(8, WearSkinLoader.sampleSize(1025, 20, 256))
        val sample = WearSkinLoader.sampleSize(Int.MAX_VALUE, 1, 1)
        assertTrue((Int.MAX_VALUE.toLong() + sample - 1) / sample <= 1)
    }

    @Test
    fun `frame paths stay within the selected skin package`() {
        val root = Files.createTempDirectory("wear-skin-path-test").toFile()
        try {
            assertEquals(root.resolve("frames/idle.png").canonicalFile,
                WearSkinLoader.confined(root, "frames/idle.png"))
            var rejected = false
            try { WearSkinLoader.confined(root, "../other-skin/secret.png") }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        } finally { root.delete() }
    }
}
