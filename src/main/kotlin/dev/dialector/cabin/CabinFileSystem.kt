package dev.dialector.cabin

import java.io.*
import java.nio.MappedByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.math.sign

data class DirectoryEntry(
    val offset: Long,
    val length: Long,
    val path: String
)

fun writeEntry(entry: DirectoryEntry, outputStream: DataOutputStream) {
    outputStream.writeLong(entry.offset)
    outputStream.writeLong(entry.length)
    outputStream.writeUTF(entry.path)
}

fun readEntry(inputStream: DataInputStream): DirectoryEntry {
    val offset = inputStream.readLong()
    val length = inputStream.readLong()
    val path = inputStream.readUTF()
    return DirectoryEntry(offset, length, path)
}

private fun entrySizeAndOffsetSort(a: DirectoryEntry, b: DirectoryEntry): Int {
    val lengthComparison = a.length - b.length
    return if (lengthComparison != 0L)
        lengthComparison.sign
    else
        (a.offset - b.offset).sign
}

class ReadOnlyCabinArchive private constructor(private val archivePath: Path) {
    private val archiveEntries: MutableList<DirectoryEntry> = readDirectory().toMutableList()
    private val files: MutableMap<String, DirectoryEntry> =
        readDirectory().associateBy(DirectoryEntry::path).toMutableMap()

    private fun getReadChannel(): FileChannel {
        return RandomAccessFile(archivePath.toFile(), "r").channel
    }

    private fun readDirectory(): List<DirectoryEntry> {
        val channel = getReadChannel()
        val size = channel.size()
        if (size == 0L) { return listOf() }
        println(size)
        channel.position(size - 12)
        val entries = mutableListOf<DirectoryEntry>()
        Channels.newInputStream(channel).use {
            DataInputStream(it).use { input ->
                val indexOffset = input.readLong()
                val indexEntries = input.readInt()
//                println("Metadata: $indexEntries entries, offset $indexOffset")
                channel.position(indexOffset)
                repeat(indexEntries) {
                    entries += readEntry(input)
                }
            }
        }
        return Collections.unmodifiableList(entries)
    }
}

/**
 * The file system for Cabin files.
 *
 * A cabin file consists primarily of a series of file data. Following this is a series of optional file
 * modifications (additions, changes, deletions). After the modifications, the archive metadata and metadata are stored.
 * The final 16 bytes in the file are two 8 byte longs representing the offset and length of the index
 *
 * Unpacked File Layout (folder ending in .cabin)
 * data:
 * File Data (repeated)
 *
 * directory:
 * Archive Directory (metadata & index)
 *  - Number of index entries (4 bytes)
 *  - Index entries (8 byte offset, 8 byte length, N byte path)
 *
 * Packed File Layout (file ending in .cabin):
 * File Data (repeated)
 * File Data Modifications (repeated)
 * Archive Directory (metadata & index)
 *  - Number of index entries (4 bytes)
 *  - Index entries (8 byte offset, 8 byte length, N byte path)
 * Offset of directory (8 bytes)
 *
 * File locking and memory sharing scheme:
 *
 * - CabinFileSystem serves as an access point, not an "open file".
 *
 * Unpacked vs Packed Cabin Files
 *
 * Unpacked Cabin files are represented by two separate OS files - the file data and the archive directory.
 *
 * Packed Cabin files are proper single-file archives.
 *
 */
class CabinFileSystem private constructor(private val archivePath: Path) : AutoCloseable {
    companion object {
        fun fromPath(archivePath: Path): CabinFileSystem {
            if (!archivePath.exists()) {
                archivePath.createFile()
            }
            return CabinFileSystem(archivePath)
        }
    }

    private val writeChannel: FileChannel by lazy { RandomAccessFile(archivePath.toString(), "rw").channel }
    private val archiveEntries: MutableList<DirectoryEntry> = readEntries().toMutableList()
    private val files: MutableMap<String, DirectoryEntry> =
        archiveEntries.associateBy(DirectoryEntry::path).toMutableMap()

    private var currentOffset = archiveEntries.lastOrNull()?.let { it.offset + it.length } ?: 0L

    private fun getReadChannel(): FileChannel {
        return RandomAccessFile(archivePath.toFile(), "r").channel
    }

    /**
     * Reads the file at the given path into a direct ByteBuffer (memory mapped)
     */
    fun readFileDirect(path: String): MappedByteBuffer {
        val file = files[path] ?: throw RuntimeException("File not found: $path")
        return getReadChannel().map(FileChannel.MapMode.READ_ONLY, file.offset, file.length)
    }

    /**
     * Reads a series of files into direct ByteBuffers
     */
    fun readFilesDirect(vararg paths: String): Map<String, MappedByteBuffer> =
        paths.associateWith { readFileDirect(it) }

    fun writeFileDirect(path: String, length: Long): MappedByteBuffer {
        val file = DirectoryEntry(currentOffset, length, path)
        addFile(file)
        val fileChannel = writeChannel
        val map = fileChannel.map(FileChannel.MapMode.READ_WRITE, file.offset, file.length)
        currentOffset += length
        return map
    }

    fun newInputStream(path: String): InputStream {
        val file = files[path] ?: throw RuntimeException("File not found: $path")
        val channel = getReadChannel()
        channel.position(file.offset)
        println(file.offset)
        return Channels.newInputStream(channel)
    }

    fun directInputStream(path: String): InputStream {
        return CabinFileInputStream(readFileDirect(path), path)
    }

    fun newOutputStream(path: String): OutputStream {
        val channel = writeChannel
        channel.position(currentOffset)
        val os = Channels.newOutputStream(channel)
        return CabinOutputStream(path, os)
    }

    fun directOutputStream(path: String, length: Long): OutputStream {
        val os = CabinFileOutputStream(writeFileDirect(path, length), path)
        return CabinOutputStream(path, os)
    }

    fun deleteFile(path: String) {
        val file = files[path] ?: throw FileNotFoundException("File not found: $path")
        deleteFile(file)
    }

    override fun close() {
        writeEntries()
    }

    private fun writeEntries() {
        // Write descriptors
        val channel = writeChannel
        channel.position(currentOffset)
        Channels.newOutputStream(channel).use {
            DataOutputStream(it).use { out ->
                archiveEntries.forEach { entry -> writeEntry(entry, out) }
                out.writeLong(currentOffset)
                out.writeInt(archiveEntries.size)
            }
        }
    }

    private fun readEntries(): List<DirectoryEntry> {
        val channel = getReadChannel()
        val size = channel.size()
        if (size == 0L) { return mutableListOf() }
        println(size)
        channel.position(size - 12)
        val entries = mutableListOf<DirectoryEntry>()
        Channels.newInputStream(channel).use {
            DataInputStream(it).use { input ->
                val indexOffset = input.readLong()
                val indexEntries = input.readInt()
//                println("Metadata: $indexEntries entries, offset $indexOffset")
                channel.position(indexOffset)
                repeat(indexEntries) {
                    entries += readEntry(input)
                }
            }
        }
        return entries
    }

    private fun addFile(file: DirectoryEntry) {
//        println("Adding: $file")
        val existing = files.remove(file.path)
        if (existing != null) {
            archiveEntries.remove(existing)
        }
        archiveEntries.add(file)
        files[file.path] = file
    }

    private fun deleteFile(file: DirectoryEntry) {
        archiveEntries.remove(file)
        files.remove(file.path)
    }

    private class CabinFileInputStream(private val buffer: MappedByteBuffer, private val path: String) : InputStream() {
        var closed = false
        var pos = 0

        override fun read(): Int {
            val b = ByteArray(1)
            return if (this.read(b, 0, 1) == 1) b[0].toInt() and 255 else -1
        }

        override fun read(b: ByteArray): Int =
            read(b, 0, b.size)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Input stream for $path closed")
            val remaining = buffer.remaining()
            return if (remaining < len) {
                buffer.get(b, off, remaining)
                remaining
            } else {
                buffer.get(b, off, len)
                b.size
            }
        }

        override fun close() {
            closed = true
        }

        override fun readAllBytes(): ByteArray {
            val result = ByteArray(buffer.remaining())
            read(result)
            return result
        }

        override fun readNBytes(len: Int): ByteArray {
            val remaining = buffer.remaining()
            val result = if (len > remaining) {
                ByteArray(remaining)
            } else {
                ByteArray(len)
            }
            read(result)
            return result
        }

        override fun readNBytes(b: ByteArray, off: Int, len: Int): Int =
            read(b, off, len)

        override fun skip(n: Long): Long {
            if (n > buffer.remaining()) {
                throw EOFException("Attempted to skip $n bytes but only ${buffer.remaining()} remaining.")
            }
            buffer.position(buffer.position() + n.toInt())
            return n
        }

        override fun skipNBytes(n: Long) {
            skip(n)
        }


        /**
         * If the backing buffer is loaded into memory, returns the number of bytes remaining in the stream.
         * Otherwise, this returns 0.
         *
         * NOTE: This is an estimate, see [MappedByteBuffer.isLoaded] for reference.
         */
        override fun available(): Int =
            if (buffer.isLoaded) buffer.remaining() else 0

        /**
         * Marks the current location in the buffer such that a call to [reset] will return to this location.
         *
         * The readLimit parameter has no effect.
         */
        override fun mark(readlimit: Int) {
            buffer.mark()
        }

        override fun reset() {
            buffer.reset()
        }

        override fun markSupported(): Boolean = true

        override fun transferTo(out: OutputStream): Long {
            return super.transferTo(out)
        }
    }

    private inner class CabinFileOutputStream(
        private var buffer: MappedByteBuffer?,
        private val path: String
    ) : OutputStream() {

        override fun close() {
            buffer = null
        }

        override fun flush() {
            buffer?.force()
        }

        override fun write(b: Int) {
            ifOpen {
                put(b.toByte())
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            ifOpen {
                put(b, off, len)
            }
        }

        inline fun <T> ifOpen(block: MappedByteBuffer.() -> T): T {
            // TODO should this be synchronized?
            val buf = buffer ?: throw IOException("OutputStream for $path is closed")
            return buf.block()
        }
    }

    private inner class CabinOutputStream(private val path: String, private val outputStream: OutputStream) : OutputStream() {
        private var written: Long = 0L
        private var closed: Boolean = false;

        override fun write(b: Int) {
            outputStream.write(b)
            written += 1
        }

        override fun write(b: ByteArray) {
            outputStream.write(b)
            written += b.size
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            outputStream.write(b, off, len)
            written += len
        }

        override fun close() {
            if (closed) return
            closed = true
            addFile(DirectoryEntry(currentOffset, written, path))
            currentOffset += written
            // If using Channels.newOutputStream, don't close here as it will close the channel.
            // TODO: Consolidate output stream behavior, this should handle low-level behavior as well
        }

        override fun flush() {
            outputStream.flush()
        }
    }
}
