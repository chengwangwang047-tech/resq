import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;

public class Day09DataGenerator {

    private static final int TOTAL = 10000;
    private static final int RESOURCE_COUNT = 100;

    private static final String AVAILABLE = "AVAILABLE";
    private static final String RESERVED = "RESERVED";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws IOException {
        Path outputPath = Path.of("docs/sql/day09-generated-data.sql");

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputPath))) {

            writer.println(
                    "INSERT INTO resource_slot_test "
                            + "(resource_id, start_time, end_time, status)");
            writer.println("VALUES");
            LocalDateTime baseTime = LocalDateTime.of(2026, 1, 1, 0, 0);

            for (int i = 0; i < TOTAL; i++) {

                int resourceId = i % RESOURCE_COUNT + 1;

                LocalDateTime startTime = baseTime.plusHours(i);
                LocalDateTime endTime = startTime.plusHours(1);

                String status;
                String startTimeText = startTime.format(FORMATTER);
                String endTimeText = endTime.format(FORMATTER);

                if (i % 2 == 0) {
                    status = AVAILABLE;
                } else {
                    status = RESERVED;
                }

                String row = "("
                        + resourceId
                        + ", '"
                        + startTimeText
                        + "', '"
                        + endTimeText
                        + "', '"
                        + status
                        + "')";

                if (i == TOTAL - 1) {
                    writer.println(row + ";");
                } else {
                    writer.println(row + ",");
                }
            }
        }
    }
}