package dev.kaorios.engine.dex

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Zip writer that starts every entry's data on an [alignment]-byte boundary.
 *
 * ART mmaps a dex straight out of the jar that holds it, and `dex2oat` refuses to do that unless
 * the dex data begins on a 4-byte boundary:
 * `Can't mmap dex file …!classes.dex directly; please zipalign to 4 bytes`. It falls back to
 * copying the dex into memory instead, which costs boot time on a classpath as large as
 * `framework.jar`. `java.util.zip.ZipOutputStream` cannot express that padding — it decides the
 * local header layout itself — so the archive is written here instead, by hand.
 *
 * Padding lives in the header's *extra field*: it is a sequence of 2-byte tag / 2-byte length /
 * payload blocks, so any length at or above 4 is legal. When the data would already be aligned
 * no extra field is written at all.
 *
 * Only what these archives contain is supported: no data descriptors, no zip64, no archive
 * comment. `framework.jar` is 51 MB with 100 entries, far from every limit.
 */
class AlignedZipWriter(
    private val output: OutputStream,
    private val alignment: Int = DEFAULT_ALIGNMENT,
) : AutoCloseable {

    private class Pending(
        val name: ByteArray,
        val flags: Int,
        val method: Int,
        val dosTime: Int,
        val dosDate: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val extra: ByteArray,
        val localOffset: Long,
    )

    private val out = BufferedOutputStream(output, 1 shl 16)
    private val pending = ArrayList<Pending>()
    private var position = 0L

    init {
        require(alignment >= 1 && alignment and (alignment - 1) == 0) {
            "alignment must be a power of two, was $alignment"
        }
    }

    /**
     * Appends [data] under [name], deflating it unless it is already stored uncompressed.
     *
     * @param time modification time in epoch milliseconds, preserved like `ZipEntry.time`.
     */
    fun put(name: String, data: ByteArray, stored: Boolean, time: Long) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(pending.size < MAX_ENTRIES) { "zip holds more than $MAX_ENTRIES entries" }

        val payload = if (stored) data else deflate(data)
        if (payload.size > Int.MAX_VALUE || data.size > Int.MAX_VALUE) {
            throw IllegalStateException("$name is larger than a zip entry may be")
        }

        val (dosTime, dosDate) = dosDateTime(time)
        val flags = if (name.any { it.code > 127 }) UTF8_FLAG else 0
        val method = if (stored) METHOD_STORED else METHOD_DEFLATED
        val headerStart = position
        val extraLen = paddingFor(headerStart + LOCAL_HEADER_SIZE + nameBytes.size)
        val extra = ByteArray(extraLen)
        if (extraLen > 0) {
            // tag 0x0000 (padding), payload fills everything after the 4-byte tag/length prefix.
            extra[0] = 0
            extra[1] = 0
            val payloadLen = extraLen - 4
            extra[2] = (payloadLen and 0xff).toByte()
            extra[3] = ((payloadLen shr 8) and 0xff).toByte()
        }

        val crc = CRC32().apply { update(data) }.value
        pending += Pending(
            name = nameBytes,
            flags = flags,
            method = method,
            dosTime = dosTime,
            dosDate = dosDate,
            crc = crc,
            compressedSize = payload.size.toLong(),
            size = data.size.toLong(),
            extra = extra,
            localOffset = headerStart,
        )

        u32(LOCAL_SIGNATURE)
        u16(VERSION_NEEDED)
        u16(flags)
        u16(method)
        u16(dosTime)
        u16(dosDate)
        u32(crc)
        u32(payload.size.toLong())
        u32(data.size.toLong())
        u16(nameBytes.size)
        u16(extra.size)
        writeFully(nameBytes)
        writeFully(extra)
        writeFully(payload)
    }

    /** Writes the central directory and end-of-central-directory record. */
    override fun close() {
        val centralOffset = position
        for (entry in pending) {
            u32(CENTRAL_SIGNATURE)
            u16(VERSION_MADE_BY)
            u16(VERSION_NEEDED)
            u16(entry.flags)
            u16(entry.method)
            u16(entry.dosTime)
            u16(entry.dosDate)
            u32(entry.crc)
            u32(entry.compressedSize)
            u32(entry.size)
            u16(entry.name.size)
            u16(entry.extra.size)
            u16(0) // archive comment
            u16(0) // disk number
            u16(0) // internal attributes
            u32(0) // external attributes
            u32(entry.localOffset)
            writeFully(entry.name)
            writeFully(entry.extra)
        }
        val centralSize = position - centralOffset
        require(centralOffset <= 0xffffffffL && centralSize <= 0xffffffffL) {
            "archive is too large for a non-zip64 zip"
        }
        u32(END_SIGNATURE)
        u16(0) // this disk
        u16(0) // disk with central directory
        u16(pending.size)
        u16(pending.size)
        u32(centralSize)
        u32(centralOffset)
        u16(0) // archive comment length
        out.flush()
        out.close()
        pending.clear()
    }

    /** Bytes of padding that put a data start at [current] on the alignment boundary. */
    private fun paddingFor(current: Long): Int {
        val missing = ((alignment - (current % alignment)) % alignment).toInt()
        if (missing == 0) return 0
        // A padding field must be at least its 4-byte tag/length prefix; alignment is a power of
        // two, so adding another whole block keeps the data start on the boundary.
        return missing + alignment
    }

    private fun deflate(data: ByteArray): ByteArray {
        val sink = ByteArrayOutputStream(data.size / 2 + 32)
        // nowrap: a zip entry holds raw deflate bytes, not a zlib stream.
        DeflaterOutputStream(sink, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { stream ->
            stream.write(data)
            stream.finish()
        }
        return sink.toByteArray()
    }

    private fun dosDateTime(timeMillis: Long): Pair<Int, Int> {
        val calendar = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val year = calendar.get(Calendar.YEAR).coerceIn(1980, 2107)
        val date = ((year - 1980) shl 9) or
            ((calendar.get(Calendar.MONTH) + 1) shl 5) or
            calendar.get(Calendar.DAY_OF_MONTH)
        val time = (calendar.get(Calendar.HOUR_OF_DAY) shl 11) or
            (calendar.get(Calendar.MINUTE) shl 5) or
            (calendar.get(Calendar.SECOND) / 2)
        return time to date
    }

    private fun u16(value: Int) {
        out.write(value)
        out.write(value shr 8)
        position += 2
    }

    private fun u32(value: Long) {
        out.write(value.toInt())
        out.write((value shr 8).toInt())
        out.write((value shr 16).toInt())
        out.write((value shr 24).toInt())
        position += 4
    }

    private fun writeFully(bytes: ByteArray) {
        out.write(bytes)
        position += bytes.size
    }

    companion object {
        const val DEFAULT_ALIGNMENT = 4

        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val END_SIGNATURE = 0x06054b50L
        private const val LOCAL_HEADER_SIZE = 30L
        private const val VERSION_NEEDED = 20
        private const val VERSION_MADE_BY = 20
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val UTF8_FLAG = 0x0800
        private const val MAX_ENTRIES = 0xffff

        /**
         * Walks the local headers of [archive] and returns the entries whose data is not aligned.
         *
         * Deliberately independent of the writer: it parses the file back the way `libziparchive`
         * does, so a mistake in the writer's own bookkeeping cannot hide behind the same mistake
         * in the check.
         */
        fun misalignedEntries(archive: File, alignment: Int = DEFAULT_ALIGNMENT): List<String> {
            val bad = mutableListOf<String>()
            RandomAccessFile(archive, "r").use { file ->
                val header = ByteArray(30)
                while (file.filePointer + 30 <= file.length()) {
                    file.readFully(header)
                    if (unsigned32(header, 0) != LOCAL_SIGNATURE) break
                    val flags = unsigned16(header, 6)
                    // A data descriptor moves the sizes out of the local header, so the walk
                    // would land in the wrong place. This writer never emits one.
                    if (flags and 0x08 != 0) break
                    val nameLen = unsigned16(header, 26)
                    val extraLen = unsigned16(header, 28)
                    val compressedSize = unsigned32(header, 18)
                    val nameBytes = ByteArray(nameLen)
                    file.readFully(nameBytes)
                    file.skipBytes(extraLen)
                    val dataStart = file.filePointer
                    if (dataStart % alignment != 0L) {
                        bad += String(nameBytes, Charsets.UTF_8)
                    }
                    file.skipBytes(compressedSize.toInt())
                }
            }
            return bad
        }

        private fun unsigned16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun unsigned32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }
}
