package bot.database;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class Database {
    static {
        System.setProperty("org.jooq.no-logo", "true");
        System.setProperty("org.jooq.no-tips", "true");
    }

    private final DataSource dataSource;
    private final Lock writeLock = new ReentrantLock();

    public Database(String jdbcUrl) {
        SQLiteConfig sqLiteConfig = new SQLiteConfig();
        sqLiteConfig.enforceForeignKeys(true);
        sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);

        SQLiteDataSource sqlite = new SQLiteDataSource(sqLiteConfig);
        sqlite.setUrl(jdbcUrl);

        Flyway flyway = Flyway.configure()
                .dataSource(sqlite)
                .locations("classpath:/db/")
                .load();
        flyway.migrate();

        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqlite);
        hikari.setMaximumPoolSize(5);
        hikari.setPoolName("sqlite-pool");

        this.dataSource = new HikariDataSource(hikari);
    }

    public void write(CheckedConsumer<? super DSLContext, ? extends DataAccessException> action) {
        writeAndProvide(ctx -> {
            action.accept(ctx);
            return null;
        });
    }

    public <T> T writeAndProvide(CheckedFunction<? super DSLContext, T, ? extends DataAccessException> action) {
        writeLock.lock();
        try {
            return dsl().transactionResult(configuration -> {
                DSLContext ctx = DSL.using(configuration);
                return action.accept(ctx);
            });
        } finally {
            writeLock.unlock();
        }
    }

    public <T> T read(CheckedFunction<? super DSLContext, T, ? extends DataAccessException> action) {
        return dsl().transactionResult(configuration -> {
            DSLContext ctx = DSL.using(configuration);
            return action.accept(ctx);
        });
    }

    private DSLContext dsl() {
        return DSL.using(dataSource, SQLDialect.SQLITE);
    }
}
