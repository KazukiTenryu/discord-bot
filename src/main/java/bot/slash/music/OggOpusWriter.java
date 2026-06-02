package bot.slash.music;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Ogg/Opus muxer: wraps a stream of raw Opus packets (one per 20ms frame, as produced by
 * LavaPlayer's {@code DISCORD_OPUS} output) into an {@code .ogg} file that Discord renders with its
 * native inline audio player.
 *
 * <p>Layout follows RFC 7845: a beginning-of-stream page carrying the {@code OpusHead} identification
 * header, a page carrying the {@code OpusTags} comment header, then audio pages each packing as many
 * packets as fit within Ogg's 255-segment-per-page limit. Granule positions count samples at the
 * fixed 48kHz Opus timebase (960 per 20ms frame).
 */
final class OggOpusWriter {
    private static final byte[] OGG_CAPTURE = {'O', 'g', 'g', 'S'};
    private static final int SAMPLES_PER_FRAME = 960; // 20ms @ 48kHz
    private static final int MAX_SEGMENTS_PER_PAGE = 255;

    private static final int HEADER_TYPE_BOS = 0x02;
    private static final int HEADER_TYPE_EOS = 0x04;

    private static final int[] CRC_TABLE = buildCrcTable();

    private final OutputStream out;
    private final int channels;
    private final int serialNumber = 0x4F505553; // "OPUS" — arbitrary, just unique within the file

    private int pageSequence = 0;
    private long granulePosition = 0;

    // Audio packets buffered for the page currently being assembled.
    private final List<byte[]> pendingPackets = new ArrayList<>();
    private int pendingSegments = 0;

    OggOpusWriter(OutputStream out, int channels) {
        this.out = out;
        this.channels = channels;
    }

    /** Writes the two mandatory Opus header pages. Call once before any audio packet. */
    void writeHeaders() throws IOException {
        writePage(List.of(buildOpusHead()), HEADER_TYPE_BOS, 0);
        writePage(List.of(buildOpusTags()), 0, 0);
    }

    /** Appends one Opus frame, flushing the current page first when it would overflow. */
    void writeAudioPacket(byte[] packet) throws IOException {
        int segments = segmentsFor(packet.length);
        if (pendingSegments + segments > MAX_SEGMENTS_PER_PAGE) {
            flushPending(false);
        }
        pendingPackets.add(packet);
        pendingSegments += segments;
        granulePosition += SAMPLES_PER_FRAME;
    }

    /** Flushes the trailing audio page with the end-of-stream flag set. */
    void finish() throws IOException {
        flushPending(true);
    }

    private void flushPending(boolean endOfStream) throws IOException {
        if (pendingPackets.isEmpty()) {
            if (endOfStream) {
                // Emit an empty terminating page so the stream is always closed cleanly.
                writePage(List.of(), HEADER_TYPE_EOS, granulePosition);
            }
            return;
        }
        writePage(pendingPackets, endOfStream ? HEADER_TYPE_EOS : 0, granulePosition);
        pendingPackets.clear();
        pendingSegments = 0;
    }

    /** Assembles, checksums, and writes a single Ogg page containing {@code packets}. */
    private void writePage(List<byte[]> packets, int headerType, long granule) throws IOException {
        ByteArrayOutputStream segmentTable = new ByteArrayOutputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] packet : packets) {
            int remaining = packet.length;
            while (remaining >= 255) {
                segmentTable.write(255);
                remaining -= 255;
            }
            segmentTable.write(remaining); // final lacing value (0 if length is a multiple of 255)
            body.writeBytes(packet);
        }

        ByteArrayOutputStream page = new ByteArrayOutputStream();
        page.writeBytes(OGG_CAPTURE);
        page.write(0); // stream structure version
        page.write(headerType);
        writeLongLE(page, granule);
        writeIntLE(page, serialNumber);
        writeIntLE(page, pageSequence++);
        writeIntLE(page, 0); // CRC placeholder — patched in below
        page.write(segmentTable.size());
        page.writeBytes(segmentTable.toByteArray());
        page.writeBytes(body.toByteArray());

        byte[] bytes = page.toByteArray();
        int crc = checksum(bytes);
        // CRC field sits at offset 22 (after capture/version/type/granule/serial/sequence).
        bytes[22] = (byte) crc;
        bytes[23] = (byte) (crc >>> 8);
        bytes[24] = (byte) (crc >>> 16);
        bytes[25] = (byte) (crc >>> 24);
        out.write(bytes);
    }

    private byte[] buildOpusHead() {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        head.writeBytes("OpusHead".getBytes(StandardCharsets.US_ASCII));
        head.write(1); // version
        head.write(channels);
        writeShortLE(head, 0); // pre-skip (no encoder priming to trim)
        writeIntLE(head, 48000); // original input sample rate
        writeShortLE(head, 0); // output gain
        head.write(0); // channel mapping family 0 (mono/stereo)
        return head.toByteArray();
    }

    private byte[] buildOpusTags() {
        ByteArrayOutputStream tags = new ByteArrayOutputStream();
        tags.writeBytes("OpusTags".getBytes(StandardCharsets.US_ASCII));
        byte[] vendor = "discord-bot-kazuki".getBytes(StandardCharsets.UTF_8);
        writeIntLE(tags, vendor.length);
        tags.writeBytes(vendor);
        writeIntLE(tags, 0); // user comment count
        return tags.toByteArray();
    }

    private static int segmentsFor(int packetLength) {
        return packetLength / 255 + 1;
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeLongLE(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) (value & 0xFF));
            value >>>= 8;
        }
    }

    /** Ogg's CRC-32: polynomial 0x04C11DB7, no reflection, zero init, no final XOR. */
    private static int checksum(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (b & 0xFF)) & 0xFF];
        }
        return crc;
    }

    private static int[] buildCrcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            }
            table[i] = r;
        }
        return table;
    }
}
