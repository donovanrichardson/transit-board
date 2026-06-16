package dev.shinpei.transitboard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.shinpei.transitboard.model.ObaResponse;
import dev.shinpei.transitboard.model.ObaStopsForAgencyResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StopsApiHandler implements HttpHandler {

    private final ObaClient obaClient;
    private final ObjectMapper objectMapper;

    public StopsApiHandler(ObaClient obaClient) {
        this.obaClient = obaClient;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Content-Type", "application/json");

        if ("OPTIONS".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"GET".equals(method)) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        Map<String, String> params = parseQuery(exchange.getRequestURI());
        String agencyId = params.get("agency");

        if (agencyId == null || agencyId.isEmpty()) {
            sendError(exchange, 400, "Missing required parameter: agency");
            return;
        }

        try {
            ObaStopsForAgencyResponse obaResp = obaClient.fetchStopsForAgency(agencyId);

            // Deduplicate by name, keeping first occurrence; then sort alphabetically
            Map<String, ObaResponse.Stop> byName = new LinkedHashMap<>();
            if (obaResp.data != null && obaResp.data.list != null) {
                List<ObaResponse.Stop> sorted = new ArrayList<>(obaResp.data.list);
                sorted.sort(Comparator.comparing(s -> s.name != null ? s.name : ""));
                for (ObaResponse.Stop stop : sorted) {
                    if (stop.name != null && !byName.containsKey(stop.name)) {
                        byName.put(stop.name, stop);
                    }
                }
            }

            List<Map<String, String>> stopList = new ArrayList<>();
            for (ObaResponse.Stop stop : byName.values()) {
                Map<String, String> entry = new HashMap<>();
                entry.put("id", stop.id);
                entry.put("name", stop.name);
                entry.put("stopCode", stop.stopCode);
                stopList.add(entry);
            }

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("stops", stopList);

            String json = objectMapper.writeValueAsString(responseBody);
            sendResponse(exchange, 200, json);
        } catch (ObaClient.ObaClientException e) {
            sendError(exchange, 502, "Could not reach OBA API: " + e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 502, "Internal error: " + e.getMessage());
        }
    }

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null) return params;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            } else if (kv.length == 1) {
                params.put(kv[0], "");
            }
        }
        return params;
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        String json = "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        sendResponse(exchange, status, json);
    }

    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
