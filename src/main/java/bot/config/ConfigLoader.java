package bot.config;

import java.io.IOException;
import java.io.InputStream;

import tools.jackson.databind.ObjectMapper;

public class ConfigLoader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static Config loadConfig() throws IOException {
        try (InputStream inputStream = ConfigLoader.class.getResourceAsStream("/config.json")) {
            if (inputStream == null) {
                throw new RuntimeException("config.json not found in resources");
            }
            return OBJECT_MAPPER.readValue(inputStream, Config.class);
        }
    }
}
