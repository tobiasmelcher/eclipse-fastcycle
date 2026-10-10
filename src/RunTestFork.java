import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Maven-free replay of the Tycho test fork for the test bundle.
 * Reuses target/work (config.ini + plugins) materialized by a previous Maven
 * run. Only rerun Maven when the target platform or workspace jars change.
 *
 * Usage: RunTestFork [--repo &lt;path&gt;] [--module team/...] [--test &lt;ClassName&gt;]
 */
public class RunTestFork {

	public static void main(String[] args) throws Exception {
		Kit.requireJdk25();
		Map<String, String> parsed = Kit.parseArgs(args);
		Path repo = Kit.repoRoot(parsed);
		String module = parsed.getOrDefault("module", Kit.DEFAULT_TEST_MODULE); //$NON-NLS-1$
		String test = parsed.getOrDefault("test", "UnifiedDiffManagerTest"); //$NON-NLS-1$ //$NON-NLS-2$
		Path mod = repo.resolve(module);
		Path target = mod.resolve("target"); //$NON-NLS-1$
		Path work = target.resolve("work"); //$NON-NLS-1$

		// Fresh workspace + reports, mirroring Tycho defaults.
		Kit.deleteTree(work.resolve("data")); //$NON-NLS-1$
		Kit.deleteTree(target.resolve("surefire-reports")); //$NON-NLS-1$
		Files.createDirectories(target.resolve("screenshots")); //$NON-NLS-1$

		// Tycho stores the fully qualified class name; resolve it from the
		// compiled classes.
		String fqn = null;
		for (String classesDir : new String[] { "target/classes", "target/test-classes" }) { //$NON-NLS-1$ //$NON-NLS-2$
			Path root = mod.resolve(classesDir);
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (Stream<Path> walk = Files.walk(root)) {
				List<Path> hits = walk
						.filter(p -> p.getFileName().toString().equals(test + ".class")) //$NON-NLS-1$
						.sorted().toList();
				if (!hits.isEmpty()) {
					Path rel = root.relativize(hits.get(0));
					String name = rel.toString();
					name = name.substring(0, name.length() - ".class".length()); //$NON-NLS-1$
					fqn = name.replace('/', '.').replace('\\', '.');
					break;
				}
			}
		}
		if (fqn == null) {
			System.err.println("ERROR: test class " + test + " not found in target/classes or target/test-classes"); //$NON-NLS-1$ //$NON-NLS-2$
			System.exit(1);
		}
		System.out.println("Running test: " + fqn); //$NON-NLS-1$

		Path props = target.resolve("surefire.properties"); //$NON-NLS-1$
		String text = Files.readString(props);
		text = Pattern.compile("^__provider\\.tc\\.0=.*$", Pattern.MULTILINE) //$NON-NLS-1$
				.matcher(text).replaceAll("__provider.tc.0=" + Matcher.quoteReplacement(fqn)); //$NON-NLS-1$
		Files.writeString(props, text);

		Path m2 = Kit.m2Repo();
		Path launcher = Kit.findBestArtifact(m2, "p2/osgi/bundle/org.eclipse.equinox.launcher", //$NON-NLS-1$
				"org.eclipse.equinox.launcher-*.jar"); //$NON-NLS-1$
		String[] platform = Kit.osgiPlatform();

		List<String> cmd = new ArrayList<>();
		cmd.add(Kit.javaExe());
		cmd.add("-Dosgi.noShutdown=false"); //$NON-NLS-1$
		cmd.add("-Dosgi.os=" + platform[0]); //$NON-NLS-1$
		cmd.add("-Dosgi.ws=" + platform[1]); //$NON-NLS-1$
		cmd.add("-Dosgi.arch=" + platform[2]); //$NON-NLS-1$
		cmd.add("--add-modules=ALL-SYSTEM"); //$NON-NLS-1$
		cmd.add("-Dosgi.clean=true"); //$NON-NLS-1$
		cmd.add("-Declipse.test.screenshot_directory=" + target.resolve("screenshots")); //$NON-NLS-1$ //$NON-NLS-2$
		cmd.add("-ea"); //$NON-NLS-1$
		cmd.add("-jar"); //$NON-NLS-1$
		cmd.add(launcher.toString());
		cmd.add("-debug"); //$NON-NLS-1$
		cmd.add("-consolelog"); //$NON-NLS-1$
		cmd.add("-data"); //$NON-NLS-1$
		cmd.add(work.resolve("data").toString()); //$NON-NLS-1$
		cmd.add("-install"); //$NON-NLS-1$
		cmd.add(work.toString());
		cmd.add("-configuration"); //$NON-NLS-1$
		cmd.add(work.resolve("configuration").toString()); //$NON-NLS-1$
		cmd.add("-application"); //$NON-NLS-1$
		cmd.add("org.eclipse.tycho.surefire.osgibooter.uitest"); //$NON-NLS-1$
		cmd.add("-testproperties"); //$NON-NLS-1$
		cmd.add(props.toString());
		cmd.add("-timeout"); //$NON-NLS-1$
		cmd.add("1200000"); //$NON-NLS-1$
		int rc = new ProcessBuilder(cmd).inheritIO().start().waitFor();
		System.exit(rc);
	}
}
