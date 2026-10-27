package com.gradesystem;

import com.gradesystem.io.DataStore;
import com.gradesystem.io.FolderWatcher;
import com.gradesystem.io.GradeFileService;
import com.gradesystem.io.ImportResult;
import com.gradesystem.model.GradeRecord;
import com.gradesystem.model.Student;
import com.gradesystem.service.AuditLogger;
import com.gradesystem.service.BackgroundServices;
import com.gradesystem.service.BatchResult;
import com.gradesystem.service.ReportService;
import com.gradesystem.service.ScheduledTaskInfo;
import com.gradesystem.service.StatsSnapshot;
import com.gradesystem.service.StudentManager;
import com.gradesystem.validation.RegexValidator;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.PatternSyntaxException;

/**
 * The console menu. This class only talks to the user: it reads input, calls the services,
 * and prints results. All the real work happens in the service, io and validation packages.
 */
public class Main {

    private static final Path REPORTS = Path.of("reports");
    private static final Path IMPORTS = Path.of("imports");
    /** All students are saved here automatically and loaded again at the next start. */
    private static final Path DATA_FILE = Path.of("data", "students.csv");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static Scanner in;
    private static StudentManager manager;
    private static GradeFileService files;
    private static ReportService reports;
    private static AuditLogger audit;
    private static BackgroundServices background;
    private static FolderWatcher watcher;
    private static DataStore store;

    public static void main(String[] args) {
        // Print with UTF-8 so the box characters and the check marks display correctly.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));

        in = new Scanner(System.in, StandardCharsets.UTF_8);
        manager = new StudentManager();
        files = new GradeFileService();
        reports = new ReportService(manager);
        store = new DataStore(DATA_FILE, manager, files);
        loadSavedStudents();

        // try-with-resources: close() is called on both objects when the block ends,
        // which stops all background threads so the program can exit.
        try (AuditLogger log = new AuditLogger(REPORTS.resolve("audit.log"));
             BackgroundServices bg = new BackgroundServices(manager, log, 4)) {
            audit = log;
            background = bg;
            startBackgroundWork();
            audit.log("Application started");

            boolean running = true;
            while (running) {
                printMenu();
                try {
                    int choice = readInt("Choose an option: ", 0, 14);
                    running = handle(choice);
                } catch (InputClosedException e) {
                    running = false;
                }
            }
            saveOnExit();
            audit.log("Application stopped");
        }
        System.out.println("Goodbye!");
    }

    /** Loads the students saved last time, then turns on auto-save. */
    private static void loadSavedStudents() {
        try {
            int loaded = store.load();
            if (loaded > 0) {
                System.out.println("✓ Loaded " + loaded + " students from " + DATA_FILE);
            } else {
                System.out.println("No saved students yet. New students will be saved to " + DATA_FILE);
            }
        } catch (Exception e) {
            System.out.println("⚠ " + e.getMessage());
        }
        // Turn auto-save on only AFTER loading, so loading doesn't save the file again and again.
        store.startAutoSave();
    }

    /** Auto-save already ran after every change; this final save is an extra safety net. */
    private static void saveOnExit() {
        try {
            store.save();
            System.out.println("✓ Saved " + manager.size() + " students to " + DATA_FILE);
        } catch (Exception e) {
            System.out.println("⚠ Could not save " + DATA_FILE + ": " + e.getMessage());
        }
    }

    private static void startBackgroundWork() {
        background.startDashboard();
        background.scheduleDaily("Daily GPA recalculation", LocalTime.of(3, 30), Main::dailyGpaRecalculation);
        watcher = new FolderWatcher(IMPORTS, files, manager, audit);
        background.runInBackground(watcher);
    }

    /** The job that runs every day at 03:30. */
    private static void dailyGpaRecalculation() {
        reports.getCache().clear();                 // force fresh report cards
        background.refreshDashboardNow();           // recalculate all statistics
        StatsSnapshot s = background.getLatestStats();
        audit.log(String.format("Daily GPA recalculation done: %d students, average GPA %.2f",
                s.studentCount(), s.averageGpa()));
    }

    private static boolean handle(int choice) {
        switch (choice) {
            case 1 -> addStudent();
            case 2 -> listStudents();
            case 3 -> recordGrade();
            case 4 -> viewReportCard();
            case 5 -> viewGradeHistory();
            case 6 -> showRankings();
            case 7 -> showDashboard();
            case 8 -> search();
            case 9 -> exportAll();
            case 10 -> importCsv();
            case 11 -> importBinary();
            case 12 -> batchReports();
            case 13 -> showScheduledTasks();
            case 14 -> performanceMonitor();
            case 0 -> { return false; }
            default -> System.out.println("Unknown option.");
        }
        return true;
    }

    private static void printMenu() {
        System.out.println("""

                ╔════════════════════════════════════════════╗
                ║   STUDENT GRADE MANAGEMENT - MAIN MENU     ║
                ║          [Advanced Edition v3.0]           ║
                ╚════════════════════════════════════════════╝
                 STUDENTS                 FILES
                  1. Add student           9. Export CSV, JSON, binary
                  2. List students        10. Import CSV
                  3. Record grade         11. Import binary
                  4. View report card
                  5. View grade history   BACKGROUND & PERFORMANCE
                 ANALYSIS                 12. Batch report cards
                  6. GPA rankings         13. Scheduled tasks
                  7. Real-time dashboard  14. Performance monitor
                  8. Pattern search
                  0. Exit
                """);
    }

    // ================================================================== students

    private static void addStudent() {
        String id = readUntilValid("Student ID (e.g. STU001): ", RegexValidator::isStudentId,
                "✗ INVALID Student ID format. Use STU followed by 3 digits.");
        if (manager.exists(id)) {
            System.out.println("✗ A student with ID " + id + " already exists.");
            return;
        }
        System.out.println("✓ Valid Student ID: " + id);
        String name = readUntilValid("Full name: ", text -> !text.isBlank(), "✗ Name cannot be empty.");
        String email = readUntilValid("Email: ", RegexValidator::isEmail, "✗ Invalid email, e.g. amina@university.edu");
        String phone = readUntilValid("Phone: ", RegexValidator::isPhone,
                "✗ Invalid phone. Examples: +250 788 123 456, 0788123456, (555) 123-4567");
        try {
            manager.addStudent(new Student(id, name, email, phone));
            audit.log("Added student " + id);
            System.out.println("✓ Student added and saved to " + DATA_FILE);
        } catch (IllegalArgumentException e) {
            System.out.println("✗ " + e.getMessage());
        }
    }

    private static void listStudents() {
        List<Student> all = manager.getAllStudents();
        if (all.isEmpty()) {
            System.out.println("No students yet. Add one (option 1) or import sample-data/students.csv (option 10).");
            return;
        }
        System.out.println("STUDENTS (" + all.size() + ")");
        all.forEach(System.out::println);
    }

    private static void recordGrade() {
        String id = read("Student ID: ");
        if (!manager.exists(id)) {
            System.out.println("✗ Student not found: " + id);
            return;
        }
        String course = readUntilValid("Course code (e.g. ENG101): ", RegexValidator::isCourseCode,
                "✗ Invalid course code. Use 3 capital letters + 3 digits.");
        int score = readInt("Grade (0-100): ", 0, 100);
        manager.recordGrade(id, course, score);
        audit.log("Recorded grade " + score + " for " + id + " in " + course);
        System.out.println("✓ Grade recorded and saved.");
    }

    private static void viewReportCard() {
        String id = read("Student ID: ");
        try {
            System.out.println(reports.getReportCard(id));
            System.out.println("(cache: " + reports.getCache() + ")");
        } catch (RuntimeException e) {
            System.out.println("✗ " + e.getMessage());
        }
    }

    private static void viewGradeHistory() {
        String id = read("Student ID: ");
        manager.findById(id).ifPresentOrElse(student -> {
            List<GradeRecord> history = student.getHistory();
            System.out.println("GRADE HISTORY - " + student.getName() + " (" + history.size() + " entries)");
            for (GradeRecord g : history) {
                System.out.printf("  %s  %-7s %6.1f%n", g.date(), g.course(), g.score());
            }
        }, () -> System.out.println("✗ Student not found: " + id));
    }

    // ================================================================== analysis

    private static void showRankings() {
        System.out.println("GPA RANKINGS (TreeMap, highest first)");
        int rank = 1;
        for (Map.Entry<Double, List<Student>> entry : manager.rankings().entrySet()) {
            for (Student s : entry.getValue()) {
                System.out.printf("  #%-3d GPA %.2f  %s %s%n", rank, entry.getKey(), s.getId(), s.getName());
            }
            rank += entry.getValue().size(); // students with the same GPA share a rank
        }
        if (rank == 1) System.out.println("  No students yet.");
    }

    private static void showDashboard() {
        StatsSnapshot s = background.getLatestStats();
        if (s == null) {
            System.out.println("Dashboard is starting, try again in a moment.");
            return;
        }
        System.out.println("REAL-TIME STATISTICS DASHBOARD (refreshes every "
                + BackgroundServices.DASHBOARD_SECONDS + " s in the background)");
        System.out.println("  Last update     : " + s.createdAt().format(TIME)
                + "  (update #" + background.getDashboardRefreshCount() + ")");
        System.out.println("  Students        : " + s.studentCount() + "    Courses: " + s.courseCount());
        System.out.printf("  Average score   : %.2f   (highest %.2f, lowest %.2f)%n",
                s.averageScore(), s.highestAverage(), s.lowestAverage());
        System.out.printf("  Average GPA     : %.2f%n", s.averageGpa());
        System.out.println("  Active threads  : " + background.activeThreadSummary());
        System.out.printf("  Cache hit rate  : %.1f%%%n", reports.getCache().hitRate());
        System.out.println("  Grade distribution:");
        if (s.letterDistribution().isEmpty()) System.out.println("    (no grades yet)");
        s.letterDistribution().forEach((letter, count) ->
                System.out.printf("    %s  %-20s %d%n", letter, "#".repeat(count.intValue()), count));
    }

    private static void search() {
        System.out.println("Examples: @university\\.edu$   ^STU00   amina   ^\\+250");
        String regex = read("Regex: ");
        try {
            List<Student> found = manager.searchByRegex(regex);
            System.out.println("SEARCH RESULTS (" + found.size() + " found)");
            found.forEach(System.out::println);
        } catch (PatternSyntaxException e) {
            System.out.println("✗ That is not a valid regex: " + e.getDescription());
        }
    }

    // ================================================================== files

    /** Runs the three exports at the same time on the report thread pool. */
    private static void exportAll() {
        List<Student> students = manager.getAllStudents();
        System.out.println("EXPORT GRADE REPORT (Multi-Format)");
        System.out.println("Processing " + students.size() + " students with NIO.2 on "
                + background.getReportThreads() + " threads...");

        List<Future<String>> jobs = new ArrayList<>();
        jobs.add(background.getReportPool().submit(() -> {
            files.exportCsv(REPORTS.resolve("grades.csv"), students);
            return "CSV Export completed    -> reports/grades.csv";
        }));
        jobs.add(background.getReportPool().submit(() -> {
            files.exportJson(REPORTS.resolve("grades.json"), students);
            return "JSON Export completed   -> reports/grades.json";
        }));
        jobs.add(background.getReportPool().submit(() -> {
            files.exportBinary(REPORTS.resolve("grades.bin"), students);
            return "Binary Export completed -> reports/grades.bin";
        }));

        for (Future<String> job : jobs) {
            try {
                System.out.println("✓ " + job.get());
            } catch (ExecutionException e) {
                System.out.println("✗ Export failed: " + e.getCause().getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        audit.log("Exported " + students.size() + " students to CSV, JSON and binary");
    }

    private static void importCsv() {
        System.out.println("Tip: you can also drop a .csv file into the 'imports' folder to import it automatically.");
        String path = read("CSV file path (e.g. sample-data/students.csv): ");
        try {
            ImportResult r = files.importCsv(Path.of(path), manager);
            audit.log("Imported " + path + ": " + r.studentsAdded() + " students, " + r.gradesAdded() + " grades");
            System.out.println("✓ Imported " + r.studentsAdded() + " new students and " + r.gradesAdded() + " grades.");
        } catch (Exception e) {
            System.out.println("✗ Import failed, nothing was changed: " + e.getMessage());
        }
    }

    private static void importBinary() {
        String path = read("Binary file path (e.g. reports/grades.bin): ");
        try {
            ImportResult r = files.importBinaryInto(Path.of(path), manager);
            audit.log("Imported binary " + path + ": " + r.studentsAdded() + " students");
            System.out.println("✓ Imported " + r.studentsAdded() + " new students (existing IDs were skipped).");
        } catch (Exception e) {
            System.out.println("✗ Import failed: " + e.getMessage());
        }
    }

    // ================================================================== background & performance

    /** Generates every report card twice, one-by-one and in parallel, and compares the time. */
    private static void batchReports() {
        if (manager.size() == 0) {
            System.out.println("No students yet. Import sample-data/students.csv first (option 10).");
            return;
        }
        try {
            System.out.println("CONCURRENT BATCH REPORTS");
            System.out.println("Generating " + manager.size() + " report cards one by one...");
            BatchResult seq = reports.generateAllSequential(REPORTS.resolve("cards-sequential"));
            System.out.println("Generating them again with a pool of " + background.getReportThreads() + " threads...");
            BatchResult par = reports.generateAllParallel(REPORTS.resolve("cards"),
                    background.getReportPool(), background.getReportThreads());

            System.out.printf("  Sequential : %5d ms (1 thread)%n", seq.millis());
            System.out.printf("  Parallel   : %5d ms (%d threads)%n", par.millis(), par.threads());
            double gain = par.millis() == 0 ? 0 : (double) seq.millis() / par.millis();
            System.out.printf("  Performance gain: %.1fx faster%n", gain);
            System.out.println("  Files written to reports/cards/");
            audit.log("Batch generated " + par.reportCount() + " report cards");
        } catch (Exception e) {
            System.out.println("✗ Batch failed: " + e.getMessage());
        }
    }

    private static void showScheduledTasks() {
        System.out.println("SCHEDULED TASKS (PriorityQueue, soonest first)");
        for (ScheduledTaskInfo t : background.getScheduledTasks()) {
            System.out.println("  " + t.getName());
            System.out.println("    Frequency : " + t.getFrequency());
            System.out.println("    Next run  : " + t.getNextRun().format(TIME));
            System.out.println("    Last run  : " + (t.getLastRun() == null ? "not yet" : t.getLastRun().format(TIME))
                    + "   (ran " + t.getRunCount() + " times)");
            System.out.println("    Status    : Scheduled");
        }
        System.out.println("  Folder watcher: " + (watcher.isReady() ? "watching " : "starting ")
                + watcher.getFolder().toAbsolutePath() + " (" + watcher.getImportedFileCount() + " files imported)");
    }

    /** Measures how fast the main operations are and shows their Big-O complexity. */
    private static void performanceMonitor() {
        List<Student> all = manager.getAllStudents();
        System.out.println("PERFORMANCE MONITOR (" + all.size() + " students)");
        if (all.isEmpty()) {
            System.out.println("  Add or import some students first.");
            return;
        }

        int lookups = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < lookups; i++) {
            manager.findById(all.get(i % all.size()).getId());
        }
        double nsPerLookup = (System.nanoTime() - start) / (double) lookups;

        start = System.nanoTime();
        manager.rankings();
        double rankingMs = (System.nanoTime() - start) / 1_000_000.0;

        System.out.printf("  HashMap lookup by ID : %8.1f ns each   O(1)%n", nsPerLookup);
        System.out.printf("  TreeMap GPA ranking  : %8.3f ms total  O(n log n) to build, O(log n) per insert%n", rankingMs);
        System.out.println("  HashSet course add   : O(1)   (" + manager.getCourses().size() + " unique courses)");
        System.out.println("  LinkedList history   : O(1) add at end, O(n) to read all");
        System.out.println("  PriorityQueue tasks  : O(log n) add/remove, O(1) peek at soonest");
        System.out.println("  LRU report cache     : O(1) get/put  -> " + reports.getCache());
    }

    // ================================================================== input helpers

    private static String read(String prompt) {
        System.out.print(prompt);
        if (!in.hasNextLine()) {
            // Input was closed (e.g. Ctrl+D). Stop the program cleanly instead of looping forever.
            throw new InputClosedException();
        }
        return in.nextLine().trim();
    }

    /** Keeps asking until the check passes. */
    private static String readUntilValid(String prompt, java.util.function.Predicate<String> check, String error) {
        while (true) {
            String text = read(prompt);
            if (check.test(text)) return text;
            System.out.println(error);
        }
    }

    private static int readInt(String prompt, int min, int max) {
        while (true) {
            String text = read(prompt);
            try {
                int number = Integer.parseInt(text);
                if (number >= min && number <= max) return number;
            } catch (NumberFormatException ignored) {
                // fall through to the error message below
            }
            System.out.printf("Please enter a number from %d to %d.%n", min, max);
        }
    }

    /** Thrown when there is no more keyboard input. */
    private static class InputClosedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
