package bot.slash.pet;

import java.util.ArrayList;
import java.util.List;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

public class ActionCommand {
    private static final List<String> ACTIONS = List.of(
            "pet", "bonk", "hug", "worship", "pat", "highfive", "kiss", "comfort", "slap", "yeet", "lick", "punch");

    public static List<SlashCommand> registerActionCommands(HandleCommandAction handler) {
        List<SlashCommand> commands = new ArrayList<>();

        for (String action : ACTIONS) {

            SlashCommand command = new SlashCommand(action, action + " a user") {
                @Override
                public void handle(SlashCommandInteractionEvent event) {
                    handler.respondToSlashCommand(event, "anime%20" + action + "ing");
                }
            };

            command.getData().addOptions(new OptionData(OptionType.USER, "user", "the user to " + action, true));

            commands.add(command);
        }

        return commands;
    }
}
