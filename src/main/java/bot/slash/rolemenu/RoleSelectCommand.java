package bot.slash.rolemenu;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

public class RoleSelectCommand extends SlashCommand {
    private static final String TITLE_OPTION = "title";
    private static final String MESSAGE_OPTION = "message";
    private static final String ROLE1_OPTION = "role1";
    private static final String ROLE2_OPTION = "role2";
    private static final String ROLE3_OPTION = "role3";
    private static final String ROLE4_OPTION = "role4";
    private static final String ROLE5_OPTION = "role5";

    public RoleSelectCommand() {
        super("role-select", "Create a single selection role menu");

        OptionData[] options = {
            new OptionData(OptionType.STRING, TITLE_OPTION, "The embed title to show", true),
            new OptionData(OptionType.STRING, MESSAGE_OPTION, "The embed description", true),
            new OptionData(OptionType.ROLE, ROLE1_OPTION, "First role", true),
            new OptionData(OptionType.ROLE, ROLE2_OPTION, "Second role", false),
            new OptionData(OptionType.ROLE, ROLE3_OPTION, "Third role", false),
            new OptionData(OptionType.ROLE, ROLE4_OPTION, "Fourth role", false),
            new OptionData(OptionType.ROLE, ROLE5_OPTION, "Fifth role", false)
        };

        getData().addOptions(options);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String message = event.getOption(MESSAGE_OPTION, "Select a role:", OptionMapping::getAsString);
        String title = Objects.requireNonNull(event.getOption(TITLE_OPTION)).getAsString();

        List<Role> roles = new ArrayList<>();

        for (String optionName : List.of(ROLE1_OPTION, ROLE2_OPTION, ROLE3_OPTION, ROLE4_OPTION, ROLE5_OPTION)) {
            OptionMapping mapping = event.getOption(optionName);
            if (mapping != null) {
                Role role = mapping.getAsRole();
                roles.add(role);
            }
        }

        if (roles.isEmpty()) {
            event.reply("You must provide at least one role.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String roleIds = roles.stream().map(Role::getId).collect(Collectors.joining(","));
        String componentId = "r" + roleIds;

        StringSelectMenu.Builder menuBuilder = StringSelectMenu.create(componentId)
                .setPlaceholder("Choose a role...")
                .setMinValues(1)
                .setMaxValues(1);

        for (Role role : roles) {
            menuBuilder.addOption(role.getName(), role.getId(), "Select this role");
        }

        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(Color.CYAN);
        embed.setTitle(title);
        embed.setDescription(message);
        event.getChannel()
                .sendMessageEmbeds(embed.build())
                .addComponents(ActionRow.of(menuBuilder.build()))
                .queue(_ -> event.reply("Role selection menu created!")
                        .setEphemeral(true)
                        .queue());
    }
}
