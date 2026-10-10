import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scoped Maven run: builds the test bundle + workspace deps, runs one test
 * class. Fallback path — prefer the Maven-free cycle (CompileBundle +
 * RunTestFork); use this for bootstrapping target/work, after
 * target-platform changes, or when the Maven-free path does not apply.
 *
 * Usage: FastTest [--repo &lt;path&gt;] [--test &lt;ClassName&gt;]
 */
public class FastTest {

	/**
	 * Extra reactor members the test bundle needs; -am cannot see p2-only
	 * deps. Overridable via --pl for other projects.
	 */
	private static final String DEFAULT_TEST_PL = ":org.eclipse.team.tests.core,:org.eclipse.core.tests.filesystem.feature"; //$NON-NLS-1$

	public static void main(String[] args) throws Exception {
		Kit.requireJdk25();
		Map<String, String> parsed = Kit.parseArgs(args);
		Path repo = Kit.repoRoot(parsed);
		String test = parsed.getOrDefault("test", "UnifiedDiffManagerTest"); //$NON-NLS-1$ //$NON-NLS-2$
		String pl = parsed.getOrDefault("pl", DEFAULT_TEST_PL); //$NON-NLS-1$

		List<String> cmd = new ArrayList<>();
		cmd.add(findMvn());
		cmd.add("verify"); //$NON-NLS-1$
		cmd.add("-pl"); //$NON-NLS-1$
		cmd.add(pl);
		cmd.add("-am"); //$NON-NLS-1$
		cmd.add("-Dtest=" + test); //$NON-NLS-1$
		cmd.add("-DfailIfNoTests=false"); //$NON-NLS-1$
		cmd.add("-Dmaven.javadoc.skip=true"); //$NON-NLS-1$
		cmd.add("--batch-mode"); //$NON-NLS-1$
		int rc = run(cmd, repo);
		System.exit(rc);
	}

	private static int run(List<String> cmd, Path repo) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile()).inheritIO();
		// mvn.cmd requires JAVA_HOME; point it at the JDK running this kit.
		pb.environment().put("JAVA_HOME", System.getProperty("java.home")); //$NON-NLS-1$ //$NON-NLS-2$
		return pb.start().waitFor();
	}

	private static String findMvn() {
		String home = System.getenv("MAVEN_HOME"); //$NON-NLS-1$
		if (home != null && !home.isBlank()) {
			return Paths.get(home, "bin", Kit.IS_WINDOWS ? "mvn.cmd" : "mvn").toString(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		Path bundled = Paths.get(System.getProperty("user.home"), "tools", "apache-maven-3.9.12", "bin", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				Kit.IS_WINDOWS ? "mvn.cmd" : "mvn"); //$NON-NLS-1$ //$NON-NLS-2$
		if (Files.isRegularFile(bundled)) {
			return bundled.toString();
		}
		// Last resort: whatever is on PATH.
		return Kit.IS_WINDOWS ? "mvn.cmd" : "mvn"; //$NON-NLS-1$ //$NON-NLS-2$
	}
}
