package bot.slash.maya;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.managers.AudioManager;

import bot.maya.MayaSessionManager;
import bot.slash.SlashCommand;

/**
 * /maya start — joins your voice channel and opens a live voice conversation with the AI. /maya stop
 * — ends it and leaves. Connecting to Sesame can take a few seconds, so the reply is deferred and
 * the actual connect runs off the gateway thread.
 */
public class MayaCommand extends SlashCommand {
    private final MayaSessionManager sessions;

    public MayaCommand(MayaSessionManager sessions) {
        super("maya", "Talk to the AI voice assistant in your voice channel 🎙️");
        this.sessions = sessions;
        getData()
                .addSubcommands(
                        new SubcommandData("start", "Join your voice channel and start talking"),
                        new SubcommandData("stop", "End the conversation and leave"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String sub = event.getSubcommandName();
        if ("stop".equals(sub)) {
            handleStop(event);
        } else {
            handleStart(event);
        }
    }

    private void handleStart(SlashCommandInteractionEvent event) {
        long guildId = event.getGuild().getIdLong();
        if (sessions.isActive(guildId)) {
            event.reply("🎙️ I'm already in a conversation. Use `/maya stop` first.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        Member member = event.getMember();
        GuildVoiceState state = member == null ? null : member.getVoiceState();
        AudioChannel channel = state == null ? null : state.getChannel();
        if (channel == null) {
            event.reply("🔇 You need to be in a voice channel first.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        AudioManager audioManager = event.getGuild().getAudioManager();
        if (audioManager.isConnected()) {
            event.reply("🔇 I'm busy in another voice channel (maybe playing music). Use `/stop` first.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.deferReply().queue();
        // Connecting blocks for up to ~15s, so do it off the gateway thread.
        Thread starter = new Thread(
                () -> {
                    boolean ok = sessions.start(channel);
                    event.getHook()
                            .editOriginal(
                                    ok
                                            ? "🎙️ Connected to " + sessions.character() + " in **" + channel.getName()
                                                    + "**. Say hello!"
                                            : "❌ Couldn't connect to " + sessions.character()
                                                    + ". Check the bot logs (Sesame credentials / availability).")
                            .queue();
                },
                "maya-start-" + guildId);
        starter.setDaemon(true);
        starter.start();
    }

    private void handleStop(SlashCommandInteractionEvent event) {
        long guildId = event.getGuild().getIdLong();
        if (!sessions.isActive(guildId)) {
            event.reply("🔇 I'm not in a conversation right now.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        sessions.stop(guildId);
        event.reply("👋 Ended the conversation.").queue();
    }
}
