package com.gradesystem.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StudentTest {

    private Student amina() {
        return new Student("STU001", "Amina Uwase", "amina@university.edu", "+250 788 123 456");
    }

    @Test
    void validStudentIsCreatedWithTrimmedValues() {
        Student s = new Student(" STU001 ", " Amina ", "amina@university.edu", "0788123456");
        assertEquals("STU001", s.getId());
        assertEquals("Amina", s.getName());
    }

    @Test
    void invalidEmailIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Student("STU001", "Amina", "not-an-email", "0788123456"));
    }

    @Test
    void newGradeForSameCourseKeepsOldOneInHistory() {
        Student s = amina();
        s.addGrade("ENG101", 70, LocalDate.of(2026, 3, 1));
        s.addGrade("ENG101", 95, LocalDate.of(2026, 6, 1));
        List<GradeRecord> history = s.getHistory();
        assertEquals(2, history.size());
        assertEquals(70.0, history.get(0).score(), 0.001);
        assertEquals(95.0, s.getGrades().get("ENG101"), 0.001);
    }

    @Test
    void averageAndGpaUseLatestGradePerCourse() {
        Student s = amina();
        s.addGrade("ENG101", 70);
        s.addGrade("ENG101", 95); // replaces 70 for the average
        s.addGrade("MAT101", 85);
        assertEquals(90.0, s.getAverage(), 0.001);
        assertEquals(3.5, s.getGpa(), 0.001);   // A (4.0) and B (3.0)
        assertEquals("A", s.getLetterGrade());
    }

    @Test
    void studentWithoutGradesHasZeroAverageAndNoLetter() {
        Student s = amina();
        assertEquals(0.0, s.getAverage(), 0.001);
        assertEquals(0.0, s.getGpa(), 0.001);
        assertEquals("N/A", s.getLetterGrade());
    }

    @Test
    void invalidGradesAreRejected() {
        Student s = amina();
        assertThrows(IllegalArgumentException.class, () -> s.addGrade("ENG101", 101));
        assertThrows(IllegalArgumentException.class, () -> s.addGrade("eng101", 50));
        assertThrows(IllegalArgumentException.class, () -> s.addGrade("ENG101", 50, null));
        assertTrue(s.getHistory().isEmpty());
    }

    @Test
    void gradeScaleBoundaries() {
        assertEquals("A", GradeScale.letterFor(90));
        assertEquals("B", GradeScale.letterFor(89.9));
        assertEquals("C", GradeScale.letterFor(70));
        assertEquals("D", GradeScale.letterFor(60));
        assertEquals("F", GradeScale.letterFor(59.99));
        assertEquals(4.0, GradeScale.pointsFor(100), 0.001);
        assertEquals(2.0, GradeScale.pointsFor(75), 0.001);
        assertEquals(1.0, GradeScale.pointsFor(65), 0.001);
        assertEquals(0.0, GradeScale.pointsFor(10), 0.001);
    }

    @Test
    void findsIdenticalGradeRecord() {
        Student s = amina();
        LocalDate date = LocalDate.of(2026, 6, 20);
        s.addGrade("ENG101", 80, date);
        assertTrue(s.hasGradeRecord("ENG101", 80, date));
        assertFalse(s.hasGradeRecord("ENG101", 81, date));
    }

    @Test
    void returnedGradesAreReadOnlyCopies() {
        Student s = amina();
        s.addGrade("ENG101", 80);
        Map<String, Double> grades = s.getGrades();
        assertThrows(UnsupportedOperationException.class, () -> grades.put("MAT101", 10.0));
        s.getHistory().clear(); // clearing the copy must not affect the student
        assertEquals(1, s.getHistory().size());
        assertTrue(s.toString().contains("STU001"));
    }
}
