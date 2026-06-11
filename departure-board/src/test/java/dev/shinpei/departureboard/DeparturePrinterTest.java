package dev.shinpei.departureboard;

import dev.shinpei.departureboard.model.Departure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeparturePrinterTest {

    private DeparturePrinter printer;
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    // 2026-06-11 06:12 PDT
    private static final Instant T_0612 = Instant.ofEpochMilli(1781183520000L);
    // 2026-06-11 06:18 PDT
    private static final Instant T_0618 = Instant.ofEpochMilli(1781183880000L);
    // 2026-06-11 06:22 PDT
    private static final Instant T_0622 = Instant.ofEpochMilli(1781184120000L);

    @BeforeEach
    void setUp() {
        printer = new DeparturePrinter();
    }

    @Test
    void formatsColumnsCorrectly() {
        List<Departure> departures = List.of(
            new Departure("40", "Loyal Heights Greenwood", T_0612),
            new Departure("40", "Downtown Seattle", T_0618),
            new Departure("E Line", "Aurora Village", T_0622)
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        printer.print(departures, LA, ps);
        ps.flush();

        String output = baos.toString();
        String[] lines = output.split(System.lineSeparator());

        assertEquals(4, lines.length, "Should have header + 3 data rows");

        // Header line
        assertTrue(lines[0].startsWith("Route"), "Header should start with Route");
        assertTrue(lines[0].contains("Headsign"), "Header should contain Headsign");
        assertTrue(lines[0].contains("Departs"), "Header should contain Departs");

        // No trailing whitespace on any line
        for (String line : lines) {
            assertEquals(line, line.stripTrailing(), "Line should have no trailing whitespace: [" + line + "]");
        }

        // Route column width: "E Line".length() = 6; header "Route".length() = 5 → padded to 6
        // Headsign column: "Loyal Heights Greenwood".length() = 23; header "Headsign".length() = 8 → padded to 23
        // Columns separated by 3 spaces

        // Check first data row
        // route column width = max("Route"=5, "E Line"=6) = 6
        // "40" padded to 6 = "40    " (4 spaces), then separator "   " (3 spaces) = 7 spaces after "40"
        String row1 = lines[1];
        assertTrue(row1.startsWith("40"), "Row should start with route 40");
        assertTrue(row1.contains("Loyal Heights Greenwood"), "Should contain headsign");
        assertTrue(row1.endsWith("06:12"), "Should end with departure time");

        // Check last data row - E Line should not be padded (already max width)
        String row3 = lines[3];
        assertTrue(row3.startsWith("E Line"), "E Line should not be padded");
        assertTrue(row3.endsWith("06:22"), "Should end with departure time");

        // Verify exact format of first data line
        // route(6) + sep(3) + headsign(23) + sep(3) + time(5)
        // "40" + 4 spaces (pad to 6) + "   " (sep) + "Loyal Heights Greenwood" + "   " (sep) + "06:12"
        // "40" + 4 spaces (pad to width 6) + 3 spaces (sep) = 7 spaces after "40"
        String expectedRow1 = "40       " + "Loyal Heights Greenwood   06:12";
        assertEquals(expectedRow1, row1);
    }

    @Test
    void emptyListProducesNoOutput() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        printer.print(List.of(), LA, ps);
        ps.flush();

        assertEquals("", baos.toString(), "Empty departure list should produce no output");
    }
}
