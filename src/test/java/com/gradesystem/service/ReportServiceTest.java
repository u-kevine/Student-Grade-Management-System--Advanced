package com.gradesystem.service;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static com.gradesystem.testutil.TestData.student;
import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private StudentManager managerWithAmina() {
        StudentManager m = new StudentManager();
        m.addStudent(student("STU001", "Amina"));
        m.recordGrade("STU001", "ENG101", 88);
        return m;
    }

    @Test
    void reportContainsNameGradesAndGpa() {
        ReportService reports = new ReportService(managerWithAmina(), 10, 0);
        String card = reports.getReportCard("STU001");
        assertTrue(card.contains("Amina"));
        assertTrue(card.contains("ENG101"));
        assertTrue(card.contains("GPA: 3.00"));
    }

    @Test
    void secondRequestComesFromCache() {
        ReportService reports = new ReportService(managerWithAmina(), 10, 0);
        reports.getReportCard("STU001");
        reports.getReportCard("STU001");
        assertEquals(1, reports.getCache().getHits());
        assertEquals(1, reports.getCache().getMisses());
    }

    @Test
    void newGradeRemovesOldReportFromCache() {
        StudentManager m = managerWithAmina();
        ReportService reports = new ReportService(m, 10, 0);
        assertTrue(reports.getReportCard("STU001").contains("88.0"));
        m.recordGrade("STU001", "ENG101", 55);
        String updated = reports.getReportCard("STU001");
        assertTrue(updated.contains("55.0"), "cache must not return the old report");
    }

    @Test
    void unknownStudentAndEmptyReport() {
        StudentManager m = new StudentManager();
        m.addStudent(student("STU002", "Brian"));
        ReportService reports = new ReportService(m, 10, 0);
        assertThrows(NoSuchElementException.class, () -> reports.getReportCard("STU404"));
        assertTrue(reports.getReportCard("STU002").contains("No grades recorded yet"));
    }
}
