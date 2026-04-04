package bot.slash;

import java.util.ArrayList;
import java.util.List;

import bot.config.Config;
import bot.database.Database;
import bot.slash.gif.GifCommand;
import bot.slash.moderation.*;
import bot.slash.pet.*;
import bot.slash.ping.PingCommand;
import bot.slash.rate.RateCommand;
import bot.slash.rizz.RizzCommand;
import bot.slash.rolemenu.RoleSelectCommand;
import bot.slash.ship.ShipCommand;
import bot.slash.truthordare.TruthOrDareCommand;

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
        commands.add(new RizzCommand(config));
        commands.add(new GifCommand(config));
        commands.add(new TruthOrDareCommand(config));
        commands.add(new RulesCommand());
        commands.add(new NoteCommand(database));
        commands.add(new AuditCommand(database));
        commands.add(new ShipCommand(config));
        commands.add(new RoleSelectCommand());

        commands.addAll(ActionCommand.registerActionCommands(new HandleCommandAction(config)));
    }

    public List<SlashCommand> getCommands() {
        return commands;
    }
}
