package bot;

import java.util.*;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.config.Config;
import bot.database.Database;
import bot.slash.SlashCommand;
import bot.slash.SlashCommandRepository;

public class GlobalEventListener extends ListenerAdapter {
    private static final Logger LOGGER = LogManager.getLogger(GlobalEventListener.class);
    private final Config config;
    private final Database database;
    private final SlashCommandRepository slashCommandRepository;

    public GlobalEventListener(Config config, Database database, SlashCommandRepository slashCommandRepository) {
        this.config = config;
        this.database = database;
        this.slashCommandRepository = slashCommandRepository;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String name = event.getName();

        LOGGER.info(
                "/{} used by {} (id: {})",
                name,
                event.getUser().getName(),
                event.getUser().getId());

        try {
            Main.getMetrics().count("slash", Map.of("userId", event.getUser().getIdLong(), "name", name));

            Optional<SlashCommand> optionalSlashCommand = slashCommandRepository.getCommands().stream()
                    .filter(cmd -> cmd.getName().equals(name))
                    .findFirst();

            optionalSlashCommand.ifPresent(slashCommand -> slashCommand.handle(event));

        } catch (Exception e) {
            Main.getMetrics().count("slash_failure", Map.of("name", name));

            LOGGER.error("Failed to handle slash command /{}", name, e);
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        String name = event.getName();

        try {
            Optional<SlashCommand> optionalSlashCommand = slashCommandRepository.getCommands().stream()
                    .filter(cmd -> cmd.getName().equals(name))
                    .findFirst();
            optionalSlashCommand.ifPresent(slashCommand -> slashCommand.onAutoComplete(event));
        } catch (Exception e) {
            LOGGER.error("Failed to handle autocomplete for /{}", name, e);
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String componentId = event.getComponentId();

        if (componentId.startsWith("r")) {
            List<String> selectedValues = event.getValues();

            if (selectedValues.isEmpty()) {
                return;
            }

            String selectedRoleId = selectedValues.getFirst();
            String roleIdsPart = componentId.substring("r".length());
            List<String> allRoleIdsInGroup = Arrays.asList(roleIdsPart.split(","));

            Role selectedRole = Objects.requireNonNull(event.getGuild()).getRoleById(selectedRoleId);

            if (selectedRole == null) {
                event.reply("That role no longer exists.").setEphemeral(true).queue();
                return;
            }

            Member member = Objects.requireNonNull(event.getMember());

            List<Role> rolesToRemove = allRoleIdsInGroup.stream()
                    .map(roleId -> event.getGuild().getRoleById(roleId))
                    .filter(Objects::nonNull)
                    .filter(role -> !role.getId().equals(selectedRoleId))
                    .filter(member.getRoles()::contains)
                    .toList();

            Main.getMetrics().count("role_selection", Map.of("role", selectedRole.getName()));

            for (Role role : rolesToRemove) {
                event.getGuild().removeRoleFromMember(member, role).queue();
            }

            if (member.getRoles().contains(selectedRole)) {
                event.getGuild()
                        .removeRoleFromMember(member, selectedRole)
                        .queue(
                                _ -> event.reply("Removed role: " + selectedRole.getName())
                                        .setEphemeral(true)
                                        .queue(),
                                error -> event.reply("Failed to remove role: " + error.getMessage())
                                        .setEphemeral(true)
                                        .queue());
            } else {
                event.getGuild()
                        .addRoleToMember(member, selectedRole)
                        .queue(
                                _ -> event.reply("Added role: " + selectedRole.getName())
                                        .setEphemeral(true)
                                        .queue(),
                                error -> event.reply("Failed to add role: " + error.getMessage())
                                        .setEphemeral(true)
                                        .queue());
            }
        }
    }
}
