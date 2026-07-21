# Kit Pay Android distribution clearance decision record

- Document type: Third-Party License Compatibility Review
- Decision classification: Internal Distribution Clearance Approval
- Approval reference: `KIT-PAY-AGPL-GOOGLE-FCM-CLEARANCE-2026-0721-001`
- Application: `com.kit.wallet`
- Product: Kit Pay
- Candidate version: `0.2.0` (`versionCode` 11)
- Review date: 2026-07-21
- Decision status: **CLEARED**

## 1. Decision summary

Following review of the Kit Pay Android application dependency inventory, licensing
obligations, and distribution architecture, approval is granted for distribution of
the application under the reviewed conditions.

The application may be distributed with the reviewed Google Android client libraries
and Signal libsignal dependencies, subject to continued compliance with the conditions
documented in this decision record.

This approval applies only to the exact dependency versions, hashes, application
boundary, and licensing assumptions reviewed in this document.

## 2. Reviewed Google components

| Maven coordinate | AAR SHA-256 | POM SHA-256 |
| --- | --- | --- |
| `com.google.android.gms:play-services-base:18.1.0` | `4eca56ceecd4325a376cd843af56377e2376ce284d0c6f05a5d0a82f4c1bf8cd` | `30df78ba3ead133c2b36784b425a9eeee7f02531907e8aaee4e8922354f732a7` |
| `com.google.android.gms:play-services-basement:18.3.0` | `6c11ae3eb2dd7f17373f919c4c557a70e4cf891bc0c9b66926a0a6445d654352` | `9cef5dc9a6950ff09a85ff522b476f855eb7ef2373aa4c17339cb114ac5397e2` |
| `com.google.android.gms:play-services-cloud-messaging:17.2.0` | `27255e7fe9706483816b158db25cf319f6a26a0566feff41597ce8807a350e37` | `65f5833e621368dfb0eb203dcafcf070c5cece5a60ca18842ce7081d440e0419` |
| `com.google.android.gms:play-services-stats:17.0.2` | `dd4314a53f49a378ec146103d36232b96c75454d29526336ccbdf132941764d3` | `68bb2bc131c0939858e3166f777c63903b990c7fcae3c1dea70a312937f6a73f` |
| `com.google.android.gms:play-services-tasks:18.1.0` | `d60575eae39350e6234858bc9d7d775375707ae82a684e6caf7f3e41a12e25a2` | `cf29ed846108d7a8f2c17d8ef5b63399735757c5a09bb663d7e784e02ad84bcd` |
| `com.google.firebase:firebase-iid-interop:17.1.0` | `0b7c3721c84b62e70415307239ed4a7f998989084bf2833f90b9f5bea3095a05` | `53f269f5e127ac21eba2d80a985abc8d2c8f24cdca9be0284448ee9ea343e34b` |

## 3. Additional reviewed dependency

The application links `org.signal:libsignal-android:0.97.4` and
`org.signal:libsignal-client:0.97.4` under `AGPL-3.0-only`.

## 4. Google terms reviewed

- Terms: Android Software Development Kit License Agreement
- URL: `https://developer.android.com/studio/terms`
- Version relied upon: effective 2026-04-28
- Retrieved: 2026-07-20
- Retrieved HTML SHA-256:
  `8bd88dc1144a7d12818687d680d6a9f9e8a2f1ee62c43a8e21f5c6a75f6977cd`

## 5. Technical architecture review

The reviewed implementation:

- isolates Firebase Cloud Messaging usage behind a dedicated notification adapter;
- does not modify Google-provided AAR binaries;
- retains required third-party notices;
- uses FCM for opaque secure-message wakeups, incoming-call notifications, token
  registration, and user alerts; and
- communicates with Google Play services through supported Android APIs.

The application maintains a provider-neutral notification abstraction to allow future
transport replacement without application-wide changes.

## 6. AGPL compatibility determination

The review considered AGPL-3.0 sections 5, 7, 10 and 12; combined-work,
separate-work and System Library considerations; third-party licensing restrictions;
and downstream recipient obligations.

Based on the reviewed architecture and dependency usage, the distribution is approved.
The Google client libraries are treated as independent third-party components
distributed under their applicable Google terms, while Kit Pay source obligations
remain governed by the applicable AGPL requirements. No additional Signal permission
is required for the reviewed libsignal usage.

## 7. Distribution conditions

1. The exact reviewed dependency versions must remain unchanged.
2. Any Google SDK or Firebase dependency change requires re-review.
3. Any libsignal version or licence change requires re-review.
4. Required third-party notices must remain included.
5. The final release AAB dependency inventory must match the reviewed inventory.
6. Any application-architecture change affecting third-party linking boundaries
   requires review.

## 8. Re-review triggers

This approval expires or requires reassessment upon:

- dependency version changes;
- changes to Google licensing terms affecting reviewed components;
- migration away from the reviewed FCM architecture;
- changes to libsignal licensing;
- changes to the distribution model; or
- introduction of additional proprietary SDKs.

## 9. Final dependency verification required before release

- [ ] Final minified AAB dependency inventory verified
- [ ] Hashes matched against reviewed artifacts
- [ ] Third-party notices verified
- [ ] Release signing verified
- [ ] CI release validation completed

These release checks are performed by the release pipeline and do not mutate this
decision record.

## 10. Approval

- Reviewer name: Namisi Arnold Paul
- Organization: Kit Pos Uganda Limited
- Qualification: Chief Executive Officer (CEO) and Product Owner
- Jurisdiction: Uganda
- Approval reference identifier: `KIT-PAY-AGPL-GOOGLE-FCM-CLEARANCE-2026-0721-001`
- Review date: 2026-07-21
- Final decision: **CLEARED**
- Reviewer signature: Namisi Arnold Paul
- Company: Kit Pos Uganda Limited
- Signature date: 2026-07-21

The reviewed Kit Pay Android application may proceed with distribution under the
conditions stated in this document.
