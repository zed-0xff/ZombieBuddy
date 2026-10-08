# Mod Signing

Mod signing is ZombieBuddy's way to verify that a Java mod JAR was approved by the author key it claims to come from.

It is not a safety guarantee. It is an identity and integrity check.

---

## The Short Version

A signed Java mod has two files:

- `YourMod.jar` - the Java code that will be loaded.
- `YourMod.jar.zbs` - a small signature file next to the JAR.

ZombieBuddy checks that:

1. The `.zbs` file names a Steam author ID.
2. The JAR's SHA-256 hash matches the signed payload.
3. The signature was made by a public key associated with that Steam author ID.
4. If the mod is installed from Steam Workshop and the uploader ID is known, the signer Steam ID matches the Workshop item uploader.

If all checks pass, ZombieBuddy knows: this exact JAR was signed by a key associated with that author identity.

---

## What Signing Proves

Signing proves file integrity:

- The JAR has not changed since it was signed.
- A copied `.zbs` file cannot validate a different JAR.
- If someone edits, repacks, or injects code into the JAR, the signature stops matching.

Signing also proves key identity:

- The signature was made by the private key corresponding to an accepted public key.
- The public key is associated with the signer SteamID64 through either:
  - the known authors list, or
  - `JavaModZBS:<public key>` on the author's Steam profile.

Signing can support author trust:

- A user can approve one signed mod and choose to trust that author.
- Later JARs signed by the same trusted author key can be allowed without asking again.

---

## What Signing Does Not Prove

Signing does not prove that the mod is safe.

A malicious author can sign malicious code. A signature only says who signed the file, not whether the code is good.

Signing does not prove that the code was reviewed.

ZombieBuddy does not inspect source code, decompile JARs, or judge behavior.

Signing does not protect users if the author's private key is stolen.

If a private key leaks, someone else may be able to sign files as that author until the accepted key is removed or replaced. Removing it from a Steam profile does not revoke a previously verified local cache entry; see the cache policy below.

Signing does not automatically make unsigned mods bad.

Unsigned mods may be legitimate, especially during development or before an author has set up signing. ZombieBuddy can still ask users to approve unsigned mods depending on policy.

---

## Why Steam Identity Is Used

The public key alone is hard for users to understand. A SteamID64 gives ZombieBuddy a stable author identity to show and store trust against.

Steam identity is used for:

- displaying an author in the approval UI
- storing trusted-author decisions
- finding the author's public signing key
- checking that a signed Workshop mod was signed by the Workshop uploader, when that information is available

This prevents trust from being attached to a vague "some key". Instead, the trust decision is "trust signed Java mods from this Steam author".

Important: Steam identity is not magic security. It answers "is this the same author identity?" It does not answer "is this author trustworthy?"

---

## Known Authors And `authors.json`

ZombieBuddy can load a known authors list from `authors.json`. Entries look like:

```json
{
  "id": 76561198012345678,
  "name": "Author name",
  "keys": ["64 hex public key"]
}
```

`authors.json` is a generated, signed file. Its source of truth is the `authors/` directory, which holds one JSON file per author (e.g. `authors/author-name.json`). The `authors.json` file is rebuilt from `authors/*.json` and re-signed whenever the mod is built.

This list gives ZombieBuddy a stable mapping from SteamID64 to display name and public signing keys. ZombieBuddy syncs `authors.json` from GitHub and caches it in the ZombieBuddy config directory, so new author entries can be added without publishing a ZombieBuddy mod update, and verification can still work when the remote list is temporarily unavailable.

ZombieBuddy checks signing keys in order: `authors.json`, the local author cache, then the author's Steam profile. A successful check at any stage validates the signature without consulting later stages. A mismatch in the official list does not block fallback to the local cache or Steam profile; the signature is rejected only if none of these sources can verify it.

Authors who want to keep their Steam profile private can submit a pull request to add their SteamID64, display name, and public key instead of publishing `JavaModZBS:<key>` on their profile. Do this by adding a new file to `authors/` (named after the author, e.g. `authors/author-name.json`), not by editing `authors.json` directly — that file is regenerated from `authors/` and any direct edits to it will be overwritten.

## Local Steam Profile Cache

When neither the official list nor the local cache verifies a signature, a successful Steam-profile check saves the verified public key and profile name in `authors.local.json` under the active `config_dir` (default `~/.zombie_buddy`). The key and name come from one HTTPS XML profile response. Only a key that verifies the current JAR is saved; a profile lookup alone never grants trust or approval. The successful profile response updates that author's cached keys, removing old keys no longer published in the profile and retaining previously verified keys only if still published. Other authors' entries remain unchanged.

Matching cached keys verify later JARs without a Steam profile request, including after a process restart. A missing key or signature mismatch permits a profile lookup; one response or failure per author is reused for the process lifetime. The first verified profile name stays attached to that SteamID64 even after a name or key change. Display names are labels, not identities.

This cache deliberately has no expiry or periodic refresh. Removing a key from a Steam profile does not revoke a matching cached key until a successful profile fallback updates the entry. To force a fresh lookup, close all processes using that configuration and remove the relevant entry from `authors.local.json` (or remove the file). `http_cache_ttl` does not expire or disable this verified-key cache.

Client, Coop and headless server processes sharing `config_dir` merge writes under `authors.local.lock` and publish the JSON atomically. Different configuration directories have separate caches. Corrupt or unsupported cache files are ignored and preserved; a successful fresh verification can still proceed, with a warning if saving fails.

`policy=allow-all` skips signature verification, so it neither checks nor populates this cache. Other approval policies, trusted-author decisions, unsigned-mod settings and Workshop uploader binding retain their existing behavior.

### Why not just use the Steam profile name?

Steam display names are not reliable author identities.

They can change at any time, may contain formatting or confusing impersonation text, and may be unavailable due to privacy settings, network failures, rate limits, or Steam pages returning different content. A live profile name is therefore not a good thing to store as a trust label.

The stable identity is the SteamID64. A previously verified local profile name is retained as its display label; otherwise `authors.json` supplies the curated name. If neither is available, ZombieBuddy shows the raw SteamID64.

---

## What Is Signed

ZombieBuddy signs a canonical payload based on:

```text
ZBS:<SteamID64>:<JAR_SHA256>
```

The same author may need to use the same signed JAR in local installs, test uploads, mirrors, or multiple Workshop items. When a Workshop uploader ID is available, ZombieBuddy checks it separately against the signer SteamID64.

---

## The `.zbs` File

A `.zbs` file is stored next to the JAR:

```text
YourMod.jar
YourMod.jar.zbs
```

The sidecar contains:

```text
ZBS
SteamID64:<author steam id>
Signature:<ed25519 signature hex>
```

Users and mod packs should distribute the `.zbs` file together with the JAR. If the `.zbs` file is missing, the mod is treated as unsigned.

---

## Public And Private Keys

The author keeps a private key locally. This key should not be shared or committed to a repository.

The public key is published so ZombieBuddy can verify signatures. ZombieBuddy can read it from:

- the known authors list, or
- the author's Steam profile summary as `JavaModZBS:<64 hex public key>`

The private key signs mod JARs. The public key verifies them.

---

## Trust Author

When ZombieBuddy shows a signed mod, the user may choose to trust the author.

If the user trusts the author:

- ZombieBuddy records trust for that author's SteamID64.
- Future JARs signed by that author can be allowed automatically.
- The JAR still has to verify cryptographically.

Trust does not mean "allow any file with this SteamID64 written in it". The signature must still match the JAR and the author's public key.

---

## Failure Cases

Common signature failure cases:

- `YourMod.jar.zbs` is missing.
- The JAR was rebuilt after signing, but the `.zbs` file was not regenerated.
- The `.zbs` file belongs to another JAR.
- The public key is missing from the known authors list and the Steam profile.
- The Steam profile key is malformed.
- The signer SteamID64 does not match the Workshop item uploader.
- The author's private key or configured SteamID64 is wrong.

---

## Advice For Mod Authors

- Keep your private key private.
- Sign the final JAR you distribute.
- Regenerate the `.zbs` file after every JAR rebuild.
- Distribute `YourMod.jar.zbs` alongside `YourMod.jar`.
- Publish your public key once using `JavaModZBS:<key>` or the known authors list.
- Do not promise that signing means your mod is "safe". Say that signing lets users verify the JAR came from your author key and was not changed after signing.

For setup commands and Gradle configuration, see [Modding Guide](ModdingGuide.md#signing-your-mod-optional).
