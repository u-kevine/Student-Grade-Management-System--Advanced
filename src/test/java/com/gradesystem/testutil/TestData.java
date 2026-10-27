package com.gradesystem.testutil;

import com.gradesystem.model.Student;

/** Small helpers so tests don't repeat the same setup code. */
public final class TestData {
    private TestData() {
    }

    /** A valid student with the given ID and name. */
    public static Student student(String id, String name) {
        return new Student(id, name, name.toLowerCase().replace(' ', '.') + "@university.edu", "+250788123456");
    }
}
