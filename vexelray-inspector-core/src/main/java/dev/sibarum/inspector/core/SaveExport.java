package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Exported;
import dev.sibarum.inspector.core.Messages.WriteExport;
import dev.vexelray.framework.api.Job;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Write an exported frame to the file the person picked, on the offload lane. It is written beside first and moved
 * into place, so a file half written is never there to be opened; the picker has already asked about replacing one.
 * A name typed with no extension gets {@code .csv}. What goes wrong is the answer's problem, never a throw.
 */
@Job
public final class SaveExport {

    public Exported run(WriteExport w) {
        String named = named(w.path());
        Path temp = null;
        try {
            Path file = Path.of(named);
            Path dir = file.toAbsolutePath().getParent();
            temp = Files.createTempFile(dir, ".export-", ".part");
            Files.writeString(temp, w.text(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new Exported(named, "");
        } catch (IOException | InvalidPathException | SecurityException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // the temporary file stays; the answer still says what went wrong
                }
            }
            return new Exported(named, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** {@code path} with {@code .csv} added when its file name has no extension. */
    static String named(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.indexOf('.', slash + 1) < 0 ? path + ".csv" : path;
    }
}
