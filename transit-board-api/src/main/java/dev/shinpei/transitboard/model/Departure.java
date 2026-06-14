package dev.shinpei.transitboard.model;

import java.time.Instant;

public class Departure {
    private final String routeId;
    private final String routeShortName;
    private final String headsign;
    private final Instant departureTime;
    private final String routeColor;
    private final String routeTextColor;

    public Departure(String routeId, String routeShortName, String headsign,
                     Instant departureTime, String routeColor, String routeTextColor) {
        this.routeId = routeId;
        this.routeShortName = routeShortName;
        this.headsign = headsign;
        this.departureTime = departureTime;
        this.routeColor = routeColor;
        this.routeTextColor = routeTextColor;
    }

    public String getRouteId() {
        return routeId;
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

    public String getRouteColor() {
        return routeColor;
    }

    public String getRouteTextColor() {
        return routeTextColor;
    }
}
