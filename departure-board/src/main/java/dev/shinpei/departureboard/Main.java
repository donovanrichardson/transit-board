package dev.shinpei.departureboard;

import dev.shinpei.departureboard.model.Departure;
import dev.shinpei.departureboard.model.ObaResponse;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

public class Main {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";

    public static void main(String[] args) {
        String stopId = null;
        String baseUrl = null;

        for (int i = 0; i < args.length; i++) {
            if ("--base-url".equals(args[i])) {
                if (i + 1 >= args.length) {
                    printUsage();
                    System.exit(1);
                }
                baseUrl = args[++i];
            } else if (stopId == null) {
                stopId = args[i];
            }
        }

        if (stopId == null) {
            printUsage();
            System.exit(1);
        }

        if (baseUrl == null) {
            String envUrl = System.getenv("OBA_BASE_URL");
            baseUrl = (envUrl != null && !envUrl.isEmpty()) ? envUrl : DEFAULT_BASE_URL;
        }

        ObaClient client = new ObaClient(baseUrl);
        ScheduleParser parser = new ScheduleParser();
        DeparturePrinter printer = new DeparturePrinter();

        try {
            LocalDate today = LocalDate.now();
            ObaResponse response = client.fetchSchedule(stopId, today);

            String tzName = response.data.entry.timeZone;
            ZoneId stopZone = (tzName != null && !tzName.isEmpty())
                    ? ZoneId.of(tzName)
                    : ZoneId.systemDefault();

            LocalDate todayInStopZone = LocalDate.now(stopZone);

            Optional<LocalDate> fallbackDate = parser.findDateToUse(response, stopZone, todayInStopZone);

            if (fallbackDate.isPresent()) {
                response = client.fetchSchedule(stopId, fallbackDate.get());
            }

            List<Departure> departures = parser.parse(response);

            if (departures.isEmpty()) {
                System.err.println("No departures found for stop " + stopId);
                System.exit(2);
            }

            printer.print(departures, stopZone, System.out);

        } catch (ScheduleParser.NoServiceException e) {
            System.err.println("No scheduled service found for stop " + stopId);
            System.exit(2);
        } catch (ObaClient.ObaClientException e) {
            System.err.println(e.getMessage());
            System.exit(e.getExitCode());
        }
    }

    private static void printUsage() {
        System.err.println("Usage: departure-board <stopId> [--base-url <url>]");
    }
}
