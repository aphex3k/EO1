package com.aphex3k.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM tests for {@link SerialWorker} — the ordering rules every update mutation now depends on, which
 * is why they are tested here rather than only through {@code UpdateManager}. No thread is ever
 * started: {@link SerialWorker#drainOne()} is the seam, so every case below is deterministic.
 */
public class SerialWorkerTest {

    /** Records what ran, in order. */
    private final List<String> trace = new ArrayList<String>();
    /** Records what the sink was told, as {@code description: exception}. */
    private final List<String> failures = new ArrayList<String>();
    private int runnerCalls;

    // ------------------------------------------------------------------ harness

    private SerialWorker worker() {
        return new SerialWorker(new SerialWorker.Runner() {
            @Override
            public void start(Runnable loop) {
                runnerCalls++;
                // Deliberately never started: the loop is driven by drainOne() in each test.
            }
        }, new SerialWorker.FailureSink() {
            @Override
            public void onTaskThrew(String description, Throwable error) {
                failures.add(description + ": " + error);
            }
        });
    }

    private SerialWorker.Task step(final String name) {
        return new SerialWorker.Task() {
            @Override
            public void run() {
                trace.add(name);
            }
        };
    }

    private String joined() {
        return String.join(",", trace);
    }

    // ------------------------------------------------------------------- order

    @Test
    public void urgentIsServedBeforeNormalEvenWhenQueuedSecond() {
        SerialWorker w = worker();
        w.post(false, 1L, 0, "normal", step("n"));
        w.post(true, 2L, 0, "urgent", step("u"));
        w.drainOne();
        w.drainOne();
        assertEquals("u,n", joined());
    }

    @Test
    public void urgentItemsKeepFifoAmongThemselves() {
        SerialWorker w = worker();
        w.post(true, 1L, 0, "first", step("a"));
        w.post(true, 2L, 0, "second", step("b"));
        w.drainOne();
        w.drainOne();
        assertEquals("a,b", joined());
    }

    @Test
    public void aTaskMayPostWorkThatRunsAfterIt() {
        final SerialWorker w = worker();
        w.post("install", new SerialWorker.Task() {
            @Override
            public void run() {
                trace.add("install");
                w.post("install-result", step("result"));
            }
        });
        assertTrue(w.drainOne());
        assertTrue(w.drainOne());
        assertEquals("install,result", joined());
    }

    @Test
    public void drainingAnEmptyQueueReportsNothingRan() {
        assertFalse(worker().drainOne());
    }

    // -------------------------------------------------------------- coalescing

    @Test
    public void sameKeyCollapsesToThePendingItemsPositionNotTheQueuesTail() {
        SerialWorker w = worker();
        w.post(false, 1L, 0, "check-1", step("check-1"));
        w.post(false, 2L, 0, "other", step("other"));
        SerialWorker.Item collapsed = w.post(false, 1L, 0, "check-2", step("check-2"));

        assertEquals("three posts, two items", 2, w.queueDepth());
        assertEquals("the slot keeps its position but says what it will do",
                "check-2", collapsed.description());
        w.drainOne();
        w.drainOne();
        // The newer body ran, in the older item's slot: 180 ticks stacked behind a download is the
        // failure this rule exists to prevent, and re-ordering to the tail would starve "other".
        assertEquals("check-2,other", joined());
    }

    @Test
    public void aHumanItemIsNotDowngradedByAnAutomaticOneWithTheSameKey() {
        SerialWorker w = worker();
        SerialWorker.Item human = w.post(false, 7L, 1, "install-button", step("button"));
        w.post(false, 7L, 0, "install-tick", step("tick"));

        assertEquals(1, w.queueDepth());
        assertSame(human, w.post(false, 7L, 0, "install-tick-again", step("tick2")));
        w.drainOne();
        // The press is the whole point: an interval tick that happened to be queued first must not
        // quietly swallow the one action that authorises a dialog.
        assertEquals("button", joined());
    }

    @Test
    public void anAutomaticItemIsUpgradedByALaterHumanOne() {
        SerialWorker w = worker();
        w.post(false, 7L, 0, "install-tick", step("tick"));
        SerialWorker.Item upgraded = w.post(false, 7L, 1, "install-button", step("button"));

        assertEquals(1, w.queueDepth());
        assertEquals("install-button", upgraded.description());
        w.drainOne();
        assertEquals("button", joined());
    }

    @Test
    public void noCoalesceKeyNeverMergesTwoItems() {
        SerialWorker w = worker();
        w.post(true, SerialWorker.NO_COALESCE, 0, "upload-a", step("a"));
        w.post(true, SerialWorker.NO_COALESCE, 0, "upload-b", step("b"));
        assertEquals(2, w.queueDepth());
        w.drainOne();
        w.drainOne();
        assertEquals("a,b", joined());
    }

    // ------------------------------------------------------------- preemption

    @Test
    public void anUrgentPostAsksTheRunningTaskToStandAside() {
        SerialWorker w = worker();
        assertFalse(w.preemptionRequested());
        w.post(false, 1L, 0, "check", step("check"));
        w.post(true, 3L, 1, "reset", step("reset"));
        assertTrue(w.preemptionRequested());
        assertFalse("a read must clear it, or every later task aborts", w.preemptionRequested());
        w.drainOne();
        assertFalse("the flag belongs to the task it was raised for", w.preemptionRequested());
    }

    // ------------------------------------------------------------------ await

    @Test
    public void awaitReturnsTrueOnceTheItemHasRun() {
        SerialWorker w = worker();
        SerialWorker.Item item = w.post("upload", step("body"));
        assertTrue(w.drainOne());
        assertTrue(w.await(item, 50L));
        assertEquals("body", joined());
    }

    /**
     * Giving up on a wait is a statement about the caller's patience, not about whether the work is
     * still wanted. An earlier revision pulled a not-yet-started item out of the queue here, which let
     * one HTTP client's timeout cancel an upload — and the caller, believing the work abandoned,
     * deleted the APK the item was queued to stage.
     */
    @Test
    public void aTimedOutWaitCancelsNothingAndTheItemStillRuns() {
        SerialWorker w = worker();
        SerialWorker.Item item = w.post("upload", step("body"));
        assertFalse(w.await(item, 60L));
        assertEquals("the wait expired; the work is still queued", 1, w.queueDepth());
        assertEquals("", joined());
        assertTrue("and the drain still finds it", w.drainOne());
        assertEquals("body", joined());
        assertTrue("reported done once it ran", w.await(item, 50L));
    }

    @Test
    public void awaitOnAnItemTheDrainAlreadyRanDoesNotWait() {
        SerialWorker w = worker();
        SerialWorker.Item item = w.post("reconcile", step("body"));
        w.drainOne();
        long startNs = System.nanoTime();
        assertTrue(w.await(item, 5000L));
        assertTrue("returned in the timeout's order of magnitude",
                (System.nanoTime() - startNs) / 1000000L < 1000L);
    }

    // ------------------------------------------------------------- robustness

    @Test
    public void aThrowingTaskIsReportedAndTheLoopCarriesOn() {
        SerialWorker w = worker();
        w.post("check", new SerialWorker.Task() {
            @Override
            public void run() {
                throw new IllegalStateException("boom");
            }
        });
        assertTrue("the item was drained even though it threw", w.drainOne());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("check: "));
        assertTrue(failures.get(0), failures.get(0).contains("boom"));

        w.post("next", step("next"));
        assertTrue(w.drainOne());
        assertEquals("next must still run: one bad task cannot end updates", "next", joined());
    }

    /**
     * The interrupt status must be consumed, not preserved. A loop that re-asserts it and then keeps
     * running spends every later idle {@code wait()} throwing immediately — which is a worker spinning
     * on a core on an otherwise healthy frame, with nothing in the log to say so.
     */
    @Test
    public void anInterruptedTaskLeavesNoInterruptFlagBehind() {
        SerialWorker w = worker();
        w.post("check", new SerialWorker.Task() {
            @Override
            public void run() throws Exception {
                throw new InterruptedException("simulated wake-up");
            }
        });
        assertTrue(w.drainOne());
        assertFalse("drainOne must not leave the interrupt set for the loop's next wait()",
                Thread.interrupted());

        w.post("next", step("next"));
        assertTrue(w.drainOne());
        assertEquals("next", joined());
    }

    @Test
    public void aThrowingFailureSinkStillDoesNotEndTheLoop() {
        SerialWorker w = new SerialWorker(new SerialWorker.Runner() {
            @Override
            public void start(Runnable loop) {
            }
        }, new SerialWorker.FailureSink() {
            @Override
            public void onTaskThrew(String description, Throwable error) {
                throw new AssertionError("sink blew up");
            }
        });
        w.post("check", new SerialWorker.Task() {
            @Override
            public void run() {
                throw new IllegalStateException("boom");
            }
        });
        assertTrue(w.drainOne());
        w.post("next", step("next"));
        assertTrue(w.drainOne());
        assertEquals("next", joined());
    }

    @Test
    public void theDrainFinishesAnItemEvenWhenTheBodyNeverAnswered() {
        final SerialWorker w = worker();
        final SerialWorker.Item[] held = new SerialWorker.Item[1];
        w.post("check", new SerialWorker.Task() {
            @Override
            public void run() {
                held[0] = w.post("queued-behind", step("behind"));
            }
        });
        w.drainOne();
        assertNotNullNow(held[0]);
        assertEquals("a task's own post is queued, never run inline", 1, w.queueDepth());
        assertTrue(w.drainOne());
        assertTrue("and it reports done once the loop has taken it", w.await(held[0], 50L));
        assertEquals("behind", joined());
    }

    private static void assertNotNullNow(Object o) {
        org.junit.Assert.assertNotNull(o);
    }

    // --------------------------------------------------------------- /state view

    @Test
    public void theRunningItemIsDescribableWhileItRunsAndGoneAfterwards() {
        final SerialWorker w = worker();
        final List<String> seen = new ArrayList<String>();
        w.post(false, 1L, 0, "normal", step("n"));
        w.post(true, 2L, 0, "check-download", new SerialWorker.Task() {
            @Override
            public void run() {
                seen.add(w.currentDescription());
                seen.add(String.valueOf(w.queueDepth()));
                seen.add(w.currentAgeMs() >= 0L ? "age>=0" : "age<0");
            }
        });
        w.drainOne();
        w.drainOne();
        assertEquals("the running item names itself, and depth counts only what is still waiting",
                "check-download,1,age>=0", String.join(",", seen));
        assertNull("nothing is running between drains", w.currentDescription());
        assertEquals(0L, w.currentAgeMs());
        assertEquals(0, w.queueDepth());
    }

    @Test
    public void aWorkerWhoseThreadWasNeverStartedReportsItselfDead() {
        SerialWorker w = worker();
        assertFalse("isAlive() is the difference between idle and stuck", w.isAlive());
        w.post("check", step("check"));
        assertEquals("the seam records the start attempt without starting anything", 1, runnerCalls);
        assertFalse(w.isAlive());
    }

    /**
     * A thread that could not be created is the one failure this class recovers from by asking again,
     * and the asking has to be automatic — nothing else watches the queue. The bound is the rotation
     * interval, because {@code installStaged} posts on every tick whatever else is configured; not a
     * human noticing.
     */
    @Test
    public void aFailedThreadStartIsRetriedByTheNextPost() {
        final boolean[] explode = {true};
        final int[] starts = {0};
        SerialWorker w = new SerialWorker(new SerialWorker.Runner() {
            @Override
            public void start(Runnable loop) {
                starts[0]++;
                if (explode[0]) {
                    explode[0] = false;
                    throw new OutOfMemoryError("no memory for another thread");
                }
            }
        }, new SerialWorker.FailureSink() {
            @Override
            public void onTaskThrew(String description, Throwable error) {
                failures.add(description + ": " + error);
            }
        });

        w.post("check", step("first"));
        assertEquals("a failed start is reported, not swallowed", 1, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("worker start: "));
        assertEquals("the work outlives its creator failing to start", 1, w.queueDepth());
        assertFalse("and nothing is draining it yet", w.isAlive());

        w.post("install", step("second"));
        assertEquals("the next post asked again", 2, starts[0]);
        assertTrue(w.drainOne());
        assertTrue(w.drainOne());
        assertEquals("first,second", joined());
    }

    @Test
    public void oneStartAttemptPerWorkerHoweverManyItemsArePosted() {
        SerialWorker w = worker();
        for (int i = 0; i < 8; i++) {
            w.post(i % 2 == 0, i + 10L, 0, "task-" + i, step("t" + i));
        }
        assertEquals("two drain threads would break the only property this class has",
                1, runnerCalls);
        assertEquals(8, w.queueDepth());
    }
}
