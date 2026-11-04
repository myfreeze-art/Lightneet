# Threat model

Lightneet aims to be honest about what it can and cannot do. This page is that honesty in
writing.

## What Lightneet protects against

| Threat | Protection |
|---|---|
| **Lost or stolen phone** | Nothing is readable without the password. File names, sizes, folder structure and counts are encrypted too. |
| **Forensic image of storage** (chip-off, backup extraction, `adb` on a rooted device) | The header is sealed by a non-exportable Keystore/StrongBox key, so a copy is useless away from this phone. Brute-forcing passwords on GPUs is impossible. |
| **Brute force on the phone itself** | Each guess costs one Argon2id run (≈1 s, 64–256 MiB). An optional attempt limit erases everything. The counter is written *before* the password is checked. |
| **Rollback of the counter** (copy files → guess → restore files) | Each attempt rotates the device key and destroys the old one, so restored files cannot be opened at all. |
| **Coercion: "open it"** | A panic password looks like a normal login and silently destroys the hidden vault (or everything). |
| **Coercion: "is there more?"** | The hidden vault lives in a reserve that always exists and always looks used. Slots are always four, and unused ones are noise. |
| **Shoulder-surfing, screenshots, recent-apps preview** | `FLAG_SECURE`, a blank recents preview, and the same title in every vault. |
| **Cloud backups / device transfer** | Storage lives in `noBackupFilesDir`, with `allowBackup=false` and every extraction rule excluded. |
| **Network leaks** | The app has no `INTERNET` permission. It is removed even if a library requests it. |
| **Keyboard learning the password** | `IME_FLAG_NO_PERSONALIZED_LEARNING`, no suggestions, no autofill, no copy/paste. |
| **Tampering with stored data** | Every block is authenticated, and its position is bound into the associated data. |
| **Crash mid-write** | A/B superblocks with copy-on-write, so the last committed state always survives. |

## What it does not protect against

- **A compromised phone while the vault is open.** Malware with root, or with accessibility
  access, can read the screen, keystrokes or process memory. No app can fix that.
- **Third-party keyboards** may still log keystrokes despite the flags. A built-in keyboard is on
  the roadmap.
- **Memory forensics on a running, unlocked app.** Keys are wiped on lock, but the JVM may have
  copied arrays before then. Keeping keys only in `mlock`ed native memory is on the roadmap.
- **Comparing storage snapshots over time.** An adversary who images the phone twice can see that
  blocks changed in the reserve the main vault never touches. That suggests a hidden vault is in
  use. This limitation is shared by every deniable-encryption design (VeraCrypt included).
- **Original files.** Importing copies a file; the original stays until you delete it. Flash
  wear-levelling means "deleted" originals may be recoverable with forensic tools.
- **Forgotten passwords.** There is no recovery. This is a design decision.
- **Losing the phone's Keystore** (factory reset, some security-patch edge cases). This makes the
  storage permanently unreadable by design. Lightneet keeps data only on the phone, so keep your
  own copies of anything irreplaceable.
- **The rubber-hose problem, beyond plausibility.** Deniability helps only if the adversary cannot
  prove a hidden vault exists. Panic and hidden vaults are tools, not guarantees.

## Open source

The security must not depend on the code being secret (Kerckhoffs's principle). Read the code,
check `docs/SPEC.md`, and run the tests: `./gradlew :core:jvmTest :app:testDebugUnitTest`.
