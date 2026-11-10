package dev.sibarum.inspector.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvTest {

    @Test
    void plainFieldsAreLeftAlone() {
        assertEquals("Topic,Written\r\na.b,12\r\n", Csv.of(List.of("Topic", "Written"), List.of(List.of("a.b", "12"))));
    }

    @Test
    void headersAloneWhenNothingIsSelected() {
        assertEquals("A,B\r\n", Csv.of(List.of("A", "B"), List.of()));
    }

    @Test
    void commasQuotesAndBreaksAreQuoted() {
        StringBuilder out = new StringBuilder();
        Csv.line(out, List.of("1,2", "say \"hi\"", "two\nlines", "", "x"));
        assertEquals("\"1,2\",\"say \"\"hi\"\"\",\"two\nlines\",,x\r\n", out.toString());
    }

    @Test
    void edgeSpacesAreKept() {
        StringBuilder out = new StringBuilder();
        Csv.line(out, List.of(" 51.68 ms", "a b"));
        assertEquals("\" 51.68 ms\",a b\r\n", out.toString());
    }
}
