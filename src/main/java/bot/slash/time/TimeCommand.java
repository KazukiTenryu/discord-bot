package bot.slash.time;

import java.awt.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import bot.slash.SlashCommand;

public class TimeCommand extends SlashCommand {
    private static final String LOCATION_OPTION = "location";

    public TimeCommand() {
        super("time", "See the current time for a specific location");

        OptionData locationOption = new OptionData(
                        OptionType.STRING, LOCATION_OPTION, "the location to get the time for", true)
                .setAutoComplete(true);

        getData().addOptions(locationOption);
    }

    @Override
    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        if (!event.getFocusedOption().getName().equals(LOCATION_OPTION)) {
            return;
        }

        String input = event.getFocusedOption().getValue().toLowerCase();

        List<Command.Choice> choices = new ArrayList<>();

        if (input.isEmpty()) {
            event.replyChoices(choices).queue();
            return;
        }

        for (String zone : ZoneId.getAvailableZoneIds()) {
            String city = zone.substring(zone.lastIndexOf("/") + 1).replace("_", " ");

            if (city.toLowerCase().contains(input)) {
                choices.add(new Command.Choice(city + " (" + zone + ")", zone));
            }

            if (choices.size() >= 25) {
                break;
            }
        }

        event.replyChoices(choices).queue();
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String location = event.getOption(LOCATION_OPTION) != null
                ? Objects.requireNonNull(event.getOption(LOCATION_OPTION)).getAsString()
                : null;

        if (location == null || location.isBlank()) {
            event.reply("❌ Please provide a location.").setEphemeral(true).queue();
            return;
        }

        String zoneId = findBestZoneId(location);

        if (zoneId == null) {
            event.reply(
                            "❌ Couldn't find a timezone for that location. Try something like `London`, `Tokyo`, or `New York`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(java.time.ZoneId.of(zoneId));

        String time = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String date = now.format(DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy"));

        String prettyLocation = zoneId.substring(zoneId.lastIndexOf("/") + 1).replace("_", " ");

        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(new Color(255, 105, 180));
        embed.setTitle("Time Check");
        embed.setDescription("✨ Current time in **" + prettyLocation + "**\n\n" + "💗 **Time:** `"
                + time + "`\n" + "📅 **Date:** `"
                + date + "`\n\n" + "🌍 *Timezone:* `"
                + zoneId + "`");

        embed.setFooter("Stay on time, cutie ⏰", null);
        event.replyEmbeds(embed.build()).queue();
    }

    private String findBestZoneId(String input) {
        String normalized = input.toLowerCase().replace(" ", "_");

        for (String zone : ZoneId.getAvailableZoneIds()) {
            if (zone.toLowerCase().contains(normalized)) {
                return zone;
            }
        }

        for (String zone : ZoneId.getAvailableZoneIds()) {
            String city = zone.substring(zone.lastIndexOf("/") + 1).toLowerCase();
            if (city.contains(input.toLowerCase())) {
                return zone;
            }
        }

        return null;
    }
}
