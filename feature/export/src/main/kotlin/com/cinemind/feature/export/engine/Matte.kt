package com.cinemind.feature.export.engine

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A subject cutout ("matte") for one media file: a low-resolution 8-bit mask per sampled frame
 * (255 = subject, 0 = background), made on the phone by the AI cutout and shared by preview and
 * export. Stored next to the app's data, keyed by asset, so every clip of that file reuses it.
 *
 * File layout (little endian): magic "CMMT", version, width, height, frame count, then per frame
 * its source time (µs), offset and length in an index at the end; frames are deflate-compressed.
 */
object MatteStore {
    private const val DIR = "mattes"

    fun file(context: Context, assetId: String): File = File(File(context.filesDir, DIR).apply { mkdirs() }, "$assetId.cmm")

    fun existing(context: Context, assetId: String): File? = file(context, assetId).takeIf { it.isFile && it.length() > HEADER }

    fun delete(context: Context, assetId: String) {
        file(context, assetId).delete()
    }

    internal const val MAGIC = 0x544D4D43 // "CMMT"
    internal const val VERSION = 1
    internal const val HEADER = 24
}

/** Writes a matte frame by frame; [finish] completes the index. Delete the file if not finished. */
class MatteWriter(private val target: File, val width: Int, val height: Int) : Closeable {
    private val tmp = File(target.path + ".part")
    private val raf = RandomAccessFile(tmp, "rw").apply { setLength(0) }
    private val deflater = Deflater(Deflater.BEST_SPEED)
    private val times = ArrayList<Long>()
    private val offsets = ArrayList<Long>()
    private val lengths = ArrayList<Int>()
    private val buf = ByteArray(width * height + 1024)

    init {
        raf.write(ByteArray(MatteStore.HEADER))
    }

    fun add(sourceUs: Long, mask: ByteArray) {
        require(mask.size == width * height) { "mask size" }
        deflater.reset()
        deflater.setInput(mask)
        deflater.finish()
        val n = deflater.deflate(buf)
        times += sourceUs
        offsets += raf.filePointer
        lengths += n
        raf.write(buf, 0, n)
    }

    fun finish() {
        val indexAt = raf.filePointer
        for (i in times.indices) {
            raf.writeLongLe(times[i])
            raf.writeLongLe(offsets[i])
            raf.writeIntLe(lengths[i])
        }
        raf.seek(0)
        raf.writeIntLe(MatteStore.MAGIC)
        raf.writeIntLe(MatteStore.VERSION)
        raf.writeIntLe(width)
        raf.writeIntLe(height)
        raf.writeIntLe(times.size)
        raf.writeIntLe(indexAt.toInt())
        raf.close()
        deflater.end()
        target.delete()
        check(tmp.renameTo(target)) { "Couldn't save the cutout" }
    }

    override fun close() {
        runCatching { raf.close() }
        deflater.end()
        tmp.delete()
    }
}

/** Reads a matte; [maskAt] gives the two frames around a source time and the blend between them. */
class MatteReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val width: Int
    val height: Int
    private val times: LongArray
    private val offsets: LongArray
    private val lengths: IntArray
    private val inflater = Inflater()
    private val cache = LinkedHashMap<Int, ByteArray>(4, 0.75f, true)

    init {
        check(raf.readIntLe() == MatteStore.MAGIC) { "Not a cutout file" }
        check(raf.readIntLe() == MatteStore.VERSION) { "Unknown cutout version" }
        width = raf.readIntLe()
        height = raf.readIntLe()
        val n = raf.readIntLe()
        val indexAt = raf.readIntLe()
        times = LongArray(n)
        offsets = LongArray(n)
        lengths = IntArray(n)
        raf.seek(indexAt.toLong())
        for (i in 0 until n) {
            times[i] = raf.readLongLe()
            offsets[i] = raf.readLongLe()
            lengths[i] = raf.readIntLe()
        }
    }

    val frameCount: Int get() = times.size

    data class Sample(val a: ByteArray, val b: ByteArray, val mix: Float, val indexA: Int, val indexB: Int)

    fun maskAt(sourceUs: Long): Sample? {
        if (times.isEmpty()) return null
        var hi = times.binarySearch(sourceUs).let { if (it >= 0) it else -it - 1 }
        if (hi >= times.size) hi = times.size - 1
        val lo = if (hi > 0 && times[hi] > sourceUs) hi - 1 else hi
        val span = (times[hi] - times[lo]).toFloat()
        val mix = if (span <= 0f) 0f else ((sourceUs - times[lo]) / span).coerceIn(0f, 1f)
        return Sample(frame(lo), frame(hi), mix, lo, hi)
    }

    private fun frame(i: Int): ByteArray = cache[i] ?: run {
        val packed = ByteArray(lengths[i])
        raf.seek(offsets[i])
        raf.readFully(packed)
        inflater.reset()
        inflater.setInput(packed)
        val out = ByteArray(width * height)
        var got = 0
        while (got < out.size && !inflater.finished()) {
            val n = inflater.inflate(out, got, out.size - got)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            got += n
        }
        if (cache.size >= 4) cache.remove(cache.keys.first())
        cache[i] = out
        out
    }

    override fun close() {
        runCatching { raf.close() }
        inflater.end()
    }
}

private fun RandomAccessFile.writeIntLe(v: Int) {
    write(v and 0xFF); write((v ushr 8) and 0xFF); write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
}

private fun RandomAccessFile.writeLongLe(v: Long) {
    writeIntLe((v and 0xFFFFFFFFL).toInt())
    writeIntLe((v ushr 32).toInt())
}

private fun RandomAccessFile.readIntLe(): Int {
    val b0 = read(); val b1 = read(); val b2 = read(); val b3 = read()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

private fun RandomAccessFile.readLongLe(): Long {
    val lo = readIntLe().toLong() and 0xFFFFFFFFL
    val hi = readIntLe().toLong()
    return lo or (hi shl 32)
}
