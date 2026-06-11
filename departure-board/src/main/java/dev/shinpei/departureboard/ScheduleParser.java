package dev.shinpei.departureboard;

import dev.shinpei.departureboard.model.Departure;
import dev.shinpei.departureboard.model.ObaResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ScheduleParser {

    /**
     * Returns null if today is in stopCalendarDays and has departures,
     * otherwise returns the most recent past date to use for a second request,
     * or throws if no fallback date exists.
     *
     * Returns Optional.empty() to signal "use current response".
     * Returns Optional.of(localDate) to signal "fetch this date instead".
     * Throws NoServiceException if no dates at all.
     */
    public Optional<LocalDate> findDateToUse(ObaResponse response, ZoneId stopZone, LocalDate today) {
        List<ObaResponse.StopCalendarDay> calendarDays =
                response.data.entry.stopCalendarDays;

        if (calendarDays == null || calendarDays.isEmpty()) {
            throw new NoServiceException("No scheduled service");
        }

        List<LocalDate> dates = new ArrayList<>();
        for (ObaResponse.StopCalendarDay day : calendarDays) {
            dates.add(Instant.ofEpochMilli(day.date).atZone(stopZone).toLocalDate());
        }

        boolean todayInList = dates.contains(today);
        if (todayInList) {
            // Check if there are any departure-enabled times for today
            boolean hasDepartures = hasDeparturesEnabled(response);
            if (hasDepartures) {
                return Optional.empty(); // use current response
            }
        }

        // Find most recent past date
        Optional<LocalDate> fallback = dates.stream()
                .filter(d -> d.isBefore(today))
                .max(Comparator.naturalOrder());

        if (fallback.isPresent()) {
            return fallback;
        }

        throw new NoServiceException("No scheduled service");
    }

    private boolean hasDeparturesEnabled(ObaResponse response) {
        if (response.data.entry.stopRouteSchedules == null) {
            return false;
        }
        for (ObaResponse.StopRouteSchedule srs : response.data.entry.stopRouteSchedules) {
            if (srs.stopRouteDirectionSchedules == null) continue;
            for (ObaResponse.StopRouteDirectionSchedule srds : srs.stopRouteDirectionSchedules) {
                if (srds.scheduleStopTimes == null) continue;
                for (ObaResponse.ScheduleStopTime sst : srds.scheduleStopTimes) {
                    if (sst.departureEnabled) return true;
                }
            }
        }
        return false;
    }

    public List<Departure> parse(ObaResponse response) {
        Map<String, String> routeIdToShortName = buildRouteMap(response);

        List<Departure> departures = new ArrayList<>();

        if (response.data.entry.stopRouteSchedules == null) {
            return departures;
        }

        for (ObaResponse.StopRouteSchedule srs : response.data.entry.stopRouteSchedules) {
            String routeShortName = resolveRouteName(srs.routeId, routeIdToShortName);

            if (srs.stopRouteDirectionSchedules == null) continue;
            for (ObaResponse.StopRouteDirectionSchedule srds : srs.stopRouteDirectionSchedules) {
                String tripHeadsign = srds.tripHeadsign;

                if (srds.scheduleStopTimes == null) continue;
                for (ObaResponse.ScheduleStopTime sst : srds.scheduleStopTimes) {
                    if (!sst.departureEnabled) continue;

                    String headsign = (sst.stopHeadsign != null && !sst.stopHeadsign.isEmpty())
                            ? sst.stopHeadsign
                            : tripHeadsign;

                    Instant departureTime = Instant.ofEpochMilli(sst.departureTime);
                    departures.add(new Departure(routeShortName, headsign, departureTime));
                }
            }
        }

        departures.sort(Comparator.comparing(Departure::getDepartureTime));
        return departures;
    }

    private Map<String, String> buildRouteMap(ObaResponse response) {
        Map<String, String> map = new HashMap<>();
        if (response.data.references != null && response.data.references.routes != null) {
            for (ObaResponse.Route route : response.data.references.routes) {
                map.put(route.id, route.shortName);
            }
        }
        return map;
    }

    private String resolveRouteName(String routeId, Map<String, String> routeMap) {
        String shortName = routeMap.get(routeId);
        if (shortName == null || shortName.isEmpty()) {
            return routeId;
        }
        return shortName;
    }

    public static class NoServiceException extends RuntimeException {
        public NoServiceException(String message) {
            super(message);
        }
    }
}
