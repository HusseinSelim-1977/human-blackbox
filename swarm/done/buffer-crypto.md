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

## 
