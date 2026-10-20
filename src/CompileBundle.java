import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jdt.internal.compiler.batch.Main;

/**
 * Maven-free ECJ recompile of one repo bundle, with optional refresh of its
 * packaged jar (needed for non-test bundles: the test runtime references
 * upstream bundles as target/*.jar, while the test bundle itself runs from
 * target/classes via dev.properties).
 *
 * Usage: CompileBundle [--repo &lt;path&gt;] [--module team/...] [--update-jar]
 */
public class CompileBundle {

	public static void main(String[] args) throws Exception {
		Kit.requireJdk25();
		Kit.warnIfEcjStale();
		Map<String, String> parsed = Kit.parseArgs(args);
		Path repo = Kit.repoRoot(parsed);
		String module = parsed.getOrDefault("module", Kit.DEFAULT_TEST_MODULE); //$NON-NLS-1$
		Path mod = repo.resolve(module);

		String manifest = Files.readString(mod.resolve("META-INF/MANIFEST.MF")); //$NON-NLS-1$
		Matcher bree = Pattern.compile("JavaSE-(\\d+)").matcher(manifest); //$NON-NLS-1$
		String release = bree.find() ? bree.group(1) : "25"; //$NON-NLS-1$

		List<String> srcDirs = new ArrayList<>();
		for (String line : Files.readAllLines(mod.resolve("build.properties"))) { //$NON-NLS-1$
			Matcher src = Pattern.compile("^source\\.\\.\\s*=\\s*(.*)$").matcher(line); //$NON-NLS-1$
			if (src.matches()) {
				for (String part : src.group(1).split(",")) { //$NON-NLS-1$
					String dir = part.strip().replaceAll("[/\\\\]+$", ""); //$NON-NLS-1$ //$NON-NLS-2$
					if (!dir.isEmpty()) {
						srcDirs.add(dir);
					}
				}
			}
		}
		if (srcDirs.isEmpty()) {
			System.err.println("ERROR: no source entry in build.properties"); //$NON-NLS-1$
			System.exit(1);
		}

		Path testwork = repo.resolve(Kit.DEFAULT_TEST_MODULE).resolve("target/work/configuration/config.ini"); //$NON-NLS-1$
		List<String> cp = compileClasspath(testwork);
		Path out = mod.resolve("target/classes"); //$NON-NLS-1$
		cp.add(out.toString());

		List<String> sources = new ArrayList<>();
		for (String src : srcDirs) {
			try (Stream<Path> walk = Files.walk(mod.resolve(src))) {
				walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toString())); //$NON-NLS-1$
			}
		}

		List<String> argv = new ArrayList<>();
		argv.add("-source"); //$NON-NLS-1$
		argv.add(release);
		argv.add("-target"); //$NON-NLS-1$
		argv.add(release);
		argv.add("-encoding"); //$NON-NLS-1$
		argv.add("UTF-8"); //$NON-NLS-1$
		argv.add("-cp"); //$NON-NLS-1$
		argv.add(String.join(java.io.File.pathSeparator, cp));
		argv.add("-d"); //$NON-NLS-1$
		argv.add(out.toString());
		argv.addAll(sources);

		System.out.println("Compiling " + module + " (release " + release + ", " + sources.size() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				+ " files, " + cp.size() + " cp entries)"); //$NON-NLS-1$ //$NON-NLS-2$
		boolean ok = new Main(new PrintWriter(System.out), new PrintWriter(System.err), false, null, null)
				.compile(argv.toArray(new String[0]));
		System.out.println(ok ? "COMPILE OK" : "COMPILE FAILED"); //$NON-NLS-1$ //$NON-NLS-2$
		if (!ok) {
			System.exit(1);
		}

		if (parsed.containsKey("update-jar")) { //$NON-NLS-1$
			String bsn = mod.getFileName().toString();
			Path target = mod.resolve("target"); //$NON-NLS-1$
			Path jar = null;
			String runtime = Files.readString(testwork).replace("\\:", ":").replace('\\', '/'); //$NON-NLS-1$ //$NON-NLS-2$
			try (var stream = Files.newDirectoryStream(target, bsn + "-*.jar")) { //$NON-NLS-1$
				for (Path candidate : stream) {
					String name = candidate.getFileName().toString();
					String location = candidate.toAbsolutePath().toString().replace('\\', '/');
					if (!name.contains("sources") && !name.contains("javadoc") && runtime.contains(location)) { //$NON-NLS-1$ //$NON-NLS-2$
						jar = candidate;
						break;
					}
				}
			}
			if (jar == null) {
				System.err.println("ERROR: packaged bundle jar is not part of the test runtime"); //$NON-NLS-1$
				System.exit(1);
			}
			System.out.println("Updating jar: " + jar); //$NON-NLS-1$
			int rc = new ProcessBuilder(Kit.jarExe(), "uf", jar.toString(), //$NON-NLS-1$
					"-C", out.toString(), ".").inheritIO().start().waitFor(); //$NON-NLS-1$ //$NON-NLS-2$
			System.exit(rc);
		}
	}

	/**
	 * Classpath from a Tycho-materialized test runtime config.ini: every
	 * osgi.bundles entry plus the separately listed Equinox framework jar,
	 * minus source bundles.
	 */
	static List<String> compileClasspath(Path configIni) throws Exception {
		String ini = Files.readString(configIni);
		List<String> cp = new ArrayList<>();
		for (String line : ini.split("\n")) { //$NON-NLS-1$
			if (line.startsWith("osgi.bundles=")) { //$NON-NLS-1$
				String bundles = line.substring("osgi.bundles=".length()); //$NON-NLS-1$
				// Split on commas that are not part of the \: escape sequence.
				for (String entry : bundles.split("(?<!\\\\),")) { //$NON-NLS-1$
					addLocation(cp, entry.trim());
				}
			} else if (line.startsWith("osgi.framework=")) { //$NON-NLS-1$
				addLocation(cp, line.substring("osgi.framework=".length()).trim()); //$NON-NLS-1$
			}
		}
		return cp;
	}

	private static void addLocation(List<String> cp, String entry) {
		if (entry.startsWith("reference\\:file\\:")) { //$NON-NLS-1$
			entry = entry.substring("reference\\:file\\:".length()); //$NON-NLS-1$
		} else if (entry.startsWith("file\\:")) { //$NON-NLS-1$
			entry = entry.substring("file\\:".length()); //$NON-NLS-1$
		} else {
			return;
		}
		int at = entry.indexOf('@');
		if (at >= 0) {
			entry = entry.substring(0, at); // strip @<startlevel>:start
		}
		entry = entry.replace("\\:", ":"); //$NON-NLS-1$ //$NON-NLS-2$
		Path path = Path.of(entry);
		boolean isDir = Files.isDirectory(path);
		String lower = entry.toLowerCase();
		if (!isDir) {
			if (!lower.endsWith(".jar")) { //$NON-NLS-1$
				return;
			}
			if (lower.contains(".source") || lower.contains("-sources") || lower.contains("source_")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				return; // source bundles carry no classes
			}
		}
		cp.add(entry);
	}
}
