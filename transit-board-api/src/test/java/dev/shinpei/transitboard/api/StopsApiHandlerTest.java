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

class StopsApiHandlerTest {

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

        String stopsJson = loadFixture("fixtures/lirr-stops-for-agency-response.json");

        mockObaServer.createContext("/api/where/stops-for-agency/LI.json", exchange -> {
            byte[] body = stopsJson.getBytes(StandardCharsets.UTF_8);
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
    void returnsLirrStops() throws Exception {
        HttpResponse<String> resp = get("/api/stops?agency=LI");

        assertEquals(200, resp.statusCode());

        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.has("stops"), "Response must have 'stops' field");
        JsonNode stops = body.get("stops");
        assertTrue(stops.isArray());

        // Deduplicated by name: Jamaica appears twice but should be once
        // Sorted alphabetically: Babylon, Jamaica, Penn Station
        assertEquals(3, stops.size(), "Should be 3 unique stops by name");

        assertEquals("Babylon", stops.get(0).get("name").asText());
        assertEquals("BAB", stops.get(0).get("stopCode").asText());

        assertEquals("Jamaica", stops.get(1).get("name").asText());
        assertEquals("JAM", stops.get(1).get("stopCode").asText());

        assertEquals("Penn Station", stops.get(2).get("name").asText());
        assertEquals("NYP", stops.get(2).get("stopCode").asText());
    }

    @Test
    void missingAgencyParam() throws Exception {
        HttpResponse<String> resp = get("/api/stops");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void obaDown() throws Exception {
        mockObaServer.stop(0);
        mockObaServer = null;

        HttpResponse<String> resp = get("/api/stops?agency=LI");
        assertEquals(502, resp.statusCode());
    }
}
