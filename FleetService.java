import java.sql.*;
import java.time.LocalDateTime;

/** Booking engine + maintenance alerting. */
public class FleetService {

    // ---- Hardware limits ----
    static final double SERVICE_LIMIT_HOURS = 50.0;  // continuous lifting hours before service
    static final int    MIN_BATTERY_TO_BOOK = 30;    // % needed to start a shift
    static final int    BATTERY_DRAIN_PER_HOUR = 8;  // % lost per operating hour

    // ---------- READ ----------
    public void listRobots() throws SQLException {
        String sql = "SELECT robot_id, ip_address, battery_level, status, hours_since_service FROM robots";
        try (Connection c = Db.get(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            System.out.printf("%-4s %-16s %-8s %-12s %s%n", "ID", "IP", "Battery", "Status", "Hrs since service");
            while (rs.next()) {
                System.out.printf("%-4d %-16s %-8s %-12s %.1f%n",
                    rs.getInt("robot_id"), rs.getString("ip_address"),
                    rs.getInt("battery_level") + "%", rs.getString("status"),
                    rs.getDouble("hours_since_service"));
            }
        }
    }

    public void listAlerts() throws SQLException {
        String sql = "SELECT alert_id, robot_id, reason, created_at FROM maintenance_alerts WHERE resolved = 0";
        try (Connection c = Db.get(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            boolean any = false;
            while (rs.next()) {
                any = true;
                System.out.printf("[ALERT #%d] Robot %d - %s (%s)%n",
                    rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4));
            }
            if (!any) System.out.println("No open maintenance alerts.");
        }
    }

    // ---------- CREATE ----------
    /** Books a robot for a 4h or 8h shift. Returns booking id, or -1 if it fails. */
    public int bookRobot(int tenantId, int robotId, int shiftHours, LocalDateTime start) throws SQLException {
        if (shiftHours != 4 && shiftHours != 8) {
            System.out.println("Shift must be 4 or 8 hours.");
            return -1;
        }
        LocalDateTime end = start.plusHours(shiftHours);

        try (Connection c = Db.get()) {
            // 1. Robot must exist, be bookable, and have enough battery
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status, battery_level FROM robots WHERE robot_id = ?")) {
                ps.setInt(1, robotId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) { System.out.println("Robot not found."); return -1; }
                String status = rs.getString("status");
                int battery = rs.getInt("battery_level");
                if (status.equals("Maintenance") || status.equals("Offline")) {
                    System.out.println("Robot is " + status + " and cannot be booked.");
                    return -1;
                }
                if (battery < MIN_BATTERY_TO_BOOK) {
                    System.out.println("Battery too low (" + battery + "%). Need " + MIN_BATTERY_TO_BOOK + "%.");
                    return -1;
                }
            }

            // 2. No overlapping active booking (ISO strings compare correctly as text)
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bookings WHERE robot_id = ? AND state = 'Booked' " +
                    "AND start_time < ? AND end_time > ?")) {
                ps.setInt(1, robotId);
                ps.setString(2, end.toString());
                ps.setString(3, start.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getInt(1) > 0) {
                    System.out.println("Robot already booked for that time slot.");
                    return -1;
                }
            }

            // 3. Insert booking and mark robot as Rented
            int bookingId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bookings (robot_id, tenant_id, start_time, end_time, shift_hours) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, robotId);
                ps.setInt(2, tenantId);
                ps.setString(3, start.toString());
                ps.setString(4, end.toString());
                ps.setInt(5, shiftHours);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                keys.next();
                bookingId = keys.getInt(1);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE robots SET status = 'Rented' WHERE robot_id = ?")) {
                ps.setInt(1, robotId);
                ps.executeUpdate();
            }
            System.out.println("Booking #" + bookingId + " confirmed: robot " + robotId +
                               ", " + start + " -> " + end);
            return bookingId;
        }
    }

    // ---------- UPDATE ----------
    /** Ends a shift: adds hours, drains battery, runs the maintenance check. */
    public void completeBooking(int bookingId) throws SQLException {
        try (Connection c = Db.get()) {
            int robotId, hours;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT robot_id, shift_hours FROM bookings WHERE booking_id = ? AND state = 'Booked'")) {
                ps.setInt(1, bookingId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) { System.out.println("No active booking with that ID."); return; }
                robotId = rs.getInt(1);
                hours = rs.getInt(2);
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE bookings SET state = 'Completed' WHERE booking_id = ?")) {
                ps.setInt(1, bookingId);
                ps.executeUpdate();
            }

            // Wear and battery drain
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE robots SET hours_since_service = hours_since_service + ?, " +
                    "battery_level = MAX(0, battery_level - ?) WHERE robot_id = ?")) {
                ps.setInt(1, hours);
                ps.setInt(2, hours * BATTERY_DRAIN_PER_HOUR);
                ps.setInt(3, robotId);
                ps.executeUpdate();
            }

            // Default next status: Charging if low battery, otherwise Available
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE robots SET status = CASE WHEN battery_level < ? THEN 'Charging' ELSE 'Available' END " +
                    "WHERE robot_id = ?")) {
                ps.setInt(1, MIN_BATTERY_TO_BOOK);
                ps.setInt(2, robotId);
                ps.executeUpdate();
            }

            System.out.println("Booking #" + bookingId + " completed.");
            checkMaintenance(c, robotId); // may override status to Maintenance
        }
    }

    /** THE HARDWARE TWIST: flag robots that hit the service interval. */
    private void checkMaintenance(Connection c, int robotId) throws SQLException {
        double hrs;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT hours_since_service FROM robots WHERE robot_id = ?")) {
            ps.setInt(1, robotId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            hrs = rs.getDouble(1);
        }
        if (hrs < SERVICE_LIMIT_HOURS) return;

        // Avoid duplicate open alerts
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM maintenance_alerts WHERE robot_id = ? AND resolved = 0")) {
            ps.setInt(1, robotId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            if (rs.getInt(1) > 0) return;
        }

        String[] reasons = {
            "Servomotor inspection (" + hrs + "h continuous lifting)",
            "Actuator recalibration (" + hrs + "h continuous lifting)"
        };
        for (String reason : reasons) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO maintenance_alerts (robot_id, reason) VALUES (?, ?)")) {
                ps.setInt(1, robotId);
                ps.setString(2, reason);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE robots SET status = 'Maintenance' WHERE robot_id = ?")) {
            ps.setInt(1, robotId);
            ps.executeUpdate();
        }
        System.out.println("!! Robot " + robotId + " hit " + hrs + "h - flagged for maintenance.");
    }

    /** Technician finished: reset counters and put the robot back in the pool. */
    public void completeMaintenance(int robotId) throws SQLException {
        try (Connection c = Db.get()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE maintenance_alerts SET resolved = 1 WHERE robot_id = ?")) {
                ps.setInt(1, robotId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE robots SET hours_since_service = 0, status = 'Available' WHERE robot_id = ?")) {
                ps.setInt(1, robotId);
                ps.executeUpdate();
            }
            System.out.println("Robot " + robotId + " serviced and returned to the pool.");
        }
    }

    // ---------- DELETE (cancel) ----------
    public void cancelBooking(int bookingId) throws SQLException {
        try (Connection c = Db.get()) {
            int robotId = -1;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT robot_id FROM bookings WHERE booking_id = ? AND state = 'Booked'")) {
                ps.setInt(1, bookingId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) robotId = rs.getInt(1);
            }
            if (robotId == -1) { System.out.println("No active booking with that ID."); return; }

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE bookings SET state = 'Cancelled' WHERE booking_id = ?")) {
                ps.setInt(1, bookingId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE robots SET status = 'Available' WHERE robot_id = ? AND status = 'Rented'")) {
                ps.setInt(1, robotId);
                ps.executeUpdate();
            }
            System.out.println("Booking #" + bookingId + " cancelled.");
        }
    }
}
