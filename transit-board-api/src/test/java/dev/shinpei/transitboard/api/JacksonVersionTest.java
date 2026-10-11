package dev.shinpei.transitboard.api;

import com.fasterxml.jackson.core.Version;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonVersionTest {

    private static final int MIN_MAJOR = 2;
    private static final int MIN_MINOR = 18;
    private static final int MIN_PATCH = 11;

    @Test
    void jacksonDatabindVersionIsAtLeast2_18_11() {
        Version v = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION;
        assertTrue(
                isAtLeast(v.getMajorVersion(), v.getMinorVersion(), v.getPatchLevel()),
                "jackson-databind version " + v + " must be >= " +
                        MIN_MAJOR + "." + MIN_MINOR + "." + MIN_PATCH);
    }

    @Test
    void jacksonCoreVersionIsAtLeast2_18_11() {
        Version v = com.fasterxml.jackson.core.json.PackageVersion.VERSION;
        assertTrue(
                isAtLeast(v.getMajorVersion(), v.getMinorVersion(), v.getPatchLevel()),
                "jackson-core version " + v + " must be >= " +
                        MIN_MAJOR + "." + MIN_MINOR + "." + MIN_PATCH);
    }

    private static boolean isAtLeast(int major, int minor, int patch) {
        if (major != MIN_MAJOR) return major > MIN_MAJOR;
        if (minor != MIN_MINOR) return minor > MIN_MINOR;
        return patch >= MIN_PATCH;
    }
}
