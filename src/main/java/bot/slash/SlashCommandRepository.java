package bot.slash;

import java.util.ArrayList;
import java.util.List;

import bot.config.Config;
import bot.database.Database;
import bot.slash.moderation.*;
import bot.slash.pet.*;
import bot.slash.ping.PingCommand;
import bot.slash.rate.RateCommand;

public class SlashCommandRepository {
    private final List<SlashCommand> commands;

    public SlashCommandRepository(Config config, Database database) {
        this.commands = new ArrayList<>();
        registerCommands(config, database);
    }

    private void registerCommands(Config config, Database database) {
        commands.add(new PingCommand());
        commands.add(new MuteCommand(config));
        commands.add(new UnmuteCommand(config));
        commands.add(new KickCommand());
        commands.add(new RateCommand());
        commands.add(new RulesCommand());
        commands.add(new NoteCommand(database));
        commands.add(new AuditCommand(database));

        HandleCommandAction handleCommandAction = new HandleCommandAction(config);
        commands.add(new PetCommand(handleCommandAction));
        commands.add(new BonkCommand(handleCommandAction));
        commands.add(new HugCommand(handleCommandAction));
        commands.add(new WorshipCommand(handleCommandAction));
    }

    public List<SlashCommand> getCommands() {
        return commands;
    }
}
