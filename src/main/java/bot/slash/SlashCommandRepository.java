package bot.slash;

import java.util.ArrayList;
import java.util.List;

import bot.config.Config;
import bot.database.Database;
import bot.slash.echo.EchoCommand;
import bot.slash.echo.EchoFromCommand;
import bot.slash.family.AdoptCommand;
import bot.slash.family.ClaimCommand;
import bot.slash.family.DivorceCommand;
import bot.slash.family.EmancipateCommand;
import bot.slash.family.FamilyTreeCommand;
import bot.slash.family.MarryCommand;
import bot.slash.family.RelationshipService;
import bot.slash.family.ReleaseCommand;
import bot.slash.gif.GifCommand;
import bot.slash.kissorslap.KissOrSlapCommand;
import bot.slash.moderation.*;
import bot.slash.music.LyricsCommand;
import bot.slash.music.NowPlayingCommand;
import bot.slash.music.PauseCommand;
import bot.slash.music.PlayCommand;
import bot.slash.music.QueueCommand;
import bot.slash.music.ResumeCommand;
import bot.slash.music.SkipCommand;
import bot.slash.music.SongCommand;
import bot.slash.music.StopCommand;
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
        commands.add(new EchoCommand());
        commands.add(new EchoFromCommand());

        RelationshipService relationshipService = new RelationshipService(database);
        commands.add(new MarryCommand(relationshipService));
        commands.add(new DivorceCommand(relationshipService));
        commands.add(new AdoptCommand(relationshipService));
        commands.add(new ClaimCommand(relationshipService));
        commands.add(new EmancipateCommand(relationshipService));
        commands.add(new ReleaseCommand(relationshipService));
        commands.add(new FamilyTreeCommand(relationshipService));

        AuditService auditService = new AuditService(database);
        commands.add(new NoteCommand(auditService));
        commands.add(new KickCommand(auditService));
        commands.add(new MuteCommand(config, auditService));
        commands.add(new UnmuteCommand(config, auditService));

        commands.add(new PlayCommand());
        commands.add(new SongCommand());
        commands.add(new SkipCommand());
        commands.add(new StopCommand());
        commands.add(new PauseCommand());
        commands.add(new ResumeCommand());
        commands.add(new QueueCommand());
        commands.add(new NowPlayingCommand());
        commands.add(new LyricsCommand());

        commands.addAll(ActionCommand.registerActionCommands(new HandleCommandAction(config)));
    }

    public List<SlashCommand> getCommands() {
        return commands;
    }
}
