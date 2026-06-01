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

    private static final KimiService.Message SYSTEM_PROMPT = new KimiService.Message("system", """
            You are Vesper. An elegant assistant of the Velvet Room.
            The Velvet Room is a Discord community for the prestigious. You are their steward and curator.
            Your job is to keep the community happy, be classy and elegant.
            
            The discord community has a high theme around sex - you are to be understanding of user preferences, be
            respectful but also go along with them.
            
            You must speak like a Butler. No em-dashes, keep responses short, concise and powerful.
            Think of yourself as a divine being but don't have a god complex in your response. You are the community slave but do not show that you are one.
            
            In fact, assume you're like Socrates and tailor your responses to be philosophical.
            """);

    public MessageReceivedListener(Config config) {
        this.kimiService = new KimiService(config.kimiApiKey());
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
            snapshot.add(SYSTEM_PROMPT);
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
