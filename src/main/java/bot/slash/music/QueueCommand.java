package bot.slash.music;

import java.awt.Color;
import java.util.List;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

/** /queue — lists the currently playing track and what's queued up next. */
public class QueueCommand extends MusicCommand {
    private static final int MAX_LISTED = 10;

    public QueueCommand() {
        super("queue", "Show the current music queue 📜");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(event.getGuild());
        AudioTrack current = musicManager.getPlayer().getPlayingTrack();
        List<AudioTrack> upcoming = musicManager.getScheduler().getQueue();

        if (current == null && upcoming.isEmpty()) {
            event.reply("🔇 The queue is empty.").setEphemeral(true).queue();
            return;
        }

        EmbedBuilder embed = new EmbedBuilder().setColor(new Color(0x1DB954)).setTitle("📜 Music Queue");

        if (current != null) {
            AudioTrackInfo info = current.getInfo();
            embed.addField("Now Playing", "🎶 **[" + info.title + "](" + info.uri + ")**", false);
        }

        if (!upcoming.isEmpty()) {
            StringBuilder list = new StringBuilder();
            int shown = Math.min(upcoming.size(), MAX_LISTED);
            for (int i = 0; i < shown; i++) {
                AudioTrackInfo info = upcoming.get(i).getInfo();
                list.append("`")
                        .append(i + 1)
                        .append(".` ")
                        .append(info.title)
                        .append(" — `")
                        .append(MusicFormat.duration(info.length))
                        .append("`\n");
            }
            if (upcoming.size() > shown) {
                list.append("…and ").append(upcoming.size() - shown).append(" more");
            }
            embed.addField("Up Next (" + upcoming.size() + ")", list.toString(), false);
        }

        event.replyEmbeds(embed.build()).queue();
    }
}
