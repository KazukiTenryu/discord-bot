package bot.slash.music;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import bot.slash.SlashCommand;
import bot.utils.LyricsService;

/**
 * /lyrics — looks up song lyrics from lrclib.net. With a {@code song} argument it searches for that;
 * with none, it falls back to whatever {@code /play} is currently playing in the guild.
 */
public class LyricsCommand extends SlashCommand {
    private static final String SONG_OPTION = "song";
    private static final Color SPOTIFY_GREEN = new Color(0x1DB954);

    // Discord caps an embed description at 4096 chars; leave headroom and split on line boundaries.
    private static final int MAX_CHUNK_CHARS = 3800;
    private static final int MAX_CHUNKS = 6;

    private final LyricsService lyricsService = new LyricsService();

    public LyricsCommand() {
        super("lyrics", "Show the lyrics for a song 🎤");
        getData()
                .addOptions(new OptionData(
                        OptionType.STRING, SONG_OPTION, "Song name (defaults to what's currently playing)", false));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String query = resolveQuery(event);
        if (query == null) {
            event.reply("🎤 Give me a song name, or start something with `/play` first.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.deferReply().queue();

        lyricsService
                .fetch(query)
                .ifPresentOrElse(lyrics -> sendInOrder(event.getHook(), buildEmbeds(lyrics), 0), () -> event.getHook()
                        .sendMessage("🔍 Couldn't find lyrics for `" + query + "`.")
                        .queue());
    }

    /** The explicit option, else the currently playing track's "title artist", else null. */
    private String resolveQuery(SlashCommandInteractionEvent event) {
        OptionMapping option = event.getOption(SONG_OPTION);
        if (option != null) {
            return option.getAsString();
        }
        if (event.getGuild() == null) {
            return null;
        }
        AudioTrack playing = PlayerManager.getInstance()
                .getMusicManager(event.getGuild())
                .getPlayer()
                .getPlayingTrack();
        if (playing == null) {
            return null;
        }
        // Drop "(Official Video)"-style noise that hurts the lyrics match.
        String title = playing.getInfo().title.replaceAll("[(\\[].*?[)\\]]", "").trim();
        String author = playing.getInfo().author;
        return (title + " " + author).trim();
    }

    private List<MessageEmbed> buildEmbeds(LyricsService.Lyrics lyrics) {
        String song = lyrics.trackName() + " — " + lyrics.artistName();
        List<String> chunks = chunk(lyrics.plainLyrics());
        List<MessageEmbed> embeds = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(SPOTIFY_GREEN)
                    // Title on the first part, a lighter author header on continuations — either way
                    // every embed shows which song the lyrics belong to.
                    .setDescription(chunks.get(i));
            if (i == 0) {
                embed.setTitle("🎤 " + song);
                embed.setFooter("via lrclib.net");
            } else {
                embed.setAuthor("🎤 " + song + " (cont.)");
            }
            embeds.add(embed.build());
        }
        return embeds;
    }

    /** Splits lyrics into description-sized pieces on line boundaries, capped at {@link #MAX_CHUNKS}. */
    private List<String> chunk(String lyrics) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean truncated = false;
        for (String line : lyrics.split("\n", -1)) {
            if (current.length() + line.length() + 1 > MAX_CHUNK_CHARS) {
                chunks.add(current.toString());
                current.setLength(0);
                if (chunks.size() >= MAX_CHUNKS) {
                    truncated = true;
                    break;
                }
            }
            current.append(line).append('\n');
        }
        if (!truncated && chunks.size() < MAX_CHUNKS && !current.isEmpty()) {
            chunks.add(current.toString());
        }
        if (chunks.isEmpty()) {
            chunks.add(lyrics);
        }
        if (truncated) {
            // Append a marker rather than silently dropping the rest.
            int last = chunks.size() - 1;
            chunks.set(last, chunks.get(last) + "\n… (lyrics truncated)");
        }
        return chunks;
    }

    /** Sends each embed as its own follow-up, chained so they always arrive in order. */
    private void sendInOrder(InteractionHook hook, List<MessageEmbed> embeds, int index) {
        if (index >= embeds.size()) {
            return;
        }
        hook.sendMessageEmbeds(embeds.get(index)).queue(message -> sendInOrder(hook, embeds, index + 1));
    }
}
