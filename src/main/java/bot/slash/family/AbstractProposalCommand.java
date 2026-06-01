package bot.slash.family;

import java.awt.Color;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

/**
 * Shared scaffolding for the consent-based relationship commands. Each subclass picks a
 * {@link RelationshipService} type and supplies the wording; this class handles option parsing,
 * up-front validation, and posting the Accept / Decline prompt that the target responds to.
 *
 * <p>The buttons carry everything needed to resolve the proposal in their custom id:
 * {@code fam:<y|n>:<type>:<proposerId>:<targetId>}. {@code GlobalEventListener} parses that on click,
 * so no pending-proposal state has to be stored.
 */
public abstract class AbstractProposalCommand extends SlashCommand {
    protected static final String USER_OPTION = "user";
    protected static final Color VELVET = new Color(0x6B0F2B);

    protected final RelationshipService service;
    private final String type;

    protected AbstractProposalCommand(
            String name, String description, String userOptionDescription, String type, RelationshipService service) {
        super(name, description);
        this.service = service;
        this.type = type;
        getData().addOptions(new OptionData(OptionType.USER, USER_OPTION, userOptionDescription, true));
    }

    /** The prompt body shown to the target, e.g. "💍 @a wants to marry @b!". */
    protected abstract String proposalText(String proposerId, String targetId);

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            event.reply("This only works inside a server.").setEphemeral(true).queue();
            return;
        }

        Member targetMember =
                Objects.requireNonNull(event.getOption(USER_OPTION)).getAsMember();
        if (targetMember == null) {
            event.reply("I couldn't find that member in this server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        User proposer = event.getUser();
        User target = targetMember.getUser();

        if (target.isBot()) {
            event.reply("Bots aren't interested, sorry.").setEphemeral(true).queue();
            return;
        }
        if (target.getId().equals(proposer.getId())) {
            event.reply("You can't do that to yourself. 🌹").setEphemeral(true).queue();
            return;
        }

        String guildId = Objects.requireNonNull(event.getGuild()).getId();
        RelationshipService.Outcome check = service.validate(type, guildId, proposer.getId(), target.getId());
        if (!check.ok()) {
            event.reply(check.message()).setEphemeral(true).queue();
            return;
        }

        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(VELVET);
        embed.setDescription(proposalText(proposer.getId(), target.getId()));

        String idSuffix = type + ":" + proposer.getId() + ":" + target.getId();
        Button accept = Button.success("fam:y:" + idSuffix, "Accept");
        Button decline = Button.danger("fam:n:" + idSuffix, "Decline");

        event.reply(target.getAsMention())
                .addEmbeds(embed.build())
                .addComponents(ActionRow.of(accept, decline))
                .queue();
    }
}
