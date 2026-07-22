# Kit Pay Android distribution clearance decision record — code 14

- Document type: Third-Party License Compatibility Review
- Decision classification: Internal Distribution Clearance Approval
- Approval reference: `KIT-PAY-AGPL-GOOGLE-FCM-CLEARANCE-2026-0722-004`
- Application: `com.kit.wallet`
- Product: Kit Pay
- Candidate version: `0.2.2` (`versionCode` 14)
- Review date: 2026-07-22
- Decision status: **CLEARED**
- Management authority: `docs/internal-release-approval-policy-2026-07-22.md`

## 1. Decision summary

Under the approved Kit Pos Uganda Limited internal release approval policy, engineering
reviewed the code-14 candidate identity, runtime dependency inventory, third-party
notices, applicable terms, distribution architecture, and the secure-messaging recovery
changes described below. Approval is granted for distribution under the conditions in
this record.

The engineering team acts as the Company's operational release authority and does not
purport to provide external legal advice. Executive management accepts responsibility
for the release decision under the referenced policy.

## 2. Code-14 scope

Code 14 is a recovery release for secure-messaging activation after an account switch
or replay of append-only device lifecycle history. When authenticated server state says
that the current device is enrolled but the account-scoped private enrollment was
missing, the client stops messaging activation and captures the exact authentication
session, activation generation, enrollment epoch, registration identifier, identity-key
digest, and bundle version. Before the network request, it stores that target in the
existing authenticated, AEAD-protected session record. A validated response atomically
upgrades the same record with the resulting enrollment epoch, which is retained through
local erasure and re-enrollment. The client erases only unusable local messaging state
under the exact authentication and activation fences, while the backend atomically
proves and removes only the captured messaging enrollment and advances its epoch. The
authenticated device and session remain active after a successful reset, and no fresh
keys are published until the exact reset proof is confirmed. A transient, cancelled, or
ambiguous request retains the captured target for retry across process death. A rejected,
invalid, or stale session clears only that exact local session and requires fresh
authentication; it can never reset a replacement enrollment.

After key reconciliation, the client pins the exact current enrollment epoch,
registration identifier, identity-key digest, and bundle version. A historical
self-revocation or identity-change event is ignored only after an authenticated
key-status refresh confirms that exact reconciled identity is still current. A confirmed
unenrolled state routes only the previously pinned target through the idempotent backend
reset/proof flow; a changed identity may attempt that same pinned target only, and a
stale-target response requires fresh authentication.
Malformed or unverifiable state remains fail-closed. Temporary server capability
unavailability is retryable rather than a permanent local quarantine, while activation
still requires the exact reviewed v2 post-quantum protocol advertisement. The UI
refreshes capability state after local activation becomes ready.

These changes do not add or remove a third-party runtime dependency, change a reviewed
third-party licence, change the FCM adapter boundary, change the libsignal version or
licence, or change the AGPL corresponding-source distribution model.

Decision `KIT-PAY-AGPL-GOOGLE-FCM-CLEARANCE-2026-0722-003` remains preserved for the
immutable code-13 candidate. This record does not move or reuse the code-13 identity,
tag, source release, or signed artifacts.

## 3. Reviewed Google components

| Maven coordinate | AAR SHA-256 | POM SHA-256 |
| --- | --- | --- |
| `com.google.android.gms:play-services-base:18.1.0` | `4eca56ceecd4325a376cd843af56377e2376ce284d0c6f05a5d0a82f4c1bf8cd` | `30df78ba3ead133c2b36784b425a9eeee7f02531907e8aaee4e8922354f732a7` |
| `com.google.android.gms:play-services-basement:18.3.0` | `6c11ae3eb2dd7f17373f919c4c557a70e4cf891bc0c9b66926a0a6445d654352` | `9cef5dc9a6950ff09a85ff522b476f855eb7ef2373aa4c17339cb114ac5397e2` |
| `com.google.android.gms:play-services-cloud-messaging:17.2.0` | `27255e7fe9706483816b158db25cf319f6a26a0566feff41597ce8807a350e37` | `65f5833e621368dfb0eb203dcafcf070c5cece5a60ca18842ce7081d440e0419` |
| `com.google.android.gms:play-services-stats:17.0.2` | `dd4314a53f49a378ec146103d36232b96c75454d29526336ccbdf132941764d3` | `68bb2bc131c0939858e3166f777c63903b990c7fcae3c1dea70a312937f6a73f` |
| `com.google.android.gms:play-services-tasks:18.1.0` | `d60575eae39350e6234858bc9d7d775375707ae82a684e6caf7f3e41a12e25a2` | `cf29ed846108d7a8f2c17d8ef5b63399735757c5a09bb663d7e784e02ad84bcd` |
| `com.google.firebase:firebase-iid-interop:17.1.0` | `0b7c3721c84b62e70415307239ed4a7f998989084bf2833f90b9f5bea3095a05` | `53f269f5e127ac21eba2d80a985abc8d2c8f24cdca9be0284448ee9ea343e34b` |

## 4. Additional reviewed dependency

The application links `org.signal:libsignal-android:0.97.4` and
`org.signal:libsignal-client:0.97.4` under `AGPL-3.0-only`. Code 14 does not change
those coordinates, artefacts, or licences.

## 5. Google terms reviewed

- Terms: Android Software Development Kit License Agreement
- URL: `https://developer.android.com/studio/terms`
- Version relied upon: effective 2026-04-28
- Retrieved: 2026-07-20
- Retrieved HTML SHA-256:
  `8bd88dc1144a7d12818687d680d6a9f9e8a2f1ee62c43a8e21f5c6a75f6977cd`

## 6. Technical architecture review

The reviewed implementation:

- isolates Firebase Cloud Messaging usage behind a dedicated notification adapter;
- does not modify Google-provided AAR binaries;
- retains required third-party notices;
- uses FCM for opaque secure-message wakeups, incoming-call notifications, token
  registration, and user alerts;
- communicates with Google Play services through supported Android APIs;
- keeps attachment plaintext, private identities, and attachment key material outside
  the server boundary;
- treats remote-enrolled/local-missing state as a fenced messaging-enrollment recovery,
  durably records the exact pending target before transmission, atomically records the
  resulting server epoch after proof, preserves a valid authenticated device session,
  and forbids same-epoch key publication; and
- revalidates the exact enrollment epoch, registration, identity digest, and bundle
  version before treating a replayed self-lifecycle event as stale.

The application retains its provider-neutral notification abstraction.

## 7. Compatibility determination

The review considered the unchanged Google Android client terms and runtime artefacts,
the unchanged AGPL-3.0-only libsignal dependencies, the corresponding-source
obligations, and the code-14 recovery behavior. The runtime dependency, licence, and
distribution boundaries remain those reviewed for code 13. Distribution is approved
under the Company's internal release policy and the conditions below.

## 8. Distribution conditions

1. The exact reviewed dependency versions and hashes must remain unchanged.
2. Any Google SDK, Firebase, libsignal version, or licence change requires re-review.
3. Required third-party notices and exact AGPL corresponding source must be published
   before the matching binary.
4. The final minified APK/AAB runtime inventory must match the reviewed inventory.
5. Attachment sends must remain gated to compatible code-13-or-later roster devices.
6. Ciphertext ownership, digest, media type, size, quota, expiry, and deletion controls
   must pass the release test suite.
7. Remote-enrolled/local-missing recovery must durably bind the exact captured target to
   the authenticated session before transmission, atomically retain the proved resulting
   epoch through fenced local erasure and re-enrollment, preserve a valid session on
   success or transient failure, and never publish new keys until the backend proves that
   exact enrollment was removed and its epoch advanced. Invalid-session, stale-target,
   corrupt-proof, or replacement-enrollment states require fresh sign-in.
8. Historical self-invalidation events may be ignored only after exact current server
   epoch, registration, identity-key digest, and bundle-version revalidation; they must
   never authorize mutation of a replacement enrollment, and unverifiable states remain
   fail-closed.
9. Retryable capability handling must not bypass the exact reviewed v2 post-quantum
   protocol gate.
10. Direct sideload publication requires exact-APK signing and physical-device
    installation/launch evidence distinct from Play-delivered evidence.
11. Any material change identified by the internal release approval policy requires a
    fresh candidate-specific approval record.

## 9. Re-review triggers

This approval expires or requires reassessment upon:

- dependency version or licence changes;
- changes to Google licensing terms affecting reviewed components;
- migration away from the reviewed FCM architecture;
- changes to libsignal licensing or the Signal session boundary;
- changes to the distribution model;
- introduction of additional proprietary SDKs; or
- a material architecture change affecting licensing or distribution.

## 10. Final verification required before release

- [ ] Final minified APK/AAB dependency inventory verified
- [ ] Reviewed artefact hashes matched
- [ ] Third-party notices and corresponding source verified
- [ ] Release signing verified
- [ ] Android and backend CI release validation completed
- [ ] Account-switch, exact-reset, response-loss/process-death, concurrent token refresh,
      transient-reset-failure, stale-target/fresh-sign-in, stale-event,
      replacement-enrollment, and exact-protocol recovery tests completed
- [ ] Exact direct APK physical-device installation/launch and two-account messaging
      evidence retained before `app.apk` publication

These release checks are performed by the release pipeline and do not mutate this
decision record.

## 11. Approval

- Engineering reviewer: Kit Pos Uganda Limited Software Engineering
- Organization: Kit Pos Uganda Limited
- Qualification: Operational release authority under the 2026-07-22 internal release approval policy
- Jurisdiction: Uganda
- Approval reference identifier: `KIT-PAY-AGPL-GOOGLE-FCM-CLEARANCE-2026-0722-004`
- Review date: 2026-07-22
- Final decision: **CLEARED**
- Management authority record: `docs/internal-release-approval-policy-2026-07-22.md`
- Management authority SHA-256: `40932f05f91c6c9d916ab3a10171d3ebf23a01a259d209563680613fca6e450a`
- Standing-policy approver: Namisi Arnold Paul
- Standing-policy approver title: Chief Executive Officer
- Standing-policy organization: Kit Pos Uganda Limited
- Standing-policy signature: `namisiaroldpaul`
- Management policy effective date: 2026-07-22
- Engineering determination date: 2026-07-22

The reviewed Kit Pay Android code-14 application may proceed with distribution under
the conditions stated in this document and the referenced management policy.
