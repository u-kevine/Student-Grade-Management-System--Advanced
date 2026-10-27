package com.gradesystem.service;

import com.gradesystem.model.Student;
import com.gradesystem.validation.RegexValidator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Stores students and answers questions about them.
 *
 * Collections used (and why):
 * - Map  students : ConcurrentHashMap  -> find a student by ID in O(1). "Concurrent" because
 *                                         background threads read while the menu writes.
 * - Set  courses  : concurrent hash set -> every course code once, duplicates ignored, O(1) add.
 * - TreeMap rankings (built on request) -> keeps GPAs sorted automatically, O(log n) per insert.
 */
public class StudentManager {

    private final Map<String, Student> students = new ConcurrentHashMap<>();
    private final Set<String> courses = ConcurrentHashMap.newKeySet();

    /**
     * Code that wants to know when a student is added or their grades change. For example:
     * the report cache throws away the old report, and the DataStore saves the file.
     * CopyOnWriteArrayList is a thread-safe list.
     */
    private final List<Consumer<String>> changeListeners = new CopyOnWriteArrayList<>();

    /** Adds a new student. Throws IllegalArgumentException if invalid or the ID already exists. */
    public void addStudent(Student student) {
        if (student == null) throw new IllegalArgumentException("Student cannot be empty");
        // Check again here: students loaded from a binary file skip the constructor checks.
        RegexValidator.checkStudent(student.getId(), student.getName(), student.getEmail(), student.getPhone());

        // putIfAbsent adds only if the key is new, and does the check+add in one safe step.
        if (students.putIfAbsent(student.getId(), student) != null) {
            throw new IllegalArgumentException("A student with ID " + student.getId() + " already exists");
        }
        courses.addAll(student.getGrades().keySet());
        notifyChanged(student.getId());
    }

    /** O(1) lookup by ID. Optional means "there may or may not be a student". */
    public Optional<Student> findById(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(students.get(id.trim()));
    }

    public boolean exists(String id) {
        return findById(id).isPresent();
    }

    /** All students sorted by ID. */
    public List<Student> getAllStudents() {
        return students.values().stream()
                .sorted(Comparator.comparing(Student::getId))
                .collect(Collectors.toList());
    }

    public int size() {
        return students.size();
    }

    /** Records a grade dated today. */
    public void recordGrade(String id, String course, double score) {
        recordGrade(id, course, score, LocalDate.now());
    }

    /** Records a grade and tells all listeners that this student changed. */
    public void recordGrade(String id, String course, double score, LocalDate date) {
        Student student = findById(id)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + id));
        student.addGrade(course, score, date);
        courses.add(course);
        notifyChanged(student.getId());
    }

    /** Register code to run whenever a student is added or a grade is recorded. */
    public void onStudentChanged(Consumer<String> listener) {
        changeListeners.add(listener);
    }

    private void notifyChanged(String id) {
        for (Consumer<String> listener : changeListeners) {
            listener.accept(id);
        }
    }

    /** Sorted copy of all course codes (TreeSet keeps them in alphabetical order). */
    public Set<String> getCourses() {
        return new TreeSet<>(courses);
    }

    /**
     * Students grouped by GPA, highest GPA first.
     * Several students can share a GPA, so each key holds a list.
     * Building it costs O(n log n): n students, each inserted in O(log n).
     */
    public NavigableMap<Double, List<Student>> rankings() {
        NavigableMap<Double, List<Student>> ranking = new TreeMap<>(Comparator.reverseOrder());
        for (Student s : students.values()) {
            ranking.computeIfAbsent(s.getGpa(), gpa -> new ArrayList<>()).add(s);
        }
        for (List<Student> sameGpa : ranking.values()) {
            sameGpa.sort(Comparator.comparing(Student::getId));
        }
        return ranking;
    }

    /**
     * Finds students whose ID, name, email or phone matches the regex (case-insensitive).
     * Throws PatternSyntaxException if the regex itself is broken, e.g. "[abc".
     */
    public List<Student> searchByRegex(String regex) {
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        return students.values().stream()
                .filter(s -> pattern.matcher(s.getId()).find()
                        || pattern.matcher(s.getName()).find()
                        || pattern.matcher(s.getEmail()).find()
                        || pattern.matcher(s.getPhone()).find())
                .sorted(Comparator.comparing(Student::getId))
                .collect(Collectors.toList());
    }

    /** Calculates statistics with streams (map, filter, reduce/collect). */
    public StatsSnapshot statistics() {
        Collection<Student> all = new ArrayList<>(students.values());

        // Only students with at least one grade count towards averages.
        List<Student> graded = all.stream()
                .filter(s -> !s.getGrades().isEmpty())
                .collect(Collectors.toList());

        double avgScore = graded.stream().mapToDouble(Student::getAverage).average().orElse(0);
        double avgGpa = graded.stream().mapToDouble(Student::getGpa).average().orElse(0);
        double highest = graded.stream().mapToDouble(Student::getAverage).max().orElse(0);
        double lowest = graded.stream().mapToDouble(Student::getAverage).min().orElse(0);

        // groupingBy + counting = "how many students got each letter". TreeMap keeps A..F order.
        Map<String, Long> distribution = graded.stream()
                .collect(Collectors.groupingBy(Student::getLetterGrade, TreeMap::new, Collectors.counting()));

        return new StatsSnapshot(all.size(), courses.size(), avgScore, avgGpa, highest, lowest,
                distribution, LocalDateTime.now());
    }
}
