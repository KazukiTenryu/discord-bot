import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteDataSource;

/**
 * Standalone Flyway runner used by the {@code migrateDb} Gradle task to bring {@code bot.db}
 * up to date <em>before</em> jOOQ code generation reads its schema.
 *
 * <p>It lives in its own {@code migration} source set and depends only on Flyway and the SQLite
 * driver — never on the application's (generated) classes — so it compiles and runs even when the
 * jOOQ classes don't exist yet. That is what breaks the chicken-and-egg cycle between codegen
 * (needs the schema in {@code bot.db}) and migration (previously needed the app to compile first).
 *
 * <p>{@code args[0]} = path to the SQLite database file, {@code args[1]} = path to the migration
 * scripts directory. Mirrors the Flyway configuration used by {@code bot.database.Database}.
 */
public final class MigrateDb {
    private MigrateDb() {}

    public static void main(String[] args) {
        String dbFile = args[0];
        String scriptsDir = args[1];

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFile);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + scriptsDir)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
