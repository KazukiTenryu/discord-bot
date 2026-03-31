package bot;

import java.util.EnumSet;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

import bot.config.Config;
import bot.config.ConfigLoader;
import bot.database.Database;
import bot.slash.SlashCommandRepository;

public class Main {
    void main() {
        System.out.println("Starting bot...");

        try {
            Config config = ConfigLoader.loadConfig();
            System.setProperty("infoLogsChannelWebHookURL", config.infoLogsChannelWebHookURL());
            System.setProperty("errorLogsChannelWebHookURL", config.errorLogsChannelWebHookURL());

            Database database = new Database("jdbc:sqlite:" + config.dbFile());

            SlashCommandRepository slashCommandRepository = new SlashCommandRepository(config, database);

            JDA jda = JDABuilder.createLight(config.botToken(), EnumSet.allOf(GatewayIntent.class))
                    .addEventListeners(new GlobalEventListener(config, database, slashCommandRepository))
                    .build();

            CommandListUpdateAction commands = jda.updateCommands();

            slashCommandRepository.getCommands().forEach(slashCommand -> commands.addCommands(slashCommand.getData()));

            commands.queue();

            System.out.println("Bot has started successfully");

        } catch (Exception e) {
            System.err.println("Failed to start application: " + e.getMessage());
        }
    }
}
