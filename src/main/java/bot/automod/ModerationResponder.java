package bot.automod;

import java.awt.Color;
import java.util.List;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Translates a {@link Verdict} into Discord side-effects: an in-channel reply for
 * warns/alerts, and a paging embed in the audit channel for alerts.
 */
public final class ModerationResponder {
    private static final Logger LOGGER = LogManager.getLogger(ModerationResponder.class);
    private static final String AUDIT_CHANNEL = "auto-mod";
    private static final int EMBED_FIELD_LIMIT = 1024;

    public void respond(JDA jda, long sourceChannelId, Verdict verdict) {
        LOGGER.info("verdict for channel {}: severity={}", sourceChannelId, verdict.severity());
        if (!verdict.requiresAction()) return;

        TextChannel sourceChannel = jda.getTextChannelById(sourceChannelId);
        // postInChannelReply(sourceChannel, verdict);

        if (verdict.requiresHumanModerator()) {
            pageHumanModerators(jda, sourceChannel, verdict);
        }
    }

    private static void postInChannelReply(TextChannel channel, Verdict verdict) {
        if (channel == null || verdict.reply().isBlank()) return;
        channel.sendMessage(verdict.reply()).queue(null, err -> LOGGER.warn("Failed to post in-channel reply", err));
    }

    private static void pageHumanModerators(JDA jda, TextChannel sourceChannel, Verdict verdict) {
        List<TextChannel> auditChannels = jda.getTextChannelsByName(AUDIT_CHANNEL, true);
        if (auditChannels.isEmpty()) {
            LOGGER.warn("No #{} channel found to page moderators", AUDIT_CHANNEL);
            return;
        }

        auditChannels
                .getFirst()
                .sendMessageEmbeds(buildAlertEmbed(sourceChannel, verdict).build())
                .queue(null, err -> LOGGER.warn("Failed to post audit alert", err));
    }

    private static EmbedBuilder buildAlertEmbed(TextChannel sourceChannel, Verdict verdict) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Auto-mod alert")
                .setColor(Color.RED)
                .addField("Severity", verdict.severity().name(), true);

        if (sourceChannel != null) {
            embed.addField("Channel", sourceChannel.getAsMention(), true);
        }
        if (!verdict.users().isEmpty()) {
            embed.addField("Users", String.join(", ", verdict.users()), false);
        }
        if (!verdict.reason().isBlank()) {
            embed.addField("Reason", truncate(verdict.reason(), EMBED_FIELD_LIMIT), false);
        }
        if (!verdict.summary().isBlank()) {
            embed.addField("Summary", truncate(verdict.summary(), EMBED_FIELD_LIMIT), false);
        }
        return embed;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
