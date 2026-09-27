# Day 6: reservation concurrency lab

## Goal

Start 100 Java threads together and have them attempt to reserve the same `ResourceSlot`. Compare an unprotected check-and-update with a `synchronized` version. Each run uses a fresh slot initially marked `AVAILABLE`.

## Run

From the repository root in WSL:

```sh
cd ~/resq
mkdir -p out/day6
javac -d out/day6 $(find src -name '*.java')
java -cp out/day6 Day6ReservationConcurrencyLab
```

The command compiles into `out/day6` and leaves existing files in `out` untouched.

## Experiment design

A start latch releases all 100 contenders together. In the unprotected example, a second test-only latch pauses requests after they read the slot status and before they write it. This makes the check-then-act race reproducible: every request sees `AVAILABLE`, then every request records a successful reservation. The latch coordinates the demonstration; it does not protect the slot status.

The synchronized version puts the availability check and status update inside `synchronized (slot)`. Only one thread can observe `AVAILABLE` and change it to `RESERVED`; the other 99 requests see `RESERVED` and fail.

A success is an accepted reservation attempt counted by the lab. The current project has no concrete reservation service or persistence layer, so this exercise does not create or save `Reservation` objects.

## Results

Run output:

```text
--- Unprotected race-condition example ---
Submitted: 100
Successes: 100
Failures: 0
Final slot status: RESERVED
--- synchronized fix ---
Submitted: 100
Successes: 1
Failures: 99
Final slot status: RESERVED
```

The final status alone cannot reveal duplicate accepted requests: both runs finish at `RESERVED`. The success and failure counts expose the race and show that the synchronized check-and-update allows only one accepted reservation.