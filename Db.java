import java.sql.*;

/** Opens connections to the shared SQLite file (also used by network_monitor.py). */
public class Db {
    private static final String URL = "jdbc:sqlite:fleet.db";

    public static Connection get() throws SQLException {
        Connection c = DriverManager.getConnection(URL);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA busy_timeout = 5000"); // wait if Python is writing
        }
        return c;
    }
}
