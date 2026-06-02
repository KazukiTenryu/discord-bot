package bot.slash.music;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.managers.AudioManager;

import bot.slash.SlashCommand;

/** Base for the voice/music commands; centralises the "is the user in the right channel" checks. */
public abstract class MusicCommand extends SlashCommand {
    protected MusicCommand(String name, String description) {
        super(name, description);
    }

    /** The voice channel the invoking member is in, or {@code null} after replying with an error. */
    protected AudioChannel memberVoiceChannel(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        GuildVoiceState state = member == null ? null : member.getVoiceState();
        AudioChannel channel = state == null ? null : state.getChannel();
        if (channel == null) {
            event.reply("🔇 You need to be in a voice channel first.")
                    .setEphemeral(true)
                    .queue();
        }
        return channel;
    }

    /**
     * Verifies the bot is connected and the invoking member shares its voice channel. Replies with
     * the appropriate error and returns {@code false} when the command should not proceed.
     */
    protected boolean requireSharedConnection(SlashCommandInteractionEvent event) {
        AudioManager audioManager = event.getGuild().getAudioManager();
        if (!audioManager.isConnected()) {
            event.reply("🔇 I'm not playing anything right now.")
                    .setEphemeral(true)
                    .queue();
            return false;
        }

        AudioChannel channel = memberVoiceChannel(event);
        if (channel == null) {
            return false;
        }
        if (!channel.equals(audioManager.getConnectedChannel())) {
            event.reply("🔇 You need to be in my voice channel to do that.")
                    .setEphemeral(true)
                    .queue();
            return false;
        }
        return true;
    }
}
