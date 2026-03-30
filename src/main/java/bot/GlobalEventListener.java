package bot;

import net.dv8tion.jda.api.hooks.ListenerAdapter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.config.Config;

public class GlobalEventListener extends ListenerAdapter {
    private static final Logger LOGGER = LogManager.getLogger(GlobalEventListener.class);
    private final Config config;

    public GlobalEventListener(Config config) {
        this.config = config;
    }
}
