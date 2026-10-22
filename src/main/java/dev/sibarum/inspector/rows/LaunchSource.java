package dev.sibarum.inspector.rows;

import dev.sibarum.inspector.history.Build;
import dev.vexelray.gui.automation.Discovery;
import sibarum.probe.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An application the Inspector starts itself, then inspects live: {@code --launch=<command>}, as
 * {@code ottermate --launch} does it (vexelray-gui {@code Launch}).
 *
 * <p><b>It does not know how to start anything.</b> It runs the command line it was given, which has to switch the
 * application's automation on ({@code --automation=0} or {@code -Dautomation=0}), because the link and the discovery
 * file come with it. It then waits for two things: the {@code automation: localhost:<port>} line on the child's
 * output, which says the sockets are up, and a discovery file from a process in the child's tree (a launch through
 * {@code mvn exec:exec} is a shell running Maven running the JVM that owns the window) that advertises a link. The
 * process, not the port, is what is matched: an application with both sockets on prints a line for each, and its
 * file names only one of them. From there it is a {@link LinkSource} on that file, exactly as if it had been chosen in the
 * picker.
 *
 * <p><b>It owns what it started.</b> {@link #close} stops the child and everything under it: when another application
 * is chosen, and when the Inspector closes. A shutdown hook does the same if the Inspector is ended some other way, so
 * a launched window never outlives the Inspector that nobody is watching it through.
 *
 * <p><b>It probes the bus.</b> A driven run leaves the bus lane out, so the child is started with {@link #LANES} in its
 * environment, unless the caller set {@code PROBE} there or {@code -Dprobe} on the command line.
 *
 * <p><b>The child's output goes to the log</b> ({@code vexelray-inspector.launch}, at debug), never to stdout, which is
 * for lines another program parses. A launch that fails says why in the status line; the child's own account of it
 * is in the log.
 *
 * <h2>Threads</h2>
 *
 * Two platform daemon threads of its own (FN-1): {@code inspector-launch-output} reads the child's output for as long
 * as it runs, and {@code inspector-launch} waits for the child to be ready, starts the {@link LinkSource} and ends.
 * From then on the rows arrive as the link delivers them.
 */
public final class LaunchSource implements RowSource {

    private static final Log LOG = Log.of("vexelray-inspector.launch");

    /** What the framework's automation driver prints on binding. Anywhere in the line: Maven may prefix it. */
    static final Pattern PORT_LINE = Pattern.compile("automation:\\s*localhost:(\\d+)");

    /** How often the discovery folder is looked at while waiting. */
    private static final long POLL_MS = 100;

    /** How long a stopped child is given to go quietly before it is made to. */
    private static final int GRACE_SECONDS = 5;

    private final List<String> command;
    private final long timeoutNs;
    private final Supplier<List<Discovery.Entry>> live;
    private volatile Process process;
    private volatile LinkSource link;
    private volatile int port = -1;
    private volatile boolean closed;
    private volatile Thread thread;
    private volatile Thread hook;

    /**
     * @param command        the command line, split ({@link #split})
     * @param timeoutSeconds how long the child has to become inspectable, Maven's compile included
     * @param live           the discovery folder's applications, read again on each look ({@link Discovery#live()})
     */
    public LaunchSource(List<String> command, int timeoutSeconds, Supplier<List<Discovery.Entry>> live) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("--launch needs a command");
        }
        this.command = List.copyOf(command);
        this.timeoutNs = TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        this.live = live;
    }

    /**
     * A command line as words: split on white space, with a double-quoted stretch kept as one word without its quotes,
     * so a path with spaces in it can be given. There are no escapes.
     */
    public static List<String> split(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    words.add(word.toString());
                    word.setLength(0);
                    any = false;
                }
            } else {
                word.append(c);
                any = true;
            }
        }
        if (any) {
            words.add(word.toString());
        }
        return words;
    }

    /**
     * {@code args} with everything after a bare {@code --launch} folded into one {@code --launch=<command>}, quoted so
     * {@link #split} gives the same words back; unchanged when there is none. This is how {@code --launch <command>}
     * works where a command line is split on white space before the Inspector sees it, as {@code -Dapp.args} is
     * (FN-3).
     */
    public static String[] fold(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--launch")) {
                String command = java.util.Arrays.stream(args, i + 1, args.length)
                        .map(w -> w.isEmpty() || w.chars().anyMatch(Character::isWhitespace) ? '"' + w + '"' : w)
                        .collect(Collectors.joining(" "));
                String[] folded = java.util.Arrays.copyOf(args, i + 1);
                folded[i] = "--launch=" + command;
                return folded;
            }
        }
        return args;
    }

    /**
     * The launched application among {@code live}: the one from one of {@code pids} that advertises a link; none while
     * it has not written its file yet.
     */
    static Optional<Discovery.Entry> find(List<Discovery.Entry> live, Set<Long> pids) {
        return live.stream()
                .filter(e -> pids.contains(e.pid()) && e.stream() != null)
                .findFirst();
    }

    /**
     * The lanes a launched application is probed on, unless its environment already says: a driven run's own
     * ({@code frame,input,layout,app}) and the bus, which a driven run leaves out (FN-4), traced in CSV as a driven
     * run is. Set in the child's environment ({@code PROBE}, {@code PROBE_FORMAT}), which every VexelRay
     * application reads, so the command line stays the caller's; a {@code -Dprobe} in it still wins.
     */
    static final String LANES = "frame,input,layout,app,bus";

    /** Probe the child on {@link #LANES}, in CSV, unless {@code env} already chose. */
    static void probe(java.util.Map<String, String> env) {
        if (!env.containsKey("PROBE")) {
            env.put("PROBE", LANES);
            env.put("PROBE_FORMAT", "csv");
        }
    }

    @Override
    public void start(RowSink sink) {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        Thread t = Thread.ofPlatform().name("inspector-launch").daemon().unstarted(() -> launch(sink));
        thread = t;
        t.start();
    }

    private void launch(RowSink sink) {
        Process p;
        try {
            ProcessBuilder b = new ProcessBuilder(command).redirectErrorStream(true);
            probe(b.environment());
            p = b.start();
        } catch (IOException e) {
            if (!closed) {
                sink.end("could not launch " + describe() + ": " + e.getMessage());
            }
            return;
        }
        process = p;
        Thread h = Thread.ofPlatform().name("inspector-launch-stop").unstarted(() -> destroy(p));
        hook = h;
        Runtime.getRuntime().addShutdownHook(h);
        if (closed) {
            stop();
            return;
        }
        LOG.info("launched pid " + p.pid() + ": " + String.join(" ", command));
        Thread.ofPlatform().name("inspector-launch-output").daemon().start(() -> relay(p));
        String why = await(p, sink);
        if (why != null && !closed) {
            sink.end(why);
        }
    }

    /** Wait for the child to become inspectable and attach to it; why it did not, or {@code null} when it did. */
    private String await(Process p, RowSink sink) {
        long deadline = System.nanoTime() + timeoutNs;
        while (!closed) {
            int at = port;
            if (at > 0) {
                Set<Long> pids = tree(p);
                Optional<Discovery.Entry> found = find(live.get(), pids);
                if (found.isPresent()) {
                    Discovery.Entry e = found.get();
                    LinkSource l = new LinkSource(e.stream(), Attach.who(e), Build.of(e));
                    link = l;
                    if (closed) {
                        l.close();
                        return null;
                    }
                    l.start(sink);
                    return null;
                }
            }
            if (!p.isAlive()) {
                return describe() + " exited (status " + p.exitValue() + ") before it could be inspected";
            }
            if (System.nanoTime() > deadline) {
                return at > 0
                        ? describe() + " opened its automation socket but advertised no inspector link"
                        : describe() + " did not announce a port; the command needs --automation=0";
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    /** The child and everything under it. */
    private static Set<Long> tree(Process p) {
        Set<Long> pids = p.descendants().map(ProcessHandle::pid).collect(Collectors.toSet());
        pids.add(p.pid());
        return pids;
    }

    /** The child's output, to the log, watching for the port line. */
    private void relay(Process p) {
        try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                LOG.debug(line);
                if (port < 0) {
                    Matcher m = PORT_LINE.matcher(line);
                    if (m.find()) {
                        port = Integer.parseInt(m.group(1));
                    }
                }
            }
        } catch (IOException e) {
            LOG.debug("the launched application's output ended: " + e.getMessage());
        }
    }

    @Override
    public Optional<Build> build() {
        LinkSource l = link;
        return l == null ? Optional.empty() : l.build();
    }

    @Override
    public Optional<String> busGraph() {
        LinkSource l = link;
        return l == null ? Optional.empty() : l.busGraph();
    }

    @Override
    public dev.sibarum.inspector.graphics.Graphics graphics() {
        LinkSource l = link;
        return l == null ? dev.sibarum.inspector.graphics.Graphics.NONE : l.graphics();
    }

    @Override
    public String describe() {
        LinkSource l = link;
        return l != null ? l.describe() : command.getFirst();
    }

    @Override
    public void close() {
        closed = true;
        LinkSource l = link;
        if (l != null) {
            l.close();
        }
        if (process != null) {
            stop();
        }
    }

    /** Stop the child, and stop watching for the Inspector's own end to do it. */
    private void stop() {
        Thread h = hook;
        if (h != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(h);
            } catch (IllegalStateException e) {
                // Already shutting down: the hook is running or has run, and does the stopping.
                return;
            }
        }
        destroy(process);
    }

    private static void destroy(Process p) {
        if (!p.isAlive()) {
            return;
        }
        // Descendants first, and listed before the parent goes, or the JVM under Maven is left with its window.
        List<ProcessHandle> children = p.descendants().toList();
        children.forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(GRACE_SECONDS, TimeUnit.SECONDS)) {
                children.forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            children.forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        LOG.info("stopped pid " + p.pid());
    }
}
