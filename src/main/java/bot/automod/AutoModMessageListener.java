package bot.automod;

import java.awt.Color;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;

import bot.automod.ModerationService.ModerationResult;
import bot.automod.RuleModerator.RuleJudgment;
import bot.config.Config;
import bot.utils.KimiService;

public class AutoModMessageListener extends ListenerAdapter implements Runnable {
    private static final Logger LOGGER = LogManager.getLogger(AutoModMessageListener.class);
    private static final int MODERATION_CONCLUSIVE_SEVERITY = 8;
    private static final int AUDIT_MIN_SEVERITY = 1;
    private static final int QUEUE_CAPACITY = 500;
    private static final String AUDIT_CHANNEL = "auto-mod";

    private final Config config;
    private final ChannelHistory history;
    private final ModerationService moderationService;
    private final RuleModerator ruleModerator;
    private final BlockingQueue<Message> queue;

    public AutoModMessageListener(Config config, ChannelHistory history) {
        this.config = config;
        this.history = history;
        this.moderationService = new ModerationService(config.openAiApiKey());
        this.ruleModerator = new RuleModerator(new KimiService(config.kimiApiKey()));
        this.queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

        new Thread(this).start();
    }

    public void onMessageReceived(@NonNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        if (config.autoModIgnoredChannels().contains(event.getChannel().getName())) {
            return;
        }

        if (!queue.offer(event.getMessage())) {
            LOGGER.warn("Automod queue full ({}); dropping message {}", QUEUE_CAPACITY, event.getMessageId());
        }
    }

    public void run() {
        while (true) {
            try {
                Message message = queue.take();
                evaluate(message);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void evaluate(Message message) {
        String content = message.getContentRaw();
        String historyText = history.asString(message.getChannelId());
        String messageLine = message.getTimeCreated().toEpochSecond() + " - "
                + message.getAuthor().getName() + ": " + content + "\n";
        history.addMessage(message.getChannelId(), message);

        if (content.isBlank()) {
            return;
        }

        Optional<ModerationResult> moderation = moderationService.moderate(content);
        int safetySeverity = moderation.map(ModerationResult::severity).orElse(0);
        Optional<RuleJudgment> rule;
        if (safetySeverity >= MODERATION_CONCLUSIVE_SEVERITY) {
            rule = Optional.empty();
            LOGGER.debug(
                    "Skipping rule judge for {}: safety gate already conclusive (severity {})",
                    message.getId(),
                    safetySeverity);
        } else {
            rule = ruleModerator.judge(historyText, messageLine);
        }

        AutoModVerdict verdict = AutoModVerdict.merge(moderation.orElse(null), rule.orElse(null));

        LOGGER.info(
                "Automod verdict for {} by {}: severity={} via {} ({}) [safety={}/{}, rule={}/{}]",
                message.getId(),
                message.getAuthor().getName(),
                verdict.severity(),
                verdict.source(),
                verdict.reason(),
                verdict.safetySeverity(),
                verdict.safetyCategory(),
                verdict.ruleSeverity(),
                verdict.ruleBroken());

        if (verdict.severity() >= AUDIT_MIN_SEVERITY) {
            postAudit(message, verdict);
        }
    }

    private void postAudit(Message message, AutoModVerdict verdict) {
        if (!message.isFromGuild()) {
            return;
        }

        List<TextChannel> channels = message.getGuild().getTextChannelsByName(AUDIT_CHANNEL, true);
        if (channels.isEmpty()) {
            LOGGER.warn("No #{} channel found to post automod verdict for {}", AUDIT_CHANNEL, message.getId());
            return;
        }

        String triggerLabel = verdict.source() == AutoModVerdict.Source.SAFETY ? "Category" : "Rule";
        String trigger =
                verdict.source() == AutoModVerdict.Source.SAFETY ? verdict.safetyCategory() : verdict.ruleBroken();

        String description = """
                **User:** %s
                **Channel:** %s
                **Severity:** %d/10
                **%s:** %s
                **Reason:** %s
                **[Jump to message](%s)**

                >>> %s"""
                .formatted(
                        message.getAuthor().getAsMention(),
                        message.getChannel().getAsMention(),
                        verdict.severity(),
                        triggerLabel,
                        trigger,
                        verdict.reason(),
                        message.getJumpUrl(),
                        truncate(message.getContentRaw(), 1000));

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(colorFor(verdict.severity()))
                .setDescription(description)
                .setTimestamp(message.getTimeCreated());

        channels.getFirst().sendMessageEmbeds(embed.build()).queue();
    }

    private static Color colorFor(int severity) {
        if (severity >= 8) {
            return Color.RED;
        }
        if (severity >= 4) {
            return Color.ORANGE;
        }
        return Color.YELLOW;
    }

    private static String truncate(String text, int max) {
        if (text == null || text.isBlank()) {
            return "*(no text)*";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
