# Threat model

Lightneet aims to be honest about what it can and cannot do. This page is that honesty in
writing.

## What Lightneet protects against

| Threat | Protection |
|---|---|
| **Lost or stolen phone** | Nothing is readable without the password. File names, sizes, folder structure and counts are encrypted too. |
| **Forensic image of storage** (chip-off, backup extraction) | The header is sealed by a non-exportable Keystore/StrongBox key, so a copy away from this phone is useless. |
| **Offline brute force, even with root** | Every password guess needs an HMAC computed inside this phone's secure hardware (the "pepper", see SPEC §3.3). Extracting the header plaintext does not allow guessing on a GPU farm. |
| **Attacks on a seized, locked phone** | All Keystore keys require the phone to be unlocked, so tools that work against a locked device cannot use them. |
| **Brute force on the phone itself** | Each guess costs one Argon2id run (≈1 s, 64–256 MiB) plus a hardware operation. After **10** wrong passwords (default, configurable) everything is erased. The counter is written *before* the password is checked. |
| **Rollback of the counter by copying app files** | Each attempt rotates the sealing key and destroys the old one, so restored files cannot be opened (see the root caveat below). |
| **Coercion: "open it"** | A panic password looks like a normal login and silently destroys the hidden vault (or everything). It must be as strong as the main password. |
| **Coercion: "is there more?"** | The hidden vault lives in a reserve that always exists and always looks used. Slots are always four, and unused ones are noise. Setting passwords never answers "already in use", so guesses for the hidden password cannot be tested from inside the main vault. The hidden vault never grows the storage by itself. Segment timestamps are refreshed by every session. |
| **Deleted files and old storage copies** | Deletions move the vault to a new epoch. A copy of the storage taken before the deletion stays undecryptable even if the password is obtained later. |
| **Secrets lingering in memory after lock** | Keys are wiped, and then the whole app process is ended. The OS reclaims its memory, including copies we cannot reach (native buffers, Strings, Bitmaps). |
| **Shoulder-surfing, screenshots, recent-apps preview** | `FLAG_SECURE`, a blank recents preview, and the same title in every vault. |
| **Malicious accessibility services / overlays** | On Android 14+, the screen is marked accessibility-data-sensitive, so only genuine accessibility tools can read it. Taps are ignored while another app draws over Lightneet. |
| **Leaving through the file picker** | Opening the system picker suspends auto-lock for at most 2 minutes. |
| **Cloud backups / device transfer** | Storage lives in `noBackupFilesDir`, with `allowBackup=false` and every extraction rule excluded. |
| **Network leaks** | The app has no `INTERNET` permission. It is removed even if a library requests it. |
| **Keyboard learning the password** | `IME_FLAG_NO_PERSONALIZED_LEARNING`, no suggestions, no autofill, no copy/paste, and no undo history in the field. |
| **Confused-deputy imports** | Only `content://` URIs are accepted from other apps. |
| **Tampering with stored data** | Every block is authenticated, and its position is bound into the associated data. |
| **Crash mid-write** | A/B superblocks with copy-on-write. An epoch change commits only after the header is durable. |
| **Supply chain** | Gradle checks a SHA-256 for every dependency and for the Gradle distribution. CI actions are pinned to commit hashes. The signing key exists on the build machine only during the signing step, and the release build never uses a shared cache. |

## What it does not protect against

- **A compromised phone while the vault is open.** Malware with root can read the screen,
  keystrokes or process memory, and can use the Keystore exactly like Lightneet does. That
  includes brute-forcing passwords *on the phone*, bypassing Lightneet's attempt counter, at the
  speed of Argon2id plus the secure hardware. A strong password is the defence here.
- **Rollback of the phone's keystore by root.** Android stores Keystore keys as hardware-encrypted
  blobs on disk. An attacker with root who copied those blobs earlier can restore them, which:
  - weakens the attempt-counter rollback protection;
  - weakens "destroying a key is a reliable erase", including what the panic password destroys,
    against an attacker who imaged the phone **with root before** the panic.

  The Android API does not expose hardware rollback resistance for app keys. Full protection
  needs hardware we cannot control.
- **Memory while unlocked.** Keys and decrypted data are in the app's memory while a vault is open.
  The JVM and JNA may make copies that cannot be wiped before the process ends.
- **Third-party keyboards.** They may still log keystrokes despite the flags. A built-in keyboard
  is on the roadmap.
- **Media decoding.** Video playback and thumbnails hand decrypted data to Android's media
  services (other processes) in memory.
- **Comparing storage snapshots over time.** An adversary who images the phone twice can see that
  blocks in the reserve changed, even though the main vault never touches them. That suggests a
  hidden vault is in use. Every deniable-encryption design (VeraCrypt included) shares this
  limitation.
- **Renames in old copies.** Only deletions change the epoch. An old copy of the storage may still
  show a file's previous name once the password is known.
- **Original files.** Importing copies a file; the original stays until you delete it.
  Wear-levelling on flash memory means "deleted" originals may be recoverable with forensic tools.
- **Forgotten passwords.** There is no recovery. This is a design decision.
- **Losing the phone's Keystore** (factory reset, some security-patch edge cases). This makes the
  storage permanently unreadable by design.
- **The rubber-hose problem, beyond plausibility.** Deniability helps only if the adversary cannot
  prove a hidden vault exists.

## Self-review history

**v0.1.0 → v0.2.0.** A self-review found 16 problems, all fixed in format v2:

| # | Problem | Fix |
|---|---|---|
| 1 | Device key used once; header plaintext allowed offline brute force | Hardware HMAC pepper in every guess; `setUnlockedDeviceRequired` |
| 2 | "Password already in use" oracle for the hidden password inside the main vault | Collisions silently destroy the other slot |
| 3 | Panic password could be 4 characters, yet holds the main key | Same strength rule as all passwords |
| 4 | Keystore rollback by root not documented | Documented above; impact reduced by #1 |
| 5 | Hidden vault grew the pool, betraying itself | Only the main vault grows automatically; explicit "Enlarge storage" |
| 6 | Segment mtimes revealed hidden activity | Every session touches every segment |
| 7 | File picker could keep the vault open indefinitely | Picker trip capped at 2 minutes |
| 8 | Old storage copies revealed deleted files once the password was known | Epochs (SPEC §6) |
| 9 | Copies of secrets in memory after lock | Process ends on lock; undo history disabled; thumbnails recycled |
| 10 | Transient Keystore error looked like "storage destroyed" | Separate "temporarily unavailable" state with no erase option |
| 11 | Accessibility services could read file names | `setAccessibilityDataSensitive`, `filterTouchesWhenObscured` |
| 12 | `file://` imports from other apps | `content://` only |
| 13 | File names in exception messages (logcat) | Removed |
| 14 | Keys orphaned by a crash mid-rotation | Removed on every unlock |
| 15 | CI supply chain | Dependency verification, pinned actions, short-lived key file, no cache for releases |
| 16 | Attempt limit off by default | 10 by default |

A self-review is not an audit. Independent review is welcome: see `docs/SPEC.md` and run
`./gradlew :core:jvmTest :app:testDebugUnitTest`.
