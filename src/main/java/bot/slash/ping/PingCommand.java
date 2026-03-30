package bot.slash.ping;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import bot.slash.SlashCommand;

public class PingCommand extends SlashCommand {

    public PingCommand() {
        super("ping", "Pong!");
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.reply("Pong!").queue();
    }
}
