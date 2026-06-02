package bot.slash.music;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.managers.AudioManager;

/** /stop — clears the queue, stops playback, and leaves the voice channel. */
public class StopCommand extends MusicCommand {
    public StopCommand() {
        super("stop", "Stop playback, clear the queue, and leave ⏹️");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        if (!requireSharedConnection(event)) {
            return;
        }

        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(event.getGuild());
        musicManager.getScheduler().clearQueue();
        musicManager.getPlayer().stopTrack();

        AudioManager audioManager = event.getGuild().getAudioManager();
        audioManager.closeAudioConnection();

        event.reply("⏹️ Stopped and left the channel.").queue();
    }
}
