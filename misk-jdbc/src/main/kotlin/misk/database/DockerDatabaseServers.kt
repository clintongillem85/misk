package misk.database

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.core.async.ResultCallbackTemplate
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import misk.backoff.DontRetryException
import misk.backoff.ExponentialBackoff
import misk.backoff.RetryConfig
import misk.backoff.retry
import mu.KLogger

/**
 * Pulls [image] unless the local Docker cache already contains a matching [sha], guarding against repeated pulls with
 * [imagePulled]. Failures are logged rather than thrown so tests can proceed against a previously pulled image.
 */
internal fun pullDockerImage(
  image: String,
  sha: String,
  imagePulled: AtomicBoolean,
  logger: KLogger,
  description: String,
) {
  if (imagePulled.get()) {
    return
  }

  synchronized(imagePulled) {
    if (imagePulled.get()) {
      return
    }

    if (runCommand("docker images --digests | grep -q $sha || docker pull $image") != 0) {
      logger.warn("Failed to pull $description docker image. Proceeding regardless.")
    }
    imagePulled.set(true)
  }
}

/**
 * Retries [healthCheck] until it succeeds, throwing an exception naming [serverName] if the server never becomes
 * healthy in time.
 */
internal fun awaitDatabaseHealthy(serverName: String, healthCheck: () -> Unit) {
  try {
    val retryConfig = RetryConfig.Builder(20, ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(5)))
    retry(retryConfig.build()) { healthCheck() }
  } catch (e: DontRetryException) {
    throw Exception(e.message)
  } catch (e: Exception) {
    throw Exception("$serverName failed to start up in time", e)
  }
}

/** Follows a container's stdout/stderr from the beginning, forwarding each frame to [callback]. */
internal fun DockerClient.followContainerLogs(containerId: String, callback: ResultCallbackTemplate<*, Frame>) {
  logContainerCmd(containerId)
    .withStdErr(true)
    .withStdOut(true)
    .withFollowStream(true)
    .withSince(0)
    .exec(callback)
    .awaitStarted()
}
