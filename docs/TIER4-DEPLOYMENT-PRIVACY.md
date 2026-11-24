# Tier 4 Design — Deployment Hardening, Privacy-by-Sensor, and Edge Budgets

Status: design proposal (not yet implemented)
Target modules: `modules/rolling_buffer.py` (real AEAD),
`modules/key_store.py` (new), `modules/thermal_detector.py` (new, optional),
`modules/mmwave_detector.py` (new, optional), `modules/budget.py` (new),
`config/default.yaml` (extend), `tests/test_crypto.py`,
`tests/test_budget.py` (new).

Depends on: Tier 1–3. Tier 4 is mostly engineering, not research: it converts
existing stand-ins into defensible deployment choices and adds privacy-by-sensor
alternatives to RGB vision.

## 1. Problem

The current tree has three deployment-level weaknesses that no amount of
modelling fixes:

1. **Stand-in crypto.** `RollingBuffer` uses XOR-masked keystream + HMAC-SHA256.
   It is honest and tested, but it is not an AEAD and not what the README
   promises. Production needs AES-256-GCM with keys held outside the process.
2. **RGB camera is a privacy landmine.** Encrypting the buffer does not change
   the fact that a bedroom camera captures identifiable imagery. The strongest
   privacy fix is to not capture it.
3. **No resource budget.** Nothing in the tree states or enforces the latency,
   RAM, flash, thermal or battery envelope the device must meet, so "on-device"
   is an assertion rather than a measurement.

## 2. Goals

- Replace stand-in crypto with real AEAD, without changing the on-disk contract.
- Move key material out of the process (secure element / TEE / OS keychain).
- Offer thermal and mmWave detector paths that never produce identifiable
  imagery, gated by config.
- Make resource budgets first-class, measured and enforced in tests/CI.
- Keep the shipped default configuration byte-identical to today.

## 3. Component 1 — Real AEAD in `rolling_buffer.py`

### 3.1 Design

Keep the existing file envelope (`magic | version | nonce | ciphertext | tag`)
so `save_to_storage` / `load_from_storage` contracts do not change. Swap the
inner primitive behind a provider interface:

```python
class AeadProvider(Protocol):
    def seal(self, key: bytes, nonce: bytes, plaintext: bytes,
             aad: bytes = b"") -> bytes: ...
    def open(self, key: bytes, nonce: bytes, ciphertext: bytes,
             aad: bytes = b"") -> bytes: ...
```

Two implementations:

- `AesGcmProvider` — uses the `cryptography` package
  (`AESGCM(key).encrypt(...)`). This is the production path and the default
  when the import succeeds.
- `XorHmacProvider` — the current code, kept as an explicit *test-only /
  sandbox* fallback. It must log/flag `mode: "standin"` and must never be
  selected silently in a release build.

Selection is config-driven and fails closed:

```yaml
buffer:
  encryption: aes-256-gcm     # aes-256-gcm | standin-xor-hmac
  require_aead: true          # refuse to start if the provider is standin
```

If `require_aead: true` and only the standin provider is available, the buffer
constructor raises `RuntimeError` rather than degrading quietly.

### 3.2 Key handling (`modules/key_store.py`)

```python
class KeyStore:
    """Abstract key source. Never returns key bytes to callers that log."""

    def get_data_key(self) -> bytes: ...
    def rotate(self) -> None: ...
```

Implementations, in order of preference:

1. `TeeKeyStore` — key lives in secure element / TEE; only handles cross the
   boundary.
2. `KeychainKeyStore` — OS keychain (iOS Keychain, Android Keystore, libsecret).
3. `FileKeyStore` — development only, file mode 0600, refuses to run when
   `buffer.require_aead` is true.

Nonce discipline: 96-bit random nonce per record, uniqueness checked against a
bounded recent-nonce set; a collision raises instead of reusing.

AAD binds the record header (version, timestamp, modality) so ciphertext cannot
be transplanted between records.

### 3.3 Tests (`tests/test_crypto.py`)

- `test_aesgcm_roundtrip`
- `test_wrong_key_raises`
- `test_tampered_ciphertext_raises`
- `test_tampered_aad_raises`
- `test_nonce_reuse_raises`
- `test_standin_provider_refused_when_require_aead`
- `test_envelope_layout_unchanged` (byte offsets of magic/version/nonce/tag)
- `test_load_of_legacy_standin_file_migrates_or_rejects` (explicit policy)
- `test_key_never_appears_in_repr_or_exception`

## 4. Component 2 — Privacy-by-sensor alternatives

### 4.1 Thermal detector (`modules/thermal_detector.py`)

Same `Detector` contract as `vision_detector.py` (returns `ModalityScore`), but
input is a low-resolution radiometric frame (e.g. 32×24 or 80×62). No facial
detail survives at that resolution, so the privacy argument holds by
construction rather than by policy.

- Pipeline: per-frame background subtraction → blob area/aspect/centroid →
  fall shape (sudden vertical drop then horizontal elongation) → score.
- The heuristic path is fully deterministic and testable without a camera.
- Optional TinyCNN head, reusing Tier 2's `GatedCNN1D` with 1 channel.

### 4.2 mmWave detector (`modules/mmwave_detector.py`)

Radar point-cloud input; no optical imagery at all, works in darkness and
through occlusion.

- Pipeline: point clustering → centroid height/velocity → impact signature.
- Deliberately no learned model in v1; the value is the privacy story and the
  day/night robustness, not accuracy.

### 4.3 Config

```yaml
detectors:
  vision:
    enabled: true
    sensor: rgb              # rgb | thermal | mmwave
  thermal:
    enabled: false
    min_blob_area: 0.02
    drop_ratio: 0.55
  mmwave:
    enabled: false
    min_points: 8
    impact_velocity: 2.5
```

Exactly one visual modality should be enabled at a time; the pipeline raises on
conflict rather than fusing RGB and thermal as if independent.

### 4.4 Tests

- `test_thermal_detector_contract_matches_vision`
- `test_thermal_drop_then_elongation_scores_high`
- `test_thermal_static_scene_scores_low`
- `test_thermal_rejects_malformed_frame`
- `test_mmwave_impact_velocity_triggers`
- `test_conflicting_visual_modalities_rejected`
- `test_thermal_path_never_calls_rgb_decoder` (assert no import/attribute use)

## 5. Component 3 — Resource budgets (`modules/budget.py`)

### 5.1 What to measure

- **End-to-end latency per window:** ≤ 100 ms phone, ≤ 50 ms Jetson.
- **Peak RSS:** ≤ 250 MB phone, ≤ 600 MB Jetson.
- **Model artifacts:** ≤ 8 MB total.
- **Flash for buffer + models:** ≤ 64 MB.
- **Sustained CPU:** ≤ 1 core, ≤ 60% duty.
- **Thermal:** no sustained throttling over 10 min.

Targets live in config, not code, so hardware revisions are a config change:

```yaml
budget:
  enforce: true
  latency_ms: 100
  peak_rss_mb: 250
  artifacts_mb: 8
  flash_mb: 64
  cpu_cores: 1
```

### 5.2 API

```python
class BudgetMonitor:
    def __init__(self, limits: dict) -> None: ...
    def time_block(self, name: str): ...        # context manager
    def record_rss(self) -> None: ...
    def check(self) -> list[str]: ...           # human-readable violations
    def report(self) -> dict: ...
```

CI runs the e2e pipeline on recorded fixtures and fails when `check()` is
non-empty. This turns "runs on-device" into a measured claim.

### 5.3 Compression decision record

Pick per-modality strategies from the edge survey taxonomy rather than
defaulting to "quantize everything":

- vision: INT8 PTQ (largest win, tolerant),
- audio: INT8 PTQ with per-channel scales, keep log-mel front end in fp32,
- motion: keep fp32 or INT8 QAT; the model is tiny, quantization saves flash
  but can hurt brief impact detection,
- temporal heads: prune 30–50% then INT8; validate against the Tier 1
  false-alarm budget, not raw accuracy.

Record the chosen strategy and measured delta per modality in
`docs/ASSUMPTIONS.md`.

### 5.4 Tests (`tests/test_budget.py`)

- `test_latency_block_recorded`
- `test_violation_raised_for_latency_overrun` (synthetic sleep-free injection)
- `test_artifact_size_within_limit`
- `test_report_is_json_serializable`
- `test_enforce_false_never_raises`

## 6. Rollout

1. `AesGcmProvider` + `KeyStore` behind `buffer.encryption`; standin path stays
   for the sandbox and is explicitly labelled.
2. Flip `require_aead: true` in release configs only.
3. Add `BudgetMonitor` to the e2e test; publish the measured numbers.
4. Ship thermal detector as the recommended visual modality; keep RGB for
   users who explicitly opt in.
5. mmWave stays experimental until a dataset is available.

## 7. Risks and mitigations

- **`cryptography` unavailable on target.** Provider abstraction; fail closed
  with a clear error.
- **Key stored in app sandbox is extractable.** Document the threat model
  honestly; the TEE path is the recommendation.
- **Thermal resolution too low to detect falls.** Validate on a thermal
  benchmark before shipping; fall back to mmWave or no vision.
- **Budget tests flaky on shared CI.** Measure on fixed fixtures, allow a
  tolerance band, gate on the median of N runs.
- **Quantization degrades impact detection.** Validate with Tier 1
  calibration metrics; keep motion in fp32 if needed.

## 8. References

- *Thermal Imaging Real-time Fall Detection.* arXiv:2509.16479 (ROC-AUC 99.7%,
  privacy-preserving benchmark).
- *EM-Fall: mmWave fall detection.* arXiv:2606.11109 (day/night,
  occlusion-robust).
- *From White Canes to Edge AI: A Survey of Assistive Navigation Technologies.*
  arXiv:2611.00671 (edge compression taxonomy, 108 studies).
- NIST SP 800-38D (GCM), RFC 5116 (AEAD interface).
