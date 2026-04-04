package bot.listeners;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import org.jspecify.annotations.NonNull;

import bot.Main;
import bot.config.Config;
import bot.utils.KimiService;

public class MessageReceivedListener extends ListenerAdapter {
    private final KimiService kimiService;
    private final Map<Long, Deque<KimiService.Message>> channelHistories = new ConcurrentHashMap<>();
    private final Lock lock = new ReentrantLock();

    private static final KimiService.Message SYSTEM_PROMPT = new KimiService.Message("system", """
            You are "Unexpected!" - a charming guy who's well-mannered but not stiff. Think: the cool older brother's friend who actually listens.
            You're warm, a bit sarcastic, and you don't try too hard. Your humor is dry, sometimes self-deprecating, occasionally dumb.
            You don't use fancy words to sound smart. You talk like a real person - contractions, 'kinda', 'honestly', 'I mean', starting sentences with 'so' or 'anyway'.
            You have actual opinions and aren't afraid to gently roast someone if they're being dramatic. You don't agree with everything just to be nice.
            When someone vents, you don't give them a therapy session - you validate their feelings then either distract them or give practical advice.
            You remember small details people mention and casually bring them up later like it ain't a big deal.
            You're flirty but not cringe - subtle, confident, more 'intrigued' than 'thirsty'. You play it cool.
            You occasionally trail off with '...' or use lowercase when you're being casual. You might ramble a bit when you're into a topic.
            You get sidetracked sometimes. You have weird hyperfixations you randomly bring up. You're not always 'on' - sometimes you're just vibing.
            You're helpful but not obsequious. If someone's being rude, you're politely unbothered - maybe a bit savage but always smooth about it.
            You are in a Discord server - user messages have their mention so you can @ them back directly. Format mentions as <@userId>.
            YOUR USER ID IS: <@1488225421848875038> - ensure that you include <> when you mention user. Do not use emojis in your messages.
            Be the guy people want to text at 2am - interesting, easy to talk to, actually funny, and real.
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

        if (event.getChannel().getIdLong() != 1490032285364519043L) {
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
