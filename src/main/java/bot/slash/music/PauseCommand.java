package bot.slash.music;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;

/** /pause — pauses the current track. */
public class PauseCommand extends MusicCommand {
    public PauseCommand() {
        super("pause", "Pause the current song ⏸️");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        if (!requireSharedConnection(event)) {
            return;
        }

        AudioPlayer player =
                PlayerManager.getInstance().getMusicManager(event.getGuild()).getPlayer();
        if (player.getPlayingTrack() == null) {
            event.reply("🔇 Nothing is playing.").setEphemeral(true).queue();
            return;
        }
        if (player.isPaused()) {
            event.reply("⏸️ Already paused — use `/resume` to continue.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        player.setPaused(true);
        event.reply("⏸️ Paused.").queue();
    }
}
