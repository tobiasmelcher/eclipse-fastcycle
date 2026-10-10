import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers for the Maven-free fast-cycle kit. */
final class Kit {

	static final boolean IS_WINDOWS = System.getProperty("os.name", "").startsWith("Windows"); //$NON-NLS-1$ //$NON-NLS-2$

	/** Project default, overridable via --module. */
	static final String DEFAULT_TEST_MODULE = "team/tests/org.eclipse.team.tests.core"; //$NON-NLS-1$

	private Kit() {
	}

	/** Parses --key value and --flag style arguments. */
	static Map<String, String> parseArgs(String[] args) {
		Map<String, String> map = new HashMap<>();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.equals("-t")) { //$NON-NLS-1$
				arg = "--test"; //$NON-NLS-1$
			} else if (arg.equals("-m")) { //$NON-NLS-1$
				arg = "--module"; //$NON-NLS-1$
			}
			if (arg.startsWith("--")) { //$NON-NLS-1$
				String key = arg.substring(2);
				if (i + 1 < args.length && !args[i + 1].startsWith("--")) { //$NON-NLS-1$
					map.put(key, args[++i]);
				} else {
					map.put(key, "true"); //$NON-NLS-1$
				}
			}
		}
		return map;
	}

	static Path repoRoot(Map<String, String> args) {
		String env = System.getenv("ECLIPSE_REPO_ROOT"); //$NON-NLS-1$
		if (args.containsKey("repo")) { //$NON-NLS-1$
			return Paths.get(args.get("repo")); //$NON-NLS-1$
		}
		if (env != null && !env.isBlank()) {
			return Paths.get(env);
		}
		return Paths.get(System.getProperty("user.home"), "git/eclipse.platform"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	static Path m2Repo() {
		String env = System.getenv("M2_REPO"); //$NON-NLS-1$
		if (env != null && !env.isBlank()) {
			return Paths.get(env);
		}
		return Paths.get(System.getProperty("user.home"), ".m2/repository"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** The kit must run on JDK 25: the forked test JVM is this very JVM. */
	static void requireJdk25() {
		String spec = System.getProperty("java.specification.version", ""); //$NON-NLS-1$ //$NON-NLS-2$
		if (!spec.equals("25")) { //$NON-NLS-1$
			throw new IllegalStateException("This kit must run on JDK 25 (running on " + spec //$NON-NLS-1$
					+ "). Start it with the java binary of a Temurin 25 installation."); //$NON-NLS-1$
		}
	}

	static String javaExe() {
		return exe(Paths.get(System.getProperty("java.home"), "bin", "java").toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	static String jarExe() {
		return exe(Paths.get(System.getProperty("java.home"), "bin", "jar").toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static String exe(String base) {
		return IS_WINDOWS ? base + ".exe" : base; //$NON-NLS-1$
	}

	/**
	 * Newest release jar under &lt;m2&gt;/&lt;groupPath&gt;/*, snapshots only as
	 * fallback. Compares the numeric version prefix so p2 qualifier suffixes
	 * (e.g. 1.8.0.v20260804-1928) do not break resolution.
	 */
	static Path findBestArtifact(Path m2, String groupPath, String glob) {
		Path base = m2.resolve(groupPath);
		Path best = null;
		int[] bestVer = null;
		boolean bestSnap = true;
		if (Files.isDirectory(base)) {
			try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base)) {
				for (Path dir : dirs) {
					if (!Files.isDirectory(dir)) {
						continue;
					}
					String name = dir.getFileName().toString();
					boolean snap = name.contains("SNAPSHOT"); //$NON-NLS-1$
					int[] ver = numericPrefix(name);
					if (ver == null) {
						continue;
					}
					Path jar = firstGlob(dir, glob);
					if (jar == null) {
						continue;
					}
					if (best == null || (!snap && bestSnap) || (snap == bestSnap && compare(ver, bestVer) > 0)) {
						best = jar;
						bestVer = ver;
						bestSnap = snap;
					}
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		if (best == null) {
			throw new IllegalStateException("Artifact not found: " + groupPath + "/" + glob //$NON-NLS-1$ //$NON-NLS-2$
					+ " (is the Maven cache warm?)"); //$NON-NLS-1$
		}
		return best;
	}

	private static int[] numericPrefix(String name) {
		Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)*)").matcher(name); //$NON-NLS-1$
		if (!m.find()) {
			return null;
		}
		String[] parts = m.group(1).split("\\."); //$NON-NLS-1$
		int[] ver = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			ver[i] = Integer.parseInt(parts[i]);
		}
		return ver;
	}

	private static int compare(int[] a, int[] b) {
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int x = i < a.length ? a[i] : 0;
			int y = i < b.length ? b[i] : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		return 0;
	}

	private static Path firstGlob(Path dir, String glob) {
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, glob)) {
			for (Path file : files) {
				return file;
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return null;
	}

	/** Maps the current OS to Equinox os/ws/arch values for the test fork. */
	static String[] osgiPlatform() {
		String os = System.getProperty("os.name", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
		String ws, osgiOs;
		if (os.startsWith("win")) { //$NON-NLS-1$
			osgiOs = "win32"; //$NON-NLS-1$
			ws = "win32"; //$NON-NLS-1$
		} else if (os.startsWith("mac")) { //$NON-NLS-1$
			osgiOs = "macosx"; //$NON-NLS-1$
			ws = "cocoa"; //$NON-NLS-1$
		} else {
			osgiOs = "linux"; //$NON-NLS-1$
			ws = "gtk"; //$NON-NLS-1$
		}
		String machine = System.getProperty("os.arch", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
		String arch = (machine.contains("aarch64") || machine.contains("arm64")) ? "aarch64" : "x86_64"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		return new String[] { osgiOs, ws, arch };
	}

	static void deleteTree(Path path) {
		if (!Files.exists(path)) {
			return;
		}
		try {
			Files.walk(path).sorted(java.util.Comparator.reverseOrder())
					.forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Directory holding the running fastcycle.jar (or its classes in dev). */
	static Path kitLibDir() {
		try {
			Path loc = Paths.get(Kit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			Path parent = loc.getParent();
			if (parent == null) {
				throw new IllegalStateException("Cannot locate kit directory"); //$NON-NLS-1$
			}
			return parent;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Warns when the Maven cache holds a newer ECJ than the lib/ copy. */
	static void warnIfEcjStale() {
		try {
			Path best = findBestArtifact(m2Repo(), "org/eclipse/jdt/ecj", "ecj-*.jar"); //$NON-NLS-1$ //$NON-NLS-2$
			try (DirectoryStream<Path> jars = Files.newDirectoryStream(kitLibDir(), "ecj-*.jar")) { //$NON-NLS-1$
				for (Path pinned : jars) {
					if (!pinned.getFileName().equals(best.getFileName())) {
						System.out.println("WARNING: lib/ holds " + pinned.getFileName() //$NON-NLS-1$
								+ " but the Maven cache has " + best.getFileName() //$NON-NLS-1$
								+ " — copy it into lib/ to refresh the compiler."); //$NON-NLS-1$
					}
					return;
				}
			}
		} catch (Exception e) {
			// Best effort only; never break the build over it.
		}
	}
}
