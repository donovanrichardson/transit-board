package dev.shinpei.transitboard.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ObaStopResponse {
    public DataWrapper data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataWrapper {
        public StopEntry entry;
        public References references;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StopEntry {
        public String id;
        public String name;
        public String direction;
        public String parent;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class References {
        public List<ObaResponse.Route> routes;
        public List<ObaResponse.Stop> stops;
    }
}
