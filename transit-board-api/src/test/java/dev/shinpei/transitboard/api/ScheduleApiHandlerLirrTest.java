package dev.shinpei.transitboard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

class ScheduleApiHandlerLirrTest {

    private HttpServer mockObaServer;
    private HttpServer apiServer;
    private int obaPort;
    private int apiPort;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        mockObaServer = HttpServer.create(new InetSocketAddress(0), 0);
        obaPort = mockObaServer.getAddress().getPort();

        String lirrScheduleJson = loadFixture("fixtures/lirr-schedule-response.json");
        String lirrStopJson = loadFixture("fixtures/lirr-stop-response.json");
        String lirrTripJson = loadFixture("fixtures/lirr-trip-001-response.json");
        String lirrTripScheduleJson = loadFixture("fixtures/lirr-trip-001-schedule-response.json");
        String subwayScheduleJson = loadFixture("fixtures/schedule-response.json");
        String subwayStopJson = loadFixture("fixtures/stop-response.json");
        String subwayParentStopJson = loadFixture("fixtures/parent-stop-response.json");
        String lirrAgencyJson = loadFixture("fixtures/agency-li-response.json");
        String subwayAgencyJson = loadFixture("fixtures/agency-mta-response.json");

        mockObaServer.createContext("/api/where/schedule-for-stop/LI_102.json", exchange -> {
            byte[] body = lirrScheduleJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/stop/LI_102.json", exchange -> {
            byte[] body = lirrStopJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/trip/LI_trip_001.json", exchange -> {
            byte[] body = lirrTripJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/trip-details/LI_trip_001.json", exchange -> {
            byte[] body = lirrTripScheduleJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/schedule-for-stop/MTA_NYCT_725S.json", exchange -> {
            byte[] body = subwayScheduleJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/stop/MTA_NYCT_725S.json", exchange -> {
            byte[] body = subwayStopJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/stop/MTA_NYCT_725.json", exchange -> {
            byte[] body = subwayParentStopJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/agency/LI.json", exchange -> {
            byte[] body = lirrAgencyJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/agency/MTA.json", exchange -> {
            byte[] body = subwayAgencyJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.start();

        String obaBaseUrl = "http://localhost:" + obaPort;
        apiServer = ApiServer.create(0, obaBaseUrl);
        apiPort = apiServer.getAddress().getPort();
        apiServer.start();
    }

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

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + apiPort + path))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void lirrResponseIncludesDirectionId() throws Exception {
        HttpResponse<String> resp = get("/api/schedule?stop=LI_102&date=2026-06-16");

        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        JsonNode departures = body.get("departures");
        assertTrue(departures.isArray());
        assertTrue(departures.size() > 0);

        JsonNode dep = departures.get(0);
        assertTrue(dep.has("directionId"), "Departure must have directionId");
        assertEquals("1", dep.get("directionId").asText());
    }

    @Test
    void subwayResponseHasEmptyHeadsignAbbreviations() throws Exception {
        HttpResponse<String> resp = get("/api/schedule?stop=MTA_NYCT_725S&date=2026-06-14");

        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("headsignAbbreviations"), "Response must have headsignAbbreviations field");
        JsonNode abbrevs = body.get("headsignAbbreviations");
        assertTrue(abbrevs.isObject(), "headsignAbbreviations must be an object");
        assertEquals(0, abbrevs.size(), "headsignAbbreviations must be empty for subway stops");
    }

    @Test
    void lirrResponseIncludesDestinations() throws Exception {
        HttpResponse<String> resp = get("/api/schedule?stop=LI_102&date=2026-06-16");

        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("destinations"), "Response must have destinations field");
        JsonNode destinations = body.get("destinations");
        assertTrue(destinations.isArray(), "destinations must be an array");
        assertTrue(destinations.size() > 0, "destinations must not be empty");

        // The fixture has stops after LI_102: Merillon Avenue, New Hyde Park, Penn Station
        List<String> destList = new ArrayList<>();
        for (JsonNode n : destinations) destList.add(n.asText());

        assertTrue(destList.contains("Merillon Avenue"), "Must contain Merillon Avenue");
        assertTrue(destList.contains("New Hyde Park"), "Must contain New Hyde Park");
        assertTrue(destList.contains("Penn Station"), "Must contain Penn Station");
        assertFalse(destList.contains("Mineola"), "Must not contain the queried stop itself");
    }

    @Test
    void lirrDepartureIncludesDownstreamStops() throws Exception {
        HttpResponse<String> resp = get("/api/schedule?stop=LI_102&date=2026-06-16");

        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        JsonNode departures = body.get("departures");
        assertTrue(departures.isArray());
        assertTrue(departures.size() > 0);

        JsonNode dep = departures.get(0);
        assertTrue(dep.has("downstreamStops"), "Departure must have downstreamStops");
        JsonNode downstreamStops = dep.get("downstreamStops");
        assertTrue(downstreamStops.isArray(), "downstreamStops must be an array");

        // fixture: stopTimes order after LI_102 is LI_103 (Merillon Avenue), LI_104 (New Hyde Park), LI_105 (Penn Station)
        assertEquals(3, downstreamStops.size());
        assertEquals("Merillon Avenue", downstreamStops.get(0).asText());
        assertEquals("New Hyde Park", downstreamStops.get(1).asText());
        assertEquals("Penn Station", downstreamStops.get(2).asText());
    }

    @Test
    void lirrDestinationsAreSortedAndDeduplicated() throws Exception {
        HttpResponse<String> resp = get("/api/schedule?stop=LI_102&date=2026-06-16");

        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        JsonNode destinations = body.get("destinations");
        assertTrue(destinations.isArray());

        List<String> destList = new ArrayList<>();
        for (JsonNode n : destinations) destList.add(n.asText());

        // Must be sorted
        List<String> sorted = new ArrayList<>(destList);
        sorted.sort(String::compareTo);
        assertEquals(sorted, destList, "destinations must be alphabetically sorted");

        // Must be deduplicated
        long distinctCount = destList.stream().distinct().count();
        assertEquals(destList.size(), distinctCount, "destinations must not contain duplicates");
    }

    @Test
    void tripScheduleFailureDoesNotBreakResponse() throws Exception {
        // Use a mock server that returns 500 for trip schedule
        HttpServer failServer = HttpServer.create(new InetSocketAddress(0), 0);
        HttpServer failApiServer = null;
        try {
            String lirrScheduleJson = loadFixture("fixtures/lirr-schedule-response.json");
            String lirrStopJson = loadFixture("fixtures/lirr-stop-response.json");
            String lirrTripJson = loadFixture("fixtures/lirr-trip-001-response.json");
            String lirrAgencyJson = loadFixture("fixtures/agency-li-response.json");

            failServer.createContext("/api/where/schedule-for-stop/LI_102.json", exchange -> {
                byte[] body = lirrScheduleJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            failServer.createContext("/api/where/stop/LI_102.json", exchange -> {
                byte[] body = lirrStopJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            failServer.createContext("/api/where/trip/LI_trip_001.json", exchange -> {
                byte[] body = lirrTripJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            failServer.createContext("/api/where/trip-details/LI_trip_001.json", exchange -> {
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
            });
            failServer.createContext("/api/where/agency/LI.json", exchange -> {
                byte[] body = lirrAgencyJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            failServer.start();

            failApiServer = ApiServer.create(0, "http://localhost:" + failServer.getAddress().getPort());
            failApiServer.start();
            int failApiPort = failApiServer.getAddress().getPort();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + failApiPort + "/api/schedule?stop=LI_102&date=2026-06-16"))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode(), "Request must succeed even when trip schedule fails");

            JsonNode body = mapper.readTree(resp.body());
            JsonNode departures = body.get("departures");
            assertTrue(departures.isArray());
            assertTrue(departures.size() > 0);

            JsonNode dep = departures.get(0);
            assertTrue(dep.has("downstreamStops"), "Departure must have downstreamStops field");
            JsonNode downstreamStops = dep.get("downstreamStops");
            assertTrue(downstreamStops.isArray());
            assertEquals(0, downstreamStops.size(), "downstreamStops must be empty when trip schedule fails");
        } finally {
            if (failApiServer != null) failApiServer.stop(0);
            failServer.stop(0);
        }
    }
}
