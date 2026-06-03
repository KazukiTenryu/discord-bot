package bot.slash.playlist;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.managers.AudioManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.slash.music.MusicCommand;
import bot.slash.music.MusicFormat;
import bot.slash.music.PlayerManager;
import bot.slash.playlist.PlaylistService.Playlist;
import bot.slash.playlist.PlaylistService.StoredTrack;

/**
 * /play-playlist — joins the caller's voice channel and queues every track from a chosen user's
 * playlist (their main one unless a playlist is named), posting an embed with pause/skip/stop buttons
 * (handled by {@link PlaylistControls}).
 */
public class PlayPlaylistCommand extends MusicCommand {
    private static final Logger LOGGER = LogManager.getLogger(PlayPlaylistCommand.class);
    private static final Color ACCENT = new Color(0x1DB954);
    private static final int PREVIEW_LINES = 5;
    private static final String USER_OPTION = "user";
    private static final String PLAYLIST_OPTION = "playlist";

    private final PlaylistService playlistService;

    public PlayPlaylistCommand(PlaylistService playlistService) {
        super("play-playlist", "Play a user's playlist in your voice channel 🎶");
        this.playlistService = playlistService;
        getData()
                .addOptions(
                        new OptionData(OptionType.USER, USER_OPTION, "Whose playlist to play", true),
                        new OptionData(
                                        OptionType.STRING,
                                        PLAYLIST_OPTION,
                                        "Which playlist to play (default: their main one)",
                                        false)
                                .setAutoComplete(true));
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        if (!event.getFocusedOption().getName().equals(PLAYLIST_OPTION)) {
            return;
        }
        // The playlist choices belong to the targeted user; if it isn't set yet, return none.
        OptionMapping userOption = event.getOption(USER_OPTION);
        if (userOption == null) {
            event.replyChoices(List.of()).queue();
            return;
        }
        String input = event.getFocusedOption().getValue().toLowerCase();
        List<Command.Choice> choices = new ArrayList<>();
        for (Playlist playlist :
                playlistService.listPlaylists(userOption.getAsUser().getId())) {
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
        User target = Objects.requireNonNull(event.getOption(USER_OPTION)).getAsUser();
        OptionMapping playlistOption = event.getOption(PLAYLIST_OPTION);

        Playlist playlist;
        if (playlistOption != null && !playlistOption.getAsString().isBlank()) {
            playlist = playlistService.getPlaylistByName(target.getId(), playlistOption.getAsString());
            if (playlist == null) {
                event.reply("🎵 " + target.getEffectiveName() + " has no playlist named **"
                                + playlistOption.getAsString() + "**.")
                        .setEphemeral(true)
                        .queue();
                return;
            }
        } else {
            playlist = playlistService.listPlaylists(target.getId()).stream()
                    .filter(Playlist::isDefault)
                    .findFirst()
                    .orElse(null);
        }

        List<StoredTrack> tracks = playlist == null ? List.of() : playlistService.getTracksByPlaylist(playlist.id());
        if (tracks.isEmpty()) {
            event.reply("🎵 " + target.getEffectiveName()
                            + (playlist == null
                                    ? " hasn't added any songs yet."
                                    : "'s **" + playlist.name() + "** is empty."))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        AudioChannel channel = memberVoiceChannel(event);
        if (channel == null) {
            return;
        }

        event.deferReply().queue();

        AudioManager audioManager = event.getGuild().getAudioManager();
        if (!audioManager.isConnected()) {
            audioManager.setSendingHandler(PlayerManager.getInstance()
                    .getMusicManager(event.getGuild())
                    .getSendHandler());
            audioManager.openAudioConnection(channel);
        }

        // loadItemOrdered (inside enqueue) preserves submission order per guild, so firing these in
        // sequence keeps the playlist's order even though each load is async.
        for (StoredTrack track : tracks) {
            PlayerManager.getInstance().enqueue(event.getGuild(), track.uri()).exceptionally(error -> {
                LOGGER.warn("Couldn't queue '{}' ({})", track.title(), track.uri(), error);
                return null;
            });
        }

        StringBuilder description = new StringBuilder();
        int preview = Math.min(tracks.size(), PREVIEW_LINES);
        for (int i = 0; i < preview; i++) {
            StoredTrack track = tracks.get(i);
            description
                    .append("**")
                    .append(i + 1)
                    .append(".** ")
                    .append(track.trackName() != null ? track.trackName() : track.title());
            if (track.durationMs() > 0) {
                description
                        .append(" `")
                        .append(MusicFormat.duration(track.durationMs()))
                        .append("`");
            }
            description.append("\n");
        }
        if (tracks.size() > preview) {
            description.append("…and ").append(tracks.size() - preview).append(" more");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(ACCENT)
                .setTitle("🎶 Playing " + target.getEffectiveName() + " · " + playlist.name())
                .setDescription(description.toString())
                .setFooter(tracks.size() + (tracks.size() == 1 ? " song queued" : " songs queued"));

        event.getHook()
                .sendMessageEmbeds(embed.build())
                .addComponents(ActionRow.of(
                        Button.primary(PlaylistControls.TOGGLE_ID, "⏯️ Pause/Resume"),
                        Button.secondary(PlaylistControls.SKIP_ID, "⏭️ Skip"),
                        Button.danger(PlaylistControls.STOP_ID, "⏹️ Stop")))
                .queue();
    }
}
