import domain.ComputeResource;
import domain.ResourceSlot;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Day 6: compare an unprotected reservation check with a synchronized one.
 */
public final class Day6ReservationConcurrencyLab {

    private static final int THREAD_COUNT = 100;
    private static final String AVAILABLE = "AVAILABLE";
    private static final String RESERVED = "RESERVED";

    private Day6ReservationConcurrencyLab() {
    }

    public static void main(String[] args) throws InterruptedException {
        ExperimentResult unprotected = runUnprotected();
        ExperimentResult synchronizedResult = runSynchronized();

        verify("unprotected", unprotected, THREAD_COUNT, 0);
        verify("synchronized", synchronizedResult, 1, THREAD_COUNT - 1);

        printResult("Unprotected race-condition example", unprotected);
        printResult("synchronized fix", synchronizedResult);
    }

    private static ExperimentResult runUnprotected() throws InterruptedException {
        ResourceSlot slot = newAvailableSlot();
        Counters counters = new Counters();
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch allCheckedAvailability = new CountDownLatch(THREAD_COUNT);
        Thread[] threads = new Thread[THREAD_COUNT];

        for (int i = 0; i < THREAD_COUNT; i++) {
            threads[i] = new Thread(() -> {
                counters.submitted.incrementAndGet();
                ready.countDown();
                await(start);

                boolean observedAvailable = AVAILABLE.equals(slot.getStatus());

                // Test coordination only: every request reads before any request writes.
                allCheckedAvailability.countDown();
                await(allCheckedAvailability);

                if (observedAvailable) {
                    slot.setStatus(RESERVED);
                    counters.successful.incrementAndGet();
                } else {
                    counters.failed.incrementAndGet();
                }
            }, "unprotected-reservation-" + i);
            threads[i].start();
        }

        ready.await();
        start.countDown();
        joinAll(threads);

        return new ExperimentResult(counters, slot.getStatus());
    }

    private static ExperimentResult runSynchronized() throws InterruptedException {
        ResourceSlot slot = newAvailableSlot();
        Counters counters = new Counters();
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[THREAD_COUNT];

        for (int i = 0; i < THREAD_COUNT; i++) {
            threads[i] = new Thread(() -> {
                counters.submitted.incrementAndGet();
                ready.countDown();
                await(start);

                synchronized (slot) {
                    if (AVAILABLE.equals(slot.getStatus())) {
                        slot.setStatus(RESERVED);
                        counters.successful.incrementAndGet();
                    } else {
                        counters.failed.incrementAndGet();
                    }
                }
            }, "synchronized-reservation-" + i);
            threads[i].start();
        }

        ready.await();
        start.countDown();
        joinAll(threads);

        return new ExperimentResult(counters, slot.getStatus());
    }

    private static ResourceSlot newAvailableSlot() {
        ComputeResource resource = new ComputeResource(
                "resource-day6",
                "A100",
                "GPU",
                "Day 6 concurrency lab",
                true
        );
        ResourceSlot slot = new ResourceSlot(
                "slot-day6",
                resource,
                "10:00",
                "11:00",
                LocalDate.of(2026, 9, 27),
                AVAILABLE
        );
        return slot;
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinAll(Thread[] threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private static void verify(
            String scenario,
            ExperimentResult result,
            int expectedSuccesses,
            int expectedFailures) {
        if (result.submitted != THREAD_COUNT
                || result.successfulReservations != expectedSuccesses
                || result.failedReservations != expectedFailures
                || !RESERVED.equals(result.finalStatus)) {
            throw new AssertionError(scenario + " result did not match expectations");
        }
    }

    private static void printResult(String scenario, ExperimentResult result) {
        System.out.println("--- " + scenario + " ---");
        System.out.println("Submitted: " + result.submitted);
        System.out.println("Successes: " + result.successfulReservations);
        System.out.println("Failures: " + result.failedReservations);
        System.out.println("Final slot status: " + result.finalStatus);
    }

    private static final class Counters {
        private final AtomicInteger submitted = new AtomicInteger();
        private final AtomicInteger successful = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
    }

    private static final class ExperimentResult {
        private final int submitted;
        private final int successfulReservations;
        private final int failedReservations;
        private final String finalStatus;

        private ExperimentResult(Counters counters, String finalStatus) {
            this.submitted = counters.submitted.get();
            this.successfulReservations = counters.successful.get();
            this.failedReservations = counters.failed.get();
            this.finalStatus = finalStatus;
        }
    }
}