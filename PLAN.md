# PLAN — Lattice Phase 0 (Host Preparation)

**Audience:** Claude Sonnet, the implementing instance. Read this top to bottom before touching anything.
**Status:** Pre-implementation. No code, no build system, no git history yet.
**Your job this session:** Start Phase 0. Set up GitHub, scaffold the repo, and stand up the
host-preparation build pipeline as far as you can get. **Commit early and often. Write a handoff
file before you run low on context.** You will almost certainly not *finish* Phase 0 in one
session — that is expected. Make durable, committed progress and hand off cleanly.

---

## 0. Orient first (do this before writing anything)

1. **Read `lattice-design.md` in full.** It is the authoritative spec (~38KB). On any conflict
   between this plan, `CLAUDE.md`, and the design doc, **the design doc wins.**
2. **Read `CLAUDE.md`** (both the repo one and `/home/Kelime/CLAUDE.md`) — system info, tools,
   working norms.
3. This plan covers **Phase 0 only** (design §5, "Host preparation"). Later phases (1–6) are
   summarized in `CLAUDE.md`; do not start them. The roadmap is deliberately ordered:
   correctness and the determinism harness come *before* any parallelism. Phase 0 has **no
   threading** in it at all — it is purely about producing a buildable, deobfuscated vanilla
   server you control the source of.

### Phase 0 goal & exit criterion (design §5)

> Stand up a Paperweight-*style* pipeline on Mojang's mappings: **decompile → remap → patch →
> recompile** into a buildable deobfuscated server you control. No threading.
> **Exit:** a vanilla-parity server you can patch and rebuild.

---

## 1. Environment facts (already checked — don't re-discover from scratch, but DO verify versions)

- **OS:** Arch Linux + Hyprland (Wayland). Package manager `pacman` (and likely an AUR helper).
- **JDK on PATH:** OpenJDK **26.0.1**. ⚠️ See the JDK section below — **do not assume this is the
  JDK that builds the project.**
- **git:** installed and configured as `Mainul Fahad <mfahad70@stuy.edu>`.
  ⚠️ Note: the Claude Code account email is `kelime2027@gmail.com` — a *different* identity.
  **Before creating any GitHub repo, confirm with the user which GitHub account/identity the repo
  should live under.** Don't silently create it under whatever happens to be authenticated.
- **`gh` (GitHub CLI):** **NOT installed.** You'll install it (step 2).
- **Build tool:** none yet (no gradle/maven wrapper). You will add one.
- **`ruvector.db`** exists in the project root (~1.5MB). Leave it alone; **add it to
  `.gitignore`** — it is local tooling state, not project source.
- Tools available per `CLAUDE.md`: `aria2c` (downloads, `-x 16 -s 16`), `rarx` (RAR), `neovim`
  (editor — never nano).

---

## 2. THE CRITICAL CONSTRAINT — read this twice

**You may NOT commit decompiled Minecraft source code to the repository, and you may NOT push it
to GitHub. This is non-negotiable and it shapes the entire Phase 0 architecture.**

Decompiled Minecraft is Mojang's copyrighted code. This is *the whole reason Paperweight exists*.
The model that makes a public repo legal is **patches, not source**:

- The repo contains **only**: build tooling/scripts, **patch files (diffs)**, Lattice's own new
  source, and the design/docs.
- The actual decompile happens **locally as a build step on each developer's machine**:
  download the vanilla server jar from Mojang → remap with Mojang's official mappings → decompile
  → apply the repo's patches → recompile.
- Mojang's **official mappings** (the published ProGuard `.txt` files) are usable for this. The
  **decompiled output is not redistributable.**

**Concretely, your `.gitignore` must exclude:** vanilla/downloaded jars, the remapped jar, the
decompiled/working source tree, any Mojang mapping downloads, build output, and `ruvector.db`.
If you find yourself about to `git add` a directory full of `net/minecraft/...` Java files, **stop
— that is the mistake this section exists to prevent.**

This patch-not-source model is also exactly what makes a public repo safe. Mirror what Paper does.

---

## 3. JDK reality (don't trip on this)

- `java` on PATH is **26** — far too new for the build tooling.
- **Gradle has a narrow supported-JDK window for launching its daemon, and JDK 26 (released
  ~April 2026) is almost certainly too new for Gradle to *run on*.** Verify Gradle's currently
  supported launch JDK before assuming anything.
- Modern Minecraft (1.21.x) targets **Java 21**. Install it: `sudo pacman -S jdk21-openjdk`
  (confirm exact package name). Use `archlinux-java` to see/manage installed JDKs.
- **Strategy:** run Gradle on a JDK it actually supports (likely 21), and use **Gradle toolchains**
  to compile/run specific code against the JDK that code needs. Do not let `java`=26 on PATH
  silently become the build JDK.
- **The design wants JDK ≥22 for FFM** (the engine, later phases). There's a real tension: the
  vanilla host targets 21, Lattice's own engine code will want ≥22. For **Phase 0**, default to
  whatever the host/decompile toolchain requires (likely 21). Resolve the engine-JDK question when
  Phase 1 code actually needs FFM — don't prematurely pin to 26. **Per `CLAUDE.md`: confirm the
  exact JDK before adding toolchain config**, and record the decision (§5 below).

---

## 4. Choosing the toolchain (research, then pin — don't blindly follow me)

"Paperweight-style" means *the technique*, not literally Paper's `paperweight` plugin (that's
Paper-specific — it's for maintaining patches on top of Paper, not for forking vanilla). For a
**greenfield vanilla fork** the realistic paths are:

- **VanillaGradle** (SpongePowered) — a Gradle plugin that provides Mojang-mapped, decompiled
  Minecraft to a project. Often the cleanest "buildable deobfuscated server" path. *(Recommended
  starting point.)*
- **A Vineflower-based decompile pipeline** against Mojang's published server jar + official
  mappings (DIY remap+decompile). More control, more work.

These tools evolve and **I cannot verify their current state or version support from here.** So:

1. **Research current state first** — use the `context7` MCP tools (resolve-library-id +
   query-docs) and/or web search to check the latest VanillaGradle (or chosen tool), which
   Minecraft + Gradle + JDK versions it currently supports, and current setup steps.
2. **Pick the Minecraft version by what the chosen tool supports best right now** — *not*
   necessarily the absolute latest MC release. A slightly older, well-supported version beats a
   bleeding-edge one the tooling can't handle.
3. **Pin everything and document it** (§5).

---

## 5. Record decisions in `DECISIONS.md` (lightweight ADR)

Create `DECISIONS.md` and append a short, dated entry for every pinned choice, with the *why*:
- Minecraft version pinned (+ why).
- Decompile/remap toolchain chosen (+ why, + version).
- Gradle version + the JDK Gradle launches on.
- JDK(s) installed and which compiles what (toolchain config).
- GitHub account/identity the repo lives under (after confirming with the user).

This is what lets the next instance not re-derive your choices.

---

## 6. Task order (durable win FIRST, risky pipeline SECOND)

Phase-0 host prep is a notorious time sink. **Get the safe, committed scaffold done before the
decompile rabbit hole**, so the session always ends with durable progress.

### Step A — GitHub setup (the quick durable win)

1. Install `gh`: `sudo pacman -S github-cli` (confirm package name).
2. **Confirm with the user** which GitHub account/identity to use (see the identity mismatch note
   in §1).
3. Authenticate. `gh auth login` is **interactive** — you can't drive it. Ask the user to run it
   themselves via the session's `!` prefix, e.g. they type:
   `! gh auth login`
   in the prompt, so its output lands in this session. Wait for them.
4. Decide repo name (`lattice` is the obvious choice) and visibility — **ask the user public vs
   private** before creating.
5. `git init`, then create the repo with `gh repo create`.

### Step B — Scaffold + first commit + push (do this before any decompile work)

1. Write `.gitignore` **first** (per the §2 critical constraint — jars, decompiled tree, mappings,
   build output, `ruvector.db`, IDE files, OS cruft).
2. Add a top-level `README.md` (short: what Lattice is, link to `lattice-design.md`, current phase,
   build status = "Phase 0 in progress").
3. Scaffold the Gradle project (wrapper + `settings.gradle(.kts)` + `build.gradle(.kts)`). Use the
   **Gradle wrapper** pinned to a version that launches on the chosen JDK. Prefer Kotlin DSL unless
   you have a reason not to; record the choice.
4. Lay out a directory structure that anticipates the roadmap without building it yet — e.g. a
   place for the patch set, a place for Lattice's own source. Keep it minimal; don't over-engineer.
5. **Commit** (design docs `lattice-design.md` + `CLAUDE.md` + `PLAN.md` + scaffold + `.gitignore`
   + `DECISIONS.md`) and **push.** Now the session has a durable, public/visible baseline no matter
   what happens next.

> Commit discipline: small, logical commits with clear messages. End every commit message with the
> `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` trailer per repo norms (adjust to your
> own model line if different). Push after meaningful milestones.

### Step C — Stand up the decompile→remap→patch→recompile pipeline (the hard part)

Work incrementally and **commit the tooling as you go** (never the decompiled output):

1. Wire the chosen plugin/pipeline into Gradle (download vanilla server jar, apply Mojang mappings,
   decompile to a *gitignored* working tree).
2. Get it to **recompile** the deobfuscated server cleanly.
3. Get it to **run** — a vanilla-parity server you can launch.
4. Establish the **patch mechanism**: a way to make a source edit to the decompiled tree and
   capture it as a committed *diff* that re-applies on a clean decompile. (This is the
   "you can patch it" half of the exit criterion.)
5. Sanity-check vanilla parity (server boots, generates a world, accepts the scripted/baseline
   behavior you'll later replay-diff against in Phase 1).

If you get blocked (tooling version mismatch, decompile failure, JDK issue) — **document the exact
blocker in the handoff**, commit what works, and stop cleanly. A precise blocker is worth more than
a half-finished guess.

---

## 7. Guardrails (things that will cause real damage if ignored)

- **Never commit decompiled Minecraft source or push it.** (§2. This is the big one.)
- **Don't invent build/test/lint commands.** Only document commands that actually exist and run.
  When the build works, update `CLAUDE.md` with the *real* commands (the repo `CLAUDE.md` explicitly
  asks for this once implementation starts).
- **Don't weaken the determinism contract** (design §1) or make any architecture decision that
  trades it away without surfacing the tradeoff explicitly to the user. (Mostly a later-phase
  concern, but internalize it now.)
- **Design doc wins** on any conflict.
- **Confirm the JDK before pinning toolchain config** (§3, and `CLAUDE.md`).
- **Don't start Phase 1+.** No ECS, no scheduler, no threading this session.
- Don't `git push --force`, don't touch `ruvector.db`, don't commit secrets/tokens.

---

## 8. Write the handoff file before you run out of context (REQUIRED)

**This is mandatory.** Before your context fills up, create/update `HANDOFF.md` at the repo root so
the next instance (Sonnet or otherwise) can pick up cold. It must contain:

1. **Where we are** — current phase, what's done, what's not.
2. **Exact state of the pipeline** — MC version pinned, toolchain chosen + version, Gradle/JDK
   setup, what builds, what runs, what doesn't.
3. **The next concrete action** — the single most useful next step.
4. **Open blockers / decisions pending** — anything you got stuck on, with the exact error if any,
   and anything you need the user to decide.
5. **GitHub state** — repo URL, visibility, account it lives under, what's pushed.
6. **Anything surprising** — gotchas the next instance shouldn't have to rediscover.

Keep `DECISIONS.md` current alongside it. Commit and push the handoff as your last act.

---

## 9. Definition of done for *this session*

You do **not** need to finish Phase 0. This session is successful if:
- [ ] GitHub repo exists (under the confirmed account), scaffold + design docs + `.gitignore`
      committed and pushed.
- [ ] `.gitignore` correctly excludes all decompiled source / jars / mappings / `ruvector.db`.
- [ ] Toolchain + MC version researched, chosen, and recorded in `DECISIONS.md`.
- [ ] As much of the decompile pipeline working as you could get, with tooling committed (never the
      decompiled output).
- [ ] `HANDOFF.md` written, accurate, committed, and pushed.

Good luck. Make it durable, keep it legal (patches not source), and leave a clean handoff.
