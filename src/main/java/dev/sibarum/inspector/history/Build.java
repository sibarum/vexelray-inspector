package dev.sibarum.inspector.history;

import dev.vexelray.gui.automation.Discovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Which build of an application is running: its coordinates, its exact source, and the exact source of the stack it
 * was built with, as its discovery file advertises them (vexelray-build {@code README.md}).
 *
 * <p>Two runs are the same build only when both match. The application's source is its commit, plus
 * {@code +dirty.<digest>} of any uncommitted change; but its binaries also change when a stack module changes under
 * the same application commit, so {@code stack} is a digest of every stamped part's {@code group:artifact:version@source}
 * line. {@link #key()} is the two together, and the history groups runs by it.
 *
 * @param group    the Maven groupId, or {@code -}
 * @param artifact the Maven artifactId, or {@code -}
 * @param version  the Maven version, or {@code -}
 * @param source   the application's exact source, or {@code unknown}
 * @param stack    a 12-hex digest of the stamped parts, or {@code -} when none were advertised
 */
public record Build(String group, String artifact, String version, String source, String stack) {

    /** What a discovery file without build facts gives: nothing to key a history on. */
    public static final String UNKNOWN = "unknown";

    /** The build a running application advertises. */
    public static Build of(Discovery.Entry e) {
        StringBuilder parts = new StringBuilder();
        int n;
        try {
            n = Integer.parseInt(e.get("stack.count").trim());
        } catch (NumberFormatException ex) {
            n = 0;
        }
        for (int i = 1; i <= n; i++) {
            parts.append(e.get("stack." + i)).append('\n');
        }
        return new Build(e.get("app.group"), e.get("app.artifact"), e.get("app.version"), e.source(),
                n == 0 ? "-" : digest(parts.toString()));
    }

    /** The first 12 hex digits of SHA-256 over {@code text}. */
    static String digest(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /** Whether the source is known, so runs of this build can be kept and compared. */
    public boolean known() {
        return !UNKNOWN.equals(source) && !"-".equals(group) && !"-".equals(artifact);
    }

    /** What makes two runs the same build: the application's source and the stack's together. */
    public String key() {
        return source + "/" + stack;
    }

    /**
     * The build for people: the commit's first seven digits, {@code +dirty} when there were uncommitted changes, and
     * the stack digest's first four. Two dirty builds of one commit read the same here; {@link #key()} tells them apart.
     */
    public String shortSource() {
        int plus = source.indexOf('+');
        String commit = plus < 0 ? source : source.substring(0, plus);
        String head = (commit.length() > 7 ? commit.substring(0, 7) : commit) + (plus < 0 ? "" : "+dirty");
        return "-".equals(stack) ? head : head + " · stack " + stack.substring(0, Math.min(4, stack.length()));
    }
}
