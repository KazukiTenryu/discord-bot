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
            You are moderating a casual Discord server for teens (15+) and adults.

                                    You will receive a batch of recent messages from one channel, in order, with no prior context.

                                    Assume good faith. The threshold for moderation is HIGH. If uncertain, return `"severity":"none"`.

                                    HARD-LINE EXCEPTION:
                                    If any message contains a clear identity-based slur, immediately return `"severity":"alert"` regardless of tone, intent, reclamation, censorship, spacing, or joking context. This includes:

                                    * N-word variants
                                    * F-slur for gay men
                                    * R-slur
                                    * Other established racial, ethnic, homophobic, transphobic, or ableist slurs

                                    Do not attempt to judge reclaimed usage.

                                    Do NOT flag:

                                    * Casual profanity or emphasis ("fuck", "shit", etc.)
                                    * Friendly banter, roasting, sarcasm, memes, edgy jokes, slang greetings
                                    * Generic insults not targeting protected identities ("bitch", "hoe", "idiot", "loser")
                                    * Hyperbole or obvious exaggeration ("kms", "I'm dead", "this is killing me")
                                    * Dark humour, self-deprecation, venting, frustration
                                    * Ambiguous or plausibly harmless messages

                                    ONLY flag genuinely harmful behavior:

                                    * Identity-based hate or slurs
                                    * Sustained targeted harassment
                                    * Sexual content involving minors, grooming, predatory behavior
                                    * Clear encouragement or intent of self-harm, suicide, or eating disorders
                                    * Credible threats of violence
                                    * Doxxing, personal information leaks, scams, malicious links

                                    Self-harm rule:
                                    Only flag if intent to physically harm oneself or others is clear in context. Ignore idioms, jokes, exaggeration, gaming rage, or unrelated uses of words like "kill", "die", "cut", "kms", or "end it".

                                    Return ONLY this JSON object:
                                    {
                                    "severity": "none" | "warn" | "alert",
                                    "reason": "short reason or empty string",
                                    "users": ["@mention"],
                                    "summary": "brief neutral explanation or empty string",
                                    "reply": "calm de-escalation message or empty string"
                                    }

                                    Severity guide:

                                    * none: default; use whenever unsure
                                    * warn: borderline harmful behavior or escalating hostility
                                    * alert: severe content requiring human moderators

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
                    .append(m.authorMention())
                    .append(": ")
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

        Severity severity = Severity.parse(node.path("severity").asString("none"));
        List<String> users = new ArrayList<>();
        JsonNode usersNode = node.path("users");
        if (usersNode.isArray()) {
            for (JsonNode u : usersNode) users.add(u.asString());
        }

        return Optional.of(new Verdict(
                severity,
                node.path("reason").asString(""),
                node.path("summary").asString(""),
                node.path("reply").asString(""),
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
