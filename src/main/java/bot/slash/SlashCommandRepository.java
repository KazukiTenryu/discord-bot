package bot.slash;

import java.util.ArrayList;
import java.util.List;

import bot.config.Config;
import bot.slash.moderation.MuteCommand;
import bot.slash.ping.PingCommand;

public class SlashCommandRepository {
    private final List<SlashCommand> commands;

    public SlashCommandRepository(Config config) {
        this.commands = new ArrayList<>();
        registerCommands(config);
    }

    private void registerCommands(Config config) {
        commands.add(new PingCommand());
        commands.add(new MuteCommand(config));
    }

    public List<SlashCommand> getCommands() {
        return commands;
    }
}
