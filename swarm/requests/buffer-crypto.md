# Solo Task Brief — buffer-crypto (Senior Storage & Crypto Engineer)

Repo root: `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox`

## Read first
1. `docs/CONTRACTS.md` — `RollingBuffer` (append, get_recent,
   load_from_storage, save_to_storage), Safety Non-Negotiables.
2. `config/default.yaml` — `buffer.size_sec`, `buffer.encryption`.
3. `modules/types.py` — import, never edit.

## Owned files (only these)
- `modules/rolling_buffer.py` (< 200 lines)
- `tests/test_rolling_buffer.py`
- `swarm/done/buffer-crypto.md`

## Deliverable
`class RollingBuffer` with the exact contract constructor
`__init__(self, buffer_size_sec: int, encryption_key: str)` plus an optional
keyword-only `storage_path: str = "buffer.enc"` (documented as a compatible
extension, `# ASSUMPTION`). Methods: `append`, `get_recent`, `load_from_storage`,
`save_to_storage`.

Behaviour:
- In-memory entries `(timestamp, chunk, score)`; on append expire anything older
  than `newest_timestamp - buffer_size_sec`. Thread-safe via `threading.Lock`.
- `get_recent(seconds)` returns `{"audio": [...], "motion": [...], "vision": [...]}`
  with lists of `(chunk, score)` tuples ordered oldest -> newest, filtered to
  the last `seconds` relative to the newest entry. `seconds` > window is capped.
- At-rest encryption (`# ASSUMPTION: stand-in crypto`): derive a 32-byte keystream
  with `hashlib.sha256(key + nonce)` and XOR the serialised payload; prepend
  nonce + HMAC-SHA256 tag. Verify the tag on load and raise `ValueError` on
  tamper. State in the docstring that production uses platform AES-256-GCM.
- Serialise with `pickle` only for plain contract objects; tests will check that
  a known plaintext marker does not appear in the file bytes.
- `save_to_storage` / `load_from_storage` are idempotent; loading replaces
  in-memory entries and re-expires. Missing file -> `FileNotFoundError`.
- Raw media never leaves the object except through these methods.

## Tests (must show real output)
Cover: append + get_recent bucketing; 60 s expiry; `get_recent` sub-window;
empty buffer; save/load roundtrip in `tmp_path`; encrypted bytes do not contain
the marker; tampered file raises ValueError; missing file raises
FileNotFoundError; append rejects a plain dict (`TypeError`).
Run `python -m pytest tests/test_rolling_buffer.py -v` and paste the actual
output into the done file.

## Handoff
Write `swarm/done/buffer-crypto.md` (summary <= 200 words, implementation,
test output, `# ASSUMPTION` lines, files touched). Do not touch other
territories, `config/`, `docs/`, or `README.md`.
