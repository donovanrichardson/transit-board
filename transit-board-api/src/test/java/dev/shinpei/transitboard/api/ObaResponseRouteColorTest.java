package dev.shinpei.transitboard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shinpei.transitboard.model.ObaResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObaResponseRouteColorTest {

    @Test
    void parsesColorFields() throws Exception {
        String json = """
                {
                  "data": {
                    "entry": {
                      "timeZone": "America/New_York",
                      "stopCalendarDays": [],
                      "stopRouteSchedules": []
                    },
                    "references": {
                      "routes": [
                        {
                          "id": "MTA_NYCT_7",
                          "shortName": "7",
                          "color": "B933AD",
                          "textColor": "FFFFFF"
                        }
                      ],
                      "stops": []
                    }
                  }
                }
                """;

        ObjectMapper mapper = new ObjectMapper();
        ObaResponse response = mapper.readValue(json, ObaResponse.class);

        assertNotNull(response.data);
        assertNotNull(response.data.references);
        assertNotNull(response.data.references.routes);
        assertEquals(1, response.data.references.routes.size());

        ObaResponse.Route route = response.data.references.routes.get(0);
        assertEquals("MTA_NYCT_7", route.id);
        assertEquals("7", route.shortName);
        assertEquals("B933AD", route.color);
        assertEquals("FFFFFF", route.textColor);
    }
}
