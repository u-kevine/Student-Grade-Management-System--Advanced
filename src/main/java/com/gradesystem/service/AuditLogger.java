package com.gradesystem.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Writes an audit trail ("who did what, when") to a log file.
 *
 * Any thread may call log(). The message is handed to a SingleThreadExecutor, which has exactly
 * one worker thread, so lines are written one at a time in order and never get mixed up.
 * The caller does not wait for the file write, so logging never slows down the menu.
 */
public class AuditLogger implements AutoCloseable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path logFile;
    private final ExecutorService writer;

    public AuditLogger(Path logFile) {
        this(logFile, Executors.newSingleThreadExecutor());
    }

    /** Lets tests pass in their own executor (for example one that runs tasks immediately). */
    public AuditLogger(Path logFile, ExecutorService writer) {
        this.logFile = logFile;
        this.writer = writer;
    }

    public Path getLogFile() {
        return logFile;
    }

    public void log(String message) {
        String line = LocalDateTime.now().format(TIME) + " | "
                + Thread.currentThread().getName() + " | " + message + System.lineSeparator();
        writer.submit(() -> writeLine(line));
    }

    private void writeLine(String line) {
        try {
            Path parent = logFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not write audit log: " + e.getMessage());
        }
    }

    /** Finishes writing every waiting message, then stops the writer thread. */
    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
