package dev.sluice.core;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

public class CronScheduleCalculator {
    final CronParser parser;

    public CronScheduleCalculator(){
        this.parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
    }

    public Instant nextRunAfter(String cronExpression, Instant from) {
        Cron cron = parser.parse(cronExpression);
        ExecutionTime executionTime = ExecutionTime.forCron(cron);

        ZonedDateTime fromZoned = from.atZone(ZoneOffset.UTC);
        Optional<ZonedDateTime> next = executionTime.nextExecution(fromZoned);

        return next
                .map(ZonedDateTime::toInstant)
                .orElseThrow(() -> new IllegalStateException(
                        "Cron expression never fires again: " + cronExpression));
    }
}