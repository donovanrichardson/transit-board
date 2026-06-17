package dev.shinpei.transitboard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.shinpei.transitboard.model.Departure;
import dev.shinpei.transitboard.model.ObaResponse;
import dev.shinpei.transitboard.model.ObaStopResponse;
import dev.shinpei.transitboard.model.ObaTripResponse;
import dev.shinpei.transitboard.model.ObaTripScheduleResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class ScheduleApiHandler implements HttpHandler {

    private final ObaClient obaClient;
    private final ScheduleParser scheduleParser;
    private final ObjectMapper objectMapper;

    public ScheduleApiHandler(ObaClient obaClient) {
        this.obaClient = obaClient;
        this.scheduleParser = new ScheduleParser();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        System.out.println(method + " " + exchange.getRequestURI());

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

        String stopId = params.get("stop");
        String dateStr = params.get("date");

        if (stopId == null || stopId.isEmpty()) {
            sendError(exchange, 400, "Missing required parameter: stop");
            return;
        }

        if (dateStr == null || dateStr.isEmpty()) {
            sendError(exchange, 400, "Missing required parameter: date");
            return;
        }

        LocalDate scheduleDate;
        try {
            scheduleDate = LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            sendError(exchange, 400, "Invalid date format. Use YYYY-MM-DD");
            return;
        }

        try {
            ScheduleResponse response = buildResponse(stopId, scheduleDate);
            String json = objectMapper.writeValueAsString(response);
            sendResponse(exchange, 200, json);
        } catch (ObaClient.ObaNotFoundException e) {
            sendError(exchange, 404, "Stop not found");
        } catch (ObaClient.ObaClientException e) {
            sendError(exchange, 502, "Could not reach OBA API: " + e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 502, "Internal error: " + e.getMessage());
        }
    }

    private ScheduleResponse buildResponse(String stopId, LocalDate scheduleDate) {
        // Fetch schedule
        ObaResponse scheduleOba = obaClient.fetchSchedule(stopId, scheduleDate);

        String timeZone = scheduleOba.data.entry.timeZone;
        ZoneId zone = ZoneId.of(timeZone != null ? timeZone : "UTC");

        // Fetch stop metadata
        ObaStopResponse stopOba = obaClient.fetchStop(stopId);
        ObaStopResponse.StopEntry stopEntry = stopOba.data != null ? stopOba.data.entry : null;

        String parentId = (stopEntry != null && stopEntry.parent != null && !stopEntry.parent.isEmpty())
                ? stopEntry.parent : null;
        String stopName = (stopEntry != null && stopEntry.name != null) ? stopEntry.name : stopId;
        String direction = (stopEntry != null) ? stopEntry.direction : null;

        // Resolve siblings by fetching parent
        List<String> siblingIds;
        if (parentId != null) {
            ObaStopResponse parentOba = obaClient.fetchStop(parentId);
            List<ObaResponse.Stop> parentStops = new ArrayList<>();
            if (parentOba.data != null && parentOba.data.references != null
                    && parentOba.data.references.stops != null) {
                parentStops.addAll(parentOba.data.references.stops);
            }
            siblingIds = SiblingResolver.resolve(stopId, parentId, parentStops);
        } else {
            siblingIds = List.of();
        }

        // Parse departures
        List<Departure> departures = scheduleParser.parse(scheduleOba);

        // Resolve directionId per unique tripId (cache per request)
        Map<String, String> tripDirectionCache = new HashMap<>();
        for (Departure d : departures) {
            String tripId = d.getTripId();
            if (tripId != null && !tripId.isEmpty()) {
                String directionId = tripDirectionCache.computeIfAbsent(tripId, tid -> {
                    try {
                        ObaTripResponse tripResponse = obaClient.fetchTrip(tid);
                        if (tripResponse != null && tripResponse.data != null
                                && tripResponse.data.entry != null) {
                            return tripResponse.data.entry.directionId;
                        }
                    } catch (Exception ignored) {
                    }
                    return null;
                });
                d.setDirectionId(directionId);
            }
        }

        // Resolve downstream stops per unique tripId (cache per request)
        Map<String, List<String>> tripDownstreamCache = new HashMap<>();
        for (Departure d : departures) {
            String tripId = d.getTripId();
            if (tripId != null && !tripId.isEmpty()) {
                tripDownstreamCache.computeIfAbsent(tripId, tid -> {
                    try {
                        ObaTripScheduleResponse schedResp = obaClient.fetchTripSchedule(tid);
                        if (schedResp == null || schedResp.data == null
                                || schedResp.data.entry == null
                                || schedResp.data.entry.schedule == null
                                || schedResp.data.entry.schedule.stopTimes == null) {
                            return Collections.emptyList();
                        }
                        List<ObaTripScheduleResponse.StopTime> stopTimes = schedResp.data.entry.schedule.stopTimes;
                        Map<String, String> stopNameById = new HashMap<>();
                        if (schedResp.data.references != null && schedResp.data.references.stops != null) {
                            for (ObaResponse.Stop s : schedResp.data.references.stops) {
                                stopNameById.put(s.id, s.name);
                            }
                        }
                        int queriedIndex = -1;
                        for (int i = 0; i < stopTimes.size(); i++) {
                            if (stopId.equals(stopTimes.get(i).stopId)) {
                                queriedIndex = i;
                                break;
                            }
                        }
                        if (queriedIndex < 0) {
                            return Collections.emptyList();
                        }
                        List<String> downstream = new ArrayList<>();
                        for (int i = queriedIndex + 1; i < stopTimes.size(); i++) {
                            String name = stopNameById.get(stopTimes.get(i).stopId);
                            if (name != null) downstream.add(name);
                        }
                        return downstream;
                    } catch (Exception ignored) {
                        return Collections.emptyList();
                    }
                });
            }
        }

        // Build routes list (deduplicated, preserving order)
        Map<String, ObaResponse.Route> routeById = new LinkedHashMap<>();
        if (scheduleOba.data.references != null && scheduleOba.data.references.routes != null) {
            for (ObaResponse.Route r : scheduleOba.data.references.routes) {
                routeById.put(r.id, r);
            }
        }
        List<ScheduleResponse.RouteInfo> routes = routeById.values().stream().map(r -> {
            ScheduleResponse.RouteInfo ri = new ScheduleResponse.RouteInfo();
            ri.id = r.id;
            ri.shortName = r.shortName;
            ri.color = r.color;
            ri.textColor = r.textColor;
            return ri;
        }).collect(Collectors.toList());

        // Build headsigns (sorted alphabetically, deduplicated)
        TreeSet<String> headsignSet = new TreeSet<>();
        for (Departure d : departures) {
            if (d.getHeadsign() != null) headsignSet.add(d.getHeadsign());
        }
        List<String> headsigns = new ArrayList<>(headsignSet);

        // Build departure list
        List<ScheduleResponse.DepartureInfo> departureInfos = new ArrayList<>();
        for (Departure d : departures) {
            int[] hourMinute = HourCalculator.compute(d.getDepartureTime().toEpochMilli(), scheduleDate, zone);
            ScheduleResponse.DepartureInfo di = new ScheduleResponse.DepartureInfo();
            di.departureEpochMs = d.getDepartureTime().toEpochMilli();
            di.hour = hourMinute[0];
            di.minute = hourMinute[1];
            di.routeId = d.getRouteId();
            di.routeShortName = d.getRouteShortName();
            di.routeColor = d.getRouteColor();
            di.routeTextColor = d.getRouteTextColor();
            di.headsign = d.getHeadsign();
            di.tripId = d.getTripId();
            di.directionId = d.getDirectionId();
            di.downstreamStops = tripDownstreamCache.getOrDefault(d.getTripId(), Collections.emptyList());
            departureInfos.add(di);
        }

        // Build destinations (sorted, deduplicated set of all downstream stop names)
        TreeSet<String> destinationSet = new TreeSet<>();
        for (List<String> stops : tripDownstreamCache.values()) {
            destinationSet.addAll(stops);
        }
        List<String> destinations = new ArrayList<>(destinationSet);

        // Build stop info
        ScheduleResponse.StopInfo stopInfo = new ScheduleResponse.StopInfo();
        stopInfo.id = stopId;
        stopInfo.name = stopName;
        stopInfo.direction = direction;
        stopInfo.parentId = parentId;
        stopInfo.siblingStopIds = siblingIds;

        ScheduleResponse response = new ScheduleResponse();
        response.stop = stopInfo;
        response.date = scheduleDate.toString();
        response.timeZone = timeZone;
        response.routes = routes;
        response.headsigns = headsigns;
        response.destinations = destinations;
        response.departures = departureInfos;
        response.agencyColor = null;
        response.headsignAbbreviations = Collections.emptyMap();

        return response;
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
