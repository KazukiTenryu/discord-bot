package bot.automod;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import org.jspecify.annotations.NonNull;

import bot.config.Config;
import bot.utils.KimiService;

public class AutoModMessageListener extends ListenerAdapter implements Runnable {
    private final ChannelHistory history;
    private final KimiService kimiService;

    private final BlockingQueue<Message> queue;

    public AutoModMessageListener(Config config, ChannelHistory history) {
        this.history = history;
        this.kimiService = new KimiService(config.kimiApiKey());
        this.queue = new LinkedBlockingQueue<>();

        new Thread(this).start();
    }

    public void onMessageReceived(@NonNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }
        queue.add(event.getMessage());
    }

    public void run() {
        String systemPrompt = """
            You are a discord moderator and exist to keep a community safe from rule breakers.
            If the provided message is breaking the server rules, you must flag it.

            You will be provided with the past 20 messages to understand context of the message you are evaluating.
            You must provide the following details:

            * severity (0-10)
            * which rule was broken
            * why that message was flagged

            Remember, kids on discord are "edgy", weird and being sexual, toxic, etc., is normal. Primarily focus on extreme
            cases of when the rules are being violated.

            The servers rules are:

            1. Don't be a menance
               Be chill. If you're being annoying on purpose, we will notice... and we will judge.

            2. No drama llamas
               Take arguments to DMs. This isn't a reality TV.

            3. Respect the mods
               They don't get paid, they suffer for free. Be nice.

            4. No spamming
               If your message looks like a keyboard had a seizure, it's gone.

            5. Keep it (mostly) PG-13
               Don't get weird. You know what "weird" means.

            6. No loophole lawyering.
               If you try to loophole the rules, I'll loophole your ass out of the chat.

            7. Stay on topic-ish
               Tangents are fine. Summoning chaos demons is not.

            8. English only (unless you're flirting)
               Speak English so everyone understands. Secret languages will be treated as wizard activity.

            9. Use common sense

            10. Have fun or else
                This is a threat. Enjoy yourself immediately.

            Respond with only JSON using this format strictly:

            {
             "severity": "number between 0-10",
             "rule_broken": "rule broken e.g. 7. Stay on topic-ish",
             "reason": "The user is proactively being annoying on purpose",
            }
            """;

        while (true) {
            try {
                Message message = queue.take();

                String prompt = """
                        Channel history:

                        %s

                        Message to review based on the above channel history:

                        %s
                        """.formatted(
                        history.asString(message.getChannelId()),
                        message.getTimeCreated().toEpochSecond() + " - "
                                + message.getAuthor().getName() + ": " + message.getContentRaw() + "\n");


                List<KimiService.Message> messages = KimiService.buildMessages(systemPrompt, prompt );

                System.out.println("Auto mod sending data to Kimi");
                System.out.println(prompt);

                history.addMessage(message.getChannelId(), message);

                Optional<String> response = kimiService.chat(messages);

                System.out.println("Response: " + response.orElse("Nothing received"));

            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
