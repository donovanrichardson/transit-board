package dev.shinpei.transitboard.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ObaTripResponse {
    public Data data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        public TripEntry entry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TripEntry {
        public String id;
        public String directionId;
        public String routeId;
        public String tripHeadsign;
    }
}
