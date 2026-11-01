# Recovery journal format, version 1

The journal is the first defence in `docs/robustness.md` §1: an
append-only log of edits, one file per document with unsaved changes.
This file is the byte-level contract. The code in
`src/main/kotlin/.../history/journal/` is the reference implementation.

All integers are **little-endian**, as in LevelDB. Strings are
**WTF-8**: UTF-8 for any valid text, plus lone surrogates encoded as
three bytes, so every Java/Kotlin `String` round-trips exactly.

## File header

| Field | Type | Notes |
|---|---|---|
| magic | 4 bytes | `SSMJ` |
| formatVersion | u16 | `1`. Read before anything else; a newer version is left untouched. |
| bodyLength | u32 | Bytes of the body that follows (at most 64 KiB). |
| createdAt | i64 | Epoch millis when this file was written. |
| baseSize | i64 | Size in bytes of the file the edits start from. |
| baseLastModified | i64 | That file's `lastModified`. Informational: not part of the base match. |
| baseSha256 | 32 bytes | SHA-256 of that file's bytes. |
| keyLength | u32 | Bytes of the document key. |
| documentKey | WTF-8 | Opaque to the journal; usually the content URI. |
| headerCrc | u32 | Masked CRC32C of everything from `formatVersion` to the end of the body. |

A journal is only replayed onto a base whose size and SHA-256 match.

## Records

Framing follows LevelDB's `doc/log_format.md`:

```
[length u32][crc u32][type u8][seq u64][payload: length bytes]
```

- `length` is the payload length, at most 1 GiB.
- `crc` is the **masked** CRC32C (`java.util.zip.CRC32C`) of type, seq
  and payload. Masking, from LevelDB, is `rotr(crc, 15) + 0xa282ead8`;
  it keeps a CRC strong over data that itself contains CRCs, as a
  snapshot can.
- `seq` goes up by exactly one per record within a file. Compaction
  carries the sequence on, so a writer's numbers never go backwards.

| Type | Name | Payload |
|---|---|---|
| 1 | Op | `offset u32` (UTF-16 code units), `deletedLength u32`, `deleted`, `insertedLength u32`, `inserted` |
| 2 | Snapshot | the whole text; replaces everything before it |
| 3 | Marker | `kind u8` (1 = Saved, 2 = Checkpoint), then a label to the end of the payload |

An Op is applied only if `deleted` matches the text at `offset`. A
mismatch means the wrong base or a lost record, and is reported as
corruption, never applied.

## Reading rules

- **Torn tail, legal.** If the file ends inside a record, or the rest
  of the file is zeros, replay stops there and succeeds with the text up
  to the last whole record. This is what a crash mid-append leaves.
- **Damage, reported.** A whole record with a bad CRC, an out-of-range
  length, a sequence gap, an unparseable payload or an Op that does not
  fit stops replay with `Corrupt`, carrying the text up to the last good
  record. As in LevelDB, a damaged length that points past the end of
  the file cannot be told apart from a torn tail.
- **Unknown version, read-only.** A newer `formatVersion` is reported
  as `Unsupported` and the file is never changed, moved or deleted.
- **Unparseable files are quarantined, never deleted.** They move to
  `quarantine/` in the journal directory.

## Writing rules

- Appends are buffered and synced in groups (`FlushPolicy`; by default
  after 1 s idle, at least every 5 s while typing, at once for 256 KiB).
- A failed write is truncated back to the last whole batch before
  anything else is appended, so a fragment never sits mid-file.
- Creating, resuming and compacting a journal all write `<name>.new`,
  `fsync` it, rename it over `<name>` and `fsync` the directory: the
  `android.util.AtomicFile` protocol. A `.new` beside its journal is an
  interrupted compaction and is deleted; a `.new` alone is quarantined.

## Changing the format

Bump `formatVersion`, keep a reader for every older version, and add a
migration test with a checked-in file of the old version. Never reuse a
record type or marker kind number.
