# Kit Pay Android app-wide distribution clearance decision

- Document type: App-Wide Third-Party Licence Compatibility Review
- Decision classification: App-Wide Internal Distribution Clearance Approval
- Approval reference: `KIT-PAY-APP-WIDE-RELEASE-CLEARANCE-2026-0722-001`
- Application: Kit Pay (`com.kit.wallet`)
- Release scope: All future Kit Pay versions and version codes
- Candidate binding: None — app-wide standing approval
- Review date: 2026-07-22
- Decision status: **CLEARED**
- Management authority: `docs/internal-release-approval-policy-2026-07-22.md`
- Management authority SHA-256: `62825425ff85d2ccf2a173c760e4c9779b29fc3108c97b2c20f4492a89bfb5c5`

## 1. Decision summary

Namisi Arnold Paul, Chief Executive Officer of Kit POS Uganda Limited, approves all
future releases of the Kit Pay application under the referenced management policy.
This clearance is app-specific and standing: it is not bound to a release candidate,
version name, version code, tag, commit, APK, or AAB. A version-name or version-code
change does not by itself require a new approval.

This decision records the reviewed third-party licence, dependency, signing, and
distribution baseline for Kit Pay. Releases that remain within that baseline are
cleared subject to the continuing conditions below. A material trigger suspends the
clearance for the affected change until the relevant area is re-reviewed and this
app-wide record is amended or replaced.

The Company accepts responsibility for release decisions and for the actions of its
software development and engineering team taken under the policy. This is an internal
company decision and does not purport to be an external-counsel opinion.

## 2. App-wide scope

The approval applies to package `com.kit.wallet`, product Kit Pay, for all future
version names and version codes. Version metadata in a generated runtime-clearance
record describes the artifact being built; it does not narrow or replace this standing
decision.

Routine application fixes, features, build metadata, release metadata, and version
changes remain approved when they do not materially change the reviewed dependency,
licence, signing, or distribution architecture. A new candidate-specific executive
approval is not required solely because engineering creates a new release.

The historical code-11, code-12, code-13, and code-14 decisions remain immutable
candidate records and retain their original references, evidence, and scope. This
app-wide decision does not relabel, move, or reuse their tags, source releases, signed
artifacts, or candidate identities.

## 3. Approved application and signing identity

- Product: Kit Pay
- Android application ID: `com.kit.wallet`
- Approved release-signing certificate SHA-256: `CA:DF:18:E8:5B:F9:32:81:52:F3:48:1A:E0:1E:DB:52:CA:0F:77:4C:F3:65:CF:A9:D9:9F:D1:1B:B6:60:08:75`

Direct-download APKs and Play-upload AABs cleared by this decision must carry the
approved application ID and be signed with the approved release certificate. APKs
generated and delivered by Google Play may carry the separately registered Play App
Signing certificate.

## 4. Reviewed runtime baseline

- Reviewed runtime inventory SHA-256: `4f63a48022e82f037a9c3e8de80d847f2a54b5948ce6f51be8a8863bd83ca330`
- Reviewed runtime graph manifest SHA-256: `1602aeac63688c2dd85062b6b951aa58e7df1715b7da125b1f487efcf385a5a9`
- Release runtime components: 188
- Direct components: 32
- Transitive components: 156
- Runtime artifact archives: 151

### Reviewed Google components

| Maven coordinate | AAR SHA-256 | POM SHA-256 |
| --- | --- | --- |
| `com.google.android.gms:play-services-base:18.1.0` | `4eca56ceecd4325a376cd843af56377e2376ce284d0c6f05a5d0a82f4c1bf8cd` | `30df78ba3ead133c2b36784b425a9eeee7f02531907e8aaee4e8922354f732a7` |
| `com.google.android.gms:play-services-basement:18.3.0` | `6c11ae3eb2dd7f17373f919c4c557a70e4cf891bc0c9b66926a0a6445d654352` | `9cef5dc9a6950ff09a85ff522b476f855eb7ef2373aa4c17339cb114ac5397e2` |
| `com.google.android.gms:play-services-cloud-messaging:17.2.0` | `27255e7fe9706483816b158db25cf319f6a26a0566feff41597ce8807a350e37` | `65f5833e621368dfb0eb203dcafcf070c5cece5a60ca18842ce7081d440e0419` |
| `com.google.android.gms:play-services-stats:17.0.2` | `dd4314a53f49a378ec146103d36232b96c75454d29526336ccbdf132941764d3` | `68bb2bc131c0939858e3166f777c63903b990c7fcae3c1dea70a312937f6a73f` |
| `com.google.android.gms:play-services-tasks:18.1.0` | `d60575eae39350e6234858bc9d7d775375707ae82a684e6caf7f3e41a12e25a2` | `cf29ed846108d7a8f2c17d8ef5b63399735757c5a09bb663d7e784e02ad84bcd` |
| `com.google.firebase:firebase-iid-interop:17.1.0` | `0b7c3721c84b62e70415307239ed4a7f998989084bf2833f90b9f5bea3095a05` | `53f269f5e127ac21eba2d80a985abc8d2c8f24cdca9be0284448ee9ea343e34b` |

The reviewed application also links `org.signal:libsignal-android:0.97.4` and
`org.signal:libsignal-client:0.97.4` under `AGPL-3.0-only`.

## 5. Applicable Google terms

- Terms: Android Software Development Kit License Agreement
- URL: `https://developer.android.com/studio/terms`
- Version relied upon: effective 2026-04-28
- Retrieved: 2026-07-20
- Retrieved HTML SHA-256: `8bd88dc1144a7d12818687d680d6a9f9e8a2f1ee62c43a8e21f5c6a75f6977cd`

## 6. Reviewed distribution architecture

The reviewed Kit Pay architecture:

- isolates Firebase Cloud Messaging behind a dedicated notification adapter;
- does not modify Google-provided AAR binaries;
- retains required third-party notices;
- uses FCM for opaque secure-message wakeups, incoming-call notifications, token
  registration, and user alerts;
- communicates with Google Play services through supported Android APIs;
- distributes the application under AGPL-3.0-only with exact corresponding source;
- retains the reviewed libsignal session and post-quantum protocol boundary;
- keeps attachment plaintext, private identities, and attachment key material outside
  the server boundary; and
- keeps secure-messaging recovery and lifecycle processing fail-closed when state
  cannot be authenticated or verified.

## 7. Compatibility determination

The reviewed Google Android client terms and runtime artefacts, AGPL-3.0-only libsignal
dependencies, corresponding-source obligations, provider-neutral FCM adapter,
release-signing identity, and distribution model form the standing Kit Pay baseline.
Distribution of future Kit Pay releases is approved while that baseline remains
materially unchanged and the conditions in this decision are met.

## 8. Continuing distribution conditions

1. Required third-party notices and exact AGPL corresponding source must be published
   before each matching binary.
2. The release runtime inventory must be generated and checked for each release.
3. A runtime change that activates a material re-review trigger must not be treated as
   covered until the affected area is re-reviewed and this app-wide decision is amended
   or replaced.
4. Direct-download APKs and Play-upload AABs must use the approved application ID and
   release-signing certificate.
5. The final APK/AAB must pass the applicable signing, dependency, source-publication,
   and release-validation gates.
6. The provider-neutral FCM boundary, AGPL corresponding-source model, libsignal
   licensing/session boundary, and fail-closed secure-messaging properties must remain
   materially unchanged unless re-reviewed.
7. Version-name, version-code, tag, commit, build-time, and artifact-hash changes do not
   require a new management approval merely because those per-release values change.

## 9. Material re-review triggers

The following material changes require re-review before an affected release may rely
on this clearance:

- introduction, removal, or material version change of a significant runtime
  dependency;
- a third-party licence change or new licence obligation;
- a change to Google licensing terms affecting the reviewed components;
- migration away from the reviewed provider-neutral FCM architecture;
- a material change to libsignal licensing or the Signal session boundary;
- a material change to the AGPL corresponding-source or binary distribution model;
- introduction of an additional proprietary SDK;
- a major application-architecture change affecting third-party linking, licensing,
  privacy, or distribution; or
- a change to the approved Android application ID or release-signing identity.

A trigger requires a new or amended app-level review record, not a candidate-specific
approval merely because a version changed. In the absence of a trigger, this app-wide
decision remains in force for future Kit Pay releases.

## 10. Per-release technical verification

Each release pipeline must verify its runtime inventory, required notices,
corresponding source, application ID, approved signer, and release-test result. These
technical checks produce per-release evidence but do not narrow this standing decision
or require a new executive signature when only version or artifact metadata changes.

## 11. Approval

- Reviewer: Namisi Arnold Paul
- Reviewer title: Chief Executive Officer
- Organization: Kit POS Uganda Limited
- Jurisdiction: Uganda
- Approval reference identifier: `KIT-PAY-APP-WIDE-RELEASE-CLEARANCE-2026-0722-001`
- Review date: 2026-07-22
- Final decision: **CLEARED**
- Management authority record: `docs/internal-release-approval-policy-2026-07-22.md`
- Management authority SHA-256: `62825425ff85d2ccf2a173c760e4c9779b29fc3108c97b2c20f4492a89bfb5c5`
- Signed: Namisi Arnold Paul
- Effective date: 2026-07-22

All future releases of the Kit Pay application may proceed under the continuing
conditions and material re-review triggers stated in this decision and the referenced
management policy.
