package com.gradesystem.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegexValidatorTest {

    @Test
    void studentIds() {
        assertTrue(RegexValidator.isStudentId("STU123"));
        assertFalse(RegexValidator.isStudentId("stu123"));
        assertFalse(RegexValidator.isStudentId("STU12"));
        assertFalse(RegexValidator.isStudentId("STU1234"));
        assertFalse(RegexValidator.isStudentId("STUabc"));
    }

    @Test
    void emails() {
        assertTrue(RegexValidator.isEmail("a@university.edu"));
        assertTrue(RegexValidator.isEmail("amina.k+test@cs.university.ac.rw"));
        assertFalse(RegexValidator.isEmail("wrong"));
        assertFalse(RegexValidator.isEmail("a@@university.edu"));
        assertFalse(RegexValidator.isEmail("a@university"));
    }

    @Test
    void internationalPhones() {
        assertTrue(RegexValidator.isPhone("+250 788 123 456"));
        assertTrue(RegexValidator.isPhone("+250788123456"));
        assertTrue(RegexValidator.isPhone("+1-555-123-4567"));
    }

    @Test
    void localPhones() {
        assertTrue(RegexValidator.isPhone("0788123456"));
        assertTrue(RegexValidator.isPhone("0788 123 456"));
    }

    @Test
    void northAmericanPhones() {
        assertTrue(RegexValidator.isPhone("(555) 123-4567"));
        assertTrue(RegexValidator.isPhone("555-123-4567"));
        assertTrue(RegexValidator.isPhone("555.123.4567"));
    }

    @Test
    void invalidPhones() {
        assertFalse(RegexValidator.isPhone("abc"));
        assertFalse(RegexValidator.isPhone("12345"));
        assertFalse(RegexValidator.isPhone("0788-12-34"));
    }

    @Test
    void courseCodes() {
        assertTrue(RegexValidator.isCourseCode("ENG101"));
        assertFalse(RegexValidator.isCourseCode("eng101"));
        assertFalse(RegexValidator.isCourseCode("EN101"));
    }

    @Test
    void datesMustExist() {
        assertTrue(RegexValidator.isIsoDate("2026-10-25"));
        assertTrue(RegexValidator.isIsoDate("2024-02-29"));  // leap year
        assertFalse(RegexValidator.isIsoDate("2026-02-29")); // not a leap year
        assertFalse(RegexValidator.isIsoDate("2026-13-01"));
        assertFalse(RegexValidator.isIsoDate("26-1-1"));
    }

    @Test
    void scores() {
        assertTrue(RegexValidator.isValidScore(0));
        assertTrue(RegexValidator.isValidScore(100));
        assertFalse(RegexValidator.isValidScore(-1));
        assertFalse(RegexValidator.isValidScore(Double.NaN));
    }

    @Test
    void nullIsNeverValid() {
        assertFalse(RegexValidator.isStudentId(null));
        assertFalse(RegexValidator.isEmail(null));
        assertFalse(RegexValidator.isPhone(null));
        assertFalse(RegexValidator.isCourseCode(null));
        assertFalse(RegexValidator.isIsoDate(null));
    }

    @Test
    void checkStudentGivesClearMessages() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RegexValidator.checkStudent("BAD1", "Amina", "a@university.edu", "0788123456"));
        assertTrue(e.getMessage().contains("student ID"));
        assertThrows(IllegalArgumentException.class,
                () -> RegexValidator.checkStudent("STU001", " ", "a@university.edu", "0788123456"));
        assertThrows(IllegalArgumentException.class,
                () -> RegexValidator.checkStudent("STU001", "Amina", "a@university.edu", "123"));
        assertThrows(IllegalArgumentException.class, () -> RegexValidator.checkGrade("ENG101", 150));
    }
}
