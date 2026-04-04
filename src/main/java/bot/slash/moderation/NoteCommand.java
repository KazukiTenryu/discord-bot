package bot.slash.moderation;

import java.awt.*;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;
import bot.utils.TimeUtils;

public class NoteCommand extends SlashCommand {
    private static final String USER_OPTION = "user";
    private static final String CONTENT_OPTION = "content";
    private final AuditService auditService;

    public NoteCommand(AuditService auditService) {
        super("note", "Leave a note about a user");

        this.auditService = auditService;

        OptionData user = new OptionData(OptionType.USER, USER_OPTION, "the user to add a note against", true);
        OptionData reason = new OptionData(OptionType.STRING, CONTENT_OPTION, "the note to add", true);

        getData().addOptions(user, reason);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption(USER_OPTION)).getAsMember());

        String content = Objects.requireNonNull(event.getOption(CONTENT_OPTION)).getAsString();

        auditService.saveAudit(event.getMember(), target, "note", content);

        EmbedBuilder builder = new EmbedBuilder();
        builder.setColor(Color.GREEN);
        builder.setTitle("/note");
        builder.setDescription(
                """
                %s wrote a note about %s (id: %s)

                %s
                """.formatted(event.getUser().getName(), event.getUser().getId(), target.getAsMention(), content));
        builder.setFooter(TimeUtils.now());

        event.replyEmbeds(builder.build()).queue();
    }
}
