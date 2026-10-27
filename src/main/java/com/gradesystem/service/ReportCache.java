package com.gradesystem.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small LRU ("Least Recently Used") cache.
 *
 * It remembers up to maxSize results. When it is full and a new one arrives, it forgets the one
 * that was used longest ago. LinkedHashMap does the hard work: with accessOrder = true it moves
 * an entry to the end every time we read it, so the eldest entry is the least recently used.
 * get, put and remove are all O(1).
 *
 * Every method is synchronized, so only one thread uses the cache at a time.
 *
 * @param <K> the key type, e.g. a student ID
 * @param <V> the value type, e.g. the report text
 */
public class ReportCache<K, V> {

    private final int maxSize;
    private final LinkedHashMap<K, V> entries;
    private long hits;
    private long misses;
    private long evictions;

    public ReportCache(int maxSize) {
        if (maxSize < 1) throw new IllegalArgumentException("Cache size must be at least 1");
        this.maxSize = maxSize;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                boolean tooBig = size() > ReportCache.this.maxSize;
                if (tooBig) evictions++;
                return tooBig;
            }
        };
    }

    /** Returns the cached value, or null if it is not in the cache. Updates hit/miss counters. */
    public synchronized V get(K key) {
        V value = entries.get(key);
        if (value == null) misses++;
        else hits++;
        return value;
    }

    public synchronized void put(K key, V value) {
        entries.put(key, value);
    }

    /** Forget one entry, e.g. because the student's grades changed. */
    public synchronized void remove(K key) {
        entries.remove(key);
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long getHits() { return hits; }
    public synchronized long getMisses() { return misses; }
    public synchronized long getEvictions() { return evictions; }

    /** Percentage of lookups that were found in the cache. */
    public synchronized double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : 100.0 * hits / total;
    }

    @Override
    public synchronized String toString() {
        return String.format("size=%d/%d hits=%d misses=%d evictions=%d hit-rate=%.1f%%",
                entries.size(), maxSize, hits, misses, evictions, hitRate());
    }
}
