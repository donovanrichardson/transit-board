package dev.shinpei.departureboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shinpei.departureboard.model.Departure;
import dev.shinpei.departureboard.model.ObaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleParserTest {

    private ScheduleParser parser;
    private ObjectMapper objectMapper;
    private ObaResponse fixtureResponse;

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    // Today in fixture: 2026-06-11 (first stopCalendarDay date in LA tz)
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 11);

    @BeforeEach
    void setUp() throws Exception {
        parser = new ScheduleParser();
        objectMapper = new ObjectMapper();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("fixtures/schedule-response.json")) {
            fixtureResponse = objectMapper.readValue(is, ObaResponse.class);
        }
    }

    @Test
    void parsesFullResponse() {
        List<Departure> departures = parser.parse(fixtureResponse);

        // Should have 5 departure-enabled entries (1 disabled filtered out)
        assertEquals(5, departures.size());

        // Sorted by departure time ascending
        for (int i = 0; i < departures.size() - 1; i++) {
            assertTrue(
                departures.get(i).getDepartureTime()
                    .compareTo(departures.get(i + 1).getDepartureTime()) <= 0,
                "Departures should be sorted ascending"
            );
        }

        // First departure: route 40, 06:12
        Departure first = departures.get(0);
        assertEquals("40", first.getRouteShortName());
        assertEquals("06:12",
            first.getDepartureTime().atZone(LA).format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
    }

    @Test
    void prefersStopHeadsignOverTripHeadsign() {
        List<Departure> departures = parser.parse(fixtureResponse);

        // Find the departure with stopHeadsign "Crown Hill" (06:45)
        Departure crownHill = departures.stream()
                .filter(d -> "Crown Hill".equals(d.getHeadsign()))
                .findFirst()
                .orElse(null);
        assertNotNull(crownHill, "Should find departure with stopHeadsign Crown Hill");
        assertEquals("Crown Hill", crownHill.getHeadsign());

        // The 06:12 departure has empty stopHeadsign, should fall back to tripHeadsign
        Departure firstDep = departures.get(0);
        assertEquals("Loyal Heights Greenwood", firstDep.getHeadsign());
    }

    @Test
    void fallsBackToRouteIdWhenNoShortName() {
        List<Departure> departures = parser.parse(fixtureResponse);

        // Route 1_NOROUTESHORTNAME has null shortName, should use routeId
        boolean found = departures.stream()
                .anyMatch(d -> "1_NOROUTESHORTNAME".equals(d.getRouteShortName()));
        assertTrue(found, "Should fall back to routeId when shortName is null");
    }

    @Test
    void filtersOutDepartureDisabled() {
        List<Departure> departures = parser.parse(fixtureResponse);

        // The fixture has one departureEnabled=false at 07:10 (1781187000000)
        // That entry should not appear in results
        long disabledEpochMs = 1781187000000L;
        boolean disabledPresent = departures.stream()
                .anyMatch(d -> d.getDepartureTime().toEpochMilli() == disabledEpochMs);
        assertFalse(disabledPresent, "departureEnabled=false entries should be excluded");

        // Total enabled departures: 5 (not 6)
        assertEquals(5, departures.size());
    }

    @Test
    void findsFallbackDate() {
        // Simulate today being 2026-06-12 (not in stopCalendarDays)
        LocalDate futureToday = LocalDate.of(2026, 6, 12);
        Optional<LocalDate> result = parser.findDateToUse(fixtureResponse, LA, futureToday);

        assertTrue(result.isPresent(), "Should return a fallback date");
        assertEquals(LocalDate.of(2026, 6, 11), result.get(),
                "Most recent past date should be 2026-06-11");
    }

    @Test
    void noCalendarDaysReturnEmpty() {
        ObaResponse emptyResponse = new ObaResponse();
        emptyResponse.data = new ObaResponse.DataWrapper();
        emptyResponse.data.entry = new ObaResponse.Entry();
        emptyResponse.data.entry.stopCalendarDays = List.of();

        Optional<LocalDate> result = parser.findDateToUse(emptyResponse, LA, TODAY);
        assertTrue(result.isEmpty(), "No calendar days → use current response (Optional.empty)");
    }
}
