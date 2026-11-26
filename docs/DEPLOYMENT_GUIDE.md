# Deployment Guide — Human BlackBox v2

**Status: configuration template. Nothing here has been deployed, built or
verified.** This checkout contains no Gradle wrapper, no Android SDK path, no
`google-services.json`, no Firebase project, no trained models, no Dockerfiles,
no Kubernetes manifests and no credentials. Do not treat any placeholder below
as a working configuration or as evidence of a running system.

> **Assistive tool only — not authoritative emergency detection.** No automatic
> emergency calls are implemented. Any future calling/PSAP integration requires
> explicit configuration, user confirmation and legal review before use.

## 1. Required user-supplied inputs

| Input | Where it belongs | Current state |
|---|---|---|
| Android SDK location (`ANDROID_HOME`/`local.properties`) | build environment | absent |
| Gradle wrapper (or matching Gradle executable) | repository root / environment | absent |
| `app/google-services.json` from the operator's Firebase project | `:app` (plugin applies only when file exists) | absent |
| Firebase project ID, API key, app ID, messaging sender ID | same JSON / environment secret store | absent |
| FCM server credentials for the operator's cloud component | cloud-only secret store | absent |
| Firestore rules, collection/schema (`alerts`) and retention policy | Firebase console / IaC | absent |
| Firebase Auth provider config and authorized client IDs | Firebase console | absent; `:cloud` has no Auth dependency yet |
| Firebase Storage bucket rules for `evidence/<eventId>.enc` | Firebase console | absent |
| Dataset manifests with `license`, `provenance`, `train`/`val`/`test` splits | operator-supplied paths | absent |
| Trained/quantized TFLite artifacts and class map | model bundle path in app assets | absent |
| Target device(s), OS version and measurement hardware | evaluation environment | absent |
| Consent/privacy notice text and legal review | app UI and release process | unresolved |

Secrets must be injected by the operator's secret manager at build/runtime. Never
commit API keys, service-account JSON, keystore files or signed APKs.

## 2. Android app configuration (placeholders only)

`:app` targets SDK 34, minSdk 29, application ID `com.humanblackbox.humanblackbox.v2`.
Firebase is applied conditionally when `app/google-services.json` exists. The
root build declares the Google Services plugin; the Gradle plugin set must be
verified against a real Gradle/SDK installation before building (for example,
`com.android.kotlin.parcel` in `settings.gradle.kts` needs verification).

Illustrative placeholders, not values from this repository:

```text
ANDROID_HOME=<operator-supplied SDK path>
FIREBASE_PROJECT_ID=<operator-supplied project id>
FCM_TOPIC=alerts
FIRESTORE_COLLECTION=alerts
STORAGE_EVIDENCE_PREFIX=evidence/
```

`FirestoreAlertGateway` currently sends only event type, confidence, timestamp,
coarse location string, device status and recommended action to `alerts`; it
subscribes to the `alerts` FCM topic. There is no call path from the app stub to
this gateway yet. `ConsentedEvidenceUploader` refuses to upload unless its
`hasConsent` predicate is true; the app must source that predicate from
`PrivacySettings.evidenceUploadConsent()` (default `false`).

## 3. Firebase configuration checklist (no secrets shown)

1. Create an operator-owned Firebase project; register the Android application
   ID above and download `google-services.json` into `app/`.
2. Enable FCM with a server-side sender; define the `alerts` topic and the
   client subscription/authorization rules.
3. Create Firestore with an `alerts` collection. Rules must restrict writes to
   authenticated, authorized devices and minimize retained metadata; exact rules
   are operator/legal decisions and are not supplied here.
4. Enable Firebase Auth with operator-chosen providers and authorized clients.
   **No Auth dependency or sign-in flow exists in `:cloud` yet**, so this is a
   prerequisite for step 3, not a supported feature.
5. Create Firebase Storage with a separate `evidence/` path; require explicit
   per-upload consent and define retention/deletion. Encrypted evidence is the
   only payload that may be uploaded, and only after consent.
6. Verify that no raw audio/video/IMU is present in alert payloads before any
   release.

## 4. Cloud-only components (planned, not present)

No Docker or Kubernetes files exist in this checkout. The intended deployment
boundary is:

```text
[Android device] --FCM/HTTPS(minimal metadata)--> [cloud alert receiver]
                          \--consent-gated encrypted upload--> [object storage]
[Docker image: alert receiver / topic fan-out]   [K8s: Deployment + Service]
[Secrets: FCM server key, Firebase service account] via external secret store
```

Placeholders to be authored by the operator when the cloud scope is implemented:
`Dockerfile` for a minimal alert receiver, a Kubernetes `Deployment`, `Service`,
`ConfigMap` and `Secret` references, network policy restricting egress, and
log-redaction rules. No image is built here and no service endpoint is known.
Firebase itself is a managed service and is not deployed as a container.

## 5. Privacy defaults to preserve

- Per-modality kill switches in `PrivacySettings` currently default to enabled;
  the UI must show and honor them before capture begins.
- Evidence-upload consent defaults to `false` and must remain false on update.
- Rolling evidence window is 60 s; `:data` uses Keystore-backed AES-256-GCM
  encrypted files. The Python baseline uses a documented stand-in and is not
  equivalent; do not claim production AEAD for both paths.
- Local alerts and countdown must work fully offline; cloud delivery is best-effort.
- Visible recording indicator and revocable consent are required before capture.
- Minimal metadata only in default alerts; raw evidence never leaves the device
  without explicit consent.

## 6. Release gates (all currently blocked)

1. Gradle/SDK available and all 11 modules compile and pass unit tests.
2. Model artifacts, class map and manifest licenses supplied and verified.
3. Fusion tensor contract reconciled (see [`MODEL_CARDS.md`](./MODEL_CARDS.md)).
4. Firebase config/Auth/Firestore rules supplied; cloud-only components authored.
5. Consent UI, manual SOS wiring and local alert path implemented and tested.
6. On-device evaluation completed per [`EVALUATION_PLAN.md`](./EVALUATION_PLAN.md).
7. Legal/privacy review of alert wording and any emergency-call integration.
