# evidence-store — Handoff

## Summary (<= 200 words)

Implemented `EvidenceStore` as a pure-stdlib, per-session encrypted evidence
store. `save(evidence, session_id)` serialises the evidence dict as sorted-key
JSON, encrypts it with a SHA-256 counter keystream derived from the key and a
fresh 16-byte nonce, authenticates it with HMAC-SHA256, and atomically writes
`<storage_path>/<session_id>.evidence` via a temp file + `os.replace` under a
`threading.Lock`. `load(session_id)` verifies the HMAC before decrypting and
parsing, raising `ValueError` on tamper or malformed files and
`FileNotFoundError` when the session is absent. Session ids are allow-listed
(`[A-Za-z0-9][A-Za-z0-9._-]*`), capped at 128 chars, and reject `..`, `/`,
`\`, empty and control characters, preventing path traversal. `list_sessions()`
returns sorted ids and an empty list when storage does not exist yet. Raw media
stays on-device; no upload path exists. The module is 165 lines.

## Implementation notes

- File layout: `MAGIC b"HBX1" | nonce(16) | HMAC-SHA256(32) | ciphertext`.
- HMAC covers `MAGIC + nonce + ciphertext` and is checked with
  `hmac.compare_digest` before decryption.
- Temp files use `secrets.token_hex(8)`, mode `0o600`, fsync before replace.
- `json.dumps(..., default=str)` stringifies non-JSON values.
- `save` rejects non-dict evidence with `TypeError`; bad ids raise `ValueError`.
- No plaintext is logged or exposed; only `load` returns the dict.

## Tests run

Command:

```
python -m pytest tests/test_evidence_store.py -v
```

Actual output:

```
============================= test session starts ==============================
platform linux -- Python 3.14.10, pytest-9.2.0, pluggy-1.7.0 -- /usr/bin/python
cachedir: .pytest_cache
rootdir: /home/MASTER-H/.local/share/opencode/worktree/e5232d/quick-island
plugins: dash-4.5.0
collecting ... collected 21 items

tests/test_evidence_store.py::test_save_load_roundtrip_nested PASSED     [  4%]
tests/test_evidence_store.py::test_file_does_not_contain_plaintext PASSED [  9%]
tests/test_evidence_store.py::test_tampered_ciphertext_raises_value_error PASSED [ 14%]
tests/test_evidence_store.py::test_tampered_hmac_tag_raises_value_error PASSED [ 19%]
tests/test_evidence_store.py::test_malformed_magic_raises_value_error PASSED [ 23%]
tests/test_evidence_store.py::test_wrong_key_cannot_load PASSED          [ 28%]
tests/test_evidence_store.py::test_missing_session_raises_file_not_found PASSED [ 33%]
tests/test_evidence_store.py::test_save_creates_directory PASSED         [ 38%]
tests/test_evidence_store.py::test_unsafe_session_ids_rejected[../escape] PASSED [ 42%]
tests/test_evidence_store.py::test_unsafe_session_ids_rejected[a/b] PASSED [ 47%]
tests/test_evidence_store.py::test_unsafe_session_ids_rejected[a\\b] PASSED [ 52%]
tests/test_evidence_store.py::test_unsafe_session_ids_rejected[] PASSED  [ 57%]
tests/test_evidence_store.py::test_unsafe_session_ids_rejected[..] PASSED [ 61%]
tests/test_evidence_store.py::test_unsafe_session_ids_rejected[.] PASSED [ 66%]
tests/test_evidence_store.py::test_unsafe_session_ids_rejected[session_id6] PASSED [ 71%]
tests/test_evidence_store.py::test_unsafe_session_ids_rejected[bad id] PASSED [ 76%]
tests/test_evidence_store.py::test_unsafe_session_ids_rejected[a\x00b] PASSED [ 80%]
tests/test_evidence_store.py::test_two_sessions_are_isolated PASSED      [ 85%]
tests/test_evidence_store.py::test_list_sessions_sorted PASSED           [ 90%]
tests/test_evidence_store.py::test_save_uses_atomic_replace_and_leaves_no_temp_files PASSED [ 95%]
tests/test_evidence_store.py::test_non_serialisable_values_are_stringified PASSED [100%]

=============================== warnings summary ===============================
../../../../../lib/python3.14/site-packages/dash/testing/plugin.py:104
  /home/MASTER-H/.local/lib/python3.14/site-packages/dash/testing/plugin.py:104: PytestRemovedIn10Warning: The hookimpl pytest_addhooks uses old-style configuration options (marks or attributes).
  Please use the pytest.hookimpl(tryfirst=True) decorator instead
   to configure the hooks.
   See https://docs.pytest.org/en/latest/deprecations.html#configuring-hook-specs-impls-using-markers
    @pytest.mark.tryfirst

-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
======================== 21 passed, 1 warning in 0.05s =========================
```

## Assumptions

- # ASSUMPTION: stand-in crypto — SHA-256 XOR keystream + HMAC-SHA256 is a
  pure-Python stand-in; production uses platform AES-256-GCM.
- # ASSUMPTION: evidence values are JSON-serialisable; other values are
  stringified with `json.dumps(..., default=str)`.
- # ASSUMPTION: session ids are opaque caller-supplied strings matching
  `[A-Za-z0-9][A-Za-z0-9._-]*`; UUIDs and `sess-...` ids qualify.
- # ASSUMPTION: raw media is never uploaded; this store only writes local files.
- # ASSUMPTION: one writer per store instance is guarded by a lock; concurrent
  multi-process writers are out of scope.

## Files touched

- `modules/evidence_store.py` (new, 165 lines)
- `tests/test_evidence_store.py` (new, 21 tests)
- `swarm/done/evidence-store.md` (new)
