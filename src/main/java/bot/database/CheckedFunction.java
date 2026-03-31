package bot.database;

public interface CheckedFunction<T, R, E extends Throwable> {
    R accept(T input) throws E;
}
