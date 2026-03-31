package bot.slash.pet;

import java.awt.*;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import bot.config.Config;
import bot.utils.KlippyService;

public class HandleCommandAction {
    private final KlippyService klippyService;

    public HandleCommandAction(Config config) {
        this.klippyService = new KlippyService(config);
    }

    public void respondToSlashCommand(SlashCommandInteractionEvent event, String action) {
        event.deferReply().queue();

        String name = event.getName();

        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption("user")).getAsMember());

        String gifUrl = klippyService.fetchGif(action).orElseThrow();

        EmbedBuilder builder = new EmbedBuilder();
        builder.setColor(Color.CYAN);
        builder.setTitle("/" + name);
        builder.setDescription(event.getUser().getAsMention() + " " + name + "s " + target.getAsMention());
        builder.setImage(gifUrl);

        event.getHook().sendMessageEmbeds(builder.build()).queue();
    }
}
