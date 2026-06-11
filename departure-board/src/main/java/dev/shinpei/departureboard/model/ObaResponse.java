package dev.shinpei.departureboard.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ObaResponse {
    public DataWrapper data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataWrapper {
        public Entry entry;
        public References references;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        public String timeZone;
        public List<StopCalendarDay> stopCalendarDays;
        public List<StopRouteSchedule> stopRouteSchedules;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StopCalendarDay {
        public long date;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StopRouteSchedule {
        public String routeId;
        public List<StopRouteDirectionSchedule> stopRouteDirectionSchedules;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StopRouteDirectionSchedule {
        public String tripHeadsign;
        public List<ScheduleStopTime> scheduleStopTimes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScheduleStopTime {
        public long departureTime;
        public boolean departureEnabled;
        public String stopHeadsign;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class References {
        public List<Route> routes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Route {
        public String id;
        public String shortName;
    }
}
