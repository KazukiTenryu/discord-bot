package bot.slash;

import java.util.ArrayList;
import java.util.List;

import bot.config.Config;
import bot.slash.moderation.KickCommand;
import bot.slash.moderation.MuteCommand;
import bot.slash.moderation.RulesCommand;
import bot.slash.moderation.UnmuteCommand;
import bot.slash.ping.PingCommand;
import bot.slash.rate.RateCommand;

public class SlashCommandRepository {
    private final List<SlashCommand> commands;

    public SlashCommandRepository(Config config) {
        this.commands = new ArrayList<>();
        registerCommands(config);
    }

    private void registerCommands(Config config) {
        commands.add(new PingCommand());
        commands.add(new MuteCommand(config));
        commands.add(new UnmuteCommand(config));
        commands.add(new KickCommand());
        commands.add(new RateCommand());
        commands.add(new RulesCommand());
    }

    public List<SlashCommand> getCommands() {
        return commands;
    }
}
