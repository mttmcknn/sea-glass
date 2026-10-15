# Maven publishing

Sea Glass is configured for Maven Central through `com.vanniktech.maven.publish` 0.37.0. Publication is local and manual; no release workflow or tag trigger is configured. Only the `sea-glass` library module is published.

The current development coordinates are `dev.mttmcknn:sea-glass:0.1.0-SNAPSHOT`. `GROUP` and `VERSION_NAME` live in the root `gradle.properties`. This setup does not establish that an artifact is available on Central. Until publication is verified, use the composite-build instructions in the [README](../README.md#use-in-an-app).

## Check the artifacts locally

Use macOS with Xcode, JDK 17, Android SDK 36, and Build Tools 36.0.0. Keep Android enabled: `-PseaGlass.android=false` is for desktop development and omits the Android publication. Repository publication tasks require macOS and reject the Android-disabled configuration.

```sh
./gradlew :sea-glass:jvmTest :sea-glass:publishAllPublicationsToLocalVerificationRepository
```

This writes to `sea-glass/build/verification-repository/`; it does not upload to Central. Use this named task for validation. The generic `publish` task includes remote destinations.

The five expected artifacts share the same group and version:

| Artifact | Contents |
| --- | --- |
| `sea-glass` | Multiplatform metadata and references to platform variants |
| `sea-glass-android` | Android AAR |
| `sea-glass-jvm` | Desktop/JVM JAR |
| `sea-glass-iosarm64` | iOS device KLIB |
| `sea-glass-iossimulatorarm64` | Apple Silicon iOS simulator KLIB |

Inspect the generated POM and Gradle module metadata for each artifact. Confirm that the platform files, source JARs, and Javadoc JARs are present. The plugin currently supplies placeholder Javadoc JARs; API documentation is in this repository. A successful local upload verifies packaging, not Central namespace ownership, signatures, or portal acceptance. Signing is optional for the current snapshot when no key is configured; non-snapshot builds require a signing key even for local verification.

Run `:androidApp:assembleDemoRelease` and `:desktopApp:verifyPlayground` for the sample checks before choosing a release candidate. iOS compilation does not verify rendering on a device. Physical-device performance and older-Android fallback coverage remain separate checks.

## Set up Central access

The Central Portal confirmed ownership of the `dev.mttmcknn` namespace on October 15, 2026. Publication also requires a Central Portal user token and a usable GPG signing key whose public key is discoverable.

Save the token's username and password in a private local configuration outside the repository, such as `~/.gradle/gradle.properties`, or supply the corresponding environment variables. Use the generated token credentials, not the Portal login:

| Gradle property | Environment variable |
| --- | --- |
| `mavenCentralUsername` | `ORG_GRADLE_PROJECT_mavenCentralUsername` |
| `mavenCentralPassword` | `ORG_GRADLE_PROJECT_mavenCentralPassword` |

The default signing setup accepts an in-memory key through these private Gradle properties or environment variables:

| Gradle property | Environment variable |
| --- | --- |
| `signingInMemoryKey` | `ORG_GRADLE_PROJECT_signingInMemoryKey` |
| `signingInMemoryKeyPassword` | `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` |
| `signingInMemoryKeyId` (optional) | `ORG_GRADLE_PROJECT_signingInMemoryKeyId` |

To reuse an existing local GPG key through `gpg-agent`, opt in with `-Psigning.useGpgCmd=true`. Put the executable path and full signing-key fingerprint in your private Gradle properties file, adjusting the path for your installation:

```properties
signing.gnupg.executable=/opt/homebrew/bin/gpg
signing.gnupg.keyName=YOUR_FULL_SIGNING_KEY_FINGERPRINT
```

Leave `signing.gnupg.passphrase` unset so `gpg-agent` and pinentry request the passphrase directly. This mode uses the existing local key without exporting it; in-memory key properties are unnecessary. A working pinentry program is required when the key needs unlocking. On macOS, `pinentry-mac` can provide a native prompt when configured for `gpg-agent`. With a terminal pinentry, run from an interactive shell and set `export GPG_TTY="$(tty)"` in that shell.

Verify signed artifacts locally before uploading:

```sh
./gradlew :sea-glass:publishAllPublicationsToLocalVerificationRepository \
  -Psigning.useGpgCmd=true
```

Never commit tokens or private signing keys. See the [plugin's Central setup](https://vanniktech.github.io/gradle-maven-publish-plugin/central/), [Gradle's GPG-agent signing configuration](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:using_gpg_agent), and [GnuPG's pinentry setup](https://www.gnupg.org/documentation/manuals/gnupg/Invoking-GPG_002dAGENT.html).

## Prepare a release candidate

Choose a release version, tag, next development version, and release notes. Validate the resulting source and metadata, review the diff, and commit the agreed candidate. This repository currently has no changelog or Metalava API snapshots. No API snapshots are configured; if Metalava snapshots are introduced later, API generation and compatibility checks must both pass before copying them.

Publication requires approval of the visible candidate details: version and tag, notes or diff, next development version, prepared commit, coordinates, destination, local publishing mechanism, and validation results. The publishing setup alone is not a release candidate.

## Publish an approved candidate

After the candidate is approved and credentials are available, run from the prepared commit with all targets enabled:

```sh
./gradlew :sea-glass:publishToMavenCentral
```

Add `-Psigning.useGpgCmd=true` when using the local GPG-agent setup above.

For a non-snapshot release, `automaticRelease = false` leaves the uploaded deployment for manual publication in the Central Portal. Review its validation result, then publish it there. For a `-SNAPSHOT` version, this same task makes the snapshot available immediately; it is not a dry run. `publishAndReleaseToMavenCentral` also releases automatically and is outside the manual workflow described here.

Verify all five published artifacts and their platform metadata at the destination. Then record the release tag against the approved commit and advance to the agreed next development version. Do not treat a successful upload as proof that consumers can resolve every variant.
