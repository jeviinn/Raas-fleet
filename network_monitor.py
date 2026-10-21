"""
Network Monitor: pings each robot's IPv4 address and updates fleet.db.

Default mode SIMULATES pings (random drops) so you can demo without real robots.
Use --real to run an actual OS ping instead.

    python network_monitor.py                 # simulated, runs forever
    python network_monitor.py --cycles 5      # run 5 sweeps then stop
    python network_monitor.py --real          # real ICMP pings
"""
import argparse
import platform
import random
import sqlite3
import subprocess
import time
from datetime import datetime

DB_PATH = "fleet.db"
FAILS_BEFORE_OFFLINE = 3      # consecutive missed pings before marking Offline
SIM_DROP_CHANCE = 0.15        # 15% of simulated pings fail


def ping_simulated(ip: str) -> bool:
    return random.random() > SIM_DROP_CHANCE


def ping_real(ip: str) -> bool:
    count_flag = "-n" if platform.system().lower() == "windows" else "-c"
    result = subprocess.run(
        ["ping", count_flag, "1", ip],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def log(msg: str) -> None:
    print(f"[{datetime.now():%H:%M:%S}] {msg}")


def sweep(conn: sqlite3.Connection, ping, misses: dict) -> None:
    robots = conn.execute("SELECT robot_id, ip_address, status FROM robots").fetchall()

    for robot_id, ip, status in robots:
        if ping(ip):
            misses[robot_id] = 0
            # Robot came back: restore it only if we were the ones who marked it Offline
            if status == "Offline":
                conn.execute("UPDATE robots SET status = 'Available' WHERE robot_id = ?", (robot_id,))
                log(f"Robot {robot_id} ({ip}) RECONNECTED -> Available")
            else:
                log(f"Robot {robot_id} ({ip}) OK")
        else:
            misses[robot_id] = misses.get(robot_id, 0) + 1
            log(f"Robot {robot_id} ({ip}) ping FAILED ({misses[robot_id]}/{FAILS_BEFORE_OFFLINE})")
            if misses[robot_id] >= FAILS_BEFORE_OFFLINE and status != "Offline":
                conn.execute("UPDATE robots SET status = 'Offline' WHERE robot_id = ?", (robot_id,))
                log(f"Robot {robot_id} ({ip}) DROPPED CONNECTION -> flagged Offline")

    conn.commit()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--real", action="store_true", help="use real OS pings")
    parser.add_argument("--interval", type=float, default=5, help="seconds between sweeps")
    parser.add_argument("--cycles", type=int, default=0, help="stop after N sweeps (0 = forever)")
    args = parser.parse_args()

    ping = ping_real if args.real else ping_simulated
    misses: dict = {}
    conn = sqlite3.connect(DB_PATH, timeout=5)  # waits if Java is writing

    cycle = 0
    try:
        while True:
            cycle += 1
            log(f"--- sweep {cycle} ---")
            sweep(conn, ping, misses)
            if args.cycles and cycle >= args.cycles:
                break
            time.sleep(args.interval)
    except KeyboardInterrupt:
        log("Monitor stopped.")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
