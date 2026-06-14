package dev.shinpei.transitboard.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class HourCalculator {

    /**
     * Computes the 24h+ hour and minute for a departure.
     *
     * @param epochMs      departure time in epoch milliseconds
     * @param scheduleDate the schedule date (requested date)
     * @param zone         the stop's timezone
     * @return int[2] where [0]=hour (may be >=24 for past-midnight), [1]=minute
     */
    public static int[] compute(long epochMs, LocalDate scheduleDate, ZoneId zone) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(zone);
        LocalDate departureDate = zdt.toLocalDate();

        int hour = zdt.getHour();
        int minute = zdt.getMinute();

        if (departureDate.isEqual(scheduleDate.plusDays(1))) {
            hour += 24;
        }

        return new int[]{hour, minute};
    }
}
