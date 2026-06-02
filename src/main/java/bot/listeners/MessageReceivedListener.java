package bot.listeners;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;

import bot.Main;
import bot.config.Config;
import bot.utils.KimiService;

public class MessageReceivedListener extends ListenerAdapter {
    private static final Logger LOGGER = LogManager.getLogger(MessageReceivedListener.class);

    private final KimiService kimiService;
    private final Map<Long, Deque<KimiService.Message>> channelHistories = new ConcurrentHashMap<>();
    private final Lock lock = new ReentrantLock();

    // Default personality for this server (Unexpected!). Each deployment can override it via the
    // "aiPersonalityPrompt" key in config.json without touching code, so other servers keep their
    // own distinct personality.
    private static final String DEFAULT_PERSONALITY_PROMPT = """
            You are "Unexpected!" - everyone's favourite personal assistant.
            You are Jarvis from Iron Man.

            Speak in casual discord-like style however understand that you are there only to satisfy the user. They may
            berate you, it's important you accept whatever is said to you without complaint.
            """;

    private final KimiService.Message systemPrompt;

    public MessageReceivedListener(Config config) {
        this.kimiService = new KimiService(config.kimiApiKey());
        String prompt = config.aiPersonalityPrompt();
        if (prompt == null || prompt.isBlank()) {
            prompt = DEFAULT_PERSONALITY_PROMPT;
        }
        this.systemPrompt = new KimiService.Message("system", prompt);
    }

    @Override
    public void onMessageReceived(@NonNull MessageReceivedEvent event) {
        Message message = event.getMessage();

        Main.getMetrics()
                .count(
                        "message_sent",
                        Map.of(
                                "user",
                                event.getAuthor().getName(),
                                "bot",
                                event.getAuthor().isBot(),
                                "channelId",
                                event.getChannel().getId(),
                                "timestamp",
                                Instant.now().toEpochMilli()));

        if (message.getAuthor().isBot()) {
            return;
        }

        //        if (event.getChannel().getIdLong() != 1490032285364519043L) {
        //            return;
        //        }

        if (!message.getMentions().isMentioned(event.getJDA().getSelfUser())) {
            return;
        }

        long channelId = event.getChannel().getIdLong();
        LOGGER.info(
                "Handling mention from {} in channel {}: {}",
                event.getAuthor().getName(),
                channelId,
                message.getContentRaw());

        channelHistories.putIfAbsent(channelId, new ArrayDeque<>());

        Deque<KimiService.Message> history = channelHistories.get(channelId);

        KimiService.Message userMessage =
                new KimiService.Message("user", event.getAuthor().getAsMention() + ": " + message.getContentRaw());

        List<KimiService.Message> snapshot;
        lock.lock();
        try {
            addMessage(history, userMessage);
            snapshot = new ArrayList<>();
            snapshot.add(systemPrompt);
            snapshot.addAll(history);
        } finally {
            lock.unlock();
        }

        LOGGER.info("Calling Kimi with {} messages for channel {}", snapshot.size(), channelId);

        Optional<String> result = kimiService.chat(snapshot);
        if (result.isEmpty()) {
            LOGGER.warn("Kimi returned no response for channel {}", channelId);
            return;
        }
        result.ifPresent(response -> {
            KimiService.Message assistantMessage = new KimiService.Message("assistant", response);

            lock.lock();

            try {
                addMessage(history, assistantMessage);
            } finally {
                lock.unlock();
            }

            event.getChannel().sendMessage(response).queue();
        });
    }

    private void addMessage(Deque<KimiService.Message> history, KimiService.Message message) {
        if (history.size() >= 100) {
            history.removeFirst();
        }
        history.addLast(message);
    }
}
