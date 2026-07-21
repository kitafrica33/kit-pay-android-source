# Vendored Kit Pay LiveKit Android fork

Kit Pay resolves calls from the checked-in Maven repository in this directory:

```text
africa.kit.livekit:livekit-android:2.27.0-kitpay.1
```

The artifact is the Apache-2.0 LiveKit Android SDK 2.27.0 fork at commit
`fe82899113a2f6468be4cb8b0e757052cb539903`. It is based on upstream tag
`v2.27.0` (`5011da6fc302fefcdc869faecae2e07055f1c8c5`) and removes the
`javax.sip:android-jain-sip-ri` dependency. The small SDP-munging surface is
implemented internally by a lossless line model. The public API packages remain
`io.livekit.android.*`, so application source does not change.

The canonical corresponding-source repository is
`https://github.com/kitafrica33/kit-pay-android-source`.

`settings.gradle.kts` gives the `africa.kit.livekit` group exclusive access to
this file repository. It cannot fall through to Maven Central, `mavenLocal()`, or
an unpublished remote repository. `PROVENANCE.json` pins every primary artifact,
the fork/base/submodule commits and the full-source archive. Maven checksum
sidecars are retained exactly as emitted by the reviewed publication.

Run the non-Gradle integrity gate from the Kit Pay Android root:

```bash
python3 -B third_party/livekit/verify.py --root "$PWD"
```

The gate verifies exact hashes and sizes; Maven metadata; the local repository
binding; safe archive structure; full source presence; and the absence of the
JAIN-SIP dependency/classes from the AAR, sources JAR and publication metadata.
Gradle `check`, CI, SBOM generation and release signing also invoke this gate.

## Corresponding source

`source/livekit-android-2.27.0-kitpay.1-source.tar.gz` contains the complete fork
tree plus the exact contents of its `protocol` submodule. The source archive is
deterministic: all entries use the fork commit timestamp, numeric owner/group 0,
sorted POSIX paths and gzip without a timestamp. A second independent generation
produced the same SHA-256 recorded in `PROVENANCE.json`.

The generated Maven sources JAR is included for IDE navigation, but it does not
replace the full archive. `livekit-android-2.27.0-kitpay.1.patch` is the exact
full-index diff from upstream 2.27.0 to the fork commit.

The fork's `LICENSE`, `NOTICE` and modification notes are retained here and in
the full source archive. The SDK remains Apache-2.0; portions identified by its
NOTICE retain their respective terms.
