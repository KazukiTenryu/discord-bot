package bot.slash.moderation;

import static bot.database.jooq.Tables.USER_NOTES;

import net.dv8tion.jda.api.entities.Member;

import bot.database.Database;

public class AuditService {
    private final Database database;

    public AuditService(Database database) {
        this.database = database;
    }

    public void saveAudit(Member issuer, Member target, String type, String message) {
        database.write(ctx -> ctx.insertInto(USER_NOTES)
                .set(USER_NOTES.USER_ID, target.getId())
                .set(USER_NOTES.CONTENT, message)
                .set(USER_NOTES.FROM_USER, issuer.getAsMention())
                .set(USER_NOTES.AUDIT_TYPE, type)
                .execute());
    }
}
