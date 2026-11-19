package com.cinemind.feature.export.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MatteFileTest {

    @Test
    fun framesRoundTripAndBlendByTime() {
        val dir = kotlin.io.path.createTempDirectory("matte").toFile()
        val file = File(dir, "a.cmm")
        val w = 5
        val h = 3
        val f0 = ByteArray(w * h) { 0 }
        val f1 = ByteArray(w * h) { (it * 10).toByte() }
        val f2 = ByteArray(w * h) { -1 }
        MatteWriter(file, w, h).apply {
            add(0, f0)
            add(100_000, f1)
            add(200_000, f2)
            finish()
        }
        assertTrue(file.isFile)
        assertFalse(File(file.path + ".part").exists())

        MatteReader(file).use { r ->
            assertEquals(w, r.width)
            assertEquals(h, r.height)
            assertEquals(3, r.frameCount)
            val mid = r.maskAt(150_000)!!
            assertArrayEquals(f1, mid.a)
            assertArrayEquals(f2, mid.b)
            assertEquals(0.5f, mid.mix, 1e-4f)
            val exact = r.maskAt(100_000)!!
            assertArrayEquals(f1, exact.a)
            assertEquals(0f, exact.mix, 1e-4f)
            // Before the first and after the last sample: hold the edge frame.
            assertArrayEquals(f0, r.maskAt(-5)!!.a)
            assertArrayEquals(f2, r.maskAt(9_000_000)!!.b)
        }
        dir.deleteRecursively()
    }

    @Test
    fun anUnfinishedWriteLeavesNoFile() {
        val dir = kotlin.io.path.createTempDirectory("matte").toFile()
        val file = File(dir, "b.cmm")
        MatteWriter(file, 2, 2).use { it.add(0, ByteArray(4)) }
        assertFalse(file.exists())
        assertFalse(File(file.path + ".part").exists())
        dir.deleteRecursively()
    }
}
