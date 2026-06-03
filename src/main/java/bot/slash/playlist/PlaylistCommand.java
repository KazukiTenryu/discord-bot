package bot.slash.playlist;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.slash.SlashCommand;
import bot.slash.music.MusicFormat;
import bot.slash.music.PlayerManager;
import bot.slash.playlist.PlaylistService.Playlist;
import bot.slash.playlist.PlaylistService.StoredTrack;

/**
 * /playlist — manage your playlists. A user may own several named playlists (one is their default);
 * {@code add} resolves a song's metadata via LavaPlayer and stores it in a chosen playlist (the
 * default when none is named), the rest read/edit the stored rows.
 */
public class PlaylistCommand extends SlashCommand {
    private static final Logger LOGGER = LogManager.getLogger(PlaylistCommand.class);
    private static final Color ACCENT = new Color(0x1DB954);
    // Discord embed descriptions cap at 4096 chars; keep the listing well under that.
    private static final int MAX_LISTED = 25;
    private static final int MAX_NAME_LENGTH = 80;

    private static final String SONG_OPTION = "song";
    private static final String USER_OPTION = "user";
    private static final String NUMBER_OPTION = "number";
    private static final String PLAYLIST_OPTION = "playlist";
    private static final String NAME_OPTION = "name";
    private static final String NEW_NAME_OPTION = "new_name";
    private static final String FROM_OPTION = "from";
    private static final String TO_OPTION = "to";

    private final PlaylistService playlistService;
    // Public base URL of the web player (no trailing slash), or null when not configured.
    private final String webBaseUrl;

    public PlaylistCommand(PlaylistService playlistService, String webBaseUrl) {
        super("playlist", "Manage your playlists 🎵");
        this.playlistService = playlistService;
        this.webBaseUrl = webBaseUrl;

        getData()
                .addSubcommands(
                        new SubcommandData("add", "Add a song to one of your playlists")
                                .addOptions(
                                        new OptionData(
                                                OptionType.STRING, SONG_OPTION, "A song URL or search term", true),
                                        playlistOption("Which playlist to add to (default: your main one)")),
                        new SubcommandData("show", "Show a playlist (yours or someone else's)")
                                .addOptions(
                                        new OptionData(OptionType.USER, USER_OPTION, "Whose playlist to show", false),
                                        playlistOption("Which playlist to show (default: their main one)")),
                        new SubcommandData("list", "List your playlists (or someone else's)")
                                .addOptions(
                                        new OptionData(OptionType.USER, USER_OPTION, "Whose playlists to list", false)),
                        new SubcommandData("create", "Create a new playlist")
                                .addOptions(new OptionData(
                                        OptionType.STRING, NAME_OPTION, "A name for the new playlist", true)),
                        new SubcommandData("rename", "Rename one of your playlists")
                                .addOptions(
                                        playlistOption("The playlist to rename").setRequired(true),
                                        new OptionData(OptionType.STRING, NEW_NAME_OPTION, "The new name", true)),
                        new SubcommandData("delete", "Delete one of your playlists (and its songs)")
                                .addOptions(
                                        playlistOption("The playlist to delete").setRequired(true)),
                        new SubcommandData("remove", "Remove a song from a playlist by its number")
                                .addOptions(
                                        new OptionData(
                                                OptionType.INTEGER,
                                                NUMBER_OPTION,
                                                "The number shown by /playlist show",
                                                true),
                                        playlistOption("Which playlist to remove from (default: your main one)")),
                        new SubcommandData("move", "Move a song from one of your playlists to another")
                                .addOptions(
                                        new OptionData(
                                                OptionType.INTEGER,
                                                NUMBER_OPTION,
                                                "The song's number in the source",
                                                true),
                                        renamedPlaylistOption(FROM_OPTION, "The playlist to move it from")
                                                .setRequired(true),
                                        renamedPlaylistOption(TO_OPTION, "The playlist to move it to")
                                                .setRequired(true)),
                        new SubcommandData("clear", "Remove every song from a playlist")
                                .addOptions(playlistOption("Which playlist to clear (default: your main one)")),
                        new SubcommandData("link", "Get a private link to manage your playlists online"));
    }

    private static OptionData playlistOption(String description) {
        return new OptionData(OptionType.STRING, PLAYLIST_OPTION, description, false).setAutoComplete(true);
    }

    private static OptionData renamedPlaylistOption(String name, String description) {
        return new OptionData(OptionType.STRING, name, description, false).setAutoComplete(true);
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        String focused = event.getFocusedOption().getName();
        if (!focused.equals(PLAYLIST_OPTION) && !focused.equals(FROM_OPTION) && !focused.equals(TO_OPTION)) {
            return;
        }
        // Suggest the targeted user's playlists when a (resolved) user option is present, else the caller's.
        String userId = event.getUser().getId();
        OptionMapping userOption = event.getOption(USER_OPTION);
        if (userOption != null) {
            userId = userOption.getAsUser().getId();
        }
        String input = event.getFocusedOption().getValue().toLowerCase();

        List<Command.Choice> choices = new ArrayList<>();
        for (Playlist playlist : playlistService.listPlaylists(userId)) {
            if (playlist.name().toLowerCase().contains(input)) {
                choices.add(new Command.Choice(playlist.name() + " (" + playlist.trackCount() + ")", playlist.name()));
            }
            if (choices.size() >= 25) {
                break;
            }
        }
        event.replyChoices(choices).queue();
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
            case "list" -> handleList(event);
            case "create" -> handleCreate(event);
            case "rename" -> handleRename(event);
            case "delete" -> handleDelete(event);
            case "remove" -> handleRemove(event);
            case "move" -> handleMove(event);
            case "clear" -> handleClear(event);
            case "link" -> handleLink(event);
            default -> {}
        }
    }

    private void handleAdd(SlashCommandInteractionEvent event) {
        String query = Objects.requireNonNull(event.getOption(SONG_OPTION)).getAsString();
        String identifier = isUrl(query) ? query : "ytsearch:" + query;

        String userId = event.getUser().getId();
        String userName = displayName(event);
        String playlistName = optionString(event, PLAYLIST_OPTION);

        // Resolve the target playlist up front (cheap DB call) so an unknown name fails fast.
        Playlist target;
        if (playlistName == null) {
            target = playlistService.ensureDefaultPlaylist(userId, userName);
        } else {
            target = playlistService.getPlaylistByName(userId, playlistName);
            if (target == null) {
                event.reply("⚠️ You don't have a playlist named **" + playlistName
                                + "** — create it with `/playlist create`.")
                        .setEphemeral(true)
                        .queue();
                return;
            }
        }

        event.deferReply().queue();

        PlayerManager.getInstance().resolve(identifier).whenComplete((info, error) -> {
            if (error != null || info == null) {
                LOGGER.warn("Failed to resolve '{}' for /playlist add", query, error);
                event.getHook()
                        .sendMessage("⚠️ Couldn't find anything for `" + query + "`.")
                        .queue();
                return;
            }

            StoredTrack stored = playlistService.addTrack(userId, userName, target.id(), info);
            String by = stored.artist() != null ? stored.artist() : info.author;
            String name = stored.trackName() != null ? stored.trackName() : info.title;

            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(ACCENT)
                    .setTitle("➕ Added to " + target.name())
                    .setDescription("**[" + name + "](" + info.uri + ")**\nby " + by);
            if (!info.isStream) {
                embed.addField("Duration", MusicFormat.duration(info.length), true);
            }
            if (stored.album() != null) {
                embed.addField("Album", stored.album(), true);
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
        boolean isSelf = target.getId().equals(event.getUser().getId());
        String playlistName = optionString(event, PLAYLIST_OPTION);

        Playlist playlist;
        if (playlistName != null) {
            playlist = playlistService.getPlaylistByName(target.getId(), playlistName);
            if (playlist == null) {
                event.reply((isSelf ? "You don't" : target.getEffectiveName() + " doesn't")
                                + " have a playlist named **" + playlistName + "**.")
                        .setEphemeral(isSelf)
                        .queue();
                return;
            }
        } else if (isSelf) {
            playlist = playlistService.ensureDefaultPlaylist(target.getId(), displayName(event));
        } else {
            playlist = playlistService.listPlaylists(target.getId()).stream()
                    .filter(Playlist::isDefault)
                    .findFirst()
                    .orElse(null);
        }

        List<StoredTrack> tracks = playlist == null ? List.of() : playlistService.getTracksByPlaylist(playlist.id());
        if (tracks.isEmpty()) {
            String label = playlist == null ? "playlist" : "**" + playlist.name() + "**";
            event.reply(
                            isSelf
                                    ? "🎵 Your " + label + " is empty — add songs with `/playlist add`."
                                    : "🎵 " + target.getEffectiveName() + "'s " + label + " is empty.")
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
                    .append(track.trackName() != null ? track.trackName() : track.title())
                    .append("](")
                    .append(track.uri())
                    .append(")");
            if (track.durationMs() > 0) {
                description
                        .append(" `")
                        .append(MusicFormat.duration(track.durationMs()))
                        .append("`");
            }
            description.append("\n");
        }
        if (tracks.size() > shown) {
            description.append("…and ").append(tracks.size() - shown).append(" more");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(ACCENT)
                .setTitle("🎵 " + target.getEffectiveName() + " · " + playlist.name())
                .setDescription(description.toString())
                .setFooter(tracks.size() + (tracks.size() == 1 ? " song" : " songs"));

        if (webBaseUrl != null) {
            String url = webBaseUrl + "/?user=" + target.getId() + "&playlist=" + playlist.id();
            String host = webBaseUrl.replaceFirst("^https?://", "");
            embed.addField("🔊 Listen online", "[" + host + "](" + url + ")", false);
        }

        event.replyEmbeds(embed.build()).queue();
    }

    private void handleList(SlashCommandInteractionEvent event) {
        User target = event.getOption(USER_OPTION) != null
                ? event.getOption(USER_OPTION).getAsUser()
                : event.getUser();
        boolean isSelf = target.getId().equals(event.getUser().getId());

        // Make sure the caller always has at least their default playlist to see.
        if (isSelf) {
            playlistService.ensureDefaultPlaylist(target.getId(), displayName(event));
        }
        List<Playlist> playlists = playlistService.listPlaylists(target.getId());
        if (playlists.isEmpty()) {
            event.reply("🎵 " + (isSelf ? "You have" : target.getEffectiveName() + " has") + " no playlists yet.")
                    .setEphemeral(isSelf)
                    .queue();
            return;
        }

        StringBuilder description = new StringBuilder();
        for (Playlist playlist : playlists) {
            description
                    .append(playlist.isDefault() ? "⭐ **" : "• **")
                    .append(playlist.name())
                    .append("** — ")
                    .append(playlist.trackCount())
                    .append(playlist.trackCount() == 1 ? " song\n" : " songs\n");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(ACCENT)
                .setTitle("🎵 " + target.getEffectiveName() + "'s playlists")
                .setDescription(description.toString())
                .setFooter(playlists.size() + (playlists.size() == 1 ? " playlist" : " playlists"));
        event.replyEmbeds(embed.build()).queue();
    }

    private void handleCreate(SlashCommandInteractionEvent event) {
        String name = Objects.requireNonNull(event.getOption(NAME_OPTION))
                .getAsString()
                .trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            event.reply("⚠️ Pick a name between 1 and " + MAX_NAME_LENGTH + " characters.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String userId = event.getUser().getId();
        String userName = displayName(event);
        // Guarantee the default exists so the user's playlist set is always well-formed.
        playlistService.ensureDefaultPlaylist(userId, userName);

        Playlist created = playlistService.createPlaylist(userId, userName, name);
        if (created == null) {
            event.reply("⚠️ You already have a playlist named **" + name + "**.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        event.reply("✅ Created playlist **" + created.name() + "**. Add songs with `/playlist add song:… playlist:"
                        + created.name() + "`.")
                .queue();
    }

    private void handleRename(SlashCommandInteractionEvent event) {
        String name = Objects.requireNonNull(event.getOption(PLAYLIST_OPTION)).getAsString();
        String newName = Objects.requireNonNull(event.getOption(NEW_NAME_OPTION))
                .getAsString()
                .trim();
        if (newName.isEmpty() || newName.length() > MAX_NAME_LENGTH) {
            event.reply("⚠️ Pick a name between 1 and " + MAX_NAME_LENGTH + " characters.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String userId = event.getUser().getId();
        Playlist playlist = playlistService.getPlaylistByName(userId, name);
        if (playlist == null) {
            event.reply("⚠️ You don't have a playlist named **" + name + "**.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        boolean renamed = playlistService.renamePlaylist(userId, playlist.id(), newName);
        if (!renamed) {
            event.reply("⚠️ You already have a playlist named **" + newName + "**.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        event.reply("✏️ Renamed **" + name + "** to **" + newName + "**.").queue();
    }

    private void handleDelete(SlashCommandInteractionEvent event) {
        String name = Objects.requireNonNull(event.getOption(PLAYLIST_OPTION)).getAsString();
        String userId = event.getUser().getId();
        Playlist playlist = playlistService.getPlaylistByName(userId, name);
        if (playlist == null) {
            event.reply("⚠️ You don't have a playlist named **" + name + "**.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if (playlist.isDefault()) {
            event.reply("⚠️ You can't delete your main playlist. Use `/playlist clear` to empty it instead.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        playlistService.deletePlaylist(userId, playlist.id());
        event.reply("🗑️ Deleted **" + playlist.name() + "** (" + playlist.trackCount()
                        + (playlist.trackCount() == 1 ? " song)." : " songs)."))
                .queue();
    }

    private void handleRemove(SlashCommandInteractionEvent event) {
        int number = Objects.requireNonNull(event.getOption(NUMBER_OPTION)).getAsInt();
        Playlist playlist = resolveOwnPlaylist(event, optionString(event, PLAYLIST_OPTION));
        if (playlist == null) {
            return; // resolveOwnPlaylist already replied with the error
        }

        String removed = playlistService.removeTrackByPosition(playlist.id(), number);
        if (removed == null) {
            event.reply("⚠️ There's no song **#" + number + "** in **" + playlist.name() + "**.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        event.reply("🗑️ Removed **" + removed + "** from **" + playlist.name() + "**.")
                .queue();
    }

    private void handleMove(SlashCommandInteractionEvent event) {
        int number = Objects.requireNonNull(event.getOption(NUMBER_OPTION)).getAsInt();
        String userId = event.getUser().getId();
        String fromName = Objects.requireNonNull(event.getOption(FROM_OPTION)).getAsString();
        String toName = Objects.requireNonNull(event.getOption(TO_OPTION)).getAsString();

        Playlist from = playlistService.getPlaylistByName(userId, fromName);
        Playlist to = playlistService.getPlaylistByName(userId, toName);
        if (from == null || to == null) {
            event.reply("⚠️ You don't have a playlist named **" + (from == null ? fromName : toName) + "**.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if (from.id() == to.id()) {
            event.reply("⚠️ The source and destination are the same playlist.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        List<StoredTrack> tracks = playlistService.getTracksByPlaylist(from.id());
        if (number < 1 || number > tracks.size()) {
            event.reply("⚠️ There's no song **#" + number + "** in **" + from.name() + "**.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        StoredTrack track = tracks.get(number - 1);
        boolean moved = playlistService.moveTrack(userId, track.id(), to.id());
        if (!moved) {
            event.reply("⚠️ Couldn't move that song.").setEphemeral(true).queue();
            return;
        }
        String name = track.trackName() != null ? track.trackName() : track.title();
        event.reply("➡️ Moved **" + name + "** from **" + from.name() + "** to **" + to.name() + "**.")
                .queue();
    }

    private void handleClear(SlashCommandInteractionEvent event) {
        Playlist playlist = resolveOwnPlaylist(event, optionString(event, PLAYLIST_OPTION));
        if (playlist == null) {
            return; // resolveOwnPlaylist already replied with the error
        }
        int removed = playlistService.clearPlaylist(playlist.id());
        if (removed == 0) {
            event.reply("🎵 **" + playlist.name() + "** is already empty.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        event.reply("🗑️ Cleared **" + playlist.name() + "** (" + removed + (removed == 1 ? " song)." : " songs)."))
                .queue();
    }

    private void handleLink(SlashCommandInteractionEvent event) {
        if (webBaseUrl == null) {
            event.reply("⚠️ The web player isn't configured on this bot.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        // Ensure the user has a default playlist so the web player has something to show on first open.
        playlistService.ensureDefaultPlaylist(event.getUser().getId(), displayName(event));

        String token = playlistService.issueToken(event.getUser().getId(), displayName(event));
        String url = webBaseUrl + "/?token=" + token;

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(ACCENT)
                .setTitle("🔗 Your private playlist link")
                .setDescription("Open this to manage your playlists online (add/remove songs, create playlists, "
                        + "import from Spotify):\n" + url)
                .setFooter("Keep this link private — anyone with it can edit your playlists. "
                        + "Running /playlist link again replaces it.");

        // Defer ephemerally; the DM is the real payload, so the in-channel reply just confirms it.
        event.deferReply(true).queue();
        event.getUser()
                .openPrivateChannel()
                .flatMap(channel -> channel.sendMessageEmbeds(embed.build()))
                .queue(
                        sent -> event.getHook()
                                .sendMessage("📬 I've DMed you a private link to manage your playlists online.")
                                .queue(),
                        // DMs closed: fall back to the (private) ephemeral reply.
                        error -> event.getHook()
                                .sendMessage("Here's your private playlist link — keep it secret:\n" + url)
                                .queue());
    }

    /**
     * Resolves one of the caller's own playlists by name, or their default when {@code name} is null.
     * Replies with an ephemeral error and returns {@code null} when the named playlist doesn't exist.
     */
    private Playlist resolveOwnPlaylist(SlashCommandInteractionEvent event, String name) {
        String userId = event.getUser().getId();
        if (name == null) {
            return playlistService.ensureDefaultPlaylist(userId, displayName(event));
        }
        Playlist playlist = playlistService.getPlaylistByName(userId, name);
        if (playlist == null) {
            event.reply("⚠️ You don't have a playlist named **" + name + "**.")
                    .setEphemeral(true)
                    .queue();
        }
        return playlist;
    }

    private static String optionString(SlashCommandInteractionEvent event, String option) {
        OptionMapping mapping = event.getOption(option);
        if (mapping == null) {
            return null;
        }
        String value = mapping.getAsString().trim();
        return value.isEmpty() ? null : value;
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
