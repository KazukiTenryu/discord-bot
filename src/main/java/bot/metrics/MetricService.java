package bot.metrics;

import static bot.database.jooq.Tables.METRICS;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import bot.database.Database;
import tools.jackson.databind.ObjectMapper;

public class MetricService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final ExecutorService service = Executors.newSingleThreadExecutor();
    private final Database database;

    public MetricService(Database database) {
        this.database = database;
    }

    public void count(String event, Map<String, Object> dimensions) {
        LocalDateTime happenedAt = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        String serializedDimensions = serializeDimensions(dimensions);

        service.submit(() -> processEvent(event, happenedAt, dimensions.isEmpty() ? null : serializedDimensions));
    }

    private void processEvent(String event, LocalDateTime happenedAt, String dimensionsJson) {
        database.write(ctx -> ctx.insertInto(METRICS)
                .set(METRICS.EVENT, event)
                .set(METRICS.HAPPENED_AT, happenedAt)
                .set(METRICS.DIMENSIONS, dimensionsJson)
                .execute());
    }

    private static String serializeDimensions(Map<String, Object> dimensions) {
        return OBJECT_MAPPER.writeValueAsString(dimensions);
    }
}
