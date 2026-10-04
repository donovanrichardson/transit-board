package dev.shinpei.transitboard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shinpei.transitboard.model.ObaAgencyResponse;
import dev.shinpei.transitboard.model.ObaResponse;
import dev.shinpei.transitboard.model.ObaStopResponse;
import dev.shinpei.transitboard.model.ObaStopsForAgencyResponse;
import dev.shinpei.transitboard.model.ObaTripResponse;
import dev.shinpei.transitboard.model.ObaTripScheduleResponse;

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

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ObaClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.apiKey = System.getenv().getOrDefault("OBA_API_KEY", "TEST");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public ObaResponse fetchSchedule(String stopId, LocalDate date) {
        String url = builder()
                .path("api").path("where").path("schedule-for-stop").path(stopId)
                .query("date", DATE_FMT.format(date))
                .build();
        return fetchAs(url, ObaResponse.class);
    }

    public ObaStopResponse fetchStop(String stopId) {
        String url = builder()
                .path("api").path("where").path("stop").path(stopId)
                .build();
        return fetchAs(url, ObaStopResponse.class);
    }

    public ObaTripResponse fetchTrip(String tripId) {
        String url = builder()
                .path("api").path("where").path("trip").path(tripId)
                .build();
        return fetchAs(url, ObaTripResponse.class);
    }

    public ObaStopsForAgencyResponse fetchStopsForAgency(String agencyId) {
        String url = builder()
                .path("api").path("where").path("stops-for-agency").path(agencyId)
                .build();
        return fetchAs(url, ObaStopsForAgencyResponse.class);
    }

    public ObaTripScheduleResponse fetchTripSchedule(String tripId) {
        String url = builder()
                .path("api").path("where").path("trip-details").path(tripId)
                .build();
        return fetchAs(url, ObaTripScheduleResponse.class);
    }

    public ObaAgencyResponse fetchAgency(String agencyId) {
        String url = builder()
                .path("api").path("where").path("agency").path(agencyId)
                .build();
        return fetchAs(url, ObaAgencyResponse.class);
    }

    private ObaUrlBuilder builder() {
        return new ObaUrlBuilder(baseUrl, apiKey);
    }

    private <T> T fetchAs(String url, Class<T> type) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            throw new ObaClientException("Cannot connect to OBA server at " + baseUrl + ": " + e.getMessage());
        } catch (IOException e) {
            throw new ObaClientException("Cannot connect to OBA server at " + baseUrl + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ObaClientException("Cannot connect to OBA server at " + baseUrl + ": interrupted");
        }

        if (response.statusCode() == 404) {
            throw new ObaNotFoundException("Stop not found");
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ObaClientException("OBA API error: HTTP " + response.statusCode());
        }

        try {
            T parsed = objectMapper.readValue(response.body(), type);
            if (parsed == null) {
                throw new ObaClientException("OBA server returned no data");
            }
            return parsed;
        } catch (IOException e) {
            throw new ObaClientException("Failed to parse OBA response: " + e.getMessage());
        }
    }

    public static class ObaClientException extends RuntimeException {
        public ObaClientException(String message) {
            super(message);
        }
    }

    public static class ObaNotFoundException extends RuntimeException {
        public ObaNotFoundException(String message) {
            super(message);
        }
    }
}
