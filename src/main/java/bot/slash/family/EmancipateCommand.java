package bot.slash.family;

import java.util.Objects;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

/**
 * Dissolves a parent/child bond. Works in either direction — a parent can disown a child or a
 * child can leave a parent — so no confirmation from the other side is required.
 */
public class EmancipateCommand extends SlashCommand {
    private static final String USER_OPTION = "user";
    private final RelationshipService service;

    public EmancipateCommand(RelationshipService service) {
        super("emancipate", "Dissolve a parent/child bond with someone");
        this.service = service;
        getData()
                .addOptions(
                        new OptionData(OptionType.USER, USER_OPTION, "the parent or child to part ways with", true));
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
        boolean removed = service.removeParent(guildId, event.getUser().getId(), target.getId());

        if (!removed) {
            event.reply("You and " + target.getAsMention() + " don't have a parent/child bond.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.reply("👋 " + event.getUser().getAsMention() + " and " + target.getAsMention() + " are no longer family.")
                .queue();
    }
}
