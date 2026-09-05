package dev.sluice.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

public class CronScheduleCalculatorTest {
    private final CronScheduleCalculator calculator = new CronScheduleCalculator();

    @Test
    void nextRunAfterFindsTopOfNextHour() {
        Instant from = Instant.parse("2026-01-01T10:15:30Z");
        Instant expected = Instant.parse("2026-01-01T11:00:00Z");

        Instant result = calculator.nextRunAfter("0 * * * *", from);

        assertEquals(expected, result);
    }

    @Test
    void nextRunAfterFindsNextMidnight() {
        Instant from = Instant.parse("2026-01-01T15:30:00Z");
        Instant expected = Instant.parse("2026-01-02T00:00:00Z");

        Instant result = calculator.nextRunAfter("0 0 * * *", from);

        assertEquals(expected, result);
    }
}