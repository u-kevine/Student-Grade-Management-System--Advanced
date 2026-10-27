package com.gradesystem.integration;

import com.gradesystem.io.FolderWatcher;
import com.gradesystem.io.GradeFileService;
import com.gradesystem.service.AuditLogger;
import com.gradesystem.service.BackgroundServices;
import com.gradesystem.service.BatchResult;
import com.gradesystem.service.ReportService;
import com.gradesystem.service.ScheduledTaskInfo;
import com.gradesystem.service.StatsSnapshot;
import com.gradesystem.service.StudentManager;
import com.gradesystem.testutil.DirectExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static com.gradesystem.testutil.TestData.student;
import static org.junit.jupiter.api.Assertions.*;

/** Integration tests for everything that uses threads. */
class ConcurrencyIntegrationTest {

    private static final String[] COURSES = {"ENG101", "MAT101", "PHY102", "CSC201"};

    private StudentManager managerWith(int count) {
        StudentManager m = new StudentManager();
        for (int i = 1; i <= count; i++) {
            String id = String.format("STU%03d", i);
            m.addStudent(student(id, "Student " + i));
            m.recordGrade(id, "ENG101", 50 + i % 50);
        }
        return m;
    }

    /** Waits up to 5 seconds for a condition to become true. */
    private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    /** The bug from the review: reading statistics while grades change must never crash. */
    @Test
    void statisticsCanBeReadWhileGradesChange() throws Exception {
        StudentManager m = managerWith(100);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger errors = new AtomicInteger();

        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                try {
                    m.statistics();
                    m.rankings();
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                }
            }
        });
        reader.start();

        ExecutorService writers = Executors.newFixedThreadPool(4);
        List<Future<?>> jobs = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            int writer = w;
            jobs.add(writers.submit(() -> {
                for (int round = 0; round < 200; round++) {
                    for (int i = 1; i <= 100; i++) {
                        m.recordGrade(String.format("STU%03d", i), COURSES[writer], 60 + round % 40);
                    }
                }
            }));
        }
        for (Future<?> job : jobs) job.get();
        stop.set(true);
        reader.join();
        writers.shutdown();

        assertEquals(0, errors.get(), "the reader thread must not see any exceptions");
        assertEquals(1 + 4 * 200, m.findById("STU001").orElseThrow().getHistory().size());
    }

    @Test
    void parallelBatchWritesSameReportsAsSequential(@TempDir Path dir) throws Exception {
        StudentManager m = managerWith(20);
        ReportService reports = new ReportService(m, 10, 0);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            BatchResult seq = reports.generateAllSequential(dir.resolve("seq"));
            BatchResult par = reports.generateAllParallel(dir.resolve("par"), pool, 4);
            assertEquals(20, seq.reportCount());
            assertEquals(20, par.reportCount());
            assertEquals(4, par.threads());
            for (int i = 1; i <= 20; i++) {
                String name = String.format("STU%03d.txt", i);
                assertEquals(Files.readString(dir.resolve("seq").resolve(name)),
                        Files.readString(dir.resolve("par").resolve(name)));
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void parallelBatchUsesTheGivenExecutor(@TempDir Path dir) throws Exception {
        StudentManager m = managerWith(5);
        DirectExecutorService fakePool = new DirectExecutorService();
        new ReportService(m, 10, 0).generateAllParallel(dir, fakePool, 2);
        assertEquals(5, fakePool.getTasksRun(), "one task per student");
        try (var listing = Files.list(dir)) {
            assertEquals(5, listing.count());
        }
    }

    @Test
    void parallelBatchIsFasterWhenWorkIsSlow(@TempDir Path dir) throws Exception {
        StudentManager m = managerWith(16);
        ReportService reports = new ReportService(m, 10, 20); // 20 ms of work per report
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            BatchResult seq = reports.generateAllSequential(dir.resolve("seq"));
            BatchResult par = reports.generateAllParallel(dir.resolve("par"), pool, 4);
            assertTrue(par.millis() < seq.millis(),
                    "parallel " + par.millis() + " ms should beat sequential " + seq.millis() + " ms");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void auditLoggerWritesMessagesInOrder(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("logs/audit.log");
        AuditLogger audit = new AuditLogger(log, new DirectExecutorService());
        audit.log("first");
        audit.log("second");
        audit.close();
        List<String> lines = Files.readAllLines(log);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith("first"));
        assertTrue(lines.get(1).endsWith("second"));
        assertEquals(log, audit.getLogFile());
    }

    @Test
    void auditLoggerKeepsEveryLineFromManyThreads(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("audit.log");
        AuditLogger audit = new AuditLogger(log);
        ExecutorService callers = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 200; i++) {
            int n = i;
            callers.submit(() -> audit.log("message " + n));
        }
        callers.shutdown();
        assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        audit.close(); // waits until every message is written

        List<String> lines = Files.readAllLines(log);
        assertEquals(200, lines.size());
        assertTrue(lines.stream().allMatch(line -> line.contains("| message ")), "no line may be mixed up");
    }

    @Test
    void dashboardRefreshesInTheBackground(@TempDir Path dir) throws Exception {
        StudentManager m = managerWith(3);
        AuditLogger audit = new AuditLogger(dir.resolve("audit.log"));
        try (BackgroundServices bg = new BackgroundServices(m, audit, 2)) {
            assertNull(bg.getLatestStats());
            bg.startDashboard(); // first refresh happens immediately, then every 5 seconds
            assertTrue(waitFor(() -> bg.getDashboardRefreshCount() > 0));
            StatsSnapshot s = bg.getLatestStats();
            assertEquals(3, s.studentCount());
            assertTrue(bg.activeThreadSummary().contains("reports"));
            assertEquals(2, bg.getReportThreads());
            assertNotNull(bg.getReportPool());
        } finally {
            audit.close();
        }
    }

    @Test
    void scheduledTasksAreListedSoonestFirst(@TempDir Path dir) throws Exception {
        AuditLogger audit = new AuditLogger(dir.resolve("audit.log"));
        try (BackgroundServices bg = new BackgroundServices(new StudentManager(), audit, 2)) {
            bg.scheduleDaily("Daily GPA recalculation", LocalTime.of(3, 30), () -> { });
            bg.startDashboard();
            List<ScheduledTaskInfo> tasks = bg.getScheduledTasks();
            assertEquals(2, tasks.size());
            assertEquals("Dashboard statistics refresh", tasks.get(0).getName());
            assertEquals(LocalTime.of(3, 30), tasks.get(1).getNextRun().toLocalTime());
            assertTrue(waitFor(() -> bg.getScheduledTasks().get(0).getRunCount() > 0));
        } finally {
            audit.close();
        }
    }

    @Test
    void folderWatcherImportsNewCsvFiles(@TempDir Path dir) throws Exception {
        StudentManager m = new StudentManager();
        AuditLogger audit = new AuditLogger(dir.resolve("audit.log"));
        Path inbox = dir.resolve("imports");
        FolderWatcher watcher = new FolderWatcher(inbox, new GradeFileService(), m, audit);
        try (BackgroundServices bg = new BackgroundServices(m, audit, 2)) {
            bg.runInBackground(watcher);
            assertTrue(waitFor(watcher::isReady), "watcher should start");

            Files.writeString(inbox.resolve("broken.csv"), "header\nnot,a,valid,row\n");
            Files.writeString(inbox.resolve("notes.txt"), "ignored, not a csv");
            Files.writeString(inbox.resolve("new.csv"),
                    "id,name,email,phone,course,grade,date\nSTU001,Amina,amina@university.edu,0788123456,ENG101,80,2026-06-01\n");

            assertTrue(waitFor(() -> m.exists("STU001")), "the CSV should be imported automatically");
            assertEquals(1, watcher.getImportedFileCount());
            assertEquals(inbox, watcher.getFolder());
        } finally {
            audit.close();
        }
    }
}
