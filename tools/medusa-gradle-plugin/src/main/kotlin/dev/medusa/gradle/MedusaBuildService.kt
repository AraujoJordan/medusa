package dev.medusa.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Marker service that caps how many Medusa tasks run at the same time across all modules.
 *
 * Every Medusa task starts an `android preview` process that runs a nested Gradle build. With
 * `org.gradle.parallel=true`, N modules would otherwise start N of them at once; they are safe, but slow and memory
 * hungry (each is another Gradle daemon of roughly 800 MB). Tasks declare `usesService`, and the cap is the service's
 * `maxParallelUsages`: default the number of CPU cores, `-Pmedusa.maxConcurrentRenders=` to change it (lower it on a
 * build with many modules and little memory). Measured on 6 modules: cap 1 took 27 s, caps 2 to 10 about 13 to 21 s,
 * without a clear winner among them.
 */
abstract class MedusaBuildService : BuildService<BuildServiceParameters.None>
