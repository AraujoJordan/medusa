package dev.medusa.core.setup

import dev.medusa.core.MedusaException
import java.io.File
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun interface Downloader {
  /** Saves [url] to [target]. Throws [MedusaException] when it cannot. */
  fun download(url: String, target: File)
}

/** Follows redirects (GitHub release downloads redirect) and honours the system proxy. */
object HttpDownloader : Downloader {
  override fun download(url: String, target: File) {
    target.parentFile.mkdirs()
    try {
      val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .proxy(ProxySelector.getDefault())
        .build()
      val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target.toPath()))
      if (response.statusCode() != 200) {
        target.delete()
        throw MedusaException("Downloading $url failed: HTTP ${response.statusCode()}")
      }
    } catch (e: IOException) {
      target.delete()
      // ConnectException and friends often have no message at all, so name the exception ("could not connect").
      val reason = e.message?.takeIf { it.isNotBlank() } ?: when (e) {
        is java.net.ConnectException -> "could not connect (no network, or a proxy that refuses connections)"
        is java.net.http.HttpTimeoutException -> "timed out"
        is java.net.UnknownHostException -> "unknown host"
        else -> e.javaClass.simpleName
      }
      throw MedusaException("Downloading $url failed: $reason", e)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw MedusaException("Downloading $url was interrupted", e)
    }
  }
}
