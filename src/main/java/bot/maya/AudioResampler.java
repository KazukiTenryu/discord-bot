package bot.maya;

/**
 * Converts PCM audio between Discord's format and Sesame's.
 *
 * <ul>
 *   <li><b>Discord</b> (via JDA): 48 kHz, 16-bit, stereo, signed, <b>big-endian</b> — both for the
 *       audio it hands us ({@code AudioReceiveHandler.OUTPUT_FORMAT}) and the audio it expects from
 *       us ({@code AudioSendHandler.INPUT_FORMAT}).
 *   <li><b>Sesame</b>: 16-bit, mono, signed, <b>little-endian</b> — 16 kHz for what we send it, and
 *       whatever rate it advertises (typically 24 kHz) for what it sends back.
 * </ul>
 *
 * <p>Resampling is simple linear interpolation. That is adequate for speech; if quality ever
 * matters more, a filtered/polyphase resampler would reduce aliasing on the down-conversion.
 */
public final class AudioResampler {
    private AudioResampler() {}

    /**
     * Discord → Sesame: 48 kHz stereo big-endian PCM to {@code targetRate} mono little-endian PCM.
     */
    public static byte[] discordToSesame(byte[] discordPcm, int targetRate) {
        short[] stereo = bytesToShorts(discordPcm, true);
        short[] mono = stereoToMono(stereo);
        short[] resampled = resampleLinear(mono, 48000, targetRate);
        return shortsToBytes(resampled, false);
    }

    /**
     * Sesame → Discord: {@code sourceRate} mono little-endian PCM to 48 kHz stereo big-endian PCM.
     */
    public static byte[] sesameToDiscord(byte[] sesamePcm, int sourceRate) {
        short[] mono = bytesToShorts(sesamePcm, false);
        short[] resampled = resampleLinear(mono, sourceRate, 48000);
        short[] stereo = monoToStereo(resampled);
        return shortsToBytes(stereo, true);
    }

    static short[] bytesToShorts(byte[] data, boolean bigEndian) {
        int n = data.length / 2;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            int lo;
            int hi;
            if (bigEndian) {
                hi = data[2 * i];
                lo = data[2 * i + 1] & 0xFF;
            } else {
                lo = data[2 * i] & 0xFF;
                hi = data[2 * i + 1];
            }
            out[i] = (short) ((hi << 8) | lo);
        }
        return out;
    }

    static byte[] shortsToBytes(short[] samples, boolean bigEndian) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short s = samples[i];
            if (bigEndian) {
                out[2 * i] = (byte) (s >> 8);
                out[2 * i + 1] = (byte) s;
            } else {
                out[2 * i] = (byte) s;
                out[2 * i + 1] = (byte) (s >> 8);
            }
        }
        return out;
    }

    /** Averages interleaved L/R sample pairs into a single mono channel. */
    static short[] stereoToMono(short[] interleaved) {
        int n = interleaved.length / 2;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            out[i] = (short) ((interleaved[2 * i] + interleaved[2 * i + 1]) / 2);
        }
        return out;
    }

    /** Duplicates a mono channel into interleaved stereo. */
    static short[] monoToStereo(short[] mono) {
        short[] out = new short[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            out[2 * i] = mono[i];
            out[2 * i + 1] = mono[i];
        }
        return out;
    }

    static short[] resampleLinear(short[] in, int fromRate, int toRate) {
        if (fromRate == toRate || in.length == 0) {
            return in;
        }
        int outLen = (int) ((long) in.length * toRate / fromRate);
        if (outLen <= 0) {
            return new short[0];
        }
        short[] out = new short[outLen];
        double step = (double) fromRate / toRate;
        for (int i = 0; i < outLen; i++) {
            double srcPos = i * step;
            int idx = (int) srcPos;
            double frac = srcPos - idx;
            int a = in[Math.min(idx, in.length - 1)];
            int b = in[Math.min(idx + 1, in.length - 1)];
            out[i] = (short) Math.round(a + (b - a) * frac);
        }
        return out;
    }
}
