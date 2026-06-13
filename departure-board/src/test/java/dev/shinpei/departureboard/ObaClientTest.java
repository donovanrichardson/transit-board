package dev.shinpei.departureboard;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ObaClientTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> capturedPath = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            capturedPath.set(exchange.getRequestURI().getRawPath());
            byte[] body = "null".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void stopIdWithSpaceIsPercentEncoded() {
        ObaClient client = new ObaClient("http://localhost:" + port);
        try {
            client.fetchSchedule("MTA NYCT_D17S", LocalDate.of(2026, 6, 13));
        } catch (ObaClient.ObaClientException e) {
            // null body → ObaClientException("OBA server returned no data") — expected in test
        }
        String path = capturedPath.get();
        assertNotNull(path, "No request was captured");
        assertFalse(path.contains(" "), "Path must not contain a literal space");
        assertTrue(path.contains("%20"), "Space must be encoded as %20 in path");
    }

    @Test
    void stopIdWithoutSpaceIsUnchanged() {
        ObaClient client = new ObaClient("http://localhost:" + port);
        try {
            client.fetchSchedule("137N", LocalDate.of(2026, 6, 13));
        } catch (ObaClient.ObaClientException e) {
            // null body — expected
        }
        String path = capturedPath.get();
        assertNotNull(path);
        assertTrue(path.contains("137N"), "Plain stop ID must appear unchanged in path");
    }
}
