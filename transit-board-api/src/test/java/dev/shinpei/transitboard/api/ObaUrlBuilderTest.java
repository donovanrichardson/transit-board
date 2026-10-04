package dev.shinpei.transitboard.api;

import org.junit.jupiter.api.Test;

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

}
