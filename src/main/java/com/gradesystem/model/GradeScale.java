package com.gradesystem.model;

/**
 * Converts a percentage score (0-100) into a letter grade and grade points (0.0-4.0).
 * Keeping these rules in one place means every part of the program uses the same scale.
 */
public final class GradeScale {

    private GradeScale() {
        // Utility class: only static methods, so nobody should create an object of it.
    }

    public static String letterFor(double score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    public static double pointsFor(double score) {
        switch (letterFor(score)) {
            case "A": return 4.0;
            case "B": return 3.0;
            case "C": return 2.0;
            case "D": return 1.0;
            default:  return 0.0;
        }
    }
}
