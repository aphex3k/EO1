package com.aphex3k.update;

import java.util.ArrayDeque;

/**
 * One thread, two queues, in-order execution. Knows nothing about updates: it is the mechanism that
 * lets {@code UpdateManager} answer "who is allowed to change this?" with "only one thread, and it is
 * not a question".
 *
 * <p>Every mutation the update path performs — a check, an install, a reset, an operator upload, an
 * install's asynchronous result — arrives here as one item and runs to completion before the next
 * begins. That is the whole correctness argument, and it is why there is no ownership machinery: a
 * task that has started cannot be revoked, so a late write cannot land on anything another worker has
 * claimed, because there is no other worker. Items waiting their turn are therefore the only ones a
 * later request can replace, which is what coalescing below governs.
 *
 * <p>Two properties are deliberately <em>not</em> given by serialisation, and are handled by their
 * owners rather than here: another process holding a staged APK open (an install in flight is a fact
 * about {@code INSTALL_PENDING}, not about a thread), and a process killed at an arbitrary instruction
 * (crash durability lives in the persisted dual identity, not in a queue).
 *
 * <p>No {@code android.*} import, on purpose: the ordering rules in this class are the ones that took
 * eight review rounds to get wrong, so they are the ones worth testing directly, in plain JVM, without
 * a {@code Looper} — which is also why the product path never uses {@code HandlerThread}.
 */
public final class SerialWorker {

    /** App-defined: API 19 has no {@code java.util.function}. */
    public interface Task {
        void run() throws Exception;
    }

    /** Starts the drain loop. Production passes a real thread; a test drives {@link #drainOne()}. */
    public interface Runner {
        void start(Runnable loop);
    }

    /**
     * Receives a task that threw. Out here rather than a direct {@code Log.e} so this class stays
     * loadable by a JVM test — and so the owner gets to settle its own state, which is the one thing a
     * task that died halfway through cannot do for itself.
     */
    public interface FailureSink {
        void onTaskThrew(String description, Throwable error);
    }

    /** Coalescing key meaning "this item is a distinct fact; never merge it with another". */
    public static final long NO_COALESCE = 0L;

    /** A posted unit of work. Fields other than the flags are read only under {@code lock}. */
    public static final class Item {
        final long key;
        /** Raised, never lowered, by a later post with the same key. */
        int priority;
        final boolean urgent;
        /** Rewritten with {@link #body} when a coalesced post takes the slot, so {@code /state} never
         *  describes work that will not happen. */
        String description;
        Task body;
        volatile boolean started;
        boolean finished;

        Item(boolean urgent, long key, int priority, String description, Task body) {
            this.urgent = urgent;
            this.key = key;
            this.priority = priority;
            this.description = description;
            this.body = body;
        }

        /** What the worker is running, or what this item would do. For {@code /state} and tests. */
        public String description() {
            return description;
        }

    }

    private final Object lock = new Object();
    private final ArrayDeque<Item> urgentItems = new ArrayDeque<Item>();
    private final ArrayDeque<Item> normalItems = new ArrayDeque<Item>();
    private final Runner runner;
    private final FailureSink sink;

    /** Set by a reset or an upload: the running task may stop early. See {@link #preemptionRequested()}. */
    private volatile boolean preempt;
    private volatile boolean alive;
    private volatile String current;
    private volatile long currentSinceNs;
    /** Guarded by {@code lock} — see {@link #startIfNeeded()}. */
    private boolean threadStarted;

    public SerialWorker(Runner runner, FailureSink sink) {
        this.runner = runner;
        this.sink = sink;
    }

    /**
     * Queues an item, or merges it into the one already waiting with the same key.
     *
     * <p>Coalescing is what keeps a frame that has been sitting behind a three-minute download from
     * arriving at 180 identical checks. A waiting item is replaced <em>in place</em> — order among
     * distinct keys is not a tuning knob — but only by an item of at least equal priority, so an
     * operator's forced press cannot be quietly downgraded to an interval tick by a tick that happened
     * to be queued first. A lower-priority arrival is dropped instead: the pending item will do
     * everything it wanted.
     *
     * @param urgent    served before any normal item, but FIFO among other urgent items
     * @param coalesceKey {@link #NO_COALESCE} to always queue a distinct item
     * @param priority  0 for automatic work, 1 when a human asked for it
     * @return the item, which is the pending one when this call coalesced
     */
    public Item post(boolean urgent, long coalesceKey, int priority, String description, Task body) {
        synchronized (lock) {
            if (coalesceKey != NO_COALESCE) {
                Item pending = findPending(coalesceKey);
                if (pending != null) {
                    if (pending.priority > priority) {
                        // Strictly less demanding than what is already waiting: the pending item will
                        // do everything this one wanted, so drop the arrival rather than reorder.
                        return pending;
                    }
                    pending.priority = priority;
                    pending.body = body;
                    pending.description = description;
                    lock.notifyAll();
                    startIfNeeded();
                    return pending;
                }
            }
            Item item = new Item(urgent, coalesceKey, priority, description, body);
            (urgent ? urgentItems : normalItems).addLast(item);
            if (urgent) {
                preempt = true;
            }
            lock.notifyAll();
            startIfNeeded();
            return item;
        }
    }

    /** Convenience for work that is never urgent and never merges: an install's result, a roll-back. */
    public Item post(String description, Task body) {
        return post(false, NO_COALESCE, 0, description, body);
    }

    /**
     * Whether a running task has been asked to stand aside, clearing the request. Raised by an urgent
     * {@link #post} and read by the one long-running loop that can honour it; purely about latency,
     * because whichever way the running task goes, the item queued behind it runs afterwards and
     * wins. A task that never reads it merely takes longer to stop.
     */
    public boolean preemptionRequested() {
        if (!preempt) {
            return false;
        }
        preempt = false;
        return true;
    }

    /**
    /**
     * Blocks until this item has run, so a caller that must answer synchronously — {@code POST
     * /update}'s HTTP response — can hand its work over without pretending it is done.
     *
     * <p><b>Giving up is not cancelling, and never was meant to be.</b> Nothing here removes an item,
     * started or not: a caller whose patience expired has said something about its own socket, not
     * about whether the work is still wanted. An earlier revision pulled a not-yet-started item out of
     * the queue on timeout, which silently turned a slow response into a cancelled upload — and the
     * caller, believing the work was abandoned, then deleted the very file the item was queued to
     * stage. So the contract is exactly two outcomes: true means it ran and there is an answer to
     * report; false means it may still run, and the caller must leave everything it owns in place.
     *
     * <p>A caller passes its answer back through a holder it shares with the body — one final array is
     * enough, and the monitor below is what makes the write visible. Handing a result in through this
     * class instead would let the drain overwrite it.
     *
     * @return true when the item ran before the deadline
     */
    public boolean await(Item item, long timeoutMs) {
        long deadlineNs = System.nanoTime() + timeoutMs * 1000000L;
        synchronized (lock) {
            while (!item.finished) {
                long leftNs = deadlineNs - System.nanoTime();
                if (leftNs <= 0L) {
                    return false;
                }
                try {
                    lock.wait(leftNs / 1000000L, (int) (leftNs % 1000000L));
                } catch (InterruptedException e) {
                    // Consumed, not preserved: see the note in run(). And no drop for the same reason
                    // the timeout gives none — an interrupted waiter is not a cancelled task.
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Runs at most one queued item to completion on the calling thread. The drain loop is this, and a
     * test drives this, which is how the ordering rules above are checkable without a thread at all.
     *
     * @return false when nothing was queued
     */
    public boolean drainOne() {
        Item item;
        synchronized (lock) {
            item = urgentItems.isEmpty() ? normalItems.pollFirst() : urgentItems.pollFirst();
            if (item == null) {
                return false;
            }
            item.started = true;
        }
        preempt = false;
        current = item.description;
        currentSinceNs = System.nanoTime();
        Throwable failure = null;
        try {
            item.body.run();
        } catch (InterruptedException e) {
            // Consumed, not preserved — see the note in run(). Handing it back would leave the status
            // set for the loop's next wait(), which is the spin that design avoids.
            failure = e;
        } catch (Throwable t) {
            failure = t;
        } finally {
            current = null;
        }
        if (failure != null) {
            // Deliberately outside the try: the sink settles state, and a sink that itself throws must
            // not be able to end the loop that is the only thing running updates.
            report(item.description, failure);
        }
        synchronized (lock) {
            item.finished = true;
            lock.notifyAll();
        }
        return true;
    }

    /**
     * The production loop: wait for work, run it, never exit.
     *
     * <p>"Never" is the load-bearing word. This thread is the only thing that will ever run update
     * work, so an exit here is not a crash of one task, it is the end of self-update for the life of
     * the process — and with nothing watching, a frame would simply stop updating while every button
     * still answered. So the loop catches what {@link #drainOne()} could not (an {@code Error} in its
     * own bookkeeping, a wake-up that arrives as an interrupt), reports it through the sink, and
     * keeps going. The thread is therefore started once and expected never to stop: {@code post()}
     * will start another only if the first was never created at all (see {@link #startIfNeeded()}),
     * because a claim based on {@code alive} rather than on the start itself is a race that can
     * produce two drainers, and two drainers is the whole point of this class.
     */
    public void run() {
        alive = true;
        try {
            while (true) {
                try {
                    if (!drainOne()) {
                        synchronized (lock) {
                            while (urgentItems.isEmpty() && normalItems.isEmpty()) {
                                lock.wait();
                            }
                        }
                    }
                } catch (InterruptedException wake) {
                    // Deliberately NOT re-asserted, which is the opposite of the usual advice and the
                    // reason it is spelled out. A thread that keeps running must not keep the
                    // interrupt status set: Object.wait() throws immediately while it is, so a single
                    // preserved interrupt would turn this loop's idle wait into a throw it catches and
                    // retries — an update worker spinning on a core of a frame that is otherwise
                    // perfectly healthy, with nothing in the log to say so. Nothing in this app
                    // interrupts this thread on purpose; if it ever is, that is a wake-up.
                } catch (Throwable escaped) {
                    report("worker loop", escaped);
                }
            }
        } finally {
            // Only reachable via something no catch can hold (a ThreadDeath, a Stop-the-world error).
            // The loop's contract is that it does not exit, so a false here is worth reporting: it
            // means updates stopped rather than that they are slow.
            alive = false;
        }
    }

    /** Hands a failure to the sink, and cannot itself become the reason the loop died. */
    private void report(String description, Throwable error) {
        if (sink == null) {
            return;
        }
        try {
            sink.onTaskThrew(description, error);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Starts the drain thread exactly once. Called under {@code lock} for that reason: a flag read
     * from the new thread ({@code alive}) cannot answer "have I already started one?", because the
     * thread exists for some microseconds before it sets it, and a second thread running
     * {@link #drainOne()} beside the first would undo the only property this class has.
     */
    private void startIfNeeded() {
        if (threadStarted) {
            return;
        }
        // Claimed before starting, and under the lock, so two posts in the same microsecond cannot
        // both decide to start one: two threads calling drainOne() would be the end of the only
        // property this class has, and nothing later would detect it.
        threadStarted = true;
        try {
            runner.start(new Runnable() {
                @Override
                public void run() {
                    SerialWorker.this.run();
                }
            });
        } catch (Throwable startFailed) {
            // The claim is released only when no thread was ever created — the one path where the
            // worker is resurrected rather than kept alive, and it is retried by every subsequent
            // post rather than by a monitor, because a thread creation failure (an out-of-memory on a
            // device with a few hundred MB free is not exotic here) must not end updates for a process
            // that could make a thread a minute later. The bound on that silence is the rotation
            // interval: MainActivity's tick posts an install attempt unconditionally.
            threadStarted = false;
            report("worker start", startFailed);
        }
    }

    /** True once the drain loop is executing. A false here with queued items means updates are stuck. */
    public boolean isAlive() {
        return alive;
    }

    /** Description of the item running now, or null. */
    public String currentDescription() {
        return current;
    }

    /** How long the running item has been running. Only meaningful with {@link #currentDescription()}. */
    public long currentAgeMs() {
        long since = currentSinceNs;
        return current == null || since == 0L ? 0L : (System.nanoTime() - since) / 1000000L;
    }

    /** Items still waiting. The running one is not counted. */
    public int queueDepth() {
        synchronized (lock) {
            return urgentItems.size() + normalItems.size();
        }
    }

    /** Called under {@code lock}. A started item is never "pending": it is already the running one. */
    private Item findPending(long key) {
        Item found = findIn(urgentItems, key);
        return found != null ? found : findIn(normalItems, key);
    }

    private static Item findIn(ArrayDeque<Item> queue, long key) {
        for (Item item : queue) {
            if (item.key == key && !item.started) {
                return item;
            }
        }
        return null;
    }


}
