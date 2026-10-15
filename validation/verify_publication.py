#!/usr/bin/env python3
"""Verify the complete Sea Glass publication in a local Maven repository.

Stable versions use their published filenames. Timestamped snapshots are selected
from each publication's maven-metadata.xml, never from modification times. The
five publications contain 30 payloads, including sources, Javadoc placeholders,
Kotlin tooling metadata, and iOS metadata/resources.

Signatures are required unless --allow-unsigned is explicitly selected for CI.
With --public-key, GPG inspects and imports only that public certificate into a
temporary, isolated keyring. Otherwise it uses already-installed public keys.
No keys are fetched, no secrets are requested, and GPG diagnostics are not logged.
Publication files are read-only; --output-json optionally writes a result report.
"""

import argparse
from contextlib import contextmanager, nullcontext
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET
import zipfile
import zlib


DEFAULT_GROUP = "dev.mttmcknn"
PUBLICATIONS = {
    "sea-glass": "jar",
    "sea-glass-android": "aar",
    "sea-glass-jvm": "jar",
    "sea-glass-iosarm64": "klib",
    "sea-glass-iossimulatorarm64": "klib",
}
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
CHECKSUM_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
ERRORS = (OSError, ValueError, KeyError, TypeError, AttributeError, ET.ParseError,
          zipfile.BadZipFile, zlib.error, EOFError, RuntimeError, subprocess.TimeoutExpired)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def require_file(path):
    require(path.is_file() and path.stat().st_size > 0,
            f"Missing or empty file: {path.name}")
    return path


def artifact_name(artifact, version, extension, classifier=""):
    return f"{artifact}-{version}{'-' + classifier if classifier else ''}.{extension}"


def expected_payloads(artifact, extension):
    expected = {(extension, ""), ("pom", ""), ("module", ""),
                ("jar", "sources"), ("jar", "javadoc")}
    if artifact == "sea-glass":
        expected.add(("json", "kotlin-tooling-metadata"))
    if artifact.startswith("sea-glass-ios"):
        expected.update({("jar", "metadata"), ("kotlin_resources.zip", "kotlin_resources")})
    return expected


class Publication:
    def __init__(self, base, group, artifact, extension, version):
        self.group, self.artifact, self.version = group, artifact, version
        self.directory = base / artifact / version
        self.selected_version = version
        self.metadata_path = None
        self.metadata_bytes = None
        self.signature_versions = {}
        self.payloads = {}
        expected = expected_payloads(artifact, extension)
        if version.endswith("-SNAPSHOT"):
            self._select_snapshot(expected)
        else:
            for suffix, classifier in sorted(expected):
                path = self.directory / artifact_name(artifact, version, suffix, classifier)
                self.payloads[(suffix, classifier)] = require_file(path)
            filenames = {path.name for path in self.payloads.values()}
            unexpected = [path.name for path in self.directory.iterdir()
                          if path.name.startswith(f"{artifact}-{version}")
                          and not path.name.endswith((*CHECKSUM_SUFFIXES, ".asc"))
                          and path.name not in filenames]
            require(not unexpected, f"Unexpected publication payloads: {sorted(unexpected)}")
        # Gradle URLs usually retain the logical SNAPSHOT version, even though
        # Maven writes timestamped filenames. Explicit current filenames work too.
        self.url_paths = {artifact_name(artifact, version, suffix, classifier): path
                          for (suffix, classifier), path in self.payloads.items()}
        self.url_paths.update({path.name: path for path in self.payloads.values()})

    def _select_snapshot(self, expected):
        self.metadata_path = self.directory / "maven-metadata.xml"
        self.metadata_bytes = require_file(self.metadata_path).read_bytes()
        root = ET.fromstring(self.metadata_bytes)
        actual = tuple(root.findtext(key) for key in ("groupId", "artifactId", "version"))
        require(actual == (self.group, self.artifact, self.version),
                f"Snapshot metadata coordinates differ: {actual}")
        timestamp = root.findtext("versioning/snapshot/timestamp")
        build_number = root.findtext("versioning/snapshot/buildNumber")
        require(bool(re.fullmatch(r"\d{8}\.\d{6}", timestamp or "")),
                "Snapshot timestamp missing or invalid")
        require(bool(re.fullmatch(r"[1-9]\d*", build_number or "")),
                "Snapshot build number missing or invalid")
        self.selected_version = f"{self.version[:-9]}-{timestamp}-{build_number}"
        for entry in root.findall("versioning/snapshotVersions/snapshotVersion"):
            suffix = entry.findtext("extension") or ""
            classifier = entry.findtext("classifier") or ""
            value = entry.findtext("value")
            require(bool(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", suffix))
                    and bool(re.fullmatch(r"[A-Za-z0-9_.-]*", classifier)),
                    "Invalid snapshot extension/classifier")
            if suffix.endswith(CHECKSUM_SUFFIXES):
                continue
            if suffix.endswith(".asc"):
                key = (suffix[:-4], classifier)
                require(key not in self.signature_versions, f"Duplicate signature entry: {key}")
                self.signature_versions[key] = value
                continue
            require(value == self.selected_version,
                    f"Mixed snapshot versions: {value} / {self.selected_version}")
            key = (suffix, classifier)
            require(key not in self.payloads, f"Duplicate snapshot entry: {key}")
            path = self.directory / artifact_name(self.artifact, value, suffix, classifier)
            self.payloads[key] = require_file(path)
        actual = self.payloads.keys()
        require(expected == actual,
                f"Snapshot payload set differs: missing={sorted(expected - actual)}, "
                f"unexpected={sorted(actual - expected)}")

    def selected(self, extension, classifier=""):
        return self.payloads[(extension, classifier)]

    def signature(self, key):
        if self.metadata_path is not None:
            require(self.signature_versions.get(key) == self.selected_version,
                    f"Snapshot metadata omits the selected signature for {key}")
        return require_file(Path(str(self.payloads[key]) + ".asc"))

    def resolve(self, url, publications):
        require(isinstance(url, str) and bool(url), "Missing Gradle metadata URL")
        parsed = urlsplit(url)
        require(not (parsed.scheme or parsed.netloc or parsed.query or parsed.fragment),
                f"Expected relative repository URL: {url}")
        relative = Path(unquote(parsed.path))
        require(not relative.is_absolute(), f"Absolute repository URL: {url}")
        logical = (self.directory / relative).resolve()
        for publication in publications.values():
            if logical.parent == publication.directory.resolve():
                require(logical.name in publication.url_paths,
                        f"URL does not select a current payload: {logical.name}")
                return publication.url_paths[logical.name]
        raise ValueError(f"Metadata URL points outside the five publications: {url}")

    def check_unchanged(self):
        if self.metadata_path is not None:
            require(self.metadata_path.read_bytes() == self.metadata_bytes,
                    f"{self.artifact} snapshot metadata changed during verification")


def check_coordinates(data, group, artifact, version, description):
    actual = tuple(data.get(key) for key in ("group", "module", "version"))
    require(actual == (group, artifact, version), f"{description}: {actual}")


def check_self_references(value, group, version):
    if isinstance(value, list):
        for child in value:
            check_self_references(child, group, version)
    elif isinstance(value, dict):
        name = value.get("module", value.get("name"))
        if isinstance(name, str) and name in PUBLICATIONS and "group" in value:
            require(value["group"] == group, f"Wrong self-reference group for {name}")
            actual = value.get("version")
            if isinstance(actual, dict):
                versions = [actual[key] for key in ("requires", "strictly", "prefers") if key in actual]
                require(bool(versions) and all(item == version for item in versions),
                        f"Wrong dependency version for {name}: {actual}")
            else:
                require(actual == version, f"Wrong self-reference version for {name}: {actual}")
        for child in value.values():
            check_self_references(child, group, version)


def check_pom(publication, extension):
    root = ET.parse(publication.selected("pom")).getroot()
    actual = tuple(root.findtext(f"m:{key}", namespaces=POM_NS)
                   for key in ("groupId", "artifactId", "version"))
    require(actual == (publication.group, publication.artifact, publication.version),
            f"POM coordinates differ: {actual}")
    packaging = root.findtext("m:packaging", namespaces=POM_NS) or "jar"
    require(packaging == extension, f"Unexpected POM packaging: {packaging}")
    for dependency in root.findall(".//m:dependency", POM_NS):
        name = dependency.findtext("m:artifactId", namespaces=POM_NS)
        if name in PUBLICATIONS:
            actual = tuple(dependency.findtext(f"m:{key}", namespaces=POM_NS)
                           for key in ("groupId", "artifactId", "version"))
            require(actual == (publication.group, name, publication.version),
                    f"Wrong POM self-reference: {actual}")


def check_seaglass_classes(archive, description):
    require(archive.testzip() is None, f"Corrupt class archive: {description}")
    classes = [entry for entry in archive.infolist()
               if not entry.is_dir() and entry.filename.startswith("dev/mttmcknn/seaglass/")
               and entry.filename.endswith(".class")]
    require(bool(classes), f"Missing Sea Glass classes: {description}")
    for entry in classes:
        data = archive.read(entry)
        require(len(data) >= 10 and data[:4] == b"\xca\xfe\xba\xbe",
                f"Invalid class header: {description}!/{entry.filename}")


def check_archives(publication):
    for (extension, classifier), path in publication.payloads.items():
        if extension in ("jar", "aar", "klib") or extension.endswith(".zip"):
            with zipfile.ZipFile(path) as archive:
                require(archive.testzip() is None, f"Corrupt archive: {path.name}")
                files = [entry.filename for entry in archive.infolist() if not entry.is_dir()]
                # Empty Compose resource ZIPs are valid; Javadoc may be a placeholder.
                if extension != "kotlin_resources.zip":
                    require(bool(files), f"Archive contains no files: {path.name}")
                if classifier == "sources":
                    require(any(name.endswith(".kt") for name in files),
                            f"Missing Kotlin sources: {path.name}")
                if publication.artifact == "sea-glass-jvm" and not classifier:
                    check_seaglass_classes(archive, path.name)
                if extension == "aar":
                    require("classes.jar" in files, "Android AAR omits classes.jar")
                    with zipfile.ZipFile(BytesIO(archive.read("classes.jar"))) as classes:
                        check_seaglass_classes(classes, f"{path.name}!/classes.jar")
        elif extension == "json":
            json.loads(path.read_text(encoding="utf-8"))


def check_module(publication, extension, publications):
    metadata = json.loads(publication.selected("module").read_text(encoding="utf-8"))
    require(metadata.get("formatVersion") == "1.1", "Unexpected Gradle metadata format")
    component = metadata["component"]
    check_coordinates(component, publication.group, "sea-glass", publication.version,
                      "Gradle root component")
    check_self_references(metadata, publication.group, publication.version)
    if publication.artifact != "sea-glass" or "url" in component:
        require(publication.resolve(component.get("url"), publications)
                == publications["sea-glass"].selected("module"),
                "Component URL does not resolve to the selected root metadata")
    variants = metadata["variants"]
    require(isinstance(variants, list) and bool(variants), "No Gradle variants")
    selected_files, linked_targets = set(), set()
    for variant in variants:
        available = variant.get("available-at")
        if available is not None:
            target = available.get("module")
            require(target in publications, f"Unknown available-at target: {target}")
            check_coordinates(available, publication.group, target, publication.version, "available-at")
            require(publication.resolve(available.get("url"), publications)
                    == publications[target].selected("module"),
                    f"available-at URL does not resolve to selected {target} metadata")
            linked_targets.add(target)
        for entry in variant.get("files", []):
            path = publication.resolve(entry.get("url"), publications)
            require(path.parent == publication.directory, "Variant payload belongs to another publication")
            data = path.read_bytes()
            if "size" in entry:
                require(entry["size"] == len(data), f"Variant size mismatch: {path.name}")
            for algorithm in ("sha512", "sha256", "sha1", "md5"):
                if algorithm in entry:
                    require(hashlib.new(algorithm, data).hexdigest() == entry[algorithm],
                            f"Variant {algorithm} mismatch: {path.name}")
            selected_files.add(path)
    require(publication.selected(extension) in selected_files, "Gradle metadata omits binary")
    require(publication.selected("jar", "sources") in selected_files, "Gradle metadata omits sources")
    if publication.artifact == "sea-glass":
        require(linked_targets == set(PUBLICATIONS) - {"sea-glass"},
                f"Root target coverage is incomplete: {sorted(linked_targets)}")


def run_gpg(command, data=None):
    arguments = {"input": data} if data is not None else {"stdin": subprocess.DEVNULL}
    return subprocess.run(command, capture_output=True, timeout=30, **arguments)


@contextmanager
def gpg_command(gpg, public_key, fingerprint):
    command = [gpg, "--no-options", "--batch", "--no-tty", "--no-autostart",
               "--no-auto-check-trustdb", "--no-auto-key-retrieve", "--no-auto-key-import"]
    if public_key is None:
        yield command
        return
    with tempfile.TemporaryDirectory(prefix="sg-pub-") as directory:
        command += ["--homedir", directory]
        certificate = require_file(public_key).read_bytes()
        inspected = run_gpg(command + ["--with-colons", "--import-options", "show-only", "--import"],
                            certificate)
        require(inspected.returncode == 0, "GPG could not inspect the supplied public certificate")
        primary_fingerprints, awaiting_primary = [], False
        for line in inspected.stdout.decode("utf-8", errors="replace").splitlines():
            fields = line.split(":")
            require(fields[0] not in ("sec", "ssb"), "--public-key must not contain secret-key material")
            if fields[0] in ("pub", "sub"):
                awaiting_primary = fields[0] == "pub"
            elif fields[0] == "fpr" and awaiting_primary:
                require(len(fields) > 9, "Malformed public-certificate fingerprint record")
                primary_fingerprints.append(fields[9].upper())
                awaiting_primary = False
        require(primary_fingerprints == [fingerprint],
                "Public certificate must contain exactly the expected primary signing key")
        imported = run_gpg(command + ["--import-options", "import-minimal", "--import"], certificate)
        require(imported.returncode == 0, "GPG could not import the public certificate into the temporary keyring")
        yield command


def check_signature(payload, signature, command, fingerprint):
    result = run_gpg(command + ["--status-fd=1", "--verify", str(signature), str(payload)])
    statuses = [line[len("[GNUPG:] "):].split()
                for line in result.stdout.decode("utf-8", errors="replace").splitlines()
                if line.startswith("[GNUPG:] ")]
    names = {line[0] for line in statuses if line}
    invalid = {"BADSIG", "ERRSIG", "EXPSIG", "EXPKEYSIG", "REVKEYSIG",
               "NO_PUBKEY", "NODATA", "FAILURE", "ERROR"}
    require(result.returncode == 0 and not names.intersection(invalid),
            f"GPG rejected {payload.name}: exit {result.returncode}, status {','.join(sorted(names))}")
    valid = [line for line in statuses if line and line[0] == "VALIDSIG"]
    require(len(valid) == 1 and len(valid[0]) >= 10,
            f"Expected one VALIDSIG for {payload.name}")
    # VALIDSIG's optional tenth argument is the primary key for a signing subkey.
    primary = valid[0][10] if len(valid[0]) > 10 else valid[0][1]
    require(primary.upper() == fingerprint, f"Unexpected signing-key fingerprint for {payload.name}")


def verify(args):
    repository = args.repository.expanduser().resolve()
    base = repository.joinpath(*args.group.split("."))
    report = {"success": False, "repository": str(repository), "group": args.group,
              "version": args.version, "signatures_required": not args.allow_unsigned,
              "fingerprint": args.fingerprint, "payload_count": 0, "signatures_verified": 0,
              "publications": [], "errors": []}
    print(f"Repository: {repository}")
    print("Signature verification: explicitly skipped (--allow-unsigned)" if args.allow_unsigned
          else f"Expected primary signing key: {args.fingerprint}")
    try:
        publications = {name: Publication(base, args.group, name, extension, args.version)
                        for name, extension in PUBLICATIONS.items()}
        report["payload_count"] = sum(len(item.payloads) for item in publications.values())
        context = nullcontext(None) if args.allow_unsigned else gpg_command(
            args.gpg, args.public_key, args.fingerprint)
        with context as command:
            for name, extension in PUBLICATIONS.items():
                publication = publications[name]
                item = {"artifact": name, "selected_version": publication.selected_version,
                        "payload_count": len(publication.payloads), "signatures_verified": 0,
                        "status": "fail"}
                try:
                    check_pom(publication, extension)
                    check_archives(publication)
                    check_module(publication, extension, publications)
                    if not args.allow_unsigned:
                        for key, payload in sorted(publication.payloads.items()):
                            check_signature(payload, publication.signature(key), command, args.fingerprint)
                            item["signatures_verified"] += 1
                            report["signatures_verified"] += 1
                    item["status"] = "pass"
                    print(f"PASS {name}:{publication.selected_version}: coordinates, archives, module links/hashes; "
                          f"{item['signatures_verified']}/{item['payload_count']} signatures verified")
                except ERRORS as error:
                    item["error"] = str(error)
                    print(f"FAIL {name}:{publication.selected_version}: {error}")
                report["publications"].append(item)
        for publication in publications.values():
            publication.check_unchanged()
        report["success"] = all(item["status"] == "pass" for item in report["publications"])
    except ERRORS as error:
        report["errors"].append(str(error))
        print(f"FAIL: {error}")
    passed = sum(item["status"] == "pass" for item in report["publications"])
    print(f"{passed}/{len(PUBLICATIONS)} publications passed; {report['signatures_verified']} signatures verified. "
          "Maven metadata/checksum sidecars are not signed payloads.")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True, help="Local Maven repository root")
    parser.add_argument("--version", required=True, help="X.Y.Z or X.Y.Z-SNAPSHOT")
    parser.add_argument("--group", default=DEFAULT_GROUP, help="Expected Maven group")
    parser.add_argument("--gpg", default="gpg", help="GnuPG executable (default: gpg on PATH)")
    parser.add_argument("--fingerprint", help="Expected full 40-hex primary signing-key fingerprint")
    parser.add_argument("--public-key", type=Path, help="Public certificate to import into an isolated temporary keyring")
    parser.add_argument("--allow-unsigned", action="store_true",
                        help="Explicitly skip all signature checks for CI; no GPG or key is needed")
    parser.add_argument("--output-json", type=Path, help="Write verification metadata to this JSON report")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-SNAPSHOT)?", args.version):
        parser.error("--version must be X.Y.Z or X.Y.Z-SNAPSHOT")
    if not re.fullmatch(r"[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*", args.group):
        parser.error("--group must be a dotted Maven group without path separators")
    if args.allow_unsigned:
        if args.fingerprint or args.public_key:
            parser.error("--allow-unsigned cannot be combined with --fingerprint or --public-key")
    elif not args.fingerprint or not re.fullmatch(r"[0-9a-fA-F]{40}", args.fingerprint):
        parser.error("Signed verification requires --fingerprint with the full 40-hex primary fingerprint")
    if args.fingerprint:
        args.fingerprint = args.fingerprint.upper()
    if args.public_key:
        args.public_key = args.public_key.expanduser().resolve()
    report = verify(args)
    if args.output_json:
        try:
            output = args.output_json.expanduser().resolve()
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        except OSError as error:
            print(f"FAIL: could not write JSON report: {error}", file=sys.stderr)
            return 1
    return 0 if report["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
