# Releasing

0.1.0 was published from a maintainer machine, signing with the local gpg agent (the private key never leaves it):

```sh
git checkout v0.1.0          # the tagged commit
cd tools
ORG_GRADLE_PROJECT_mavenCentralUsername=<token user> ORG_GRADLE_PROJECT_mavenCentralPassword=<token password> \
  ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache -Pmedusa.gpgAgent=true -Psigning.gnupg.keyName=<key id>
```

The `io.github.araujojordan` namespace is verified on Central. Create a short-lived token for each release at
https://central.sonatype.com/usertoken and revoke it afterwards. The CI route below is the alternative if you prefer not to publish by hand.

The release workflow passes the git tag as `-PmedusaVersion`. Local builds default to `0.2.0`.

## Every release
1. Update the version, changelog and README install snippets. Run the tests and verify the signed artifacts in a consumer build.
2. Wait for Linux and macOS CI on the release commit.
3. Publish that commit locally using the command above with `-PmedusaVersion=0.2.0`, or enable CI publishing below. Do not publish the same version twice.
4. `git tag v0.2.0 && git push origin v0.2.0`. The **Release** workflow runs the tests, creates the GitHub release and, if enabled, publishes to Maven Central.
5. JitPack builds the same tag on first request: https://jitpack.io/#AraujoJordan/medusa (open the page and press *Get it* to warm it).

## One-time setup for Maven Central
Medusa publishes as `io.github.araujojordan:medusa-core` and `io.github.araujojordan:medusa-gradle-plugin`
(plugin id `io.github.araujojordan.medusa`).

1. Sign in at https://central.sonatype.com with GitHub and **verify the namespace `io.github.araujojordan`**
   (Namespaces -> Add Namespace; Central asks you to create a temporary public GitHub repository with a given name to prove ownership).
2. Create a **user token** (Account -> Generate User Token). It is a username and a password, not your login.
3. Make sure your GPG public key is on a keyserver (`gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`), and export the
   private key: `gpg --armor --export-secret-keys <KEYID>`.
4. In the GitHub repository: Settings -> Secrets and variables -> Actions, add the secrets
   `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` (the armored private key) and, if it has one, `SIGNING_KEY_PASSWORD`.
5. Add the repository **variable** `PUBLISH_TO_CENTRAL` = `true`.

A Maven Central release cannot be deleted or changed afterwards, so tag only what you mean to keep.

To publish by hand instead: put the same four values in `~/.gradle/gradle.properties` as `mavenCentralUsername`,
`mavenCentralPassword`, `signingInMemoryKey`, `signingInMemoryKeyPassword`, then
`cd tools && ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache`.
