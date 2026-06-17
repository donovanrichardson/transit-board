package dev.shinpei.transitboard.api;

import com.sun.net.httpserver.HttpServer;
import dev.shinpei.transitboard.model.ObaTripScheduleResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ObaClientFetchTripScheduleTest {

    private HttpServer mockServer;
    private ObaClient obaClient;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = mockServer.getAddress().getPort();

        String scheduleJson = loadFixture("fixtures/lirr-trip-001-schedule-response.json");
        mockServer.createContext("/api/where/trip-details/LI_trip_001.json", exchange -> {
            // Verify key param is present
            String query = exchange.getRequestURI().getQuery();
            assertTrue(query != null && query.contains("key="), "Request must include key param");
            byte[] body = scheduleJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockServer.start();
        obaClient = new ObaClient("http://localhost:" + port);
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) mockServer.stop(0);
    }

    private String loadFixture(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Fixture not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void fetchTripScheduleReturnsCorrectStopData() throws Exception {
        ObaTripScheduleResponse resp = obaClient.fetchTripSchedule("LI_trip_001");

        assertNotNull(resp);
        assertNotNull(resp.data);
        assertNotNull(resp.data.entry);
        assertEquals(4, resp.data.entry.schedule.stopTimes.size());
        assertEquals("LI_102", resp.data.entry.schedule.stopTimes.get(0).stopId);

        assertNotNull(resp.data.references);
        assertEquals(4, resp.data.references.stops.size());
        assertEquals("Mineola", resp.data.references.stops.get(0).name);
        assertEquals("Penn Station", resp.data.references.stops.get(3).name);
    }

    @Test
    void fetchTripScheduleCallsCorrectEndpoint() throws Exception {
        // If the server doesn't receive the request at the right path, it returns 404 and throws
        ObaTripScheduleResponse resp = obaClient.fetchTripSchedule("LI_trip_001");
        assertNotNull(resp);
    }
}
