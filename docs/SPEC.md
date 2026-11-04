# Lightneet storage format, version 2

This document is the normative description of how Lightneet stores data. Anyone should be able
to write an independent implementation (for example the future iOS app) from it alone.
Test vectors live in `core/src/jvmTest`.

Version 2 replaced version 1 after the first security self-review (see
[THREAT_MODEL.md](THREAT_MODEL.md), "Self-review history"). There is no migration: v1 only ever
shipped as a preview build.

## 1. Primitives

| Purpose | Primitive | Parameters |
|---|---|---|
| Password stretching | Argon2id v1.3 (`crypto_pwhash`, `ALG_ARGON2ID13`) | 32-byte output, 16-byte salt, `opsLimit`/`memLimit` from the header |
| Hardware pepper | Android Keystore HMAC-SHA256 (StrongBox if present, else TEE) | non-exportable, usable only while the phone is unlocked |
| Header seal | Android Keystore AES-256-GCM (StrongBox if present, else TEE) | non-exportable, rotated on every unlock attempt, usable only while the phone is unlocked |
| Authenticated encryption | XChaCha20-Poly1305-IETF | 32-byte key, 24-byte random nonce, 16-byte tag |
| Key derivation | keyed BLAKE2b-256 (`crypto_generichash`) | `KDF(key, label, ctx) = BLAKE2b(key = key, msg = "lightneet/v2/" ‖ label ‖ ctx)` |

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

**Timestamps.** Every session, of either vault, rewrites the first byte of every segment with its
own value when it opens and when it closes. That updates every segment's modification time
without changing any data. As a result, file timestamps never show activity that the main vault
cannot account for.

## 3. Header

### 3.1 Outer layer (on disk)

```
u32  keyIdLength
u8[] keyId              UTF-8 Keystore alias of the sealing key, not secret
u8[] sealed             device-sealed header plaintext (AES-256-GCM: u8 ivLength | iv | ciphertext+tag)
```

The file is written atomically: write `header.tmp`, fsync, then rename.

**Every unlock attempt re-seals the header under a freshly generated sealing key and then
deletes the previous key.** When the vault is unlocked, every `lightneet-*` Keystore alias other
than the current sealing key and the pepper key is deleted. This removes keys orphaned by a crash
between the rename and the deletion.

### 3.2 Header plaintext (fixed 784 bytes)

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `"LNV2"` |
| 4 | 1 | version = 2 |
| 5 | 1 | hidden reserve: 0 = none, 1 = quarter, 2 = half |
| 6 | 2 | reserved, zero |
| 8 | 4 | blockSize |
| 12 | 4 | segmentBlocks |
| 16 | 4 | Argon2id opsLimit |
| 20 | 4 | Argon2id memLimit in KiB |
| 24 | 16 | salt |
| 40 | 4 | failedAttempts |
| 44 | 4 | maxAttempts (0 = unlimited; **default 10**) |
| 48 | 64 | pepper key alias, UTF-8, zero-padded |
| 112 | 4 × 32 | epochs 0‥3 (§6) |
| 240 | 4 × 136 | slots 0‥3 |

### 3.3 Slots and the KEK

There are always four slots of 136 bytes: `nonce (24) | ciphertext (96) | tag (16)`. An unused
slot is 136 random bytes, and its epoch is 32 random bytes. Slots have fixed roles:

| Slot | Role |
|---|---|
| 0 | main vault |
| 1 | panic password |
| 2 | hidden vault |
| 3 | spare (always random in v2) |

The key that opens a slot is

```
S   = Argon2id(password, salt, opsLimit, memLimit)
P   = HMAC-SHA256_pepperKey(S)            computed inside the phone's secure hardware
KEK = BLAKE2b(key = S, msg = "lightneet/v2/kek" ‖ P)
```

Because `P` can only be computed by this phone's Keystore, **every** password guess needs the
phone. This holds even for an attacker who has extracted the header plaintext. The slot's AD is
`ad("slot", slotIndex) ‖ salt`. All slots share the salt, so a single derivation tests a password
against all four.

The slot payload (96 bytes) is unchanged from v1:

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type: 1 = MAIN, 2 = HIDDEN, 3 = PANIC (must match the slot role) |
| 1 | 1 | panic mode: 1 = destroy hidden, 2 = destroy all (PANIC only) |
| 2 | 6 | reserved |
| 8 | 32 | master key (for PANIC: the **main** vault's master key) |
| 40 | 8 | superblock A index |
| 48 | 8 | superblock B index |
| 56 | 40 | reserved, zero |

### 3.4 Setting a password

When a password is about to be stored in slot `T` from a session of slot `C`, it is first
tested against all slots. Suppose it opens slot `X`:

| Case | Result |
|---|---|
| `X = T`, or no slot matches | proceed |
| `X = C` (the session's own current password) | rejected: "same as current password" |
| session is the main vault | slot `X` and its epoch are **silently replaced with noise**, then proceed |
| session is the hidden vault | rejected: "password unavailable" |

There must be no "password already in use" answer in the main vault. Otherwise a coerced user's
attacker could test guesses for the hidden password without limit, and without the attempt
counter seeing them.

The panic password is subject to the same strength requirement as the others, because its slot
contains the main vault's master key.

## 4. Regions

The hidden reserve splits the pool by block index:

- quarter: block `i` belongs to the **hidden** region when `i % 4 == 3`;
- half: when `i % 2 == 1`;
- every other block belongs to the **main** region.

A vault allocates only inside its own region, lowest free index first.

**Only the main vault grows the pool automatically.** When the hidden region is full, the hidden
vault reports "out of space", and the user enlarges the storage explicitly, from either vault.
That is an ordinary action. If the hidden vault grew the pool on its own, the pool would become
far larger than the main vault's content could explain.

## 5. Blocks

```
block = nonce (24) | XChaCha20-Poly1305(key, nonce, ad, plaintext[capacity]) (capacity + 16)
capacity = blockSize − 40
```

Plaintext is always padded with zeros to `capacity`.

| Block kind | Key | AD |
|---|---|---|
| superblock | `KDF(master, "superblock", epoch)` | `ad("sb", blockIndex)` |
| index chunk | `KDF(master, "index", epoch)` | `ad("ix", blockIndex, generation, seq)` |
| file data | per-file random key | `ad("fd", blockIndex, fileId, seq)` |
| thumbnail | per-file random key | `ad("th", blockIndex, fileId, seq)` |

## 6. Superblocks, the index and epochs

Each vault has two superblocks, A and B, at the first two indices of its region:

```
"LNSB" | u64 generation | u64 indexLength | u32 n | u64 indexBlock[n]
```

The index is a Protocol Buffers message (`VaultIndex` in `core/.../vault/Index.kt`). It holds
entries with their names, sizes, dates, **per-file keys** and block lists, plus per-vault
settings.

The **epoch** is the 32 random bytes in the header that belong to the vault's slot. Superblock and
index keys depend on it.

**Normal commit (crash safe):**
1. Write the new index into fresh blocks, then fsync.
2. Write a superblock with `generation + 1` into the *older* of A/B, then fsync.
3. Scrub the previous index blocks.

**Commit that deletes files** additionally moves the vault to a new epoch:
1. Generate a new random epoch `E′`.
2. Write the new index and superblock under keys derived from `E′`, then fsync.
3. Store `E′` in the header and persist it (rotating the sealing key).
4. Only then scrub the previous index blocks.

If step 3 fails, the commit is rolled back. The header still holds the old epoch, so the previous
superblock remains the valid one.

After an epoch change, an older copy of the pool, taken before the deletion, can no longer be
decrypted, even with the password and the phone. Its index is under the old epoch's keys, and the
old epoch exists only in header copies sealed with destroyed keys.

## 7. Unlock algorithm

```
read header:   missing → NoVault · key gone / tampered → Unreadable · keystore refused → TemporarilyUnavailable
delete orphaned lightneet-* keys
if maxAttempts > 0 and failed ≥ maxAttempts   → destroy everything
failed += 1; persist (rotating sealing key)   # counted before any password work
KEK = derive(password)                        # Argon2id + hardware pepper (§3.3)
try all 4 slots (always all 4)
no match: if limit reached → destroy everything; else WrongPassword(left)
match:    failed = 0
  MAIN / HIDDEN → persist; open that vault
  PANIC, mode 1 → slot 2 and epoch 2 := random; persist; open the main vault using the panic slot's master key
  PANIC, mode 2 → all slots and epochs := random; slot 0 := new MAIN (fresh master) sealed with KEK;
                  persist; open the new, empty vault
```

A Keystore failure at any step yields `TemporarilyUnavailable`. It is never treated as a
destroyed storage.

"Destroy everything" deletes every Lightneet Keystore key (sealing and pepper), `header.bin` and
the pool.

## 8. Deleting files

Deleting a file removes its entry and moves the vault to a new epoch (§6). It then overwrites
each of the file's blocks with random bytes and forgets the file's key.

The overwrite is best effort on flash storage. Unrecoverability rests on the key: the only copies
of it are in index versions protected by destroyed epochs.
