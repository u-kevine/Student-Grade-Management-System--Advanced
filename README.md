# Advanced Student Grade Management System (v3.0)

A console application for managing students and grades, built to practise Java Collections,
generics, regular expressions, Streams, NIO.2 file I/O and concurrency with executors.

## How to run

You need **JDK 17 or newer** and **Maven**.

```bash
mvn verify                                   # compile, run all 65 tests, check 85% coverage
mvn package -DskipTests                      # build the jar
java -jar target/advanced-grade-system-3.1.0.jar
```

## Saving your students

Students are saved automatically in **`data/students.csv`**:

- When the program starts, it loads everything saved there last time.
- Every time you add a student or record a grade, the file is rewritten straight away,
  so nothing is lost even if the program crashes or the window is closed.
- When you choose Exit, it is saved one final time.

The file is a normal CSV, so you can open it in Excel. To start over, delete it.
If the file is ever damaged, it is renamed to `students.csv.broken-<date>` (your data is
kept, not overwritten) and the program starts empty.

The 40 sample students in `sample-data/students.csv` are **not** loaded automatically.
Import them once with option 10; from then on they are part of `data/students.csv` too.

## Trying it out

To try it quickly, choose **10. Import CSV** and enter `sample-data/students.csv` (40 students,
170 grades). Then try the dashboard (7), batch reports (12) and the performance monitor (14).

You can also copy any `.csv` file into the `imports/` folder while the program runs: it is
imported automatically.

The coverage report is written to `target/site/jacoco/index.html`.

## Project structure

```
src/main/java/com/gradesystem/
├── Main.java                 Console menu. Only reads input and prints results.
├── model/
│   ├── Student.java          A student, their latest grades and full grade history
│   ├── GradeRecord.java      One grade: course, score, date
│   └── GradeScale.java       Score -> letter (A-F) and grade points (0.0-4.0)
├── validation/
│   └── RegexValidator.java   All regex checks: ID, email, 3 phone formats, course, date
├── service/
│   ├── StudentManager.java   Stores students; search, rankings, statistics
│   ├── ReportService.java    Report cards, LRU cache, sequential vs parallel batch
│   ├── ReportCache.java      Generic thread-safe LRU cache with hit-rate counters
│   ├── BackgroundServices.java  All thread pools, dashboard, scheduled tasks
│   ├── AuditLogger.java      Thread-safe audit trail (SingleThreadExecutor)
│   ├── ScheduledTaskInfo.java, StatsSnapshot.java, BatchResult.java
└── io/
    ├── GradeFileService.java CSV / JSON / binary import and export (NIO.2)
    ├── DataStore.java        Loads data/students.csv at start, saves after every change
    ├── FolderWatcher.java    WatchService: auto-imports CSV files from imports/
    └── ImportResult.java
```

How a request flows: `Main` reads input → `RegexValidator` checks it → `StudentManager`
updates the data → listeners (the report cache) are notified → `AuditLogger` records the
action → every 5 seconds the dashboard thread takes a fresh `StatsSnapshot`.

## Where each requirement is implemented

| Requirement | Where |
|---|---|
| HashMap O(1) lookup | `StudentManager.students` (`ConcurrentHashMap`, thread-safe hash map) |
| TreeMap GPA rankings | `StudentManager.rankings()` |
| HashSet unique courses | `StudentManager.courses` (concurrent hash set) |
| LinkedList grade history | `Student.history` |
| PriorityQueue of tasks | `BackgroundServices.tasks` |
| Regex validation | `RegexValidator` (used by menu, CSV import and binary import) |
| Regex pattern search | `StudentManager.searchByRegex()` |
| NIO.2: Path, Files.lines, BufferedWriter | `GradeFileService` |
| Saving between runs | `DataStore` (load at startup, auto-save on every change) |
| Object serialization | `GradeFileService.exportBinary()` / `importBinary()` |
| WatchService | `FolderWatcher` |
| FixedThreadPool (reports) | `BackgroundServices.reportPool`, used by batch reports and export |
| CachedThreadPool (stats) | `BackgroundServices.statsPool` |
| ScheduledThreadPool | dashboard every 5 s, daily GPA recalculation at 03:30 |
| SingleThreadExecutor (logging) | `AuditLogger` |
| LRU cache with eviction | `ReportCache` |
| Streams: map, filter, reduce, collect | `StudentManager.statistics()`, `Student.getAverage()` |

## Design decisions

**Why ConcurrentHashMap instead of HashMap?** Background threads (dashboard, folder watcher,
batch reports) read the data while the menu changes it. A plain `HashMap` can break when
used by several threads. `ConcurrentHashMap` is still a hash map with O(1) lookup, just safe
for threads.

**Why does the cache hold report cards, not students?** Looking up a student is already O(1),
so caching students saves nothing. Building a report card is the slow part, so that is what
gets cached. When a grade changes, `StudentManager` notifies the cache, which removes that
student's old report.

**Why is the dashboard a snapshot?** Printing every 5 seconds would mix with what the user is
typing. Instead the background thread stores the newest statistics, and option 7 shows them.

**Why does CSV import read the file twice?** Pass 1 checks every row; pass 2 only runs if
all rows are valid. A file with one bad row changes nothing. Importing the same file again
skips grades that already exist.

**Simulated work in reports.** `ReportService` sleeps 25 ms per report to imitate slow work
(like rendering a PDF). This makes the speed-up of the parallel batch visible. Tests set it to 0.

## Big-O summary

| Operation | Collection | Complexity |
|---|---|---|
| Find student by ID | HashMap | O(1) |
| Add course | HashSet | O(1) |
| Build GPA ranking | TreeMap | O(n log n) |
| Add grade to history | LinkedList | O(1) |
| Next scheduled task | PriorityQueue | O(1) peek, O(log n) add/remove |
| Cache get/put | LinkedHashMap (LRU) | O(1) |
| Regex search | stream over all students | O(n) |

## Testing

65 JUnit 5 tests: **43 unit tests** (`model`, `validation`, `service` packages) and
**22 integration tests** (`integration` package).

- File tests use `@TempDir`, a temporary folder, so they never touch real files.
- `DirectExecutorService` is a fake executor that runs tasks immediately on the test
  thread, so thread-pool code can be tested predictably.
- `statisticsCanBeReadWhileGradesChange` runs 4 writer threads and a reader thread at the
  same time to prove the data structures are thread-safe.
- Line coverage is about 94% (Main excluded); the build fails below 85%.

## Git workflow

- `main`: stable, tested releases only
- `develop`: integration branch
- `feature/<name>` branches (e.g. `feature/regex-validation`, `feature/concurrency`), merged
  into `develop` through pull requests once tests pass

## Limitations

- The whole file is rewritten on each change. That is fast for hundreds or a few thousand
  students; a real system with much more data would use a database.
- JSON is written by hand (no library) and CSV fields cannot contain line breaks.
- Only load binary files you created yourself. A filter blocks unexpected classes, but Java
  serialization should still not be used for files from unknown sources.
