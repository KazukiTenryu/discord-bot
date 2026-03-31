package bot.database;

public interface CheckedConsumer<T, E extends Throwable> {
    void accept(T input) throws E;
}
