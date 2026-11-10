package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Exported;
import dev.sibarum.inspector.core.Messages.WriteExport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveExportTest {

    @Test
    void aNameWithNoExtensionGetsCsv() {
        assertEquals("C:/out/frame 7.csv", SaveExport.named("C:/out/frame 7"));
        assertEquals("C:\\out.d\\frame.csv", SaveExport.named("C:\\out.d\\frame"), "a dot in a folder is not one");
        assertEquals("frame.txt", SaveExport.named("frame.txt"));
    }

    @Test
    void writesTheTextAndLeavesNothingBeside(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("frame");
        Exported e = new SaveExport().run(new WriteExport(file.toString(), "Frame\r\nField,Value\r\n"));
        assertEquals("", e.problem());
        assertEquals(file + ".csv", e.path());
        assertEquals("Frame\r\nField,Value\r\n", Files.readString(Path.of(e.path()), StandardCharsets.UTF_8));
        try (var listed = Files.list(dir)) {
            assertEquals(1, listed.count(), "no temporary file left");
        }
    }

    @Test
    void whatGoesWrongIsTheAnswersProblem(@TempDir Path dir) {
        Exported e = new SaveExport().run(new WriteExport(dir.resolve("no/such/folder/f.csv").toString(), "x"));
        assertFalse(e.problem().isEmpty());
        assertTrue(e.path().endsWith("f.csv"));
    }
}
