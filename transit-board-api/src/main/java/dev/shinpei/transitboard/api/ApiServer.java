package dev.shinpei.transitboard.api;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

public class ApiServer {

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "4000"));
        String obaBaseUrl = System.getenv().getOrDefault("OBA_BASE_URL", "http://oba-app:8080");

        HttpServer server = create(port, obaBaseUrl);
        server.start();
        System.out.println("transit-board-api listening on port " + server.getAddress().getPort());
    }

    /**
     * Creates and configures the HTTP server without starting it.
     *
     * @param port       port to listen on (0 = OS-assigned ephemeral port, useful for tests)
     * @param obaBaseUrl base URL of the OBA REST API
     * @return configured but not yet started HttpServer
     */
    public static HttpServer create(int port, String obaBaseUrl) throws IOException {
        ObaClient obaClient = new ObaClient(obaBaseUrl);
        ScheduleApiHandler handler = new ScheduleApiHandler(obaClient);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/schedule", handler);
        return server;
    }
}
