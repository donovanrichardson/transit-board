package dev.shinpei.transitboard.api;

import dev.shinpei.transitboard.model.Departure;
import dev.shinpei.transitboard.model.ObaResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleParserTest {

    @Test
    void extractsTripId() {
        ObaResponse response = new ObaResponse();
        response.data = new ObaResponse.DataWrapper();
        response.data.entry = new ObaResponse.Entry();

        ObaResponse.StopRouteSchedule srs = new ObaResponse.StopRouteSchedule();
        srs.routeId = "LI_1";

        ObaResponse.StopRouteDirectionSchedule srds = new ObaResponse.StopRouteDirectionSchedule();
        srds.tripHeadsign = "Penn Station";

        ObaResponse.ScheduleStopTime sst = new ObaResponse.ScheduleStopTime();
        sst.departureTime = 1718370720000L;
        sst.departureEnabled = true;
        sst.stopHeadsign = "";
        sst.tripId = "LI_trip_001";

        srds.scheduleStopTimes = List.of(sst);
        srs.stopRouteDirectionSchedules = List.of(srds);
        response.data.entry.stopRouteSchedules = List.of(srs);
        response.data.references = new ObaResponse.References();
        response.data.references.routes = List.of();

        ScheduleParser parser = new ScheduleParser();
        List<Departure> departures = parser.parse(response);

        assertEquals(1, departures.size());
        assertEquals("LI_trip_001", departures.get(0).getTripId());
    }
}
