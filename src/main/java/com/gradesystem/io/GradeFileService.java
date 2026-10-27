package com.gradesystem.io;

import com.gradesystem.model.GradeRecord;
import com.gradesystem.model.Student;
import com.gradesystem.service.StudentManager;
import com.gradesystem.validation.RegexValidator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads and writes student data with the NIO.2 API (Path + Files).
 *
 * Re-importing a file is safe: grades that already exist are skipped.
 *
 * CSV format, one row per grade (a student without grades gets one row with empty grade columns):
 *   id,name,email,phone,course,grade,date
 */
public class GradeFileService {

    public static final String CSV_HEADER = "id,name,email,phone,course,grade,date";

    // ================================================================== CSV export

    public void exportCsv(Path path, Collection<Student> students) throws IOException {
        createParentFolder(path);
        // try-with-resources closes the writer automatically, even if an error happens.
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write(CSV_HEADER);
            out.newLine();
            for (Student s : students) {
                List<GradeRecord> history = s.getHistory();
                if (history.isEmpty()) {
                    writeCsvRow(out, s.getId(), s.getName(), s.getEmail(), s.getPhone(), "", "", "");
                }
                for (GradeRecord g : history) {
                    writeCsvRow(out, s.getId(), s.getName(), s.getEmail(), s.getPhone(),
                            g.course(), String.valueOf(g.score()), g.date().toString());
                }
            }
        }
    }

    /** Puts every field in quotes; a quote inside a field is written twice ("" ). */
    private void writeCsvRow(BufferedWriter out, String... fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) out.write(',');
            out.write('"' + fields[i].replace("\"", "\"\"") + '"');
        }
        out.newLine();
    }

    // ================================================================== CSV import

    /**
     * Imports a CSV file in two passes so a bad file never leaves half its data behind:
     *  1. read and check EVERY row (nothing is changed yet)
     *  2. only if all rows are fine, add the students and grades
     */
    public ImportResult importCsv(Path path, StudentManager manager) throws IOException {
        List<CsvRow> rows = readAndValidate(path);

        int studentsAdded = 0;
        int gradesAdded = 0;
        for (CsvRow row : rows) {
            if (!manager.exists(row.id)) {
                manager.addStudent(new Student(row.id, row.name, row.email, row.phone));
                studentsAdded++;
            }
            // Skip grades that are already recorded, so importing the same file twice
            // does not create duplicate history entries.
            if (row.hasGrade() && !alreadyRecorded(manager, row)) {
                manager.recordGrade(row.id, row.course, row.score, row.date);
                gradesAdded++;
            }
        }
        return new ImportResult(studentsAdded, gradesAdded);
    }

    private boolean alreadyRecorded(StudentManager manager, CsvRow row) {
        return manager.findById(row.id)
                .map(s -> s.hasGradeRecord(row.course, row.score, row.date))
                .orElse(false);
    }

    /** One checked line of the CSV file. */
    private static final class CsvRow {
        String id, name, email, phone, course;
        double score;
        LocalDate date;

        boolean hasGrade() {
            return course != null;
        }
    }

    private List<CsvRow> readAndValidate(Path path) throws IOException {
        List<CsvRow> rows = new ArrayList<>();
        // Files.lines reads the file lazily, one line at a time, instead of all at once.
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            Iterator<String> it = lines.iterator();
            int lineNumber = 0;
            while (it.hasNext()) {
                String line = it.next();
                lineNumber++;
                if (lineNumber == 1 || line.isBlank()) continue; // skip header and empty lines
                rows.add(parseRow(line, lineNumber));
            }
        }
        return rows;
    }

    private CsvRow parseRow(String line, int lineNumber) throws IOException {
        List<String> f = splitCsvLine(line, lineNumber);
        if (f.size() == 6) f.add("");               // old files without a date column
        if (f.size() != 7) throw new IOException("Line " + lineNumber + ": expected 7 columns but found " + f.size());

        CsvRow row = new CsvRow();
        row.id = f.get(0).trim();
        row.name = f.get(1).trim();
        row.email = f.get(2).trim();
        row.phone = f.get(3).trim();
        try {
            RegexValidator.checkStudent(row.id, row.name, row.email, row.phone);

            String course = f.get(4).trim();
            String grade = f.get(5).trim();
            String date = f.get(6).trim();
            if (course.isEmpty() && grade.isEmpty()) return row; // student without a grade

            double score = Double.parseDouble(grade);
            RegexValidator.checkGrade(course, score);
            if (!date.isEmpty() && !RegexValidator.isIsoDate(date)) {
                throw new IllegalArgumentException("Invalid date (expected YYYY-MM-DD): " + date);
            }
            row.course = course;
            row.score = score;
            row.date = date.isEmpty() ? LocalDate.now() : LocalDate.parse(date);
            return row;
        } catch (IllegalArgumentException e) { // NumberFormatException is a subclass of this
            throw new IOException("Line " + lineNumber + ": " + e.getMessage(), e);
        }
    }

    /** Splits one CSV line on commas, but not on commas inside "quoted text". */
    private List<String> splitCsvLine(String line, int lineNumber) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean insideQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                boolean doubledQuote = insideQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"';
                if (doubledQuote) {
                    current.append('"');
                    i++;
                } else {
                    insideQuotes = !insideQuotes;
                }
            } else if (c == ',' && !insideQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (insideQuotes) throw new IOException("Line " + lineNumber + ": a quote was opened but never closed");
        fields.add(current.toString());
        return fields;
    }

    // ================================================================== JSON export

    public void exportJson(Path path, Collection<Student> students) throws IOException {
        createParentFolder(path);
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("[\n");
            boolean firstStudent = true;
            for (Student s : students) {
                if (!firstStudent) out.write(",\n");
                firstStudent = false;
                out.write("  {\"id\": " + json(s.getId())
                        + ", \"name\": " + json(s.getName())
                        + ", \"email\": " + json(s.getEmail())
                        + ", \"phone\": " + json(s.getPhone())
                        + ", \"average\": " + round(s.getAverage())
                        + ", \"gpa\": " + round(s.getGpa())
                        + ", \"history\": [");
                boolean firstGrade = true;
                for (GradeRecord g : s.getHistory()) {
                    if (!firstGrade) out.write(", ");
                    firstGrade = false;
                    out.write("{\"course\": " + json(g.course()) + ", \"score\": " + g.score()
                            + ", \"date\": " + json(g.date().toString()) + "}");
                }
                out.write("]}");
            }
            out.write("\n]\n");
        }
    }

    /** Turns text into a JSON string: adds quotes and escapes special characters. */
    static String json(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); // other control characters
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    // ================================================================== binary (serialization)

    public void exportBinary(Path path, Collection<Student> students) throws IOException {
        createParentFolder(path);
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(path))) {
            out.writeObject(new ArrayList<>(students));
        }
    }

    /**
     * Reads students saved by exportBinary.
     * SECURITY: reading Java objects from an unknown file can be dangerous, so a filter only
     * allows our own model classes and standard Java classes; anything else is rejected.
     */
    public List<Student> importBinary(Path path) throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(path))) {
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    "com.gradesystem.model.*;java.base/*;!*"));
            Object data = in.readObject();
            if (!(data instanceof List<?> list)) {
                throw new IOException("File does not contain a list of students");
            }
            List<Student> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Student student)) {
                    throw new IOException("File contains something that is not a student");
                }
                result.add(student);
            }
            return result;
        } catch (ClassNotFoundException | InvalidClassException e) {
            throw new IOException("Not a valid student binary file: " + e.getMessage(), e);
        }
    }

    /** Adds students from a binary file, skipping IDs that already exist. Returns how many were added. */
    public ImportResult importBinaryInto(Path path, StudentManager manager) throws IOException {
        int added = 0;
        int grades = 0;
        Set<String> seen = new HashSet<>();
        for (Student s : importBinary(path)) {
            if (manager.exists(s.getId()) || !seen.add(s.getId())) continue;
            manager.addStudent(s);
            added++;
            grades += s.getHistory().size();
        }
        return new ImportResult(added, grades);
    }

    // ================================================================== helpers

    private static void createParentFolder(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
    }
}
