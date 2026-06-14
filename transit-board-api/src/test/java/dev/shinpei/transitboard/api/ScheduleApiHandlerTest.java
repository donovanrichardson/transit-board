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

import static org.junit.jupiter.api.Assertions.*;

class ScheduleApiHandlerTest {

    private HttpServer mockObaServer;
    private HttpServer apiServer;
    private int obaPort;
    private int apiPort;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        // Start mock OBA server
        mockObaServer = HttpServer.create(new InetSocketAddress(0), 0);
        obaPort = mockObaServer.getAddress().getPort();

        String scheduleJson = loadFixture("fixtures/schedule-response.json");
        String stopJson = loadFixture("fixtures/stop-response.json");
        String parentStopJson = loadFixture("fixtures/parent-stop-response.json");

        mockObaServer.createContext("/api/where/schedule-for-stop/", exchange -> {
            byte[] body = scheduleJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/stop/MTA_NYCT_725S.json", exchange -> {
            byte[] body = stopJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.createContext("/api/where/stop/MTA_NYCT_725.json", exchange -> {
            byte[] body = parentStopJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        mockObaServer.start();

        // Start the API server
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
    void validRequest() throws Exception {
        HttpResponse<String> resp = get("/api/schedule?stop=MTA_NYCT_725S&date=2026-06-14");

        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("stop"), "Response must have 'stop' field");
        assertTrue(body.has("routes"), "Response must have 'routes' field");
        assertTrue(body.has("headsigns"), "Response must have 'headsigns' field");
        assertTrue(body.has("departures"), "Response must have 'departures' field");
        assertTrue(body.has("agencyColor"), "Response must have 'agencyColor' field");

        // Validate stop fields
        JsonNode stop = body.get("stop");
        assertEquals("MTA_NYCT_725S", stop.get("id").asText());
        assertEquals("Times Sq-42 St", stop.get("name").asText());

        // Validate routes include color fields
        JsonNode routes = body.get("routes");
        assertTrue(routes.isArray());
        assertTrue(routes.size() > 0);
        JsonNode route = routes.get(0);
        assertTrue(route.has("color"), "Route must have color field");
        assertTrue(route.has("textColor"), "Route must have textColor field");

        // Validate departures
        JsonNode departures = body.get("departures");
        assertTrue(departures.isArray());
        assertTrue(departures.size() > 0);
        JsonNode dep = departures.get(0);
        assertTrue(dep.has("hour"), "Departure must have hour");
        assertTrue(dep.has("minute"), "Departure must have minute");
        assertTrue(dep.has("routeId"), "Departure must have routeId");
        assertTrue(dep.has("routeColor"), "Departure must have routeColor");
        assertTrue(dep.has("routeTextColor"), "Departure must have routeTextColor");

        // Validate CORS header
        assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

        // Validate Content-Type
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.contains("application/json"), "Content-Type must be application/json");
    }

    @Test
    void missingStopParam() throws Exception {
        HttpResponse<String> resp = get("/api/schedule?date=2026-06-14");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void missingDateParam() throws Exception {
        HttpResponse<String> resp = get("/api/schedule?stop=MTA_NYCT_725S");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void invalidDateFormat() throws Exception {
        HttpResponse<String> resp = get("/api/schedule?stop=MTA_NYCT_725S&date=06-14-2026");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void obaServerDown() throws Exception {
        // Stop the mock OBA server first
        mockObaServer.stop(0);
        mockObaServer = null;

        HttpResponse<String> resp = get("/api/schedule?stop=MTA_NYCT_725S&date=2026-06-14");
        assertEquals(502, resp.statusCode());
    }
}
