package bot;

import java.util.EnumSet;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;

import bot.config.Config;
import bot.config.ConfigLoader;
import bot.database.Database;
import bot.listeners.MessageReceivedListener;
import bot.metrics.MetricService;
import bot.slash.SlashCommandRepository;

public class Main {
    private static MetricService metricService;

    void main() {
        System.out.println("Starting bot...");

        try {
            Config config = ConfigLoader.loadConfig();
            System.setProperty("infoLogsChannelWebHookURL", config.infoLogsChannelWebHookURL());
            System.setProperty("errorLogsChannelWebHookURL", config.errorLogsChannelWebHookURL());

            Database database = new Database("jdbc:sqlite:" + config.dbFile());
            metricService = new MetricService(database);

            SlashCommandRepository slashCommandRepository = new SlashCommandRepository(config, database);

            JDA jda = JDABuilder.createLight(config.botToken(), EnumSet.allOf(GatewayIntent.class))
                    .addEventListeners(new GlobalEventListener(config, database, slashCommandRepository))
                    .addEventListeners(new MessageReceivedListener(config))
                    .addEventListeners(new ListenerAdapter() {
                        private static final Logger LOGGER = LogManager.getLogger("Main#ReadyListener");

                        @Override
                        public void onReady(@NonNull ReadyEvent event) {
                            LOGGER.info(
                                    "Bot is ready! Logged in as {}",
                                    event.getJDA().getSelfUser().getName());
                        }
                    })
                    .build();

            CommandListUpdateAction commands = jda.updateCommands();
            slashCommandRepository.getCommands().forEach(slashCommand -> commands.addCommands(slashCommand.getData()));
            commands.queue();
        } catch (Exception e) {
            System.err.println("Failed to start application: " + e.getMessage());
        }
    }

    public static MetricService getMetrics() {
        if (metricService != null) {
            return metricService;
        }
        throw new NullPointerException("Metrics class has not been initalised");
    }
}
