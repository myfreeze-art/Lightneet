# Lightneet storage format, version 1

This document is the normative description of how Lightneet stores data. Anyone should be able
to write an independent implementation (for example the future iOS app) from it alone.
Test vectors live in `core/src/jvmTest`.

## 1. Primitives

| Purpose | Primitive | Parameters |
|---|---|---|
| Password → key | Argon2id v1.3 (`crypto_pwhash`, `ALG_ARGON2ID13`) | 32-byte output, 16-byte salt, `opsLimit`/`memLimit` from the header |
| Authenticated encryption | XChaCha20-Poly1305-IETF | 32-byte key, 24-byte random nonce, 16-byte tag |
| Key derivation | keyed BLAKE2b-256 (`crypto_generichash`) | `subkey(master, label) = BLAKE2b(key = master, msg = "lightneet/v1/" ‖ label)` |
| Device binding | Android Keystore AES-256-GCM (StrongBox if present, else TEE) | non-exportable key |

All integers are **little-endian**. `ad(tag, n₁, n₂, …)` means the UTF-8 bytes of `tag` followed by
each number as a u64. It is used as AEAD associated data.

Random nonces are safe: with 192-bit nonces the collision probability is negligible for any
realistic number of writes.

## 2. Files

```
<app noBackupFilesDir>/vault/
├── header.bin          the sealed header (§3)
└── pool/
    ├── s000000.dat     segment 0: segmentBlocks × blockSize bytes
    ├── s000001.dat
    └── …
```

Every block in the pool is `blockSize` bytes long (default 256 KiB), and a segment holds
`segmentBlocks` blocks (default 256, so 64 MiB). Block `i` lives in segment `i / segmentBlocks`
at byte offset `(i % segmentBlocks) × blockSize`.

New segments are filled entirely with random bytes before use. That is how free space becomes
indistinguishable from used space.

## 3. Header

### 3.1 Outer layer (on disk)

```
u32  keyIdLength
u8[] keyId              UTF-8 Keystore alias, not secret
u8[] sealed             device-sealed header plaintext (AES-256-GCM: u8 ivLength | iv | ciphertext+tag)
```

The file is written atomically: write `header.tmp`, fsync, then rename.

**Every unlock attempt re-seals the header under a freshly generated Keystore key and then
deletes the previous key.** Consequences:

- A copy of `header.bin` from the past can no longer be opened. This defeats rollback attacks on
  the attempt counter.
- Deleting the current key crypto-erases the whole storage instantly and reliably, even on
  flash memory.

### 3.2 Header plaintext (fixed 592 bytes)

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `"LNV1"` |
| 4 | 1 | version = 1 |
| 5 | 1 | hidden reserve: 0 = none, 1 = quarter, 2 = half |
| 6 | 2 | reserved, zero |
| 8 | 4 | blockSize |
| 12 | 4 | segmentBlocks |
| 16 | 4 | Argon2id opsLimit |
| 20 | 4 | Argon2id memLimit in KiB |
| 24 | 16 | salt |
| 40 | 4 | failedAttempts |
| 44 | 4 | maxAttempts (0 = unlimited) |
| 48 | 4 × 136 | slots 0‥3 |

Configuration fields are not secret. The magic is only visible to someone who can already use the
phone's Keystore as this app.

### 3.3 Slots

There are always four slots of 136 bytes: `nonce (24) | ciphertext (96) | tag (16)`. An unused
slot is 136 random bytes. Slots have fixed roles:

| Slot | Role |
|---|---|
| 0 | main vault |
| 1 | panic password |
| 2 | hidden vault |
| 3 | spare (always random in v1) |

The slot key is `KEK = Argon2id(password, salt, opsLimit, memLimit)`. Every slot shares the same
salt, so a single Argon2 run tests a password against all four slots. The slot's AD is
`ad("slot", slotIndex) ‖ salt`.

Slot payload, 96 bytes:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type: 1 = MAIN, 2 = HIDDEN, 3 = PANIC (must match the slot role) |
| 1 | 1 | panic mode: 1 = destroy hidden, 2 = destroy all (PANIC only) |
| 2 | 6 | reserved |
| 8 | 32 | master key (for PANIC: the **main** vault's master key) |
| 40 | 8 | superblock A index |
| 48 | 8 | superblock B index |
| 56 | 40 | reserved, zero |

Two passwords may never open two different slots. Implementations must reject such a password
when it is being set.

## 4. Regions

The hidden reserve splits the pool by block index:

- quarter: block `i` belongs to the **hidden** region when `i % 4 == 3`;
- half: when `i % 2 == 1`;
- every other block belongs to the **main** region.

A vault allocates only inside its own region, lowest free index first. Because the regions are
interleaved, pool growth extends both, and the two vaults can never overwrite each other. The
main vault does not know whether the hidden region is used.

## 5. Blocks

```
block = nonce (24) | XChaCha20-Poly1305(key, nonce, ad, plaintext[capacity]) (capacity + 16)
capacity = blockSize − 40
```

Plaintext is always padded with zeros to `capacity`, so every block has the same length and
every byte of it looks random.

| Block kind | Key | AD |
|---|---|---|
| superblock | `subkey(master, "superblock")` | `ad("sb", blockIndex)` |
| index chunk | `subkey(master, "index")` | `ad("ix", blockIndex, generation, seq)` |
| file data | per-file random key | `ad("fd", blockIndex, fileId, seq)` |
| thumbnail | per-file random key | `ad("th", blockIndex, fileId, seq)` |

Binding the block index and sequence number into the AD prevents an attacker from swapping or
reordering blocks without detection.

## 6. Superblocks and the index

Each vault has two superblocks, A and B, at the first two indices of its region. Their plaintext:

```
"LNSB" | u64 generation | u64 indexLength | u32 n | u64 indexBlock[n]
```

The index is a Protocol Buffers message (`VaultIndex` in `core/.../vault/Index.kt`): the
`nextId` counter, a list of entries, and per-vault settings. Each entry holds an id, parent id,
name, folder flag, size, MIME type, timestamps, the 32-byte file key, the list of data block
indices, and the thumbnail block indices and size. The serialized bytes are split into
`capacity`-sized chunks.

**Commit procedure (crash safe):**

1. Write the new index into freshly allocated blocks, then fsync.
2. Write a superblock with `generation + 1` into the *older* of A/B, then fsync.
3. Scrub the previous index blocks: overwrite them with random bytes and free them.

**Loading:** decrypt both superblocks, then try them in descending generation order and use the
first one whose index decrypts completely.

## 7. Unlock algorithm

```
load header (device key)                      → missing: NoVault; unopenable: Unreadable
if maxAttempts > 0 and failed ≥ maxAttempts   → destroy everything
failed += 1; persist (rotating device key)    # counted before any password work
KEK = Argon2id(password)
try all 4 slots (always all 4)
no match: if limit reached → destroy everything; else WrongPassword(left)
match:    failed = 0
  MAIN / HIDDEN  → persist; open that vault
  PANIC, mode 1  → slot 2 := random; persist; open main vault using the master key in the panic slot
  PANIC, mode 2  → all slots := random; slot 0 := new MAIN (fresh master) sealed with KEK;
                   persist; open the new, empty vault
```

"Destroy everything" deletes the device key, `header.bin` and the pool.

## 8. Deleting files

Deleting a file removes its entry from the index (commit), then overwrites each of its blocks
with random bytes and forgets its key. On flash storage the overwrite is best effort. The key
being gone is what makes the data unrecoverable, as long as the index blocks that held the key
were scrubbed too.
