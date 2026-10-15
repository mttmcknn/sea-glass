"""Focused publication-verifier tests using synthetic files and mocked GPG only.

Run with: python3 -m unittest discover -s validation -p 'test_verify_publication.py'
"""

from argparse import Namespace
from contextlib import redirect_stdout, redirect_stderr
from io import BytesIO, StringIO
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

import verify_publication as verifier


FINGERPRINT = "0AEB37022B619062D178402160A4B9CA4E001652"
SUBKEY = "1" * 40
CLASS_BYTES = b"\xca\xfe\xba\xbe\x00\x00\x00\x34\x00\x01"


def archive_bytes(entries):
    buffer = BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return buffer.getvalue()


def good_signature(command, data=None):
    status = (f"[GNUPG:] GOODSIG {SUBKEY[-16:]} Synthetic test\n"
              f"[GNUPG:] VALIDSIG {SUBKEY} 2026-10-15 1792080000 0 4 0 1 10 00 {FINGERPRINT}\n")
    return subprocess.CompletedProcess(command, 0, status.encode(), b"")


class SyntheticRepository:
    def __init__(self, directory, version="0.1.0", signed=True):
        self.directory, self.version = Path(directory), version
        self.base = self.directory / "dev/mttmcknn"
        self.paths = {}
        self.selected_versions = {}
        for build, (artifact, extension) in enumerate(verifier.PUBLICATIONS.items(), 1):
            selected = (f"{version[:-9]}-20261015.120000-{build}"
                        if version.endswith("-SNAPSHOT") else version)
            self.selected_versions[artifact] = selected
            publication_directory = self.base / artifact / version
            publication_directory.mkdir(parents=True)
            for suffix, classifier in sorted(verifier.expected_payloads(artifact, extension)):
                path = publication_directory / verifier.artifact_name(artifact, selected, suffix, classifier)
                self.paths[(artifact, suffix, classifier)] = path
                if suffix == "pom":
                    content = (f'<project xmlns="http://maven.apache.org/POM/4.0.0">'
                               f"<modelVersion>4.0.0</modelVersion><groupId>dev.mttmcknn</groupId>"
                               f"<artifactId>{artifact}</artifactId><version>{version}</version>"
                               f"<packaging>{extension}</packaging></project>").encode()
                elif suffix in ("module", "json"):
                    content = b"{}"
                elif suffix == "kotlin_resources.zip":
                    content = archive_bytes({})
                elif classifier == "sources":
                    content = archive_bytes({"commonMain/SeaGlass.kt": b"class SeaGlass"})
                elif suffix == "aar":
                    content = archive_bytes({"classes.jar": archive_bytes({
                        "dev/mttmcknn/seaglass/SeaGlass.class": CLASS_BYTES})})
                elif artifact == "sea-glass-jvm" and not classifier:
                    content = archive_bytes({"dev/mttmcknn/seaglass/SeaGlass.class": CLASS_BYTES})
                else:
                    content = archive_bytes({"metadata": b"synthetic payload"})
                path.write_bytes(content)
                if signed:
                    Path(str(path) + ".asc").write_text("synthetic detached signature\n")
            if version.endswith("-SNAPSHOT"):
                root = ET.Element("metadata")
                for key, value in (("groupId", verifier.DEFAULT_GROUP), ("artifactId", artifact),
                                   ("version", version)):
                    ET.SubElement(root, key).text = value
                versioning = ET.SubElement(root, "versioning")
                snapshot = ET.SubElement(versioning, "snapshot")
                ET.SubElement(snapshot, "timestamp").text = "20261015.120000"
                ET.SubElement(snapshot, "buildNumber").text = str(build)
                versions = ET.SubElement(versioning, "snapshotVersions")
                for suffix, classifier in sorted(verifier.expected_payloads(artifact, extension)):
                    for ending in ("", ".asc") if signed else ("",):
                        entry = ET.SubElement(versions, "snapshotVersion")
                        ET.SubElement(entry, "extension").text = suffix + ending
                        if classifier:
                            ET.SubElement(entry, "classifier").text = classifier
                        ET.SubElement(entry, "value").text = selected
                ET.ElementTree(root).write(publication_directory / "maven-metadata.xml")
        for artifact, extension in verifier.PUBLICATIONS.items():
            component = {"group": verifier.DEFAULT_GROUP, "module": "sea-glass", "version": version}
            if artifact != "sea-glass":
                component["url"] = f"../../sea-glass/{version}/sea-glass-{version}.module"
            variants = []
            for suffix, classifier in sorted(verifier.expected_payloads(artifact, extension)):
                if suffix in ("pom", "module", "json") or classifier == "javadoc":
                    continue
                path = self.path(artifact, suffix, classifier)
                data = path.read_bytes()
                entry = {"name": path.name, "url": verifier.artifact_name(artifact, version, suffix, classifier),
                         "size": len(data)}
                entry.update({algorithm: hashlib.new(algorithm, data).hexdigest()
                              for algorithm in ("md5", "sha1", "sha256", "sha512")})
                variants.append({"name": suffix + classifier, "files": [entry]})
            if artifact == "sea-glass":
                for target in verifier.PUBLICATIONS:
                    if target != artifact:
                        variants.append({"name": target, "available-at": {
                            "group": verifier.DEFAULT_GROUP, "module": target, "version": version,
                            "url": f"../../{target}/{version}/{target}-{version}.module"}})
            self.path(artifact, "module").write_text(json.dumps({
                "formatVersion": "1.1", "component": component, "variants": variants}))

    def path(self, artifact, extension, classifier=""):
        return self.paths[(artifact, extension, classifier)]

    def args(self, **overrides):
        values = dict(repository=self.directory, group=verifier.DEFAULT_GROUP, version=self.version,
                      gpg="synthetic-gpg-must-not-execute", fingerprint=FINGERPRINT,
                      public_key=None, allow_unsigned=False)
        values.update(overrides)
        return Namespace(**values)


class VerifyPublicationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="sg-test-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def verify(self, repository, **overrides):
        with redirect_stdout(StringIO()), patch.object(verifier, "run_gpg", side_effect=good_signature) as gpg:
            report = verifier.verify(repository.args(**overrides))
        return report, gpg

    def assert_publication_failure(self, report, artifact, message):
        self.assertFalse(report["success"])
        item = next(item for item in report["publications"] if item["artifact"] == artifact)
        self.assertEqual("fail", item["status"])
        self.assertIn(message, item["error"])

    def test_stable_verifies_all_thirty_payload_signatures(self):
        report, gpg = self.verify(SyntheticRepository(self.directory))
        self.assertTrue(report["success"], report)
        self.assertEqual(30, report["payload_count"])
        self.assertEqual(30, report["signatures_verified"])
        self.assertEqual(30, gpg.call_count)
        self.assertEqual(5, len(report["publications"]))

    def test_snapshot_selects_metadata_and_allows_per_publication_build_numbers(self):
        repository = SyntheticRepository(self.directory, "0.1.0-SNAPSHOT")
        # A newer-looking unselected build must never replace the metadata selection.
        root = repository.path("sea-glass", "jar")
        root.with_name("sea-glass-0.1.0-20990101.000000-999.jar").write_bytes(b"stale")
        report, gpg = self.verify(repository)
        self.assertTrue(report["success"], report)
        self.assertEqual(30, gpg.call_count)
        self.assertEqual(repository.selected_versions,
                         {item["artifact"]: item["selected_version"] for item in report["publications"]})

    def test_unsigned_snapshot_requires_no_signatures_or_gpg(self):
        repository = SyntheticRepository(self.directory, "0.1.0-SNAPSHOT", signed=False)
        report, gpg = self.verify(repository, allow_unsigned=True, fingerprint=None)
        self.assertTrue(report["success"], report)
        self.assertFalse(report["signatures_required"])
        self.assertEqual(0, report["signatures_verified"])
        gpg.assert_not_called()

    def test_missing_payload_fails(self):
        repository = SyntheticRepository(self.directory)
        repository.path("sea-glass-iosarm64", "jar", "metadata").unlink()
        report, _ = self.verify(repository)
        self.assertFalse(report["success"])
        self.assertIn("Missing or empty file", report["errors"][0])

    def test_unexpected_stable_payload_fails(self):
        repository = SyntheticRepository(self.directory)
        repository.path("sea-glass", "jar").with_name("sea-glass-0.1.0-surprise.jar").write_bytes(b"unexpected")
        report, _ = self.verify(repository)
        self.assertFalse(report["success"])
        self.assertIn("Unexpected publication payloads", report["errors"][0])

    def test_wrong_pom_coordinates_fail(self):
        repository = SyntheticRepository(self.directory)
        path = repository.path("sea-glass-jvm", "pom")
        path.write_text(path.read_text().replace("<version>0.1.0</version>", "<version>0.2.0</version>"))
        report, _ = self.verify(repository)
        self.assert_publication_failure(report, "sea-glass-jvm", "POM coordinates differ")

    def test_nested_android_class_archive_crc_is_checked(self):
        repository = SyntheticRepository(self.directory)
        inner = archive_bytes({"dev/mttmcknn/seaglass/SeaGlass.class": CLASS_BYTES})
        inner = inner.replace(CLASS_BYTES, CLASS_BYTES[:-1] + b"\x02")
        repository.path("sea-glass-android", "aar").write_bytes(archive_bytes({"classes.jar": inner}))
        report, _ = self.verify(repository)
        self.assert_publication_failure(report, "sea-glass-android", "Corrupt class archive")

    def test_android_missing_classes_fails(self):
        repository = SyntheticRepository(self.directory)
        repository.path("sea-glass-android", "aar").write_bytes(archive_bytes({"AndroidManifest.xml": b"manifest"}))
        report, _ = self.verify(repository)
        self.assert_publication_failure(report, "sea-glass-android", "omits classes.jar")

    def test_wrong_declared_hash_fails(self):
        repository = SyntheticRepository(self.directory)
        path = repository.path("sea-glass-jvm", "module")
        metadata = json.loads(path.read_text())
        metadata["variants"][0]["files"][0]["sha256"] = "0" * 64
        path.write_text(json.dumps(metadata))
        report, _ = self.verify(repository)
        self.assert_publication_failure(report, "sea-glass-jvm", "sha256 mismatch")

    def test_wrong_root_target_link_fails(self):
        repository = SyntheticRepository(self.directory)
        path = repository.path("sea-glass", "module")
        metadata = json.loads(path.read_text())
        metadata["variants"][-1]["available-at"]["url"] = "../../outside/stale.module"
        path.write_text(json.dumps(metadata))
        report, _ = self.verify(repository)
        self.assert_publication_failure(report, "sea-glass", "outside the five publications")

    def test_missing_signature_fails(self):
        repository = SyntheticRepository(self.directory)
        Path(str(repository.path("sea-glass-jvm", "jar", "javadoc")) + ".asc").unlink()
        report, _ = self.verify(repository)
        self.assert_publication_failure(report, "sea-glass-jvm", "Missing or empty file")

    def test_snapshot_rejects_mixed_builds_and_missing_signature_mapping(self):
        repository = SyntheticRepository(self.directory, "0.1.0-SNAPSHOT")
        path = repository.path("sea-glass-jvm", "pom").parent / "maven-metadata.xml"
        metadata = ET.parse(path)
        entry = metadata.find("versioning/snapshotVersions/snapshotVersion")
        original = entry.findtext("value")
        entry.find("value").text = "0.1.0-20261014.120000-1"
        metadata.write(path)
        report, _ = self.verify(repository)
        self.assertFalse(report["success"])
        self.assertIn("Mixed snapshot versions", report["errors"][0])
        entry.find("value").text = original
        versions = metadata.find("versioning/snapshotVersions")
        versions.remove(next(entry for entry in versions if entry.findtext("extension").endswith(".asc")))
        metadata.write(path)
        report, _ = self.verify(repository)
        self.assert_publication_failure(report, "sea-glass-jvm", "omits the selected signature")

    def test_snapshot_metadata_change_during_verification_fails(self):
        repository = SyntheticRepository(self.directory, "0.1.0-SNAPSHOT")
        metadata = repository.path("sea-glass", "jar").parent / "maven-metadata.xml"
        def mutate_metadata(command, data=None):
            metadata.write_bytes(metadata.read_bytes() + b"\n")
            return good_signature(command, data)
        with redirect_stdout(StringIO()), patch.object(verifier, "run_gpg", side_effect=mutate_metadata):
            report = verifier.verify(repository.args())
        self.assertFalse(report["success"])
        self.assertIn("changed during verification", report["errors"][0])

    def test_wrong_primary_key_and_expired_signature_are_rejected(self):
        path = self.directory / "payload"
        valid = good_signature([])
        cases = [valid.stdout.replace(FINGERPRINT.encode(), b"2" * 40),
                 valid.stdout + b"[GNUPG:] EXPKEYSIG 0000000000000000 Expired\n"]
        for status in cases:
            with self.subTest(status=status), patch.object(verifier, "run_gpg", return_value=
                    subprocess.CompletedProcess([], 0, status, b"")):
                with self.assertRaises(ValueError):
                    verifier.check_signature(path, path, ["fake-gpg"], FINGERPRINT)

    def test_public_certificate_uses_temporary_keyring_without_leaking_gpg_diagnostics(self):
        certificate = self.directory / "public.asc"
        certificate.write_bytes(b"synthetic public certificate")
        inspection = f"pub:::::::::\nfpr:::::::::{FINGERPRINT}:\nsub:::::::::\nfpr:::::::::{SUBKEY}:\n"
        replies = [subprocess.CompletedProcess([], 0, inspection.encode(), b"private diagnostics"),
                   subprocess.CompletedProcess([], 0, b"", b"private diagnostics")]
        with patch.object(verifier, "run_gpg", side_effect=replies) as gpg:
            with verifier.gpg_command("fake-gpg", certificate, FINGERPRINT) as command:
                keyring = Path(command[command.index("--homedir") + 1])
                self.assertTrue(keyring.is_dir())
                self.assertIn("--no-auto-key-retrieve", command)
                self.assertEqual(2, gpg.call_count)
                self.assertEqual(certificate.read_bytes(), gpg.call_args.args[1])
            self.assertFalse(keyring.exists())

    def test_public_certificate_rejects_secret_material_and_wrong_key_before_import(self):
        certificate = self.directory / "public.asc"
        certificate.write_bytes(b"synthetic public certificate")
        inspections = [f"sec:::::::::\nfpr:::::::::{FINGERPRINT}:\n",
                       f"pub:::::::::\nfpr:::::::::{'2' * 40}:\n",
                       "pub:::::::::\nfpr:\n"]
        for inspection in inspections:
            with self.subTest(inspection=inspection), patch.object(verifier, "run_gpg", return_value=
                    subprocess.CompletedProcess([], 0, inspection.encode(), b"")) as gpg:
                with self.assertRaises(ValueError), verifier.gpg_command("fake-gpg", certificate, FINGERPRINT):
                    self.fail("Untrusted certificate was accepted")
                self.assertEqual(1, gpg.call_count)

    def test_cli_requires_explicit_signature_mode_and_prevents_path_versions(self):
        for arguments in ([], ["--allow-unsigned", "--fingerprint", FINGERPRINT],
                          ["--allow-unsigned", "--public-key", "public.asc"],
                          ["--allow-unsigned", "--version", "../../outside"]):
            with self.subTest(arguments=arguments), redirect_stderr(StringIO()):
                with self.assertRaises(SystemExit) as raised:
                    verifier.main(["--repository", str(self.directory), "--version", "0.1.0", *arguments])
                self.assertEqual(2, raised.exception.code)

    def test_cli_writes_json_success_report(self):
        SyntheticRepository(self.directory, signed=False)
        output = self.directory / "report.json"
        with redirect_stdout(StringIO()), patch.object(verifier, "run_gpg") as gpg:
            result = verifier.main(["--repository", str(self.directory), "--version", "0.1.0",
                                    "--allow-unsigned", "--output-json", str(output)])
        self.assertEqual(0, result)
        self.assertTrue(json.loads(output.read_text())["success"])
        gpg.assert_not_called()


if __name__ == "__main__":
    unittest.main()
