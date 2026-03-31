package bot.slash.rate;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

public class RateCommand extends SlashCommand {
    private static final String USER_OPTION = "user";

    public RateCommand() {
        super("rate", "Rates a user between 1-10");

        getData().addOptions(new OptionData(OptionType.USER, USER_OPTION, "the user to rate", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption(USER_OPTION)).getAsMember());

        int rating = ThreadLocalRandom.current().nextInt(1, 11);

        event.reply("I rate " + target.getAsMention() + " " + rating + "/10 :heart:")
                .queue();
    }
}
