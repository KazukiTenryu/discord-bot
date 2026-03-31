package bot;

import java.util.Optional;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.config.Config;
import bot.database.Database;
import bot.slash.SlashCommand;
import bot.slash.SlashCommandRepository;

public class GlobalEventListener extends ListenerAdapter {
    private static final Logger LOGGER = LogManager.getLogger(GlobalEventListener.class);
    private final Config config;
    private final Database database;
    private final SlashCommandRepository slashCommandRepository;

    public GlobalEventListener(Config config, Database database, SlashCommandRepository slashCommandRepository) {
        this.config = config;
        this.database = database;
        this.slashCommandRepository = slashCommandRepository;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String name = event.getName();

        LOGGER.info(
                "/{} used by {} (id: {})",
                name,
                event.getUser().getName(),
                event.getUser().getId());

        try {
            Optional<SlashCommand> optionalSlashCommand = slashCommandRepository.getCommands().stream()
                    .filter(cmd -> cmd.getName().equals(name))
                    .findFirst();
            optionalSlashCommand.ifPresent(slashCommand -> slashCommand.handle(event));
        } catch (Exception e) {
            LOGGER.error("Failed to handle slash command /{}", name, e);
        }
    }
}
