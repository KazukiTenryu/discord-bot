package bot.slash.rizz;

import java.awt.Color;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.config.Config;
import bot.slash.SlashCommand;

public class RizzCommand extends SlashCommand {
    private static final Logger LOGGER = LogManager.getLogger(RizzCommand.class);
    private static final String TARGET_OPTION = "target";
    private static final String STYLE_OPTION = "style";
    private static final ThreadLocalRandom RNG = ThreadLocalRandom.current();

    private final PickupLineService pickupLineService;

    public RizzCommand(Config config) {
        super("rizz", "Shoot your shot with a flirty pickup line 💘");

        this.pickupLineService = new PickupLineService(config.kimiApiKey());

        getData().addOptions(new OptionData(OptionType.USER, TARGET_OPTION, "Who you're trying to rizz up", true));
        getData()
                .addOptions(new OptionData(OptionType.STRING, STYLE_OPTION, "The style of your rizz", false)
                        .addChoice("🍯 Smooth", "smooth")
                        .addChoice("🧀 Cheesy", "cheesy")
                        .addChoice("🔥 Spicy", "spicy")
                        .addChoice("🎲 Random", "random"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        Member author = Objects.requireNonNull(event.getMember());
        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption(TARGET_OPTION)).getAsMember());

        String style = event.getOption(STYLE_OPTION) != null
                ? Objects.requireNonNull(event.getOption(STYLE_OPTION)).getAsString()
                : "random";

        if (author.getId().equals(target.getId())) {
            event.getHook()
                    .sendMessage("💅 Self-love is important, but you can't rizz yourself! Try someone else~")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String actualStyle = "random".equals(style) ? pickRandomStyle() : style;

        Optional<String> aiLine =
                pickupLineService.generatePickupLine(actualStyle, author.getEffectiveName(), target.getEffectiveName());

        PickupLine line;
        boolean isAiGenerated;

        if (aiLine.isPresent()) {
            line = new PickupLine(aiLine.get(), actualStyle, getStyleName(actualStyle), getStyleColor(actualStyle));
            LOGGER.debug("Using AI-generated pickup line for style: {}", actualStyle);
        } else {
            line = getHardcodedLine(actualStyle);
            LOGGER.debug("Falling back to hardcoded pickup line for style: {}", actualStyle);
        }

        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(line.color());
        embed.setTitle(line.getEmoji() + " Pickup Line Deployed! " + line.getEmoji());

        embed.setDescription("**" + author.getEffectiveName() + "** is shooting their shot at **"
                + target.getAsMention() + "**\n\n" + "*\""
                + line.line() + "\"*\n\n" + "💭 *Style: "
                + line.styleName() + "* ✨");
        embed.setFooter("Will they take the bait? 👀", null);

        embed.setThumbnail(getGifForStyle(line.style()));
        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private String pickRandomStyle() {
        String[] styles = {"smooth", "cheesy", "spicy"};
        return styles[RNG.nextInt(styles.length)];
    }

    private String getStyleName(String style) {
        return switch (style) {
            case "smooth" -> "🍯 Smooth";
            case "cheesy" -> "🧀 Cheesy";
            case "spicy" -> "🔥 Spicy";
            default -> "💘 Random";
        };
    }

    private Color getStyleColor(String style) {
        return switch (style) {
            case "smooth" -> new Color(255, 215, 0); // Gold
            case "cheesy" -> new Color(255, 165, 0); // Orange
            case "spicy" -> new Color(220, 20, 60); // Crimson
            default -> new Color(255, 105, 180); // Hot pink
        };
    }

    private PickupLine getHardcodedLine(String style) {
        return switch (style) {
            case "smooth" -> getSmoothLine();
            case "cheesy" -> getCheesyLine();
            case "spicy" -> getSpicyLine();
            default -> getSmoothLine();
        };
    }

    private PickupLine getSmoothLine() {
        String[] lines = {
            "Are you a magician? Because whenever I look at you, everyone else disappears.",
            "I was blinded by your beauty... I'm going to need your name and number for insurance purposes.",
            "Do you have a map? I keep getting lost in your eyes.",
            "Is your name Google? Because you've got everything I've been searching for.",
            "Are you a parking ticket? Because you've got FINE written all over you.",
            "Do you believe in love at first sight, or should I walk by again?",
            "If you were a vegetable, you'd be a cute-cumber.",
            "Are you WiFi? Because I'm feeling a connection.",
            "Is your dad a boxer? Because you're a knockout!",
            "Do you have a Band-Aid? Because I scraped my knee falling for you.",
            "If beauty were time, you'd be an eternity.",
            "Are you a camera? Because every time I look at you, I smile.",
            "Is it hot in here or is it just you?",
            "Do you have a name, or can I call you mine?",
            "Are you a bank loan? Because you have my interest."
        };
        return new PickupLine(lines[RNG.nextInt(lines.length)], "smooth", "🍯 Smooth", new Color(255, 215, 0));
    }

    private PickupLine getCheesyLine() {
        String[] lines = {
            "Are you a beaver? Because daaaaam.",
            "Do you like raisins? How do you feel about a date?",
            "Are you French? Because Eiffel for you.",
            "If you were a fruit, you'd be a fine-apple.",
            "Are you a time traveler? Because I see you in my future.",
            "Is your name Chapstick? Because you're da balm!",
            "Do you have a sunburn, or are you always this hot?",
            "Are you a cat? Because you're purr-fect.",
            "If you were words on a page, you'd be fine print.",
            "Are you a banana? Because I find you a-peeling.",
            "Do you play soccer? Because you're a keeper.",
            "Are you a campfire? Because you're hot and I want s'more.",
            "Is your name WiFi? Because I'm really feeling a connection.",
            "Are you a snowstorm? Because you're making my heart race.",
            "Do you have an eraser? Because I can't get you out of my mind."
        };
        return new PickupLine(lines[RNG.nextInt(lines.length)], "cheesy", "🧀 Cheesy", new Color(255, 165, 0));
    }

    private PickupLine getSpicyLine() {
        String[] lines = {
            "I'm not a photographer, but I can definitely picture us together.",
            "Are you a haunted house? Because I'm going to scream when I'm inside you.",
            "Is that a mirror in your pocket? Because I can see myself in your pants.",
            "Do you have a switch? Because you just turned me on.",
            "Are you a drill sergeant? Because you have my privates standing at attention.",
            "I'm no weatherman, but you can expect a few inches tonight.",
            "Are you a sea lion? Because I can sea you lion in my bed tonight.",
            "Do you have a shovel? Because I'm digging that ass.",
            "Are you a tower? Because Eiffel for you... and I want to climb you.",
            "Is your name winter? Because you'll be coming soon.",
            "Are you a light switch? Because you turn me on.",
            "Do you believe in karma? Because I know some good karma-sutra positions.",
            "Are you a thief? Because you just stole my heart... and my breath.",
            "Is your dad an art thief? Because you're a masterpiece.",
            "Do you have a jersey? Because I need your name and number."
        };
        return new PickupLine(lines[RNG.nextInt(lines.length)], "spicy", "🔥 Spicy", new Color(220, 20, 60));
    }

    private String getGifForStyle(String style) {
        return switch (style) {
            case "smooth" -> "https://media.giphy.com/media/l378eCHnLEeMyyeYM/giphy.gif";
            case "cheesy" -> "https://media.giphy.com/media/3o7TKTDn976rzVgky4/giphy.gif";
            case "spicy" -> "https://media.giphy.com/media/l0HlNQ03J5JxX6lva/giphy.gif";
            default -> "https://media.giphy.com/media/l0HlNQ03J5JxX6lva/giphy.gif";
        };
    }

    private record PickupLine(String line, String style, String styleName, Color color) {
        String getEmoji() {
            return switch (style) {
                case "smooth" -> "🍯";
                case "cheesy" -> "🧀";
                case "spicy" -> "🔥";
                default -> "💘";
            };
        }
    }
}
