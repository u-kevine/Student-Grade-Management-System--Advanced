package com.gradesystem.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.*;

class SchedulingTest {

    @Test
    void priorityQueueGivesSoonestTaskFirst() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 26, 12, 0);
        PriorityQueue<ScheduledTaskInfo> queue = new PriorityQueue<>();
        queue.add(new ScheduledTaskInfo("later", "daily", now.plusHours(5)));
        queue.add(new ScheduledTaskInfo("soon", "every 5 s", now.plusSeconds(5)));
        queue.add(new ScheduledTaskInfo("middle", "hourly", now.plusHours(1)));
        assertEquals("soon", queue.poll().getName());
        assertEquals("middle", queue.poll().getName());
        assertEquals("later", queue.poll().getName());
    }

    @Test
    void nextRunIsLaterTodayIfTimeNotPassed() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 26, 1, 0);
        assertEquals(LocalDateTime.of(2026, 10, 26, 3, 30),
                BackgroundServices.nextOccurrence(now, LocalTime.of(3, 30)));
    }

    @Test
    void nextRunIsTomorrowIfTimeAlreadyPassed() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 26, 9, 0);
        assertEquals(LocalDateTime.of(2026, 10, 27, 3, 30),
                BackgroundServices.nextOccurrence(now, LocalTime.of(3, 30)));
    }

    @Test
    void reportPoolMustHaveTwoToEightThreads() {
        AuditLogger audit = new AuditLogger(Path.of("unused.log"));
        assertThrows(IllegalArgumentException.class, () -> new BackgroundServices(new StudentManager(), audit, 1));
        assertThrows(IllegalArgumentException.class, () -> new BackgroundServices(new StudentManager(), audit, 9));
        audit.close();
    }

    @Test
    void taskInfoRemembersRuns() {
        ScheduledTaskInfo info = new ScheduledTaskInfo("job", "daily", LocalDateTime.of(2026, 1, 1, 3, 30));
        assertNull(info.getLastRun());
        info.markRan(LocalDateTime.of(2026, 1, 1, 3, 30), LocalDateTime.of(2026, 1, 2, 3, 30));
        assertEquals(1, info.getRunCount());
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 30), info.getNextRun());
        assertEquals("daily", info.getFrequency());
    }
}
