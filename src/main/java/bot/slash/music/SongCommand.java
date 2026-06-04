package bot.slash.music;

import java.awt.Color;
import java.util.Locale;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.FileUpload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import bot.slash.SlashCommand;
import bot.stats.StatsService;

/**
 * /song — searches for a track by name, decodes it to an {@code .ogg} file, and posts it in the text
 * channel so Discord renders its native inline audio player. No voice channel is involved; for live
 * voice playback use {@code /play} instead.
 */
public class SongCommand extends SlashCommand {
    private static final Logger LOGGER = LogManager.getLogger(SongCommand.class);
    private static final String NAME_OPTION = "name";
    private static final Color SPOTIFY_GREEN = new Color(0x1DB954);

    public SongCommand() {
        super("song", "Search for a song and post it here to play 🎵");
        getData().addOptions(new OptionData(OptionType.STRING, NAME_OPTION, "A song name, artist, or URL", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String name = Objects.requireNonNull(event.getOption(NAME_OPTION)).getAsString();
        // A bare term becomes a YouTube search; URLs are resolved by their matching source manager.
        String query = isUrl(name) ? name : "ytsearch:" + name;

        // Decoding takes a few seconds, so acknowledge first and follow up with the file.
        event.deferReply().queue();

        String userId = event.getUser().getId();
        String userName = event.getMember() != null
                ? event.getMember().getEffectiveName()
                : event.getUser().getEffectiveName();
        PlayerManager.getInstance().downloadOgg(query).whenComplete((track, error) -> {
            if (error != null) {
                event.getHook().sendMessage(errorMessage(error, name)).queue();
                return;
            }
            StatsService stats = PlayerManager.getInstance().getStatsService();
            if (stats != null) {
                stats.recordPlay(userId, userName, track.info(), "song");
            }
            event.getHook()
                    .sendFiles(FileUpload.fromData(track.ogg(), fileName(track.info())))
                    .addEmbeds(card(track.info()))
                    .queue();
        });
    }

    private MessageEmbed card(AudioTrackInfo info) {
        String url = info.uri != null && info.uri.startsWith("http") ? info.uri : null;
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(SPOTIFY_GREEN)
                .setAuthor("🎵  Song")
                .setTitle(info.title, url)
                .setDescription("by **" + info.author + "**")
                .addField("Duration", MusicFormat.duration(info.length), true);
        if (info.artworkUrl != null) {
            embed.setThumbnail(info.artworkUrl);
        }
        return embed.build();
    }

    private String errorMessage(Throwable error, String name) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        if (cause instanceof PlayerManager.NoMatchException) {
            return "🔍 Nothing found for `" + name + "`.";
        }
        if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
            return "⚠️ " + cause.getMessage();
        }
        LOGGER.error("Failed to render song for query '{}'", name, cause);
        return "⚠️ Couldn't fetch that song: " + cause.getMessage();
    }

    private String fileName(AudioTrackInfo info) {
        String base = info.title == null || info.title.isBlank() ? "song" : info.title;
        // Discord rejects some characters in attachment names; keep it conservative.
        String safe = base.replaceAll("[^a-zA-Z0-9 ._-]", "").trim();
        if (safe.isEmpty()) {
            safe = "song";
        }
        if (safe.length() > 80) {
            safe = safe.substring(0, 80).trim();
        }
        return safe + ".ogg";
    }

    private boolean isUrl(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
