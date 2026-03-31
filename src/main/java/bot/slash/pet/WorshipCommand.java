package bot.slash.pet;

import java.awt.*;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.config.Config;
import bot.slash.SlashCommand;
import bot.utils.KlippyService;

public class WorshipCommand extends SlashCommand {
    private static final String USER_OPTION = "user";
    private final KlippyService klippyService;

    public WorshipCommand(Config config) {
        super("worship", "Worship the target user");

        this.klippyService = new KlippyService(config);

        getData().addOptions(new OptionData(OptionType.USER, USER_OPTION, "the user to worship", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption(USER_OPTION)).getAsMember());

        String gifUrl = klippyService.fetchGif("anime%20worship").orElseThrow();

        EmbedBuilder builder = new EmbedBuilder();
        builder.setColor(Color.CYAN);
        builder.setTitle("/worship");
        builder.setDescription(event.getUser().getAsMention() + " worships " + target.getAsMention());
        builder.setImage(gifUrl);

        event.replyEmbeds(builder.build()).queue();
    }
}
