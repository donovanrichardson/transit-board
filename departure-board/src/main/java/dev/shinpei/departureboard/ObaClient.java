package dev.shinpei.departureboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shinpei.departureboard.model.ObaResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class ObaClient {

    // TODO: Make API key configurable via CLI flag or environment variable
    private static final String API_KEY = "TEST";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ObaClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public ObaResponse fetchSchedule(String stopId, LocalDate date) {
        String url = baseUrl + "/api/where/schedule-for-stop/" + stopId + ".json"
                + "?key=" + API_KEY + "&date=" + DATE_FMT.format(date);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            throw new ObaClientException("Cannot connect to OBA server at " + baseUrl + ": " + e.getMessage(), 3);
        } catch (IOException e) {
            throw new ObaClientException("Cannot connect to OBA server at " + baseUrl + ": " + e.getMessage(), 3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ObaClientException("Cannot connect to OBA server at " + baseUrl + ": interrupted", 3);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ObaClientException("OBA API error: HTTP " + response.statusCode(), 3);
        }

        try {
            return objectMapper.readValue(response.body(), ObaResponse.class);
        } catch (IOException e) {
            throw new ObaClientException("Failed to parse OBA response: " + e.getMessage(), 3);
        }
    }

    public static class ObaClientException extends RuntimeException {
        private final int exitCode;

        public ObaClientException(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }

        public int getExitCode() {
            return exitCode;
        }
    }
}
