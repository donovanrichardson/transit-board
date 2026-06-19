package dev.shinpei.transitboard.api;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

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

    @Test
    void isDstRepeat_normalDay_returnsFalse() {
        // 14:30 on 2026-06-14 in America/New_York — normal summer day, no DST overlap
        long epochMs = ZonedDateTime.of(2026, 6, 14, 14, 30, 0, 0, NY)
                .toInstant().toEpochMilli();
        assertFalse(HourCalculator.isDstRepeat(epochMs, NY));
    }

    @Test
    void isDstRepeat_springForwardGap_returnsFalse() {
        // 03:00 on 2026-03-08 in America/New_York — spring-forward gap (skipped hour)
        long epochMs = ZonedDateTime.of(2026, 3, 8, 3, 0, 0, 0, NY)
                .toInstant().toEpochMilli();
        assertFalse(HourCalculator.isDstRepeat(epochMs, NY));
    }

    @Test
    void isDstRepeat_fallBackFirstRepetition_returnsFalse() {
        // 01:30 EDT (-04:00) on 2026-11-01 — first repetition of the overlap hour
        long epochMs = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, ZoneOffset.ofHours(-4))
                .toInstant().toEpochMilli();
        assertFalse(HourCalculator.isDstRepeat(epochMs, NY));
    }

    @Test
    void isDstRepeat_fallBackSecondRepetition_returnsTrue() {
        // 01:30 EST (-05:00) on 2026-11-01 — second repetition of the overlap hour
        long epochMs = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, ZoneOffset.ofHours(-5))
                .toInstant().toEpochMilli();
        assertTrue(HourCalculator.isDstRepeat(epochMs, NY));
    }
}
