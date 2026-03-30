package bot;

import java.util.EnumSet;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.config.Config;
import bot.config.ConfigLoader;

public class Main {
    private static final Logger LOGGER = LogManager.getLogger(Main.class);

    void main() {
        try {
            Config config = ConfigLoader.loadConfig();

            JDABuilder.createLight(config.botToken(), EnumSet.allOf(GatewayIntent.class))
                    .addEventListeners(new GlobalEventListener(config))
                    .build();
        } catch (Exception e) {
            LOGGER.error("Failed to start application", e);
        }
    }
}
