import java.sql.*;
import java.time.LocalDateTime;
import java.nio.file.*;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        initDatabase();
        FleetService fleet = new FleetService();
        Scanner in = new Scanner(System.in);

        while (true) {
            System.out.println("\n=== ULTRON Fleet Manager ===");
            System.out.println("1) List robots        2) Book robot");
            System.out.println("3) Complete booking   4) Cancel booking");
            System.out.println("5) Maintenance alerts 6) Mark robot serviced");
            System.out.println("0) Exit");
            System.out.print("> ");
            String choice = in.nextLine().trim();

            try {
                switch (choice) {
                    case "1" -> fleet.listRobots();
                    case "2" -> {
                        System.out.print("Tenant ID: ");  int t = Integer.parseInt(in.nextLine().trim());
                        System.out.print("Robot ID: ");   int r = Integer.parseInt(in.nextLine().trim());
                        System.out.print("Shift (4/8): "); int h = Integer.parseInt(in.nextLine().trim());
                        fleet.bookRobot(t, r, h, LocalDateTime.now().withSecond(0).withNano(0));
                    }
                    case "3" -> { System.out.print("Booking ID: "); fleet.completeBooking(Integer.parseInt(in.nextLine().trim())); }
                    case "4" -> { System.out.print("Booking ID: "); fleet.cancelBooking(Integer.parseInt(in.nextLine().trim())); }
                    case "5" -> fleet.listAlerts();
                    case "6" -> { System.out.print("Robot ID: "); fleet.completeMaintenance(Integer.parseInt(in.nextLine().trim())); }
                    case "0" -> { return; }
                    default -> System.out.println("Invalid option.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Please enter a number.");
            } catch (SQLException e) {
                System.out.println("Database error: " + e.getMessage());
            }
        }
    }

    /** Runs schema.sql on startup (safe to repeat: uses IF NOT EXISTS / INSERT OR IGNORE). */
    private static void initDatabase() throws Exception {
        String script = Files.readString(Path.of("schema.sql"));
        try (Connection c = Db.get(); Statement s = c.createStatement()) {
            for (String stmt : script.split(";")) {
                // drop comment-only lines, run whatever SQL is left
                String cleaned = stmt.replaceAll("(?m)^\\s*--.*$", "").trim();
                if (!cleaned.isEmpty()) s.execute(cleaned);
            }
        }
    }
}
