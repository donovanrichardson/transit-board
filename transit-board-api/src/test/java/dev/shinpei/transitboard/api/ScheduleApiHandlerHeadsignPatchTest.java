package dev.shinpei.transitboard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleApiHandlerHeadsignPatchTest {

    private HttpServer mockObaServer;
    private HttpServer apiServer;
    private int obaPort;
    private int apiPort;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (apiServer != null) apiServer.stop(0);
        if (mockObaServer != null) mockObaServer.stop(0);
    }

    private String loadFixture(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Fixture not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Starts mock OBA server and API server. The tripJson parameter controls what
     * /api/where/trip/LI_hp_trip_001.json returns; pass null to simulate a 500 error.
     */
    private void startServers(String tripJson) throws Exception {
        String scheduleJson = loadFixture("fixtures/headsign-patch-huntington-schedule.json");
        String stopJson = loadFixture("fixtures/headsign-patch-stop-response.json");
        String agencyJson = loadFixture("fixtures/agency-li-response.json");
        String tripScheduleJson = loadFixture("fixtures/headsign-patch-trip-schedule-no-next-stopheadsign.json");

        mockObaServer = HttpServer.create(new InetSocketAddress(0), 0);
        obaPort = mockObaServer.getAddress().getPort();

        mockObaServer.createContext("/api/where/schedule-for-stop/LI_hp_200.json", exchange -> {
            byte[] body = scheduleJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/stop/LI_hp_200.json", exchange -> {
            byte[] body = stopJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/agency/LI.json", exchange -> {
            byte[] body = agencyJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/trip-details/LI_hp_trip_001.json", exchange -> {
            byte[] body = tripScheduleJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        if (tripJson != null) {
            final byte[] tripBody = tripJson.getBytes(StandardCharsets.UTF_8);
            mockObaServer.createContext("/api/where/trip/LI_hp_trip_001.json", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, tripBody.length);
                exchange.getResponseBody().write(tripBody);
                exchange.close();
            });
        } else {
            mockObaServer.createContext("/api/where/trip/LI_hp_trip_001.json", exchange -> {
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
            });
        }

        mockObaServer.start();

        apiServer = ApiServer.create(0, "http://localhost:" + obaPort);
        apiPort = apiServer.getAddress().getPort();
        apiServer.start();
    }

    /**
     * Starts servers with a two-departure schedule fixture to test that both departures
     * sharing the same tripId get patched.
     */
    private void startServersWithTwoDepartures(String tripJson) throws Exception {
        String scheduleJson = loadFixture("fixtures/headsign-patch-huntington-two-departures.json");
        String stopJson = loadFixture("fixtures/headsign-patch-stop-response.json");
        String agencyJson = loadFixture("fixtures/agency-li-response.json");
        String tripScheduleJson = loadFixture("fixtures/headsign-patch-trip-schedule-no-next-stopheadsign.json");

        mockObaServer = HttpServer.create(new InetSocketAddress(0), 0);
        obaPort = mockObaServer.getAddress().getPort();

        mockObaServer.createContext("/api/where/schedule-for-stop/LI_hp_200.json", exchange -> {
            byte[] body = scheduleJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/stop/LI_hp_200.json", exchange -> {
            byte[] body = stopJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/agency/LI.json", exchange -> {
            byte[] body = agencyJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/trip-details/LI_hp_trip_001.json", exchange -> {
            byte[] body = tripScheduleJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        final byte[] tripBody = tripJson.getBytes(StandardCharsets.UTF_8);
        mockObaServer.createContext("/api/where/trip/LI_hp_trip_001.json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, tripBody.length);
            exchange.getResponseBody().write(tripBody);
            exchange.close();
        });

        mockObaServer.start();

        apiServer = ApiServer.create(0, "http://localhost:" + obaPort);
        apiPort = apiServer.getAddress().getPort();
        apiServer.start();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + apiPort + path))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void headsignDerivedFromTripEndpoint() throws Exception {
        String tripJson = "{ \"data\": { \"entry\": { \"id\": \"LI_hp_trip_001\", \"directionId\": \"0\", \"routeId\": \"LI_1\", \"tripHeadsign\": \"Port Jefferson\" } } }";
        startServers(tripJson);

        HttpResponse<String> resp = get("/api/schedule?stop=LI_hp_200&date=2026-06-17");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        JsonNode departures = body.get("departures");
        assertTrue(departures.isArray());
        assertTrue(departures.size() > 0, "Must have at least one departure");

        JsonNode dep = departures.get(0);
        assertEquals("Port Jefferson", dep.get("headsign").asText(),
                "Headsign must be derived from fetchTrip tripHeadsign");

        List<String> headsignList = new ArrayList<>();
        for (JsonNode n : body.get("headsigns")) headsignList.add(n.asText());
        assertTrue(headsignList.contains("Port Jefferson"), "headsigns list must contain 'Port Jefferson'");
        assertFalse(headsignList.contains("Huntington"), "headsigns list must not contain original 'Huntington'");
    }

    @Test
    void headsignFallsBackToScheduleParserWhenFetchTripFails() throws Exception {
        startServers(null); // null => server returns 500 for trip endpoint

        HttpResponse<String> resp = get("/api/schedule?stop=LI_hp_200&date=2026-06-17");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        JsonNode departures = body.get("departures");
        assertTrue(departures.isArray());
        assertTrue(departures.size() > 0, "Must have at least one departure");

        JsonNode dep = departures.get(0);
        assertEquals("Huntington", dep.get("headsign").asText(),
                "Headsign must fall back to ScheduleParser value when fetchTrip fails");
    }

    @Test
    void headsignFallsBackWhenTripHeadsignIsEmpty() throws Exception {
        String tripJson = "{ \"data\": { \"entry\": { \"id\": \"LI_hp_trip_001\", \"directionId\": \"0\", \"routeId\": \"LI_1\", \"tripHeadsign\": \"\" } } }";
        startServers(tripJson);

        HttpResponse<String> resp = get("/api/schedule?stop=LI_hp_200&date=2026-06-17");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        JsonNode departures = body.get("departures");
        assertTrue(departures.isArray());
        assertTrue(departures.size() > 0, "Must have at least one departure");

        JsonNode dep = departures.get(0);
        assertEquals("Huntington", dep.get("headsign").asText(),
                "Headsign must fall back to ScheduleParser value when tripHeadsign is empty");
    }

    @Test
    void allDeparturesForSameTripGetSameHeadsign() throws Exception {
        String tripJson = "{ \"data\": { \"entry\": { \"id\": \"LI_hp_trip_001\", \"directionId\": \"0\", \"routeId\": \"LI_1\", \"tripHeadsign\": \"Port Jefferson\" } } }";
        startServersWithTwoDepartures(tripJson);

        HttpResponse<String> resp = get("/api/schedule?stop=LI_hp_200&date=2026-06-17");
        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        JsonNode departures = body.get("departures");
        assertTrue(departures.isArray());
        assertEquals(2, departures.size(), "Must have two departures");

        for (JsonNode dep : departures) {
            assertEquals("Port Jefferson", dep.get("headsign").asText(),
                    "All departures for the same tripId must get headsign from fetchTrip");
        }
    }
}
