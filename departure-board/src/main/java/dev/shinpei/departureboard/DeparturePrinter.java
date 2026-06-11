package dev.shinpei.departureboard;

import dev.shinpei.departureboard.model.Departure;

import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class DeparturePrinter {

    private static final String COL_ROUTE = "Route";
    private static final String COL_HEADSIGN = "Headsign";
    private static final String COL_DEPARTS = "Departs";
    private static final String SEPARATOR = "   ";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public void print(List<Departure> departures, ZoneId stopZone, PrintStream out) {
        if (departures.isEmpty()) {
            return;
        }

        int routeWidth = COL_ROUTE.length();
        int headsignWidth = COL_HEADSIGN.length();

        for (Departure d : departures) {
            routeWidth = Math.max(routeWidth, d.getRouteShortName().length());
            headsignWidth = Math.max(headsignWidth, d.getHeadsign() != null ? d.getHeadsign().length() : 0);
        }

        String headerLine = pad(COL_ROUTE, routeWidth) + SEPARATOR
                + pad(COL_HEADSIGN, headsignWidth) + SEPARATOR
                + COL_DEPARTS;
        out.println(headerLine);

        for (Departure d : departures) {
            String departs = TIME_FMT.format(d.getDepartureTime().atZone(stopZone));
            String headsign = d.getHeadsign() != null ? d.getHeadsign() : "";
            String line = pad(d.getRouteShortName(), routeWidth) + SEPARATOR
                    + pad(headsign, headsignWidth) + SEPARATOR
                    + departs;
            out.println(line);
        }
    }

    private String pad(String value, int width) {
        if (value == null) value = "";
        if (value.length() >= width) return value;
        return value + " ".repeat(width - value.length());
    }
}
