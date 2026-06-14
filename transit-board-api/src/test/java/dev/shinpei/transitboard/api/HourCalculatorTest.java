package dev.shinpei.transitboard.api;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class HourCalculatorTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final LocalDate SCHEDULE_DATE = LocalDate.of(2026, 6, 14);

    @Test
    void sameDayHour() {
        // 14:30 on 2026-06-14 in America/New_York
        // 2026-06-14T14:30:00-04:00 = epoch ms
        long epochMs = java.time.ZonedDateTime.of(2026, 6, 14, 14, 30, 0, 0, NY)
                .toInstant().toEpochMilli();

        int[] result = HourCalculator.compute(epochMs, SCHEDULE_DATE, NY);
        assertEquals(14, result[0], "hour should be 14");
        assertEquals(30, result[1], "minute should be 30");
    }

    @Test
    void pastMidnightHour() {
        // 01:15 on 2026-06-15 (day after schedule date) → hour=25, minute=15
        long epochMs = java.time.ZonedDateTime.of(2026, 6, 15, 1, 15, 0, 0, NY)
                .toInstant().toEpochMilli();

        int[] result = HourCalculator.compute(epochMs, SCHEDULE_DATE, NY);
        assertEquals(25, result[0], "hour should be 25 (past midnight)");
        assertEquals(15, result[1], "minute should be 15");
    }

    @Test
    void midnightExact() {
        // 00:00 on 2026-06-15 → hour=24, minute=0
        long epochMs = java.time.ZonedDateTime.of(2026, 6, 15, 0, 0, 0, 0, NY)
                .toInstant().toEpochMilli();

        int[] result = HourCalculator.compute(epochMs, SCHEDULE_DATE, NY);
        assertEquals(24, result[0], "hour should be 24 (exact midnight next day)");
        assertEquals(0, result[1], "minute should be 0");
    }
}
