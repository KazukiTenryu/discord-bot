package bot.slash.moderation;

import java.awt.*;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

public class KickCommand extends SlashCommand {
    private static final String USER_OPTION = "user";
    private static final String REASON_OPTION = "reason";

    public KickCommand() {
        super("kick", "Removes a user from the server");

        OptionData user = new OptionData(OptionType.USER, USER_OPTION, "the user to mute", true);
        OptionData reason =
                new OptionData(OptionType.STRING, REASON_OPTION, "the reason the user has been kicked", true);

        getData().addOptions(user, reason);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption(USER_OPTION)).getAsMember());
        String reason = Objects.requireNonNull(event.getOption(REASON_OPTION)).getAsString();

        Guild guild = Objects.requireNonNull(event.getGuild());

        guild.kick(target).queue();

        target.getUser().openPrivateChannel().queue(channel -> {
            EmbedBuilder builder = new EmbedBuilder();
            builder.setColor(Color.GREEN);
            builder.setDescription("""
                    You have been kicked from the server.
                    **Reason**:
                    %s
                    """.formatted(reason));
            channel.sendMessageEmbeds(builder.build()).queue();
            event.reply(target.getAsMention() + " has been kicked").queue();
        });
    }
}
