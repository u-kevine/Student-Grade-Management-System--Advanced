package com.gradesystem.service;

/** How many reports a batch produced and how long it took. */
public record BatchResult(int reportCount, long millis, int threads) {
}
