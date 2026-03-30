package bot.slash.ping;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.slash.SlashCommand;

public class PingCommand extends SlashCommand {
    private static final Logger LOGGER = LogManager.getLogger(PingCommand.class);

    public PingCommand() {
        super("ping", "Pong!");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.reply("Pong!").queue();
        LOGGER.info("Test");
    }
}
