package bot.slash.music;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;

/** /resume — resumes a paused track. */
public class ResumeCommand extends MusicCommand {
    public ResumeCommand() {
        super("resume", "Resume the paused song ▶️");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        if (!requireSharedConnection(event)) {
            return;
        }

        AudioPlayer player =
                PlayerManager.getInstance().getMusicManager(event.getGuild()).getPlayer();
        if (!player.isPaused()) {
            event.reply("▶️ Playback isn't paused.").setEphemeral(true).queue();
            return;
        }

        player.setPaused(false);
        event.reply("▶️ Resumed.").queue();
    }
}
