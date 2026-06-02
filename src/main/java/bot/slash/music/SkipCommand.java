package bot.slash.music;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

/** /skip — stops the current track and advances to the next one in the queue. */
public class SkipCommand extends MusicCommand {
    public SkipCommand() {
        super("skip", "Skip the current song ⏭️");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        if (!requireSharedConnection(event)) {
            return;
        }

        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(event.getGuild());
        AudioTrack current = musicManager.getPlayer().getPlayingTrack();
        if (current == null) {
            event.reply("🔇 Nothing is playing.").setEphemeral(true).queue();
            return;
        }

        musicManager.getScheduler().nextTrack();
        event.reply("⏭️ Skipped **" + current.getInfo().title + "**").queue();
    }
}
