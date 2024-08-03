package dev.dialector.cabin

import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.random.Random

fun main(args: Array<String>) {
    val algorithm = args[0].toInt()
    var totalTime = 0L
    var totalSize = 0

    val archivePath = Path.of("C:\\Users\\tyler\\development\\cabin\\build\\perftest.cabin")
    val filePath = "perf"

    archivePath.deleteIfExists()
    val fs = CabinFileSystem.fromPath(archivePath)

    val writers: List<(String, ByteArray, ByteArray) -> Any> = listOf(
        { path: String, size: ByteArray, data: ByteArray ->
            fs.newOutputStream(path).use {
                it.write(size)
                it.write(data)
            }
        },
        { path: String, size: ByteArray, data: ByteArray ->
            val file = fs.writeFileDirect(path, (size.size + data.size).toLong())
            file.put(size)
            file.put(data)
        }
    )
    val writer = writers[algorithm]

    val random = Random(284712)
    (1 .. 100).forEach { fileNum ->
        val size = random.nextInt(1024, 16384)
        val sizeBuf = ByteBuffer.allocate(4)
        sizeBuf.putInt(size)
        val sizeData = sizeBuf.array()
        val data = random.nextBytes(size)
        totalSize += size
        val start = System.nanoTime()
        writer.invoke(filePath + fileNum, sizeData, data)
        val end = System.nanoTime()
        totalTime += end - start
    }

    val milliTime = TimeUnit.NANOSECONDS.toMillis(totalTime)
    println("$milliTime to serialize $totalSize bytes across 100 files")
}