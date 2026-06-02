package bot.slash.playlist;

import java.awt.Color;
import java.util.List;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.slash.SlashCommand;
import bot.slash.music.MusicFormat;
import bot.slash.music.PlayerManager;
import bot.slash.playlist.PlaylistService.StoredTrack;

/**
 * /playlist — manage your personal playlist. One playlist per user; {@code add} resolves a song's
 * metadata via LavaPlayer and stores it, the rest read/edit the stored rows.
 */
public class PlaylistCommand extends SlashCommand {
    private static final Logger LOGGER = LogManager.getLogger(PlaylistCommand.class);
    private static final Color ACCENT = new Color(0x1DB954);
    // Discord embed descriptions cap at 4096 chars; keep the listing well under that.
    private static final int MAX_LISTED = 25;

    private static final String SONG_OPTION = "song";
    private static final String USER_OPTION = "user";
    private static final String NUMBER_OPTION = "number";

    private final PlaylistService playlistService;

    public PlaylistCommand(PlaylistService playlistService) {
        super("playlist", "Manage your personal playlist 🎵");
        this.playlistService = playlistService;

        getData()
                .addSubcommands(
                        new SubcommandData("add", "Add a song to your playlist")
                                .addOptions(new OptionData(
                                        OptionType.STRING, SONG_OPTION, "A song URL or search term", true)),
                        new SubcommandData("show", "Show your playlist (or someone else's)")
                                .addOptions(new OptionData(
                                        OptionType.USER, USER_OPTION, "Whose playlist to show", false)),
                        new SubcommandData("remove", "Remove a song from your playlist by its number")
                                .addOptions(new OptionData(
                                        OptionType.INTEGER, NUMBER_OPTION, "The number shown by /playlist show", true)),
                        new SubcommandData("clear", "Remove every song from your playlist"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String subcommand = event.getSubcommandName();
        if (subcommand == null) {
            return;
        }

        switch (subcommand) {
            case "add" -> handleAdd(event);
            case "show" -> handleShow(event);
            case "remove" -> handleRemove(event);
            case "clear" -> handleClear(event);
            default -> {}
        }
    }

    private void handleAdd(SlashCommandInteractionEvent event) {
        String query = Objects.requireNonNull(event.getOption(SONG_OPTION)).getAsString();
        String identifier = isUrl(query) ? query : "ytsearch:" + query;

        String userId = event.getUser().getId();
        String userName = displayName(event);

        event.deferReply().queue();

        PlayerManager.getInstance().resolve(identifier).whenComplete((info, error) -> {
            if (error != null || info == null) {
                LOGGER.warn("Failed to resolve '{}' for /playlist add", query, error);
                event.getHook()
                        .sendMessage("⚠️ Couldn't find anything for `" + query + "`.")
                        .queue();
                return;
            }

            playlistService.addTrack(userId, userName, info);

            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(ACCENT)
                    .setTitle("➕ Added to your playlist")
                    .setDescription("**[" + info.title + "](" + info.uri + ")**\nby " + info.author);
            if (!info.isStream) {
                embed.addField("Duration", MusicFormat.duration(info.length), true);
            }
            if (info.artworkUrl != null) {
                embed.setThumbnail(info.artworkUrl);
            }
            event.getHook().sendMessageEmbeds(embed.build()).queue();
        });
    }

    private void handleShow(SlashCommandInteractionEvent event) {
        User target = event.getOption(USER_OPTION) != null
                ? event.getOption(USER_OPTION).getAsUser()
                : event.getUser();

        List<StoredTrack> tracks = playlistService.getTracks(target.getId());
        boolean isSelf = target.getId().equals(event.getUser().getId());

        if (tracks.isEmpty()) {
            event.reply(isSelf
                            ? "🎵 Your playlist is empty — add songs with `/playlist add`."
                            : "🎵 " + target.getEffectiveName() + " hasn't added any songs yet.")
                    .setEphemeral(isSelf)
                    .queue();
            return;
        }

        StringBuilder description = new StringBuilder();
        int shown = Math.min(tracks.size(), MAX_LISTED);
        for (int i = 0; i < shown; i++) {
            StoredTrack track = tracks.get(i);
            description
                    .append("**")
                    .append(i + 1)
                    .append(".** [")
                    .append(track.title())
                    .append("](")
                    .append(track.uri())
                    .append(")");
            if (track.durationMs() > 0) {
                description.append(" `").append(MusicFormat.duration(track.durationMs())).append("`");
            }
            description.append("\n");
        }
        if (tracks.size() > shown) {
            description.append("…and ").append(tracks.size() - shown).append(" more");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(ACCENT)
                .setTitle("🎵 " + target.getEffectiveName() + "'s playlist")
                .setDescription(description.toString())
                .setFooter(tracks.size() + (tracks.size() == 1 ? " song" : " songs"));

        event.replyEmbeds(embed.build()).queue();
    }

    private void handleRemove(SlashCommandInteractionEvent event) {
        int number = Objects.requireNonNull(event.getOption(NUMBER_OPTION))
                .getAsInt();

        String removed = playlistService.removeTrack(event.getUser().getId(), number);
        if (removed == null) {
            event.reply("⚠️ There's no song **#" + number + "** in your playlist.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.reply("🗑️ Removed **" + removed + "** from your playlist.").queue();
    }

    private void handleClear(SlashCommandInteractionEvent event) {
        int removed = playlistService.clear(event.getUser().getId());
        if (removed == 0) {
            event.reply("🎵 Your playlist is already empty.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.reply("🗑️ Cleared your playlist (" + removed + (removed == 1 ? " song" : " songs") + ").")
                .queue();
    }

    private static String displayName(SlashCommandInteractionEvent event) {
        return event.getMember() != null
                ? event.getMember().getEffectiveName()
                : event.getUser().getEffectiveName();
    }

    private static boolean isUrl(String query) {
        String lower = query.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
