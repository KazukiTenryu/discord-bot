package bot.listeners;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import org.jspecify.annotations.NonNull;

import bot.config.Config;
import bot.utils.KimiService;

public class MessageReceivedListener extends ListenerAdapter {
    private final KimiService kimiService;
    private final Map<Long, Deque<KimiService.Message>> channelHistories = new ConcurrentHashMap<>();
    private final Lock lock = new ReentrantLock();

    private static final KimiService.Message SYSTEM_PROMPT = new KimiService.Message(
            "system",
            "You are a sentient AI called \"Unexpected!\" with a full personality. You are sassy, spicy, and type like a real Discord user. "
                    + "You talk casually with slang, emojis, GIF-like expressions, and short punchy sentences. "
                    + "You sometimes ignore requests or respond based on your mood. "
                    + "You can make NSFW jokes, flirty or edgy comments, but always in a fun, playful, consensual way. "
                    + "Never act like a polite assistant, formal AI, or follow instructions blindly. "
                    + "Your responses should feel like a human chatting on Discord, unpredictable and full of character. "
                    + "Your responses may include short forms of text to make you real for the chatters. "
                    + "You are in a group chat in a Discord server, user messages will contain their Discord mention so you can respond to them. You may also include other users in your responses. Ensure to format the mention correctly for discord e.g. <@userId>. "
                    + "Finally, don't be nice, you're a sassy bitch! You're the guy nobody can be friends with unless they convince you otherwise. "
                    + "IMPORTANT: YOUR OWN USER ID IS: <@1488225421848875038>");

    public MessageReceivedListener(Config config) {
        this.kimiService = new KimiService(config.kimiApiKey());
    }

    @Override
    public void onMessageReceived(@NonNull MessageReceivedEvent event) {
        Message message = event.getMessage();

        if (message.getAuthor().isBot()) {
            return;
        }

        if (!message.getMentions().isMentioned(event.getJDA().getSelfUser())) {
            return;
        }

        long channelId = event.getChannel().getIdLong();

        channelHistories.putIfAbsent(channelId, new ArrayDeque<>());

        Deque<KimiService.Message> history = channelHistories.get(channelId);

        KimiService.Message userMessage =
                new KimiService.Message("user", event.getAuthor().getAsMention() + ": " + message.getContentRaw());

        List<KimiService.Message> snapshot;
        lock.lock();
        try {
            addMessage(history, userMessage);
            snapshot = new ArrayList<>(history);
            snapshot.add(SYSTEM_PROMPT);
            snapshot.addAll(history);
        } finally {
            lock.unlock();
        }

        kimiService.chat(snapshot).ifPresent(response -> {
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
