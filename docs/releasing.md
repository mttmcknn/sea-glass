# Release process

Sea Glass uses [Semantic Versioning](https://semver.org/) so consumers can judge the compatibility of a library update. The planned first release is **0.1.0**, with tag **v0.1.0**. After its artifacts are verified on Maven Central, development moves to **0.2.0-SNAPSHOT**.

## Choose a version

All five Maven publications use the same `GROUP` and `VERSION_NAME` from the root `gradle.properties`.

| Change | During `0.x` development | From `1.0.0` onward |
| --- | --- | --- |
| Compatible fixes or performance improvements | Patch, for example `0.1.1` | Patch, for example `1.0.1` |
| New public functionality | Minor, for example `0.2.0` | Minor, for example `1.1.0` |
| Breaking public API or supported behavior | Minor, with migration notes | Major, with migration notes |

Although SemVer permits changes throughout `0.x`, Sea Glass keeps patch releases compatible. Reserve `1.0.0` for a public API whose compatibility can be maintained. Add automated API compatibility checks before that release; none are configured today. API checks supplement tests and review of behavior and platform requirements.

Use `vX.Y.Z` for release tags. Choose the next development version explicitly; do not infer it from the release number. The current staging workflow accepts only numeric `X.Y.Z` releases. It does not accept prerelease suffixes or snapshots. A `-SNAPSHOT` version is for development, and uploading one can make it available immediately; it is not a publishing dry run.

## Prepare the candidate

1. Start from the intended `main` history with a clean worktree and index. Inspect existing tags and Maven Central coordinates before choosing an unused version. Never replace an existing release tag or published version.
2. Confirm the release version, tag, next development version, and publication scope. Only the `sea-glass` library is published; sample applications are not release artifacts.
3. Set `VERSION_NAME` to the release version and update the documented coordinates. Write release notes describing the final consumer-visible behavior, compatibility changes, migration steps where needed, and known limitations. For the first release, cover the library from its initial implementation. There is currently no changelog or prerelease history to consolidate; keep the notes in `docs/releases/`.
4. Validate the candidate, inspect its diff, and commit the release files. Record the full commit SHA and keep the candidate unchanged while it is reviewed. A result from a parent commit is insufficient when relevant code or release inputs have changed.

## Validate the candidate

Use the macOS, JDK, Xcode, and Android SDK requirements in [Maven publishing](publishing.md). Keep Android enabled so validation covers every published target.

- Run `:sea-glass:jvmTest`, `:sea-glass:iosSimulatorArm64Test`, `:desktopApp:verifyPlayground`, and `:androidApp:assembleDemoRelease`.
- Run the publication verifier's tests: `python3 -m unittest discover -s validation -p 'test_verify_publication.py'`.
- Build signed artifacts with `:sea-glass:publishAllPublicationsToLocalVerificationRepository`, then run `validation/verify_publication.py` for the actual release version, using the committed public certificate and Sea Glass fingerprint. Check all five publications, metadata links and checksums, archive contents, and all signatures.
- Record the results and their limits. Simulator logic tests do not establish iOS rendering coverage; emulator checks do not establish physical-device performance. See [rendering validation](rendering.md#exactness-and-platform-differences).

Ordinary pull-request CI verifies packaging with a temporary unsigned snapshot version. That result complements, but does not replace, signed verification of the release version. The staging workflow repeats the tests and signed artifact checks before uploading.

Metalava API snapshots are not configured. If introduced later, API generation **and** compatibility checks must both pass **before** generated snapshots are copied into a release. Do not copy another API dump format as if it were a Metalava snapshot.

## Review and approve

Present the actual release notes or diff together with the version and tag, next development version, full prepared commit SHA, validation results, and destination. The coordinates are `dev.mttmcknn:sea-glass:X.Y.Z` plus the Android, JVM, iOS device, and iOS simulator publications listed in [Maven publishing](publishing.md#check-the-artifacts-locally).

Obtain explicit approval of those visible details before pushing the release tag or staging artifacts. State whether approval covers staging only or also final public publication. Approval of publishing setup alone is insufficient. Revalidate and obtain renewed approval if the code, versions, notes, or publishing scope change.

## Stage and publish

Use the existing **Stage Maven Central release** GitHub Actions workflow. After approval, push `vX.Y.Z` pointing to the exact prepared commit and manually dispatch `publish.yml` against that tag, supplying the matching `release_version` and full `approved_sha`. [Publishing commands](publishing.md#publish-an-approved-candidate) show the invocation. Pushing a tag alone does not upload anything.

The workflow uses `com.vanniktech.maven.publish` with `automaticRelease = false`. It verifies the tag and commit, runs the checks, signs all five publications with the Sea Glass key, and uploads a deployment to Maven Central. Review the deployment's validation in the Central Portal, then publish it within the approved scope. A successful staging workflow does not prove public availability.

Use one publication route per candidate. Local staging is an alternative documented in [Maven publishing](publishing.md); do not also run it after GitHub staging succeeds or has an uncertain outcome. Keep credentials and private keys in the existing private configuration or Actions secrets.

## Verify and continue development

Verify that all five artifact coordinates and versions are publicly available, including their platform files, metadata, sources, documentation archives, and signatures. The repository's publication verifier reads a local Maven layout; it does not fetch Central artifacts. Download the published files into that layout and run the signed verifier, then check that a separate Maven consumer resolves the platform variants from Central. Check that the remote tag still resolves to the approved commit. For GitHub staging, confirm that the workflow succeeded for that commit and preserve the release evidence you need before its 14-day artifact retention expires. For local staging, retain the corresponding validation and deployment evidence.

Only after those checks pass, advance `VERSION_NAME` to the agreed next development version, update current-version documentation, commit, push, and verify the remote branch. For `0.1.0`, the next version is `0.2.0-SNAPSHOT`. Retain the release tag and notes unchanged.

If publication is partial or uncertain, inspect the existing deployment, workflow, remote tag, and artifact state before doing anything else. Resume only the proven missing step; do not repeat an upload to discover whether the previous one worked.
