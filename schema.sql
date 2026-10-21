-- RaaS Fleet Booking & Maintenance System (SQLite)
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS robots (
    robot_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    model               TEXT    NOT NULL DEFAULT 'ULTRON',
    ip_address          TEXT    NOT NULL UNIQUE,
    battery_level       INTEGER NOT NULL DEFAULT 100 CHECK (battery_level BETWEEN 0 AND 100),
    status              TEXT    NOT NULL DEFAULT 'Available'
                        CHECK (status IN ('Available','Rented','Charging','Maintenance','Offline')),
    hours_since_service REAL    NOT NULL DEFAULT 0   -- continuous lifting hours since last service
);

CREATE TABLE IF NOT EXISTS tenants (
    tenant_id INTEGER PRIMARY KEY AUTOINCREMENT,
    name      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS bookings (
    booking_id  INTEGER PRIMARY KEY AUTOINCREMENT,
    robot_id    INTEGER NOT NULL REFERENCES robots(robot_id),
    tenant_id   INTEGER NOT NULL REFERENCES tenants(tenant_id),
    start_time  TEXT    NOT NULL,   -- ISO-8601, e.g. 2026-10-20T08:00
    end_time    TEXT    NOT NULL,
    shift_hours INTEGER NOT NULL CHECK (shift_hours IN (4, 8)),
    state       TEXT    NOT NULL DEFAULT 'Booked'
                CHECK (state IN ('Booked','Completed','Cancelled'))
);

CREATE TABLE IF NOT EXISTS maintenance_alerts (
    alert_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    robot_id   INTEGER NOT NULL REFERENCES robots(robot_id),
    reason     TEXT    NOT NULL,
    created_at TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved   INTEGER NOT NULL DEFAULT 0
);

-- Sample data
INSERT OR IGNORE INTO robots (robot_id, ip_address, battery_level, status) VALUES
    (1, '192.168.1.101', 100, 'Available'),
    (2, '192.168.1.102',  85, 'Available'),
    (3, '192.168.1.103',  40, 'Charging'),
    (4, '192.168.1.104',  95, 'Available');

INSERT OR IGNORE INTO tenants (tenant_id, name) VALUES
    (1, 'FastFreight Logistics'),
    (2, 'GreenPack Sdn Bhd');
