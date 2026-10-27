package com.gradesystem.model;

import com.gradesystem.validation.RegexValidator;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A student and all of their grades.
 *
 * THREAD SAFETY: the dashboard thread reads grades while the menu thread writes them.
 * - latestGrades is a ConcurrentHashMap, which is safe to read and write from many threads.
 * - history is a LinkedList, which is NOT thread-safe, so every access is wrapped
 *   in synchronized (history) { ... } so only one thread touches it at a time.
 */
public class Student implements Serializable {
    private static final long serialVersionUID = 2L;

    private final String id;
    private final String name;
    private final String email;
    private final String phone;

    /** Latest score per course, e.g. "ENG101" -> 88.0. Used for average and GPA. */
    private final ConcurrentHashMap<String, Double> latestGrades = new ConcurrentHashMap<>();

    /**
     * Every grade ever recorded, oldest first.
     * LinkedList is a good fit: we only add at the end (O(1)) and read in order.
     */
    private final LinkedList<GradeRecord> history = new LinkedList<>();

    public Student(String id, String name, String email, String phone) {
        // All validation lives in RegexValidator, so the menu, CSV import and
        // binary import all follow exactly the same rules.
        RegexValidator.checkStudent(id, name, email, phone);
        this.id = id.trim();
        this.name = name.trim();
        this.email = email.trim();
        this.phone = phone.trim();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }

    /** Records a grade dated today. */
    public void addGrade(String course, double score) {
        addGrade(course, score, LocalDate.now());
    }

    /** Records a grade. Old grades stay in the history; the newest one counts for GPA. */
    public void addGrade(String course, double score, LocalDate date) {
        RegexValidator.checkGrade(course, score);
        if (date == null) {
            throw new IllegalArgumentException("Date cannot be empty");
        }
        synchronized (history) {
            history.add(new GradeRecord(course, score, date));
        }
        latestGrades.put(course, score);
    }

    /** True if exactly this grade (same course, score and date) is already in the history. */
    public boolean hasGradeRecord(String course, double score, LocalDate date) {
        synchronized (history) {
            // Records compare by their values, so contains() finds an identical entry.
            return history.contains(new GradeRecord(course, score, date));
        }
    }

    /** A copy of the latest grade per course. Changing the copy does not change the student. */
    public Map<String, Double> getGrades() {
        return Map.copyOf(latestGrades);
    }

    /** A copy of the full grade history, oldest first. */
    public List<GradeRecord> getHistory() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /** Average percentage of the latest grade in each course. 0 if there are no grades. */
    public double getAverage() {
        return latestGrades.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }

    /** GPA on a 4.0 scale: the average of the grade points of each course. */
    public double getGpa() {
        return latestGrades.values().stream()
                .mapToDouble(GradeScale::pointsFor)
                .average()
                .orElse(0.0);
    }

    /** Letter grade for the overall average, or "N/A" if the student has no grades yet. */
    public String getLetterGrade() {
        return latestGrades.isEmpty() ? "N/A" : GradeScale.letterFor(getAverage());
    }

    @Override
    public String toString() {
        return String.format("%s | %-15s | %-25s | avg %6.2f | GPA %.2f",
                id, name, email, getAverage(), getGpa());
    }
}
