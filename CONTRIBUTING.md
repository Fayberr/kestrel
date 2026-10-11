# Contributing to Kestrel

Thanks for wanting to help. Kestrel is a Minecraft Java server (26.x) that aims
to play exactly like vanilla while using much less memory and running technical
worlds faster. It's a small project, so the rules below are short.

## Sending changes

Open a pull request whenever you're ready. You don't have to open an issue
first, but you're welcome to if you'd like to talk an idea through, report a
bug, or check that a change fits before you build it.

Your PR description must include these two lines, exactly as written:

    AI use: none
    Checks run: <the checks you ran and their results>

For `AI use:` write `none`, or name the tool and what it did (see the AI section
below). A PR without both lines is labelled `needs-guidelines` and will not be
reviewed until they are added.

- Keep each PR to one logical change. Small PRs get reviewed fastest.
- Say what changed and why.
- The maintainer has the final say on what gets merged. A change that doesn't
  fit the project's goals may be declined, even if it works.

## The main rule: match vanilla

Kestrel's goal is parity with vanilla, quirks included.

- A change must not alter vanilla-observable behavior unless it sits behind an
  opt-in flag that defaults to off.
- A fast path has to prove it gives identical results. Add or extend a check
  that compares against vanilla, or against fixtures captured from a real
  vanilla 26.2 server (see `harness/corpus/`). "It looks the same in play" is
  not enough.
- Don't "fix" vanilla bugs. Quirks are features here.

## Setting up and testing

You need JDK 25. This downloads Mojang's pinned 26.2 server jar and verifies
its hash:

```bash
scripts/setup-env.sh
cd core && ./gradlew compileJava \
  -PvanillaJar=$HOME/kestrel-run/vinner/META-INF/versions/26.2/server-26.2.jar \
  -PvanillaLibs=$HOME/kestrel-run/vinner
```

Correctness is enforced by `checkRepro`. Run the default set, plus any check
that covers what you touched:

```bash
cd core && ./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=...
cd core && ./gradlew checkRepro -PvanillaJar=... -PvanillaLibs=... \
  -Pcheck=FluidParityCheck,RedstoneParityCheck
```

- All checks must pass before a PR is ready.
- New behavior needs a new check. Register it in `checkClassMap` in
  `core/build.gradle`. An unregistered name fails the build on purpose.
- If you change what a mechanic can do, update its row in
  `docs/CAPABILITIES.md`.

## AI-assisted contributions

Using AI tools (Claude, Copilot, Cursor, ChatGPT and the like) is fine. Plenty
of good work is written that way. Declaring it is required.

**Declaration (required).** The `AI use:` line in your PR description must say
whether AI tools were used. Write `AI use: none` if you didn't use any. If you
did, say which tool and what it did, for example `AI use: Claude, drafted the
code and wrote the tests` or `AI use: autocomplete only`. A PR with no
declaration will not be reviewed, and a PR found to have undeclared AI use will
be closed. Declaring AI use never counts against you.

Beyond that:

- **You are the author.** Whatever a tool wrote, you own it. Read it, run it,
  and understand it well enough to answer review questions about it.
- **Test it for real.** Generated code that compiles but has never been run
  against the checks will be closed. Tools are good at code that looks right and
  isn't, and parity bugs are exactly that.
- **No bulk or drive-by PRs.** Large automated sweeps, mass refactors and
  untested "improvements" from an agent won't be reviewed.
- **Watch for Mojang code.** Models sometimes reproduce decompiled Minecraft
  source from memory. Don't let that into the repo (see below).

## What not to submit

- Mojang's jar, classes, assets, mappings or world data, and no pasted
  decompiled code. Describe vanilla behavior in your own words and refer to the
  class and method by name. Call vanilla's classes at runtime from the
  user-supplied jar, as the project already does.
- Code you didn't write or don't have the right to share. If you bring in
  third-party code under a compatible licence, keep its notice and add it to
  `NOTICE`.
- Personal info, local paths, host names or credentials.

## Style

- Match the surrounding code. Don't reformat or rename things you aren't
  changing.
- Comments explain why, especially where a shortcut would break vanilla parity.
  Skip comments that restate the code.
- Short, clear commit messages. A prefix like `docs:` or `ci:` is nice.

## Bugs and security

- Bugs: open an issue with the version, steps to reproduce, and what vanilla
  does.
- Security problems: don't use a public issue. Use the Security tab, which has
  private vulnerability reporting.

## Licence

Kestrel is GPL-3.0-only (see `LICENSE`). Contributions are accepted under that
licence. Some details:

- You keep the copyright in your work.
- By submitting a PR you also give the project owner (Fayber) the right to
  relicense your contribution along with the rest of the project, which may
  include non-open-source terms. Already-released versions stay available under
  GPL-3.0.
- Forks and redistributed builds should use a different name.
