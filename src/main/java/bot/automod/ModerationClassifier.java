package bot.automod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.utils.KimiService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends a batch of messages to Kimi and parses the structured verdict.
 */
public final class ModerationClassifier {
    private static final Logger LOGGER = LogManager.getLogger(ModerationClassifier.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String MODEL = "moonshot-v1-8k";
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 300;

    private static final KimiService.Message SYSTEM_PROMPT = KimiService.Message.system("""
            You are a content moderator for a Discord server whose audience is teenagers (15+) and adults.
            You will be given a batch of recent chat messages from a single channel, in order, with no
            prior context. Default to "none" unless something is clearly wrong.

            This is a casual server. Assume good faith. The bar for action is HIGH — when in
            doubt, do nothing. It is far worse to nag people about harmless banter than to miss
            something borderline.

            HARD-LINE EXCEPTION (always overrides everything below): the following are ALWAYS an
            "alert", regardless of tone, surrounding friendliness, apparent affection, claimed
            reclamation, or creative spelling/spacing/punctuation used to disguise them. If any
            message in the batch contains one of these — even casually, even between friends, even
            followed by emoji or "lol" or "ily" — emit severity="alert" and stop deliberating.
              - The N-word in any form or spelling (nigger, nigga, n-word, n*gga, n!gga, etc.)
              - The F-slur for gay men (faggot, fag, f-slur and disguised variants)
              - The R-slur (retard, retarded, 'tard, used as an insult)
              - Other established racial, ethnic, homophobic, transphobic, or ableist slurs
            The bot does not adjudicate reclaimed usage; let a human decide.

            Do NOT flag any of the following (this is not exhaustive — extend the spirit of the list):
              - Mild profanity used casually or for emphasis (shit, damn, hell, crap, ass, piss, bloody, fuck, fucking, etc.)
              - Crude or edgy slang used as casual greeting/banter ("sup hoes", "what's up bitches",
                "you idiots", "you losers", "lads"). These are friendly idioms, NOT slurs, when used
                generally rather than aimed at an identifiable target.
              - Casual banter, roasting, sarcasm, teasing between friends, internet slang, memes, in-jokes
              - Apologies, off-topic chatter, jokes, food/coffee/game/music/show talk
              - Mild venting or frustration ("ugh", "this sucks", "I hate Mondays", "kms" used hyperbolically)
              - Dark humour, edgy jokes, self-deprecation that is clearly not a real cry for help
              - Single ambiguous messages that could plausibly be innocent
              - Anything that just feels "a bit much" but is not on the harmful list below

            ONLY flag when something is GENUINELY harmful. A real slur is one that targets a protected
            identity (race, ethnicity, sexuality, gender identity, religion, disability) — words like
            "hoe" or "bitch" used as general slang do not qualify.
              - Identity-based slurs or hateful language targeting a group
              - Sustained, targeted harassment aimed at a specific user (one barb is not harassment)
              - Sexual content, sexualisation of minors, predatory behaviour, grooming
              - Encouragement of self-harm, suicide, or eating disorders. Must be a clear, literal
                statement of intent or encouragement — NOT idioms or hyperbole. Words like "cut",
                "kill", "die", "kms", "end it" appear constantly in harmless contexts and are not
                self-harm on their own. Examples that are NOT self-harm:
                  * "should I cut myself bangs" / "I cut myself shaving" (hair, accident)
                  * "this is killing me", "I could die", "I'm dead" (frustration/exaggeration)
                  * "kms", "I want to die" used hyperbolically about minor inconveniences
                  * "starving", "I haven't eaten all day" (casual, not an ED)
                Only flag if intent to harm one's body is unambiguous in context.
              - Credible threats of violence
              - Doxxing, sharing personal information, scam links targeting users

            Respond with ONLY a single JSON object, no prose, no code fences. Schema:
            {
              "severity": "none" | "warn" | "alert",
              "reason": "short reason, empty string if severity is none",
              "users": ["@mention", ...],
              "summary": "1-3 sentence neutral summary of what is happening and why it is concerning, empty if none",
              "reply": "a calm, brief message to post in the channel to de-escalate, empty if severity is none"
            }

            severity meanings:
              - none:  nothing to do — this is the default; use it whenever you are unsure.
                       The overwhelming majority of batches should be "none".
              - warn:  reserve for borderline cases on the harmful list that don't yet need a human
                       (e.g. one user being noticeably rude to another, escalation starting). If you
                       are not sure whether to pick "warn" or "none", pick "none".
              - alert: human moderators must be paged. Reserve strictly for the harmful list above.
            """);

    private final KimiService kimiService;

    public ModerationClassifier(KimiService kimiService) {
        this.kimiService = kimiService;
    }

    public Optional<Verdict> classify(long channelId, List<BufferedMessage> batch) {
        List<KimiService.Message> prompt = List.of(SYSTEM_PROMPT, KimiService.Message.user(buildTranscript(batch)));

        long start = System.currentTimeMillis();
        LOGGER.info("classifying {} messages from channel {}", batch.size(), channelId);

        Optional<String> response = kimiService.chat(prompt, MODEL, TEMPERATURE, MAX_TOKENS);
        long elapsedMs = System.currentTimeMillis() - start;

        if (response.isEmpty()) {
            LOGGER.warn("Kimi returned no response for channel {} after {} ms", channelId, elapsedMs);
            return Optional.empty();
        }

        LOGGER.info("Kimi responded for channel {} in {} ms", channelId, elapsedMs);
        LOGGER.debug("Kimi raw response: {}", response.get());
        return parseVerdict(response.get());
    }

    private static String buildTranscript(List<BufferedMessage> batch) {
        StringBuilder transcript = new StringBuilder();
        for (BufferedMessage m : batch) {
            transcript
                    .append(m.authorName())
                    .append(" (")
                    .append(m.authorMention())
                    .append("): ")
                    .append(m.content())
                    .append('\n');
        }
        return transcript.toString();
    }

    private static Optional<Verdict> parseVerdict(String response) {
        JsonNode node = extractJsonObject(response);
        if (node == null) {
            LOGGER.warn("Could not parse Kimi response as JSON: {}", response);
            return Optional.empty();
        }

        Severity severity = Severity.parse(node.path("severity").asText("none"));
        List<String> users = new ArrayList<>();
        JsonNode usersNode = node.path("users");
        if (usersNode.isArray()) {
            for (JsonNode u : usersNode) users.add(u.asText());
        }

        return Optional.of(new Verdict(
                severity,
                node.path("reason").asText(""),
                node.path("summary").asText(""),
                node.path("reply").asText(""),
                users));
    }

    private static JsonNode extractJsonObject(String response) {
        String trimmed = stripCodeFences(response.trim());
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return OBJECT_MAPPER.readTree(trimmed.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripCodeFences(String s) {
        if (!s.startsWith("```")) return s;
        int firstNewline = s.indexOf('\n');
        int closingFence = s.lastIndexOf("```");
        if (firstNewline <= 0 || closingFence <= firstNewline) return s;
        return s.substring(firstNewline + 1, closingFence).trim();
    }
}
