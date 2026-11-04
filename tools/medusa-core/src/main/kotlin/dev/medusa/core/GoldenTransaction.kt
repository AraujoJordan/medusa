package dev.medusa.core

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Locks are held through discovery, recovery, rendering, golden commit and report publication. */
internal object RunLocks {
  private val locks = ConcurrentHashMap<String, ReentrantLock>()
  private val directory = File(System.getProperty("user.home"), ".medusa/locks")
  private class Retry(val extra: List<File>) : RuntimeException()
  fun <T> withLocks(paths: Collection<File>, block: () -> T): T = try { locked(paths, block) }
    catch (retry: Retry) { withLocks(paths + retry.extra, block) }
  private fun <T> locked(paths: Collection<File>, block: () -> T): T {
    val requested = paths.map { it.canonicalFile }.toMutableSet()
    // Interrupted multi-directory commits must be recovered under all their original locks.
    while (true) {
      val extra = requested.flatMap { GoldenTransaction.related(it) }.toSet() - requested
      if (extra.isEmpty()) break
      requested += extra
    }
    val names = requested.map { it.path }.sorted()
    val held = mutableListOf<AutoCloseable>()
    try {
      directory.mkdirs()
      for (name in names) {
        val local = locks.computeIfAbsent(name) { ReentrantLock() }
        local.lockInterruptibly()
        held += AutoCloseable { local.unlock() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        val channel = FileChannel.open(File(directory, hash).toPath(), CREATE, WRITE)
        held += channel
        held += channel.lock()
      }
      val expanded = requested.flatMap { GoldenTransaction.related(it) }.filter { it !in requested }
      if (expanded.isNotEmpty()) throw Retry(expanded)
      requested.forEach { GoldenTransaction.recover(it) }
      return block()
    } finally { held.asReversed().forEach { runCatching { it.close() } } }
  }
}

/** A durable rollback journal, discoverable from every destination directory. */
internal object GoldenTransaction {
  private const val MARKER = ".medusa-journal"
  private fun manifest(directory: File): File? = File(directory, MARKER).takeIf { it.isFile }?.readText()?.let(::File)
  private fun load(file: File) = Properties().apply { file.inputStream().use { load(it) } }
  fun related(directory: File): List<File> = manifest(directory)?.let { journal ->
    val properties = load(journal)
    (0 until properties.getProperty("count").toInt()).map { File(properties.getProperty("target.$it")).parentFile.canonicalFile }
  }.orEmpty()
  fun recover(directory: File) { manifest(directory)?.let { finish(it, rollback = true) } }
  private fun replace(source: File, target: File) {
    Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
  }
  private fun durable(file: File, bytes: ByteArray) {
    java.io.FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
  }
  private fun finish(journal: File, rollback: Boolean) {
    val properties = load(journal)
    val targets = (0 until properties.getProperty("count").toInt()).map { File(properties.getProperty("target.$it")) }
    if (rollback && !File(journal.parentFile, "committed").exists()) {
      targets.forEachIndexed { index, target ->
        val backup = File(journal.parentFile, "backup.$index")
        if (backup.exists()) {
          val restore = Files.createTempFile(target.parentFile.toPath(), ".medusa-restore-", ".tmp").toFile()
          durable(restore, backup.readBytes())
          replace(restore, target)
        } else Files.deleteIfExists(target.toPath())
      }
    }
    targets.map { it.parentFile }.distinct().forEach { File(it, MARKER).delete() }
    journal.parentFile.deleteRecursively()
  }
  fun commit(staged: Map<File, File?>) {
    if (staged.isEmpty()) return
    val entries = staged.entries.sortedBy { it.key.canonicalPath }
    val home = Files.createTempDirectory(entries.first().key.parentFile.toPath(), ".medusa-transaction-").toFile()
    val journal = File(home, "manifest")
    val properties = Properties().apply { setProperty("count", entries.size.toString()) }
    try {
      entries.forEachIndexed { index, entry ->
        properties.setProperty("target.$index", entry.key.canonicalPath)
        if (entry.key.exists()) durable(File(home, "backup.$index"), entry.key.readBytes())
      }
      val bytes = java.io.ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray()
      durable(journal, bytes)
      // No destination changes until every directory can locate the complete journal.
      entries.map { it.key.parentFile }.distinct().forEach { durable(File(it, MARKER), journal.canonicalPath.toByteArray()) }
      entries.forEach { (target, temporary) ->
        if (temporary == null) Files.deleteIfExists(target.toPath()) else replace(temporary, target)
      }
      val commitMarker = File(home, "committed.tmp")
      durable(commitMarker, byteArrayOf(1))
      replace(commitMarker, File(home, "committed"))
      finish(journal, rollback = false)
    } catch (failure: Throwable) {
      if (journal.exists()) runCatching { finish(journal, rollback = true) }.exceptionOrNull()?.let { failure.addSuppressed(it) }
      else home.deleteRecursively()
      throw failure
    }
  }
}
