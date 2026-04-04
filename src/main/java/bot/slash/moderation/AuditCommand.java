package bot.slash.moderation;

import static bot.database.jooq.Tables.USER_NOTES;

import java.awt.*;
import java.util.List;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.database.Database;
import bot.database.jooq.tables.records.UserNotesRecord;
import bot.slash.SlashCommand;

public class AuditCommand extends SlashCommand {
    private static final String USER_OPTION = "user";
    private final Database database;

    public AuditCommand(Database database) {
        super("audit", "See all the notes left against a user");

        this.database = database;

        getData().addOptions(new OptionData(OptionType.USER, USER_OPTION, "the user to view notes for", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption(USER_OPTION)).getAsMember());

        List<UserNotesRecord> notes = database.read(ctx -> ctx.selectFrom(USER_NOTES)
                .where(USER_NOTES.USER_ID.eq(target.getId()))
                .fetch());

        StringBuilder sb = new StringBuilder();

        EmbedBuilder builder = new EmbedBuilder();
        builder.setColor(Color.GREEN);
        builder.setTitle("/audit");

        if (notes.isEmpty()) {
            sb.append("There are no actions against ").append(target.getAsMention());
        } else {
            sb.append("There are ")
                    .append(notes.size())
                    .append(" actions against ")
                    .append(target.getAsMention());
        }

        for (UserNotesRecord note : notes) {
            sb.append("\n\n**")
                    .append(note.getAuditType().toUpperCase())
                    .append(" by ")
                    .append(note.getFromUser())
                    .append("**");
            sb.append("\n");
            sb.append(note.getContent());
            sb.append("\n");
            sb.append(note.getCreatedAt());
            sb.append("\n\n");
        }

        builder.setDescription(sb.toString());

        event.replyEmbeds(builder.build()).queue();
    }
}
