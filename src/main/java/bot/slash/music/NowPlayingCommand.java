package bot.slash.music;

import java.awt.Color;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

/** /nowplaying — shows the track that is currently playing. */
public class NowPlayingCommand extends MusicCommand {
    public NowPlayingCommand() {
        super("nowplaying", "Show the song that's currently playing 🎶");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        AudioTrack track = PlayerManager.getInstance()
                .getMusicManager(event.getGuild())
                .getPlayer()
                .getPlayingTrack();
        if (track == null) {
            event.reply("🔇 Nothing is playing right now.").setEphemeral(true).queue();
            return;
        }

        AudioTrackInfo info = track.getInfo();
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(0x1DB954))
                .setTitle("🎶 Now Playing")
                .setDescription("**[" + info.title + "](" + info.uri + ")**\nby " + info.author);

        if (info.isStream) {
            embed.addField("Position", "🔴 LIVE", true);
        } else {
            embed.addField(
                    "Position",
                    MusicFormat.duration(track.getPosition()) + " / " + MusicFormat.duration(info.length),
                    true);
        }

        event.replyEmbeds(embed.build()).queue();
    }
}
