package bot.slash.ship;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import javax.imageio.ImageIO;

import net.dv8tion.jda.api.EmbedBuilder;
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

            byte[] imageData = baos.toByteArray();
            FileUpload file = FileUpload.fromData(imageData, "ship.png");

            String mentionA = userA.getAsMention();
            String mentionB = userB.getAsMention();

            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle(getShipTitle(percent));
            embed.setColor(new Color(255, 105, 180)); // cute pink
            embed.setDescription(mentionA + " ❤️❤️❤️ " + mentionB);
            embed.setImage("attachment://ship.png");

            event.getHook()
                    .sendFiles(file)
                    .addEmbeds(embed.build())
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

    private static final ThreadLocalRandom RNG = ThreadLocalRandom.current();

    private String getShipTitle(int percent) {

        if (percent < 10) return randomFrom(
                "💀 This should be illegal.",
                "The universe said NO.",
                "Blocked in every timeline.",
                "Even enemies have more chemistry.",
                "Discord TOS violation level pairing.",
                "A love story written in crayon and tears.",
                "Absolutely catastrophic.",
                "Cupid missed and hit the wall.",
                "This ship sank before leaving port.",
                "Please reconsider your life choices."
        );

        if (percent < 20) return randomFrom(
                "This is a certified disaster.",
                "Maybe… don’t try this.",
                "Yikes 😬",
                "Friendzone speedrun.",
                "Chemistry not detected.",
                "The vibes are… absent.",
                "Cupid is on vacation.",
                "This feels illegal somehow.",
                "Please remain 10 meters apart.",
                "We recommend therapy instead."
        );

        if (percent < 30) return randomFrom(
                "Please stay friends.",
                "Not the worst… but close.",
                "A risky experiment.",
                "There is… potential? maybe?",
                "The spark is buffering.",
                "It’s giving awkward silence.",
                "This could start a sitcom.",
                "Strangers energy.",
                "Low battery relationship.",
                "Patch notes required."
        );

        if (percent < 40) return randomFrom(
                "Hmm… maybe in another timeline.",
                "A small spark exists.",
                "Early access relationship.",
                "Needs DLC to work.",
                "We’re warming up.",
                "Beta testing love.",
                "Plot development required.",
                "Slow burn arc starting.",
                "The romcom just began.",
                "We see potential."
        );

        if (percent < 50) return randomFrom(
                "There’s a tiny spark.",
                "Now we’re getting somewhere.",
                "Cute but clumsy.",
                "This could become something.",
                "Early butterflies detected.",
                "Halfway to adorable.",
                "We’re cautiously optimistic.",
                "It might just work.",
                "Potential unlocked.",
                "The ship is floating!"
        );

        if (percent < 60) return randomFrom(
                "This could work 👀",
                "Okay this is kinda cute.",
                "Chemistry increasing.",
                "Love.exe starting.",
                "We’re vibing now.",
                "This ship has wind!",
                "Not bad at all.",
                "Things are heating up.",
                "We approve this ship.",
                "Romance arc unlocked."
        );

        if (percent < 70) return randomFrom(
                "Looking pretty cute together!",
                "This ship is sailing!",
                "Love is in the air 💕",
                "Strong romcom energy.",
                "Now we’re talking!",
                "Very promising duo.",
                "This feels right.",
                "Certified cute couple.",
                "Cupid is paying attention.",
                "The fandom approves."
        );

        if (percent < 80) return randomFrom(
                "Now we’re talking 💕",
                "Serious relationship vibes.",
                "This ship has momentum.",
                "Chemistry level: HIGH.",
                "A very cute couple.",
                "Romance anime arc unlocked.",
                "This is getting real.",
                "Heart eyes detected 😍",
                "Love is blooming.",
                "We ship it!"
        );

        if (percent < 90) return randomFrom(
                "This is getting serious 😳",
                "We hear wedding bells.",
                "Power couple energy.",
                "The romance arc peaked.",
                "Main character couple.",
                "The ship is unstoppable.",
                "Love overload.",
                "Couple goals unlocked.",
                "This is beautiful.",
                "True love arc."
        );

        if (percent < 100) return randomFrom(
                "A match made in heaven 💖",
                "This is destiny.",
                "Cosmic level compatibility.",
                "The stars aligned.",
                "Perfectly synced souls.",
                "Legendary romance.",
                "The ultimate ship.",
                "Fate approved this.",
                "Heaven signed the papers.",
                "Peak love achieved."
        );

        return randomFrom(
                "💍 Soulmates. Wedding when?",
                "This is THE ship.",
                "Absolute perfection.",
                "Marriage speedrun.",
                "The universe ships this.",
                "Unbreakable bond.",
                "True love confirmed.",
                "They were written in the stars.",
                "Happily ever after unlocked.",
                "Maximum love achieved."
        );
    }

    private String randomFrom(String... options) {
        return options[RNG.nextInt(options.length)];
    }
}
