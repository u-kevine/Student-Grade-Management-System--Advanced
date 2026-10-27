package com.gradesystem.service;

import java.time.LocalDateTime;

/**
 * Describes one scheduled background task so the menu can display it.
 * Implements Comparable so a PriorityQueue keeps the task that runs soonest at the front.
 */
public class ScheduledTaskInfo implements Comparable<ScheduledTaskInfo> {

    private final String name;
    private final String frequency;
    private LocalDateTime nextRun;
    private LocalDateTime lastRun;
    private int runCount;

    public ScheduledTaskInfo(String name, String frequency, LocalDateTime nextRun) {
        this.name = name;
        this.frequency = frequency;
        this.nextRun = nextRun;
    }

    public String getName() { return name; }
    public String getFrequency() { return frequency; }
    public LocalDateTime getNextRun() { return nextRun; }
    public LocalDateTime getLastRun() { return lastRun; }
    public int getRunCount() { return runCount; }

    /** Called after the task ran: remember when, and when it runs next. */
    void markRan(LocalDateTime ranAt, LocalDateTime next) {
        lastRun = ranAt;
        nextRun = next;
        runCount++;
    }

    @Override
    public int compareTo(ScheduledTaskInfo other) {
        return nextRun.compareTo(other.nextRun);
    }
}
