# Version history format, version 1

Version history is the per-file timeline of `docs/robustness.md` §3:
every open, save, autosave and checkpoint of a document, stored as
keyframes plus backward line deltas. This file is the byte-level
contract. The code in `src/main/kotlin/.../history/versions/` is the
reference implementation; `VersionStore` is its public face.

As in the journal (`FORMAT.md`), integers are **little-endian**, strings
are **WTF-8** (UTF-8 plus lone surrogates, so every `String` round-trips
exactly), and CRCs are **masked CRC32C** (`MaskedCrc`).

## Layout

```
<root>/                       app filesDir, never cacheDir
  docs/<40 hex>/              one directory per document: SHA-256 of its URI, first 160 bits
    index.ssv                 the index; replaced atomically to commit every change
    pack-<generation>.ssp     version bodies; append-only between rewrites
  quarantine/<dir>-<nanos>/   a document directory whose index could not be read
  cleanup-log.jsonl           every removal, one JSON object per line
```

## Pack records

```
[length u32][crc u32][flags u8][payload: length bytes]
```

- `crc` covers `flags` and the payload.
- `flags` bit 0: **keyframe** (payload is the whole text) or, clear, a
  **delta**. Bit 1: payload is **deflated** (raw zlib stream). Other bits
  must be zero. A body is deflated only when that makes it smaller.
- A record is found through the index (offset and length); packs are
  never scanned.

### Deltas

A delta rebuilds a version from the next newer version on its branch
(its *base*). Uncompressed:

```
[count u32] then count × ([start u32][end u32][length u32][replacement: length bytes WTF-8])
```

`start`/`end` are UTF-16 offsets into the base, sorted and
non-overlapping, always on line boundaries. Applying replaces each
`base[start, end)` with its replacement. A delta that does not fit its
base exactly (offsets out of order or range, trailing bytes, bad WTF-8)
is rejected, never applied.

Deltas come from a line diff: common prefix and suffix stripped, lines
interned to ints, then linear-space Myers with an edit cap (20,000) and
a work cap (40 M steps). A capped diff stores a keyframe instead.

### Keyframe rule

The newest version of every branch is always a keyframe. When a new
version arrives, the old head becomes a delta from it only if that keeps
Mercurial's rule (`revlogutils/deltas.py`) for it **and for every delta
below it down to the next keyframe**; otherwise it stays a keyframe:

- the delta record is no bigger than the text (UTF-16 length);
- the delta records read to rebuild the version total at most 2× its
  text length;
- fewer than 50 deltas are applied (git's default pack depth).

So at most 49 deltas are ever applied for one read, and a damaged delta
loses only the versions between it and the next keyframe below it.

## Index (`index.ssv`)

| Field | Type | Notes |
|---|---|---|
| magic | 4 bytes | `SSMV` |
| formatVersion | u16 | `1`. A newer version is reported `Unsupported` and never changed, moved or deleted. |
| bodyLength | u32 | |
| body | | below |
| crc | u32 | over `formatVersion` to the end of the body |

Body: `uri` (string), `nextSeq` i64, `lastOpened` i64 (epoch ms),
`packName` (string), `packLength` i64, `branchCount` u32 then per branch
`id` u32 + `label` (string; empty for the main branch, 0), `versionCount`
u32 then per version, sorted by branch then seq:

| Field | Type |
|---|---|
| seq | i64, unique per document, only ever increases |
| branch | u32 |
| wallTime | i64, display only |
| kind | u8: 1 Opened, 2 Saved, 3 Autosaved, 4 Checkpoint, 5 ChangedOutside, 6 BeforeRestore, 7 Appended |
| flags | u8: 1 pinned, 2 keyframe, 4 has parent, 8 has label |
| parent | i64, if flagged |
| label | string, if flagged |
| added, removed | u32 each: line-change summary against the previous version on the branch |
| textLength | u32, UTF-16 units |
| sha256 | 32 bytes, of the text's WTF-8 bytes |
| offset | i64 into the pack |
| storedBytes | u32, the whole record |

A string is `length u32` + WTF-8 bytes.

## Writing rules

- **Record:** append the old head's delta (if it is demoted) and the new
  head's keyframe to the pack and `fsync`; then write the index with
  `AtomicFile` (`.new`, `fsync`, rename, `fsync` the directory). The
  rename is the commit. Pack bytes past `packLength` belong to an append
  whose commit never happened; they are cut off before the next append.
- **Rewrite** (pruning, clears, compaction): write `pack-<n+1>.ssp`
  whole and `fsync` it, commit an index naming it, then delete the old
  pack. Branches with nothing removed are copied record for record;
  others are rebuilt newest to oldest with the keyframe rule, so no
  delta is ever left pointing at a removed version.
- **Compaction:** a demoted head leaves its old keyframe unreferenced.
  When unreferenced bytes exceed live ones, the pack is rewritten, so
  disk use stays within about twice the live size.
- **On load:** any file in a document directory other than the index and
  the pack it names is a leftover of an interrupted write and is deleted.
  An unreadable index moves the whole directory to `quarantine/`.
- **Reads** check the record CRC, that each delta fits, the text length,
  and the SHA-256 of the rebuilt text. A failure is reported as
  `Corrupt` with the nearest intact keyframe as a fallback.

## Retention

- Schedule: keep everything from the last 24 hours, then the newest per
  hour to 7 days, per day to 90 days, per week after. Buckets are fixed
  multiples of wall time, so the schedule is idempotent.
- Limits (per file 100 MB, total 1 GB by default, or none) remove only
  versions that are not a branch's latest, not pinned and at least 7 days
  old, oldest first; under the total limit, files not opened for 90 days
  go first. If a limit cannot be met, retention stops and reports a
  `LimitShortfall`.
- Every removal, automatic or asked for, and every version dropped
  because its chain was already damaged, is a line in
  `cleanup-log.jsonl`: `{"time","uri","removed","freed","reason","limit"}`.

## Export (`.zip`), format version 1

```
manifest.json
d<document index>/v<seq>.txt     each version's full text, WTF-8
```

```json
{
  "format": "ss-markdown-history",
  "formatVersion": 1,
  "exportedAt": 1700000000000,
  "documents": [{
    "uri": "content://…/handbook.md",
    "branches": [{"id": 0, "label": ""}],
    "versions": [{
      "seq": 12, "branch": 0, "time": 1700000000000, "kind": "Saved",
      "label": null, "pinned": false, "parent": 11,
      "added": 3, "removed": 1, "length": 20480,
      "sha256": "…64 hex…", "path": "d0/v12.txt"
    }]
  }]
}
```

- Texts, not packs: an export is readable with any unzip tool and does
  not depend on this pack format.
- An export is written as `<name>.part`, read back and checked against
  every SHA-256, and only then renamed. A clear with "Export first"
  deletes nothing unless that succeeded.
- **Import** checks every text against its hash before writing anything,
  then attaches each exported branch as a **new branch** of the target
  document, with fresh sequence numbers and parents remapped. The
  document's own branch and the file itself are never modified. A newer
  `formatVersion` is refused as `Unsupported`.
