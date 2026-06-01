package bot.slash.family;

import java.util.Objects;
import java.util.Optional;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import bot.slash.SlashCommand;

/**
 * Ends the caller's marriage immediately — no confirmation from the other side required.
 * A member is only ever married to one person at a time, so no target is needed.
 */
public class DivorceCommand extends SlashCommand {
    private final RelationshipService service;

    public DivorceCommand(RelationshipService service) {
        super("divorce", "End your current marriage");
        this.service = service;
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            event.reply("This only works inside a server.").setEphemeral(true).queue();
            return;
        }

        String guildId = Objects.requireNonNull(event.getGuild()).getId();
        Optional<String> exSpouse = service.divorce(guildId, event.getUser().getId());

        if (exSpouse.isEmpty()) {
            event.reply("You're not married to anyone here.").setEphemeral(true).queue();
            return;
        }

        event.reply("💔 " + event.getUser().getAsMention() + " and <@" + exSpouse.get() + "> are no longer married.")
                .queue();
    }
}
