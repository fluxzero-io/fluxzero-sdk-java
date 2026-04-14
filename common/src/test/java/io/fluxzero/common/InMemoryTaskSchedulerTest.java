package io.fluxzero.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryTaskSchedulerTest {

    @Test
    void executeExpiredTasksAsyncDelegatesExpiredTasksToWorkerPool() {
        RecordingExecutorService workerPool = new RecordingExecutorService();
        InMemoryTaskScheduler scheduler = new InMemoryTaskScheduler("scheduler-test", Clock.systemUTC(), workerPool);

        try {
            scheduler.schedule(System.currentTimeMillis() - 1, () -> {});
            scheduler.schedule(System.currentTimeMillis() - 1, () -> {});

            scheduler.executeExpiredTasksAsync();

            assertEquals(2, workerPool.submittedTasks.size());
        } finally {
            scheduler.shutdown();
        }
    }

    private static class RecordingExecutorService extends AbstractExecutorService {
        private final AtomicBoolean shutdown = new AtomicBoolean();
        private final List<Runnable> submittedTasks = new CopyOnWriteArrayList<>();

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            return submittedTasks;
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return isShutdown();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            submittedTasks.add(command);
        }
    }
}
