package com.resq.day17;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resq.dto.ReservationCreateDTO;
import com.resq.exception.ReservationBusyException;
import com.resq.exception.ReservationConflictException;
import com.resq.service.ReservationLockService;
import com.resq.service.ReservationService;
import com.resq.mapper.ReservationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Service-level experiment. The deliberately unsafe simulation never uses production tables. */
@EnabledIfSystemProperty(named = "resq.day17.integration", matches = "true")
class Day17ConcurrencyComparisonTest {
    record Outcome(String category, String error, double millis) {}
    @FunctionalInterface interface Attempt { void run(int index) throws Exception; }

    @Test
    void compareThreeStrategiesAtOneHundredAndFiveHundredClients() throws Exception {
        List<Map<String, Object>> reports = new ArrayList<>();
        try (var db = new Day17TestDatabase()) {
            // Separate tables intentionally omit atomic claiming/transactions/active-slot constraints.
            // This is a controlled counterexample, never an alternative production service.
            db.jdbc.execute("CREATE TABLE day17_unsafe_slot(id BIGINT PRIMARY KEY,status VARCHAR(20) NOT NULL) ENGINE=InnoDB");
            db.jdbc.execute("CREATE TABLE day17_unsafe_reservation(id BIGINT AUTO_INCREMENT PRIMARY KEY,slot_id BIGINT NOT NULL,request_id VARCHAR(100) UNIQUE,status VARCHAR(20) NOT NULL) ENGINE=InnoDB");
            try {
                for (int clients : new int[]{100, 500}) {
                    reports.add(unsafe(db, clients));
                    reports.add(safe(db, clients, false));
                    reports.add(safe(db, clients, true));
                }
            } finally {
                db.jdbc.execute("DROP TABLE day17_unsafe_reservation");
                db.jdbc.execute("DROP TABLE day17_unsafe_slot");
            }
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM resource_slot", Integer.class));
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM compute_resource", Integer.class));
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("mysqlVersion", db.jdbc.queryForObject("SELECT VERSION()", String.class));
            output.put("transactionIsolation", db.jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
            output.put("clients", "virtual threads, start gate; JDBC pool max=16; same JVM; one hot slot per scenario");
            output.put("scope", "Service-level, not HTTP; actual production mapper/service/transaction/Redisson wrapper in safe groups; cache backend mocked in all safe groups");
            output.put("unsafeSchedule", "Every client reads AVAILABLE, releases its connection, then waits at a barrier. Adverse interleaving forced for reproducibility; timing cannot rank production strategies.");
            output.put("cleanup", "All fake users/resources/slots/reservations and unsafe tables removed; private processes removed by runner");
            output.put("results", reports);
            Path dir = Path.of(System.getProperty("day17.results"));
            Files.createDirectories(dir);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(dir.resolve("concurrency.json").toFile(), output);
        }
    }

    private Map<String, Object> unsafe(Day17TestDatabase db, int clients) throws Exception {
        db.jdbc.update("INSERT INTO day17_unsafe_slot VALUES(1,'AVAILABLE')");
        CyclicBarrier afterRead = new CyclicBarrier(clients);
        AtomicInteger reads = new AtomicInteger();
        try {
            var result = measure("UNSAFE_SIMULATION", clients, index -> {
                String status = db.jdbc.queryForObject("SELECT status FROM day17_unsafe_slot WHERE id=1", String.class);
                reads.incrementAndGet();
                afterRead.await(15, TimeUnit.SECONDS); // all connections have already been returned
                if (!"AVAILABLE".equals(status)) throw new ReservationConflictException("simulation unavailable");
                db.jdbc.update("UPDATE day17_unsafe_slot SET status='RESERVED' WHERE id=1");
                db.jdbc.update("INSERT INTO day17_unsafe_reservation(slot_id,request_id,status) VALUES(1,?,'PENDING')", "unsafe-" + index);
            });
            int rows = db.jdbc.queryForObject("SELECT COUNT(*) FROM day17_unsafe_reservation WHERE status IN ('PENDING','CONFIRMED')", Integer.class);
            result.put("activeReservations", rows);
            result.put("duplicateActiveReservations", Math.max(0, rows - 1));
            result.put("finalSlot", db.jdbc.queryForList("SELECT * FROM day17_unsafe_slot"));
            result.put("finalReservations", db.jdbc.queryForList("SELECT id,slot_id,status,request_id FROM day17_unsafe_reservation ORDER BY id"));
            result.put("availableReads", reads.get());
            result.put("dbStatements", 3 * clients);
            assertEquals(clients, result.get("success"));
            assertEquals(0, result.get("otherErrors"));
            assertEquals(clients, rows, "forced race must reproduce unsafe duplicate reservations");
            return result;
        } finally {
            db.jdbc.update("DELETE FROM day17_unsafe_reservation WHERE slot_id=1");
            db.jdbc.update("DELETE FROM day17_unsafe_slot WHERE id=1");
        }
    }

    private Map<String, Object> safe(Day17TestDatabase db, int clients, boolean lock) throws Exception {
        var f = db.fixture();
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger claims = new AtomicInteger();
        AtomicInteger inserts = new AtomicInteger();
        ReservationMapper measuredMapper = spy(db.mapper);
        doAnswer(call -> {
            updates.incrementAndGet();
            int affected = (int) call.callRealMethod();
            if (affected == 1) claims.incrementAndGet();
            return affected;
        }).when(measuredMapper).reserveSlotIfAvailable(f.slot());
        doAnswer(call -> {
            inserts.incrementAndGet();
            return call.callRealMethod();
        }).when(measuredMapper).insert(any());
        var measuredService = db.transactional(new ReservationService(measuredMapper, db.invalidator));
        var wrapper = new ReservationLockService(db.redis, measuredService);
        try {
            var result = measure(lock ? "REDISSON_MYSQL" : "MYSQL_ONLY", clients, index -> {
                var dto = new ReservationCreateDTO(f.slot(), "safe-" + UUID.randomUUID());
                if (lock) wrapper.create(f.user(), dto); else measuredService.create(f.user(), dto);
            });
            int rows = db.count(f.slot());
            result.put("activeReservations", db.jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE slot_id=? AND status IN ('PENDING','CONFIRMED')", Integer.class, f.slot()));
            result.put("duplicateActiveReservations", Math.max(0, rows - 1));
            result.put("finalSlot", db.jdbc.queryForList("SELECT id,resource_id,status FROM resource_slot WHERE id=?", f.slot()));
            result.put("finalReservations", db.jdbc.queryForList("SELECT id,user_id,slot_id,status,request_id FROM reservation WHERE slot_id=? ORDER BY id", f.slot()));
            result.put("conditionalUpdateAttempts", updates.get());
            result.put("successfulSlotClaims", claims.get());
            result.put("reservationInsertAttempts", inserts.get());
            result.put("poolMax", 16);
            assertEquals(1, result.get("success"));
            assertEquals(1, rows);
            assertEquals("RESERVED", db.state(f.slot()));
            assertEquals(result.get("lockTimeouts"), result.get("otherErrors"), "unexpected infrastructure failure; inspect errorTypes");
            assertFalse(db.redis.getLock("resq:lock:slot:" + f.slot()).isLocked(), "no lock may remain after workers finish");
            return result;
        } finally {
            db.cleanup(f);
        }
    }

    private Map<String, Object> measure(String strategy, int clients, Attempt attempt) throws Exception {
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        long began;
        List<Outcome> outcomes = new ArrayList<>();
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < clients; i++) {
                int index = i;
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    long nanos = System.nanoTime();
                    String category = "success", error = "";
                    try { attempt.run(index); }
                    catch (ReservationConflictException e) { category = "businessConflict"; }
                    catch (ReservationBusyException e) { category = "otherError"; error = "LOCK_WAIT_TIMEOUT"; }
                    catch (Exception e) { category = "otherError"; error = e.getClass().getSimpleName(); }
                    return new Outcome(category, error, (System.nanoTime() - nanos) / 1e6);
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS), "workers failed to become ready");
            began = System.nanoTime();
            start.countDown();
            for (var f : futures) outcomes.add(f.get(60, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            for (var f : futures) if (!f.isDone()) f.cancel(true);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "workers failed to terminate within bound");
        }
        double total = (System.nanoTime() - began) / 1e6;
        double[] sorted = outcomes.stream().mapToDouble(Outcome::millis).sorted().toArray();
        Map<String, Integer> errors = new TreeMap<>();
        for (var o : outcomes) if (!o.error().isEmpty()) errors.merge(o.error(), 1, Integer::sum);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", strategy);
        result.put("clients", clients);
        result.put("success", (int) outcomes.stream().filter(x -> x.category().equals("success")).count());
        result.put("businessConflicts", (int) outcomes.stream().filter(x -> x.category().equals("businessConflict")).count());
        result.put("otherErrors", (int) outcomes.stream().filter(x -> x.category().equals("otherError")).count());
        result.put("lockTimeouts", errors.getOrDefault("LOCK_WAIT_TIMEOUT", 0));
        result.put("errorTypes", errors);
        result.put("elapsedMs", total);
        result.put("throughputPerSec", clients / (total / 1000));
        result.put("latencyP50Ms", sorted[(int)Math.ceil(clients * .50) - 1]);
        result.put("latencyP95Ms", sorted[(int)Math.ceil(clients * .95) - 1]);
        result.put("latencyP99Ms", sorted[(int)Math.ceil(clients * .99) - 1]);
        System.out.printf("DAY17 %s clients=%d success=%s conflict=%s other=%s elapsedMs=%.3f%n", strategy, clients, result.get("success"), result.get("businessConflicts"), result.get("otherErrors"), total);
        return result;
    }
}
