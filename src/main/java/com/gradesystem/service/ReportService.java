package com.gradesystem.service;

import com.gradesystem.model.GradeRecord;
import com.gradesystem.model.Student;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Builds report cards, caches them, and writes many of them at once (batch processing).
 */
public class ReportService {

    /**
     * Pretend each report takes this long to build (in a real system: PDF rendering, a slow
     * database, etc.). It makes the speed-up of the parallel batch easy to see. Tests use 0.
     */
    public static final long DEFAULT_SIMULATED_WORK_MS = 25;

    private final StudentManager manager;
    private final ReportCache<String, String> cache;
    private final long simulatedWorkMs;

    public ReportService(StudentManager manager) {
        this(manager, 50, DEFAULT_SIMULATED_WORK_MS);
    }

    public ReportService(StudentManager manager, int cacheSize, long simulatedWorkMs) {
        this.manager = manager;
        this.cache = new ReportCache<>(cacheSize);
        this.simulatedWorkMs = simulatedWorkMs;
        // When a student's grades change, their cached report is out of date: remove it.
        manager.onStudentChanged(cache::remove);
    }

    public ReportCache<String, String> getCache() {
        return cache;
    }

    /** Returns the report card for one student, from the cache if possible. */
    public String getReportCard(String studentId) {
        String cached = cache.get(studentId);
        if (cached != null) {
            return cached;
        }
        Student student = manager.findById(studentId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));
        String report = buildReportCard(student);
        cache.put(studentId, report);
        return report;
    }

    /** Builds the report text. This is the "expensive" work the cache saves us from repeating. */
    public String buildReportCard(Student student) {
        simulateWork();
        StringBuilder sb = new StringBuilder();
        sb.append("==============================================\n");
        sb.append("REPORT CARD - ").append(student.getName()).append(" (").append(student.getId()).append(")\n");
        sb.append("Email: ").append(student.getEmail()).append("   Phone: ").append(student.getPhone()).append('\n');
        sb.append("----------------------------------------------\n");
        Map<String, Double> grades = new TreeMap<>(student.getGrades());
        if (grades.isEmpty()) {
            sb.append("No grades recorded yet.\n");
        }
        for (Map.Entry<String, Double> g : grades.entrySet()) {
            sb.append(String.format("%-8s %6.1f%n", g.getKey(), g.getValue()));
        }
        sb.append("----------------------------------------------\n");
        sb.append(String.format("Average: %.2f   GPA: %.2f   Letter: %s%n",
                student.getAverage(), student.getGpa(), student.getLetterGrade()));
        List<GradeRecord> history = student.getHistory();
        sb.append("Grade history entries: ").append(history.size()).append('\n');
        return sb.toString();
    }

    /** Writes one report file per student, one after another, on the current thread. */
    public BatchResult generateAllSequential(Path folder) throws IOException {
        Files.createDirectories(folder);
        long start = System.nanoTime();
        List<Student> all = manager.getAllStudents();
        for (Student s : all) {
            writeReport(folder, s);
        }
        return new BatchResult(all.size(), elapsedMillis(start), 1);
    }

    /**
     * Writes one report file per student, using the thread pool.
     * Each student becomes a separate task; the pool runs several tasks at the same time.
     * We keep the Future of every task and wait for all of them with get().
     */
    public BatchResult generateAllParallel(Path folder, ExecutorService pool, int threads)
            throws IOException, InterruptedException {
        Files.createDirectories(folder);
        long start = System.nanoTime();
        List<Student> all = manager.getAllStudents();

        List<Future<Path>> futures = new ArrayList<>();
        for (Student s : all) {
            futures.add(pool.submit(() -> writeReport(folder, s)));
        }
        for (Future<Path> f : futures) {
            try {
                f.get(); // waits until this task is finished
            } catch (ExecutionException e) {
                throw new IOException("A report failed: " + e.getCause().getMessage(), e.getCause());
            }
        }
        return new BatchResult(all.size(), elapsedMillis(start), threads);
    }

    private Path writeReport(Path folder, Student student) {
        Path file = folder.resolve(student.getId() + ".txt");
        try {
            Files.writeString(file, buildReportCard(student), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            // Lambdas passed to submit() can't throw checked exceptions, so we wrap it.
            throw new UncheckedIOException(e);
        }
    }

    private void simulateWork() {
        if (simulatedWorkMs <= 0) return;
        try {
            Thread.sleep(simulatedWorkMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // keep the "interrupted" flag for the caller
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
