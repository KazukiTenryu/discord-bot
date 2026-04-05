package bot.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.ObjectMapper;

public class ConfigLoader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static Config loadConfig() throws IOException {
        try (InputStream inputStream = ConfigLoader.class.getResourceAsStream("/config.json")) {
            if (inputStream == null) {
                return loadFromDisk();
            }
            return OBJECT_MAPPER.readValue(inputStream, Config.class);
        }
    }

    public static Config loadFromDisk() throws IOException {
        return OBJECT_MAPPER.readValue(new String(Files.readAllBytes(Path.of("config.json"))), Config.class);
    }
}
