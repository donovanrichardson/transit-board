package dev.shinpei.transitboard.api;

import java.util.List;

public class ScheduleResponse {

    public StopInfo stop;
    public String date;
    public String timeZone;
    public List<RouteInfo> routes;
    public List<String> headsigns;
    public List<DepartureInfo> departures;
    public String agencyColor;

    public static class StopInfo {
        public String id;
        public String name;
        public String direction;
        public String parentId;
        public List<String> siblingStopIds;
    }

    public static class RouteInfo {
        public String id;
        public String shortName;
        public String color;
        public String textColor;
    }

    public static class DepartureInfo {
        public long departureEpochMs;
        public int hour;
        public int minute;
        public String routeId;
        public String routeShortName;
        public String routeColor;
        public String routeTextColor;
        public String headsign;
    }
}
