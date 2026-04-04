package bot.slash.wouldyourather;

import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.config.Config;
import bot.slash.SlashCommand;
import bot.utils.KimiService;

public class WouldYouRatherCommand extends SlashCommand {
    private static final String TARGET_OPTION = "user";
    private static final String THEME_OPTION = "theme";
    private final KimiService kimiService;

    public WouldYouRatherCommand(Config config) {
        super("would-you-rather", "Would you rather...?");

        this.kimiService = new KimiService(config.kimiApiKey());

        getData().addOptions(new OptionData(OptionType.USER, TARGET_OPTION, "Who to ask?", true));
        getData()
                .addOptions(
                        new OptionData(OptionType.STRING, THEME_OPTION, "Theme (e.g. funny, nsfw, chaotic)", false));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Member author = Objects.requireNonNull(event.getMember());

        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption(TARGET_OPTION)).getAsMember());

        String theme = event.getOption(THEME_OPTION) != null
                ? Objects.requireNonNull(event.getOption(THEME_OPTION)).getAsString()
                : "funny / embarassing";

        event.deferReply().queue();

        if (kimiService.isConfigured()) {
            EmbedBuilder embed = new EmbedBuilder();

            embed.setColor(new Color(186, 85, 211));
            embed.setTitle("🤔 WOULD YOU RATHER 🤔");
            embed.setDescription("**" + author.getAsMention() + "** asks " + "**"
                    + target.getAsMention() + "**" + "...\n\n"
                    + "*\""
                    + generateWithKimi(author, target, theme).orElse("Service unavailable") + "\"*\n\n"
                    + "⚖️ *Make your choice...*");

            embed.setFooter("No backing out now 😏", null);
            event.getHook().sendMessageEmbeds(embed.build()).queue();
        } else {
            event.getHook()
                    .sendMessage("The service is currently unavailable")
                    .setEphemeral(true)
                    .queue();
        }
    }

    private Optional<String> generateWithKimi(Member author, Member target, String theme) {
        String systemPrompt = """
            You are a fun, flirty game master for a Discord server's Would You Rather game.
            Keep things light, playful, and slightly spicy.

            Questions should:
                - Present two interesting or challenging choices
                - Be funny, awkward, or a little embarrassing but not cruel
                - Be something people can realistically answer in a Discord chat
                - Spark conversation or reactions
                - Keep responses under 200 characters
                - Don't use emojis in responses
                - Make it engaging, playful, and creative

            Adapt your tone and choices based on the given theme (e.g. funny, chaotic, wholesome, spicy).

            Format:
                "Would you rather [option A] or [option B]?"
            """;

        String userPrompt = String.format(
                "Theme: %s\nGenerate a Would You Rather question from %s to ask %s. Match the theme.",
                theme, author.getEffectiveName(), target.getAsMention());

        List<KimiService.Message> messages = KimiService.buildMessages(systemPrompt, userPrompt);
        return kimiService.chat(messages);
    }
}
