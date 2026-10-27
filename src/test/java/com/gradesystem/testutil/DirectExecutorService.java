package com.gradesystem.testutil;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A fake ("mock") ExecutorService for tests: it runs each task immediately on the calling thread.
 * This makes tests predictable because nothing happens "later" on another thread.
 * It also counts how many tasks it received, so tests can check the pool was really used.
 */
public class DirectExecutorService extends AbstractExecutorService {
    private boolean shutdown;
    private int tasksRun;

    @Override
    public void execute(Runnable command) {
        if (shutdown) throw new IllegalStateException("Executor is shut down");
        tasksRun++;
        command.run();
    }

    public int getTasksRun() {
        return tasksRun;
    }

    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
}
