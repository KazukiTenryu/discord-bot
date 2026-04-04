package bot.slash.truthordare;

import java.awt.Color;
import java.util.List;
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
import bot.utils.KimiService;

public class TruthOrDareCommand extends SlashCommand {
    private static final Logger LOGGER = LogManager.getLogger(TruthOrDareCommand.class);
    private static final String MODE_OPTION = "mode";
    private static final String TARGET_OPTION = "target";
    private static final String THEME_OPTION = "theme";
    private static final ThreadLocalRandom RNG = ThreadLocalRandom.current();

    private final KimiService kimiService;

    public TruthOrDareCommand(Config config) {
        super("truth-or-dare", "Spicy truth or dare with your server members 🔥");
        this.kimiService = new KimiService(config.kimiApiKey());

        getData().addOptions(new OptionData(OptionType.USER, TARGET_OPTION, "Who to challenge", true));

        getData()
                .addOptions(new OptionData(OptionType.STRING, MODE_OPTION, "What do you choose?", false)
                        .addChoice("🎯 Truth", "truth")
                        .addChoice("😈 Dare", "dare")
                        .addChoice("🎲 Random", "random"));
        getData()
                .addOptions(
                        new OptionData(OptionType.STRING, THEME_OPTION, "Theme (e.g. funny, nsfw, chaotic)", false));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        Member author = Objects.requireNonNull(event.getMember());
        String mode = event.getOption(MODE_OPTION) != null
                ? Objects.requireNonNull(event.getOption(MODE_OPTION)).getAsString()
                : "random";

        String actualMode = "random".equals(mode) ? (RNG.nextBoolean() ? "truth" : "dare") : mode;

        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption(TARGET_OPTION)).getAsMember());

        String theme = event.getOption(THEME_OPTION) != null
                ? Objects.requireNonNull(event.getOption(THEME_OPTION)).getAsString()
                : "funny / embarassing";

        if (kimiService.isConfigured()) {
            String content = generateWithKimi(actualMode, author, target, theme)
                    .orElse("Couldn't generate a challenge. Try again.");
            EmbedBuilder embed = buildEmbed(actualMode, author, target, content, theme);
            event.getHook().sendMessageEmbeds(embed.build()).queue();
        } else {
            event.getHook()
                    .sendMessage("The service is currently unavailable")
                    .setEphemeral(true)
                    .queue();
        }
    }

    private Optional<String> generateWithKimi(String mode, Member author, Member target, String theme) {
        String systemPrompt = """
            You are a fun, flirty game master for a Discord server's Truth or Dare game.
            Keep things light, playful, and slightly spicy.
            Questions and dares should be:
            - Funny and embarrassing but not cruel
            - Something that could actually be answered or done in Discord text chat or voice chat
            - Keep responses under 200 characters
            - Don't use emojis in responses
            - Make it engaging, playful, and creative
            """;

        String userPrompt =
                switch (mode) {
                    case "truth" ->
                        String.format(
                                "Theme: %s\nGenerate a TRUTH question from %s to ask %s. Match the theme.",
                                theme, author.getEffectiveName(), target.getAsMention());
                    case "dare" ->
                        String.format(
                                "Theme: %s\nGenerate a DARE from %s for %s. Match the theme.",
                                theme, author.getEffectiveName(), target.getAsMention());
                    default -> "";
                };

        if (userPrompt.isEmpty()) {
            return Optional.empty();
        }

        List<KimiService.Message> messages = KimiService.buildMessages(systemPrompt, userPrompt);
        return kimiService.chat(messages);
    }

    private EmbedBuilder buildEmbed(String mode, Member author, Member target, String content, String theme) {
        EmbedBuilder embed = new EmbedBuilder();

        boolean isTruth = "truth".equals(mode);
        Color color = isTruth ? new Color(255, 105, 180) : new Color(220, 20, 60);
        String emoji = isTruth ? "🎯" : "😈";
        String title = isTruth ? "TRUTH" : "DARE";

        embed.setColor(color);
        embed.setTitle(emoji + " " + title + " " + emoji);

        embed.setDescription(
                "**" + author.getAsMention() + "** has challenged **" + target.getAsMention() + "**\n\n" + "*\""
                        + content + "\"*\n\n" + "💭 *Mode: "
                        + title + "* ✨");

        embed.setFooter("Will they accept the challenge? 👀", null);

        String thumbnailUrl = isTruth
                ? "https://media.giphy.com/media/3o7TKTDn976rzVgky4/giphy.gif"
                : "https://media.giphy.com/media/l0HlNQ03J5JxX6lva/giphy.gif";
        embed.setThumbnail(thumbnailUrl);

        return embed;
    }
}
