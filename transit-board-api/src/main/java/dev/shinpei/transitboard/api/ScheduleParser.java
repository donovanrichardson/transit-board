package dev.shinpei.transitboard.api;

import dev.shinpei.transitboard.model.Departure;
import dev.shinpei.transitboard.model.ObaResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScheduleParser {

    public List<Departure> parse(ObaResponse response) {
        Map<String, ObaResponse.Route> routeMap = buildRouteMap(response);

        List<Departure> departures = new ArrayList<>();

        if (response.data.entry.stopRouteSchedules == null) {
            return departures;
        }

        for (ObaResponse.StopRouteSchedule srs : response.data.entry.stopRouteSchedules) {
            ObaResponse.Route route = routeMap.get(srs.routeId);
            String routeShortName = resolveRouteName(srs.routeId, route);
            String routeColor = route != null ? route.color : null;
            String routeTextColor = route != null ? route.textColor : null;

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
                    departures.add(new Departure(srs.routeId, routeShortName, headsign,
                            departureTime, routeColor, routeTextColor, sst.tripId));
                }
            }
        }

        departures.sort(Comparator.comparing(Departure::getDepartureTime));
        return departures;
    }

    private Map<String, ObaResponse.Route> buildRouteMap(ObaResponse response) {
        Map<String, ObaResponse.Route> map = new HashMap<>();
        if (response.data.references != null && response.data.references.routes != null) {
            for (ObaResponse.Route route : response.data.references.routes) {
                map.put(route.id, route);
            }
        }
        return map;
    }

    private String resolveRouteName(String routeId, ObaResponse.Route route) {
        if (route == null || route.shortName == null || route.shortName.isEmpty()) {
            return routeId;
        }
        return route.shortName;
    }
}
