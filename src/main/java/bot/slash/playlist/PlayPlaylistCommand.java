package bot.slash.playlist;

import java.awt.Color;
import java.util.List;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.managers.AudioManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.slash.music.MusicCommand;
import bot.slash.music.MusicFormat;
import bot.slash.music.PlayerManager;
import bot.slash.playlist.PlaylistService.StoredTrack;

/**
 * /play-playlist — joins the caller's voice channel and queues every track from a chosen user's
 * playlist, posting an embed with pause/skip/stop buttons (handled by {@link PlaylistControls}).
 */
public class PlayPlaylistCommand extends MusicCommand {
    private static final Logger LOGGER = LogManager.getLogger(PlayPlaylistCommand.class);
    private static final Color ACCENT = new Color(0x1DB954);
    private static final int PREVIEW_LINES = 5;
    private static final String USER_OPTION = "user";

    private final PlaylistService playlistService;

    public PlayPlaylistCommand(PlaylistService playlistService) {
        super("play-playlist", "Play a user's playlist in your voice channel 🎶");
        this.playlistService = playlistService;
        getData().addOptions(new OptionData(OptionType.USER, USER_OPTION, "Whose playlist to play", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        User target = Objects.requireNonNull(event.getOption(USER_OPTION)).getAsUser();
        List<StoredTrack> tracks = playlistService.getTracks(target.getId());

        if (tracks.isEmpty()) {
            event.reply("🎵 " + target.getEffectiveName() + " hasn't added any songs yet.")
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
            description.append("**").append(i + 1).append(".** ").append(track.title());
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
                .setTitle("🎶 Playing " + target.getEffectiveName() + "'s playlist")
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
