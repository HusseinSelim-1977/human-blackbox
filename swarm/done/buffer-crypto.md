# buffer-crypto — done

**Role:** Senior Storage & Crypto (territory #5)
**Status:** complete — contract implemented, 15/15 tests green, committed.

## Summary

`modules/rolling_buffer.py` implements `RollingBuffer` per CONTRACTS.md: a
thread-safe rolling window (default config: 60 s) of `(timestamp, chunk,
score)` entries over `AudioChunk` / `ImuSample` / `Frame` plus their
`ModalityScore`. `append` validates types (plain dicts raise `TypeError`),
stores the chunk and expires anything older than `newest - buffer_size_sec`
(boundary inclusive). `get_recent(seconds)` returns `{"audio", "motion",
"vision"}` buckets of `(chunk, score)` tuples, oldest to newest, capped to the
window and filtered relative to the newest entry. `save_to_storage` /
`load_from_storage` roundtrip through an encrypted at-rest file (nonce +
XOR-masked pickled payload + HMAC-SHA256 tag); tampered or truncated files
raise `ValueError`, missing files raise `FileNotFoundError`. Loading replaces
entries and re-expires. Raw media never leaves the object except via these
methods.

## Implementation notes

- In-memory store: list of `(timestamp, chunk, score)`; expiry cutoff uses
  `max(timestamps)`, so out-of-order appends are handled correctly.
- Encryption file layout: `nonce(16) || ciphertext || tag(32)`; tag is
  HMAC-SHA256 over `nonce + ciphertext` so nonce tampering is also caught.
- Keystream: `sha256(key + nonce)` for the first 32 bytes, extended per
  32-byte block with a big-endian block counter for longer payloads.
- `save_to_storage` writes to `<path>.tmp` then `os.replace` — atomic save.
- Module is 196 lines (under the 200-line budget); pure stdlib only.

## # ASSUMPTIONS

- # ASSUMPTION: stand-in crypto (SHA-256 keystream + HMAC-SHA256) instead of
  platform AES-256-GCM (`config: buffer.encryption`); sandbox has no AES
  library. Production swaps the encrypt/decrypt internals, same interface.
- # ASSUMPTION: `storage_path` keyword-only parameter (default
  `"buffer.enc"`) is a compatible extension of the contract `__init__`.
- # ASSUMPTION: expiry boundary is inclusive — an entry exactly
  `buffer_size_sec` old is kept; older-than means strictly `< newest - size`.
- # ASSUMPTION: entries are pickled as plain contract objects; after
  unpickling they are re-validated by type before entering memory.

## Verification (actual run)

Command: `python -m pytest tests/test_rolling_buffer.py -v`

```
============================= test session starts ==============================
platform linux -- Python 3.14.10, pytest-9.2.0, pluggy-1.7.0 -- /usr/bin/python
cachedir: .pytest_cache
rootdir: /home/MASTER-H/.local/share/opencode/worktree/e5232d/neon-harbor
plugins: dash-4.5.0
collecting ... collected 15 items

tests/test_rolling_buffer.py::test_append_and_get_recent_buckets PASSED  [  6%]
tests/test_rolling_buffer.py::test_get_recent_orders_oldest_to_newest PASSED [ 13%]
tests/test_rolling_buffer.py::test_expiry_after_60_seconds PASSED        [ 20%]
tests/test_rolling_buffer.py::test_expiry_boundary_is_inclusive PASSED   [ 26%]
tests/test_rolling_buffer.py::test_get_recent_sub_window PASSED          [ 33%]
tests/test_rolling_buffer.py::test_get_recent_seconds_capped_to_window PASSED [ 40%]
tests/test_rolling_buffer.py::test_empty_buffer PASSED                   [ 46%]
tests/test_rolling_buffer.py::test_append_rejects_plain_dict PASSED      [ 53%]
tests/test_rolling_buffer.py::test_save_load_roundtrip PASSED            [ 60%]
tests/test_rolling_buffer.py::test_load_replaces_and_reexpires PASSED    [ 66%]
tests/test_rolling_buffer.py::test_encrypted_file_hides_plaintext_marker PASSED [ 73%]
tests/test_rolling_buffer.py::test_tampered_file_raises_value_error PASSED [ 80%]
tests/test_rolling_buffer.py::test_truncated_file_raises_value_error PASSED [ 86%]
tests/test_rolling_buffer.py::test_missing_file_raises_file_not_found PASSED [ 93%]
tests/test_rolling_buffer.py::test_wrong_key_raises_value_error PASSED   [100%]

=============================== warnings summary ===============================
../../../../../lib/python3.14/site-packages/dash/testing/plugin.py:104
  /home/MASTER-H/.local/lib/python3.14/site-packages/dash/testing/plugin.py:104: PytestRemovedIn10Warning: The hookimpl pytest_addhooks uses old-style configuration options (marks or attributes).
  Please use the pytest.hookimpl(tryfirst=True) decorator instead
  See https://docs.pytest.org/en/latest/deprecations.html#configuring-hook-specs-impls-using-marks-or-attributes
    @pytest.mark.tryfirst

-- Docs: https://docs.pytest.org/en/stable/capture_warnings.html
======================== 15 passed, 1 warning in 0.02s =========================
```

Full suite sanity: `python -m pytest tests/ -q` → 16 passed (15 buffer + 1
pre-existing config test). The 1 warning comes from the site-installed dash
plugin, unrelated to this work.

## Files touched

- `modules/rolling_buffer.py` (new, 196 lines)
- `tests/test_rolling_buffer.py` (new, 15 tests)
- `swarm/done/buffer-crypto.md` (this file)

Downstream (fusion / recorder seniors): call `get_recent(seconds)` for
windowed fusion input; `save_to_storage()` before shutdown; keys are required
to match for a successful `load_from_storage()`.
