package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.ChildExited;
import dev.sibarum.inspector.core.Messages.LaunchChild;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Sender;
import sibarum.probe.Cause;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A launched command: how its command line is read, and what its run says when it cannot start or ends. */
class ChildTest {

    @Test
    void aCommandIsSplitOnSpacesOutsideDoubleQuotes() {
        assertEquals(List.of("mvn.cmd", "-o", "exec:exec", "-Dapp.args=a b"),
                Child.words("  mvn.cmd -o  exec:exec \"-Dapp.args=a b\" "));
        assertEquals(List.of("C:/Program Files/app.exe", ""), Child.words("\"C:/Program Files/app.exe\" \"\""));
        assertEquals(List.of(), Child.words("   "));
    }

    @Test
    void aCommandThatCannotStartSaysSo() {
        Child child = new Child(Sender.outside(Atchung.create(), Messages.CHOOSE_TOPIC,
                Cause.source("test", Cause.Kind.IO)));
        ChildExited e = child.run(new LaunchChild("no-such-command-anywhere-7f3a"));
        assertTrue(e.why().startsWith("it could not be started: "), e.why());
        assertEquals("there was no command to start", child.run(new LaunchChild(" ")).why());
    }

    @Test
    void aCommandThatEndsSaysItsExitCodeAndItsLastLine() {
        Child child = new Child(Sender.outside(Atchung.create(), Messages.CHOOSE_TOPIC,
                Cause.source("test", Cause.Kind.IO)));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ChildExited e = child.run(new LaunchChild("\"" + java + "\" -version"));
        assertTrue(e.why().startsWith("it exited with 0: "), e.why());
    }
}
