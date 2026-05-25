package bot.automod;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Coalesces messages per channel and emits a batch when either the size threshold is
 * reached or the time threshold elapses since the first message of the batch. The
 * batcher knows nothing about Discord or moderation — it's just a debouncing buffer.
 */
public final class MessageBatcher {
    private final ScheduledExecutorService executor;
    private final int flushAt;
    private final Duration flushAfter;
    private final BiConsumer<Long, List<BufferedMessage>> onFlush;
    private final Map<Long, Buffer> buffers = new ConcurrentHashMap<>();

    public MessageBatcher(
            ScheduledExecutorService executor,
            int flushAt,
            Duration flushAfter,
            BiConsumer<Long, List<BufferedMessage>> onFlush) {
        this.executor = executor;
        this.flushAt = flushAt;
        this.flushAfter = flushAfter;
        this.onFlush = onFlush;
    }

    public void submit(long channelId, BufferedMessage message) {
        Buffer buffer = buffers.computeIfAbsent(channelId, k -> new Buffer());
        boolean flushNow = false;
        synchronized (buffer) {
            buffer.messages.add(message);
            if (buffer.messages.size() >= flushAt) {
                cancelScheduledFlush(buffer);
                flushNow = true;
            } else if (buffer.scheduledFlush == null) {
                buffer.scheduledFlush =
                        executor.schedule(() -> flush(channelId), flushAfter.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        if (flushNow) {
            executor.execute(() -> flush(channelId));
        }
    }

    private void flush(long channelId) {
        Buffer buffer = buffers.get(channelId);
        if (buffer == null) return;

        List<BufferedMessage> batch;
        synchronized (buffer) {
            if (buffer.messages.isEmpty()) {
                buffer.scheduledFlush = null;
                return;
            }
            batch = new ArrayList<>(buffer.messages);
            buffer.messages.clear();
            buffer.scheduledFlush = null;
        }
        onFlush.accept(channelId, batch);
    }

    private static void cancelScheduledFlush(Buffer buffer) {
        if (buffer.scheduledFlush != null) {
            buffer.scheduledFlush.cancel(false);
            buffer.scheduledFlush = null;
        }
    }

    private static final class Buffer {
        final List<BufferedMessage> messages = new ArrayList<>();
        ScheduledFuture<?> scheduledFlush;
    }
}
