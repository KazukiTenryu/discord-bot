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
    private static final ThreadLocalRandom RNG = ThreadLocalRandom.current();

    private final KimiService kimiService;

    public TruthOrDareCommand(Config config) {
        super("truth-or-dare", "Spicy truth or dare with your server members 🔥");
        this.kimiService = new KimiService(config.kimiApiKey());

        getData()
                .addOptions(new OptionData(OptionType.STRING, MODE_OPTION, "What do you choose?", false)
                        .addChoice("🎯 Truth", "truth")
                        .addChoice("😈 Dare", "dare")
                        .addChoice("🎲 Random", "random"));
        getData().addOptions(new OptionData(OptionType.USER, TARGET_OPTION, "Who to challenge", false));
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

        String content;
        boolean isAiGenerated = false;

        if (kimiService.isConfigured()) {
            Optional<String> aiContent = generateWithKimi(actualMode, author, target);
            if (aiContent.isPresent()) {
                content = aiContent.get();
                isAiGenerated = true;
            } else {
                content = getFallbackContent(actualMode);
            }
        } else {
            content = getFallbackContent(actualMode);
        }

        EmbedBuilder embed = buildEmbed(actualMode, author, target, content, isAiGenerated);
        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private Optional<String> generateWithKimi(String mode, Member author, Member target) {
        String systemPrompt = """
            You are a fun, flirty game master for a Discord server's Truth or Dare game.
            Keep things light, playful, and slightly spicy (NSFW-adjacent but not explicit).
            Questions and dares should be:
            - Flirty and suggestive but not pornographic
            - Funny and embarrassing but not cruel
            - Something that could actually be answered or done in Discord/voice chat
            - Keep responses under 200 characters
            - Don't use emojis in responses
            """;

        String userPrompt =
                switch (mode) {
                    case "truth" ->
                        String.format(
                                "Generate a spicy TRUTH question from %s to ask %s. "
                                        + "Make it flirty, slightly embarrassing, but fun. "
                                        + "Example tone: 'What's the most scandalous thing you've done in a voice call?' "
                                        + "Truth question:",
                                author.getEffectiveName(), target.getAsMention());
                    case "dare" ->
                        String.format(
                                "Generate a spicy DARE from %s to make %s do. "
                                        + "Make it flirty, slightly risky, but doable in Discord. "
                                        + "Example tone: 'Send your last Google search in this chat' or 'Change your nickname to something embarrassing for 10 minutes' "
                                        + "Dare:",
                                author.getEffectiveName(), target.getAsMention());
                    default -> "";
                };

        if (userPrompt.isEmpty()) {
            return Optional.empty();
        }

        List<KimiService.Message> messages = KimiService.buildMessages(systemPrompt, userPrompt);
        return kimiService.chat(messages);
    }

    private String getFallbackContent(String mode) {
        return switch (mode) {
            case "truth" -> getFallbackTruth();
            case "dare" -> getFallbackDare();
            default -> getFallbackTruth();
        };
    }

    private String getFallbackTruth() {
        String[] truths = {
            "What's your most embarrassing search history item?",
            "Who in this server would you want to cuddle with?",
            "What's the last thing you did that made you blush?",
            "Have you ever had a crush on someone in this server?",
            "What's your guilty pleasure that no one knows about?",
            "What's the most scandalous DM you've ever received?",
            "If you had to kiss someone in this server, who would it be?",
            "What's your biggest turn-on that you're embarrassed to admit?",
            "Have you ever sent a message to the wrong person? What was it?",
            "What's the wildest thing you've done while alone?",
            "Who here do you think has the best rizz?",
            "What's your most shameful parasocial relationship?",
            "Have you ever lied about your age online?",
            "What's something you'd do if you were invisible for a day?",
            "What's your most recent regret?"
        };
        return truths[RNG.nextInt(truths.length)];
    }

    private String getFallbackDare() {
        String[] dares = {
            "Change your status to 'horny and available' for 10 minutes",
            "Send your most recent photo in DMs to the person who asked",
            "Type your next 5 messages in uwu speak",
            "Send a voice message saying 'I'm a bad kitty'",
            "Change your nickname to 'Daddy's Little [Name]' for the next hour",
            "Send your last 3 Google searches in this chat",
            "Flirt with the next person who messages in this channel",
            "React with 🔥 to the last 10 messages in general chat",
            "Send a selfie doing your best 'come hither' face",
            "Type everything backwards for the next 5 minutes",
            "Send the most embarrassing photo on your phone",
            "Change your pfp to something silly for 30 minutes",
            "Confess your love to the 3rd person in the member list",
            "Send a dramatic reading of your last DM out loud in voice chat",
            "React with 💦 to every message for the next 2 minutes"
        };
        return dares[RNG.nextInt(dares.length)];
    }

    private EmbedBuilder buildEmbed(String mode, Member author, Member target, String content, boolean isAiGenerated) {
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
