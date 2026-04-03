package bot.slash.gif;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.List;

import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.ImageInputStream;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.FileUpload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.NodeList;

import com.madgag.gif.fmsware.AnimatedGifEncoder;

import bot.config.Config;
import bot.slash.SlashCommand;
import bot.utils.KlippyService;

public class GifCommand extends SlashCommand {
    private static final Logger LOGGER = LogManager.getLogger(GifCommand.class);

    private static final String MESSAGE_OPTION = "message";
    private static final String THEME_OPTION = "theme";

    private final KlippyService klippyService;

    public GifCommand(Config config) {
        super("gif", "Create a custom GIF with your text overlay 🎬");
        this.klippyService = new KlippyService(config);

        getData().addOptions(new OptionData(OptionType.STRING, MESSAGE_OPTION, "Text to overlay", true));
        getData().addOptions(new OptionData(OptionType.STRING, THEME_OPTION, "GIF theme", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        try {
            String message =
                    Objects.requireNonNull(event.getOption(MESSAGE_OPTION)).getAsString();
            String theme = Objects.requireNonNull(event.getOption(THEME_OPTION)).getAsString();

            Optional<String> gifUrlOpt = klippyService.fetchGif(theme.replace(" ", "%20"));

            if (gifUrlOpt.isEmpty()) {
                event.getHook().sendMessage("❌ Couldn't find a GIF!").queue();
                return;
            }

            byte[] result = processGif(gifUrlOpt.get(), message);

            if (result == null) {
                event.getHook().sendMessage("❌ Failed to process GIF").queue();
                return;
            }

            event.getHook().sendFiles(FileUpload.fromData(result, "custom.gif")).queue();

        } catch (Exception e) {
            LOGGER.error("GIF command failed", e);
            event.getHook().sendMessage("❌ Something went wrong").queue();
        }
    }

    private byte[] processGif(String gifUrl, String text) throws Exception {
        URL url = new URL(gifUrl);

        try (InputStream is = url.openStream();
                ImageInputStream iis = ImageIO.createImageInputStream(is)) {

            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(iis);

            int frameCount = reader.getNumImages(true);

            List<FrameData> frames = new ArrayList<>();

            BufferedImage master = null;
            Graphics2D masterGraphics = null;

            for (int i = 0; i < frameCount; i++) {
                BufferedImage frame = reader.read(i);
                IIOMetadata metadata = reader.getImageMetadata(i);

                int delay = getDelay(metadata);
                FrameInfo info = getFrameInfo(metadata);

                if (master == null) {
                    master = new BufferedImage(reader.getWidth(0), reader.getHeight(0), BufferedImage.TYPE_INT_ARGB);
                    masterGraphics = master.createGraphics();
                }

                // Handle disposal
                if ("restoreToBackgroundColor".equals(info.disposalMethod)) {
                    masterGraphics.clearRect(info.x, info.y, frame.getWidth(), frame.getHeight());
                }

                // Draw frame
                masterGraphics.drawImage(frame, info.x, info.y, null);

                // Copy full frame
                BufferedImage fullFrame =
                        new BufferedImage(master.getWidth(), master.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = fullFrame.createGraphics();
                g.drawImage(master, 0, 0, null);
                g.dispose();

                // Overlay text
                BufferedImage withText = overlayText(fullFrame, text);

                // Convert to indexed (GIF-friendly)
                BufferedImage indexed = toIndexed(withText);

                frames.add(new FrameData(indexed, delay));
            }

            reader.dispose();
            return encodeGif(frames);
        }
    }

    private byte[] encodeGif(List<FrameData> frames) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        AnimatedGifEncoder encoder = new AnimatedGifEncoder();
        encoder.start(baos);
        encoder.setRepeat(0);

        for (FrameData frame : frames) {
            encoder.setDelay(frame.delay);
            encoder.addFrame(frame.image);
        }

        encoder.finish();
        return baos.toByteArray();
    }

    private int getDelay(IIOMetadata metadata) {
        try {
            String format = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

            NodeList children = root.getChildNodes();

            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof IIOMetadataNode node) {
                    if ("GraphicControlExtension".equals(node.getNodeName())) {
                        return Integer.parseInt(node.getAttribute("delayTime")) * 10;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return 100;
    }

    private FrameInfo getFrameInfo(IIOMetadata metadata) {
        int x = 0, y = 0;
        String disposal = "none";

        try {
            String format = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

            NodeList children = root.getChildNodes();

            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof IIOMetadataNode node) {

                    if ("ImageDescriptor".equals(node.getNodeName())) {
                        x = Integer.parseInt(node.getAttribute("imageLeftPosition"));
                        y = Integer.parseInt(node.getAttribute("imageTopPosition"));
                    }

                    if ("GraphicControlExtension".equals(node.getNodeName())) {
                        disposal = node.getAttribute("disposalMethod");
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Metadata parse failed", e);
        }

        return new FrameInfo(x, y, disposal);
    }

    private BufferedImage overlayText(BufferedImage image, String text) {
        Graphics2D g = image.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int fontSize = Math.min(image.getWidth() / 8, 42);
        Font font = new Font("SansSerif", Font.BOLD, fontSize);
        g.setFont(font);

        FontMetrics fm = g.getFontMetrics();
        int maxWidth = image.getWidth() - 30;

        List<String> lines = wrapText(text, fm, maxWidth);

        int lineHeight = fm.getHeight();
        int totalHeight = lines.size() * lineHeight;

        int y = image.getHeight() - totalHeight - 20;

        for (String line : lines) {
            int x = (image.getWidth() - fm.stringWidth(line)) / 2;

            // Outline
            g.setColor(Color.BLACK);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    g.drawString(line, x + dx, y + dy);
                }
            }

            // Text
            g.setColor(Color.WHITE);
            g.drawString(line, x, y);

            y += lineHeight;
        }

        g.dispose();
        return image;
    }

    private BufferedImage toIndexed(BufferedImage src) {
        BufferedImage indexed = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_INDEXED);

        Graphics2D g = indexed.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();

        return indexed;
    }

    private List<String> wrapText(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();

        StringBuilder line = new StringBuilder();

        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;

            if (fm.stringWidth(test) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }

        if (!line.isEmpty()) lines.add(line.toString());

        return lines;
    }

    private record FrameData(BufferedImage image, int delay) {}

    private static class FrameInfo {
        int x, y;
        String disposalMethod;

        FrameInfo(int x, int y, String disposalMethod) {
            this.x = x;
            this.y = y;
            this.disposalMethod = disposalMethod;
        }
    }
}
