package dev.medusa.core.compare

import dev.medusa.core.MedusaException
import java.io.DataInputStream
import java.io.File

/** Image size from the PNG header, without decoding the image. */
internal object PngHeader {
  private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

  fun size(file: File): Pair<Int, Int> = try {
    DataInputStream(file.inputStream().buffered()).use { input ->
      val signature = ByteArray(8).also { input.readFully(it) }
      if (!signature.contentEquals(SIGNATURE)) throw MedusaException("${file.path} is not a PNG file")
      input.readInt() // IHDR length
      val type = ByteArray(4).also { input.readFully(it) }
      if (String(type, Charsets.US_ASCII) != "IHDR") throw MedusaException("${file.path} has no IHDR chunk")
      input.readInt() to input.readInt()
    }
  } catch (e: java.io.IOException) {
    throw MedusaException("Cannot read the PNG header of ${file.path}: ${e.message}", e)
  }
}
