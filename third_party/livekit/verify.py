#!/usr/bin/env python3
"""Verify the exact checked-in JAIN-free LiveKit publication and source."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import pathlib
import stat
import sys
import tarfile
import xml.etree.ElementTree as ET
import zipfile


class VerificationError(RuntimeError):
    pass


COORDINATE = "africa.kit.livekit:livekit-android:2.27.0-kitpay.1"
GROUP = "africa.kit.livekit"
MODULE = "livekit-android"
VERSION = "2.27.0-kitpay.1"
FORK_COMMIT = "fe82899113a2f6468be4cb8b0e757052cb539903"
FORK_TREE = "5bee13a3935f4d398c3b3f0be66705c88d70ced2"
SOURCE_REPOSITORY = "https://github.com/kitafrica33/kit-pay-android-source"
UPSTREAM_COMMIT = "5011da6fc302fefcdc869faecae2e07055f1c8c5"
PROTOCOL_COMMIT = "8381f2180c45ab926b3ebf19df0608f1dadcac1e"
VENDOR = pathlib.PurePosixPath("third_party/livekit")
MAVEN_VERSION = VENDOR / "maven/africa/kit/livekit/livekit-android" / VERSION
BASE_NAME = f"livekit-android-{VERSION}"
SOURCE_ROOT = f"{BASE_NAME}-source"

EXPECTED_ARTIFACTS: dict[str, tuple[int, str]] = {
    f"maven/africa/kit/livekit/livekit-android/{VERSION}/{BASE_NAME}.aar": (
        2_582_447,
        "875ca7e0a1b9768f414440d2889504d5192800840a385fbe94cd407096724a9b",
    ),
    f"maven/africa/kit/livekit/livekit-android/{VERSION}/{BASE_NAME}-sources.jar": (
        583_909,
        "dc6d6095c0db6e3941c10a1f443671528b19afe0ba4541292ac82f0f9689d961",
    ),
    f"maven/africa/kit/livekit/livekit-android/{VERSION}/{BASE_NAME}-javadoc.jar": (
        3_854_748,
        "f9a8c9cf67b54168337cba58e939480915feb13db34f366ba2ac1a69e1fd0acf",
    ),
    f"maven/africa/kit/livekit/livekit-android/{VERSION}/{BASE_NAME}.module": (
        6_424,
        "ff46f588e4a9589e11b8d85021c1577553096911f85a1bd144f8c68aae4d8894",
    ),
    f"maven/africa/kit/livekit/livekit-android/{VERSION}/{BASE_NAME}.pom": (
        3_929,
        "07e3d790483968801ad1ee3b591917d8edc42a64fc3c66eced7bdb752a1f95fc",
    ),
    f"source/{BASE_NAME}-source.tar.gz": (
        1_698_290,
        "8cae9d2cfa17d1c0b4687c160118b1781f511e09fe653fda18812a1c8518011b",
    ),
    f"{BASE_NAME}.patch": (
        54_133,
        "4a774f9d4a923eb50a0b7f60c4e1ee8e5be0ac0a36f3a6dcaac1c6811ee9f134",
    ),
    "LICENSE": (
        11_357,
        "58d1e17ffe5109a7ae296caafcadfdbe6a7d176f0bc4ab01e12a689b0499d8bd",
    ),
    "NOTICE": (
        5_305,
        "dae3375b7a34fa5e154f989e88942fda5919eabba5d6f6e70ae88854cd3ae984",
    ),
    "KIT_PAY_FORK.md": (
        2_058,
        "0dbf484cab879a7130c233cf2a9082471477f7a89bcd2be45bf6622cdb2f69a7",
    ),
}

EXPECTED_DEPENDENCIES = {
    "androidx.annotation:annotation:1.7.1",
    "androidx.core:core:1.13.1",
    "com.auth0.android:jwtdecode:2.0.2",
    "com.github.davidliu:audioswitch:039a35aefab7747c557242fa216c9ea11743b604",
    "com.google.dagger:dagger:2.46",
    "com.google.protobuf:protobuf-javalite:3.22.0",
    "com.squareup.okhttp3:okhttp:4.12.0",
    "com.vdurmont:semver4j:3.1.0",
    "io.github.webrtc-sdk:android-prefixed:144.7559.09",
    "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.25",
    "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0",
}

FORBIDDEN_BINARY_MARKERS = (
    b"javax/sip",
    b"javax.sip",
    b"gov/nist/javax/sip",
    b"android-jain-sip",
    b"jain-sip-ri",
    b"jainsdputils",
)


def fail(message: str) -> None:
    raise VerificationError(message)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=pathlib.Path)
    return parser.parse_args()


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def relative_vendor_path(relative: str) -> pathlib.PurePosixPath:
    path = pathlib.PurePosixPath(relative)
    if path.is_absolute() or ".." in path.parts or str(path) != relative:
        fail(f"Unsafe vendored artifact path in provenance: {relative!r}")
    return path


def verify_primary_artifacts(root: pathlib.Path, provenance: dict[str, object]) -> None:
    vendor = root / VENDOR
    if not vendor.is_dir() or vendor.is_symlink():
        fail("The checked-in LiveKit vendor directory is missing or unsafe.")

    for candidate in vendor.rglob("*"):
        if candidate.is_symlink():
            fail(f"Vendored LiveKit content must not contain a symlink: {candidate}")

    recorded = provenance.get("artifacts")
    if not isinstance(recorded, dict) or set(recorded) != set(EXPECTED_ARTIFACTS):
        fail("PROVENANCE.json does not identify the exact reviewed artifact set.")

    for relative, (expected_size, expected_hash) in EXPECTED_ARTIFACTS.items():
        path = vendor / relative_vendor_path(relative)
        if not path.is_file() or path.is_symlink():
            fail(f"Reviewed vendored LiveKit file is missing or unsafe: {relative}")
        actual_size = path.stat().st_size
        actual_hash = sha256(path)
        if actual_size != expected_size or actual_hash != expected_hash:
            fail(
                f"{relative} differs from the reviewed bytes: expected "
                f"{expected_size} bytes/{expected_hash}, got {actual_size}/{actual_hash}."
            )
        entry = recorded.get(relative)
        if entry != {"size": expected_size, "sha256": expected_hash}:
            fail(f"PROVENANCE.json has incorrect size/hash evidence for {relative}.")


def verify_provenance(root: pathlib.Path) -> dict[str, object]:
    path = root / VENDOR / "PROVENANCE.json"
    if not path.is_file() or path.is_symlink():
        fail("The vendored LiveKit provenance manifest is missing or unsafe.")
    try:
        provenance = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"The vendored LiveKit provenance manifest is invalid: {error}")
    if not isinstance(provenance, dict) or provenance.get("schema_version") != 1:
        fail("The vendored LiveKit provenance schema is not supported.")

    component = provenance.get("component")
    if component != {
        "coordinate": COORDINATE,
        "group": GROUP,
        "module": MODULE,
        "version": VERSION,
        "licence": "Apache-2.0",
    }:
        fail("The vendored LiveKit component identity is not the reviewed coordinate.")

    fork = provenance.get("fork")
    required_fork = {
        "commit": FORK_COMMIT,
        "tree": FORK_TREE,
        "commit_epoch": 1_784_578_001,
        "source_repository": SOURCE_REPOSITORY,
        "upstream_repository": "https://github.com/livekit/client-sdk-android",
        "upstream_tag": "v2.27.0",
        "upstream_commit": UPSTREAM_COMMIT,
        "upstream_tree": "c7537f3ae7418b56a17006737eaee49ce1e1cb44",
        "protocol_repository": "https://github.com/livekit/protocol",
        "protocol_commit": PROTOCOL_COMMIT,
        "protocol_tree": "4d9270438c2d086de8e556abc09afaa96d77bd89",
    }
    if fork != required_fork:
        fail("The vendored LiveKit fork/base/submodule provenance is not exact.")

    publication = provenance.get("publication")
    if not isinstance(publication, dict):
        fail("The LiveKit publication provenance is missing.")
    if (
        publication.get("gradle_version") != "8.9"
        or publication.get("repository_path") != "third_party/livekit/maven"
        or publication.get("remote_publication_required") is not False
    ):
        fail("The vendored LiveKit publication provenance is not the reviewed local build.")
    return provenance


def check_forbidden(data: bytes, label: str) -> None:
    normalized = data.lower()
    for marker in FORBIDDEN_BINARY_MARKERS:
        if marker in normalized:
            fail(f"JAIN-SIP marker {marker.decode()} remains in {label}.")


def safe_zip_entries(document: zipfile.ZipFile, label: str) -> list[zipfile.ZipInfo]:
    entries = document.infolist()
    names = [entry.filename for entry in entries]
    if len(names) != len(set(names)):
        fail(f"{label} contains duplicate ZIP entry names.")
    for entry in entries:
        path = pathlib.PurePosixPath(entry.filename)
        if path.is_absolute() or ".." in path.parts or "\\" in entry.filename:
            fail(f"{label} contains an unsafe ZIP path: {entry.filename}")
        mode = entry.external_attr >> 16
        if mode and stat.S_ISLNK(mode):
            fail(f"{label} contains a symbolic link: {entry.filename}")
    return entries


def scan_zip_bytes(data: bytes, label: str) -> set[str]:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as document:
            entries = safe_zip_entries(document, label)
            names = {entry.filename for entry in entries}
            for entry in entries:
                if entry.is_dir():
                    continue
                payload = document.read(entry)
                check_forbidden(entry.filename.encode("utf-8"), label)
                check_forbidden(payload, f"{label}!/{entry.filename}")
                if entry.filename.lower().endswith((".jar", ".zip")):
                    scan_zip_bytes(payload, f"{label}!/{entry.filename}")
            return names
    except zipfile.BadZipFile as error:
        fail(f"{label} is not a valid ZIP archive: {error}")


def verify_maven_publication(root: pathlib.Path) -> None:
    version_dir = root / MAVEN_VERSION
    primary_names = {
        f"{BASE_NAME}.aar",
        f"{BASE_NAME}-sources.jar",
        f"{BASE_NAME}-javadoc.jar",
        f"{BASE_NAME}.module",
        f"{BASE_NAME}.pom",
    }
    algorithms = ("md5", "sha1", "sha256", "sha512")
    expected_names = primary_names | {
        f"{name}.{algorithm}" for name in primary_names for algorithm in algorithms
    }
    actual_names = {path.name for path in version_dir.iterdir() if path.is_file()}
    if actual_names != expected_names:
        fail("The checked-in LiveKit Maven publication file set is not exact.")

    for name in primary_names:
        artifact = version_dir / name
        payload = artifact.read_bytes()
        for algorithm in algorithms:
            expected = hashlib.new(algorithm, payload).hexdigest().encode("ascii")
            sidecar = (version_dir / f"{name}.{algorithm}").read_bytes()
            if sidecar != expected:
                fail(f"Maven checksum sidecar is incorrect: {name}.{algorithm}")

    aar = version_dir / f"{BASE_NAME}.aar"
    aar_names = scan_zip_bytes(aar.read_bytes(), aar.relative_to(root).as_posix())
    if "classes.jar" not in aar_names:
        fail("The reviewed LiveKit AAR does not contain classes.jar.")

    sources = version_dir / f"{BASE_NAME}-sources.jar"
    source_names = scan_zip_bytes(sources.read_bytes(), sources.relative_to(root).as_posix())
    expected_sdp = "io/livekit/android/webrtc/SdpUtils.kt"
    removed_sdp = "io/livekit/android/webrtc/JainSdpUtils.kt"
    if expected_sdp not in source_names or removed_sdp in source_names:
        fail("The LiveKit sources JAR does not contain the reviewed JAIN-free SDP source.")

    javadoc = version_dir / f"{BASE_NAME}-javadoc.jar"
    scan_zip_bytes(javadoc.read_bytes(), javadoc.relative_to(root).as_posix())


def xml_text(element: ET.Element, name: str, namespace: str) -> str:
    child = element.find(f"{namespace}{name}")
    if child is None or child.text is None:
        fail(f"The vendored LiveKit POM lacks {name}.")
    return child.text


def verify_metadata(root: pathlib.Path) -> None:
    version_dir = root / MAVEN_VERSION
    pom_path = version_dir / f"{BASE_NAME}.pom"
    try:
        pom = ET.fromstring(pom_path.read_bytes())
    except ET.ParseError as error:
        fail(f"The vendored LiveKit POM is malformed: {error}")
    namespace = "{http://maven.apache.org/POM/4.0.0}"
    if (
        xml_text(pom, "groupId", namespace) != GROUP
        or xml_text(pom, "artifactId", namespace) != MODULE
        or xml_text(pom, "version", namespace) != VERSION
        or xml_text(pom, "packaging", namespace) != "aar"
    ):
        fail("The vendored LiveKit POM identity is not exact.")
    scm = pom.find(f"{namespace}scm")
    if (
        xml_text(pom, "url", namespace) != SOURCE_REPOSITORY
        or scm is None
        or xml_text(scm, "connection", namespace)
        != f"scm:git:{SOURCE_REPOSITORY}.git"
        or xml_text(scm, "developerConnection", namespace)
        != "scm:git:ssh://git@github.com/kitafrica33/kit-pay-android-source.git"
        or xml_text(scm, "url", namespace) != SOURCE_REPOSITORY
    ):
        fail("The vendored LiveKit POM source repository is not canonical.")
    dependencies: set[str] = set()
    dependency_parent = pom.find(f"{namespace}dependencies")
    if dependency_parent is None:
        fail("The vendored LiveKit POM has no dependency metadata.")
    for dependency in dependency_parent.findall(f"{namespace}dependency"):
        dependencies.add(
            ":".join(
                (
                    xml_text(dependency, "groupId", namespace),
                    xml_text(dependency, "artifactId", namespace),
                    xml_text(dependency, "version", namespace),
                )
            )
        )
    if dependencies != EXPECTED_DEPENDENCIES:
        fail("The vendored LiveKit POM dependency set differs from the reviewed set.")
    check_forbidden(pom_path.read_bytes(), pom_path.relative_to(root).as_posix())

    module_path = version_dir / f"{BASE_NAME}.module"
    try:
        metadata = json.loads(module_path.read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        fail(f"The vendored LiveKit Gradle metadata is malformed: {error}")
    if metadata.get("component") != {
        "group": GROUP,
        "module": MODULE,
        "version": VERSION,
        "attributes": {"org.gradle.status": "release"},
    }:
        fail("The vendored LiveKit Gradle module identity is not exact.")
    module_dependencies: set[str] = set()
    for variant in metadata.get("variants", []):
        for dependency in variant.get("dependencies", []):
            required = dependency.get("version", {}).get("requires")
            module_dependencies.add(
                f"{dependency.get('group')}:{dependency.get('module')}:{required}"
            )
    if module_dependencies != EXPECTED_DEPENDENCIES:
        fail("The vendored LiveKit Gradle metadata dependency set is not exact.")
    check_forbidden(module_path.read_bytes(), module_path.relative_to(root).as_posix())


def verify_source_archive(root: pathlib.Path) -> None:
    source_path = root / VENDOR / "source" / f"{BASE_NAME}-source.tar.gz"
    try:
        with tarfile.open(source_path, "r:gz") as document:
            members = document.getmembers()
            names = [member.name for member in members]
            if not members or len(names) != len(set(names)):
                fail("The vendored LiveKit full-source archive is empty or duplicated.")
            archive_root = pathlib.PurePosixPath(SOURCE_ROOT)
            by_name: dict[str, tarfile.TarInfo] = {}
            for member in members:
                path = pathlib.PurePosixPath(member.name)
                if (
                    path.is_absolute()
                    or ".." in path.parts
                    or not path.is_relative_to(archive_root)
                    or not (member.isfile() or member.isdir())
                ):
                    fail(f"Unsafe entry in the LiveKit full-source archive: {member.name}")
                by_name[member.name] = member

            required = {
                f"{SOURCE_ROOT}/.gitmodules",
                f"{SOURCE_ROOT}/KIT_PAY_FORK.md",
                f"{SOURCE_ROOT}/LICENSE",
                f"{SOURCE_ROOT}/NOTICE",
                f"{SOURCE_ROOT}/gradle.properties",
                f"{SOURCE_ROOT}/gradle/libs.versions.toml",
                f"{SOURCE_ROOT}/livekit-android-sdk/build.gradle",
                f"{SOURCE_ROOT}/livekit-android-sdk/src/main/java/io/livekit/android/webrtc/SdpUtils.kt",
                f"{SOURCE_ROOT}/livekit-android-test/src/test/java/io/livekit/android/webrtc/SdpUtilsTest.kt",
                f"{SOURCE_ROOT}/livekit-android-test/src/test/java/io/livekit/android/room/SdpMungingTest.kt",
                f"{SOURCE_ROOT}/protocol/LICENSE",
                f"{SOURCE_ROOT}/protocol/NOTICE",
                f"{SOURCE_ROOT}/protocol/protobufs/livekit_rtc.proto",
            }
            if not required.issubset(by_name):
                missing = sorted(required - set(by_name))
                fail(f"The LiveKit full-source archive is incomplete: {', '.join(missing)}")
            removed = (
                f"{SOURCE_ROOT}/livekit-android-sdk/src/main/java/"
                "io/livekit/android/webrtc/JainSdpUtils.kt"
            )
            if removed in by_name:
                fail("The removed JAIN-SDP implementation remains in the full source archive.")

            def archived(relative: str) -> bytes:
                name = f"{SOURCE_ROOT}/{relative}"
                handle = document.extractfile(by_name[name])
                if handle is None:
                    fail(f"Could not read required source archive member {name}.")
                return handle.read()

            for name in ("LICENSE", "NOTICE", "KIT_PAY_FORK.md"):
                if archived(name) != (root / VENDOR / name).read_bytes():
                    fail(f"The source archive {name} differs from the retained fork copy.")
            build_definitions = b"\n".join(
                archived(name)
                for name in (
                    "gradle/libs.versions.toml",
                    "livekit-android-sdk/build.gradle",
                    "livekit-android-test/build.gradle",
                )
            ).lower()
            for marker in (
                b"javax.sip:android-jain-sip-ri",
                b"libs.android.jain.sip.ri",
                b"libs.android-jain-sip-ri",
            ):
                if marker in build_definitions:
                    fail("The JAIN-SIP dependency remains in the full source build definitions.")
    except (tarfile.TarError, OSError) as error:
        fail(f"The vendored LiveKit full-source archive is invalid: {error}")


def verify_consumer_binding(root: pathlib.Path) -> None:
    settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    required_settings = (
        "exclusiveContent {",
        'name = "KitPayVendoredLiveKit"',
        'url = uri(rootDir.resolve("third_party/livekit/maven"))',
        f'includeGroup("{GROUP}")',
    )
    if not all(marker in settings for marker in required_settings):
        fail("settings.gradle.kts does not bind LiveKit exclusively to the vendored repository.")
    if "mavenLocal()" in settings:
        fail("mavenLocal() must not participate in Kit Pay dependency resolution.")

    catalog = (root / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    if catalog.count(f'livekit = "{VERSION}"') != 1:
        fail("The version catalog does not pin the reviewed LiveKit fork exactly once.")
    declaration = (
        f'livekit-android = {{ group = "{GROUP}", name = "{MODULE}", '
        'version.ref = "livekit" }'
    )
    if catalog.count(declaration) != 1:
        fail("The LiveKit version-catalog coordinate is not the reviewed fork.")
    for forbidden in (
        'group = "io.livekit", name = "livekit-android"',
        "javax.sip:android-jain-sip-ri",
    ):
        if forbidden in catalog:
            fail(f"The version catalog contains prohibited dependency marker {forbidden}.")

    app_build = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    if app_build.count("implementation(libs.livekit.android)") != 1:
        fail("The application must consume the pinned LiveKit catalog entry exactly once.")


def main() -> int:
    root = parse_args().root.resolve()
    try:
        provenance = verify_provenance(root)
        verify_primary_artifacts(root, provenance)
        verify_maven_publication(root)
        verify_metadata(root)
        verify_source_archive(root)
        verify_consumer_binding(root)
    except VerificationError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(
        "Verified vendored LiveKit "
        f"{COORDINATE} ({FORK_COMMIT}); exact hashes and JAIN-free contents passed."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
