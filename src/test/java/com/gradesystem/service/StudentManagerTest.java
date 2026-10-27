package com.gradesystem.service;

import com.gradesystem.model.Student;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.regex.PatternSyntaxException;

import static com.gradesystem.testutil.TestData.student;
import static org.junit.jupiter.api.Assertions.*;

class StudentManagerTest {

    @Test
    void addAndFindById() {
        StudentManager m = new StudentManager();
        m.addStudent(student("STU001", "Amina"));
        assertTrue(m.findById("STU001").isPresent());
        assertTrue(m.findById("STU999").isEmpty());
        assertTrue(m.findById(null).isEmpty());
        assertEquals(1, m.size());
    }

    @Test
    void duplicateIdIsRejected() {
        StudentManager m = new StudentManager();
        m.addStudent(student("STU001", "Amina"));
        assertThrows(IllegalArgumentException.class, () -> m.addStudent(student("STU001", "Other")));
        assertThrows(IllegalArgumentException.class, () -> m.addStudent(null));
    }

    @Test
    void eachCourseIsTrackedOnceInSortedOrder() {
        StudentManager m = new StudentManager();
        m.addStudent(student("STU001", "Amina"));
        m.recordGrade("STU001", "MAT101", 80);
        m.recordGrade("STU001", "ENG101", 90);
        m.recordGrade("STU001", "ENG101", 70);
        assertEquals(List.of("ENG101", "MAT101"), new ArrayList<>(m.getCourses()));
    }

    @Test
    void gradeForUnknownStudentFails() {
        StudentManager m = new StudentManager();
        assertThrows(NoSuchElementException.class, () -> m.recordGrade("STU404", "ENG101", 50));
    }

    @Test
    void rankingsAreHighestFirstAndKeepTies() {
        StudentManager m = new StudentManager();
        m.addStudent(student("STU001", "Amina"));
        m.addStudent(student("STU002", "Brian"));
        m.addStudent(student("STU003", "Claudine"));
        m.recordGrade("STU001", "ENG101", 95);
        m.recordGrade("STU002", "ENG101", 72);
        m.recordGrade("STU003", "ENG101", 91);

        NavigableMap<Double, List<Student>> ranking = m.rankings();
        assertEquals(4.0, ranking.firstKey(), 0.001);
        assertEquals(2, ranking.firstEntry().getValue().size()); // Amina and Claudine tie on 4.0
        assertEquals(2.0, ranking.lastKey(), 0.001);
    }

    @Test
    void regexSearchMatchesEmailDomain() {
        StudentManager m = new StudentManager();
        m.addStudent(student("STU001", "Amina"));
        m.addStudent(new Student("STU002", "Brian", "brian@student.ac.rw", "0788123456"));
        assertEquals(1, m.searchByRegex("@university\\.edu$").size());
        assertEquals(2, m.searchByRegex("^STU00").size());
        assertEquals(1, m.searchByRegex("AMINA").size()); // case-insensitive
    }

    @Test
    void brokenRegexThrows() {
        StudentManager m = new StudentManager();
        assertThrows(PatternSyntaxException.class, () -> m.searchByRegex("[abc"));
    }

    @Test
    void statisticsOfEmptyManager() {
        StatsSnapshot s = new StudentManager().statistics();
        assertEquals(0, s.studentCount());
        assertEquals(0.0, s.averageScore(), 0.001);
        assertTrue(s.letterDistribution().isEmpty());
    }

    @Test
    void statisticsAndDistribution() {
        StudentManager m = new StudentManager();
        m.addStudent(student("STU001", "Amina"));
        m.addStudent(student("STU002", "Brian"));
        m.addStudent(student("STU003", "Claudine")); // no grades: counted, but not averaged
        m.recordGrade("STU001", "ENG101", 90);
        m.recordGrade("STU002", "ENG101", 70);

        StatsSnapshot s = m.statistics();
        assertEquals(3, s.studentCount());
        assertEquals(1, s.courseCount());
        assertEquals(80.0, s.averageScore(), 0.001);
        assertEquals(90.0, s.highestAverage(), 0.001);
        assertEquals(70.0, s.lowestAverage(), 0.001);
        assertEquals(3.0, s.averageGpa(), 0.001);
        assertEquals(Long.valueOf(1), s.letterDistribution().get("A"));
        assertEquals(Long.valueOf(1), s.letterDistribution().get("C"));
    }

    @Test
    void listenersHearAboutNewStudentsAndGrades() {
        StudentManager m = new StudentManager();
        List<String> changed = new ArrayList<>();
        m.onStudentChanged(changed::add);
        m.addStudent(student("STU001", "Amina"));   // 1st notification
        m.recordGrade("STU001", "ENG101", 90);      // 2nd notification
        assertEquals(List.of("STU001", "STU001"), changed);
    }
}
