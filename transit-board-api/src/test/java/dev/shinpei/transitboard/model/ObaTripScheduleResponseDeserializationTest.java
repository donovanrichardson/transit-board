package dev.shinpei.transitboard.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ObaTripScheduleResponseDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String loadFixture(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Fixture not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void deserializesStopTimes() throws Exception {
        String json = loadFixture("fixtures/lirr-trip-001-schedule-response.json");
        ObaTripScheduleResponse resp = mapper.readValue(json, ObaTripScheduleResponse.class);

        assertNotNull(resp);
        assertNotNull(resp.data);
        assertNotNull(resp.data.entry);
        assertNotNull(resp.data.entry.schedule);
        assertNotNull(resp.data.entry.schedule.stopTimes);
        assertEquals(4, resp.data.entry.schedule.stopTimes.size());
        assertEquals("LI_102", resp.data.entry.schedule.stopTimes.get(0).stopId);
        assertEquals("LI_105", resp.data.entry.schedule.stopTimes.get(3).stopId);
    }

    @Test
    void deserializesReferenceStops() throws Exception {
        String json = loadFixture("fixtures/lirr-trip-001-schedule-response.json");
        ObaTripScheduleResponse resp = mapper.readValue(json, ObaTripScheduleResponse.class);

        assertNotNull(resp.data.references);
        assertNotNull(resp.data.references.stops);
        assertEquals(4, resp.data.references.stops.size());
        assertEquals("LI_102", resp.data.references.stops.get(0).id);
        assertEquals("Mineola", resp.data.references.stops.get(0).name);
        assertEquals("Penn Station", resp.data.references.stops.get(3).name);
    }
}
