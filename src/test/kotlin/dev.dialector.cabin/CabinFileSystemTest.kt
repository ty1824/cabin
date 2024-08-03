package dev.dialector.cabin

import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals

class CabinFileSystemTest {

    @Test
    fun testWrite() {
        val archivePath = Path.of("C:\\Users\\tyler\\development\\cabin\\build\\test.cabin")
        val filePath = "world"

        archivePath.deleteIfExists()
        val fs = CabinFileSystem.fromPath(archivePath)
        (1 .. 100).forEach { fileNum ->
            fs.newOutputStream(filePath+fileNum).use {
                DataOutputStream(it).use { out ->
                    out.writeUTF("Hello$fileNum")
                }
            }
        }
        fs.close()

        val fs2 = CabinFileSystem.fromPath(archivePath)
        (1 .. 100).forEach { fileNum ->
            fs2.newInputStream(filePath+fileNum).use {
                DataInputStream(it).use { input ->
                    assertEquals("Hello$fileNum", input.readUTF())
                }
            }
        }
        fs2.close()
    }
}