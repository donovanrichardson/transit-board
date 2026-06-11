package dev.shinpei.departureboard.model;

import java.time.Instant;

public class Departure {
    private final String routeShortName;
    private final String headsign;
    private final Instant departureTime;

    public Departure(String routeShortName, String headsign, Instant departureTime) {
        this.routeShortName = routeShortName;
        this.headsign = headsign;
        this.departureTime = departureTime;
    }

    public String getRouteShortName() {
        return routeShortName;
    }

    public String getHeadsign() {
        return headsign;
    }

    public Instant getDepartureTime() {
        return departureTime;
    }
}
