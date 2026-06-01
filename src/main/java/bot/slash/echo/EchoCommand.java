package bot.slash.echo;

import java.awt.Color;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

/**
 * Makes the bot repeat a given message, either as plain text or wrapped in an embed
 * (toggled by the {@code embed} option). The original command invocation is acknowledged
 * privately so the posted message reads as if the bot said it.
 */
public class EchoCommand extends SlashCommand {
    private static final String MESSAGE_OPTION = "message";
    private static final String EMBED_OPTION = "embed";
    private static final Color VELVET = new Color(0x6B0F2B);

    public EchoCommand() {
        super("echo", "Make the bot repeat a message as plain text or an embed");

        OptionData message = new OptionData(OptionType.STRING, MESSAGE_OPTION, "the text for the bot to send", true);
        OptionData embed = new OptionData(
                OptionType.BOOLEAN, EMBED_OPTION, "send as an embed instead of plain text (default: false)", false);

        getData().addOptions(message, embed);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String message = Objects.requireNonNull(event.getOption(MESSAGE_OPTION)).getAsString();
        boolean asEmbed = event.getOption(EMBED_OPTION) != null
                && Objects.requireNonNull(event.getOption(EMBED_OPTION)).getAsBoolean();

        if (asEmbed) {
            EmbedBuilder builder = new EmbedBuilder();
            builder.setColor(VELVET);
            builder.setDescription(message);
            event.getChannel().sendMessageEmbeds(builder.build()).queue();
        } else {
            event.getChannel().sendMessage(message).queue();
        }

        event.reply("Sent ✅").setEphemeral(true).queue();
    }
}
