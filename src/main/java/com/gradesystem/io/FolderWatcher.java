package com.gradesystem.io;

import com.gradesystem.service.AuditLogger;
import com.gradesystem.service.StudentManager;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watches a folder (e.g. "imports/") with WatchService. When a new .csv file appears there,
 * it is imported automatically.
 *
 * It implements Runnable so it can run on a background thread. It stops when that thread
 * is interrupted (BackgroundServices.close() does this).
 */
public class FolderWatcher implements Runnable {

    private final Path folder;
    private final GradeFileService files;
    private final StudentManager manager;
    private final AuditLogger audit;
    private final AtomicInteger importedFiles = new AtomicInteger();
    private volatile boolean ready; // volatile: other threads always see the newest value

    public FolderWatcher(Path folder, GradeFileService files, StudentManager manager, AuditLogger audit) {
        this.folder = folder;
        this.files = files;
        this.manager = manager;
        this.audit = audit;
    }

    public int getImportedFileCount() {
        return importedFiles.get();
    }

    /** True once the watcher is registered and listening for new files. */
    public boolean isReady() {
        return ready;
    }

    public Path getFolder() {
        return folder;
    }

    @Override
    public void run() {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            Files.createDirectories(folder);
            folder.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            ready = true;
            audit.log("Watching folder " + folder.toAbsolutePath());

            while (!Thread.currentThread().isInterrupted()) {
                // Wait at most 1 second for an event, then loop to check if we should stop.
                WatchKey key = watcher.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Path created = folder.resolve((Path) event.context());
                    if (created.toString().toLowerCase().endsWith(".csv")) {
                        importFile(created);
                    }
                }
                key.reset(); // must be called, or we receive no more events
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            Thread.currentThread().interrupt(); // normal way to stop
        } catch (IOException e) {
            audit.log("Folder watcher stopped: " + e.getMessage());
        }
    }

    private void importFile(Path file) {
        try {
            Thread.sleep(300); // give the program that is copying the file time to finish writing
            ImportResult result = files.importCsv(file, manager);
            importedFiles.incrementAndGet();
            audit.log("Auto-imported " + file.getFileName() + ": " + result.studentsAdded()
                    + " students, " + result.gradesAdded() + " grades");
            System.out.println("\n[Watcher] Imported " + file.getFileName() + " ("
                    + result.studentsAdded() + " students, " + result.gradesAdded() + " grades)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            audit.log("Auto-import of " + file.getFileName() + " failed: " + e.getMessage());
            System.out.println("\n[Watcher] Could not import " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
