package bot.slash.family;

import static bot.database.jooq.Tables.RELATIONSHIPS;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jooq.Condition;

import bot.database.Database;
import bot.database.jooq.tables.records.RelationshipsRecord;

/**
 * All persistence and business rules for the social-relationship commands
 * ({@code /marry}, {@code /adopt}, {@code /claim}, {@code /divorce}, {@code /family-tree}).
 *
 * <p>Everything lives in the single {@code relationships} table, distinguished by {@code type}:
 * <ul>
 *   <li>{@link #MARRIAGE} — {@code a} and {@code b} are spouses (symmetric, stored once).</li>
 *   <li>{@link #PARENT} — {@code a} is the parent, {@code b} is the child (directed).</li>
 *   <li>{@link #OWNER} — {@code a} is the owner, {@code b} is the owned (directed).</li>
 * </ul>
 * Relationships are scoped per guild so the same user can have different ties in different servers.
 */
public class RelationshipService {
    public static final String MARRIAGE = "marriage";
    public static final String PARENT = "parent";
    public static final String OWNER = "owner";

    /** A child may have at most this many parents (keeps the tree sane). */
    private static final int MAX_PARENTS = 2;
    /** Safety bound when walking the parent chain (cycle/runaway guard). */
    private static final int MAX_WALK = 100;

    private final Database database;

    public RelationshipService(Database database) {
        this.database = database;
    }

    /** Result of a validate/commit attempt: whether it succeeded and a user-facing message. */
    public record Outcome(boolean ok, String message) {}

    // ---------------------------------------------------------------- queries

    public List<RelationshipsRecord> allForGuild(String guildId) {
        return database.read(ctx ->
                ctx.selectFrom(RELATIONSHIPS).where(RELATIONSHIPS.GUILD_ID.eq(guildId)).fetch());
    }

    public Optional<String> spouseOf(String guildId, String userId) {
        RelationshipsRecord r = database.read(ctx -> ctx.selectFrom(RELATIONSHIPS)
                .where(RELATIONSHIPS.GUILD_ID.eq(guildId))
                .and(RELATIONSHIPS.TYPE.eq(MARRIAGE))
                .and(RELATIONSHIPS.A_USER_ID.eq(userId).or(RELATIONSHIPS.B_USER_ID.eq(userId)))
                .fetchAny());
        if (r == null) {
            return Optional.empty();
        }
        return Optional.of(userId.equals(r.getAUserId()) ? r.getBUserId() : r.getAUserId());
    }

    public List<String> parentsOf(String guildId, String childId) {
        return database.read(ctx -> ctx.select(RELATIONSHIPS.A_USER_ID)
                .from(RELATIONSHIPS)
                .where(RELATIONSHIPS.GUILD_ID.eq(guildId))
                .and(RELATIONSHIPS.TYPE.eq(PARENT))
                .and(RELATIONSHIPS.B_USER_ID.eq(childId))
                .fetch(RELATIONSHIPS.A_USER_ID));
    }

    public Optional<String> ownerOf(String guildId, String ownedId) {
        String owner = database.read(ctx -> ctx.select(RELATIONSHIPS.A_USER_ID)
                .from(RELATIONSHIPS)
                .where(RELATIONSHIPS.GUILD_ID.eq(guildId))
                .and(RELATIONSHIPS.TYPE.eq(OWNER))
                .and(RELATIONSHIPS.B_USER_ID.eq(ownedId))
                .fetchAny(RELATIONSHIPS.A_USER_ID));
        return Optional.ofNullable(owner);
    }

    private boolean edgeExists(String guildId, String type, String a, String b) {
        return database.read(ctx -> ctx.fetchExists(ctx.selectFrom(RELATIONSHIPS)
                .where(RELATIONSHIPS.GUILD_ID.eq(guildId))
                .and(RELATIONSHIPS.TYPE.eq(type))
                .and(RELATIONSHIPS.A_USER_ID.eq(a))
                .and(RELATIONSHIPS.B_USER_ID.eq(b))));
    }

    /** True if {@code ancestorId} sits somewhere above {@code nodeId} in the parent chain. */
    private boolean isAncestor(String guildId, String ancestorId, String nodeId) {
        Set<String> seen = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>(parentsOf(guildId, nodeId));
        int steps = 0;
        while (!stack.isEmpty() && steps++ < MAX_WALK) {
            String current = stack.pop();
            if (current.equals(ancestorId)) {
                return true;
            }
            if (seen.add(current)) {
                stack.addAll(parentsOf(guildId, current));
            }
        }
        return false;
    }

    // ------------------------------------------------------------- validation

    /** Checks whether a proposal could succeed right now, without committing anything. */
    public Outcome validate(String type, String guildId, String proposerId, String targetId) {
        return switch (type) {
            case MARRIAGE -> {
                if (spouseOf(guildId, proposerId).isPresent()) {
                    yield fail("💔 " + m(proposerId) + " is already married — /divorce first.");
                }
                if (spouseOf(guildId, targetId).isPresent()) {
                    yield fail("💔 " + m(targetId) + " is already married to someone else.");
                }
                yield ok();
            }
            case PARENT -> {
                if (edgeExists(guildId, PARENT, proposerId, targetId)) {
                    yield fail(m(proposerId) + " is already " + m(targetId) + "'s parent.");
                }
                if (parentsOf(guildId, targetId).size() >= MAX_PARENTS) {
                    yield fail(m(targetId) + " already has two parents.");
                }
                if (isAncestor(guildId, targetId, proposerId)) {
                    yield fail("🌀 That would loop the family tree — " + m(targetId)
                            + " is already an ancestor of " + m(proposerId) + ".");
                }
                yield ok();
            }
            case OWNER -> {
                Optional<String> existingOwner = ownerOf(guildId, targetId);
                if (existingOwner.isPresent()) {
                    yield fail(existingOwner.get().equals(proposerId)
                            ? "🔗 " + m(targetId) + " already belongs to you."
                            : "🔗 " + m(targetId) + " already belongs to " + m(existingOwner.get()) + ".");
                }
                yield ok();
            }
            default -> fail("Unknown request.");
        };
    }

    /** Re-validates and, if valid, writes the relationship. Returns the celebratory (or error) message. */
    public Outcome commit(String type, String guildId, String proposerId, String targetId) {
        Outcome validation = validate(type, guildId, proposerId, targetId);
        if (!validation.ok()) {
            return validation;
        }

        return switch (type) {
            case MARRIAGE -> {
                insert(guildId, MARRIAGE, proposerId, targetId);
                yield new Outcome(
                        true, "💍 " + m(proposerId) + " and " + m(targetId) + " are now married! Congratulations 🌹");
            }
            case PARENT -> {
                insert(guildId, PARENT, proposerId, targetId);
                yield new Outcome(
                        true, "🍼 " + m(proposerId) + " adopted " + m(targetId) + "! Welcome to the family.");
            }
            case OWNER -> {
                insert(guildId, OWNER, proposerId, targetId);
                yield new Outcome(true, "🔗 " + m(targetId) + " now belongs to " + m(proposerId) + ". 🌹");
            }
            default -> fail("Unknown request.");
        };
    }

    /** Ends the caller's marriage. Returns the (now ex-)spouse id, or empty if they weren't married. */
    public Optional<String> divorce(String guildId, String userId) {
        Optional<String> spouse = spouseOf(guildId, userId);
        if (spouse.isEmpty()) {
            return Optional.empty();
        }
        database.write(ctx -> ctx.deleteFrom(RELATIONSHIPS)
                .where(RELATIONSHIPS.GUILD_ID.eq(guildId))
                .and(RELATIONSHIPS.TYPE.eq(MARRIAGE))
                .and(RELATIONSHIPS.A_USER_ID.eq(userId).or(RELATIONSHIPS.B_USER_ID.eq(userId)))
                .execute());
        return spouse;
    }

    /** Removes the parent/child bond between two users (either direction). Returns true if one existed. */
    public boolean removeParent(String guildId, String userA, String userB) {
        return removeDirected(guildId, PARENT, userA, userB);
    }

    /** Removes the ownership bond between two users (either direction). Returns true if one existed. */
    public boolean removeOwnership(String guildId, String userA, String userB) {
        return removeDirected(guildId, OWNER, userA, userB);
    }

    private boolean removeDirected(String guildId, String type, String userA, String userB) {
        Condition forward = RELATIONSHIPS.A_USER_ID.eq(userA).and(RELATIONSHIPS.B_USER_ID.eq(userB));
        Condition reverse = RELATIONSHIPS.A_USER_ID.eq(userB).and(RELATIONSHIPS.B_USER_ID.eq(userA));
        int deleted = database.writeAndProvide(ctx -> ctx.deleteFrom(RELATIONSHIPS)
                .where(RELATIONSHIPS.GUILD_ID.eq(guildId))
                .and(RELATIONSHIPS.TYPE.eq(type))
                .and(forward.or(reverse))
                .execute());
        return deleted > 0;
    }

    // ----------------------------------------------------------------- helpers

    private void insert(String guildId, String type, String a, String b) {
        database.write(ctx -> ctx.insertInto(RELATIONSHIPS)
                .set(RELATIONSHIPS.GUILD_ID, guildId)
                .set(RELATIONSHIPS.TYPE, type)
                .set(RELATIONSHIPS.A_USER_ID, a)
                .set(RELATIONSHIPS.B_USER_ID, b)
                .execute());
    }

    private static Outcome ok() {
        return new Outcome(true, "");
    }

    private static Outcome fail(String message) {
        return new Outcome(false, message);
    }

    private static String m(String userId) {
        return "<@" + userId + ">";
    }
}
