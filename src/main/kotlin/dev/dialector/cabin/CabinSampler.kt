package dev.dialector.cabin

import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.random.Random

fun main(args: Array<String>) {
    val algorithm = args[0].toInt()
    var totalTime = 0L
    var totalSize = 0L


    val resource = object {}::class.java.getResource("/test.txt")
    val input = File(resource!!.toURI()).readLines()
    val dirPath = Path("cabin_test")
    if (!dirPath.exists()) {
        Files.createDirectory(dirPath)
    }
    val archivePath = dirPath.resolve("perftest.cabin")
    println(archivePath.toAbsolutePath())
    val filePath = "perf"

    archivePath.deleteIfExists()
    val fs = CabinFileSystem.fromPath(archivePath)

    val writers: List<(String, Int, ByteArray, ByteArray) -> Any> = listOf(
        { path: String, num: Int, size: ByteArray, data: ByteArray ->
            BufferedOutputStream(fs.newOutputStream(path)).use { out ->
                out.write(size)
                repeat(num) { out.write(data) }
            }
        },
        { path: String, num: Int, size: ByteArray, data: ByteArray ->
            val file = fs.writeFileDirect(path, (size.size + data.size * num).toLong())
            file.put(size)
            repeat(num) { file.put(data) }
        },
        { path: String, num: Int, size: ByteArray, data: ByteArray ->
            fs.directOutputStream(path, (size.size + data.size * num).toLong()).use { out ->
                out.write(size)
                repeat(num) {
                    out.write(data)
                }
            }
        }
    )
    val writer = writers[algorithm]

    val n = 1000
    val random = Random(284712)
    (1 .. n).forEach { fileNum ->
//        println(fileNum)
        val lineNum = random.nextInt(0, input.size)
        val repeat = random.nextInt(1, 1000)
        val toWrite = input[lineNum].toByteArray()
        val size = toWrite.size * repeat
        val sizeBuf = ByteBuffer.allocate(4)
        sizeBuf.putInt(size)
        val sizeData = sizeBuf.array()
        totalSize += size
        val start = System.nanoTime()
        writer.invoke(filePath + fileNum, repeat, sizeData, toWrite)
        val end = System.nanoTime()
        totalTime += end - start
    }

    val milliTime = TimeUnit.NANOSECONDS.toMillis(totalTime)
    println("${milliTime}ms to serialize ${String.format("%,d", totalSize)} bytes across $n files")
}