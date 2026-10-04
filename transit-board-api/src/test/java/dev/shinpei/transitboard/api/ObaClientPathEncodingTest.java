package dev.shinpei.transitboard.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ObaClientPathEncodingTest {

    private HttpServer mockObaServer;
    private HttpServer apiServer;
    private ObaClient obaClient;
    private int obaPort;
    private int apiPort;

    private final AtomicReference<String> lastRequestUri = new AtomicReference<>(null);
    private final AtomicBoolean requestMade = new AtomicBoolean(false);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        lastRequestUri.set(null);
        requestMade.set(false);

        mockObaServer = HttpServer.create(new InetSocketAddress(0), 0);
        obaPort = mockObaServer.getAddress().getPort();

        String scheduleJson = loadFixture("fixtures/lirr-schedule-response.json");
        String stopJson = loadFixture("fixtures/lirr-stop-response.json");
        String tripJson = loadFixture("fixtures/lirr-trip-001-response.json");
        String stopsForAgencyJson = loadFixture("fixtures/lirr-stops-for-agency-response.json");
        String tripScheduleJson = loadFixture("fixtures/lirr-trip-001-schedule-response.json");
        String agencyJson = loadFixture("fixtures/agency-li-response.json");

        mockObaServer.createContext("/api/where/schedule-for-stop/", exchange -> {
            lastRequestUri.set(exchange.getRequestURI().toString());
            requestMade.set(true);
            send(exchange, scheduleJson);
        });
        mockObaServer.createContext("/api/where/stop/", exchange -> {
            lastRequestUri.set(exchange.getRequestURI().toString());
            requestMade.set(true);
            send(exchange, stopJson);
        });
        mockObaServer.createContext("/api/where/trip/", exchange -> {
            lastRequestUri.set(exchange.getRequestURI().toString());
            requestMade.set(true);
            send(exchange, tripJson);
        });
        mockObaServer.createContext("/api/where/stops-for-agency/", exchange -> {
            lastRequestUri.set(exchange.getRequestURI().toString());
            requestMade.set(true);
            send(exchange, stopsForAgencyJson);
        });
        mockObaServer.createContext("/api/where/trip-details/", exchange -> {
            lastRequestUri.set(exchange.getRequestURI().toString());
            requestMade.set(true);
            send(exchange, tripScheduleJson);
        });
        mockObaServer.createContext("/api/where/agency/", exchange -> {
            lastRequestUri.set(exchange.getRequestURI().toString());
            requestMade.set(true);
            send(exchange, agencyJson);
        });

        mockObaServer.start();
        obaClient = new ObaClient("http://localhost:" + obaPort);

        apiServer = ApiServer.create(0, "http://localhost:" + obaPort);
        apiPort = apiServer.getAddress().getPort();
        apiServer.start();
    }

    @AfterEach
    void tearDown() {
        if (apiServer != null) apiServer.stop(0);
        if (mockObaServer != null) mockObaServer.stop(0);
    }

    // Test 7: fetchSchedule sends the exact URL the old code sent
    @Test
    void fetchScheduleSendsExactUrl() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 4);
        obaClient.fetchSchedule("1_1", date);
        assertEquals(
                "/api/where/schedule-for-stop/1_1.json?key=TEST&date=2026-10-04",
                lastRequestUri.get(),
                "fetchSchedule must send the exact pre-migration URL");
    }

    // Test 8: fetchStopsForAgency sends the exact URL the old code sent
    @Test
    void fetchStopsForAgencySendsExactUrl() throws Exception {
        obaClient.fetchStopsForAgency("LI");
        assertEquals(
                "/api/where/stops-for-agency/LI.json?key=TEST",
                lastRequestUri.get(),
                "fetchStopsForAgency must send the exact pre-migration URL");
    }

    // Test 9: fetchStop with injection throws before any HTTP call
    @Test
    void fetchStopWithInjectionThrowsBeforeHttp() {
        assertThrows(IllegalArgumentException.class,
                () -> obaClient.fetchStop("1_1?zz=1"),
                "fetchStop must throw IllegalArgumentException for injection before HTTP");
        assertFalse(requestMade.get(),
                "Mock OBA server must receive no request when the ID is invalid");
    }

    // Test 10: each of the six fetch* methods rejects one injection case
    @Test
    void allSixMethodsRejectInjection() {
        LocalDate date = LocalDate.of(2026, 10, 4);

        assertThrows(IllegalArgumentException.class,
                () -> obaClient.fetchSchedule("LI?inject", date),
                "fetchSchedule must reject '?'");

        assertThrows(IllegalArgumentException.class,
                () -> obaClient.fetchStop("LI/inject"),
                "fetchStop must reject '/'");

        assertThrows(IllegalArgumentException.class,
                () -> obaClient.fetchTrip("LI#inject"),
                "fetchTrip must reject '#'");

        assertThrows(IllegalArgumentException.class,
                () -> obaClient.fetchStopsForAgency("LI inject"),
                "fetchStopsForAgency must reject space");

        assertThrows(IllegalArgumentException.class,
                () -> obaClient.fetchTripSchedule("LI%inject"),
                "fetchTripSchedule must reject '%'");

        assertThrows(IllegalArgumentException.class,
                () -> obaClient.fetchAgency("LI;inject"),
                "fetchAgency must reject ';'");
    }

    // Test 11: ScheduleApiHandler returns 400 for stop ID containing '?'
    @Test
    void scheduleHandlerReturns400ForInjectedStop() throws Exception {
        HttpResponse<String> resp = apiGet("/api/schedule?stop=1_1%3Fzz%3D1&date=2026-10-04");
        assertEquals(400, resp.statusCode(),
                "Handler must return 400 for injected stop ID");
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("Invalid stop ID", body.get("error").asText());
        assertFalse(requestMade.get(),
                "Mock OBA server must receive no request for invalid stop ID");
    }

    // Test 12: StopsApiHandler returns 400 for agency ID containing '/'
    @Test
    void stopsHandlerReturns400ForInjectedAgency() throws Exception {
        HttpResponse<String> resp = apiGet("/api/stops?agency=LI%2F1");
        assertEquals(400, resp.statusCode(),
                "Handler must return 400 for injected agency ID");
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("Invalid agency ID", body.get("error").asText());
        assertFalse(requestMade.get(),
                "Mock OBA server must receive no request for invalid agency ID");
    }

    // --- helpers ---

    private HttpResponse<String> apiGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + apiPort + path))
                .GET()
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private void send(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String loadFixture(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Fixture not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
