package com.gradesystem.validation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * All input checks in one place.
 *
 * Patterns are compiled ONCE (static final) because compiling a regex is the slow part.
 * matcher(...).matches() then checks that the WHOLE text fits the pattern.
 */
public final class RegexValidator {

    /** "STU" followed by exactly 3 digits, e.g. STU001. */
    private static final Pattern STUDENT_ID = Pattern.compile("STU\\d{3}");

    /** something@domain.extension, e.g. amina.k@university.edu */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    /** International: +250 788 123 456 or +250788123456 (country code + 9 digits). */
    private static final Pattern PHONE_INTERNATIONAL = Pattern.compile("\\+\\d{1,3}[ -]?\\d{3}[ -]?\\d{3}[ -]?\\d{3,4}");

    /** Local Rwandan mobile: 0788123456 or 0788 123 456. */
    private static final Pattern PHONE_LOCAL = Pattern.compile("07\\d{2}[ -]?\\d{3}[ -]?\\d{3}");

    /** North American: (555) 123-4567, 555-123-4567 or 555.123.4567. */
    private static final Pattern PHONE_US = Pattern.compile("(\\(\\d{3}\\) ?|\\d{3}[-.])\\d{3}[-.]\\d{4}");

    /** Three capital letters + three digits, e.g. ENG101. */
    private static final Pattern COURSE = Pattern.compile("[A-Z]{3}\\d{3}");

    /** Shape of a date: YYYY-MM-DD. LocalDate.parse then checks the date really exists. */
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private RegexValidator() {
    }

    public static boolean isStudentId(String text) {
        return text != null && STUDENT_ID.matcher(text.trim()).matches();
    }

    public static boolean isEmail(String text) {
        return text != null && EMAIL.matcher(text.trim()).matches();
    }

    /** Accepts any of the three phone formats above. */
    public static boolean isPhone(String text) {
        if (text == null) return false;
        String t = text.trim();
        return PHONE_INTERNATIONAL.matcher(t).matches()
                || PHONE_LOCAL.matcher(t).matches()
                || PHONE_US.matcher(t).matches();
    }

    public static boolean isCourseCode(String text) {
        return text != null && COURSE.matcher(text.trim()).matches();
    }

    /** True for real dates only: 2024-02-29 is valid (leap year), 2026-02-30 is not. */
    public static boolean isIsoDate(String text) {
        if (text == null || !DATE.matcher(text.trim()).matches()) return false;
        try {
            LocalDate.parse(text.trim());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public static boolean isValidScore(double score) {
        return Double.isFinite(score) && score >= 0 && score <= 100;
    }

    /** Throws an exception with a clear message if any student field is invalid. */
    public static void checkStudent(String id, String name, String email, String phone) {
        if (!isStudentId(id)) throw new IllegalArgumentException("Invalid student ID (expected STU followed by 3 digits): " + id);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name cannot be empty");
        if (!isEmail(email)) throw new IllegalArgumentException("Invalid email: " + email);
        if (!isPhone(phone)) throw new IllegalArgumentException("Invalid phone: " + phone);
    }

    /** Throws an exception with a clear message if the course or score is invalid. */
    public static void checkGrade(String course, double score) {
        if (!isCourseCode(course)) throw new IllegalArgumentException("Invalid course code (expected e.g. ENG101): " + course);
        if (!isValidScore(score)) throw new IllegalArgumentException("Grade must be between 0 and 100: " + score);
    }
}
