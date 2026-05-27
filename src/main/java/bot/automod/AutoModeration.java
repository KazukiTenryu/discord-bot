package bot.automod;

import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;

import bot.config.Config;
import bot.utils.KimiService;

/**
 * JDA entry point for AI moderation. Filters incoming messages, hands eligible ones
 * to a {@link MessageBatcher}, and dispatches resulting batches through the
 * classifier and responder.
 */
public class AutoModeration extends ListenerAdapter {
    private static final Logger LOGGER = LogManager.getLogger(AutoModeration.class);

    private static final int FLUSH_AT = 10;
    private static final Duration FLUSH_AFTER = Duration.ofSeconds(5);
    private static final int EXECUTOR_THREADS = 2;

    private final MessageBatcher batcher;
    private final ModerationClassifier classifier;
    private final ModerationResponder responder;
    private final Set<String> ignoredChannels;

    private volatile JDA jda;

    public AutoModeration(Config config) {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(EXECUTOR_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "auto-mod");
            thread.setDaemon(true);
            return thread;
        });

        this.classifier = new ModerationClassifier(new KimiService(config.kimiApiKey()));
        this.responder = new ModerationResponder();
        this.batcher = new MessageBatcher(executor, FLUSH_AT, FLUSH_AFTER, this::onBatchReady);
        this.ignoredChannels = buildIgnoredChannels(config);
    }

    @Override
    public void onMessageReceived(@NonNull MessageReceivedEvent event) {
        if (!shouldModerate(event)) return;

        this.jda = event.getJDA();
        long channelId = event.getChannel().getIdLong();
        BufferedMessage buffered = new BufferedMessage(
                event.getAuthor().getAsMention(), event.getMessage().getContentRaw());

        batcher.submit(channelId, buffered);
    }

    private boolean shouldModerate(MessageReceivedEvent event) {
        if (event.getChannelType() != ChannelType.TEXT) return false;
        if (event.getAuthor().isBot()) return false;
        if (event.getMessage().getContentRaw().isBlank()) return false;
        if (ignoredChannels.contains(event.getChannel().getName())) return false;
        return true;
    }

    private void onBatchReady(long channelId, java.util.List<BufferedMessage> batch) {
        JDA currentJda = this.jda;
        if (currentJda == null) {
            LOGGER.warn("Batch ready for channel {} but JDA reference not yet captured; skipping", channelId);
            return;
        }
        classifier.classify(channelId, batch).ifPresent(verdict -> responder.respond(currentJda, channelId, verdict));
    }

    private static Set<String> buildIgnoredChannels(Config config) {
        Set<String> ignored = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (config.autoModIgnoredChannels() != null) {
            ignored.addAll(config.autoModIgnoredChannels());
        }
        return ignored;
    }
}
