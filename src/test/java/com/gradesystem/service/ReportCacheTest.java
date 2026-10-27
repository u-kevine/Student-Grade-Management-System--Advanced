package com.gradesystem.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReportCacheTest {

    @Test
    void evictsLeastRecentlyUsedEntry() {
        ReportCache<String, String> cache = new ReportCache<>(2);
        cache.put("a", "A");
        cache.put("b", "B");
        cache.get("a");          // "a" is now the most recently used
        cache.put("c", "C");     // cache is full, so "b" (least recently used) goes
        assertNull(cache.get("b"));
        assertEquals("A", cache.get("a"));
        assertEquals("C", cache.get("c"));
        assertEquals(1, cache.getEvictions());
        assertEquals(2, cache.size());
    }

    @Test
    void countsHitsAndMisses() {
        ReportCache<String, String> cache = new ReportCache<>(5);
        assertEquals(0.0, cache.hitRate(), 0.001);
        cache.put("a", "A");
        cache.get("a");
        cache.get("a");
        cache.get("a");
        cache.get("missing");
        assertEquals(3, cache.getHits());
        assertEquals(1, cache.getMisses());
        assertEquals(75.0, cache.hitRate(), 0.001);
        assertTrue(cache.toString().contains("hit-rate=75.0%"));
    }

    @Test
    void removeAndClear() {
        ReportCache<String, String> cache = new ReportCache<>(5);
        cache.put("a", "A");
        cache.put("b", "B");
        cache.remove("a");
        assertEquals(1, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void sizeMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new ReportCache<String, String>(0));
    }
}
