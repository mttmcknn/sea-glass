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

The Central Portal confirmed ownership of the `dev.mttmcknn` namespace on October 15, 2026. Token and signing credentials are still required before publication.

Create a Central Portal user token and a GPG signing key whose public key is discoverable. Provide these Gradle properties through a secure local configuration outside the repository, or the corresponding environment variables:

| Gradle property | Environment variable |
| --- | --- |
| `mavenCentralUsername` | `ORG_GRADLE_PROJECT_mavenCentralUsername` |
| `mavenCentralPassword` | `ORG_GRADLE_PROJECT_mavenCentralPassword` |
| `signingInMemoryKey` | `ORG_GRADLE_PROJECT_signingInMemoryKey` |
| `signingInMemoryKeyPassword` | `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` |
| `signingInMemoryKeyId` (optional) | `ORG_GRADLE_PROJECT_signingInMemoryKeyId` |

Use the token's username and password, rather than the Portal login. Never commit tokens or private signing keys. See the [plugin's Central setup](https://vanniktech.github.io/gradle-maven-publish-plugin/central/) for current credential and signing requirements.

## Prepare a release candidate

Choose a release version, tag, next development version, and release notes. Validate the resulting source and metadata, review the diff, and commit the agreed candidate. This repository currently has no changelog or Metalava API snapshots. No API snapshots are configured; if Metalava snapshots are introduced later, API generation and compatibility checks must both pass before copying them.

Publication requires approval of the visible candidate details: version and tag, notes or diff, next development version, prepared commit, coordinates, destination, local publishing mechanism, and validation results. The publishing setup alone is not a release candidate.

## Publish an approved candidate

After the candidate is approved and credentials are available, run from the prepared commit with all targets enabled:

```sh
./gradlew :sea-glass:publishToMavenCentral
```

For a non-snapshot release, `automaticRelease = false` leaves the uploaded deployment for manual publication in the Central Portal. Review its validation result, then publish it there. For a `-SNAPSHOT` version, this same task makes the snapshot available immediately; it is not a dry run. `publishAndReleaseToMavenCentral` also releases automatically and is outside the manual workflow described here.

Verify all five published artifacts and their platform metadata at the destination. Then record the release tag against the approved commit and advance to the agreed next development version. Do not treat a successful upload as proof that consumers can resolve every variant.
