package dev.shinpei.transitboard.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ObaAgencyResponse {
    public DataWrapper data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DataWrapper {
        public AgencyEntry entry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgencyEntry {
        public String timezone;
    }
}
