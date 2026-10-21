# RaaS Fleet Booking & Maintenance System

Files: `schema.sql`, `Db.java`, `FleetService.java`, `Main.java`, `network_monitor.py`

## Run the Java app
1. Download the SQLite JDBC jar (Maven: `org.xerial:sqlite-jdbc`) into this folder, e.g. `sqlite-jdbc.jar`.
2. Compile: `javac *.java`
3. Run (Linux/macOS): `java -cp ".:sqlite-jdbc.jar" Main`
   Run (Windows):     `java -cp ".;sqlite-jdbc.jar" Main`
   (Newer sqlite-jdbc versions may also need `slf4j-api` jars on the classpath.)

The first run creates `fleet.db` from `schema.sql` and loads sample robots/tenants.

## Run the Python monitor (in a second terminal, same folder)
`python network_monitor.py` (simulated) or `python network_monitor.py --real`

## Demo flow
1. Book robot 1 for 8h -> complete booking. Repeat ~7 times: at 56h the robot flips to **Maintenance**
   with servomotor + actuator alerts.
2. Run the monitor: robots drop to **Offline** after 3 missed pings.
