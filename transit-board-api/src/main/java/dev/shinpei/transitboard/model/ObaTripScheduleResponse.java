package dev.shinpei.transitboard.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ObaTripScheduleResponse {
    public DataWrapper data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataWrapper {
        public Entry entry;
        public References references;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        public String tripId;
        public Schedule schedule;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Schedule {
        public List<StopTime> stopTimes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StopTime {
        public String stopId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class References {
        public List<ObaResponse.Stop> stops;
    }
}
