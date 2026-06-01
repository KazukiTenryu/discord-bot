package bot.slash.echo;

import java.awt.Color;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

/**
 * Looks up an existing message by ID in the current channel and re-posts its text content,
 * either as plain text or wrapped in an embed (toggled by the {@code embed} option).
 * The message must live in the same channel the command is run in — Discord only allows
 * fetching a message by ID from the channel that holds it.
 */
public class EchoFromCommand extends SlashCommand {
    private static final String MESSAGE_ID_OPTION = "message_id";
    private static final String EMBED_OPTION = "embed";
    private static final Color VELVET = new Color(0x6B0F2B);

    public EchoFromCommand() {
        super("echofrom", "Repeat the text of an existing message (by ID) from this channel");

        OptionData messageId = new OptionData(
                OptionType.STRING, MESSAGE_ID_OPTION, "the ID of the message to copy (must be in this channel)", true);
        OptionData embed = new OptionData(
                OptionType.BOOLEAN, EMBED_OPTION, "send as an embed instead of plain text (default: false)", false);

        getData().addOptions(messageId, embed);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String rawId = Objects.requireNonNull(event.getOption(MESSAGE_ID_OPTION))
                .getAsString()
                .trim();
        boolean asEmbed = event.getOption(EMBED_OPTION) != null
                && Objects.requireNonNull(event.getOption(EMBED_OPTION)).getAsBoolean();

        long messageId;
        try {
            messageId = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            event.reply("`" + rawId + "` isn't a valid message ID.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.getChannel()
                .retrieveMessageById(messageId)
                .queue(
                        source -> {
                            String content = source.getContentRaw();
                            if (content.isBlank()) {
                                event.reply("That message has no text content to echo.")
                                        .setEphemeral(true)
                                        .queue();
                                return;
                            }

                            if (asEmbed) {
                                EmbedBuilder builder = new EmbedBuilder();
                                builder.setColor(VELVET);
                                builder.setDescription(content);
                                event.getChannel()
                                        .sendMessageEmbeds(builder.build())
                                        .queue();
                            } else {
                                event.getChannel().sendMessage(content).queue();
                            }

                            event.reply("Sent ✅").setEphemeral(true).queue();
                        },
                        error -> event.reply("Couldn't find a message with that ID in this channel.")
                                .setEphemeral(true)
                                .queue());
    }
}
