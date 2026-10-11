# Vanilla Minecraft 26.2 server artifacts

Fetched 2026-09-10 from Mojang piston-meta. Server jar + mappings only, no client.

## Version status

| Item | Value |
|---|---|
| 26.2 exists? | Yes, `type: release` |
| 26.2 release date | 2026-06-16T12:03:33Z |
| Latest release in manifest | 26.2 |
| 26.3 full release? | **No.** Still pre-release. Latest: `26.3-pre-3` (releaseTime 2026-09-08T13:04:44Z). Snapshots run `26.3-snapshot-1` (2026-06-23) through `26.3-snapshot-10` (2026-08-25), then `26.3-pre-1` (2026-09-01), `pre-2` (2026-09-04), `pre-3` (2026-09-08). |
| Protocol version (26.2) | **776** (from `version.json` inside the jar; the 26.2 version manifest has no `protocol_version` field) |
| World version | 4903 |
| Data pack version | 107.1 (resource 88.0) |
| Java requirement | majorVersion **25**, component `java-runtime-epsilon` (both version manifest and in-jar `version.json` agree) |

## URLs and hashes

Version manifest (cached at `preresearch/26.2.json`):

- `https://piston-meta.mojang.com/v1/packages/2ee1d19a4f344bcc84c0499ba332b98f6441e6ff/26.2.json`

Server jar (local: `vanilla-26.2/server.jar`):

- URL: `https://piston-data.mojang.com/v1/objects/823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar`
- SHA1: `823e2250d24b3ddac457a60c92a6a941943fcd6a` (verified with `sha1sum` after download: match)
- Size: 60,894,273 bytes (~58 MiB)

## Mappings: none published for 26.2

The 26.2 version manifest `downloads` section contains **only `client` and `server` keys**.
No `client_mappings` / `server_mappings` entries (control check: 1.21.11 manifest still has both,
so this is a 26.x change, not a parsing error). Same for `26.3-pre-3` (only client+server).

Consequences and alternatives investigated (2026-09-10):

- The server jar ships **deobfuscated**: nested game jar classes use readable names
  (`net/minecraft/server/Bootstrap.class`, `net/minecraft/CharPredicate.class`, zero single-letter
  `net/minecraft/[a-z]/` packages). Mojang appears to have stopped obfuscating 26.x server jars,
  which is presumably why no official mappings are published.
- Fabric Yarn: no builds for 26.2 or 26.1 (meta.fabricmc.net returns `[]`); newest yarn game
  version is 1.21.11.
- Fabric Intermediary for 26.2 returns only the `0.0.0` placeholder stub, i.e. not mapped.
- NeoForge maven (`maven.neoforged.net`) has no `net/minecraft/server` 26.x artifacts found.

Takeaway for the mc-light port: the 26.2 server jar itself is the readable reference. No
TinyV2/Mojang mapping file to download or apply.

## Jar layout

- Outer `server.jar` (60,894,273 bytes) is a bundler: 165 entries, containing
  `META-INF/versions/26.2/server-26.2.jar` (24,952,681 bytes) plus libraries under `META-INF/libraries/`.
- Nested game jar: **7,445 `.class` files**, all under readable `net/minecraft/...` / `com/mojang/...` names.
- `META-INF/main-class` points at the bundler main (`net.minecraft.bundler.Main` per boot error below).

## Boot attempt (Java 17, expected failure)

Local java: OpenJDK 17.0.20.1 (Debian). Command: `java -jar server.jar --nogui --help`.

Exact error (proves the JDK 25 requirement; class file version 69 = Java 25, 61 = Java 17):

```text
Fehler: Beim Laden der Klasse net.minecraft.bundler.Main ist ein LinkageError aufgetreten
    java.lang.UnsupportedClassVersionError: net/minecraft/bundler/Main has been compiled by a more recent version of the Java Runtime (class file version 69.0), this version of the Java Runtime only recognizes class file versions up to 61.0
```

(Note: system locale is German, hence the German JVM launcher message. The class file version
numbers are the load-bearing part: 69.0 required, 61.0 available.)

The jar cannot boot here without a Java 25 runtime. No Java 25 was installed for this check;
install `temurin-25` / `openjdk-25` (or use the `java-runtime-epsilon` component) before any
real boot test.

## Re-fetch script (port rehearsal for 26.3 release day)

```sh
#!/bin/sh
# Re-fetch vanilla server artifacts for a given version (e.g. VERSION=26.3).
# Usage: VERSION=26.3 sh refetch-vanilla.sh
set -eu
VERSION="${VERSION:?set VERSION, e.g. VERSION=26.3}"
OUT="preresearch/vanilla-${VERSION}"
mkdir -p "$OUT"

# 1. Locate the version manifest URL from piston-meta
URL=$(curl -s https://piston-meta.mojang.com/mc/game/version_manifest_v2.json \
  | python3 -c "import json,sys; m=json.load(sys.stdin); print(next(v['url'] for v in m['versions'] if v['id']=='$VERSION'))")
curl -s "$URL" -o "$OUT/$VERSION.json"

# 2. Extract server jar URL + SHA1 (+ mappings if Mojang publishes them again)
python3 - "$OUT/$VERSION.json" <<'EOF'
import json,sys
m=json.load(open(sys.argv[1]))
print('server URL :', m['downloads']['server']['url'])
print('server SHA1:', m['downloads']['server']['sha1'])
print('server size:', m['downloads']['server']['size'])
print('extra keys :', sorted(k for k in m['downloads'] if k!='server' and k!='client'))
print('java       :', m.get('javaVersion'))
EOF
SERVER_URL=$(python3 -c "import json; print(json.load(open('$OUT/$VERSION.json'))['downloads']['server']['url'])")
EXPECTED=$(python3 -c "import json; print(json.load(open('$OUT/$VERSION.json'))['downloads']['server']['sha1'])")

# 3. Download and verify
curl -sL -o "$OUT/server.jar" "$SERVER_URL"
ACTUAL=$(sha1sum "$OUT/server.jar" | cut -d' ' -f1)
[ "$ACTUAL" = "$EXPECTED" ] || { echo "SHA1 MISMATCH: $ACTUAL != $EXPECTED"; exit 1; }
echo "OK $VERSION sha1=$ACTUAL"

# 4. Protocol + java version from in-jar version.json (manifest has no protocol field on 26.x)
unzip -p "$OUT/server.jar" version.json | python3 -m json.tool | grep -E 'protocol_version|world_version|java_version|releaseTime|"id"'
```

## Local files

- `preresearch/version_manifest_v2.json` (full game manifest, 2026-09-10 snapshot)
- `preresearch/26.2.json` (26.2 version manifest)
- `preresearch/vanilla-26.2/server.jar` (verified, **gitignored, local-only**)
- `.gitignore` at repo root excludes `preresearch/vanilla-*/**.jar` and `*.jar`
