package bot.slash.ship;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;
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

public class ShipCommand extends SlashCommand {
    private static final Logger LOGGER = LogManager.getLogger(ShipCommand.class);
    private static final String USER_A_OPTION = "target";
    private static final String USER_B_OPTION = "ship";
    private static final ThreadLocalRandom RNG = ThreadLocalRandom.current();

    private final KlippyService klippyService;

    public ShipCommand(Config config) {
        super("ship", "Discover the romantic compatibility between two souls 💕");
        this.klippyService = new KlippyService(config);

        getData().addOptions(new OptionData(OptionType.USER, USER_A_OPTION, "Your beloved 💗", true));
        getData().addOptions(new OptionData(OptionType.USER, USER_B_OPTION, "The one who steals your heart 💘", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        try {
            Member userA = Objects.requireNonNull(
                    Objects.requireNonNull(event.getOption(USER_A_OPTION)).getAsMember());
            Member userB = Objects.requireNonNull(
                    Objects.requireNonNull(event.getOption(USER_B_OPTION)).getAsMember());

            boolean isSelfShip = userA.getId().equals(userB.getId());

            // Fetch romantic animated GIF for thumbnail
            String gifUrl = fetchRomanticGif(isSelfShip);

            // Calculate compatibility
            int percent = isSelfShip ? 100 : RNG.nextInt(0, 101);

            // Generate ship name
            String shipName =
                    generateShipName(userA.getUser().getName(), userB.getUser().getName());

            // Create the romantic ship card (static image)
            BufferedImage image = generateRomanticImage(
                    userA.getEffectiveAvatarUrl(),
                    userB.getEffectiveAvatarUrl(),
                    userA.getUser().getName(),
                    userB.getUser().getName(),
                    shipName,
                    percent,
                    isSelfShip);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] imageData = baos.toByteArray();
            FileUpload file = FileUpload.fromData(imageData, "ship.png");

            // Build beautiful embed with animated GIF thumbnail
            EmbedBuilder embed = buildRomanticEmbed(userA, userB, percent, shipName, isSelfShip, gifUrl);
            embed.setImage("attachment://ship.png");

            event.getHook().sendFiles(file).addEmbeds(embed.build()).queue();

        } catch (Exception e) {
            LOGGER.error("Failed to handle /{} command", event.getName(), e);
            event.getHook()
                    .sendMessage("💔 The love gods are having technical difficulties...")
                    .queue();
        }
    }

    private String fetchRomanticGif(boolean isSelfShip) {
        String[] queries = isSelfShip
                ? new String[] {
                    "self%20love%20heart%20animated", "pink%20hearts%20sparkle", "love%20yourself%20animated"
                }
                : new String[] {
                    "anime%20romance%20sparkle%20heart",
                    "pink%20glitter%20hearts%20animated",
                    "romantic%20sparkle%20aesthetic%20gif",
                    "shooting%20star%20heart%20love",
                    "glowing%20heart%20animated"
                };

        for (String query : queries) {
            Optional<String> result = klippyService.fetchGif(query);
            if (result.isPresent()) {
                return result.get();
            }
        }

        // Fallback to a known working romantic GIF if Klippy fails
        return "https://media.giphy.com/media/l378eCHnLEeMyyeYM/giphy.gif";
    }

    private EmbedBuilder buildRomanticEmbed(
            Member userA, Member userB, int percent, String shipName, boolean isSelfShip, String gifUrl) {
        EmbedBuilder embed = new EmbedBuilder();

        Color romanticColor = getRomanticColor(percent);
        embed.setColor(romanticColor);

        // Set animated GIF as thumbnail for that sparkling effect ✨
        embed.setThumbnail(gifUrl);

        if (isSelfShip) {
            embed.setTitle("💝 Self-Love is the Best Love! 💝");
            embed.setDescription("**" + userA.getAsMention() + "** ❤️ **themselves**\n\n"
                    + "*\"To love oneself is the beginning of a lifelong romance.\" - Oscar Wilde*\n\n"
                    + "**Ship Name:** "
                    + shipName + "\n" + "**Compatibility:** 💯 Perfect!");
            return embed;
        }

        ShipTier tier = getShipTier(percent);
        String loveQuote = getLoveQuote(percent);

        embed.setTitle(tier.getTitle());
        embed.setDescription("💕 **" + userA.getAsMention() + "** + **" + userB.getAsMention() + "** 💕\n\n" + "*\""
                + loveQuote + "\"*\n\n" + "**Ship Name:** `"
                + shipName + "`\n" + "**Compatibility Score:** `"
                + percent + "%`\n" + tier.getEmoji()
                + " *" + tier.getDescription() + "*");

        embed.setFooter("May your love story be written in the stars ✨", null);

        return embed;
    }

    private BufferedImage generateRomanticImage(
            String avatarURL1,
            String avatarURL2,
            String name1,
            String name2,
            String shipName,
            int percent,
            boolean isSelfShip)
            throws Exception {

        // Taller cinematic format for more romantic composition
        int width = 850;
        int height = 450;

        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();

        // Enable high quality rendering
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // ========== BACKGROUND - Beautiful romantic gradient ==========
        // Create a multi-stop gradient for a dreamy romantic atmosphere
        GradientPaint bgGradient = new GradientPaint(0, 0, new Color(30, 15, 35), width, height, new Color(60, 30, 70));
        g.setPaint(bgGradient);
        g.fillRect(0, 0, width, height);

        // Add soft pink/purple romantic glow overlay
        RadialGradientPaint romanticGlow = new RadialGradientPaint(
                new Point(width / 2, height / 2), width * 0.8f, new float[] {0f, 0.5f, 1f}, new Color[] {
                    new Color(100, 50, 100, 60), new Color(80, 40, 90, 40), new Color(40, 20, 50, 80)
                });
        g.setPaint(romanticGlow);
        g.fillRect(0, 0, width, height);

        // Add subtle vignette
        drawVignette(g, width, height);

        // ========== FLOATING HEARTS (Particle Effect) ==========
        drawFloatingHearts(g, width, height);

        // ========== AVATARS ==========
        int avatarSize = 140;
        int avatarY = 160;
        int leftX = 130;
        int rightX = width - 130 - avatarSize;

        BufferedImage avatar1 = circleCrop(ImageIO.read(new URL(avatarURL1)), avatarSize);
        BufferedImage avatar2 = circleCrop(ImageIO.read(new URL(avatarURL2)), avatarSize);

        // Draw romantic avatar frames with glow
        drawRomanticAvatar(g, avatar1, leftX, avatarY, avatarSize, getRomanticColor(percent));
        drawRomanticAvatar(g, avatar2, rightX, avatarY, avatarSize, getRomanticColor(percent));

        // ========== CENTER HEART WITH PERCENTAGE ==========
        int centerX = width / 2;
        int heartY = 200;

        // Draw pulsing heart glow
        drawHeartGlow(g, centerX, heartY, percent, isSelfShip);

        // Draw the heart shape
        drawStylizedHeart(g, centerX, heartY, 60, isSelfShip ? new Color(255, 105, 180) : getRomanticColor(percent));

        // Percentage text on heart
        g.setFont(new Font("Segoe UI", Font.BOLD, 32));
        String percentText = percent + "%";
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(percentText);

        // Shadow
        g.setColor(new Color(0, 0, 0, 150));
        g.drawString(percentText, centerX - textWidth / 2 + 2, heartY + 12);

        // Text
        g.setColor(Color.WHITE);
        g.drawString(percentText, centerX - textWidth / 2, heartY + 10);

        // ========== SHIP NAME ==========
        g.setFont(new Font("Segoe UI", Font.BOLD | Font.ITALIC, 28));
        String displayShipName = "💕 " + shipName + " 💕";
        fm = g.getFontMetrics();
        int shipNameWidth = fm.stringWidth(displayShipName);

        // Glowing text effect for ship name
        g.setColor(new Color(255, 182, 193, 100));
        g.drawString(displayShipName, centerX - shipNameWidth / 2 + 2, 95);
        g.drawString(displayShipName, centerX - shipNameWidth / 2 - 2, 95);

        g.setColor(new Color(255, 192, 203));
        g.drawString(displayShipName, centerX - shipNameWidth / 2, 95);

        // ========== USER NAMES ==========
        g.setFont(new Font("Segoe UI", Font.BOLD, 22));

        // Name 1 with shadow
        drawGlowingText(g, name1, leftX + avatarSize / 2, 340, Color.WHITE, new Color(255, 105, 180));

        // Name 2 with shadow
        drawGlowingText(g, name2, rightX + avatarSize / 2, 340, Color.WHITE, new Color(255, 105, 180));

        // ========== TIER LABEL ==========
        if (!isSelfShip) {
            ShipTier tier = getShipTier(percent);
            g.setFont(new Font("Segoe UI", Font.BOLD | Font.ITALIC, 18));
            String tierLabel = tier.getEmoji() + " " + tier.getLabel() + " " + tier.getEmoji();
            fm = g.getFontMetrics();
            int tierWidth = fm.stringWidth(tierLabel);

            g.setColor(new Color(0, 0, 0, 150));
            g.drawString(tierLabel, centerX - tierWidth / 2 + 1, 390);
            g.setColor(tier.getColor());
            g.drawString(tierLabel, centerX - tierWidth / 2, 389);
        } else {
            g.setFont(new Font("Segoe UI", Font.BOLD | Font.ITALIC, 18));
            String selfLoveMsg = "💖 Self Love Champion 💖";
            fm = g.getFontMetrics();
            int msgWidth = fm.stringWidth(selfLoveMsg);
            g.setColor(new Color(255, 105, 180));
            g.drawString(selfLoveMsg, centerX - msgWidth / 2, 389);
        }

        // ========== SPARKLES ==========
        drawSparkles(g, width, height);

        g.dispose();
        return canvas;
    }

    private void drawVignette(Graphics2D g, int width, int height) {
        RadialGradientPaint vignette = new RadialGradientPaint(
                new Point(width / 2, height / 2),
                Math.max(width, height) / 1.5f,
                new float[] {0f, 0.7f, 1f},
                new Color[] {new Color(0, 0, 0, 0), new Color(0, 0, 0, 50), new Color(20, 10, 20, 180)});
        g.setPaint(vignette);
        g.fillRect(0, 0, width, height);
    }

    private void drawFloatingHearts(Graphics2D g, int width, int height) {
        Color[] heartColors = {
            new Color(255, 182, 193, 120),
            new Color(255, 105, 180, 100),
            new Color(255, 20, 147, 80),
            new Color(255, 240, 245, 90)
        };

        // Pre-defined positions for consistent but scattered hearts
        int[][] heartPositions = {
            {80, 80}, {200, 50}, {350, 40}, {500, 60}, {650, 45}, {780, 90},
            {50, 200}, {750, 250}, {40, 350}, {800, 380}, {120, 420}, {700, 420}
        };

        int[] heartSizes = {12, 16, 10, 18, 14, 11, 15, 13, 17, 12, 14, 10};

        for (int i = 0; i < heartPositions.length; i++) {
            int x = heartPositions[i][0];
            int y = heartPositions[i][1];
            int size = heartSizes[i];
            Color color = heartColors[i % heartColors.length];

            g.setColor(color);
            drawSmallHeart(g, x, y, size);
        }
    }

    private void drawSmallHeart(Graphics2D g, int x, int y, int size) {
        int half = size / 2;
        Path2D heart = new Path2D.Double();
        heart.moveTo(x, y + half / 2);
        heart.curveTo(x, y - half / 2, x - half, y - half / 2, x - half, y);
        heart.curveTo(x - half, y + half, x, y + size, x, y + size);
        heart.curveTo(x, y + size, x + half, y + half, x + half, y);
        heart.curveTo(x + half, y - half / 2, x, y - half / 2, x, y + half / 2);
        heart.closePath();
        g.fill(heart);
    }

    private void drawRomanticAvatar(Graphics2D g, BufferedImage avatar, int x, int y, int size, Color accentColor) {
        // Outer glow ring
        for (int i = 8; i >= 1; i--) {
            float alpha = 0.12f * (9 - i);
            int alphaValue = Math.min(255, (int) (255 * alpha));
            g.setColor(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), alphaValue));
            g.fillOval(x - i, y - i, size + 2 * i, size + 2 * i);
        }

        // White border
        g.setColor(Color.WHITE);
        g.fillOval(x - 4, y - 4, size + 8, size + 8);

        // Accent inner border
        g.setColor(accentColor);
        g.fillOval(x - 2, y - 2, size + 4, size + 4);

        // Avatar
        g.drawImage(avatar, x, y, null);
    }

    private void drawHeartGlow(Graphics2D g, int centerX, int centerY, int percent, boolean isSelfShip) {
        Color glowColor = isSelfShip ? new Color(255, 105, 180) : getRomanticColor(percent);

        // Multiple glow layers
        for (int i = 6; i >= 1; i--) {
            float alpha = 0.08f * (7 - i);
            int glowSize = 50 + i * 15;
            RadialGradientPaint glow =
                    new RadialGradientPaint(new Point(centerX, centerY), glowSize, new float[] {0f, 1f}, new Color[] {
                        new Color(glowColor.getRed(), glowColor.getGreen(), glowColor.getBlue(), (int) (200 * alpha)),
                        new Color(glowColor.getRed(), glowColor.getGreen(), glowColor.getBlue(), 0)
                    });
            g.setPaint(glow);
            g.fillOval(centerX - glowSize, centerY - glowSize, glowSize * 2, glowSize * 2);
        }
    }

    private void drawStylizedHeart(Graphics2D g, int x, int y, int size, Color color) {
        // Create a beautiful heart shape
        Path2D heart = new Path2D.Double();
        int half = size / 2;

        heart.moveTo(x, y + half);
        heart.curveTo(x, y - half / 2, x - size, y - size / 2, x - size, y);
        heart.curveTo(x - size, y + size, x, y + size * 1.8, x, y + size * 1.8);
        heart.curveTo(x, y + size * 1.8, x + size, y + size, x + size, y);
        heart.curveTo(x + size, y - size / 2, x, y - half / 2, x, y + half);
        heart.closePath();

        // Gradient fill
        GradientPaint heartGradient =
                new GradientPaint(x - size, y - size, color.brighter(), x + size, y + size, color.darker());
        g.setPaint(heartGradient);
        g.fill(heart);

        // Highlight
        g.setColor(new Color(255, 255, 255, 80));
        g.fillOval(x - size / 2, y - size / 3, size / 2, size / 3);
    }

    private void drawGlowingText(Graphics2D g, String text, int centerX, int y, Color textColor, Color glowColor) {
        FontMetrics fm = g.getFontMetrics();
        int x = centerX - fm.stringWidth(text) / 2;

        // Glow effect
        g.setColor(glowColor);
        g.drawString(text, x - 1, y);
        g.drawString(text, x + 1, y);
        g.drawString(text, x, y - 1);
        g.drawString(text, x, y + 1);

        // Shadow
        g.setColor(new Color(0, 0, 0, 150));
        g.drawString(text, x + 2, y + 2);

        // Main text
        g.setColor(textColor);
        g.drawString(text, x, y);
    }

    private void drawSparkles(Graphics2D g, int width, int height) {
        g.setColor(new Color(255, 255, 255, 200));

        int[][] sparklePositions = {
            {150, 120}, {700, 150}, {200, 380}, {650, 360},
            {300, 100}, {550, 110}, {100, 300}, {750, 320}
        };

        for (int[] pos : sparklePositions) {
            drawSparkle(g, pos[0], pos[1], RNG.nextInt(6, 12));
        }
    }

    private void drawSparkle(Graphics2D g, int x, int y, int size) {
        // Four-pointed star
        int half = size / 2;
        g.fillOval(x - 1, y - half, 2, size);
        g.fillOval(x - half, y - 1, size, 2);
        g.fillOval(x - half / 2, y - half / 2, half, half);
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

    private String generateShipName(String name1, String name2) {
        if (name1.equalsIgnoreCase(name2)) {
            return name1 + "²";
        }

        String lower1 = name1.toLowerCase();
        String lower2 = name2.toLowerCase();

        // Find shared starting letters or create blend
        int split1 = Math.max(1, name1.length() / 2);
        int split2 = Math.max(1, name2.length() / 2);

        String part1 = name1.substring(0, split1);
        String part2 = name2.substring(split2);

        String combined = part1 + part2;

        // Capitalize first letter
        return Character.toUpperCase(combined.charAt(0)) + combined.substring(1);
    }

    private Color getRomanticColor(int percent) {
        if (percent >= 90) return new Color(255, 105, 180); // Hot pink
        if (percent >= 80) return new Color(255, 20, 147); // Deep pink
        if (percent >= 70) return new Color(255, 182, 193); // Light pink
        if (percent >= 60) return new Color(221, 160, 221); // Plum
        if (percent >= 50) return new Color(147, 112, 219); // Medium purple
        if (percent >= 40) return new Color(138, 43, 226); // Blue violet
        if (percent >= 30) return new Color(106, 90, 205); // Slate blue
        if (percent >= 20) return new Color(72, 61, 139); // Dark slate blue
        if (percent >= 10) return new Color(119, 136, 153); // Light slate gray
        return new Color(112, 128, 144); // Slate gray
    }

    private String getLoveQuote(int percent) {
        if (percent >= 90)
            return randomFrom(
                    "Two souls with but a single thought, two hearts that beat as one.",
                    "In all the world, there is no heart for me like yours.",
                    "We loved with a love that was more than love.",
                    "You are my sun, my moon, and all my stars.",
                    "Whatever our souls are made of, his and mine are the same.");
        if (percent >= 80)
            return randomFrom(
                    "If I know what love is, it is because of you.",
                    "To love is to burn, to be on fire.",
                    "I saw that you were perfect, and so I loved you.",
                    "You are the finest, loveliest, tenderest, and most beautiful person I have ever known.",
                    "I would rather spend one lifetime with you than face all the ages of this world alone.");
        if (percent >= 70)
            return randomFrom(
                    "Love is composed of a single soul inhabiting two bodies.",
                    "The best thing to hold onto in life is each other.",
                    "You are my heart, my life, my one and only thought.",
                    "I look at you and see the rest of my life in front of my eyes.",
                    "Together is a wonderful place to be.");
        if (percent >= 60)
            return randomFrom(
                    "There is no remedy for love but to love more.",
                    "Love is a friendship set to music.",
                    "The heart has its reasons which reason knows not.",
                    "We are most alive when we're in love.",
                    "You make me want to be a better person.");
        if (percent >= 50)
            return randomFrom(
                    "Love is the greatest refreshment in life.",
                    "You are my favorite notification.",
                    "Sometimes the heart sees what is invisible to the eye.",
                    "Love is an endless act of forgiveness.",
                    "The greatest happiness of life is the conviction that we are loved.");
        if (percent >= 40)
            return randomFrom(
                    "Love is patient, love is kind.",
                    "The course of true love never did run smooth.",
                    "There is always some madness in love.",
                    "We accept the love we think we deserve.",
                    "To love at all is to be vulnerable.");
        if (percent >= 30)
            return randomFrom(
                    "Friendship is love without his wings.",
                    "Love is a serious mental disease.",
                    "It is better to have loved and lost than never to have loved at all.",
                    "Love is blind.",
                    "The art of love is largely the art of persistence.");
        if (percent >= 20)
            return randomFrom(
                    "Not all those who wander are lost, but this ship might be.",
                    "Love is a battlefield.",
                    "Sometimes love just ain't enough.",
                    "Better to remain friends than risk the heartache.",
                    "Some ships are meant to stay in harbor.");
        if (percent >= 10)
            return randomFrom(
                    "The heart was made to be broken.",
                    "Love is a smoke made with the fume of sighs.",
                    "Some people are meant to fall in love with each other, but not meant to be together.",
                    "This love may need some time to bloom.",
                    "Perhaps in another lifetime...");
        return randomFrom(
                "Love is a trap. When it appears, we see only its light, not its shadows.",
                "Some ships were never meant to sail.",
                "The course of this love runs through rocky waters.",
                "Sometimes the best love is the love we don't pursue.",
                "This ship has hit an iceberg called reality.");
    }

    private ShipTier getShipTier(int percent) {
        if (percent >= 95)
            return new ShipTier("💍💍💍", "Destined Soulmates", "Written in the stars", new Color(255, 215, 0));
        if (percent >= 90)
            return new ShipTier("💍💍", "True Soulmates", "A love for the ages", new Color(255, 105, 180));
        if (percent >= 85) return new ShipTier("💍", "Perfect Match", "Marriage material!", new Color(255, 20, 147));
        if (percent >= 80)
            return new ShipTier("💕💕💕", "Power Couple", "The world envies you", new Color(220, 20, 60));
        if (percent >= 75) return new ShipTier("💕💕", "Deeply in Love", "Hearts intertwined", new Color(255, 69, 0));
        if (percent >= 70) return new ShipTier("💕", "Loving Couple", "Beautiful chemistry", new Color(255, 140, 0));
        if (percent >= 65) return new ShipTier("💖💖", "Passionate Lovers", "Fire and desire", new Color(255, 165, 0));
        if (percent >= 60) return new ShipTier("💖", "Sweethearts", "Growing affection", new Color(255, 215, 0));
        if (percent >= 55) return new ShipTier("💝💝", "Romantic Pair", "Sparks flying", new Color(238, 130, 238));
        if (percent >= 50)
            return new ShipTier("💝", "Potential Couple", "Something special brewing", new Color(147, 112, 219));
        if (percent >= 45) return new ShipTier("💗💗", "Good Friends", "Solid foundation", new Color(100, 149, 237));
        if (percent >= 40) return new ShipTier("💗", "Friendly Duo", "Nice compatibility", new Color(72, 209, 204));
        if (percent >= 35) return new ShipTier("💓💓", "Awkward Pair", "Needs more time", new Color(70, 130, 180));
        if (percent >= 30) return new ShipTier("💓", "Complicated", "It's... something", new Color(119, 136, 153));
        if (percent >= 25) return new ShipTier("💔💔", "Rocky Waters", "Tread carefully", new Color(112, 128, 144));
        if (percent >= 20) return new ShipTier("💔", "Strained", "Rough seas ahead", new Color(105, 105, 105));
        if (percent >= 15) return new ShipTier("🌊🌊", "Turbulent", "Stormy relationship", new Color(128, 128, 128));
        if (percent >= 10) return new ShipTier("🌊", "Sinking Ship", "Abandon ship!", new Color(169, 169, 169));
        return new ShipTier("⚓", "Iceberg Ahead", "This ship has sunk", new Color(220, 220, 220));
    }

    private String randomFrom(String... options) {
        return options[RNG.nextInt(options.length)];
    }

    // Helper class for ship tiers
    private static class ShipTier {
        private final String emoji;
        private final String label;
        private final String description;
        private final Color color;

        ShipTier(String emoji, String label, String description, Color color) {
            this.emoji = emoji;
            this.label = label;
            this.description = description;
            this.color = color;
        }

        String getEmoji() {
            return emoji;
        }

        String getLabel() {
            return label;
        }

        String getDescription() {
            return description;
        }

        Color getColor() {
            return color;
        }

        String getTitle() {
            return emoji + " " + label + " " + emoji;
        }
    }
}
