package bot.slash.rizz;

import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.utils.KimiService;

/**
 * Service for generating pickup lines using AI.
 * Uses KimiService for the underlying AI calls.
 */
public class PickupLineService {
    private static final Logger LOGGER = LogManager.getLogger(PickupLineService.class);

    private final KimiService kimiService;

    public PickupLineService(String apiKey) {
        this.kimiService = new KimiService(apiKey, "moonshot-v1-8k", 0.9, 100);
    }

    /**
     * Generate a pickup line using Kimi AI.
     *
     * @param style "smooth", "cheesy", or "spicy"
     * @param authorName name of the person shooting the shot
     * @param targetName name of the target
     * @return Optional containing the generated line, or empty if API fails
     */
    public Optional<String> generatePickupLine(String style, String authorName, String targetName) {
        if (!kimiService.isConfigured()) {
            LOGGER.warn("Kimi API key not configured, skipping AI generation");
            return Optional.empty();
        }

        String systemPrompt = "You are a creative, flirty assistant that generates pickup lines. "
                + "Keep responses under 150 characters. Be fun and playful. "
                + "Don't use emojis. Return ONLY the pickup line, nothing else.";

        String userPrompt = buildPrompt(style, authorName, targetName);

        List<KimiService.Message> messages = KimiService.buildMessages(systemPrompt, userPrompt);

        return kimiService.chat(messages);
    }

    private String buildPrompt(String style, String authorName, String targetName) {
        String styleDescription =
                switch (style) {
                    case "smooth" -> "smooth and charming - think confident, clever wordplay that's flirty but classy";
                    case "cheesy" -> "cheesy and corny - funny, cute puns that make people laugh and groan";
                    case "spicy" ->
                        "spicy and suggestive - a bit naughty and provocative, but still funny and not too explicit";
                    default -> "funny and flirty";
                };

        return String.format("""
            Generate a creative, %s pickup line.

            Context: %s is trying to flirt with %s.

            Pickup line:""", styleDescription, authorName, targetName);
    }
}
