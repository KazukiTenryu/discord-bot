package bot.slash.family;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.database.jooq.tables.records.RelationshipsRecord;
import bot.slash.SlashCommand;

/**
 * Renders someone's family as an indented ASCII tree (wrapped in a code block so it stays
 * monospaced and the branches line up), plus a clickable summary of their direct ties below it.
 *
 * <p>The tree roots at the highest reachable ancestor and draws descendants; spouses are shown
 * inline (⚭) and ownership ties as markers (👑 owns / 🔗 owned). Display names are resolved over
 * REST because the bot runs with a light cache, and mentions don't render inside code blocks.
 */
public class FamilyTreeCommand extends SlashCommand {
    private static final String USER_OPTION = "user";
    private static final Color VELVET = new Color(0x6B0F2B);
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 60;

    private final RelationshipService service;

    public FamilyTreeCommand(RelationshipService service) {
        super("family-tree", "Show someone's family tree");
        this.service = service;
        getData().addOptions(new OptionData(OptionType.USER, USER_OPTION, "whose tree to show", true));
    }

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

        String guildId = Objects.requireNonNull(event.getGuild()).getId();
        String targetId = targetMember.getId();

        // Build lookup maps from a single fetch.
        Map<String, List<String>> children = new LinkedHashMap<>(); // parent -> children
        Map<String, List<String>> parents = new LinkedHashMap<>(); // child -> parents
        Map<String, String> spouse = new LinkedHashMap<>(); // symmetric
        Map<String, String> ownedBy = new LinkedHashMap<>(); // owned -> owner
        Map<String, List<String>> owns = new LinkedHashMap<>(); // owner -> owned
        Set<String> involved = new HashSet<>();

        for (RelationshipsRecord r : service.allForGuild(guildId)) {
            String a = r.getAUserId();
            String b = r.getBUserId();
            involved.add(a);
            involved.add(b);
            switch (r.getType()) {
                case RelationshipService.MARRIAGE -> {
                    spouse.put(a, b);
                    spouse.put(b, a);
                }
                case RelationshipService.PARENT -> {
                    children.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                    parents.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
                }
                case RelationshipService.OWNER -> {
                    ownedBy.put(b, a);
                    owns.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                }
                default -> {}
            }
        }

        if (!involved.contains(targetId)) {
            event.reply("💔 " + targetMember.getAsMention()
                            + " hasn't started a family yet. Try `/marry`, `/adopt`, or `/claim`!")
                    .queue();
            return;
        }

        // Walk up to the root ancestor (follow the first parent each step, guarding cycles).
        String root = targetId;
        Set<String> climbed = new HashSet<>();
        climbed.add(root);
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            List<String> ps = parents.get(root);
            if (ps == null || ps.isEmpty() || !climbed.add(ps.get(0))) {
                break;
            }
            root = ps.get(0);
        }

        // Resolve display names for everyone involved, then render asynchronously.
        event.deferReply().queue();

        Map<String, String> names = new ConcurrentHashMap<>();
        List<CompletableFuture<?>> lookups = new ArrayList<>();
        for (String id : involved) {
            lookups.add(event.getJDA().retrieveUserById(id).submit().handle((user, error) -> {
                names.put(id, user != null ? user.getEffectiveName() : ("user " + id));
                return null;
            }));
        }

        String finalRoot = root;
        CompletableFuture.allOf(lookups.toArray(new CompletableFuture[0])).whenComplete((v, error) -> {
            StringBuilder tree = new StringBuilder("```\n");
            Set<String> drawn = new HashSet<>();
            int[] count = {0};
            tree.append(label(finalRoot, names, spouse, owns, ownedBy, targetId)).append('\n');
            renderChildren(finalRoot, "", tree, drawn, count, children, names, spouse, owns, ownedBy, targetId);
            if (count[0] >= MAX_NODES) {
                tree.append("… (tree truncated)\n");
            }
            tree.append("```");

            String dynamics = dynamics(targetId, spouse, parents, children, ownedBy, owns);

            EmbedBuilder embed = new EmbedBuilder();
            embed.setColor(VELVET);
            embed.setTitle("🌳 Family Tree — " + names.getOrDefault(targetId, "Unknown"));
            embed.setDescription(tree + (dynamics.isEmpty() ? "" : "\n" + dynamics));

            event.getHook().sendMessageEmbeds(embed.build()).queue();
        });
    }

    private void renderChildren(
            String node,
            String prefix,
            StringBuilder out,
            Set<String> drawn,
            int[] count,
            Map<String, List<String>> children,
            Map<String, String> names,
            Map<String, String> spouse,
            Map<String, List<String>> owns,
            Map<String, String> ownedBy,
            String targetId) {
        if (!drawn.add(node)) {
            return; // already shown elsewhere — avoid cycles/duplicates
        }
        List<String> kids = children.get(node);
        if (kids == null) {
            return;
        }
        for (int i = 0; i < kids.size(); i++) {
            if (count[0]++ >= MAX_NODES) {
                return;
            }
            String kid = kids.get(i);
            boolean last = i == kids.size() - 1;
            out.append(prefix)
                    .append(last ? "└─ " : "├─ ")
                    .append(label(kid, names, spouse, owns, ownedBy, targetId))
                    .append('\n');
            renderChildren(
                    kid,
                    prefix + (last ? "   " : "│  "),
                    out,
                    drawn,
                    count,
                    children,
                    names,
                    spouse,
                    owns,
                    ownedBy,
                    targetId);
        }
    }

    private String label(
            String id,
            Map<String, String> names,
            Map<String, String> spouse,
            Map<String, List<String>> owns,
            Map<String, String> ownedBy,
            String targetId) {
        StringBuilder sb = new StringBuilder();
        String name = names.getOrDefault(id, "user " + id);
        sb.append(id.equals(targetId) ? "» " + name + " «" : name);
        if (spouse.containsKey(id)) {
            sb.append(" ⚭ ").append(names.getOrDefault(spouse.get(id), "?"));
        }
        if (owns.containsKey(id)) {
            sb.append(" 👑");
        }
        if (ownedBy.containsKey(id)) {
            sb.append(" 🔗");
        }
        return sb.toString();
    }

    private String dynamics(
            String targetId,
            Map<String, String> spouse,
            Map<String, List<String>> parents,
            Map<String, List<String>> children,
            Map<String, String> ownedBy,
            Map<String, List<String>> owns) {
        StringBuilder sb = new StringBuilder();
        if (spouse.containsKey(targetId)) {
            sb.append("💍 Married to <@").append(spouse.get(targetId)).append(">\n");
        }
        if (parents.containsKey(targetId)) {
            sb.append("👪 Parents: ").append(mentions(parents.get(targetId))).append('\n');
        }
        if (children.containsKey(targetId)) {
            sb.append("🍼 Children: ").append(mentions(children.get(targetId))).append('\n');
        }
        if (ownedBy.containsKey(targetId)) {
            sb.append("🔗 Owned by <@").append(ownedBy.get(targetId)).append(">\n");
        }
        if (owns.containsKey(targetId)) {
            sb.append("👑 Owns: ").append(mentions(owns.get(targetId))).append('\n');
        }
        return sb.toString();
    }

    private String mentions(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("<@").append(ids.get(i)).append('>');
        }
        return sb.toString();
    }
}
