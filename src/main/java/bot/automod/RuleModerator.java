package bot.automod;

import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.utils.KimiService;
import bot.utils.KimiService.ChatOutcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Context-aware, custom-rule moderation backed by a chat LLM (Kimi).
 *
 * <p>This complements {@link ModerationService}: the moderation endpoint catches raw
 * unsafe content (sexual, hate, violence) on a single message, while this judge reads the full
 * channel history to catch the social/contextual rules a category classifier cannot understand
 * (drama, off-topic, spam, "being a menace", loophole lawyering).
 *
 * <p>Because those social rules rarely involve raw-unsafe content, they generally do not trip
 * Moonshot's risk-control filter. When they do, {@link ChatOutcome#rejectedHighRisk()} lets the
 * caller treat the rejection itself as a signal and lean on the moderation gate instead.
 */
public class RuleModerator {
    private static final Logger LOGGER = LogManager.getLogger(RuleModerator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are a discord moderator and exist to keep a community safe from rule breakers.
            If the provided message is breaking the server rules, you must flag it.

            You will be provided with the past 20 messages to understand context of the message you are evaluating.
            Judge ONLY the final "Message to review" — the history is context, not the target. A message may only
            be a violation because of what came before it (e.g. targeted harassment, reigniting drama, off-topic
            derailing); use the history to make that call.

            You must provide the following details:

            * severity (0-10)
            * which rule was broken
            * why that message was flagged

            Remember, kids on discord are "edgy", weird and being sexual, toxic, etc., is normal. Primarily focus on extreme
            cases of when the rules are being violated. If nothing is wrong, return severity 0 and rule_broken "none".

            The servers rules are:

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

            Respond with only JSON using this format strictly:

            {
             "severity": 0,
             "rule_broken": "7. Stay on topic-ish",
             "reason": "The user is proactively being annoying on purpose"
            }
            """;

    private final KimiService kimiService;

    public RuleModerator(KimiService kimiService) {
        this.kimiService = kimiService;
    }

    /**
     * Judge the latest message against the custom server rules, using the channel history as context.
     *
     * @param historyText the formatted recent channel history (excluding the message under review)
     * @param messageLine the formatted line for the message being reviewed
     * @return the rule judgment, or empty if the LLM could not be reached or its reply was unusable
     */
    public Optional<RuleJudgment> judge(String historyText, String messageLine) {
        String prompt = """
                Channel history:

                %s

                Message to review based on the above channel history:

                %s
                """.formatted(historyText, messageLine);

        List<KimiService.Message> messages = KimiService.buildMessages(SYSTEM_PROMPT, prompt);
        ChatOutcome outcome = kimiService.chatDetailed(messages);

        if (outcome.rejectedHighRisk()) {
            // The chat model's own safety filter refused — the moderation gate already covers this.
            LOGGER.info("Rule judge rejected message as high risk; deferring to moderation gate");
            return Optional.of(RuleJudgment.highRiskRejection());
        }

        if (outcome.content().isEmpty()) {
            return Optional.empty();
        }

        return parseJudgment(outcome.content().get());
    }

    private Optional<RuleJudgment> parseJudgment(String content) {
        String json = stripCodeFences(content);
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            int severity = clampSeverity(root.path("severity").asInt(0));
            String ruleBroken = root.path("rule_broken").asText("none");
            String reason = root.path("reason").asText("");
            return Optional.of(new RuleJudgment(severity, ruleBroken, reason, false));
        } catch (Exception e) {
            LOGGER.warn("Could not parse rule judgment as JSON: {}", content, e);
            return Optional.empty();
        }
    }

    private static int clampSeverity(int severity) {
        return Math.max(0, Math.min(10, severity));
    }

    private static String stripCodeFences(String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.strip();
    }

    /**
     * A custom-rule judgment from the LLM.
     *
     * @param severity 0-10 severity of the violation (0 = no violation)
     * @param ruleBroken the rule that was broken, or "none"
     * @param reason a short explanation
     * @param rejectedHighRisk true when the LLM refused on safety grounds rather than judging
     */
    public record RuleJudgment(int severity, String ruleBroken, String reason, boolean rejectedHighRisk) {
        static RuleJudgment highRiskRejection() {
            return new RuleJudgment(0, "none", "LLM refused on safety grounds", true);
        }
    }
}
