package com.gradesystem.io;

import com.gradesystem.service.StudentManager;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Keeps all students saved in one CSV file (by default data/students.csv), so nothing is lost
 * when the program stops.
 *
 *  - load()           : at startup, reads the file back into the StudentManager.
 *  - startAutoSave()  : from then on, every new student or grade rewrites the file.
 *  - save()           : writes the file now (also called when the program exits).
 *
 * The file uses the same CSV format as export/import, so you can open it in Excel too.
 */
public class DataStore {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path file;
    private final StudentManager manager;
    private final GradeFileService files;
    private boolean autoSaveStarted;

    public DataStore(Path file, StudentManager manager, GradeFileService files) {
        this.file = file;
        this.manager = manager;
        this.files = files;
    }

    public Path getFile() {
        return file;
    }

    /**
     * Loads saved students. Returns how many students were loaded.
     * If there is no file yet (first run), nothing happens and 0 is returned.
     *
     * If the file is damaged, it is renamed to a ".broken" backup and the program starts empty.
     * This way the next save does NOT overwrite (and destroy) the data in the damaged file:
     * you can still open the backup and fix it by hand.
     */
    public int load() throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
        try {
            return files.importCsv(file, manager).studentsAdded();
        } catch (IOException e) {
            Path backup = file.resolveSibling(file.getFileName() + ".broken-" + LocalDateTime.now().format(STAMP));
            Files.move(file, backup);
            throw new IOException("Saved data could not be read (" + e.getMessage()
                    + "). It was moved to " + backup + " and the program starts empty.", e);
        }
    }

    /** From now on, save the file every time a student is added or a grade is recorded. */
    public void startAutoSave() {
        if (autoSaveStarted) return;
        autoSaveStarted = true;
        manager.onStudentChanged(id -> saveQuietly());
    }

    /**
     * Writes all students to the file.
     *
     * We write to a temporary file first and then rename it over the real one. If the program
     * crashes in the middle of writing, the old file is still complete, never half-written.
     *
     * synchronized: the menu and the folder watcher thread may both trigger a save at the
     * same moment; this makes them take turns.
     */
    public synchronized void save() throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path temp = folder.resolve(file.getFileName() + ".tmp");

        files.exportCsv(temp, manager.getAllStudents());
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some file systems can't do an "all at once" rename; a normal replace is the fallback.
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Same as save(), but prints a warning instead of throwing (used by auto-save). */
    private void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            System.out.println("\n⚠ Could not save " + file + ": " + e.getMessage());
        }
    }
}
