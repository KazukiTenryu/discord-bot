package bot;

import java.util.Optional;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.config.Config;
import bot.slash.SlashCommand;
import bot.slash.SlashCommandRepository;

public class GlobalEventListener extends ListenerAdapter {
    private static final Logger LOGGER = LogManager.getLogger(GlobalEventListener.class);
    private final Config config;
    private final SlashCommandRepository slashCommandRepository;

    public GlobalEventListener(Config config, SlashCommandRepository slashCommandRepository) {
        this.config = config;
        this.slashCommandRepository = slashCommandRepository;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String name = event.getName();
        Optional<SlashCommand> optionalSlashCommand = slashCommandRepository.getCommands().stream()
                .filter(cmd -> cmd.getName().equals(name))
                .findFirst();
        optionalSlashCommand.ifPresent(slashCommand -> slashCommand.handle(event));
    }
}
