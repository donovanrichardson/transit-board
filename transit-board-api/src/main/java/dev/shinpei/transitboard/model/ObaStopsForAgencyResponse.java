package dev.shinpei.transitboard.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ObaStopsForAgencyResponse {
    public int code;
    public Data data;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        public List<ObaResponse.Stop> list;
    }
}
