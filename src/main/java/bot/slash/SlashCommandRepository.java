package bot.slash;

import java.util.ArrayList;
import java.util.List;

import bot.config.Config;
import bot.database.Database;
import bot.maya.MayaSessionManager;
import bot.slash.gif.GifCommand;
import bot.slash.kissorslap.KissOrSlapCommand;
import bot.slash.maya.MayaCommand;
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
import bot.slash.playlist.PlayPlaylistCommand;
import bot.slash.playlist.PlaylistCommand;
import bot.slash.playlist.PlaylistService;
import bot.slash.rate.RateCommand;
import bot.slash.rizz.RizzCommand;
import bot.slash.rolemenu.RoleSelectCommand;
import bot.slash.ship.ShipCommand;
import bot.slash.time.TimeCommand;
import bot.slash.truthordare.TruthOrDareCommand;
import bot.slash.wouldyourather.WouldYouRatherCommand;

public class SlashCommandRepository {
    private final List<SlashCommand> commands;

    public SlashCommandRepository(
            Config config, Database database, PlaylistService playlistService, MayaSessionManager mayaSessions) {
        this.commands = new ArrayList<>();
        registerCommands(config, database, playlistService, mayaSessions);
    }

    private void registerCommands(
            Config config, Database database, PlaylistService playlistService, MayaSessionManager mayaSessions) {
        commands.add(new PingCommand());

        commands.add(new RateCommand());
        commands.add(new RizzCommand(config));
        commands.add(new GifCommand(config));
        commands.add(new TruthOrDareCommand(config));
        commands.add(new RulesCommand(config));

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

        commands.add(new PlayCommand());
        commands.add(new SongCommand());
        commands.add(new SkipCommand());
        commands.add(new StopCommand());
        commands.add(new PauseCommand());
        commands.add(new ResumeCommand());
        commands.add(new QueueCommand());
        commands.add(new NowPlayingCommand());
        commands.add(new LyricsCommand());

        commands.add(new PlaylistCommand(playlistService, config.webBaseUrlOrNull()));
        commands.add(new PlayPlaylistCommand(playlistService));

        commands.addAll(ActionCommand.registerActionCommands(new HandleCommandAction(config)));

        // Only expose /maya when Sesame credentials are configured.
        if (mayaSessions != null) {
            commands.add(new MayaCommand(mayaSessions));
        }
    }

    public List<SlashCommand> getCommands() {
        return commands;
    }
}
