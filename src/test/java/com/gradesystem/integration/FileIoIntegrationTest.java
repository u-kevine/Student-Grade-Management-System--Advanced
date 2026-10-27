package com.gradesystem.integration;

import com.gradesystem.io.GradeFileService;
import com.gradesystem.io.ImportResult;
import com.gradesystem.model.Student;
import com.gradesystem.service.StudentManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.gradesystem.testutil.TestData.student;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for reading and writing files.
 * @TempDir gives every test its own empty temporary folder (a throw-away "fake" file system),
 * so tests never touch real project files and never affect each other.
 */
class FileIoIntegrationTest {

    private final GradeFileService files = new GradeFileService();

    private StudentManager sampleManager() {
        StudentManager m = new StudentManager();
        m.addStudent(student("STU001", "Amina"));
        m.addStudent(new Student("STU002", "Brian \"B\" Mugabo, Jr", "brian@university.edu", "0788123456"));
        m.addStudent(student("STU003", "Claudine")); // no grades
        m.recordGrade("STU001", "ENG101", 70, LocalDate.of(2026, 3, 1));
        m.recordGrade("STU001", "ENG101", 90, LocalDate.of(2026, 6, 1));
        m.recordGrade("STU002", "MAT101", 65, LocalDate.of(2026, 6, 1));
        return m;
    }

    @Test
    void csvRoundTripKeepsEveryGradeAndTrickyNames(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("out/grades.csv");
        files.exportCsv(csv, sampleManager().getAllStudents());

        StudentManager loaded = new StudentManager();
        ImportResult result = files.importCsv(csv, loaded);

        assertEquals(3, result.studentsAdded());
        assertEquals(3, result.gradesAdded());
        assertEquals("Brian \"B\" Mugabo, Jr", loaded.findById("STU002").orElseThrow().getName());
        assertEquals(2, loaded.findById("STU001").orElseThrow().getHistory().size());
        assertEquals(90.0, loaded.findById("STU001").orElseThrow().getAverage(), 0.001);
    }

    @Test
    void importingSameFileTwiceDoesNotDuplicateGrades(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("grades.csv");
        files.exportCsv(csv, sampleManager().getAllStudents());
        StudentManager m = new StudentManager();
        files.importCsv(csv, m);
        ImportResult second = files.importCsv(csv, m);
        assertEquals(0, second.studentsAdded());
        assertEquals(0, second.gradesAdded());
        assertEquals(2, m.findById("STU001").orElseThrow().getHistory().size());
    }

    @Test
    void oneBadRowRejectsTheWholeFile(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("bad.csv");
        Files.writeString(csv, String.join("\n",
                "id,name,email,phone,course,grade,date",
                "STU001,Amina,amina@university.edu,0788123456,ENG101,80,2026-06-01",
                "STU002,Brian,brian@university.edu,0788123456,ENG101,150,2026-06-01"));
        StudentManager m = new StudentManager();
        IOException e = assertThrows(IOException.class, () -> files.importCsv(csv, m));
        assertTrue(e.getMessage().contains("Line 3"), e.getMessage());
        assertEquals(0, m.size(), "nothing may be imported from a bad file");
    }

    @Test
    void rejectsBadDatesWrongColumnsAndOpenQuotes(@TempDir Path dir) throws IOException {
        StudentManager m = new StudentManager();
        Path badDate = dir.resolve("date.csv");
        Files.writeString(badDate, "header\nSTU001,Amina,amina@university.edu,0788123456,ENG101,80,2026-02-30\n");
        assertThrows(IOException.class, () -> files.importCsv(badDate, m));

        Path columns = dir.resolve("columns.csv");
        Files.writeString(columns, "header\nSTU001,Amina\n");
        assertThrows(IOException.class, () -> files.importCsv(columns, m));

        Path quote = dir.resolve("quote.csv");
        Files.writeString(quote, "header\n\"STU001,Amina,amina@university.edu,0788123456,,,\n");
        assertThrows(IOException.class, () -> files.importCsv(quote, m));
        assertEquals(0, m.size());
    }

    @Test
    void importsOlderSixColumnFiles(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("old.csv");
        Files.writeString(csv, "id,name,email,phone,course,grade\n\nSTU001,Amina,amina@university.edu,0788123456,ENG101,80\n");
        StudentManager m = new StudentManager();
        files.importCsv(csv, m);
        assertEquals(80.0, m.findById("STU001").orElseThrow().getAverage(), 0.001);
    }

    @Test
    void jsonExportEscapesTextAndIncludesGpa(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("grades.json");
        files.exportJson(json, sampleManager().getAllStudents());
        String text = Files.readString(json);
        assertTrue(text.startsWith("["));
        assertTrue(text.contains("\"name\": \"Brian \\\"B\\\" Mugabo, Jr\""));
        assertTrue(text.contains("\"gpa\": 4.00"));
        assertTrue(text.contains("\"date\": \"2026-03-01\""));
    }

    @Test
    void binaryRoundTrip(@TempDir Path dir) throws IOException {
        Path bin = dir.resolve("grades.bin");
        files.exportBinary(bin, sampleManager().getAllStudents());

        StudentManager m = new StudentManager();
        m.addStudent(student("STU003", "Claudine")); // already exists, so it is skipped
        ImportResult result = files.importBinaryInto(bin, m);
        assertEquals(2, result.studentsAdded());
        assertEquals(3, m.size());
        assertTrue(m.getCourses().contains("ENG101"));
        // the loaded student must still be thread-safe and editable
        m.recordGrade("STU001", "PHY102", 75);
        assertEquals(3, m.findById("STU001").orElseThrow().getHistory().size());
    }

    @Test
    void binaryFileWithWrongContentIsRejected(@TempDir Path dir) throws IOException {
        Path bin = dir.resolve("strings.bin");
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(bin))) {
            out.writeObject(new ArrayList<>(List.of("not", "students")));
        }
        assertThrows(IOException.class, () -> files.importBinary(bin));

        Path notList = dir.resolve("string.bin");
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(notList))) {
            out.writeObject("just text");
        }
        assertThrows(IOException.class, () -> files.importBinary(notList));
    }
}
