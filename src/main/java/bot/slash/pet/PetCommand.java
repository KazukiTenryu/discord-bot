package bot.slash.pet;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

public class PetCommand extends SlashCommand {
    private static final String USER_OPTION = "user";
    private final HandleCommandAction handler;

    public PetCommand(HandleCommandAction handler) {
        super("pet", "Pet the target user");

        this.handler = handler;

        getData().addOptions(new OptionData(OptionType.USER, USER_OPTION, "the user to pet", true));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        handler.respondToSlashCommand(event, "anime%20petting");
    }
}
