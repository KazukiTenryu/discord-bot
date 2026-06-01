package bot;

import java.awt.Color;
import java.util.*;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.config.Config;
import bot.database.Database;
import bot.slash.SlashCommand;
import bot.slash.SlashCommandRepository;
import bot.slash.family.RelationshipService;

public class GlobalEventListener extends ListenerAdapter {
    private static final Logger LOGGER = LogManager.getLogger(GlobalEventListener.class);
    private final Config config;
    private final Database database;
    private final SlashCommandRepository slashCommandRepository;
    private final RelationshipService relationshipService;

    public GlobalEventListener(Config config, Database database, SlashCommandRepository slashCommandRepository) {
        this.config = config;
        this.database = database;
        this.slashCommandRepository = slashCommandRepository;
        this.relationshipService = new RelationshipService(database);
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
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (!componentId.startsWith("fam:")) {
            return;
        }

        // Format: fam:<y|n>:<type>:<proposerId>:<targetId>
        String[] parts = componentId.split(":");
        if (parts.length != 5) {
            return;
        }
        String action = parts[1];
        String type = parts[2];
        String proposerId = parts[3];
        String targetId = parts[4];

        if (!event.getUser().getId().equals(targetId)) {
            event.reply("Only <@" + targetId + "> can answer this.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (event.getGuild() == null) {
            return;
        }

        EmbedBuilder result = new EmbedBuilder();
        if ("n".equals(action)) {
            result.setColor(new Color(0x4B4B4B));
            result.setDescription("💔 <@" + targetId + "> declined <@" + proposerId + ">.");
        } else {
            RelationshipService.Outcome outcome =
                    relationshipService.commit(type, event.getGuild().getId(), proposerId, targetId);
            result.setColor(outcome.ok() ? new Color(0x6B0F2B) : new Color(0x4B4B4B));
            result.setDescription(outcome.message());
        }

        // setReplace(true) swaps in the result embed and drops the Accept/Decline buttons.
        event.editMessageEmbeds(result.build()).setReplace(true).queue();
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
