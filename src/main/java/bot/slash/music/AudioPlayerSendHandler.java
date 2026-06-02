package bot.slash.music;

import java.nio.Buffer;
import java.nio.ByteBuffer;

import net.dv8tion.jda.api.audio.AudioSendHandler;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;

/**
 * Bridges a LavaPlayer {@link AudioPlayer} to JDA's {@link AudioSendHandler}. The player is
 * configured to output Discord-flavoured Opus, so frames are passed straight through without JDA
 * re-encoding them ({@link #isOpus()} returns {@code true}).
 */
public class AudioPlayerSendHandler implements AudioSendHandler {
    private final AudioPlayer audioPlayer;
    private final ByteBuffer buffer;
    private final MutableAudioFrame frame;

    public AudioPlayerSendHandler(AudioPlayer audioPlayer) {
        this.audioPlayer = audioPlayer;
        this.buffer = ByteBuffer.allocate(1024);
        this.frame = new MutableAudioFrame();
        this.frame.setBuffer(buffer);
    }

    @Override
    public boolean canProvide() {
        // provide() fills the frame's buffer and returns true when audio is available.
        return audioPlayer.provide(frame);
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        // Cast to Buffer to stay compatible across JDK versions (covariant flip() overrides).
        ((Buffer) buffer).flip();
        return buffer;
    }

    @Override
    public boolean isOpus() {
        return true;
    }
}
