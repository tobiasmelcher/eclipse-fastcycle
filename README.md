# Fast develop–build–test cycle for eclipse.platform (pure Java, all OSes)

Verifying pull requests such as
[eclipse.platform#3004](https://github.com/eclipse-platform/eclipse.platform/pull/3004):
full CI takes ~58 min. This kit brings the agent's edit–test loop down to
~20–40 s with pure-Java tooling that runs unchanged on Windows, macOS and
Linux. No PowerShell, no Python — only a JDK 25 `java` binary is needed to
drive it.

Developed and verified on Windows 11 against branch `pr-3004`
(commit `20e5e87`, "Add dedicated overview-ruler annotation types for unified diff").

## Policy for agents: always go Maven-free when applicable

Use the Maven-free cycle (`CompileBundle` + `RunTestFork`) for every
edit–test iteration. Use Maven (`FastTest`) only to bootstrap the workspace
once, after target-platform changes, or when the Maven-free path does not
apply (see checklist below).

The Maven-free path is applicable when **all** of these hold:

1. `<test-module>/target/work/configuration/config.ini` exists (a previous
   Maven run materialized the test runtime).
2. The change is in the test bundle or in a bundle wired into that runtime
   (e.g. `org.eclipse.compare` — anything in the `-am` closure listed below).
3. The target platform did not change since that Maven run.

## Setup (once per machine)

1. **JDK 25 is required** (whatever the OS). The kit runs on the JVM it is
   started with and refuses to run on anything else — so start it with the
   `java` binary of a Temurin 25 installation
   (https://adoptium.net). If none is available, agents must ask the user
   for the location or for permission to install one instead of guessing.
2. Maven 3.9.x is needed only for bootstrapping (`FastTest` uses
   `$env:MAVEN_HOME`, else `~/tools/apache-maven-3.9.12`, else `mvn` on `PATH`).
3. Repository location defaults to `~/git/eclipse.platform`
   (`--repo` argument or `$env:ECLIPSE_REPO_ROOT` override it); the Maven
   cache defaults to `~/.m2/repository` (`$env:M2_REPO` override).
4. Build the kit jar once (needs ECJ from the Maven cache; forward slashes
   work on all OSes including Windows):
   ```
   javac -cp <m2>/org/eclipse/jdt/ecj/3.46.0/ecj-3.46.0.jar -d lib/classes src/Kit.java src/CompileBundle.java src/RunTestFork.java src/FastTest.java
   jar cf lib/fastcycle.jar -C lib/classes .
   cp <m2>/org/eclipse/jdt/ecj/3.46.0/ecj-3.46.0.jar lib/
   ```
   (`lib/` already ships built; rebuild only after editing `src/`.)
5. Run `FastTest` once to warm the cache and materialize `target/work`.

## Repository contents: where the jars come from

`lib/*.jar` files are **build artifacts, never committed** (see `.gitignore`).
Another agent setting the kit up only needs `src/`, the docs, and these two
jars, obtained as follows:

- `ecj-<ver>.jar` — the Eclipse batch compiler. It arrives automatically in
  the local Maven cache (`~/.m2/repository/org/eclipse/jdt/ecj/<ver>/`) with
  the first Maven/Tycho build, since Tycho itself depends on it; the version
  should match the one Tycho resolved (`CompileBundle` prints a WARNING when
  the `lib/` copy differs from the cache). Without any Maven cache it can be
  fetched directly from Maven Central (`org.eclipse.jdt:ecj`).
- `fastcycle.jar` — built from `src/*.java` with the commands in step 4
  above. Rebuilding is required after any `src/` change; no other step is
  needed (`lib/classes/` is intermediate output, also gitignored).

Launch pattern everywhere (quoted wildcard works on all OSes, no
per-OS quoting quirks):
```
java -cp "<kit>/lib/*" <Main> [args]
```

## Usage (the daily loop)

```
# 1. Edit sources (test bundle or upstream bundle, e.g. org.eclipse.compare)
# 2. Recompile. Add --update-jar for non-test bundles (their packaged
#    target/*.jar is what the test runtime loads; the test bundle itself
#    runs from target/classes and needs no jar refresh):
java -cp "<kit>/lib/*" CompileBundle --module team/bundles/org.eclipse.compare --update-jar
java -cp "<kit>/lib/*" CompileBundle --module team/tests/org.eclipse.team.tests.core
# 3. Run the test:
java -cp "<kit>/lib/*" RunTestFork [--test <ClassName>]
```

`RunTestFork` defaults to `UnifiedDiffManagerTest`. `CompileBundle` reads the
release level from the bundle's `MANIFEST.MF` (`JavaSE-21` vs `JavaSE-25`)
and source folders from its `build.properties`, so no per-bundle flags are
needed. It warns when the Maven cache holds a newer ECJ than the `lib/` copy.

## How it works

- `Kit.java` — shared helpers: CLI/env parsing, `~/.m2` artifact resolution
  (newest release wins; p2 qualifier suffixes handled), Equinox os/ws/arch
  mapping for the current OS, recursive delete.
- `CompileBundle.java` — rebuilds the compile classpath from the
  materialized runtime (`config.ini`: `osgi.bundles` ≈ 130 jars, exploded
  bundle dirs included, source jars excluded, plus the separately listed
  Equinox framework jar) and invokes ECJ **in-process**, because ~200
  classpath entries exceed the Windows 32k process-creation limit.
- `RunTestFork.java` — clears `target/work/data` + `surefire-reports`,
  resolves the simple test name to its fully qualified class from the
  compiled classes, writes it to `target/surefire.properties`
  (`__provider.tc.0=...`), and launches the exact Equinox fork command
  captured from a Maven `-X` run via `ProcessBuilder` (argument lists, so no
  shell quoting pitfalls on any OS):
  `-application org.eclipse.tycho.surefire.osgibooter.uitest`.
- `FastTest.java` — the Maven fallback (`verify -pl
  ':org.eclipse.team.tests.core,:org.eclipse.core.tests.filesystem.feature'
  -am -Dtest=...`); the extra feature module is needed because `-am` cannot
  see p2-only dependencies.

## Measured performance (`UnifiedDiffManagerTest`, 32 tests, warm cache)

| Cycle | Time | Breakdown |
|---|---|---|
| Full CI build | ~58 min | GitHub check on the PR |
| `FastTest` (Maven) | ~61–77 s | ~45 s reactor (24 modules) + ~24 s workbench fork |
| Maven-free, test-bundle edit | ~17–38 s | ~2–3 s ECJ + ~15–35 s fork (workbench startup varies) |
| Maven-free, production edit (`org.eclipse.compare`) | ~25 s | ~8 s ECJ (175 files) + jar refresh + ~17 s fork |

Do not use Maven offline `-o`: it triggers a silent multi-minute stall in
`tycho-ds:declarative-services`. ECJ was already the compiler (Tycho uses the
JDT batch compiler, incremental) — the savings come from skipping Maven/Tycho
orchestration, not from a different compiler.

## Coverage: which changes are picked up

The `-am` closure (and hence the fork runtime) covers 24 projects: the
compare bundles + tests, team bundles + tests, `core.resources`,
`core.filesystem` (+ fragments), `core.runtime`, `core.jobs`,
`core.contenttype`, `core.expressions`, `core.variables`, `ant.core`, test
harness, resources tests + filesystem test feature. Changes anywhere else in
the repo (e.g. `debug/`, `ua/`, `terminal/`) resolve to old I-build binaries
and are silently ignored — extend the reactor or run a broader build for those.

## Gotchas baked into the kit

- `ProcessBuilder` argument lists are used everywhere: no shell quoting
  pitfalls on any OS (an earlier PowerShell version of this kit lost dotted
  `-Dfoo.bar=baz` arguments to shell splitting — impossible by construction now).
- Tycho's no-tests flag is `-DfailIfNoTests=false`
  (`surefire.failIfNoSpecifiedTests` is ignored by Tycho 5.0.4).
- `verify`, not `test`: the OSGi fork binds to `integration-test`.
- UI-harness tests need a display (fine on Windows/macOS; headless Linux
  needs Xvfb/VNC).

For applying this to other Eclipse projects, see `STRATEGY.md`.
