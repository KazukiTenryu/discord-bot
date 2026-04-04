package bot.slash.kissorslap;

import java.awt.*;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

public class KissOrSlapCommand extends SlashCommand {
    private static final String USER_OPTION = "user";

    public KissOrSlapCommand() {
        super("kiss-or-slap", "Kiss or slap?");

        getData().addOptions(new OptionData(OptionType.USER, USER_OPTION, "the user to kiss or slap", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Member target = Objects.requireNonNull(
                Objects.requireNonNull(event.getOption(USER_OPTION)).getAsMember());

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("💖 Kiss or Slap?");
        embed.setDescription("What should we do with " + target.getAsMention() + "? 👀\n\n" + "React below to vote!");
        embed.setColor(Color.PINK);

        event.replyEmbeds(embed.build())
                .flatMap(InteractionHook::retrieveOriginal)
                .queue(message -> {
                    message.addReaction(Emoji.fromUnicode("💋")).queue();
                    message.addReaction(Emoji.fromUnicode("👋")).queue();
                });
    }
}
