package bot.slash.music;

import java.util.Objects;

import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.managers.AudioManager;

/**
 * /play — joins the caller's voice channel (if not already connected) and queues a track from a URL
 * or a search term.
 */
public class PlayCommand extends MusicCommand {
    private static final String QUERY_OPTION = "query";

    public PlayCommand() {
        super("play", "Play a song in your voice channel 🎵");
        getData().addOptions(new OptionData(OptionType.STRING, QUERY_OPTION, "A song URL or search term", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
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

        String query = Objects.requireNonNull(event.getOption(QUERY_OPTION)).getAsString();
        // A bare term becomes a YouTube search; URLs are resolved by their matching source manager.
        String identifier = isUrl(query) ? query : "ytsearch:" + query;

        PlayerManager.getInstance().loadAndPlay(event.getGuild(), identifier, event.getHook());
    }

    private boolean isUrl(String query) {
        String lower = query.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
