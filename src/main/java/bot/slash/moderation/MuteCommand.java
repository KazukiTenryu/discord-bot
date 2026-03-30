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

public class MuteCommand extends SlashCommand {
    private static final String USER_OPTION = "user";
    private static final String REASON_OPTION = "reason";
    private final String muteRole;

    public MuteCommand(Config config) {
        super("mute", "Mutes a given user so they cannot send messages anymore");

        this.muteRole = config.muteRole();

        OptionData duration = new OptionData(OptionType.USER, USER_OPTION, "the user to mute", true);
        OptionData reason =
                new OptionData(OptionType.STRING, REASON_OPTION, "the reason the user has been muted", true);
        getData().addOptions(duration, reason);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption(USER_OPTION)).getAsMember());
        String reason = Objects.requireNonNull(event.getOption(REASON_OPTION)).getAsString();

        Guild guild = event.getGuild();
        Role role =
                Objects.requireNonNull(guild).getRolesByName(muteRole, false).getFirst();

        if (target.getRoles().contains(role)) {
            event.reply(target.getAsMention() + " is already muted").queue();
            return;
        }

        guild.addRoleToMember(target, role).reason(reason).queue();
        target.getUser().openPrivateChannel().queue(channel -> {
            EmbedBuilder builder = new EmbedBuilder();
            builder.setColor(Color.RED);
            builder.setDescription("""
                    We're sorry to inform you but you have muted on the server.
                    This means you can no longer send any messages until you have been unmuted again.
                    """);
            channel.sendMessageEmbeds(builder.build()).queue();
            event.reply(target.getAsMention() + " has been muted").queue();
        });
    }
}
