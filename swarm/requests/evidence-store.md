# Solo Task Brief — evidence-store (Senior Evidence Storage Engineer)

Repo root: `/home/MASTER-H/Documents/Bachelor Thesis/human-blackbox`

## Read first
1. `docs/CONTRACTS.md` — `EvidenceStore.save/load`, Safety Non-Negotiables
   (raw media stays on-device unless the user opts in).
2. `config/default.yaml` — `safety`, `buffer`.
3. `modules/types.py` — import, never edit.

## Owned files (only these)
- `modules/evidence_store.py` (< 200 lines)
- `tests/test_evidence_store.py`
- `swarm/done/evidence-store.md`

## Deliverable
`class EvidenceStore` with the exact contract:
`__init__(self, storage_path: str, encryption_key: str)`,
`save(self, evidence: dict, session_id: str) -> bool`,
`load(self, session_id: str) -> dict`.

Behaviour:
- One file per session: `<storage_path>/<session_id>.evidence`. Create the
  directory on first save.
- Reject unsafe `session_id` values (`..`, `/`, `\`, empty, > 128 chars) with
  `ValueError` — no path traversal.
- Encrypt at rest: `json.dumps(evidence, sort_keys=True, default=str)` ->
  UTF-8 -> XOR with a `hashlib.sha256(key + nonce)` keystream -> file layout
  `MAGIC | nonce | HMAC-SHA256 | ciphertext`. Verify HMAC on load; tamper ->
  `ValueError`. `# ASSUMPTION: stand-in crypto` (production: platform
  AES-256-GCM).
- `load` of a missing session -> `FileNotFoundError`; malformed magic ->
  `ValueError`. Never log or return raw plaintext outside the returned dict.
- `save` returns True on success; atomic write via temp file + `os.replace`.
- Thread-safe enough for one writer: guard saves with `threading.Lock`.
- Optional `list_sessions(self) -> list[str]` helper (safe extension).

## Tests (must show real output)
Cover: save/load roundtrip of nested dict with bytes-free values; file bytes do
not contain a plaintext marker; tamper -> ValueError; missing -> FileNotFoundError;
`../escape` rejected; empty id rejected; two sessions isolated; save creates
directory; `list_sessions` sorted.
Run `python -m pytest tests/test_evidence_store.py -v` and paste the actual
output into the done file.

## Handoff
Write `swarm/done/evidence-store.md` (summary <= 200 words, implementation,
test output, `# ASSUMPTION` lines, files touched). Do not touch other
territories, `config/`, `docs/`, or `README.md`.
