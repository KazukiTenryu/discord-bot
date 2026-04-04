package bot.slash;

import java.util.ArrayList;
import java.util.List;

import bot.config.Config;
import bot.database.Database;
import bot.slash.gif.GifCommand;
import bot.slash.kissorslap.KissOrSlapCommand;
import bot.slash.moderation.*;
import bot.slash.pet.*;
import bot.slash.ping.PingCommand;
import bot.slash.rate.RateCommand;
import bot.slash.rizz.RizzCommand;
import bot.slash.rolemenu.RoleSelectCommand;
import bot.slash.ship.ShipCommand;
import bot.slash.time.TimeCommand;
import bot.slash.truthordare.TruthOrDareCommand;
import bot.slash.wouldyourather.WouldYouRatherCommand;

public class SlashCommandRepository {
    private final List<SlashCommand> commands;

    public SlashCommandRepository(Config config, Database database) {
        this.commands = new ArrayList<>();
        registerCommands(config, database);
    }

    private void registerCommands(Config config, Database database) {
        commands.add(new PingCommand());

        commands.add(new RateCommand());
        commands.add(new RizzCommand(config));
        commands.add(new GifCommand(config));
        commands.add(new TruthOrDareCommand(config));
        commands.add(new RulesCommand());

        commands.add(new AuditCommand(database));
        commands.add(new ShipCommand(config));
        commands.add(new RoleSelectCommand());
        commands.add(new TimeCommand());
        commands.add(new WouldYouRatherCommand(config));
        commands.add(new KissOrSlapCommand());

        AuditService auditService = new AuditService(database);
        commands.add(new NoteCommand(auditService));
        commands.add(new KickCommand(auditService));
        commands.add(new MuteCommand(config, auditService));
        commands.add(new UnmuteCommand(config, auditService));

        commands.addAll(ActionCommand.registerActionCommands(new HandleCommandAction(config)));
    }

    public List<SlashCommand> getCommands() {
        return commands;
    }
}
