package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.ChildExited;
import dev.sibarum.inspector.core.Messages.Choose;
import dev.sibarum.inspector.core.Messages.LaunchChild;
import dev.sibarum.inspector.core.Messages.Run;
import dev.vexelray.framework.api.Source;
import dev.vexelray.gui.automation.Discovery;
import sibarum.atchung.Sender;
import sibarum.probe.Log;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A command this Inspector started, for as long as it runs: a source, one run per {@link LaunchChild}, on a thread of
 * its own. {@code --launch="<command>"} (vexelray-inspector's {@code docs/design.md} section 10, slice 5).
 *
 * <p><b>Started, its output read, and chosen once it can be inspected.</b> The command is started with its automation
 * on ({@link #AUTOMATION}, a free port), which opens its link and records every lane its link asks for. Its output,
 * stdout and stderr together, is logged a line at a time. Once a discovery file names its process or one of its
 * descendants (a {@code mvn} wrapper's JVM is a grandchild) and has a link, it is sent as a {@link Choose}, so the
 * bench attaches to it and stays with it.
 *
 * <p><b>It dies with the run.</b> The run ends when the command does, saying how in its reply. Stopped (which the
 * framework does to every source at shutdown), the command and every process under it are ended, so closing this
 * Inspector closes what it launched. A process's output is not an interruptible read, so the run reads only what is
 * there and waits in {@code Thread.sleep} between looks, which the stop's interrupt ends.
 *
 * <p>One part of design.md's plan is folded in here: a launch job, a job to stop it and a source for its output were
 * three parts around one process, and a source's stop is already the stop.
 */
@Source
public final class Child {

    private static final Log LOG = Log.of("vexelray-inspector.child");

    /**
     * The environment variable that switches a VexelRay application's automation on, {@code 0} for a free port:
     * {@code Driver.ENVIRONMENT} in vexelray-framework-automation, which this module does not build against. It reaches a
     * {@code mvn} wrapper's JVM as well, which a flag on the wrapper's command line does not.
     */
    static final String AUTOMATION = "VEXELRAY_AUTOMATION";

    /** How long the run waits between looks at the command's output. */
    private static final long LOOK_MS = 50;

    /** How often it looks for the command's discovery file until it has found it. */
    private static final long FIND_NANOS = 250_000_000L;

    private final Sender<Choose> choose;

    public Child(Sender<Choose> choose) {
        this.choose = choose;
    }

    public ChildExited run(LaunchChild request) {
        List<String> argv = words(request.command());
        if (argv.isEmpty()) {
            return new ChildExited(request.command(), "there was no command to start");
        }
        Process p;
        try {
            ProcessBuilder b = new ProcessBuilder(argv).redirectErrorStream(true);
            b.environment().put(AUTOMATION, "0");
            p = b.start();
        } catch (IOException | RuntimeException e) {
            return new ChildExited(request.command(), "it could not be started: " + e.getMessage());
        }
        LOG.info("launched {} as pid {}", request.command(), p.pid());
        String last = "";
        try (Reader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder();
            boolean found = false;
            long lookedAt = 0L;
            while (true) {
                // Only what is there: ready() never blocks, and a read it vouches for does not either.
                while (out.ready()) {
                    int c = out.read();
                    if (c < 0) {
                        break;
                    }
                    if (c == '\n' || c == '\r') {
                        if (!line.isEmpty()) {
                            last = line.toString();
                            LOG.info("pid {}: {}", p.pid(), last);
                            line.setLength(0);
                        }
                    } else {
                        line.append((char) c);
                    }
                }
                if (!found && System.nanoTime() - lookedAt >= FIND_NANOS) {
                    lookedAt = System.nanoTime();
                    Run run = find(p);
                    if (run != null) {
                        found = true;
                        LOG.info("pid {} can be inspected as {}", p.pid(), run.name());
                        choose.send(new Choose(run, Bench.LAUNCHED));
                    }
                }
                if (!p.isAlive() && !out.ready()) {
                    break;
                }
                Thread.sleep(LOOK_MS);
            }
            if (!line.isEmpty()) {
                last = line.toString();
            }
            int code = p.waitFor();
            return new ChildExited(request.command(), "it exited with " + code + (last.isEmpty() ? "" : ": " + last));
        } catch (InterruptedException stopped) {
            return new ChildExited(request.command(), "stopped");
        } catch (IOException | RuntimeException e) {
            return new ChildExited(request.command(), "its output could not be read: " + e.getMessage());
        } finally {
            if (p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroy);
                p.destroy();
            }
        }
    }

    /** The running application with a link that is {@code p} or one of its descendants; null while there is none. */
    private static Run find(Process p) {
        Set<Long> ours = p.descendants().map(ProcessHandle::pid).collect(Collectors.toSet());
        ours.add(p.pid());
        for (Discovery.Entry e : Discovery.live()) {
            if (ours.contains(e.pid()) && e.stream() != null) {
                return new Run(e.base(), e.title(), e.pid(), e.stream());
            }
        }
        return null;
    }

    /**
     * {@code command} as the words it is made of: split on spaces outside double quotes, the quotes dropped, so a path
     * with a space in it can be quoted. One string because the command line has no option that takes the rest of the
     * line (framework-notes FN-13).
     */
    static List<String> words(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    out.add(w.toString());
                    w.setLength(0);
                    any = false;
                }
            } else {
                w.append(c);
                any = true;
            }
        }
        if (any) {
            out.add(w.toString());
        }
        return out;
    }
}
