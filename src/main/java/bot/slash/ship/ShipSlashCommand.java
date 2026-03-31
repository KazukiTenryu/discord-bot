package bot.slash.ship;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import javax.imageio.ImageIO;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.FileUpload;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.config.Config;
import bot.slash.SlashCommand;
import bot.utils.KlippyService;

public class ShipSlashCommand extends SlashCommand {
    private static final Logger LOGGER = LogManager.getLogger(ShipSlashCommand.class);
    private static final String USER_A_OPTION = "target";
    private static final String USER_B_OPTION = "ship";
    private final KlippyService klippyService;

    public ShipSlashCommand(Config config) {
        super("ship", "See the compatability of 2 users");

        this.klippyService = new KlippyService(config);

        getData().addOptions(new OptionData(OptionType.USER, USER_A_OPTION, "the user to ship", true));
        getData().addOptions(new OptionData(OptionType.USER, USER_B_OPTION, "the user to ship to", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        try {
            Member userA = Objects.requireNonNull(
                    Objects.requireNonNull(event.getOption(USER_A_OPTION)).getAsMember());

            Member userB = Objects.requireNonNull(
                    Objects.requireNonNull(event.getOption(USER_B_OPTION)).getAsMember());

            String bgUrl = klippyService.fetchGif("anime%20romance%20kissing").orElseThrow();

            int percent = ThreadLocalRandom.current().nextInt(0, 101);

            BufferedImage image = generateShipImage(
                    userA.getEffectiveAvatarUrl(),
                    userB.getEffectiveAvatarUrl(),
                    userA.getUser().getName(),
                    userB.getUser().getName(),
                    bgUrl,
                    percent);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);

            event.getHook()
                    .sendFiles(FileUpload.fromData(baos.toByteArray(), "ship.png"))
                    .queue();
        } catch (Exception e) {
            LOGGER.error("Failed to handle /{}", event.getName(), e);
        }
    }

    private BufferedImage generateShipImage(
            String avatarURL1, String avatarURL2,
            String name1, String name2,
            String backgroundURL, int percent
    ) throws Exception {

        int width = 800;
        int height = 320; // thinner banner

        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // ───── Background ─────
        BufferedImage bg = ImageIO.read(new URL(backgroundURL));
        g.drawImage(bg, 0, 0, width, height, null);

        // soft gradient overlay (WAY nicer than flat dark layer)
        GradientPaint gradient = new GradientPaint(
                0, 0, new Color(0,0,0,180),
                width, height, new Color(0,0,0,120)
        );
        g.setPaint(gradient);
        g.fillRect(0,0,width,height);

        // ───── Avatar setup ─────
        int avatarSize = 150;
        int avatarY = height/2 - avatarSize/2;

        int leftX = 140;
        int rightX = width - 290;

        BufferedImage avatar1 = circleCrop(ImageIO.read(new URL(avatarURL1)), avatarSize);
        BufferedImage avatar2 = circleCrop(ImageIO.read(new URL(avatarURL2)), avatarSize);

        // draw avatar with border + shadow
        drawAvatarWithBorder(g, avatar1, leftX, avatarY, avatarSize);
        drawAvatarWithBorder(g, avatar2, rightX, avatarY, avatarSize);

        // ───── Percentage + heart ─────
        String percentText = percent + "%";
        String heart = "❤";

        g.setFont(new Font("Arial", Font.BOLD, 56));
        FontMetrics fm = g.getFontMetrics();

        int centerX = width / 2;
        int textWidth = fm.stringWidth(percentText + " " + heart);
        int textX = centerX - textWidth / 2;
        int textY = height/2 + 20;

        // shadow
        g.setColor(new Color(0,0,0,180));
        g.drawString(percentText, textX + 3, textY + 3);

        // percentage text
        g.setColor(Color.WHITE);
        g.drawString(percentText, textX, textY);

        // heart in pink ❤️
        int percentWidth = fm.stringWidth(percentText + " ");
        g.setColor(new Color(255, 80, 110));
        g.drawString(heart, textX + percentWidth, textY);

        // ───── Names ─────
        g.setFont(new Font("Arial", Font.BOLD, 24));
        drawCenteredString(g, name1, leftX + avatarSize/2, height - 20);
        drawCenteredString(g, name2, rightX + avatarSize/2, height - 20);

        g.dispose();
        return canvas;
    }

    private void drawAvatarWithBorder(Graphics2D g, BufferedImage avatar, int x, int y, int size) {
        // soft shadow
        g.setColor(new Color(0,0,0,120));
        g.fillOval(x+4, y+4, size, size);

        // white border ring
        g.setColor(Color.WHITE);
        g.fillOval(x-3, y-3, size+6, size+6);

        // avatar on top
        g.drawImage(avatar, x, y, null);
    }

    private BufferedImage circleCrop(BufferedImage src, int size) {
        BufferedImage output = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = output.createGraphics();

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setClip(new Ellipse2D.Float(0, 0, size, size));
        g2.drawImage(src, 0, 0, size, size, null);

        g2.dispose();
        return output;
    }

    private void drawCenteredString(Graphics2D g, String text, int centerX, int y) {
        FontMetrics metrics = g.getFontMetrics(g.getFont());
        int x = centerX - metrics.stringWidth(text) / 2;

        // text shadow
        g.setColor(Color.BLACK);
        g.drawString(text, x + 2, y + 2);

        g.setColor(Color.WHITE);
        g.drawString(text, x, y);
    }
}
