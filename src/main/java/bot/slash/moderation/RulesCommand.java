package bot.slash.moderation;

import java.awt.*;
import java.util.List;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import bot.config.Config;
import bot.slash.SlashCommand;

public class RulesCommand extends SlashCommand {

    // Default rules for this server (Unexpected!). Each deployment can override them via the
    // "rules" array in config.json — one entry per rule block — without touching code, so other
    // servers keep their own distinct rules.
    private static final String DEFAULT_RULES =
            """
            1. Don't be a menance
               Be chill. If you're being annoying on purpose, we will notice... and we will judge.

            2. No drama llamas
               Take arguments to DMs. This isn't a reality TV.

            3. Respect the mods
               They don't get paid, they suffer for free. Be nice.

            4. No spamming
               If your message looks like a keyboard had a seizure, it's gone.

            5. Keep it (mostly) PG-13
               Don't get weird. You know what "weird" means.

            6. No loophole lawyering.
               If you try to loophole the rules, I'll loophole your ass out of the chat.

            7. Stay on topic-ish
               Tangents are fine. Summoning chaos demons is not.

            8. English only (unless you're flirting)
               Speak English so everyone understands. Secret languages will be treated as wizard activity.

            9. Use common sense

            10. Have fun or else
                This is a threat. Enjoy yourself immediately.
            """;

    private final String rules;

    public RulesCommand(Config config) {
        super("rules", "View the server rules");
        List<String> configured = config.rules();
        this.rules = (configured == null || configured.isEmpty()) ? DEFAULT_RULES : String.join("\n\n", configured);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        EmbedBuilder builder = new EmbedBuilder();
        builder.setColor(Color.CYAN);
        builder.setTitle("Rules");
        builder.setDescription(rules);

        event.replyEmbeds(builder.build()).queue();
    }
}
