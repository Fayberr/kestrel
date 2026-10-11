Kestrel 26.2 server, packaged from main
=======================================

WHAT THIS IS
Kestrel is a from-scratch Minecraft Java server that runs vanilla's game
mechanics through vanilla's own classes. It is early. It generates byte-identical
vanilla terrain at Paper-like speed, streams chunks, ticks redstone and fluids,
tracks entities and players, and serves inventories.

It is NOT yet playable as a survival game. No block drops an item, nothing can be
picked up or granted, and placing a block writes stone whatever you were holding,
so tools, crafting, containers, eating, durability and bows are all out of reach.
Expect a walking, chatting, world-ticking server that you cannot yet play a game
on. docs/CAPABILITIES.md in the repository has the per-mechanic truth.

REQUIREMENTS
- Linux (x86_64 or aarch64) or Windows (x64).
- Network access on the first run, to fetch the JDK and the vanilla server jar.
- Nothing else. If you have no Java 25, setup downloads and verifies one.

STARTING IT
  Linux:     ./start.sh
  Windows:   start.bat

The first run downloads the pinned Temurin 25 JDK (about 200 MB) and Mojang's
pinned 26.2 server bundler (about 60 MB), verifies both against hardcoded
checksums, extracts them under runtime/, and then launches. Later runs start
immediately.

Useful options, both platforms:
  --port 25566          run on another port
  --online              require Mojang authentication (the default is offline)
  --world <dir>         use another world directory
  KESTREL_MEMORY=4G     raise the heap (default 2G)
Anything else you pass is handed to the server unchanged, so the server's own
usage message lists every flag it accepts.

Offline mode is the default, because that is what the project's own live server
runs. Do not expose an offline-mode server to the internet: anyone can then
connect under any name. Pass --online to authenticate instead.

The zip embeds no third-party binaries. The JDK and Mojang's jar are downloaded
on your machine and never redistributed here, which is why the first run needs
network access.

WHERE THINGS LIVE
  classes/             Kestrel's compiled classes
  runtime/jdk/         the pinned Temurin 25 JDK, when one was downloaded
  runtime/vinner/      the extracted vanilla server jar and its 39 libraries
  runtime/packdump/    the vanilla datapack
  runtime/play/world/  your world, created on first start
  runtime/cp.txt       the generated classpath the launcher reads
  BUILD-COMMIT.txt     the main commit this zip was built from, which the
                       server prints in its startup banner
runtime/ is safe to delete; the next start rebuilds it.

SAVING
Saving is on. Worlds are written in Kestrel's own format (KEX4), and vanilla
format compatibility is still partial: a world saved here is not yet
interchangeable with a vanilla server's. Pass --no-save for a disposable world,
which is what the project's own live server does.

THE SERVER IS EARLY, SO EXPECT THIS
- It spawns no mobs, so no mob spawns, no mob AI in play, and no mob drops.
- Random ticks, crops, grass and ice do diverge from vanilla while mob spawning
  is unmodelled. The project is making that divergence loud, not silent.
- Chunks never unload yet, so a long exploration walk grows the heap until it
  dies. Raise KESTREL_MEMORY or restart.
