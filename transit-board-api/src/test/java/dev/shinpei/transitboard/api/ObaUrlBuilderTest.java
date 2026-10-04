package dev.shinpei.transitboard.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ObaUrlBuilderTest {

    // Test 1: path segment containing '?' throws
    @Test
    void pathWithQueryCharThrows() {
        ObaUrlBuilder builder = new ObaUrlBuilder("http://oba", "key");
        assertThrows(IllegalArgumentException.class, () -> builder.path("1_1?zz=1"));
    }

    // Test 2: path segment containing '/' throws
    @Test
    void pathWithSlashThrows() {
        ObaUrlBuilder builder = new ObaUrlBuilder("http://oba", "key");
        assertThrows(IllegalArgumentException.class, () -> builder.path("1_1/../x"));
    }

    // Test 3: space throws; underscore is accepted and appears literally in the URL
    @Test
    void spaceThrowsAndUnderscoreAccepted() {
        ObaUrlBuilder builder = new ObaUrlBuilder("http://oba", "key");
        assertThrows(IllegalArgumentException.class, () -> builder.path("a b"));

        String url = new ObaUrlBuilder("http://oba", "key")
                .path("stop").path("1_1")
                .build();
        assertTrue(url.contains("1_1"), "URL must contain 1_1 literally, got: " + url);
    }

    // Test 4: query encodes values; '&' in value becomes '%26'
    @Test
    void queryParamEncoding() {
        String url = new ObaUrlBuilder("http://oba", "key")
                .path("stop").path("1_1")
                .query("date", "2026-10-04")
                .build();
        assertTrue(url.contains("date=2026-10-04"), "URL must contain date=2026-10-04, got: " + url);

        String url2 = new ObaUrlBuilder("http://oba", "key")
                .path("stop").path("1_1")
                .query("x", "a&b")
                .build();
        assertTrue(url2.contains("x=a%26b"), "Ampersand must be encoded as %26, got: " + url2);
    }

    // Test 5: build() produces exactly one 'key=' parameter
    @Test
    void buildHasExactlyOneKeyParam() {
        String url = new ObaUrlBuilder("http://oba", "mykey")
                .path("stop").path("1_1")
                .build();
        int count = 0;
        int idx = 0;
        while ((idx = url.indexOf("key=", idx)) != -1) {
            count++;
            idx += 4;
        }
        assertEquals(1, count, "URL must contain exactly one 'key=' parameter, got: " + url);
    }

    // Test 6: all committed feed IDs from gtfs-out pass the allowlist
    @Test
    void committedFeedIdsPassAllowlist() throws Exception {
        Pattern allowlist = Pattern.compile("^[A-Za-z0-9_.:-]+$");

        checkFileIds("gtfs-out/stops.txt", "stop_id", allowlist);
        checkFileIds("gtfs-out/trips.txt", "trip_id", allowlist);
        checkFileIds("gtfs-out/stop_times.txt", "stop_id", allowlist);
        checkFileIds("gtfs-out/agency.txt", "agency_id", allowlist);
    }

    private void checkFileIds(String resource, String idColumn, Pattern allowlist) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(is, "Test resource not found: " + resource);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));

            String headerLine = reader.readLine();
            assertNotNull(headerLine, "Empty file: " + resource);

            String[] headers = headerLine.split(",", -1);
            int colIdx = -1;
            for (int i = 0; i < headers.length; i++) {
                if (idColumn.equals(headers[i].trim())) {
                    colIdx = i;
                    break;
                }
            }
            assertNotEquals(-1, colIdx, "Column '" + idColumn + "' not found in " + resource);

            List<String> failing = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",", -1);
                if (colIdx < fields.length) {
                    String id = fields[colIdx].trim();
                    if (!id.isEmpty() && !allowlist.matcher(id).matches()) {
                        failing.add(id);
                    }
                }
            }

            assertTrue(failing.isEmpty(),
                    "IDs in " + resource + " that fail the allowlist: " + failing);
        }
    }
}
