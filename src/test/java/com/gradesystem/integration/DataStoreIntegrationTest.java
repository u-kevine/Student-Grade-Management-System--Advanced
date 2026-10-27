package com.gradesystem.integration;

import com.gradesystem.io.DataStore;
import com.gradesystem.io.GradeFileService;
import com.gradesystem.service.StudentManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static com.gradesystem.testutil.TestData.student;
import static org.junit.jupiter.api.Assertions.*;

/** Tests that students are saved to a file and come back after a "restart". */
class DataStoreIntegrationTest {

    private final GradeFileService files = new GradeFileService();

    /** Simulates starting the program: a fresh, empty manager that loads the saved file. */
    private StudentManager restart(Path file) throws IOException {
        StudentManager m = new StudentManager();
        DataStore store = new DataStore(file, m, files);
        store.load();
        store.startAutoSave();
        return m;
    }

    @Test
    void firstRunStartsEmptyWithoutAFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("data/students.csv");
        StudentManager m = new StudentManager();
        DataStore store = new DataStore(file, m, files);
        assertEquals(0, store.load());
        assertEquals(0, m.size());
        assertEquals(file, store.getFile());
    }

    @Test
    void newStudentIsSavedImmediately(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("data/students.csv");
        StudentManager m = restart(file);
        m.addStudent(student("STU001", "Amina"));
        // No save() call needed: auto-save already wrote the file.
        assertTrue(Files.readString(file).contains("STU001"));
    }

    @Test
    void studentsAndGradesSurviveRestarts(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("data/students.csv");

        StudentManager firstRun = restart(file);
        firstRun.addStudent(student("STU001", "Amina"));
        firstRun.recordGrade("STU001", "ENG101", 88);

        StudentManager secondRun = restart(file);
        assertEquals(88.0, secondRun.findById("STU001").orElseThrow().getAverage(), 0.001);
        secondRun.addStudent(student("STU002", "Brian"));

        StudentManager thirdRun = restart(file);
        assertEquals(2, thirdRun.size());
        assertEquals(1, thirdRun.findById("STU001").orElseThrow().getHistory().size(),
                "loading must not duplicate grades");
    }

    @Test
    void temporaryFileIsNotLeftBehind(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("students.csv");
        StudentManager m = restart(file);
        m.addStudent(student("STU001", "Amina"));
        try (var listing = Files.list(dir)) {
            List<String> names = listing.map(p -> p.getFileName().toString()).collect(Collectors.toList());
            assertEquals(List.of("students.csv"), names);
        }
    }

    @Test
    void damagedFileIsBackedUpInsteadOfOverwritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("students.csv");
        Files.writeString(file, "header\nSTU001,this row is broken\n");

        StudentManager m = new StudentManager();
        DataStore store = new DataStore(file, m, files);
        IOException e = assertThrows(IOException.class, store::load);
        assertTrue(e.getMessage().contains("broken"));

        try (var listing = Files.list(dir)) {
            Path backup = listing.filter(p -> p.getFileName().toString().contains(".broken-"))
                    .findFirst().orElseThrow();
            assertTrue(Files.readString(backup).contains("this row is broken"), "the old data must be kept");
        }
        assertFalse(Files.exists(file));
    }
}
