package bot.automod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.dv8tion.jda.api.entities.Message;

public class ChannelHistory {
    private static final int WORKING_AMOUNT = 20;
    private final Map<String, List<Message>> channelMessages;

    public ChannelHistory() {
        channelMessages = new HashMap<>();
    }

    public void addMessage(String channelId, Message message) {
        if (!channelMessages.containsKey(channelId)) {
            channelMessages.put(channelId, new ArrayList<>());
        }

        List<Message> messages = channelMessages.get(channelId);
        messages.add(message);

        if (messages.size() > WORKING_AMOUNT) {
            messages.removeFirst();
        }
    }

    public String asString(String channelId) {
        StringBuilder sb = new StringBuilder();

        if (!channelMessages.containsKey(channelId)) {
            channelMessages.put(channelId, new ArrayList<>());
            return "";
        }

        channelMessages.get(channelId).forEach(message -> {
            String content = message.getContentRaw();
            long time = message.getTimeCreated().toEpochSecond();
            String user = message.getAuthor().getName();

            sb.append(time)
                    .append(" - ")
                    .append(user)
                    .append(": ")
                    .append(content)
                    .append("\n");
        });

        return sb.toString();
    }
}
