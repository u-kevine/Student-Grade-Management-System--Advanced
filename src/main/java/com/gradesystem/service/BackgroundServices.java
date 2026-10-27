package com.gradesystem.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns all background thread pools. Each pool has one job:
 *
 *  - reportPool (FixedThreadPool)    : batch reports and exports, a fixed number of workers (2-8).
 *  - statsPool  (CachedThreadPool)   : short statistics jobs and the folder watcher.
 *                                      Creates threads when needed and reuses idle ones.
 *  - scheduler  (ScheduledThreadPool): runs tasks on a timer (dashboard every 5 s, daily GPA job).
 *  - The audit log has its own SingleThreadExecutor inside AuditLogger.
 *
 * Call close() when the program ends, otherwise the pool threads keep the program running.
 */
public class BackgroundServices implements AutoCloseable {

    public static final int DASHBOARD_SECONDS = 5;

    private final StudentManager manager;
    private final AuditLogger audit;
    private final int reportThreads;

    private final ExecutorService reportPool;
    private final ExecutorService statsPool;
    private final ScheduledExecutorService scheduler;

    /** Latest dashboard numbers. AtomicReference lets one thread write while others read safely. */
    private final AtomicReference<StatsSnapshot> latestStats = new AtomicReference<>();
    private final AtomicInteger dashboardRefreshes = new AtomicInteger();

    /** Scheduled tasks, soonest first. PriorityQueue is not thread-safe, so we lock on it. */
    private final PriorityQueue<ScheduledTaskInfo> tasks = new PriorityQueue<>();

    public BackgroundServices(StudentManager manager, AuditLogger audit, int reportThreads) {
        if (reportThreads < 2 || reportThreads > 8) {
            throw new IllegalArgumentException("Report pool must use 2-8 threads");
        }
        this.manager = manager;
        this.audit = audit;
        this.reportThreads = reportThreads;
        this.reportPool = Executors.newFixedThreadPool(reportThreads);
        this.statsPool = Executors.newCachedThreadPool();
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    public ExecutorService getReportPool() {
        return reportPool;
    }

    public int getReportThreads() {
        return reportThreads;
    }

    // ------------------------------------------------------------------ dashboard

    /** Starts refreshing the dashboard statistics every 5 seconds in the background. */
    public void startDashboard() {
        ScheduledTaskInfo info = registerTask("Dashboard statistics refresh",
                "every " + DASHBOARD_SECONDS + " seconds", LocalDateTime.now());
        scheduler.scheduleAtFixedRate(() -> runSafely(info, this::refreshDashboardNow,
                        LocalDateTime.now().plusSeconds(DASHBOARD_SECONDS)),
                0, DASHBOARD_SECONDS, TimeUnit.SECONDS);
    }

    /** Calculates fresh statistics on the stats pool and stores them as the latest snapshot. */
    public void refreshDashboardNow() {
        Future<StatsSnapshot> job = statsPool.submit(manager::statistics);
        try {
            latestStats.set(job.get());
            dashboardRefreshes.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Statistics failed", e.getCause());
        }
    }

    /** The most recent statistics, or null if the dashboard has not refreshed yet. */
    public StatsSnapshot getLatestStats() {
        return latestStats.get();
    }

    public int getDashboardRefreshCount() {
        return dashboardRefreshes.get();
    }

    // ------------------------------------------------------------------ scheduled tasks

    /** Runs a task every day at the given time, e.g. 03:30. */
    public void scheduleDaily(String name, LocalTime time, Runnable task) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime firstRun = nextOccurrence(now, time);
        ScheduledTaskInfo info = registerTask(name, "daily at " + time, firstRun);

        long delayMillis = Duration.between(now, firstRun).toMillis();
        scheduler.scheduleAtFixedRate(() -> runSafely(info, task, nextOccurrence(LocalDateTime.now().plusMinutes(1), time)),
                delayMillis, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
        audit.log("Scheduled task '" + name + "', first run " + firstRun);
    }

    /** The next date-time when the clock shows `time` (today if still ahead, otherwise tomorrow). */
    public static LocalDateTime nextOccurrence(LocalDateTime now, LocalTime time) {
        LocalDateTime today = now.toLocalDate().atTime(time);
        return today.isAfter(now) ? today : today.plusDays(1);
    }

    /** All scheduled tasks, soonest first. */
    public List<ScheduledTaskInfo> getScheduledTasks() {
        synchronized (tasks) {
            PriorityQueue<ScheduledTaskInfo> copy = new PriorityQueue<>(tasks);
            List<ScheduledTaskInfo> ordered = new ArrayList<>();
            while (!copy.isEmpty()) {
                ordered.add(copy.poll()); // poll always removes the soonest task
            }
            return ordered;
        }
    }

    private ScheduledTaskInfo registerTask(String name, String frequency, LocalDateTime nextRun) {
        ScheduledTaskInfo info = new ScheduledTaskInfo(name, frequency, nextRun);
        synchronized (tasks) {
            tasks.add(info);
        }
        return info;
    }

    /**
     * IMPORTANT: if a scheduled task throws an exception, the ScheduledExecutorService silently
     * stops running it forever. Catching the exception here keeps the task alive.
     */
    private void runSafely(ScheduledTaskInfo info, Runnable task, LocalDateTime nextRun) {
        try {
            task.run();
        } catch (RuntimeException e) {
            audit.log("Task '" + info.getName() + "' failed: " + e.getMessage());
        }
        synchronized (tasks) {
            // An element's position in a PriorityQueue is decided when it is added,
            // so we remove it, change the time, and add it again.
            tasks.remove(info);
            info.markRan(LocalDateTime.now(), nextRun);
            tasks.add(info);
        }
    }

    // ------------------------------------------------------------------ other background work

    /** Runs a long job (like the folder watcher) on the cached pool. */
    public Future<?> runInBackground(Runnable job) {
        return statsPool.submit(job);
    }

    /** Number of threads currently busy in each pool, for the dashboard. */
    public String activeThreadSummary() {
        return String.format("reports %d/%d busy, stats %d busy, scheduler %d busy",
                active(reportPool), reportThreads, active(statsPool), active(scheduler));
    }

    private static int active(ExecutorService pool) {
        // The Executors factory methods return ThreadPoolExecutor objects, which can report this.
        return (pool instanceof ThreadPoolExecutor tpe) ? tpe.getActiveCount() : 0;
    }

    // ------------------------------------------------------------------ shutdown

    @Override
    public void close() {
        stop(scheduler);
        stop(statsPool);
        stop(reportPool);
    }

    private static void stop(ExecutorService pool) {
        pool.shutdownNow(); // also interrupts long-running jobs such as the folder watcher
        try {
            pool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
