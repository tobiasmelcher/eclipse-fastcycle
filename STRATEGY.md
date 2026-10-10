# Strategy: Maven-free edit–compile–test cycle for other Eclipse plugins

Generalizes the approach proven in this folder (`README.md`) so it can be
re-applied to any Eclipse/OSGi project built with Maven + Tycho. Input from
the user for a new project: **which Eclipse plugin projects** (bundle +
test bundle directories) and **which target platform** (target file / p2 repos).

## 0. Goal and idea

Keep Maven for what only Maven can do (one-time: resolve the target platform,
build workspace bundles, materialize the OSGi test runtime under
`<test-bundle>/target/work`), then bypass it for the inner loop:

- **Compile** with standalone ECJ, classpath rebuilt from the materialized
  runtime — no reactor, no lifecycle.
- **Test** by replaying Tycho's forked Equinox launch command directly.

Steady state here: ~17 s (test edit) / ~25 s (production edit) vs ~77 s
scoped Maven vs ~58 min full build.

## 1. Prerequisites per machine (one time)

1. JDK matching the build (auto-detected; if missing, ask the user — see README Setup) and Maven
   (here: 3.9.12 binary zip — there is no official cross-OS package).
2. The kit runs on the JVM it is started with and requires JDK 25
   (`Kit.requireJdk25` fails fast otherwise); agent shells may start with a
   minimal `PATH`, so always launch with the JDK's explicit `java` path.
3. Warm `~/.m2`: one successful scoped build (expect target-platform
   download hiccups like `Connection reset` — retry *without* `clean`).

## 2. Establish the scoped Maven baseline first

From the repo root, converge on a command of this shape and verify green
(shell-neutral; quote dotted `-D` args on shells that split them):

```
mvn verify -pl ':<test-bundle>,:<extra-feature>' -am \
  '-Dtest=<TestClass>' '-DfailIfNoTests=false' '-Dmaven.javadoc.skip=true' --batch-mode
```

Notes that transfer to other projects:

- `-pl` needs the test bundle **plus any test-feature modules** it requires:
  `-am` follows Maven dependencies only, not p2-only requirements
  (e.g. `extraRequirements` of type `eclipse-feature` in a pom). Symptom:
  `requires 'osgi.bundle; <x> 0.0.0' but it could not be found`.
- Standalone `mvn verify` inside a test-bundle directory fails the same way
  — always drive test bundles from the root reactor with `-am`.
- Tycho's no-tests flag is `-DfailIfNoTests=false`
  (`surefire.failIfNoSpecifiedTests` is ignored by Tycho 5.x).
- `verify`, not `test`: `tycho-surefire:test` binds to `integration-test`.
- Quote every dotted `-D` argument (`'-Dfoo.bar=baz'`) — unquoted dots get
  split into separate process arguments in this shell environment, producing
  bogus `Unknown lifecycle phase` errors.
- Never use offline `-o` blindly here: it caused a silent multi-minute stall
  in `tycho-ds:declarative-services`. Stay online once the cache is warm.

## 3. Capture the fork command (one Maven `-X` run)

Re-run the green scoped command with `'-X'` appended and extract:

1. The line starting `[INFO] Command line:` — the complete
   `java ... -jar <equinox.launcher.jar> -data <work>/data -install <work>
   -configuration <work>/configuration -application <booter-app>
   -testproperties <module>/target/surefire.properties ...` invocation.
   For UI tests the app is
   `org.eclipse.tycho.surefire.osgibooter.uitest`; headless bundles use the
   `headlesstest` variant — take whatever the log shows.
2. `<module>/target/work/configuration/config.ini` — the full runtime bundle
   closure (`osgi.bundles`, one huge line) plus `osgi.framework`.
3. `<module>/target/work/dev.properties` — usually maps only the test bundle
   to `target/classes` (+ `target/test-classes`); upstream bundles are wired
   as packaged `target/*.jar` files.
4. `<module>/target/surefire.properties` — test selection key
   (`__provider.tc.0=<fully-qualified-class>`), reports dir, provider.

## 4. Build the Maven-free compile step

- Reuse ECJ from the Maven cache (`~/.m2/repository/org/eclipse/jdt/ecj/...`;
  take the version Tycho itself resolved) via a small in-process runner
  (`CompileBundle` + ECJ `Main.compile`): ~200-entry classpaths exceed the
  Windows 32k process-creation limit, so no child-process compiler call.
- Assemble the classpath from `config.ini`: unescape `\:` → `:`, strip
  `@<level>:start` suffixes, drop source jars (`.source`, `-sources`,
  `source_`), **keep exploded bundle directories** (existence check) and add
  the `osgi.framework` jar (it is *not* part of `osgi.bundles` — missing it
  yields `BundleContext cannot be resolved` single-error failures).
- Per-bundle settings, read from the bundle itself, not hardcoded:
  release level from `MANIFEST.MF` (`Bundle-RequiredExecutionEnvironment:
  JavaSE-XX`), source folders from `build.properties` (`source.. = ...`,
  often *not* `src/`), output always `target/classes` (Tycho convention,
  regardless of `output.. = bin/`).
- Validate with a temporary `System.out.println` probe in both a test class
  and a production class; the probe text in the fork output proves the
  recompiled code actually ran. Revert afterwards and recompile to restore.

## 5. Build the Maven-free test step

Replay the captured command verbatim, with this per-run preparation
(mirrors Tycho defaults):

1. Delete `<module>/target/work/data` (fresh workspace) and
   `target/surefire-reports`; recreate any dirs Tycho flags reference
   (e.g. `target/screenshots`).
2. Resolve the requested simple test name to its FQN by scanning
   `target/classes` + `target/test-classes` for `<Name>.class`, and rewrite
   `__provider.tc.0=` in `surefire.properties` (a simple name alone yields
   `ClassNotFoundException` from the booter).
3. Launch via `ProcessBuilder`-style argument lists (never a shell string):
   dotted `-D`/`-Dosgi` args survive untouched on every OS. Map os/ws/arch
   from the runtime (`win32/win32`, `macosx/cocoa`, `linux/gtk`; `x86_64` vs
   `aarch64`). Fork on the same JVM that runs the kit (it must be the build
   JDK — fail fast otherwise).

## 6. Cover production-code edits

Upstream bundles run from their packaged `target/*.jar`, so after ECJ also
refresh the jar (`jar uf <bundle>-*.jar -C target/classes .`, excluding
`*-sources.jar`). Validate with a probe in production code before trusting it.

## 7. When Maven is still required

- First run on a machine / new workspace: materialize `target/work` + jars.
- Target-platform changes (new `.target`, new I-build): re-resolve via Maven.
- New bundles entering the dependency closure, new test features, Tycho
  upgrades (re-extract the `Command line:` block and ECJ version).
- Anything outside the `-am` closure is silently tested against old I-build
  binaries — extend `-pl` or go broader; the Maven-free path cannot fix that.

## 8. Portability checklist for the new repo

- Parameterize: repo root, Maven repo location, module dirs (done via
  CLI args/env in `Kit.java` — no per-machine files); resolve tool jars
  (ECJ, launcher) from the local Maven cache instead of pinning versions.
- The kit assumes one test bundle's layout (`src` vs custom source dirs are
  handled; `pom.model.*` packaging quirks per bundle are not).
- Keep the Maven path (`FastTest`) as the always-works fallback next to the
  speed path.
- Build once per checkout: `javac` + `jar` into `lib/fastcycle.jar`, ECJ copy
  into `lib/`; launch is a single OS-neutral `java -cp "<kit>/lib/*"` line.
