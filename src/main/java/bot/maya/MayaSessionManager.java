package bot.maya;

import java.util.concurrent.ConcurrentHashMap;

import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;

/**
 * Tracks the active {@link MayaSession} per guild (one at a time) and creates the voice-backend
 * client for each. Shared by the {@code /maya} command and the auto-join voice listener.
 */
public class MayaSessionManager {
    private final String apiKey;
    private final String agentId;
    private final String characterName;
    private final ConcurrentHashMap<Long, MayaSession> sessions = new ConcurrentHashMap<>();

    public MayaSessionManager(String apiKey, String agentId, String characterName) {
        this.apiKey = apiKey;
        this.agentId = agentId;
        this.characterName = characterName;
    }

    /** Whether a session is currently active in the guild. */
    public boolean isActive(long guildId) {
        return sessions.containsKey(guildId);
    }

    /**
     * Starts a session in the given channel unless one is already running for that guild.
     *
     * @return {@code true} if a new session connected and started
     */
    public boolean start(AudioChannel channel) {
        long guildId = channel.getGuild().getIdLong();
        // computeIfAbsent keeps two concurrent starts (command + auto-join) from racing.
        boolean[] started = {false};
        sessions.computeIfAbsent(guildId, id -> {
            MayaSession session = new MayaSession(new ElevenLabsVoiceClient(apiKey, agentId), channel);
            if (session.start(channel)) {
                started[0] = true;
                return session;
            }
            return null; // connect failed; leave nothing mapped
        });
        return started[0];
    }

    /** Stops and removes the guild's session, if any. */
    public void stop(long guildId) {
        MayaSession session = sessions.remove(guildId);
        if (session != null) {
            session.stop();
        }
    }

    /** Stops every active session (used on shutdown). */
    public void stopAll() {
        sessions.keySet().forEach(this::stop);
    }

    public String character() {
        return characterName;
    }
}
