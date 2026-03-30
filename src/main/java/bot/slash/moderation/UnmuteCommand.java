package bot.slash.moderation;

import java.awt.*;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.config.Config;
import bot.slash.SlashCommand;

public class UnmuteCommand extends SlashCommand {
    private static final String USER_OPTION = "user";
    private static final String REASON_OPTION = "reason";
    private final String muteRole;

    public UnmuteCommand(Config config) {
        super("unmute", "Unmutes a user so they can send messages again");

        this.muteRole = config.muteRole();

        getData().addOptions(new OptionData(OptionType.USER, USER_OPTION, "the user to unmute", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption(USER_OPTION)).getAsMember());

        Guild guild = event.getGuild();
        Role role =
                Objects.requireNonNull(guild).getRolesByName(muteRole, false).getFirst();

        if (!target.getRoles().contains(role)) {
            event.reply(target.getAsMention() + " is already unmuted").queue();
            return;
        }

        guild.removeRoleFromMember(target, role).queue();

        target.getUser().openPrivateChannel().queue(channel -> {
            EmbedBuilder builder = new EmbedBuilder();
            builder.setColor(Color.GREEN);
            builder.setDescription("""
                    You have been unmuted from the server which means you are able to send messages again.
                    Next time, please avoid sending messages that may go against the server rules to avoid being muted again in the future.
                    """);
            channel.sendMessageEmbeds(builder.build()).queue();
            event.reply(target.getAsMention() + " has been unmuted").queue();
        });
    }
}
