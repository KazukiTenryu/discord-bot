package bot.slash.family;

import java.util.Objects;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

/**
 * Ends an ownership bond. Works in either direction — an owner can release the person they own,
 * or the owned person can step away — so either party can end it without the other's confirmation.
 */
public class ReleaseCommand extends SlashCommand {
    private static final String USER_OPTION = "user";
    private final RelationshipService service;

    public ReleaseCommand(RelationshipService service) {
        super("release", "End an ownership bond with someone");
        this.service = service;
        getData().addOptions(new OptionData(OptionType.USER, USER_OPTION, "the owner or owned person to release", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            event.reply("This only works inside a server.").setEphemeral(true).queue();
            return;
        }

        Member target = Objects.requireNonNull(event.getOption(USER_OPTION)).getAsMember();
        if (target == null) {
            event.reply("I couldn't find that member in this server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String guildId = Objects.requireNonNull(event.getGuild()).getId();
        boolean removed = service.removeOwnership(guildId, event.getUser().getId(), target.getId());

        if (!removed) {
            event.reply("There's no ownership bond between you and " + target.getAsMention() + ".")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.reply("🔓 " + event.getUser().getAsMention() + " and " + target.getAsMention()
                        + " are no longer bound.")
                .queue();
    }
}
