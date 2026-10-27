package com.gradesystem.model;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * One grade a student received: which course, what score, and when.
 *
 * A "record" is a short way to write a class that only holds data.
 * Java automatically creates the constructor, the getters (course(), score(), date()),
 * equals(), hashCode() and toString() for us.
 */
public record GradeRecord(String course, double score, LocalDate date) implements Serializable {
}
