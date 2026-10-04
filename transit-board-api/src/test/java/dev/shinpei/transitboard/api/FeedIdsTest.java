package dev.shinpei.transitboard.api;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class FeedIdsTest {

    private static final Pattern ALLOWLIST = Pattern.compile("^[A-Za-z0-9_.:-]+$");

    @Test
    void allIdsMatchAllowlist() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("feed-ids.txt");
        assertNotNull(is, "feed-ids.txt not found on test classpath — regenerate with scripts/regenerate-feed-ids.py");

        List<String> failing = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                int tab = line.indexOf('\t');
                assertTrue(tab > 0, "Line missing tab separator: " + line);
                String id = line.substring(tab + 1);
                if (!ALLOWLIST.matcher(id).matches()) {
                    failing.add(id);
                }
            }
        }
        assertTrue(failing.isEmpty(), "IDs failing allowlist: " + failing);
    }

    @Test
    void negativeCheckFailsForBadId() {
        List<String> badIds = List.of("1_1?zz=1");
        for (String id : badIds) {
            assertFalse(ALLOWLIST.matcher(id).matches(),
                    "Expected '" + id + "' to fail the allowlist check but it passed");
        }
    }
}
