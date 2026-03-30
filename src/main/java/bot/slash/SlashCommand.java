package bot.slash;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

public abstract class SlashCommand {
    private final String name;
    private final SlashCommandData data;

    protected SlashCommand(String name, String description) {
        this.name = name;
        this.data = Commands.slash(name, description);
    }

    public abstract void handle(SlashCommandInteractionEvent event);

    public String getName() {
        return name;
    }

    public SlashCommandData getData() {
        return data;
    }
}
