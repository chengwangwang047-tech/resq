import domain.ComputeResource;
import domain.ResourceSlot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Day 5 practice: search the same ResourceSlot data with Stream and a loop.
 */
public class Day5AvailableSlotSearch {

    public static void main(String[] args) {
        testNormalMatch();
        testEmptySlots();
        testNoMatch();

        System.out.println("Day 5 available-slot search checks passed.");
    }

    public static List<ResourceSlot> searchAvailableSlotsWithStream(
            List<ResourceSlot> slots,
            String resourceType,
            LocalDate date,
            String status) {

        return slots.stream()
                .filter(slot -> Objects.equals(resourceType, slot.getResource().getType()))
                .filter(slot -> Objects.equals(date, slot.getDate()))
                .filter(slot -> Objects.equals(status, slot.getStatus()))
                .toList();
    }

    public static List<ResourceSlot> searchAvailableSlotsWithLoop(
            List<ResourceSlot> slots,
            String resourceType,
            LocalDate date,
            String status) {

        List<ResourceSlot> result = new ArrayList<>();

        for (ResourceSlot slot : slots) {
            if (Objects.equals(resourceType, slot.getResource().getType())
                    && Objects.equals(date, slot.getDate())
                    && Objects.equals(status, slot.getStatus())) {
                result.add(slot);
            }
        }

        return result;
    }

    private static void testNormalMatch() {
        LocalDate targetDate = LocalDate.of(2026, 9, 18);
        List<ResourceSlot> slots = List.of(
                slot("slot-1", "GPU", targetDate, "AVAILABLE"),
                slot("slot-2", "CPU", targetDate, "AVAILABLE"),
                slot("slot-3", "GPU", targetDate, "RESERVED"),
                slot("slot-4", "GPU", LocalDate.of(2026, 9, 19), "AVAILABLE"),
                slot("slot-5", "GPU", targetDate, "AVAILABLE")
        );

        List<ResourceSlot> streamResult =
                searchAvailableSlotsWithStream(slots, "GPU", targetDate, "AVAILABLE");
        List<ResourceSlot> loopResult =
                searchAvailableSlotsWithLoop(slots, "GPU", targetDate, "AVAILABLE");

        check(slotIds(streamResult).equals(List.of("slot-1", "slot-5")),
                "normal Stream search should return slot-1 and slot-5");
        check(streamResult.equals(loopResult),
                "normal Stream and loop searches should match");
        check(slots.size() == 5, "the searches should not modify the original list");

        printResult("normal match", streamResult, loopResult);
    }

    private static void testEmptySlots() {
        LocalDate targetDate = LocalDate.of(2026, 9, 18);
        List<ResourceSlot> slots = List.of();

        List<ResourceSlot> streamResult =
                searchAvailableSlotsWithStream(slots, "GPU", targetDate, "AVAILABLE");
        List<ResourceSlot> loopResult =
                searchAvailableSlotsWithLoop(slots, "GPU", targetDate, "AVAILABLE");

        check(streamResult.isEmpty(), "an empty input should produce an empty Stream result");
        check(streamResult.equals(loopResult),
                "empty-input Stream and loop searches should match");

        printResult("empty input", streamResult, loopResult);
    }

    private static void testNoMatch() {
        LocalDate targetDate = LocalDate.of(2026, 9, 18);
        List<ResourceSlot> slots = List.of(
                slot("slot-1", "GPU", targetDate, "AVAILABLE"),
                slot("slot-2", "CPU", targetDate, "AVAILABLE")
        );

        List<ResourceSlot> streamResult =
                searchAvailableSlotsWithStream(
                        slots, "GPU", LocalDate.of(2026, 9, 20), "AVAILABLE");
        List<ResourceSlot> loopResult =
                searchAvailableSlotsWithLoop(
                        slots, "GPU", LocalDate.of(2026, 9, 20), "AVAILABLE");

        check(streamResult.isEmpty(), "a no-match search should return an empty Stream result");
        check(streamResult.equals(loopResult),
                "no-match Stream and loop searches should match");

        printResult("no match", streamResult, loopResult);
    }

    private static ResourceSlot slot(
            String id,
            String resourceType,
            LocalDate date,
            String status) {

        ComputeResource resource = new ComputeResource(
                "resource-" + id,
                resourceType + " resource",
                resourceType,
                "Day 5 search data",
                true
        );

        return new ResourceSlot(id, resource, "09:00", "10:00", date, status);
    }

    private static List<String> slotIds(List<ResourceSlot> slots) {
        return slots.stream()
                .map(ResourceSlot::getId)
                .toList();
    }

    private static void printResult(
            String scenario,
            List<ResourceSlot> streamResult,
            List<ResourceSlot> loopResult) {

        System.out.println(scenario + " Stream: " + slotIds(streamResult));
        System.out.println(scenario + " loop:   " + slotIds(loopResult));
        System.out.println(scenario + " consistent: " + streamResult.equals(loopResult));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
