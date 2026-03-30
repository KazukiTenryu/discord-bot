package bot;

import java.util.EnumSet;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.config.Config;
import bot.config.ConfigLoader;
import bot.slash.SlashCommandRepository;

public class Main {
    private static final Logger LOGGER = LogManager.getLogger(Main.class);

    void main() {
        try {
            Config config = ConfigLoader.loadConfig();

            SlashCommandRepository slashCommandRepository = new SlashCommandRepository(config);

            JDA jda = JDABuilder.createLight(config.botToken(), EnumSet.allOf(GatewayIntent.class))
                    .addEventListeners(new GlobalEventListener(config, slashCommandRepository))
                    .build();

            CommandListUpdateAction commands = jda.updateCommands();

            slashCommandRepository.getCommands().forEach(slashCommand -> commands.addCommands(slashCommand.getData()));

            commands.queue();

        } catch (Exception e) {
            LOGGER.error("Failed to start application", e);
        }
    }
}
