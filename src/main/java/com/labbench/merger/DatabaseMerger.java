package com.labbench.merger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges two LabBench databases (schema version 11.0) into a third target database.
 * Supports both MySQL and SQLite connections.
 *
 * Usage:
 *   java -jar db-merger.jar plan   <db1-url> <db2-url> <target-url> [username] [password]
 *   java -jar db-merger.jar merge  <db1-url> <db2-url> <target-url> [username] [password]
 */
public class DatabaseMerger {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMerger.class);

    /**
     * Number of rows to fetch and insert per batch. Controls memory usage for tables
     * with large binary data (e.g. gelimage, traces). Adjust as needed.
     */
    private static final int BATCH_SIZE = 100;

    // Mapping tables for deduplicated entities (DB2 old id -> target new id)
    private final Map<Integer, Integer> cscocktailMap = new HashMap<>();
    private final Map<Integer, Integer> pcrCocktailMap = new HashMap<>();
    private final Map<Integer, Integer> thermocycleMap = new HashMap<>();
    private final Map<Integer, Integer> cycleMap = new HashMap<>();
    private final Map<Integer, Integer> stateMap = new HashMap<>();

    // DB1 row counts per table (used as offset for DB2 ids)
    private final Map<String, Long> db1Counts = new LinkedHashMap<>();

    // Workflow name remapping for DB2: db2 workflow id -> new name
    private final Map<Integer, String> workflowNameMap = new HashMap<>();

    // ─── Main entry point ──────────────────────────────────────────────

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String mode = null;
        String db1Url = null;
        String db2Url = null;
        String targetUrl = null;
        String db1Username = null;
        String db1Password = null;
        String db2Username = null;
        String db2Password = null;
        String targetUsername = null;
        String targetPassword = null;

        // First arg is always the mode
        mode = args[0].toLowerCase();

        // Determine properties file path: second arg if not a URL, otherwise default
        String propsPath = "merger.properties";
        if (args.length == 2 && !args[1].startsWith("jdbc:")) {
            propsPath = args[1];
        }

        // Try loading from properties file
        java.io.File propsFile = new java.io.File(propsPath);
        if (propsFile.exists()) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(propsFile)) {
                java.util.Properties props = new java.util.Properties();
                props.load(fis);
                db1Url = props.getProperty("db1.url");
                db2Url = props.getProperty("db2.url");
                targetUrl = props.getProperty("target.url");

                // Per-database credentials (fall back to shared credentials)
                String sharedUser = trimToNull(props.getProperty("db.username"));
                String sharedPass = trimToNull(props.getProperty("db.password"));
                db1Username = trimToNull(props.getProperty("db1.username"));
                db1Password = trimToNull(props.getProperty("db1.password"));
                db2Username = trimToNull(props.getProperty("db2.username"));
                db2Password = trimToNull(props.getProperty("db2.password"));
                targetUsername = trimToNull(props.getProperty("target.username"));
                targetPassword = trimToNull(props.getProperty("target.password"));

                // Fall back to shared credentials if per-db not set
                if (db1Username == null) db1Username = sharedUser;
                if (db1Password == null) db1Password = sharedPass;
                if (db2Username == null) db2Username = sharedUser;
                if (db2Password == null) db2Password = sharedPass;
                if (targetUsername == null) targetUsername = sharedUser;
                if (targetPassword == null) targetPassword = sharedPass;

                log.info("Loaded configuration from {}", propsFile.getAbsolutePath());
            } catch (java.io.IOException e) {
                log.warn("Failed to load properties file: {}", e.getMessage());
            }
        }

        // CLI args override properties file (legacy support — shared credentials only)
        if (args.length >= 4) {
            db1Url = args[1];
            db2Url = args[2];
            targetUrl = args[3];
            if (args.length > 4) {
                db1Username = db2Username = targetUsername = args[4];
            }
            if (args.length > 5) {
                db1Password = db2Password = targetPassword = args[5];
            }
        }

        if (!mode.equals("plan") && !mode.equals("merge")) {
            System.err.println("Unknown mode: " + mode + ". Use 'plan' or 'merge'.");
            printUsage();
            System.exit(1);
        }

        if (db1Url == null || db2Url == null || targetUrl == null) {
            System.err.println("ERROR: Database URLs not configured. Provide merger.properties or pass URLs as arguments.");
            printUsage();
            System.exit(1);
        }

        DatabaseMerger merger = new DatabaseMerger();

        try {
            switch (mode) {
                case "plan":
                    merger.runPlan(db1Url, db1Username, db1Password,
                                  db2Url, db2Username, db2Password,
                                  targetUrl, targetUsername, targetPassword);
                    break;
                case "merge":
                    merger.runMerge(db1Url, db1Username, db1Password,
                                   db2Url, db2Username, db2Password,
                                   targetUrl, targetUsername, targetPassword);
                    break;
            }
        } catch (Exception e) {
            log.error("Fatal error", e);
            System.err.println("ERROR: " + e.getMessage());
            System.exit(2);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar db-merger.jar <plan|merge>                                      # uses merger.properties");
        System.out.println("  java -jar db-merger.jar <plan|merge> <properties-file>                    # uses specified properties file");
        System.out.println("  java -jar db-merger.jar <plan|merge> <db1> <db2> <target> [user] [pass]   # all from CLI (shared credentials)");
        System.out.println();
        System.out.println("Properties file format (merger.properties):");
        System.out.println("  db1.url=jdbc:sqlite:/path/to/db1.db");
        System.out.println("  db2.url=jdbc:sqlite:/path/to/db2.db");
        System.out.println("  target.url=jdbc:sqlite:/path/to/target.db");
        System.out.println();
        System.out.println("  # Per-database credentials (optional, override shared):");
        System.out.println("  db1.username=user1");
        System.out.println("  db1.password=pass1");
        System.out.println("  db2.username=user2");
        System.out.println("  db2.password=pass2");
        System.out.println("  target.username=user3");
        System.out.println("  target.password=pass3");
        System.out.println();
        System.out.println("  # Or shared credentials (used when per-db not set):");
        System.out.println("  db.username=shareduser");
        System.out.println("  db.password=sharedpass");
    }

    // ─── Connection helper ─────────────────────────────────────────────

    private Connection connect(String url, String username, String password) throws SQLException {
        if (url.startsWith("jdbc:sqlite:")) {
            return DriverManager.getConnection(url);
        } else {
            if (username != null && password != null) {
                return DriverManager.getConnection(url, username, password);
            } else {
                return DriverManager.getConnection(url);
            }
        }
    }

    private boolean isSQLite(String url) {
        return url != null && url.startsWith("jdbc:sqlite:");
    }

    /**
     * Extract a human-readable database name from a JDBC URL.
     * e.g. "jdbc:sqlite:/path/to/mydb.db" -> "mydb"
     *      "jdbc:mysql://host:3306/labbench1" -> "labbench1"
     */
    private String dbNameFromUrl(String url) {
        if (url == null) return "unknown";
        // SQLite: last path segment without extension
        if (url.startsWith("jdbc:sqlite:")) {
            String path = url.substring("jdbc:sqlite:".length());
            String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            return fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        }
        // MySQL: database name is the last path segment
        if (url.contains("/")) {
            String last = url.substring(url.lastIndexOf('/') + 1);
            // Strip query params
            if (last.contains("?")) last = last.substring(0, last.indexOf('?'));
            return last;
        }
        return url;
    }

    // Duplicate name sets computed during merge for cocktail name prefixing
    private final Set<String> csCocktailDupNames = new HashSet<>();
    private final Set<String> pcrCocktailDupNames = new HashSet<>();
    private String db1Name = "DB1";
    private String db2Name = "DB2";

    // ─── Schema creation for target database ───────────────────────────

    private void createTargetSchema(Connection target, String targetUrl) throws SQLException {
        log.info("Creating target database schema...");
        Statement stmt = target.createStatement();

        // Use TEXT for LONGVARCHAR/LONGVARBINARY depending on DB type
        String longText = isSQLite(targetUrl) ? "TEXT" : "LONGTEXT";
        String longBlob = isSQLite(targetUrl) ? "BLOB" : "LONGBLOB";
        String autoInc = isSQLite(targetUrl) ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "INTEGER PRIMARY KEY AUTO_INCREMENT";
        String timestampDefault = isSQLite(targetUrl) ? "TIMESTAMP DEFAULT CURRENT_TIMESTAMP" : "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";

        // databaseversion
        stmt.execute("CREATE TABLE IF NOT EXISTS databaseversion (version INTEGER PRIMARY KEY)");

        // properties
        stmt.execute("CREATE TABLE IF NOT EXISTS properties (name VARCHAR(255) PRIMARY KEY, value VARCHAR(255))");

        // cyclesequencing_cocktail
        stmt.execute("CREATE TABLE IF NOT EXISTS cyclesequencing_cocktail (" +
                "id " + autoInc + ", name VARCHAR(99) NOT NULL, ddh2o DOUBLE NOT NULL, " +
                "buffer DOUBLE NOT NULL, bigDye DOUBLE NOT NULL, notes " + longText + " NOT NULL, " +
                "bufferConc DOUBLE NOT NULL, bigDyeConc DOUBLE NOT NULL, templateConc DOUBLE NOT NULL, " +
                "primerConc DOUBLE NOT NULL, primerAmount DOUBLE NOT NULL, extraItem " + longText + " NOT NULL, " +
                "extraItemAmount DOUBLE NOT NULL, templateAmount DOUBLE NOT NULL)");

        // pcr_cocktail
        stmt.execute("CREATE TABLE IF NOT EXISTS pcr_cocktail (" +
                "id " + autoInc + ", name VARCHAR(99) NOT NULL, ddH20 DOUBLE NOT NULL, " +
                "buffer DOUBLE NOT NULL, mg DOUBLE NOT NULL, bsa DOUBLE NOT NULL, dNTP DOUBLE NOT NULL, " +
                "taq DOUBLE NOT NULL, notes " + longText + " NOT NULL, bufferConc DOUBLE NOT NULL, " +
                "mgConc DOUBLE NOT NULL, dNTPConc DOUBLE NOT NULL, taqConc DOUBLE NOT NULL, " +
                "templateConc DOUBLE NOT NULL, bsaConc DOUBLE NOT NULL, fwPrAmount DOUBLE NOT NULL, " +
                "fwPrConc DOUBLE NOT NULL, revPrAmount DOUBLE NOT NULL, revPrConc DOUBLE NOT NULL, " +
                "extraItem " + longText + " NOT NULL, extraItemAmount DOUBLE NOT NULL, " +
                "templateAmount DOUBLE NOT NULL)");

        // thermocycle
        stmt.execute("CREATE TABLE IF NOT EXISTS thermocycle (" +
                "id " + autoInc + ", name VARCHAR(64), notes " + longText + " NOT NULL)");

        // cycle
        stmt.execute("CREATE TABLE IF NOT EXISTS cycle (" +
                "id " + autoInc + ", thermocycleId INTEGER, repeats INTEGER, " +
                "FOREIGN KEY (thermocycleId) REFERENCES thermocycle(id))");

        // state
        stmt.execute("CREATE TABLE IF NOT EXISTS state (" +
                "id " + autoInc + ", temp INTEGER NOT NULL, length INTEGER NOT NULL, " +
                "cycleId INTEGER, FOREIGN KEY (cycleId) REFERENCES cycle(id))");

        // failure_reason
        stmt.execute("CREATE TABLE IF NOT EXISTS failure_reason (" +
                "id " + autoInc + ", name VARCHAR(80), description VARCHAR(255))");

        // gelimages
        stmt.execute("CREATE TABLE IF NOT EXISTS gelimages (" +
                "id " + autoInc + ", name VARCHAR(45) NOT NULL, plate INTEGER NOT NULL, " +
                "imageData " + longBlob + ", notes " + longText + " NOT NULL, " +
                "FOREIGN KEY (plate) REFERENCES plate(id))");

        // plate
        stmt.execute("CREATE TABLE IF NOT EXISTS plate (" +
                "id " + autoInc + ", name VARCHAR(64) DEFAULT 'plate', " +
                "date DATE DEFAULT CURRENT_TIMESTAMP, size INTEGER NOT NULL, " +
                "type VARCHAR(45) NOT NULL, thermocycle INTEGER DEFAULT -1)");

        // pcr_thermocycle
        stmt.execute("CREATE TABLE IF NOT EXISTS pcr_thermocycle (" +
                "id " + autoInc + ", cycle INTEGER NOT NULL)");

        // cyclesequencing_thermocycle
        stmt.execute("CREATE TABLE IF NOT EXISTS cyclesequencing_thermocycle (" +
                "id " + autoInc + ", cycle INTEGER NOT NULL)");

        // extraction
        stmt.execute("CREATE TABLE IF NOT EXISTS extraction (" +
                "id " + autoInc + ", date " + timestampDefault + ", " +
                "method VARCHAR(45) NOT NULL, volume DOUBLE NOT NULL, dilution DOUBLE, " +
                "concentrationStored TINYINT DEFAULT 0 NOT NULL, concentration DOUBLE, " +
                "parent VARCHAR(45) NOT NULL, sampleId VARCHAR(45) NOT NULL, " +
                "extractionId VARCHAR(45) NOT NULL, control VARCHAR(45) NOT NULL, " +
                "plate INTEGER NOT NULL, location INTEGER NOT NULL, " +
                "technician VARCHAR(90) NOT NULL, notes " + longText + " NOT NULL, " +
                "extractionBarcode VARCHAR(45) NOT NULL, previousPlate VARCHAR(45) NOT NULL, " +
                "previousWell VARCHAR(45) NOT NULL, gelimage " + longBlob + ", " +
                "FOREIGN KEY (plate) REFERENCES plate(id))");

        // workflow
        stmt.execute("CREATE TABLE IF NOT EXISTS workflow (" +
                "id " + autoInc + ", name VARCHAR(45) DEFAULT 'workflow', " +
                "date DATE DEFAULT CURRENT_TIMESTAMP, extractionId INTEGER NOT NULL, " +
                "locus VARCHAR(45) DEFAULT 'COI' NOT NULL, " +
                "FOREIGN KEY (extractionId) REFERENCES extraction(id))");

        // assembly
        stmt.execute("CREATE TABLE IF NOT EXISTS assembly (" +
                "id " + autoInc + ", extraction_id VARCHAR(45) NOT NULL, " +
                "workflow INTEGER NOT NULL, progress VARCHAR(45) NOT NULL, " +
                "consensus " + longText + ", params " + longText + ", " +
                "coverage FLOAT, disagreements INTEGER, edits INTEGER, " +
                "reference_seq_id INTEGER, confidence_scores " + longText + ", " +
                "trim_params_fwd " + longText + ", trim_params_rev " + longText + ", " +
                "other_processing_fwd " + longText + ", other_processing_rev " + longText + ", " +
                "date " + timestampDefault + ", submitted TINYINT DEFAULT 0 NOT NULL, " +
                "notes " + longText + ", editrecord " + longText + ", " +
                "technician VARCHAR(255), bin VARCHAR(255), ambiguities INTEGER, " +
                "failure_reason INTEGER, failure_notes " + longText + ", " +
                "FOREIGN KEY (workflow) REFERENCES workflow(id), " +
                "FOREIGN KEY (failure_reason) REFERENCES failure_reason(id))");

        // gel_quantification
        stmt.execute("CREATE TABLE IF NOT EXISTS gel_quantification (" +
                "id " + autoInc + ", date " + timestampDefault + ", " +
                "extractionId INTEGER NOT NULL, plate INTEGER NOT NULL, " +
                "location INTEGER NOT NULL, technician VARCHAR(255), " +
                "notes " + longText + ", volume DOUBLE, gelImage " + longBlob + ", " +
                "gelBuffer VARCHAR(255), gelConc DOUBLE, stain VARCHAR(255), " +
                "stainConc VARCHAR(255), stainMethod VARCHAR(255), " +
                "gelLadder VARCHAR(255), threshold INTEGER, aboveThreshold INTEGER, " +
                "FOREIGN KEY (extractionId) REFERENCES extraction(id), " +
                "FOREIGN KEY (plate) REFERENCES plate(id))");

        // pcr
        stmt.execute("CREATE TABLE IF NOT EXISTS pcr (" +
                "id " + autoInc + ", prName VARCHAR(64), prSequence VARCHAR(999), " +
                "date " + timestampDefault + ", workflow INTEGER, " +
                "plate INTEGER NOT NULL, location INTEGER NOT NULL, " +
                "cocktail INTEGER NOT NULL, progress VARCHAR(45) NOT NULL, " +
                "extractionId VARCHAR(45) NOT NULL, thermocycle INTEGER DEFAULT -1, " +
                "cleanupPerformed TINYINT DEFAULT 0, cleanupMethod VARCHAR(45) NOT NULL, " +
                "technician VARCHAR(90) NOT NULL, notes " + longText + " NOT NULL, " +
                "revPrName VARCHAR(64) NOT NULL, revPrSequence VARCHAR(999) NOT NULL, " +
                "gelimage " + longBlob + ", " +
                "FOREIGN KEY (workflow) REFERENCES workflow(id), " +
                "FOREIGN KEY (cocktail) REFERENCES pcr_cocktail(id), " +
                "FOREIGN KEY (plate) REFERENCES plate(id))");

        // cyclesequencing
        stmt.execute("CREATE TABLE IF NOT EXISTS cyclesequencing (" +
                "id " + autoInc + ", primerName VARCHAR(64) NOT NULL, " +
                "primerSequence VARCHAR(999) NOT NULL, technician VARCHAR(90) NOT NULL, " +
                "notes " + longText + " NOT NULL, date " + timestampDefault + ", " +
                "workflow INTEGER, thermocycle INTEGER NOT NULL, " +
                "plate INTEGER NOT NULL, location INTEGER NOT NULL, " +
                "extractionId VARCHAR(45) NOT NULL, cocktail INTEGER NOT NULL, " +
                "progress VARCHAR(45) NOT NULL, cleanupPerformed TINYINT NOT NULL, " +
                "cleanupMethod VARCHAR(99) NOT NULL, direction VARCHAR(32) NOT NULL, " +
                "gelimage " + longBlob + ", " +
                "FOREIGN KEY (plate) REFERENCES plate(id), " +
                "FOREIGN KEY (workflow) REFERENCES workflow(id), " +
                "FOREIGN KEY (cocktail) REFERENCES cyclesequencing_cocktail(id))");

        // traces
        stmt.execute("CREATE TABLE IF NOT EXISTS traces (" +
                "id " + autoInc + ", reaction INTEGER NOT NULL, " +
                "name VARCHAR(96) NOT NULL, data " + longBlob + " NOT NULL, " +
                "FOREIGN KEY (reaction) REFERENCES cyclesequencing(id) ON DELETE CASCADE)");

        // sequencing_result
        stmt.execute("CREATE TABLE IF NOT EXISTS sequencing_result (" +
                "reaction INTEGER, assembly INTEGER, " +
                "PRIMARY KEY (reaction, assembly), " +
                "FOREIGN KEY (reaction) REFERENCES cyclesequencing(id) ON DELETE CASCADE, " +
                "FOREIGN KEY (assembly) REFERENCES assembly(id) ON DELETE CASCADE)");

        // Create indexes
        stmt.execute("CREATE INDEX IF NOT EXISTS plate_name ON plate (name)");
        stmt.execute("CREATE INDEX IF NOT EXISTS workflow_date ON workflow (date)");
        stmt.execute("CREATE INDEX IF NOT EXISTS workflow_locus ON workflow (locus)");
        stmt.execute("CREATE INDEX IF NOT EXISTS plate_type ON plate (type)");
        stmt.execute("CREATE INDEX IF NOT EXISTS plate_date ON plate (date)");
        stmt.execute("CREATE INDEX IF NOT EXISTS extraction_extractionBarcode ON extraction (extractionBarcode)");
        stmt.execute("CREATE INDEX IF NOT EXISTS extraction_date ON extraction (date)");
        stmt.execute("CREATE INDEX IF NOT EXISTS assembly_progress ON assembly (progress)");
        stmt.execute("CREATE INDEX IF NOT EXISTS assembly_submitted ON assembly (submitted)");
        stmt.execute("CREATE INDEX IF NOT EXISTS assembly_technician ON assembly (technician)");
        stmt.execute("CREATE INDEX IF NOT EXISTS assembly_date ON assembly (date)");
        stmt.execute("CREATE INDEX IF NOT EXISTS extraction_sampleId ON extraction (sampleId)");

        stmt.close();
        log.info("Target schema created successfully.");
    }

    // ─── Utility: count rows ───────────────────────────────────────────

    private long countRows(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private int getIntProperty(Connection conn, String propName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM properties WHERE name = ?")) {
            ps.setString(1, propName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString(1);
                    return val != null ? Integer.parseInt(val.trim()) : 0;
                }
                return 0;
            }
        }
    }

    private long getLongProperty(Connection conn, String propName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM properties WHERE name = ?")) {
            ps.setString(1, propName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString(1);
                    return val != null ? Long.parseLong(val.trim()) : 0L;
                }
                return 0L;
            }
        }
    }

    private String getStringProperty(Connection conn, String propName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM properties WHERE name = ?")) {
            ps.setString(1, propName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
                return null;
            }
        }
    }

    private int getDatabaseVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM databaseversion")) {
            if (rs.next()) return rs.getInt(1);
            throw new SQLException("No version found in databaseversion table");
        }
    }

    // ─── Plan Mode ─────────────────────────────────────────────────────

    public void runPlan(String db1Url, String db1Username, String db1Password,
                        String db2Url, String db2Username, String db2Password,
                        String targetUrl, String targetUsername, String targetPassword) throws Exception {
        log.info("Running merge plan analysis...");

        Connection targetConn = null;
        try (Connection conn1 = connect(db1Url, db1Username, db1Password);
             Connection conn2 = connect(db2Url, db2Username, db2Password)) {

            // Only connect to target for MySQL permission checks
            if (!isSQLite(targetUrl)) {
                targetConn = connect(targetUrl, targetUsername, targetPassword);
            }

            MergePlan plan = buildPlan(conn1, conn2, db1Url, db2Url, targetUrl, targetConn);
            plan.printReport();

            if (!plan.canProceed()) {
                System.exit(1);
            }
        } finally {
            if (targetConn != null) {
                try { targetConn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    private MergePlan buildPlan(Connection conn1, Connection conn2,
                               String db1Url, String db2Url, String targetUrl,
                               Connection targetConn) throws SQLException {
        MergePlan plan = new MergePlan();

        // 0. Permission checks (MySQL only)
        log.info("[Plan  1/10] Checking database permissions...");
        checkPermissions(conn1, db1Url, "DB1", new String[]{"SELECT"}, plan);
        checkPermissions(conn2, db2Url, "DB2", new String[]{"SELECT"}, plan);
        if (targetConn != null) {
            checkPermissions(targetConn, targetUrl, "Target", new String[]{"SELECT", "INSERT", "CREATE"}, plan);
        } else if (isSQLite(targetUrl)) {
            plan.getPermissionResults().add("Target (SQLite): full access assumed");
        }

        // 1. Version validation
        log.info("[Plan  2/10] Validating database versions...");
        plan.setDb1Version(getDatabaseVersion(conn1));
        plan.setDb2Version(getDatabaseVersion(conn2));
        plan.setVersionsMatch(plan.getDb1Version() == plan.getDb2Version());
        if (!plan.isVersionsMatch()) {
            plan.addError("Database versions do not match: DB1=" + plan.getDb1Version() + " DB2=" + plan.getDb2Version());
        }

        // 2. Properties
        log.info("[Plan  3/10] Reading properties...");
        plan.setDb1FullVersion(getStringProperty(conn1, "fullDatabaseVersion"));
        plan.setDb2FullVersion(getStringProperty(conn2, "fullDatabaseVersion"));
        plan.setFullVersionsMatch(Objects.equals(plan.getDb1FullVersion(), plan.getDb2FullVersion()));
        if (!plan.isFullVersionsMatch()) {
            plan.addError("fullDatabaseVersion mismatch: DB1=" + plan.getDb1FullVersion() + " DB2=" + plan.getDb2FullVersion());
        }

        plan.setDb1BackgroundTasksStarted(getLongProperty(conn1, "backgroundTasksStarted"));
        plan.setDb2BackgroundTasksStarted(getLongProperty(conn2, "backgroundTasksStarted"));
        plan.setDb1BackgroundTasksFailed(getLongProperty(conn1, "numberOfTimesBackgroundTasksFailed"));
        plan.setDb2BackgroundTasksFailed(getLongProperty(conn2, "numberOfTimesBackgroundTasksFailed"));

        // 3. Count all tables
        log.info("[Plan  4/10] Counting records in all tables...");
        for (String table : MergePlan.ALL_TABLES) {
            try {
                plan.getDb1Counts().put(table, countRows(conn1, table));
            } catch (SQLException e) {
                plan.getDb1Counts().put(table, 0L);
            }
            try {
                plan.getDb2Counts().put(table, countRows(conn2, table));
            } catch (SQLException e) {
                plan.getDb2Counts().put(table, 0L);
            }
        }
        log.info("  Counted {} tables in both databases", MergePlan.ALL_TABLES.size());

        // 4. Simulate cyclesequencing_cocktail deduplication
        log.info("[Plan  5/10] Analyzing cyclesequencing_cocktail deduplication...");
        simulateCsCocktailDedup(conn1, conn2, plan);

        // 5. Simulate pcr_cocktail deduplication
        log.info("[Plan  6/10] Analyzing pcr_cocktail deduplication...");
        simulatePcrCocktailDedup(conn1, conn2, plan);

        // 6. Simulate thermocycle hierarchy deduplication
        log.info("[Plan  7/10] Analyzing thermocycle hierarchy deduplication...");
        simulateThermocycleDedup(conn1, conn2, plan);

        // 7. Check extraction ID uniqueness
        log.info("[Plan  8/10] Checking extraction ID uniqueness...");
        checkExtractionIdUniqueness(conn1, conn2, plan);

        // 8. Check plate name uniqueness
        log.info("[Plan  9/10] Checking plate name uniqueness...");
        checkPlateNameUniqueness(conn1, conn2, plan);

        // 9. Check workflow name conflicts
        log.info("[Plan 10/10] Checking workflow name conflicts...");
        checkWorkflowNameConflicts(conn1, conn2, plan);

        log.info("Plan analysis complete.");

        return plan;
    }

    // ─── Permission checking (MySQL only) ────────────────────────────

    /**
     * Check MySQL permissions for a given connection. Parses SHOW GRANTS output
     * to verify the required privileges are present. For SQLite, this is skipped.
     */
    private void checkPermissions(Connection conn, String url, String label,
                                  String[] requiredPrivileges, MergePlan plan) {
        if (isSQLite(url)) {
            plan.getPermissionResults().add(label + " (SQLite): full access assumed");
            return;
        }

        // MySQL: extract database name from URL
        String dbName = dbNameFromUrl(url);

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {

            // Collect all grant lines
            List<String> grants = new ArrayList<>();
            while (rs.next()) {
                grants.add(rs.getString(1).toUpperCase());
            }

            // Check each required privilege
            List<String> missing = new ArrayList<>();
            for (String priv : requiredPrivileges) {
                String privUpper = priv.toUpperCase();
                boolean found = false;
                for (String grant : grants) {
                    // Check for ALL PRIVILEGES or the specific privilege
                    // Grants can be on *.* (global), dbname.* (database), or dbname.table
                    if (grant.contains("ALL PRIVILEGES")) {
                        if (grant.contains("ON *.*") ||
                            grant.contains("ON `" + dbName.toUpperCase() + "`.*") ||
                            grant.contains("ON `" + dbName + "`.*")) {
                            found = true;
                            break;
                        }
                    }
                    if (grant.contains(privUpper)) {
                        if (grant.contains("ON *.*") ||
                            grant.contains("ON `" + dbName.toUpperCase() + "`.*") ||
                            grant.contains("ON `" + dbName + "`.*")) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    missing.add(priv);
                }
            }

            if (missing.isEmpty()) {
                plan.getPermissionResults().add(label + " (" + dbName + "): OK — has " +
                        String.join(", ", requiredPrivileges));
            } else {
                String msg = label + " (" + dbName + "): MISSING — " + String.join(", ", missing);
                plan.getPermissionResults().add(msg);
                plan.addError("Insufficient permissions on " + label + ": missing " +
                        String.join(", ", missing) + " on database '" + dbName + "'");
            }

        } catch (SQLException e) {
            // SHOW GRANTS might fail if user lacks even that privilege
            String msg = label + " (" + dbName + "): UNABLE TO CHECK — " + e.getMessage();
            plan.getPermissionResults().add(msg);
            plan.addWarning("Could not verify permissions on " + label + ": " + e.getMessage());
        }
    }

    // ─── Dedup simulation helpers ──────────────────────────────────────

    private void simulateCsCocktailDedup(Connection conn1, Connection conn2, MergePlan plan) throws SQLException {
        List<Map<String, Object>> db1Rows = fetchAllRows(conn1,
                "SELECT id, name, ddh2o, buffer, bigDye, notes, bufferConc, bigDyeConc, templateConc, " +
                        "primerConc, primerAmount, extraItem, extraItemAmount, templateAmount FROM cyclesequencing_cocktail ORDER BY id");
        String[] compFields = {"name", "ddh2o", "buffer", "bigdye", "notes", "bufferconc", "bigdyeconc",
                "templateconc", "primerconc", "primeramount", "extraitem", "extraitemamount", "templateamount"};
        List<Map<String, Object>> db2Rows = fetchAllRows(conn2,
                "SELECT id, name, ddh2o, buffer, bigDye, notes, bufferConc, bigDyeConc, templateConc, " +
                        "primerConc, primerAmount, extraItem, extraItemAmount, templateAmount FROM cyclesequencing_cocktail ORDER BY id");

        int dupes = 0;
        // First pass: identify full-row duplicates
        Set<Integer> fullDupeDb2Ids = new HashSet<>();
        for (Map<String, Object> r2 : db2Rows) {
            int db2Id = ((Number) r2.get("id")).intValue();
            Map<String, Object> r2Comp = extractCompFields(r2, compFields);
            for (Map<String, Object> r1 : db1Rows) {
                Map<String, Object> r1Comp = extractCompFields(r1, compFields);
                if (rowsEqual(r1Comp, r2Comp)) {
                    fullDupeDb2Ids.add(db2Id);
                    int db1Id = ((Number) r1.get("id")).intValue();
                    plan.getCscocktailMappings().add(
                            "DB2 id=" + db2Id + " ('" + r2.get("name") + "') -> existing DB1 id=" + db1Id + " [DUPLICATE]");
                    dupes++;
                    break;
                }
            }
        }
        for (Map<String, Object> r2 : db2Rows) {
            int db2Id = ((Number) r2.get("id")).intValue();
            if (!fullDupeDb2Ids.contains(db2Id)) {
                plan.getCscocktailMappings().add(
                        "DB2 id=" + db2Id + " ('" + r2.get("name") + "') -> new auto-increment id [UNIQUE]");
            }
        }

        // Find shared names, then exclude those where ALL DB2 rows with that name are full duplicates
        Set<String> db1CsNames = new HashSet<>();
        for (Map<String, Object> r1 : db1Rows) {
            Object n = r1.get("name");
            if (n != null) db1CsNames.add(n.toString());
        }
        Set<String> csDupNames = new LinkedHashSet<>();
        for (Map<String, Object> r2 : db2Rows) {
            int db2Id = ((Number) r2.get("id")).intValue();
            Object n = r2.get("name");
            if (n != null && db1CsNames.contains(n.toString()) && !fullDupeDb2Ids.contains(db2Id)) {
                csDupNames.add(n.toString());
            }
        }
        plan.setCscocktailDuplicateNames(csDupNames.size());
        plan.getCscocktailDupNameList().addAll(csDupNames);

        plan.setCscocktailDuplicates(dupes);
        plan.setCscocktailUnique((int) (db2Rows.size() - dupes));
    }

    private void simulatePcrCocktailDedup(Connection conn1, Connection conn2, MergePlan plan) throws SQLException {
        List<Map<String, Object>> db1Rows = fetchAllRows(conn1,
                "SELECT id, name, ddH20, buffer, mg, bsa, dNTP, taq, notes, bufferConc, mgConc, dNTPConc, " +
                        "taqConc, templateConc, bsaConc, fwPrAmount, fwPrConc, revPrAmount, revPrConc, " +
                        "extraItem, extraItemAmount, templateAmount FROM pcr_cocktail ORDER BY id");
        String[] compFields = {"name", "ddh20", "buffer", "mg", "bsa", "dntp", "taq", "notes", "bufferconc",
                "mgconc", "dntpconc", "taqconc", "templateconc", "bsaconc", "fwpramount", "fwprconc",
                "revpramount", "revprconc", "extraitem", "extraitemamount", "templateamount"};
        List<Map<String, Object>> db2Rows = fetchAllRows(conn2,
                "SELECT id, name, ddH20, buffer, mg, bsa, dNTP, taq, notes, bufferConc, mgConc, dNTPConc, " +
                        "taqConc, templateConc, bsaConc, fwPrAmount, fwPrConc, revPrAmount, revPrConc, " +
                        "extraItem, extraItemAmount, templateAmount FROM pcr_cocktail ORDER BY id");

        int dupes = 0;
        // First pass: identify full-row duplicates
        Set<Integer> fullDupeDb2Ids = new HashSet<>();
        for (Map<String, Object> r2 : db2Rows) {
            int db2Id = ((Number) r2.get("id")).intValue();
            Map<String, Object> r2Comp = extractCompFields(r2, compFields);
            for (Map<String, Object> r1 : db1Rows) {
                Map<String, Object> r1Comp = extractCompFields(r1, compFields);
                if (rowsEqual(r1Comp, r2Comp)) {
                    fullDupeDb2Ids.add(db2Id);
                    int db1Id = ((Number) r1.get("id")).intValue();
                    plan.getPcrCocktailMappings().add(
                            "DB2 id=" + db2Id + " ('" + r2.get("name") + "') -> existing DB1 id=" + db1Id + " [DUPLICATE]");
                    dupes++;
                    break;
                }
            }
        }
        for (Map<String, Object> r2 : db2Rows) {
            int db2Id = ((Number) r2.get("id")).intValue();
            if (!fullDupeDb2Ids.contains(db2Id)) {
                plan.getPcrCocktailMappings().add(
                        "DB2 id=" + db2Id + " ('" + r2.get("name") + "') -> new auto-increment id [UNIQUE]");
            }
        }

        // Find shared names, then exclude those where ALL DB2 rows with that name are full duplicates
        Set<String> db1PcrNames = new HashSet<>();
        for (Map<String, Object> r1 : db1Rows) {
            Object n = r1.get("name");
            if (n != null) db1PcrNames.add(n.toString());
        }
        Set<String> pcrDupNames = new LinkedHashSet<>();
        for (Map<String, Object> r2 : db2Rows) {
            int db2Id = ((Number) r2.get("id")).intValue();
            Object n = r2.get("name");
            if (n != null && db1PcrNames.contains(n.toString()) && !fullDupeDb2Ids.contains(db2Id)) {
                pcrDupNames.add(n.toString());
            }
        }
        plan.setPcrCocktailDuplicateNames(pcrDupNames.size());
        plan.getPcrCocktailDupNameList().addAll(pcrDupNames);

        plan.setPcrCocktailDuplicates(dupes);
        plan.setPcrCocktailUnique((int) (db2Rows.size() - dupes));
    }

    /**
     * Represents a complete thermocycle hierarchy for comparison.
     */
    private static class ThermocycleHierarchy {
        int thermocycleId;
        String name;
        String notes;
        List<CycleData> cycles = new ArrayList<>();

        boolean structurallyEquals(ThermocycleHierarchy other) {
            if (!Objects.equals(name, other.name)) return false;
            // Notes can differ - we'll concatenate if different
            if (cycles.size() != other.cycles.size()) return false;
            for (int i = 0; i < cycles.size(); i++) {
                if (!cycles.get(i).structurallyEquals(other.cycles.get(i))) return false;
            }
            return true;
        }
    }

    private static class CycleData {
        int cycleId;
        int repeats;
        List<StateData> states = new ArrayList<>();

        boolean structurallyEquals(CycleData other) {
            if (repeats != other.repeats) return false;
            if (states.size() != other.states.size()) return false;
            for (int i = 0; i < states.size(); i++) {
                if (!states.get(i).equals(other.states.get(i))) return false;
            }
            return true;
        }
    }

    private static class StateData {
        int stateId;
        int temp;
        int length;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof StateData)) return false;
            StateData s = (StateData) o;
            return temp == s.temp && length == s.length;
        }
    }

    private List<ThermocycleHierarchy> loadThermocycleHierarchies(Connection conn) throws SQLException {
        // Load all thermocycles
        Map<Integer, ThermocycleHierarchy> tcMap = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name, notes FROM thermocycle ORDER BY id")) {
            while (rs.next()) {
                ThermocycleHierarchy th = new ThermocycleHierarchy();
                th.thermocycleId = rs.getInt("id");
                th.name = rs.getString("name");
                th.notes = rs.getString("notes");
                tcMap.put(th.thermocycleId, th);
            }
        }

        // Load all cycles in bulk and group by thermocycleId
        Map<Integer, CycleData> cycleById = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, thermocycleId, repeats FROM cycle ORDER BY thermocycleId, id")) {
            while (rs.next()) {
                CycleData cd = new CycleData();
                cd.cycleId = rs.getInt("id");
                cd.repeats = rs.getInt("repeats");
                int tcId = rs.getInt("thermocycleId");
                cycleById.put(cd.cycleId, cd);
                ThermocycleHierarchy th = tcMap.get(tcId);
                if (th != null) {
                    th.cycles.add(cd);
                }
            }
        }

        // Load all states in bulk and group by cycleId
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, temp, length, cycleId FROM state ORDER BY cycleId, id")) {
            while (rs.next()) {
                StateData sd = new StateData();
                sd.stateId = rs.getInt("id");
                sd.temp = rs.getInt("temp");
                sd.length = rs.getInt("length");
                int cyId = rs.getInt("cycleId");
                CycleData cd = cycleById.get(cyId);
                if (cd != null) {
                    cd.states.add(sd);
                }
            }
        }

        return new ArrayList<>(tcMap.values());
    }

    private void simulateThermocycleDedup(Connection conn1, Connection conn2, MergePlan plan) throws SQLException {
        List<ThermocycleHierarchy> h1 = loadThermocycleHierarchies(conn1);
        List<ThermocycleHierarchy> h2 = loadThermocycleHierarchies(conn2);

        int tcDupes = 0, cyDupes = 0, stDupes = 0;

        for (ThermocycleHierarchy th2 : h2) {
            boolean matched = false;
            for (ThermocycleHierarchy th1 : h1) {
                if (th2.structurallyEquals(th1)) {
                    matched = true;
                    tcDupes++;
                    plan.getThermocycleMappings().add(
                            "DB2 id=" + th2.thermocycleId + " ('" + th2.name + "') -> existing DB1 id=" + th1.thermocycleId + " [DUPLICATE]");
                    for (int i = 0; i < th2.cycles.size(); i++) {
                        CycleData c2 = th2.cycles.get(i);
                        CycleData c1 = th1.cycles.get(i);
                        cyDupes++;
                        plan.getCycleMappings().add(
                                "DB2 id=" + c2.cycleId + " (repeats=" + c2.repeats + ") -> existing DB1 id=" + c1.cycleId + " [DUPLICATE]");
                        for (int j = 0; j < c2.states.size(); j++) {
                            StateData s2 = c2.states.get(j);
                            StateData s1 = c1.states.get(j);
                            stDupes++;
                            plan.getStateMappings().add(
                                    "DB2 id=" + s2.stateId + " (temp=" + s2.temp + ",len=" + s2.length + ") -> existing DB1 id=" + s1.stateId + " [DUPLICATE]");
                        }
                    }
                    break;
                }
            }
            if (!matched) {
                plan.getThermocycleMappings().add(
                        "DB2 id=" + th2.thermocycleId + " ('" + th2.name + "') -> new auto-increment id [UNIQUE]");
                for (CycleData c2 : th2.cycles) {
                    plan.getCycleMappings().add(
                            "DB2 id=" + c2.cycleId + " (repeats=" + c2.repeats + ") -> new auto-increment id [UNIQUE]");
                    for (StateData s2 : c2.states) {
                        plan.getStateMappings().add(
                                "DB2 id=" + s2.stateId + " (temp=" + s2.temp + ",len=" + s2.length + ") -> new auto-increment id [UNIQUE]");
                    }
                }
            }
        }

        plan.setThermocycleDuplicates(tcDupes);
        plan.setThermocycleUnique(h2.size() - tcDupes);
        plan.setCycleDuplicatesRemoved(cyDupes);
        plan.setStateDuplicatesRemoved(stDupes);
    }

    private void checkExtractionIdUniqueness(Connection conn1, Connection conn2, MergePlan plan) throws SQLException {
        // Build a set of DB1 extraction IDs
        Set<String> db1Ids = new HashSet<>();
        try (Statement stmt = conn1.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT extractionId FROM extraction")) {
            while (rs.next()) db1Ids.add(rs.getString("extractionId"));
        }

        // Find duplicates in DB2, collecting plate name and well location from both DBs
        String detailQuery =
                "SELECT e.extractionId, e.location, p.name AS plateName " +
                "FROM extraction e JOIN plate p ON e.plate = p.id";

        // Load DB1 extraction details for duplicates
        Map<String, List<String>> db1Details = new HashMap<>();
        try (Statement stmt = conn1.createStatement();
             ResultSet rs = stmt.executeQuery(detailQuery)) {
            while (rs.next()) {
                String eid = rs.getString("extractionId");
                if (db1Ids.contains(eid)) {
                    String plateName = rs.getString("plateName");
                    int location = rs.getInt("location");
                    String well = wellLocationToString(location);
                    db1Details.computeIfAbsent(eid, k -> new ArrayList<>())
                            .add(plateName + " / " + well);
                }
            }
        }

        // Check DB2 for duplicates
        List<String> conflicts = new ArrayList<>();
        List<String> detailedConflicts = new ArrayList<>();
        try (Statement stmt = conn2.createStatement();
             ResultSet rs = stmt.executeQuery(detailQuery)) {
            while (rs.next()) {
                String eid = rs.getString("extractionId");
                if (db1Ids.contains(eid)) {
                    String plateName = rs.getString("plateName");
                    int location = rs.getInt("location");
                    String well = wellLocationToString(location);
                    if (!conflicts.contains(eid)) {
                        conflicts.add(eid);
                    }
                    // Format: extractionId | DB1: plate/well | DB2: plate/well
                    List<String> db1Locs = db1Details.getOrDefault(eid, Collections.singletonList("unknown"));
                    for (String db1Loc : db1Locs) {
                        detailedConflicts.add(eid + "  DB1: " + db1Loc + "  |  DB2: " + plateName + " / " + well);
                    }
                }
            }
        }

        if (!conflicts.isEmpty()) {
            plan.setExtractionIdsUnique(false);
            plan.setExtractionIdConflictDetail(conflicts.size() + " duplicate extractionId(s) found between DB1 and DB2");
            plan.getDuplicateExtractionIds().addAll(detailedConflicts);
            plan.addError("Duplicate extraction.extractionId values found between DB1 and DB2");
        }
    }

    /**
     * Convert a 0-based well index on a 96-well plate (12 columns) to standard notation.
     * 0=A1, 1=A2, ..., 11=A12, 12=B1, etc.
     */
    private static String wellLocationToString(int location) {
        int row = location / 12;
        int col = location % 12;
        char rowLetter = (char) ('A' + row);
        return "" + rowLetter + (col + 1);
    }

    private void checkPlateNameUniqueness(Connection conn1, Connection conn2, MergePlan plan) throws SQLException {
        Set<String> db1Names = new HashSet<>();
        try (Statement stmt = conn1.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM plate")) {
            while (rs.next()) db1Names.add(rs.getString("name"));
        }
        List<String> conflicts = new ArrayList<>();
        try (Statement stmt = conn2.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM plate")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (db1Names.contains(name)) conflicts.add(name);
            }
        }
        if (!conflicts.isEmpty()) {
            plan.setPlateNamesUnique(false);
            plan.setPlateNameConflictDetail("Duplicate plate names: " +
                    String.join(", ", conflicts.size() > 10 ? conflicts.subList(0, 10) : conflicts) +
                    (conflicts.size() > 10 ? " (and " + (conflicts.size() - 10) + " more)" : ""));
            plan.addError("Duplicate plate.name values found between DB1 and DB2");
        }
    }

    // Workflow name pattern: LOCUS_workflowXX
    private static final Pattern WORKFLOW_NAME_PATTERN = Pattern.compile("^(.+)_workflow(\\d+)$");

    private void checkWorkflowNameConflicts(Connection conn1, Connection conn2, MergePlan plan) throws SQLException {
        // Find max workflow number per locus in DB1
        Map<String, Integer> db1MaxPerLocus = getWorkflowMaxPerLocus(conn1);
        Map<String, Integer> db2MaxPerLocus = getWorkflowMaxPerLocus(conn2);

        // Simulate the rename: for DB2 workflows, add DB1's max for same locus
        Set<String> db1Names = new HashSet<>();
        try (Statement stmt = conn1.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM workflow")) {
            while (rs.next()) db1Names.add(rs.getString("name"));
        }

        Set<String> newDb2Names = new HashSet<>();
        List<String> conflicts = new ArrayList<>();
        try (Statement stmt = conn2.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, name FROM workflow")) {
            while (rs.next()) {
                String name = rs.getString("name");
                Matcher m = WORKFLOW_NAME_PATTERN.matcher(name);
                String newName;
                if (m.matches()) {
                    String locus = m.group(1);
                    int num = Integer.parseInt(m.group(2));
                    int offset = db1MaxPerLocus.getOrDefault(locus, 0);
                    newName = locus + "_workflow" + (num + offset);
                } else {
                    newName = name; // non-standard name, keep as-is
                }
                if (db1Names.contains(newName) || newDb2Names.contains(newName)) {
                    conflicts.add(name + " -> " + newName);
                }
                newDb2Names.add(newName);
            }
        }

        if (!conflicts.isEmpty()) {
            plan.setWorkflowRenameConflict(true);
            plan.setWorkflowConflictDetail("Conflicts after rename: " + String.join("; ", conflicts));
            plan.addError("Workflow name conflicts detected after applying rename offset");
        }
    }

    private Map<String, Integer> getWorkflowMaxPerLocus(Connection conn) throws SQLException {
        Map<String, Integer> maxPerLocus = new HashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM workflow")) {
            while (rs.next()) {
                String name = rs.getString("name");
                Matcher m = WORKFLOW_NAME_PATTERN.matcher(name);
                if (m.matches()) {
                    String locus = m.group(1);
                    int num = Integer.parseInt(m.group(2));
                    maxPerLocus.merge(locus, num, Math::max);
                }
            }
        }
        return maxPerLocus;
    }

    // ─── Generic row fetching/comparison helpers ───────────────────────

    private List<Map<String, Object>> fetchAllRows(Connection conn, String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(meta.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Read a single row from a ResultSet into a Map.
     */
    private Map<String, Object> readRow(ResultSet rs, ResultSetMetaData meta) throws SQLException {
        int cols = meta.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= cols; i++) {
            row.put(meta.getColumnLabel(i).toLowerCase(), rs.getObject(i));
        }
        return row;
    }

    /**
     * Functional interface for transforming a row before insertion.
     */
    @FunctionalInterface
    private interface RowTransformer {
        void transform(Map<String, Object> row);
    }

    /**
     * Stream rows from a source query, apply a transformation, and insert into the target
     * in batches of BATCH_SIZE. Returns the number of rows copied.
     */
    private long streamingCopy(Connection source, String selectSql,
                               Connection target, String insertSql, String[] colNames,
                               RowTransformer transformer) throws SQLException {
        long count = 0;
        try (Statement stmt = source.createStatement();
             ResultSet rs = stmt.executeQuery(selectSql);
             PreparedStatement ps = target.prepareStatement(insertSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int batchCount = 0;
            while (rs.next()) {
                Map<String, Object> row = readRow(rs, meta);
                if (transformer != null) {
                    transformer.transform(row);
                }
                setRowParams(ps, row, colNames);
                ps.addBatch();
                batchCount++;
                count++;
                if (batchCount >= BATCH_SIZE) {
                    ps.executeBatch();
                    batchCount = 0;
                    if (count % (BATCH_SIZE * 10) == 0) {
                        log.info("    ... {} rows processed", count);
                    }
                }
            }
            if (batchCount > 0) {
                ps.executeBatch();
            }
        }
        return count;
    }

    private boolean rowsEqual(Map<String, Object> r1, Map<String, Object> r2) {
        if (r1.size() != r2.size()) return false;
        for (String key : r1.keySet()) {
            Object v1 = r1.get(key);
            Object v2 = r2.get(key);
            if (v1 == null && v2 == null) continue;
            if (v1 == null || v2 == null) return false;
            // Handle numeric comparison (different DB types may return different Number types)
            if (v1 instanceof Number && v2 instanceof Number) {
                if (((Number) v1).doubleValue() != ((Number) v2).doubleValue()) return false;
            } else if (!v1.toString().equals(v2.toString())) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  MERGE MODE
    // ═══════════════════════════════════════════════════════════════════

    public void runMerge(String db1Url, String db1Username, String db1Password,
                         String db2Url, String db2Username, String db2Password,
                         String targetUrl, String targetUsername, String targetPassword) throws Exception {
        log.info("Starting database merge...");

        try (Connection conn1 = connect(db1Url, db1Username, db1Password);
             Connection conn2 = connect(db2Url, db2Username, db2Password);
             Connection target = connect(targetUrl, targetUsername, targetPassword)) {

            conn1.setAutoCommit(true); // read-only sources
            conn2.setAutoCommit(true);
            target.setAutoCommit(false);

            if (isSQLite(targetUrl)) {
                target.createStatement().execute("PRAGMA foreign_keys = OFF");
            }

            try {
                // Validate first
                log.info("[Merge] Running pre-merge validation...");
                MergePlan plan = buildPlan(conn1, conn2, db1Url, db2Url, targetUrl, target);
                if (!plan.canProceed()) {
                    plan.printReport();
                    throw new RuntimeException("Pre-merge validation failed. See errors above.");
                }
                log.info("[Merge] Pre-merge validation passed.");

                // Set DB names for name prefixing
                db1Name = dbNameFromUrl(db1Url);
                db2Name = dbNameFromUrl(db2Url);

                // Compute duplicate cocktail name sets for name prefixing during merge
                log.info("[Merge] Computing cocktail duplicate names...");
                computeCocktailDupNames(conn1, conn2);

                // Create schema
                log.info("[Merge  1/17] Creating target schema...");
                createTargetSchema(target, targetUrl);

                // Phase 1: Version and properties
                log.info("[Merge  2/17] Merging version and properties...");
                mergeVersionAndProperties(conn1, conn2, target);

                // Phase 2: Cocktail deduplication
                log.info("[Merge  3/17] Merging cyclesequencing_cocktail (with dedup)...");
                mergeCsCocktails(conn1, conn2, target);
                log.info("[Merge  4/17] Merging pcr_cocktail (with dedup)...");
                mergePcrCocktails(conn1, conn2, target);

                // Phase 3: Thermocycle hierarchy deduplication
                log.info("[Merge  5/17] Merging thermocycle hierarchy (with dedup)...");
                mergeThermocycleHierarchy(conn1, conn2, target);

                // Phase 4: Remaining tables in dependency order
                log.info("[Merge  6/17] Merging failure_reason...");
                mergeSimpleTable(conn1, conn2, target, "failure_reason",
                        "id, name, description",
                        "INSERT INTO failure_reason (id, name, description) VALUES (?, ?, ?)",
                        new String[]{"name", "description"}, new int[]{}, new int[]{});

                log.info("[Merge  7/17] Merging gelimages...");
                mergeGelimages(conn1, conn2, target);

                log.info("[Merge  8/17] Merging pcr_thermocycle...");
                mergeSimpleTable(conn1, conn2, target, "pcr_thermocycle",
                        "id, cycle",
                        "INSERT INTO pcr_thermocycle (id, cycle) VALUES (?, ?)",
                        new String[]{"cycle"}, new int[]{}, new int[]{1});

                log.info("[Merge  9/17] Merging cyclesequencing_thermocycle...");
                mergeSimpleTable(conn1, conn2, target, "cyclesequencing_thermocycle",
                        "id, cycle",
                        "INSERT INTO cyclesequencing_thermocycle (id, cycle) VALUES (?, ?)",
                        new String[]{"cycle"}, new int[]{}, new int[]{1});

                log.info("[Merge 10/17] Merging plate...");
                mergePlate(conn1, conn2, target);
                log.info("[Merge 11/17] Merging extraction...");
                mergeExtraction(conn1, conn2, target);
                log.info("[Merge 12/17] Merging workflow...");
                mergeWorkflow(conn1, conn2, target);

                log.info("[Merge 13/17] Merging gel_quantification...");
                mergeGelQuantification(conn1, conn2, target);
                log.info("[Merge 14/17] Merging assembly...");
                mergeAssembly(conn1, conn2, target);
                log.info("[Merge 15/17] Merging pcr...");
                mergePcr(conn1, conn2, target);
                log.info("[Merge 16/17] Merging cyclesequencing...");
                mergeCyclesequencing(conn1, conn2, target);

                log.info("[Merge 17/17] Merging traces and sequencing_result...");
                mergeTraces(conn1, conn2, target);
                mergeSequencingResult(conn1, conn2, target);

                log.info("[Merge] Committing transaction...");
                target.commit();
                log.info("[Merge] Merge completed successfully! All changes committed.");

            } catch (Exception e) {
                log.error("Merge failed, rolling back...", e);
                try { target.rollback(); } catch (SQLException re) { log.error("Rollback failed", re); }
                throw e;
            }
        }
    }

    // ─── Compute duplicate cocktail names for prefixing ──────────────

    private void computeCocktailDupNames(Connection conn1, Connection conn2) throws SQLException {
        // CS cocktail duplicate names
        Set<String> db1CsNames = new HashSet<>();
        try (Statement stmt = conn1.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM cyclesequencing_cocktail")) {
            while (rs.next()) { String n = rs.getString("name"); if (n != null) db1CsNames.add(n); }
        }
        try (Statement stmt = conn2.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM cyclesequencing_cocktail")) {
            while (rs.next()) {
                String n = rs.getString("name");
                if (n != null && db1CsNames.contains(n)) csCocktailDupNames.add(n);
            }
        }
        if (!csCocktailDupNames.isEmpty()) {
            log.info("CS cocktail duplicate names (will be prefixed): {}", csCocktailDupNames);
        }

        // PCR cocktail duplicate names
        Set<String> db1PcrNames = new HashSet<>();
        try (Statement stmt = conn1.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM pcr_cocktail")) {
            while (rs.next()) { String n = rs.getString("name"); if (n != null) db1PcrNames.add(n); }
        }
        try (Statement stmt = conn2.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM pcr_cocktail")) {
            while (rs.next()) {
                String n = rs.getString("name");
                if (n != null && db1PcrNames.contains(n)) pcrCocktailDupNames.add(n);
            }
        }
        if (!pcrCocktailDupNames.isEmpty()) {
            log.info("PCR cocktail duplicate names (will be prefixed): {}", pcrCocktailDupNames);
        }
    }

    // ─── Phase 1: Version and Properties ───────────────────────────────

    private void mergeVersionAndProperties(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Phase 1: Merging version and properties...");

        int version = getDatabaseVersion(conn1);
        try (PreparedStatement ps = target.prepareStatement("INSERT INTO databaseversion (version) VALUES (?)")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }

        String fullVersion = getStringProperty(conn1, "fullDatabaseVersion");
        long bgStarted = getLongProperty(conn1, "backgroundTasksStarted") +
                getLongProperty(conn2, "backgroundTasksStarted");
        long bgFailed = getLongProperty(conn1, "numberOfTimesBackgroundTasksFailed") +
                getLongProperty(conn2, "numberOfTimesBackgroundTasksFailed");

        try (PreparedStatement ps = target.prepareStatement("INSERT INTO properties (name, value) VALUES (?, ?)")) {
            ps.setString(1, "fullDatabaseVersion");
            ps.setString(2, fullVersion);
            ps.executeUpdate();

            ps.setString(1, "backgroundTasksStarted");
            ps.setString(2, String.valueOf(bgStarted));
            ps.executeUpdate();

            ps.setString(1, "numberOfTimesBackgroundTasksFailed");
            ps.setString(2, String.valueOf(bgFailed));
            ps.executeUpdate();
        }

        log.info("Phase 1 complete. Version={}, bgStarted={}, bgFailed={}", version, bgStarted, bgFailed);
    }

    // ─── Phase 2: Cocktail Deduplication ───────────────────────────────

    private void mergeCsCocktails(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Phase 2a: Merging cyclesequencing_cocktail...");

        String cols = "id, name, ddh2o, buffer, bigDye, notes, bufferConc, bigDyeConc, templateConc, " +
                "primerConc, primerAmount, extraItem, extraItemAmount, templateAmount";
        String compCols = "name, ddh2o, buffer, bigDye, notes, bufferConc, bigDyeConc, templateConc, " +
                "primerConc, primerAmount, extraItem, extraItemAmount, templateAmount";
        String insertSql = "INSERT INTO cyclesequencing_cocktail (" + cols + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        // Load all rows from both DBs
        List<Map<String, Object>> db1Rows = fetchAllRows(conn1, "SELECT " + cols + " FROM cyclesequencing_cocktail ORDER BY id");
        List<Map<String, Object>> db2Rows = fetchAllRows(conn2, "SELECT " + cols + " FROM cyclesequencing_cocktail ORDER BY id");

        // First pass: identify which DB2 rows are full duplicates of DB1 rows (using original names)
        Set<Integer> db2FullDupeIds = new HashSet<>();
        Map<Integer, Integer> db2ToDupDb1Id = new HashMap<>();
        for (Map<String, Object> db2Row : db2Rows) {
            int db2Id = ((Number) db2Row.get("id")).intValue();
            Map<String, Object> db2Comp = extractCompFields(db2Row, compCols.split(",\\s*"));
            for (Map<String, Object> db1Row : db1Rows) {
                Map<String, Object> db1Comp = extractCompFields(db1Row, compCols.split(",\\s*"));
                if (rowsEqual(db2Comp, db1Comp)) {
                    db2FullDupeIds.add(db2Id);
                    db2ToDupDb1Id.put(db2Id, ((Number) db1Row.get("id")).intValue());
                    break;
                }
            }
        }

        // Copy all from DB1 — prefix duplicate names only for rows whose name is shared
        // but where the DB2 counterpart with that name is NOT a full duplicate
        // (i.e., there exists at least one non-identical DB2 row with the same name)
        Set<String> namesNeedingPrefix = new HashSet<>();
        for (String dupName : csCocktailDupNames) {
            // Check if ANY DB2 row with this name is NOT a full duplicate
            for (Map<String, Object> db2Row : db2Rows) {
                Object n = db2Row.get("name");
                if (n != null && n.toString().equals(dupName)) {
                    int db2Id = ((Number) db2Row.get("id")).intValue();
                    if (!db2FullDupeIds.contains(db2Id)) {
                        namesNeedingPrefix.add(dupName);
                        break;
                    }
                }
            }
        }

        try (PreparedStatement ps = target.prepareStatement(insertSql)) {
            for (Map<String, Object> row : db1Rows) {
                prefixDupName(row, namesNeedingPrefix, db1Name);
                setRowParams(ps, row, cols.split(",\\s*"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.info("  Copied {} rows from DB1", db1Rows.size());

        // Get target comparison data (with prefixed names already applied)
        List<Map<String, Object>> targetCompRows = fetchAllRows(target, "SELECT id, " + compCols + " FROM cyclesequencing_cocktail ORDER BY id");

        // Process DB2
        int dupes = 0, inserted = 0;

        for (Map<String, Object> db2Row : db2Rows) {
            int db2Id = ((Number) db2Row.get("id")).intValue();

            if (db2FullDupeIds.contains(db2Id)) {
                // Full duplicate — map to the existing DB1 id (which is preserved in target)
                int db1Id = db2ToDupDb1Id.get(db2Id);
                cscocktailMap.put(db2Id, db1Id);
                log.debug("  CS cocktail DB2 id={} -> existing target id={} [FULL DUPLICATE]", db2Id, db1Id);
                dupes++;
            } else {
                // Not a full duplicate — prefix name if needed, then insert
                prefixDupName(db2Row, namesNeedingPrefix, db2Name);
                Map<String, Object> db2Comp = extractCompFields(db2Row, compCols.split(",\\s*"));

                // Check if it now matches something already in target (e.g. another DB2 row)
                boolean matched = false;
                for (Map<String, Object> targetRow : targetCompRows) {
                    Map<String, Object> targetComp = extractCompFields(targetRow, compCols.split(",\\s*"));
                    if (rowsEqual(db2Comp, targetComp)) {
                        int targetId = ((Number) targetRow.get("id")).intValue();
                        cscocktailMap.put(db2Id, targetId);
                        log.debug("  CS cocktail DB2 id={} -> existing target id={}", db2Id, targetId);
                        matched = true;
                        dupes++;
                        break;
                    }
                }

                if (!matched) {
                    String insertNoId = "INSERT INTO cyclesequencing_cocktail (" + compCols +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = target.prepareStatement(insertNoId, Statement.RETURN_GENERATED_KEYS)) {
                        setRowParams(ps, db2Comp, compCols.split(",\\s*"));
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (keys.next()) {
                                int newId = keys.getInt(1);
                                cscocktailMap.put(db2Id, newId);
                                Map<String, Object> newRow = new LinkedHashMap<>(db2Comp);
                                newRow.put("id", newId);
                                targetCompRows.add(newRow);
                                log.debug("  CS cocktail DB2 id={} -> new target id={}", db2Id, newId);
                            }
                        }
                    }
                    inserted++;
                }
            }
        }

        db1Counts.put("cyclesequencing_cocktail", (long) db1Rows.size());
        log.info("  DB2: {} duplicates, {} new insertions", dupes, inserted);
    }

    private void mergePcrCocktails(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Phase 2b: Merging pcr_cocktail...");

        String cols = "id, name, ddH20, buffer, mg, bsa, dNTP, taq, notes, bufferConc, mgConc, dNTPConc, " +
                "taqConc, templateConc, bsaConc, fwPrAmount, fwPrConc, revPrAmount, revPrConc, " +
                "extraItem, extraItemAmount, templateAmount";
        String compCols = "name, ddH20, buffer, mg, bsa, dNTP, taq, notes, bufferConc, mgConc, dNTPConc, " +
                "taqConc, templateConc, bsaConc, fwPrAmount, fwPrConc, revPrAmount, revPrConc, " +
                "extraItem, extraItemAmount, templateAmount";
        int colCount = cols.split(",\\s*").length;
        String placeholders = String.join(", ", Collections.nCopies(colCount, "?"));
        String insertSql = "INSERT INTO pcr_cocktail (" + cols + ") VALUES (" + placeholders + ")";

        // Load all rows from both DBs
        List<Map<String, Object>> db1Rows = fetchAllRows(conn1, "SELECT " + cols + " FROM pcr_cocktail ORDER BY id");
        List<Map<String, Object>> db2Rows = fetchAllRows(conn2, "SELECT " + cols + " FROM pcr_cocktail ORDER BY id");

        // First pass: identify which DB2 rows are full duplicates of DB1 rows (using original names)
        Set<Integer> db2FullDupeIds = new HashSet<>();
        Map<Integer, Integer> db2ToDupDb1Id = new HashMap<>();
        for (Map<String, Object> db2Row : db2Rows) {
            int db2Id = ((Number) db2Row.get("id")).intValue();
            Map<String, Object> db2Comp = extractCompFields(db2Row, compCols.split(",\\s*"));
            for (Map<String, Object> db1Row : db1Rows) {
                Map<String, Object> db1Comp = extractCompFields(db1Row, compCols.split(",\\s*"));
                if (rowsEqual(db2Comp, db1Comp)) {
                    db2FullDupeIds.add(db2Id);
                    db2ToDupDb1Id.put(db2Id, ((Number) db1Row.get("id")).intValue());
                    break;
                }
            }
        }

        // Determine which duplicate names actually need prefixing
        // (only if there's a non-identical DB2 row sharing that name)
        Set<String> namesNeedingPrefix = new HashSet<>();
        for (String dupName : pcrCocktailDupNames) {
            for (Map<String, Object> db2Row : db2Rows) {
                Object n = db2Row.get("name");
                if (n != null && n.toString().equals(dupName)) {
                    int db2Id = ((Number) db2Row.get("id")).intValue();
                    if (!db2FullDupeIds.contains(db2Id)) {
                        namesNeedingPrefix.add(dupName);
                        break;
                    }
                }
            }
        }

        // Copy all from DB1, prefixing only names that need it
        try (PreparedStatement ps = target.prepareStatement(insertSql)) {
            for (Map<String, Object> row : db1Rows) {
                prefixDupName(row, namesNeedingPrefix, db1Name);
                setRowParams(ps, row, cols.split(",\\s*"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        log.info("  Copied {} rows from DB1", db1Rows.size());

        // Get target comparison data (with prefixed names already applied)
        List<Map<String, Object>> targetCompRows = fetchAllRows(target, "SELECT id, " + compCols + " FROM pcr_cocktail ORDER BY id");

        // Process DB2
        int dupes = 0, inserted = 0;
        int compColCount = compCols.split(",\\s*").length;
        String compPlaceholders = String.join(", ", Collections.nCopies(compColCount, "?"));

        for (Map<String, Object> db2Row : db2Rows) {
            int db2Id = ((Number) db2Row.get("id")).intValue();

            if (db2FullDupeIds.contains(db2Id)) {
                // Full duplicate — map to the existing DB1 id (preserved in target)
                int db1Id = db2ToDupDb1Id.get(db2Id);
                pcrCocktailMap.put(db2Id, db1Id);
                log.debug("  PCR cocktail DB2 id={} -> existing target id={} [FULL DUPLICATE]", db2Id, db1Id);
                dupes++;
            } else {
                // Not a full duplicate — prefix name if needed, then insert
                prefixDupName(db2Row, namesNeedingPrefix, db2Name);
                Map<String, Object> db2Comp = extractCompFields(db2Row, compCols.split(",\\s*"));

                // Check if it now matches something already in target
                boolean matched = false;
                for (Map<String, Object> targetRow : targetCompRows) {
                    Map<String, Object> targetComp = extractCompFields(targetRow, compCols.split(",\\s*"));
                    if (rowsEqual(db2Comp, targetComp)) {
                        int targetId = ((Number) targetRow.get("id")).intValue();
                        pcrCocktailMap.put(db2Id, targetId);
                        log.debug("  PCR cocktail DB2 id={} -> existing target id={}", db2Id, targetId);
                        matched = true;
                        dupes++;
                        break;
                    }
                }

                if (!matched) {
                    String insertNoId = "INSERT INTO pcr_cocktail (" + compCols + ") VALUES (" + compPlaceholders + ")";
                    try (PreparedStatement ps = target.prepareStatement(insertNoId, Statement.RETURN_GENERATED_KEYS)) {
                        setRowParams(ps, db2Comp, compCols.split(",\\s*"));
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (keys.next()) {
                                int newId = keys.getInt(1);
                                pcrCocktailMap.put(db2Id, newId);
                                Map<String, Object> newRow = new LinkedHashMap<>(db2Comp);
                                newRow.put("id", newId);
                                targetCompRows.add(newRow);
                                log.debug("  PCR cocktail DB2 id={} -> new target id={}", db2Id, newId);
                            }
                        }
                    }
                    inserted++;
                }
            }
        }

        db1Counts.put("pcr_cocktail", (long) db1Rows.size());
        log.info("  DB2: {} duplicates, {} new insertions", dupes, inserted);
    }

    // ─── Phase 3: Thermocycle Hierarchy ────────────────────────────────

    private void mergeThermocycleHierarchy(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Phase 3: Merging thermocycle hierarchy...");

        // Load hierarchies
        List<ThermocycleHierarchy> h1 = loadThermocycleHierarchies(conn1);
        List<ThermocycleHierarchy> h2 = loadThermocycleHierarchies(conn2);

        // Copy all from DB1 to target (preserve IDs)
        for (ThermocycleHierarchy th : h1) {
            try (PreparedStatement ps = target.prepareStatement(
                    "INSERT INTO thermocycle (id, name, notes) VALUES (?, ?, ?)")) {
                ps.setInt(1, th.thermocycleId);
                ps.setString(2, th.name);
                ps.setString(3, th.notes);
                ps.executeUpdate();
            }
            for (CycleData cd : th.cycles) {
                try (PreparedStatement ps = target.prepareStatement(
                        "INSERT INTO cycle (id, thermocycleId, repeats) VALUES (?, ?, ?)")) {
                    ps.setInt(1, cd.cycleId);
                    ps.setInt(2, th.thermocycleId);
                    ps.setInt(3, cd.repeats);
                    ps.executeUpdate();
                }
                for (StateData sd : cd.states) {
                    try (PreparedStatement ps = target.prepareStatement(
                            "INSERT INTO state (id, temp, length, cycleId) VALUES (?, ?, ?, ?)")) {
                        ps.setInt(1, sd.stateId);
                        ps.setInt(2, sd.temp);
                        ps.setInt(3, sd.length);
                        ps.setInt(4, cd.cycleId);
                        ps.executeUpdate();
                    }
                }
            }
        }

        int db1ThermocycleCount = h1.size();
        int db1CycleCount = h1.stream().mapToInt(th -> th.cycles.size()).sum();
        int db1StateCount = h1.stream().flatMap(th -> th.cycles.stream()).mapToInt(c -> c.states.size()).sum();

        db1Counts.put("thermocycle", (long) db1ThermocycleCount);
        db1Counts.put("cycle", (long) db1CycleCount);
        db1Counts.put("state", (long) db1StateCount);

        log.info("  Copied from DB1: {} thermocycles, {} cycles, {} states",
                db1ThermocycleCount, db1CycleCount, db1StateCount);

        // Now reload target hierarchies for comparison
        List<ThermocycleHierarchy> targetH = loadThermocycleHierarchies(target);

        int dupes = 0, newInserts = 0;

        for (ThermocycleHierarchy th2 : h2) {
            boolean matched = false;

            for (ThermocycleHierarchy thT : targetH) {
                if (th2.structurallyEquals(thT)) {
                    // Matched! Create mappings
                    thermocycleMap.put(th2.thermocycleId, thT.thermocycleId);
                    for (int i = 0; i < th2.cycles.size(); i++) {
                        cycleMap.put(th2.cycles.get(i).cycleId, thT.cycles.get(i).cycleId);
                        for (int j = 0; j < th2.cycles.get(i).states.size(); j++) {
                            stateMap.put(th2.cycles.get(i).states.get(j).stateId,
                                    thT.cycles.get(i).states.get(j).stateId);
                        }
                    }

                    // Handle notes concatenation if different
                    if (!Objects.equals(th2.notes, thT.notes)) {
                        String combinedNotes = (thT.notes != null ? thT.notes : "") + "\n" + (th2.notes != null ? th2.notes : "");
                        try (PreparedStatement ps = target.prepareStatement(
                                "UPDATE thermocycle SET notes = ? WHERE id = ?")) {
                            ps.setString(1, combinedNotes.trim());
                            ps.setInt(2, thT.thermocycleId);
                            ps.executeUpdate();
                        }
                    }

                    log.debug("  Thermocycle DB2 id={} -> existing target id={}", th2.thermocycleId, thT.thermocycleId);
                    matched = true;
                    dupes++;
                    break;
                }
            }

            if (!matched) {
                // Insert entire hierarchy with new IDs
                int newThermocycleId;
                try (PreparedStatement ps = target.prepareStatement(
                        "INSERT INTO thermocycle (name, notes) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, th2.name);
                    ps.setString(2, th2.notes);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        newThermocycleId = keys.getInt(1);
                    }
                }
                thermocycleMap.put(th2.thermocycleId, newThermocycleId);

                for (CycleData cd : th2.cycles) {
                    int newCycleId;
                    try (PreparedStatement ps = target.prepareStatement(
                            "INSERT INTO cycle (thermocycleId, repeats) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, newThermocycleId);
                        ps.setInt(2, cd.repeats);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            keys.next();
                            newCycleId = keys.getInt(1);
                        }
                    }
                    cycleMap.put(cd.cycleId, newCycleId);

                    for (StateData sd : cd.states) {
                        int newStateId;
                        try (PreparedStatement ps = target.prepareStatement(
                                "INSERT INTO state (temp, length, cycleId) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                            ps.setInt(1, sd.temp);
                            ps.setInt(2, sd.length);
                            ps.setInt(3, newCycleId);
                            ps.executeUpdate();
                            try (ResultSet keys = ps.getGeneratedKeys()) {
                                keys.next();
                                newStateId = keys.getInt(1);
                            }
                        }
                        stateMap.put(sd.stateId, newStateId);
                    }
                }

                log.debug("  Thermocycle DB2 id={} -> new target id={}", th2.thermocycleId, newThermocycleId);
                newInserts++;
            }
        }

        log.info("  DB2: {} duplicate hierarchies, {} new hierarchies inserted", dupes, newInserts);
    }

    // ─── Phase 4: Sequential Copy with Offset ──────────────────────────

    /**
     * Generic simple table merge for tables with no special FK handling beyond offset.
     * offsetFkIndices: indices in colNames (0-based) for FK columns that use offset
     * mappedFkIndices: indices in colNames (0-based) for FK columns that use thermocycle mapping
     */
    private void mergeSimpleTable(Connection conn1, Connection conn2, Connection target,
                                  String tableName, String cols, String insertSql,
                                  String[] nonIdCols, int[] offsetFkIndices, int[] mappedFkIndices) throws SQLException {
        log.info("Merging table: {}", tableName);
        String[] colNames = cols.split(",\\s*");

        // Stream DB1
        long db1Count = streamingCopy(conn1, "SELECT " + cols + " FROM " + tableName + " ORDER BY id",
                target, insertSql, colNames, null);
        db1Counts.put(tableName, db1Count);
        log.info("  Copied {} rows from DB1", db1Count);

        // Stream DB2 with offset and FK mapping
        final long offset = db1Count;
        long db2Count = streamingCopy(conn2, "SELECT " + cols + " FROM " + tableName + " ORDER BY id",
                target, insertSql, colNames, row -> {
                    int origId = ((Number) row.get("id")).intValue();
                    row.put("id", (int) (origId + offset));
                    for (int idx : mappedFkIndices) {
                        String fkCol = colNames[idx].trim().toLowerCase();
                        Object val = row.get(fkCol);
                        if (val != null) {
                            int fkId = ((Number) val).intValue();
                            if (fkId >= 0 && thermocycleMap.containsKey(fkId)) {
                                row.put(fkCol, thermocycleMap.get(fkId));
                            }
                        }
                    }
                });

        log.info("  Copied {} rows from DB2 (offset={})", db2Count, db1Count);
    }

    private void mergeGelimages(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: gelimages");

        String cols = "id, name, plate, imageData, notes";
        String insertSql = "INSERT INTO gelimages (id, name, plate, imageData, notes) VALUES (?, ?, ?, ?, ?)";
        String[] colNames = cols.split(",\\s*");

        long db1Count = streamingCopy(conn1, "SELECT " + cols + " FROM gelimages ORDER BY id",
                target, insertSql, colNames, null);
        db1Counts.put("gelimages", db1Count);
        final long plateOffset = db1Counts.getOrDefault("plate", 0L);

        long db2Count = streamingCopy(conn2, "SELECT " + cols + " FROM gelimages ORDER BY id",
                target, insertSql, colNames, row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1Count);
                    applyOffset(row, "plate", plateOffset);
                });

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
    }

    private void mergePlate(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: plate");

        String cols = "id, name, date, size, type, thermocycle";
        String insertSql = "INSERT INTO plate (id, name, date, size, type, thermocycle) VALUES (?, ?, ?, ?, ?, ?)";
        String[] colNames = cols.split(",\\s*");

        long db1Count = streamingCopy(conn1, "SELECT " + cols + " FROM plate ORDER BY id",
                target, insertSql, colNames, null);
        db1Counts.put("plate", db1Count);

        long db2Count = streamingCopy(conn2, "SELECT " + cols + " FROM plate ORDER BY id",
                target, insertSql, colNames, row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1Count);
                    Object tcVal = row.get("thermocycle");
                    if (tcVal != null) {
                        int tcId = ((Number) tcVal).intValue();
                        if (tcId >= 0 && thermocycleMap.containsKey(tcId)) {
                            row.put("thermocycle", thermocycleMap.get(tcId));
                        }
                    }
                });

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
    }

    private void mergeExtraction(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: extraction");

        String cols = "id, date, method, volume, dilution, concentrationStored, concentration, parent, " +
                "sampleId, extractionId, control, plate, location, technician, notes, " +
                "extractionBarcode, previousPlate, previousWell, gelimage";
        String insertSql = "INSERT INTO extraction (id, date, method, volume, dilution, concentrationStored, " +
                "concentration, parent, sampleId, extractionId, control, plate, location, technician, " +
                "notes, extractionBarcode, previousPlate, previousWell, gelimage) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String[] colNames = cols.split(",\\s*");

        long db1Count = streamingCopy(conn1, "SELECT " + cols + " FROM extraction ORDER BY id",
                target, insertSql, colNames, null);
        db1Counts.put("extraction", db1Count);
        final long plateOffset = db1Counts.getOrDefault("plate", 0L);

        long db2Count = streamingCopy(conn2, "SELECT " + cols + " FROM extraction ORDER BY id",
                target, insertSql, colNames, row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1Count);
                    applyOffset(row, "plate", plateOffset);
                });

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
    }

    private void mergeWorkflow(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: workflow");

        String cols = "id, name, date, extractionId, locus";
        String insertSql = "INSERT INTO workflow (id, name, date, extractionId, locus) VALUES (?, ?, ?, ?, ?)";
        String[] colNames = cols.split(",\\s*");

        // Get DB1 max workflow number per locus for name offsetting
        Map<String, Integer> db1MaxPerLocus = getWorkflowMaxPerLocus(conn1);

        long db1Count = streamingCopy(conn1, "SELECT " + cols + " FROM workflow ORDER BY id",
                target, insertSql, colNames, null);
        db1Counts.put("workflow", db1Count);
        final long extractionOffset = db1Counts.getOrDefault("extraction", 0L);

        long db2Count = streamingCopy(conn2, "SELECT " + cols + " FROM workflow ORDER BY id",
                target, insertSql, colNames, row -> {
                    int origId = ((Number) row.get("id")).intValue();
                    row.put("id", (int) (origId + db1Count));
                    applyOffset(row, "extractionid", extractionOffset);

                    // Rename workflow
                    String name = row.get("name") != null ? row.get("name").toString() : null;
                    if (name != null) {
                        Matcher m = WORKFLOW_NAME_PATTERN.matcher(name);
                        if (m.matches()) {
                            String locus = m.group(1);
                            int num = Integer.parseInt(m.group(2));
                            int wfOffset = db1MaxPerLocus.getOrDefault(locus, 0);
                            String newName = locus + "_workflow" + (num + wfOffset);
                            row.put("name", newName);
                            workflowNameMap.put(origId, newName);
                        }
                    }
                });

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
    }

    private void mergeGelQuantification(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: gel_quantification");

        String cols = "id, date, extractionId, plate, location, technician, notes, volume, gelImage, " +
                "gelBuffer, gelConc, stain, stainConc, stainMethod, gelLadder, threshold, aboveThreshold";
        String insertSql = "INSERT INTO gel_quantification (id, date, extractionId, plate, location, technician, " +
                "notes, volume, gelImage, gelBuffer, gelConc, stain, stainConc, stainMethod, gelLadder, " +
                "threshold, aboveThreshold) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String[] colNames = cols.split(",\\s*");

        long db1Count = streamingCopy(conn1, "SELECT " + cols + " FROM gel_quantification ORDER BY id",
                target, insertSql, colNames, null);
        db1Counts.put("gel_quantification", db1Count);
        final long extractionOffset = db1Counts.getOrDefault("extraction", 0L);
        final long plateOffset = db1Counts.getOrDefault("plate", 0L);

        long db2Count = streamingCopy(conn2, "SELECT " + cols + " FROM gel_quantification ORDER BY id",
                target, insertSql, colNames, row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1Count);
                    applyOffset(row, "extractionid", extractionOffset);
                    applyOffset(row, "plate", plateOffset);
                });

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
    }

    private void mergeAssembly(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: assembly");

        String cols = "id, extraction_id, workflow, progress, consensus, params, coverage, disagreements, " +
                "edits, reference_seq_id, confidence_scores, trim_params_fwd, trim_params_rev, " +
                "other_processing_fwd, other_processing_rev, date, submitted, notes, editrecord, " +
                "technician, bin, ambiguities, failure_reason, failure_notes";
        String placeholders = String.join(", ", Collections.nCopies(cols.split(",\\s*").length, "?"));
        String insertSql = "INSERT INTO assembly (" + cols + ") VALUES (" + placeholders + ")";
        String[] colNames = cols.split(",\\s*");

        long db1Count = streamingCopy(conn1, "SELECT " + cols + " FROM assembly ORDER BY id",
                target, insertSql, colNames, null);
        db1Counts.put("assembly", db1Count);
        final long workflowOffset = db1Counts.getOrDefault("workflow", 0L);
        final long frOffset = db1Counts.getOrDefault("failure_reason", 0L);

        long db2Count = streamingCopy(conn2, "SELECT " + cols + " FROM assembly ORDER BY id",
                target, insertSql, colNames, row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1Count);
                    applyOffset(row, "workflow", workflowOffset);
                    Object frVal = row.get("failure_reason");
                    if (frVal != null) {
                        row.put("failure_reason", ((Number) frVal).intValue() + (int) frOffset);
                    }
                });

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
    }

    private void mergePcr(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: pcr");

        String cols = "id, prName, prSequence, date, workflow, plate, location, cocktail, progress, " +
                "extractionId, thermocycle, cleanupPerformed, cleanupMethod, technician, notes, " +
                "revPrName, revPrSequence, gelimage";
        String placeholders = String.join(", ", Collections.nCopies(cols.split(",\\s*").length, "?"));
        String insertSql = "INSERT INTO pcr (" + cols + ") VALUES (" + placeholders + ")";
        String[] colNames = cols.split(",\\s*");

        long db1Count = streamingCopy(conn1, "SELECT " + cols + " FROM pcr ORDER BY id",
                target, insertSql, colNames, null);
        db1Counts.put("pcr", db1Count);
        final long workflowOffset = db1Counts.getOrDefault("workflow", 0L);
        final long plateOffset = db1Counts.getOrDefault("plate", 0L);

        long db2Count = streamingCopy(conn2, "SELECT " + cols + " FROM pcr ORDER BY id",
                target, insertSql, colNames, row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1Count);
                    applyOffset(row, "workflow", workflowOffset);
                    applyOffset(row, "plate", plateOffset);
                    applyMapping(row, "cocktail", pcrCocktailMap);
                    Object tcVal = row.get("thermocycle");
                    if (tcVal != null) {
                        int tcId = ((Number) tcVal).intValue();
                        if (tcId >= 0 && thermocycleMap.containsKey(tcId)) {
                            row.put("thermocycle", thermocycleMap.get(tcId));
                        }
                    }
                });

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
    }

    private void mergeCyclesequencing(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: cyclesequencing");

        String cols = "id, primerName, primerSequence, technician, notes, date, workflow, thermocycle, " +
                "plate, location, extractionId, cocktail, progress, cleanupPerformed, cleanupMethod, " +
                "direction, gelimage";
        String placeholders = String.join(", ", Collections.nCopies(cols.split(",\\s*").length, "?"));
        String insertSql = "INSERT INTO cyclesequencing (" + cols + ") VALUES (" + placeholders + ")";
        String[] colNames = cols.split(",\\s*");

        long db1Count = streamingCopy(conn1, "SELECT " + cols + " FROM cyclesequencing ORDER BY id",
                target, insertSql, colNames, null);
        db1Counts.put("cyclesequencing", db1Count);
        final long workflowOffset = db1Counts.getOrDefault("workflow", 0L);
        final long plateOffset = db1Counts.getOrDefault("plate", 0L);

        long db2Count = streamingCopy(conn2, "SELECT " + cols + " FROM cyclesequencing ORDER BY id",
                target, insertSql, colNames, row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1Count);
                    applyOffset(row, "workflow", workflowOffset);
                    applyOffset(row, "plate", plateOffset);
                    applyMapping(row, "cocktail", cscocktailMap);
                    Object tcVal = row.get("thermocycle");
                    if (tcVal != null) {
                        int tcId = ((Number) tcVal).intValue();
                        if (tcId >= 0 && thermocycleMap.containsKey(tcId)) {
                            row.put("thermocycle", thermocycleMap.get(tcId));
                        }
                    }
                });

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
    }

    private void mergeTraces(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: traces");

        String cols = "id, reaction, name, data";
        String insertSql = "INSERT INTO traces (id, reaction, name, data) VALUES (?, ?, ?, ?)";
        String[] colNames = cols.split(",\\s*");

        long db1Count = streamingCopy(conn1, "SELECT " + cols + " FROM traces ORDER BY id",
                target, insertSql, colNames, null);
        db1Counts.put("traces", db1Count);
        final long csOffset = db1Counts.getOrDefault("cyclesequencing", 0L);

        long db2Count = streamingCopy(conn2, "SELECT " + cols + " FROM traces ORDER BY id",
                target, insertSql, colNames, row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1Count);
                    applyOffset(row, "reaction", csOffset);
                });

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
    }

    private void mergeSequencingResult(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: sequencing_result");

        String cols = "reaction, assembly";
        String insertSql = "INSERT INTO sequencing_result (reaction, assembly) VALUES (?, ?)";
        String[] colNames = cols.split(",\\s*");

        long db1Count = streamingCopy(conn1, "SELECT " + cols + " FROM sequencing_result",
                target, insertSql, colNames, null);
        db1Counts.put("sequencing_result", db1Count);
        final long csOffset = db1Counts.getOrDefault("cyclesequencing", 0L);
        final long assemblyOffset = db1Counts.getOrDefault("assembly", 0L);

        long db2Count = streamingCopy(conn2, "SELECT " + cols + " FROM sequencing_result",
                target, insertSql, colNames, row -> {
                    applyOffset(row, "reaction", csOffset);
                    applyOffset(row, "assembly", assemblyOffset);
                });

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
    }

    // ─── Helper methods ────────────────────────────────────────────────

    private void applyOffset(Map<String, Object> row, String colName, long offset) {
        Object val = row.get(colName.toLowerCase());
        if (val != null) {
            row.put(colName.toLowerCase(), ((Number) val).intValue() + (int) offset);
        }
    }

    private void applyMapping(Map<String, Object> row, String colName, Map<Integer, Integer> mapping) {
        Object val = row.get(colName.toLowerCase());
        if (val != null) {
            int origId = ((Number) val).intValue();
            if (mapping.containsKey(origId)) {
                row.put(colName.toLowerCase(), mapping.get(origId));
            }
        }
    }

    private Map<String, Object> extractCompFields(Map<String, Object> row, String[] fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String f : fields) {
            String key = f.trim().toLowerCase();
            result.put(key, row.get(key));
        }
        return result;
    }

    /**
     * If the row's "name" field is in the duplicate names set, prepend the dbName prefix.
     * Only modifies the name if it matches a known duplicate; leaves non-duplicates as-is.
     */
    private void prefixDupName(Map<String, Object> row, Set<String> dupNames, String dbName) {
        Object nameVal = row.get("name");
        if (nameVal != null && dupNames.contains(nameVal.toString())) {
            row.put("name", dbName + " " + nameVal);
        }
    }

    private void setRowParams(PreparedStatement ps, Map<String, Object> row, String[] colNames) throws SQLException {
        for (int i = 0; i < colNames.length; i++) {
            String col = colNames[i].trim().toLowerCase();
            Object val = row.get(col);
            if (val == null) {
                ps.setNull(i + 1, Types.NULL);
            } else if (val instanceof Integer) {
                ps.setInt(i + 1, (Integer) val);
            } else if (val instanceof Long) {
                ps.setLong(i + 1, (Long) val);
            } else if (val instanceof Double) {
                ps.setDouble(i + 1, (Double) val);
            } else if (val instanceof Float) {
                ps.setFloat(i + 1, (Float) val);
            } else if (val instanceof byte[]) {
                ps.setBytes(i + 1, (byte[]) val);
            } else if (val instanceof java.sql.Timestamp) {
                ps.setTimestamp(i + 1, (java.sql.Timestamp) val);
            } else if (val instanceof java.sql.Date) {
                ps.setDate(i + 1, (java.sql.Date) val);
            } else {
                ps.setString(i + 1, val.toString());
            }
        }
    }
}
