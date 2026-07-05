package bot.maya;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;

/**
 * When enabled, makes the bot follow humans into voice: it starts a Maya session as soon as someone
 * joins a voice channel (if the bot isn't already busy) and leaves once the last human is gone.
 * Disabled by default — see {@code sesameAutoJoin} in the config.
 */
public class MayaVoiceListener extends ListenerAdapter {
    private static final Logger LOGGER = LogManager.getLogger(MayaVoiceListener.class);

    private final MayaSessionManager sessions;

    public MayaVoiceListener(MayaSessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public void onGuildVoiceUpdate(@NonNull GuildVoiceUpdateEvent event) {
        Member member = event.getMember();
        if (member.getUser().isBot()) {
            return; // ignore the bot's own join/leave (including our own connect)
        }
        Guild guild = event.getGuild();

        AudioChannel joined = event.getChannelJoined();
        if (joined != null) {
            maybeStart(guild, joined);
        }

        AudioChannel left = event.getChannelLeft();
        if (left != null) {
            maybeStop(guild, left);
        }
    }

    private void maybeStart(Guild guild, AudioChannel channel) {
        if (sessions.isActive(guild.getIdLong()) || guild.getAudioManager().isConnected()) {
            return;
        }
        Thread starter = new Thread(
                () -> {
                    if (sessions.start(channel)) {
                        LOGGER.info("Auto-joined voice channel {} in guild {}", channel.getName(), guild.getId());
                    } else {
                        LOGGER.warn("Auto-join failed to connect to Sesame in guild {}", guild.getId());
                    }
                },
                "maya-autojoin-" + guild.getId());
        starter.setDaemon(true);
        starter.start();
    }

    private void maybeStop(Guild guild, AudioChannel left) {
        if (!sessions.isActive(guild.getIdLong())) {
            return;
        }
        AudioManager audioManager = guild.getAudioManager();
        AudioChannel connected = audioManager.getConnectedChannel();
        if (connected == null || !connected.equals(left)) {
            return;
        }
        long humans = connected.getMembers().stream()
                .filter(m -> !m.getUser().isBot())
                .count();
        if (humans == 0) {
            LOGGER.info("Last human left {}; ending Maya session", left.getName());
            sessions.stop(guild.getIdLong());
        }
    }
}
