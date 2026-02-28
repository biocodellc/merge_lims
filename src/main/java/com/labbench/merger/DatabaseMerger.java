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

    // DB1 max ID per table (used as offset for DB2 ids to avoid collisions)
    private final Map<String, Long> db1MaxIds = new LinkedHashMap<>();

    // Workflow name remapping for DB2: db2 workflow id -> new name
    private final Map<Integer, String> workflowNameMap = new HashMap<>();

    // IDs to skip during merge (plate/location dedup — keep highest id only)
    private final Set<Integer> extractionSkipIds = new HashSet<>();
    private final Set<Integer> pcrSkipIds = new HashSet<>();
    private final Set<Integer> csSkipIds = new HashSet<>();

    // Test mode: limit rows copied per table (0 = no limit)
    private int testRowLimit = 0;

    /**
     * Append LIMIT clause to a SELECT query when in test mode.
     */
    private String limitSql(String sql) {
        if (testRowLimit > 0) {
            return sql + " LIMIT " + testRowLimit;
        }
        return sql;
    }

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
        boolean skipSchema = false;
        boolean testMode = false;
        boolean resume = false;
        boolean serverSide = false;
        boolean serverSideDb2 = false;

        // First arg is always the mode
        mode = args[0].toLowerCase();

        // Scan for flags anywhere in args
        for (String arg : args) {
            if ("--skip-schema".equals(arg)) {
                skipSchema = true;
            }
            if ("--test".equals(arg)) {
                testMode = true;
            }
            if ("--resume".equals(arg)) {
                resume = true;
            }
            if ("--server-side".equals(arg)) {
                serverSide = true;
            }
            if ("--server-side-db2".equals(arg)) {
                serverSideDb2 = true;
            }
        }

        // Determine properties file path: second arg if not a URL and not a flag, otherwise default
        String propsPath = "merger.properties";
        if (args.length >= 2 && !args[1].startsWith("jdbc:") && !args[1].startsWith("--")) {
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

                // Optional: skip schema creation
                String skipSchemaProp = props.getProperty("skip.schema");
                if ("true".equalsIgnoreCase(skipSchemaProp)) skipSchema = true;

                // Optional: test mode (limit rows)
                String testModeProp = props.getProperty("test.mode");
                if ("true".equalsIgnoreCase(testModeProp)) testMode = true;

                // Optional: resume mode
                String resumeProp = props.getProperty("resume");
                if ("true".equalsIgnoreCase(resumeProp)) resume = true;

                // Optional: server-side transfer
                String serverSideProp = props.getProperty("server.side");
                if ("true".equalsIgnoreCase(serverSideProp)) serverSide = true;

                // Optional: server-side transfer for DB2
                String serverSideDb2Prop = props.getProperty("server.side.db2");
                if ("true".equalsIgnoreCase(serverSideDb2Prop)) serverSideDb2 = true;

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
                                   targetUrl, targetUsername, targetPassword,
                                   skipSchema, testMode, resume, serverSide, serverSideDb2);
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
        System.out.println();
        System.out.println("  # Skip schema creation (tables must already exist):");
        System.out.println("  skip.schema=true");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --skip-schema   Skip CREATE TABLE statements (tables must already exist)");
        System.out.println("  --test          Test mode: copy only 10 rows per table (tier 1 and above)");
        System.out.println("  --resume        Resume a previously interrupted merge from where it left off");
        System.out.println("  --server-side   Use INSERT INTO...SELECT for DB1 tables when DB1 and target are on the same MySQL server");
        System.out.println("  --server-side-db2  Use INSERT INTO...SELECT for DB2 tables with simple offsets (same server required)");
    }

    // ─── Connection helper ─────────────────────────────────────────────

    private Connection connect(String url, String username, String password) throws SQLException {
        if (url.startsWith("jdbc:sqlite:")) {
            return DriverManager.getConnection(url);
        } else {
            // Append socket and connect timeout for MySQL connections
            String separator = url.contains("?") ? "&" : "?";
            String timedUrl = url + separator + "socketTimeout=90000&connectTimeout=30000";
            if (username != null && password != null) {
                return DriverManager.getConnection(timedUrl, username, password);
            } else {
                return DriverManager.getConnection(timedUrl);
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

    /**
     * Extract host:port from a MySQL JDBC URL.
     * e.g. "jdbc:mysql://localhost:3306/mydb?param=val" -> "localhost:3306"
     * Returns null for non-MySQL URLs.
     */
    private String mysqlHostPort(String url) {
        if (url == null || !url.startsWith("jdbc:mysql://")) return null;
        String after = url.substring("jdbc:mysql://".length()); // "host:port/db?params"
        int slashIdx = after.indexOf('/');
        return slashIdx > 0 ? after.substring(0, slashIdx) : after;
    }

    /**
     * Test whether the target connection can SELECT from the DB1 database.
     */
    private boolean testCrossDbAccess(Connection target, String db1Schema) {
        try (Statement stmt = target.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1 FROM " + db1Schema + ".databaseversion LIMIT 1")) {
            return true;
        } catch (SQLException e) {
            log.warn("Cross-database access test failed (target cannot SELECT from {}): {}", db1Schema, e.getMessage());
            return false;
        }
    }

    /** Batch size for server-side INSERT INTO...SELECT to avoid filling temp disk */
    private static final int SERVER_SIDE_BATCH = 20000;

    /**
     * Server-side INSERT INTO ... SELECT for a DB1 table. Data stays entirely within
     * the MySQL server — no transfer through the Java process.
     *
     * For tables with an id column, copies in batches of SERVER_SIDE_BATCH rows using
     * WHERE id BETWEEN to avoid MySQL creating huge temp files for BLOB-heavy tables.
     *
     * @param target     target connection
     * @param db1Schema  DB1 schema/database name
     * @param tableName  table to copy
     * @param cols       column list
     * @param whereClause optional WHERE clause (e.g. "WHERE id NOT IN (...)")  or null
     * @return number of rows inserted
     */
    private long serverSideCopyDb1(Connection target, String db1Schema, String tableName,
                                    String cols, String whereClause) throws SQLException {
        String targetSchema = dbNameFromUrl(target.getMetaData().getURL());
        boolean hasId = cols.toLowerCase().startsWith("id,") || cols.toLowerCase().startsWith("id ");

        if (!hasId) {
            // No id column (e.g. sequencing_result) — single statement
            String sql = "INSERT INTO " + targetSchema + "." + tableName + " (" + cols + ") " +
                    "SELECT " + cols + " FROM " + db1Schema + "." + tableName +
                    (whereClause != null ? " " + whereClause : "") +
                    (testRowLimit > 0 ? " LIMIT " + testRowLimit : "");
            log.debug("  Server-side SQL: {}", sql);
            try (Statement stmt = target.createStatement()) {
                return stmt.executeUpdate(sql);
            }
        }

        // Determine the ID range to copy
        long minId = 0;
        long maxIdVal = 0;
        String rangeWhere = whereClause != null ? whereClause : "";
        String rangeSql = "SELECT MIN(id), MAX(id) FROM " + db1Schema + "." + tableName +
                (rangeWhere.isEmpty() ? "" : " " + rangeWhere);
        try (Statement stmt = target.createStatement();
             ResultSet rs = stmt.executeQuery(rangeSql)) {
            if (rs.next()) {
                minId = rs.getLong(1);
                maxIdVal = rs.getLong(2);
            }
        }

        if (maxIdVal == 0) {
            log.info("  Server-side: no rows to copy from {}.{}", db1Schema, tableName);
            return 0;
        }

        // Copy in batches by ID range
        long totalRows = 0;
        long batchLimit = testRowLimit > 0 ? testRowLimit : Long.MAX_VALUE;
        for (long batchStart = minId; batchStart <= maxIdVal && totalRows < batchLimit; batchStart += SERVER_SIDE_BATCH) {
            long batchEnd = Math.min(batchStart + SERVER_SIDE_BATCH - 1, maxIdVal);

            // Build WHERE clause: combine id range with any existing where conditions
            String batchWhere;
            if (rangeWhere.isEmpty()) {
                batchWhere = "WHERE id BETWEEN " + batchStart + " AND " + batchEnd;
            } else if (rangeWhere.toUpperCase().startsWith("WHERE ")) {
                batchWhere = "WHERE id BETWEEN " + batchStart + " AND " + batchEnd +
                        " AND (" + rangeWhere.substring(6) + ")";
            } else {
                batchWhere = "WHERE id BETWEEN " + batchStart + " AND " + batchEnd +
                        " AND (" + rangeWhere + ")";
            }

            String sql = "INSERT INTO " + targetSchema + "." + tableName + " (" + cols + ") " +
                    "SELECT " + cols + " FROM " + db1Schema + "." + tableName +
                    " " + batchWhere + " ORDER BY id";
            if (testRowLimit > 0) {
                long remaining = batchLimit - totalRows;
                sql += " LIMIT " + remaining;
            }

            log.debug("  Server-side batch SQL: {}", sql);
            try (Statement stmt = target.createStatement()) {
                long rows = stmt.executeUpdate(sql);
                totalRows += rows;
                target.commit();
            }

            if (totalRows % (SERVER_SIDE_BATCH * 5) == 0 || batchEnd >= maxIdVal) {
                log.info("    ... {} rows copied server-side (id range {}-{})", totalRows, minId, batchEnd);
            }
        }

        return totalRows;
    }

    /**
     * Server-side INSERT INTO...SELECT for a DB2 table with simple column offsets.
     * Builds a SELECT expression that applies arithmetic offsets inline, e.g.:
     *   INSERT INTO target.table (id, col1, fk1, fk2)
     *   SELECT id + 1000, col1, fk1 + 500, fk2 + 300 FROM db2.table
     *
     * @param target       target connection
     * @param db2Schema    DB2 schema/database name
     * @param tableName    table to copy
     * @param cols         column list (comma-separated)
     * @param offsetMap    map of column name (lowercase) -> offset to apply
     * @param whereClause  optional WHERE clause or null
     * @return number of rows inserted
     */
    private long serverSideCopyDb2(Connection target, String db2Schema, String tableName,
                                    String cols, Map<String, Long> offsetMap,
                                    String whereClause) throws SQLException {
        String targetSchema = dbNameFromUrl(target.getMetaData().getURL());
        String[] colArr = cols.split(",\\s*");
        boolean hasId = colArr[0].trim().equalsIgnoreCase("id");

        // Build SELECT expression list with offsets applied
        StringBuilder selectExpr = new StringBuilder();
        for (int i = 0; i < colArr.length; i++) {
            if (i > 0) selectExpr.append(", ");
            String col = colArr[i].trim();
            String colLower = col.toLowerCase();
            Long offset = offsetMap.get(colLower);
            if (offset != null && offset != 0) {
                // Apply offset, handling NULLs: IFNULL leaves NULL as NULL
                selectExpr.append(col).append(" + ").append(offset);
            } else {
                selectExpr.append(col);
            }
        }

        if (!hasId) {
            // No id column — single statement
            String sql = "INSERT INTO " + targetSchema + "." + tableName + " (" + cols + ") " +
                    "SELECT " + selectExpr + " FROM " + db2Schema + "." + tableName +
                    (whereClause != null ? " " + whereClause : "") +
                    (testRowLimit > 0 ? " LIMIT " + testRowLimit : "");
            log.debug("  Server-side DB2 SQL: {}", sql);
            try (Statement stmt = target.createStatement()) {
                return stmt.executeUpdate(sql);
            }
        }

        // Batched copy by ID range
        long minId = 0;
        long maxIdVal = 0;
        String rangeWhere = whereClause != null ? whereClause : "";
        String rangeSql = "SELECT MIN(id), MAX(id) FROM " + db2Schema + "." + tableName +
                (rangeWhere.isEmpty() ? "" : " " + rangeWhere);
        try (Statement stmt = target.createStatement();
             ResultSet rs = stmt.executeQuery(rangeSql)) {
            if (rs.next()) {
                minId = rs.getLong(1);
                maxIdVal = rs.getLong(2);
            }
        }

        if (maxIdVal == 0) {
            log.info("  Server-side DB2: no rows to copy from {}.{}", db2Schema, tableName);
            return 0;
        }

        long totalRows = 0;
        long batchLimit = testRowLimit > 0 ? testRowLimit : Long.MAX_VALUE;
        for (long batchStart = minId; batchStart <= maxIdVal && totalRows < batchLimit; batchStart += SERVER_SIDE_BATCH) {
            long batchEnd = Math.min(batchStart + SERVER_SIDE_BATCH - 1, maxIdVal);

            String batchWhere;
            if (rangeWhere.isEmpty()) {
                batchWhere = "WHERE id BETWEEN " + batchStart + " AND " + batchEnd;
            } else if (rangeWhere.toUpperCase().startsWith("WHERE ")) {
                batchWhere = "WHERE id BETWEEN " + batchStart + " AND " + batchEnd +
                        " AND (" + rangeWhere.substring(6) + ")";
            } else {
                batchWhere = "WHERE id BETWEEN " + batchStart + " AND " + batchEnd +
                        " AND (" + rangeWhere + ")";
            }

            String sql = "INSERT INTO " + targetSchema + "." + tableName + " (" + cols + ") " +
                    "SELECT " + selectExpr + " FROM " + db2Schema + "." + tableName +
                    " " + batchWhere + " ORDER BY id";
            if (testRowLimit > 0) {
                long remaining = batchLimit - totalRows;
                sql += " LIMIT " + remaining;
            }

            log.debug("  Server-side DB2 batch SQL: {}", sql);
            try (Statement stmt = target.createStatement()) {
                long rows = stmt.executeUpdate(sql);
                totalRows += rows;
                target.commit();
            }

            if (totalRows % (SERVER_SIDE_BATCH * 5) == 0 || batchEnd >= maxIdVal) {
                log.info("    ... {} rows copied server-side from DB2 (id range {}-{})", totalRows, minId, batchEnd);
            }
        }

        return totalRows;
    }
    private void serverSidePrefixNames(Connection target, String tableName, Set<String> dupNames, String prefix) throws SQLException {
        if (dupNames.isEmpty()) return;
        // Build WHERE name IN (...)
        StringBuilder inClause = new StringBuilder("(");
        int i = 0;
        for (String name : dupNames) {
            if (i > 0) inClause.append(", ");
            inClause.append("'").append(name.replace("'", "''")).append("'");
            i++;
        }
        inClause.append(")");

        String sql = "UPDATE " + tableName + " SET name = CONCAT('" + prefix.replace("'", "''") +
                ":', name) WHERE name IN " + inClause +
                " AND name NOT LIKE '%:%'";  // Don't double-prefix
        log.debug("  Prefix SQL: {}", sql);
        try (Statement stmt = target.createStatement()) {
            int updated = stmt.executeUpdate(sql);
            if (updated > 0) {
                log.info("  Prefixed {} duplicate {} names with '{}'", updated, tableName, prefix);
            }
        }
    }

    // Instance flag: whether server-side transfer is active for this merge
    private boolean useServerSide = false;
    private String db1Schema = null;
    private boolean useServerSideDb2 = false;
    private String db2Schema = null;

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
        // For MySQL, DATE columns cannot have CURRENT_TIMESTAMP default; use DATETIME instead
        String dateDefault = isSQLite(targetUrl) ? "DATE DEFAULT CURRENT_TIMESTAMP" : "DATETIME DEFAULT CURRENT_TIMESTAMP";

        // For MySQL, test if we have REFERENCES privilege. If not, create tables without FK constraints.
        boolean includeForeignKeys = true;
        if (!isSQLite(targetUrl)) {
            includeForeignKeys = testReferencesPrivilege(target);
            if (!includeForeignKeys) {
                log.warn("REFERENCES privilege not available — creating tables without FOREIGN KEY constraints");
            }
        }

        // Helper to conditionally include FK clause
        String fk_cycle_tc = includeForeignKeys ? ", FOREIGN KEY (thermocycleId) REFERENCES thermocycle(id)" : "";
        String fk_state_cy = includeForeignKeys ? ", FOREIGN KEY (cycleId) REFERENCES cycle(id)" : "";
        String fk_gelimg_pl = includeForeignKeys ? ", FOREIGN KEY (plate) REFERENCES plate(id)" : "";
        String fk_ext_pl = includeForeignKeys ? ", FOREIGN KEY (plate) REFERENCES plate(id)" : "";
        String fk_wf_ext = includeForeignKeys ? ", FOREIGN KEY (extractionId) REFERENCES extraction(id)" : "";
        String fk_asm_wf = includeForeignKeys ? ", FOREIGN KEY (workflow) REFERENCES workflow(id), FOREIGN KEY (failure_reason) REFERENCES failure_reason(id)" : "";
        String fk_gelq = includeForeignKeys ? ", FOREIGN KEY (extractionId) REFERENCES extraction(id), FOREIGN KEY (plate) REFERENCES plate(id)" : "";
        String fk_pcr = includeForeignKeys ? ", FOREIGN KEY (workflow) REFERENCES workflow(id), FOREIGN KEY (cocktail) REFERENCES pcr_cocktail(id), FOREIGN KEY (plate) REFERENCES plate(id)" : "";
        String fk_cs = includeForeignKeys ? ", FOREIGN KEY (plate) REFERENCES plate(id), FOREIGN KEY (workflow) REFERENCES workflow(id), FOREIGN KEY (cocktail) REFERENCES cyclesequencing_cocktail(id)" : "";
        String fk_traces = includeForeignKeys ? ", FOREIGN KEY (reaction) REFERENCES cyclesequencing(id) ON DELETE CASCADE" : "";
        String fk_seqres = includeForeignKeys ? ", FOREIGN KEY (reaction) REFERENCES cyclesequencing(id) ON DELETE CASCADE, FOREIGN KEY (assembly) REFERENCES assembly(id) ON DELETE CASCADE" : "";

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
                "id " + autoInc + ", thermocycleId INTEGER, repeats INTEGER" +
                fk_cycle_tc + ")");

        // state
        stmt.execute("CREATE TABLE IF NOT EXISTS state (" +
                "id " + autoInc + ", temp INTEGER NOT NULL, length INTEGER NOT NULL, " +
                "cycleId INTEGER" + fk_state_cy + ")");

        // failure_reason
        stmt.execute("CREATE TABLE IF NOT EXISTS failure_reason (" +
                "id " + autoInc + ", name VARCHAR(80), description VARCHAR(255))");

        // gelimages
        stmt.execute("CREATE TABLE IF NOT EXISTS gelimages (" +
                "id " + autoInc + ", name VARCHAR(45) NOT NULL, plate INTEGER NOT NULL, " +
                "imageData " + longBlob + ", notes " + longText + " NOT NULL" +
                fk_gelimg_pl + ")");

        // plate
        stmt.execute("CREATE TABLE IF NOT EXISTS plate (" +
                "id " + autoInc + ", name VARCHAR(64) DEFAULT 'plate', " +
                "date " + dateDefault + ", size INTEGER NOT NULL, " +
                "type VARCHAR(45) NOT NULL, thermocycle INTEGER DEFAULT -1)");

        // pcr_thermocycle
        stmt.execute("CREATE TABLE IF NOT EXISTS pcr_thermocycle (" +
                "id " + autoInc + ", cycle INTEGER NOT NULL)");

        // cyclesequencing_thermocycle
        stmt.execute("CREATE TABLE IF NOT EXISTS cyclesequencing_thermocycle (" +
                "id " + autoInc + ", cycle INTEGER NOT NULL)");

        // extraction
        stmt.execute("CREATE TABLE IF NOT EXISTS extraction (" +
                "id " + autoInc + ", date " + dateDefault + ", " +
                "method VARCHAR(45) NOT NULL, volume DOUBLE NOT NULL, dilution DOUBLE, " +
                "concentrationStored TINYINT DEFAULT 0 NOT NULL, concentration DOUBLE, " +
                "parent VARCHAR(45) NOT NULL, sampleId VARCHAR(45) NOT NULL, " +
                "extractionId VARCHAR(45) NOT NULL, control VARCHAR(45) NOT NULL, " +
                "plate INTEGER NOT NULL, location INTEGER NOT NULL, " +
                "technician VARCHAR(90) NOT NULL, notes " + longText + " NOT NULL, " +
                "extractionBarcode VARCHAR(45) NOT NULL, previousPlate VARCHAR(45) NOT NULL, " +
                "previousWell VARCHAR(45) NOT NULL, gelimage " + longBlob +
                fk_ext_pl + ")");

        // workflow
        stmt.execute("CREATE TABLE IF NOT EXISTS workflow (" +
                "id " + autoInc + ", name VARCHAR(45) DEFAULT 'workflow', " +
                "date " + dateDefault + ", extractionId INTEGER NOT NULL, " +
                "locus VARCHAR(45) DEFAULT 'COI' NOT NULL" +
                fk_wf_ext + ")");

        // assembly
        stmt.execute("CREATE TABLE IF NOT EXISTS assembly (" +
                "id " + autoInc + ", extraction_id VARCHAR(45) NOT NULL, " +
                "workflow INTEGER NOT NULL, progress VARCHAR(45) NOT NULL, " +
                "consensus " + longText + ", params " + longText + ", " +
                "coverage FLOAT, disagreements INTEGER, edits INTEGER, " +
                "reference_seq_id INTEGER, confidence_scores " + longText + ", " +
                "trim_params_fwd " + longText + ", trim_params_rev " + longText + ", " +
                "other_processing_fwd " + longText + ", other_processing_rev " + longText + ", " +
                "date " + dateDefault + ", submitted TINYINT DEFAULT 0 NOT NULL, " +
                "notes " + longText + ", editrecord " + longText + ", " +
                "technician VARCHAR(255), bin VARCHAR(255), ambiguities INTEGER, " +
                "failure_reason INTEGER, failure_notes " + longText +
                fk_asm_wf + ")");

        // gel_quantification
        stmt.execute("CREATE TABLE IF NOT EXISTS gel_quantification (" +
                "id " + autoInc + ", date " + dateDefault + ", " +
                "extractionId INTEGER NOT NULL, plate INTEGER NOT NULL, " +
                "location INTEGER NOT NULL, technician VARCHAR(255), " +
                "notes " + longText + ", volume DOUBLE, gelImage " + longBlob + ", " +
                "gelBuffer VARCHAR(255), gelConc DOUBLE, stain VARCHAR(255), " +
                "stainConc VARCHAR(255), stainMethod VARCHAR(255), " +
                "gelLadder VARCHAR(255), threshold INTEGER, aboveThreshold INTEGER" +
                fk_gelq + ")");

        // pcr
        stmt.execute("CREATE TABLE IF NOT EXISTS pcr (" +
                "id " + autoInc + ", prName VARCHAR(64), prSequence VARCHAR(999), " +
                "date " + dateDefault + ", workflow INTEGER, " +
                "plate INTEGER NOT NULL, location INTEGER NOT NULL, " +
                "cocktail INTEGER NOT NULL, progress VARCHAR(45) NOT NULL, " +
                "extractionId VARCHAR(45) NOT NULL, thermocycle INTEGER DEFAULT -1, " +
                "cleanupPerformed TINYINT DEFAULT 0, cleanupMethod VARCHAR(45) NOT NULL, " +
                "technician VARCHAR(90) NOT NULL, notes " + longText + " NOT NULL, " +
                "revPrName VARCHAR(64) NOT NULL, revPrSequence VARCHAR(999) NOT NULL, " +
                "gelimage " + longBlob +
                fk_pcr + ")");

        // cyclesequencing
        stmt.execute("CREATE TABLE IF NOT EXISTS cyclesequencing (" +
                "id " + autoInc + ", primerName VARCHAR(64) NOT NULL, " +
                "primerSequence VARCHAR(999) NOT NULL, technician VARCHAR(90) NOT NULL, " +
                "notes " + longText + " NOT NULL, date " + dateDefault + ", " +
                "workflow INTEGER, thermocycle INTEGER NOT NULL, " +
                "plate INTEGER NOT NULL, location INTEGER NOT NULL, " +
                "extractionId VARCHAR(45) NOT NULL, cocktail INTEGER NOT NULL, " +
                "progress VARCHAR(45) NOT NULL, cleanupPerformed TINYINT NOT NULL, " +
                "cleanupMethod VARCHAR(99) NOT NULL, direction VARCHAR(32) NOT NULL, " +
                "gelimage " + longBlob +
                fk_cs + ")");

        // traces
        stmt.execute("CREATE TABLE IF NOT EXISTS traces (" +
                "id " + autoInc + ", reaction INTEGER NOT NULL, " +
                "name VARCHAR(96) NOT NULL, data " + longBlob + " NOT NULL" +
                fk_traces + ")");

        // sequencing_result
        stmt.execute("CREATE TABLE IF NOT EXISTS sequencing_result (" +
                "reaction INTEGER, assembly INTEGER, " +
                "PRIMARY KEY (reaction, assembly)" +
                fk_seqres + ")");

        // Create indexes — MySQL doesn't support IF NOT EXISTS on CREATE INDEX
        String[] indexes = {
                "CREATE INDEX plate_name ON plate (name)",
                "CREATE INDEX workflow_date ON workflow (date)",
                "CREATE INDEX workflow_locus ON workflow (locus)",
                "CREATE INDEX plate_type ON plate (type)",
                "CREATE INDEX plate_date ON plate (date)",
                "CREATE INDEX extraction_extractionBarcode ON extraction (extractionBarcode)",
                "CREATE INDEX extraction_date ON extraction (date)",
                "CREATE INDEX assembly_progress ON assembly (progress)",
                "CREATE INDEX assembly_submitted ON assembly (submitted)",
                "CREATE INDEX assembly_technician ON assembly (technician)",
                "CREATE INDEX assembly_date ON assembly (date)",
                "CREATE INDEX extraction_sampleId ON extraction (sampleId)"
        };
        for (String idx : indexes) {
            try {
                stmt.execute(isSQLite(targetUrl) ? idx.replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ") : idx);
            } catch (SQLException e) {
                // Index may already exist — ignore
                log.debug("Index creation skipped (may already exist): {}", e.getMessage());
            }
        }

        stmt.close();
        log.info("Target schema created successfully.");
    }

    /**
     * Test if the current user has REFERENCES privilege by attempting to create and drop
     * a temporary table with a self-referencing foreign key.
     */
    private boolean testReferencesPrivilege(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS _merger_fk_test (id INTEGER PRIMARY KEY, ref_id INTEGER, " +
                    "FOREIGN KEY (ref_id) REFERENCES _merger_fk_test(id))");
            stmt.execute("DROP TABLE IF EXISTS _merger_fk_test");
            return true;
        } catch (SQLException e) {
            log.debug("REFERENCES privilege test failed: {}", e.getMessage());
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS _merger_fk_test");
            } catch (SQLException ignored) {}
            return false;
        }
    }

    /**
     * Truncate all target tables in reverse dependency order.
     * Used with --skip-schema to ensure a clean target before merging.
     */
    private void truncateTargetTables(Connection target, String targetUrl) throws SQLException {
        // Reverse dependency order: children first, parents last
        String[] tables = {
                "sequencing_result", "traces",
                "cyclesequencing", "pcr", "assembly", "gel_quantification",
                "workflow", "extraction",
                "plate", "cyclesequencing_thermocycle", "pcr_thermocycle",
                "gelimages", "failure_reason",
                "state", "cycle", "thermocycle",
                "pcr_cocktail", "cyclesequencing_cocktail",
                "properties", "databaseversion"
        };

        if (!isSQLite(targetUrl)) {
            // Disable FK checks for MySQL to allow truncation in any order
            try (Statement stmt = target.createStatement()) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
            }
        }

        try (Statement stmt = target.createStatement()) {
            for (String table : tables) {
                try {
                    if (isSQLite(targetUrl)) {
                        stmt.execute("DELETE FROM " + table);
                    } else {
                        stmt.execute("TRUNCATE TABLE " + table);
                    }
                    log.debug("  Truncated {}", table);
                } catch (SQLException e) {
                    log.debug("  Could not truncate {} (may not exist): {}", table, e.getMessage());
                }
            }
        }

        if (!isSQLite(targetUrl)) {
            try (Statement stmt = target.createStatement()) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
        log.info("Target tables truncated.");
    }

    // ─── Utility: count rows ───────────────────────────────────────────

    private long countRows(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long maxId(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + table)) {
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
        log.info("[Plan  1/12] Checking database permissions...");
        checkPermissions(conn1, db1Url, "DB1", new String[]{"SELECT"}, plan);
        checkPermissions(conn2, db2Url, "DB2", new String[]{"SELECT"}, plan);
        if (targetConn != null) {
            checkPermissions(targetConn, targetUrl, "Target", new String[]{"SELECT", "INSERT", "CREATE"}, plan);
        } else if (isSQLite(targetUrl)) {
            plan.getPermissionResults().add("Target (SQLite): full access assumed");
        }

        // 1. Version validation
        log.info("[Plan  2/12] Validating database versions...");
        plan.setDb1Version(getDatabaseVersion(conn1));
        plan.setDb2Version(getDatabaseVersion(conn2));
        plan.setVersionsMatch(plan.getDb1Version() == plan.getDb2Version());
        if (!plan.isVersionsMatch()) {
            plan.addError("Database versions do not match: DB1=" + plan.getDb1Version() + " DB2=" + plan.getDb2Version());
        }

        // 2. Properties
        log.info("[Plan  3/12] Reading properties...");
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
        log.info("[Plan  4/12] Counting records in all tables...");
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
        log.info("[Plan  5/12] Analyzing cyclesequencing_cocktail deduplication...");
        simulateCsCocktailDedup(conn1, conn2, plan);

        // 5. Simulate pcr_cocktail deduplication
        log.info("[Plan  6/12] Analyzing pcr_cocktail deduplication...");
        simulatePcrCocktailDedup(conn1, conn2, plan);

        // 6. Simulate thermocycle hierarchy deduplication
        log.info("[Plan  7/12] Analyzing thermocycle hierarchy deduplication...");
        simulateThermocycleDedup(conn1, conn2, plan);

        // 7. Check extraction ID uniqueness
        log.info("[Plan  8/12] Checking extraction ID uniqueness...");
        checkExtractionIdUniqueness(conn1, conn2, plan);

        // 8. Check extraction barcode uniqueness
        log.info("[Plan  9/12] Checking extraction barcode uniqueness...");
        checkExtractionBarcodeUniqueness(conn1, conn2, plan);

        // 9. Check plate name uniqueness
        log.info("[Plan 10/12] Checking plate name uniqueness...");
        checkPlateNameUniqueness(conn1, conn2, plan);

        // 10. Check workflow name conflicts
        log.info("[Plan 11/12] Checking workflow name conflicts...");
        checkWorkflowNameConflicts(conn1, conn2, plan);

        // 11. Check duplicate plate/location reactions
        log.info("[Plan 12/12] Checking for duplicate plate/location reactions...");
        checkDuplicatePlateLocation(conn1, conn2, plan);

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

    private void checkExtractionBarcodeUniqueness(Connection conn1, Connection conn2, MergePlan plan) throws SQLException {
        String detailQuery =
                "SELECT e.extractionBarcode, e.location, p.name AS plateName " +
                "FROM extraction e JOIN plate p ON e.plate = p.id " +
                "WHERE e.extractionBarcode IS NOT NULL AND e.extractionBarcode != ''";

        // Check internal duplicates within each DB
        List<String> db1InternalDupes = findInternalBarcodeDuplicates(conn1, detailQuery);
        Set<String> db1DupePlates = findInternalBarcodeDupPlates(conn1);
        List<String> db2InternalDupes = findInternalBarcodeDuplicates(conn2, detailQuery);
        Set<String> db2DupePlates = findInternalBarcodeDupPlates(conn2);

        // Build barcode -> plate/well lookup for DB1 (for cross-db reporting)
        Map<String, List<String>> db1BarcodeDetails = new LinkedHashMap<>();
        Map<String, Set<String>> db1BarcodePlates = new LinkedHashMap<>();
        try (Statement stmt = conn1.createStatement();
             ResultSet rs = stmt.executeQuery(detailQuery)) {
            while (rs.next()) {
                String barcode = rs.getString("extractionBarcode");
                String plateName = rs.getString("plateName");
                int location = rs.getInt("location");
                db1BarcodeDetails.computeIfAbsent(barcode, k -> new ArrayList<>())
                        .add(plateName + " / " + wellLocationToString(location));
                db1BarcodePlates.computeIfAbsent(barcode, k -> new LinkedHashSet<>())
                        .add(plateName);
            }
        }

        // Check cross-database duplicates
        List<String> crossDupes = new ArrayList<>();
        Set<String> crossBarcodesFound = new LinkedHashSet<>();
        Set<String> crossDupePlatesDb1 = new LinkedHashSet<>();
        Set<String> crossDupePlatesDb2 = new LinkedHashSet<>();
        try (Statement stmt = conn2.createStatement();
             ResultSet rs = stmt.executeQuery(detailQuery)) {
            while (rs.next()) {
                String barcode = rs.getString("extractionBarcode");
                if (db1BarcodeDetails.containsKey(barcode)) {
                    String plateName = rs.getString("plateName");
                    int location = rs.getInt("location");
                    String db2Loc = plateName + " / " + wellLocationToString(location);
                    List<String> db1Locs = db1BarcodeDetails.get(barcode);
                    for (String db1Loc : db1Locs) {
                        crossDupes.add(barcode + "  DB1: " + db1Loc + "  |  DB2: " + db2Loc);
                    }
                    crossBarcodesFound.add(barcode);
                    crossDupePlatesDb2.add(plateName);
                    crossDupePlatesDb1.addAll(db1BarcodePlates.getOrDefault(barcode, Collections.emptySet()));
                }
            }
        }

        boolean hasIssues = false;

        // Store full details for file output
        if (!db1InternalDupes.isEmpty()) {
            plan.getDuplicateExtractionBarcodes().add("DB1 internal duplicates:");
            for (String d : db1InternalDupes) plan.getDuplicateExtractionBarcodes().add("  " + d);
            plan.addWarning("DB1 has " + db1InternalDupes.size() +
                    " duplicate extractionBarcode occurrence(s) internally on plate(s): " +
                    String.join(", ", db1DupePlates));
            plan.getBarcodeWarningPlatesDb1().addAll(db1DupePlates);
            hasIssues = true;
        }

        if (!db2InternalDupes.isEmpty()) {
            plan.getDuplicateExtractionBarcodes().add("DB2 internal duplicates:");
            for (String d : db2InternalDupes) plan.getDuplicateExtractionBarcodes().add("  " + d);
            plan.addWarning("DB2 has " + db2InternalDupes.size() +
                    " duplicate extractionBarcode occurrence(s) internally on plate(s): " +
                    String.join(", ", db2DupePlates));
            plan.getBarcodeWarningPlatesDb2().addAll(db2DupePlates);
            hasIssues = true;
        }

        if (!crossDupes.isEmpty()) {
            plan.getDuplicateExtractionBarcodes().add("Cross-database duplicates (" + crossBarcodesFound.size() + " barcode(s)):");
            for (String d : crossDupes) plan.getDuplicateExtractionBarcodes().add("  " + d);
            plan.addWarning("Duplicate extraction.extractionBarcode values between DB1 and DB2 (" +
                    crossBarcodesFound.size() + ")");
            plan.getBarcodeWarningPlatesDb1().addAll(crossDupePlatesDb1);
            plan.getBarcodeWarningPlatesDb2().addAll(crossDupePlatesDb2);
            hasIssues = true;
        }

        plan.setExtractionBarcodesUnique(!hasIssues);
        if (hasIssues) {
            int db1Count = db1InternalDupes.size();
            int db2Count = db2InternalDupes.size();
            int crossCount = crossBarcodesFound.size();
            StringBuilder detail = new StringBuilder();
            if (db1Count > 0) detail.append(db1Count).append(" DB1 internal, ");
            if (db2Count > 0) detail.append(db2Count).append(" DB2 internal, ");
            if (crossCount > 0) detail.append(crossCount).append(" cross-database, ");
            if (detail.length() > 2) detail.setLength(detail.length() - 2);
            detail.append(" duplicate(s)");
            plan.setExtractionBarcodeConflictDetail(detail.toString());
        }
    }

    /**
     * Find extractionBarcode values that appear more than once within a single database,
     * returning each occurrence with plate name and well location.
     */
    private List<String> findInternalBarcodeDuplicates(Connection conn, String detailQuery) throws SQLException {
        // First find which barcodes are duplicated
        Set<String> dupBarcodes = new LinkedHashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT extractionBarcode FROM extraction " +
                     "WHERE extractionBarcode IS NOT NULL AND extractionBarcode != '' " +
                     "GROUP BY extractionBarcode HAVING COUNT(*) > 1")) {
            while (rs.next()) {
                dupBarcodes.add(rs.getString("extractionBarcode"));
            }
        }

        if (dupBarcodes.isEmpty()) return Collections.emptyList();

        // Then fetch plate/well details for each duplicated barcode
        List<String> results = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(detailQuery)) {
            while (rs.next()) {
                String barcode = rs.getString("extractionBarcode");
                if (dupBarcodes.contains(barcode)) {
                    String plateName = rs.getString("plateName");
                    int location = rs.getInt("location");
                    results.add(barcode + "  " + plateName + " / " + wellLocationToString(location));
                }
            }
        }
        return results;
    }

    /**
     * Find plate names that contain internally-duplicated barcodes.
     */
    private Set<String> findInternalBarcodeDupPlates(Connection conn) throws SQLException {
        Set<String> plates = new LinkedHashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT p.name FROM extraction e " +
                     "JOIN plate p ON e.plate = p.id " +
                     "WHERE e.extractionBarcode IN (" +
                     "  SELECT extractionBarcode FROM extraction " +
                     "  WHERE extractionBarcode IS NOT NULL AND extractionBarcode != '' " +
                     "  GROUP BY extractionBarcode HAVING COUNT(*) > 1" +
                     ")")) {
            while (rs.next()) {
                plates.add(rs.getString("name"));
            }
        }
        return plates;
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

    /**
     * Check for reactions (extraction, pcr, cyclesequencing) that share the same plate+location.
     * These will be deduplicated during merge by keeping only the highest id (most recent).
     */
    private void checkDuplicatePlateLocation(Connection conn1, Connection conn2, MergePlan plan) throws SQLException {
        // extraction
        int extDb1 = findDupPlateLocForTable(conn1, "extraction", "DB1", plan.getDupPlateLocExtraction());
        int extDb2 = findDupPlateLocForTable(conn2, "extraction", "DB2", plan.getDupPlateLocExtraction());
        plan.setDupPlateLocExtractionCountDb1(extDb1);
        plan.setDupPlateLocExtractionCountDb2(extDb2);

        // pcr
        int pcrDb1 = findDupPlateLocForTable(conn1, "pcr", "DB1", plan.getDupPlateLocPcr());
        int pcrDb2 = findDupPlateLocForTable(conn2, "pcr", "DB2", plan.getDupPlateLocPcr());
        plan.setDupPlateLocPcrCountDb1(pcrDb1);
        plan.setDupPlateLocPcrCountDb2(pcrDb2);

        // cyclesequencing
        int csDb1 = findDupPlateLocForTable(conn1, "cyclesequencing", "DB1", plan.getDupPlateLocCs());
        int csDb2 = findDupPlateLocForTable(conn2, "cyclesequencing", "DB2", plan.getDupPlateLocCs());
        plan.setDupPlateLocCsCountDb1(csDb1);
        plan.setDupPlateLocCsCountDb2(csDb2);

        int total = extDb1 + extDb2 + pcrDb1 + pcrDb2 + csDb1 + csDb2;
        if (total > 0) {
            plan.addWarning(total + " reaction(s) share a plate/location with another reaction " +
                    "of the same type — only the most recent (highest id) will be kept during merge");
        }
    }

    /**
     * Find rows in the given table that share (plate, location) with another row.
     * Returns count of rows that would be skipped (all but highest id per group).
     * Adds detail lines to the output list.
     */
    private int findDupPlateLocForTable(Connection conn, String tableName, String dbLabel,
                                        List<String> output) throws SQLException {
        // Find (plate, location) groups with >1 row, and all their ids
        String sql = "SELECT t.id, t.plate, t.location, p.name AS plateName " +
                "FROM " + tableName + " t JOIN plate p ON t.plate = p.id " +
                "WHERE (t.plate, t.location) IN (" +
                "  SELECT plate, location FROM " + tableName +
                "  GROUP BY plate, location HAVING COUNT(*) > 1" +
                ") ORDER BY t.plate, t.location, t.id";

        // Group by (plate, location), track all ids per group
        Map<String, List<Integer>> groupIds = new LinkedHashMap<>();
        Map<String, String> groupPlateNames = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                int plate = rs.getInt("plate");
                int location = rs.getInt("location");
                String plateName = rs.getString("plateName");
                String key = plate + ":" + location;
                groupIds.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
                groupPlateNames.put(key, plateName);
            }
        }

        if (groupIds.isEmpty()) return 0;

        int skipCount = 0;
        for (Map.Entry<String, List<Integer>> entry : groupIds.entrySet()) {
            List<Integer> ids = entry.getValue();
            String plateName = groupPlateNames.get(entry.getKey());
            int location = Integer.parseInt(entry.getKey().split(":")[1]);
            String well = wellLocationToString(location);
            int keepId = ids.stream().max(Integer::compare).orElse(0);
            List<Integer> skipIds = new ArrayList<>();
            for (int id : ids) {
                if (id != keepId) skipIds.add(id);
            }
            skipCount += skipIds.size();
            output.add(dbLabel + " " + tableName + ": " + plateName + " / " + well +
                    " — ids " + ids + ", keeping id=" + keepId +
                    ", skipping " + skipIds.size());
        }
        return skipCount;
    }

    // ─── Generic row fetching/comparison helpers ───────────────────────

    private List<Map<String, Object>> fetchAllRows(Connection conn, String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                rows.add(readRow(rs, meta));
            }
        }
        return rows;
    }

    /**
     * Read a single row from a ResultSet into a Map.
     * Normalizes MySQL TINYINT(1) Boolean values to Integer (0/1) for cross-DB compatibility.
     */
    private Map<String, Object> readRow(ResultSet rs, ResultSetMetaData meta) throws SQLException {
        int cols = meta.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= cols; i++) {
            Object val = rs.getObject(i);
            // MySQL JDBC returns Boolean for TINYINT(1); normalize to Integer
            if (val instanceof Boolean) {
                val = ((Boolean) val) ? 1 : 0;
            }
            row.put(meta.getColumnLabel(i).toLowerCase(), val);
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
        try (Statement stmt = source.createStatement()) {
            // Enable streaming for MySQL (avoids loading entire ResultSet into memory).
            // Integer.MIN_VALUE is MySQL JDBC's convention for streaming mode.
            // SQLite ignores this or may throw, so we catch and ignore.
            try { stmt.setFetchSize(Integer.MIN_VALUE); } catch (SQLException ignored) {}
            try (ResultSet rs = stmt.executeQuery(selectSql);
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
            } // close inner try (rs, ps)
        } // close outer try (stmt)
        return count;
    }

    /**
     * Like streamingCopy, but skips rows whose original (pre-transform) id is in the skipIds set.
     * Returns the number of rows actually copied.
     */
    private long streamingCopyWithSkip(Connection source, String selectSql,
                                       Connection target, String insertSql, String[] colNames,
                                       RowTransformer transformer, Set<Integer> skipIds) throws SQLException {
        if (skipIds.isEmpty()) {
            return streamingCopy(source, selectSql, target, insertSql, colNames, transformer);
        }
        long count = 0;
        long skipped = 0;
        try (Statement stmt = source.createStatement()) {
            try { stmt.setFetchSize(Integer.MIN_VALUE); } catch (SQLException ignored) {}
            try (ResultSet rs = stmt.executeQuery(selectSql);
                 PreparedStatement ps = target.prepareStatement(insertSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int batchCount = 0;
            while (rs.next()) {
                Map<String, Object> row = readRow(rs, meta);
                int origId = ((Number) row.get("id")).intValue();
                if (skipIds.contains(origId)) {
                    skipped++;
                    continue;
                }
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
            } // close inner try (rs, ps)
        } // close outer try (stmt)
        if (skipped > 0) {
            log.info("    Skipped {} duplicate plate/location rows", skipped);
        }
        return count;
    }

    /**
     * Like streamingCopy, but commits after every batch.
     * Used for large BLOB tables (gelimages, traces) so that progress survives a crash.
     */
    private long streamingCopyWithCommit(Connection source, String selectSql,
                                          Connection target, String insertSql, String[] colNames,
                                          RowTransformer transformer) throws SQLException {
        long count = 0;
        try (Statement stmt = source.createStatement()) {
            try { stmt.setFetchSize(Integer.MIN_VALUE); } catch (SQLException ignored) {}
            try (ResultSet rs = stmt.executeQuery(selectSql);
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
                    target.commit();
                    batchCount = 0;
                    if (count % (BATCH_SIZE * 10) == 0) {
                        log.info("    ... {} rows committed", count);
                    }
                }
            }
            if (batchCount > 0) {
                ps.executeBatch();
                target.commit();
            }
            } // close inner try (rs, ps)
        } // close outer try (stmt)
        log.info("    ... {} rows total (copy complete)", count);
        return count;
    }

    /**
     * Resumable streaming copy for a single table. Checks the max ID already in the target
     * and reads only rows beyond that point from each source. Commits after each batch.
     *
     * DB1 rows are inserted with original IDs; DB2 rows get IDs offset by db1MaxId.
     * So target IDs 1..db1MaxId came from DB1, and db1MaxId+1.. came from DB2.
     * We use the target's max(id) to determine which source(s) still need work.
     *
     * When useServerSide is active and db1Transform is null, DB1 rows are transferred
     * via INSERT INTO...SELECT entirely within the MySQL server.
     */
    private long resumableCopy(Connection conn1, Connection conn2, Connection target,
                               String tableName, String cols, String insertSql, String[] colNames,
                               RowTransformer db1Transform, RowTransformer db2Transform) throws SQLException {
        long db1MaxId = db1MaxIds.getOrDefault(tableName, 0L);
        long targetMaxId = maxId(target, tableName);
        boolean ssEligible = useServerSide && db1Transform == null;

        long db1Count = 0;
        long db2Count = 0;

        if (targetMaxId == 0) {
            if (ssEligible) {
                db1Count = serverSideCopyDb1(target, db1Schema, tableName, cols, null);
                log.info("  Copied {} rows from DB1 (server-side)", db1Count);
            } else {
                db1Count = streamingCopyWithCommit(conn1,
                        limitSql("SELECT " + cols + " FROM " + tableName + " ORDER BY id"),
                        target, insertSql, colNames, db1Transform);
            }
            db2Count = streamingCopyWithCommit(conn2,
                    limitSql("SELECT " + cols + " FROM " + tableName + " ORDER BY id"),
                    target, insertSql, colNames, db2Transform);
        } else if (targetMaxId < db1MaxId) {
            log.info("  Resuming DB1 from id > {} (target maxId={})", targetMaxId, targetMaxId);
            if (ssEligible) {
                db1Count = serverSideCopyDb1(target, db1Schema, tableName, cols, "WHERE id > " + targetMaxId);
                log.info("  Copied {} rows from DB1 (server-side, resumed)", db1Count);
            } else {
                db1Count = streamingCopyWithCommit(conn1,
                        limitSql("SELECT " + cols + " FROM " + tableName + " WHERE id > " + targetMaxId + " ORDER BY id"),
                        target, insertSql, colNames, db1Transform);
            }
            db2Count = streamingCopyWithCommit(conn2,
                    limitSql("SELECT " + cols + " FROM " + tableName + " ORDER BY id"),
                    target, insertSql, colNames, db2Transform);
        } else if (targetMaxId >= db1MaxId) {
            long db2ResumeFrom = targetMaxId - db1MaxId;
            if (db2ResumeFrom > 0) {
                log.info("  DB1 complete. Resuming DB2 from id > {} (target maxId={})", db2ResumeFrom, targetMaxId);
                db2Count = streamingCopyWithCommit(conn2,
                        limitSql("SELECT " + cols + " FROM " + tableName + " WHERE id > " + db2ResumeFrom + " ORDER BY id"),
                        target, insertSql, colNames, db2Transform);
            } else {
                db2Count = streamingCopyWithCommit(conn2,
                        limitSql("SELECT " + cols + " FROM " + tableName + " ORDER BY id"),
                        target, insertSql, colNames, db2Transform);
            }
        }

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
        return db1Count + db2Count;
    }

    /**
     * Overload of resumableCopy that supports server-side DB2 transfer for tables
     * with simple offset-only transforms (no map lookups like cocktail/thermocycle).
     *
     * @param db2OffsetMap  map of column name (lowercase) -> offset value for DB2 server-side.
     *                      Must include "id" -> db1MaxId.
     */
    private long resumableCopy(Connection conn1, Connection conn2, Connection target,
                               String tableName, String cols, String insertSql, String[] colNames,
                               RowTransformer db1Transform, RowTransformer db2Transform,
                               Map<String, Long> db2OffsetMap) throws SQLException {
        if (!useServerSideDb2 || db2OffsetMap == null) {
            return resumableCopy(conn1, conn2, target, tableName, cols, insertSql, colNames,
                    db1Transform, db2Transform);
        }

        long db1MaxIdVal = db1MaxIds.getOrDefault(tableName, 0L);
        long targetMaxId = maxId(target, tableName);
        boolean ssDb1 = useServerSide && db1Transform == null;

        long db1Count = 0;
        long db2Count = 0;

        if (targetMaxId == 0) {
            if (ssDb1) {
                db1Count = serverSideCopyDb1(target, db1Schema, tableName, cols, null);
                log.info("  Copied {} rows from DB1 (server-side)", db1Count);
            } else {
                db1Count = streamingCopyWithCommit(conn1,
                        limitSql("SELECT " + cols + " FROM " + tableName + " ORDER BY id"),
                        target, insertSql, colNames, db1Transform);
            }
            db2Count = serverSideCopyDb2(target, db2Schema, tableName, cols, db2OffsetMap, null);
            log.info("  Copied {} rows from DB2 (server-side)", db2Count);
        } else if (targetMaxId < db1MaxIdVal) {
            log.info("  Resuming DB1 from id > {} (target maxId={})", targetMaxId, targetMaxId);
            if (ssDb1) {
                db1Count = serverSideCopyDb1(target, db1Schema, tableName, cols, "WHERE id > " + targetMaxId);
                log.info("  Copied {} rows from DB1 (server-side, resumed)", db1Count);
            } else {
                db1Count = streamingCopyWithCommit(conn1,
                        limitSql("SELECT " + cols + " FROM " + tableName + " WHERE id > " + targetMaxId + " ORDER BY id"),
                        target, insertSql, colNames, db1Transform);
            }
            db2Count = serverSideCopyDb2(target, db2Schema, tableName, cols, db2OffsetMap, null);
            log.info("  Copied {} rows from DB2 (server-side)", db2Count);
        } else if (targetMaxId >= db1MaxIdVal) {
            long db2ResumeFrom = targetMaxId - db1MaxIdVal;
            if (db2ResumeFrom > 0) {
                log.info("  DB1 complete. Resuming DB2 from id > {} (target maxId={})", db2ResumeFrom, targetMaxId);
                db2Count = serverSideCopyDb2(target, db2Schema, tableName, cols, db2OffsetMap,
                        "WHERE id > " + db2ResumeFrom);
            } else {
                db2Count = serverSideCopyDb2(target, db2Schema, tableName, cols, db2OffsetMap, null);
            }
            log.info("  Copied {} rows from DB2 (server-side)", db2Count);
        }

        log.info("  Copied {} from DB1, {} from DB2 (DB2 server-side)", db1Count, db2Count);
        return db1Count + db2Count;
    }

    /**
     * Like resumableCopy but uses streamingCopyWithSkipAndCommit for tables with plate/location dedup.
     * Server-side mode uses WHERE id NOT IN (...) for DB1 skip IDs.
     */
    private long resumableCopyWithSkip(Connection conn1, Connection conn2, Connection target,
                                        String tableName, String cols, String insertSql, String[] colNames,
                                        RowTransformer db1Transform, RowTransformer db2Transform,
                                        Set<Integer> skipIds) throws SQLException {
        long db1MaxId = db1MaxIds.getOrDefault(tableName, 0L);
        long targetMaxId = maxId(target, tableName);
        boolean ssEligible = useServerSide && db1Transform == null;

        // Build server-side skip clause for DB1 IDs
        String ssSkipClause = null;
        if (ssEligible && skipIds != null && !skipIds.isEmpty()) {
            StringBuilder sb = new StringBuilder("id NOT IN (");
            int cnt = 0;
            for (int skipId : skipIds) {
                if (skipId <= db1MaxId) {
                    if (cnt > 0) sb.append(", ");
                    sb.append(skipId);
                    cnt++;
                }
            }
            sb.append(")");
            if (cnt > 0) ssSkipClause = sb.toString();
        }

        long db1Count = 0;
        long db2Count = 0;

        if (targetMaxId == 0) {
            if (ssEligible) {
                String where = ssSkipClause != null ? "WHERE " + ssSkipClause : null;
                db1Count = serverSideCopyDb1(target, db1Schema, tableName, cols, where);
                log.info("  Copied {} rows from DB1 (server-side)", db1Count);
            } else {
                db1Count = streamingCopyWithSkipAndCommit(conn1,
                        limitSql("SELECT " + cols + " FROM " + tableName + " ORDER BY id"),
                        target, insertSql, colNames, db1Transform, skipIds);
            }
            db2Count = streamingCopyWithSkipAndCommit(conn2,
                    limitSql("SELECT " + cols + " FROM " + tableName + " ORDER BY id"),
                    target, insertSql, colNames, db2Transform, skipIds);
        } else if (targetMaxId < db1MaxId) {
            log.info("  Resuming DB1 from id > {} (target maxId={})", targetMaxId, targetMaxId);
            if (ssEligible) {
                String where = "WHERE id > " + targetMaxId;
                if (ssSkipClause != null) where += " AND " + ssSkipClause;
                db1Count = serverSideCopyDb1(target, db1Schema, tableName, cols, where);
                log.info("  Copied {} rows from DB1 (server-side, resumed)", db1Count);
            } else {
                db1Count = streamingCopyWithSkipAndCommit(conn1,
                        limitSql("SELECT " + cols + " FROM " + tableName + " WHERE id > " + targetMaxId + " ORDER BY id"),
                        target, insertSql, colNames, db1Transform, skipIds);
            }
            db2Count = streamingCopyWithSkipAndCommit(conn2,
                    limitSql("SELECT " + cols + " FROM " + tableName + " ORDER BY id"),
                    target, insertSql, colNames, db2Transform, skipIds);
        } else if (targetMaxId >= db1MaxId) {
            long db2ResumeFrom = targetMaxId - db1MaxId;
            if (db2ResumeFrom > 0) {
                log.info("  DB1 complete. Resuming DB2 from id > {} (target maxId={})", db2ResumeFrom, targetMaxId);
                db2Count = streamingCopyWithSkipAndCommit(conn2,
                        limitSql("SELECT " + cols + " FROM " + tableName + " WHERE id > " + db2ResumeFrom + " ORDER BY id"),
                        target, insertSql, colNames, db2Transform, skipIds);
            } else {
                db2Count = streamingCopyWithSkipAndCommit(conn2,
                        limitSql("SELECT " + cols + " FROM " + tableName + " ORDER BY id"),
                        target, insertSql, colNames, db2Transform, skipIds);
            }
        }

        log.info("  Copied {} from DB1, {} from DB2", db1Count, db2Count);
        return db1Count + db2Count;
    }

    /**
     * Like streamingCopyWithSkip, but commits after every batch for crash resilience.
     */
    private long streamingCopyWithSkipAndCommit(Connection source, String selectSql,
                                                 Connection target, String insertSql, String[] colNames,
                                                 RowTransformer transformer, Set<Integer> skipIds) throws SQLException {
        if (skipIds == null || skipIds.isEmpty()) {
            return streamingCopyWithCommit(source, selectSql, target, insertSql, colNames, transformer);
        }
        long count = 0;
        long skipped = 0;
        try (Statement stmt = source.createStatement()) {
            try { stmt.setFetchSize(Integer.MIN_VALUE); } catch (SQLException ignored) {}
            try (ResultSet rs = stmt.executeQuery(selectSql);
                 PreparedStatement ps = target.prepareStatement(insertSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int batchCount = 0;
            while (rs.next()) {
                Map<String, Object> row = readRow(rs, meta);
                int origId = ((Number) row.get("id")).intValue();
                if (skipIds.contains(origId)) {
                    skipped++;
                    continue;
                }
                if (transformer != null) {
                    transformer.transform(row);
                }
                setRowParams(ps, row, colNames);
                ps.addBatch();
                batchCount++;
                count++;
                if (batchCount >= BATCH_SIZE) {
                    ps.executeBatch();
                    target.commit();
                    batchCount = 0;
                    if (count % (BATCH_SIZE * 10) == 0) {
                        log.info("    ... {} rows committed", count);
                    }
                }
            }
            if (batchCount > 0) {
                ps.executeBatch();
                target.commit();
            }
            } // close inner try (rs, ps)
        } // close outer try (stmt)
        if (skipped > 0) {
            log.info("    Skipped {} duplicate plate/location rows", skipped);
        }
        log.info("    ... {} rows total (copy complete)", count);
        return count;
    }

    private boolean rowsEqual(Map<String, Object> r1, Map<String, Object> r2) {
        if (r1.size() != r2.size()) return false;
        for (String key : r1.keySet()) {
            Object v1 = r1.get(key);
            Object v2 = r2.get(key);
            if (v1 == null && v2 == null) continue;
            if (v1 == null || v2 == null) return false;
            if (!valuesEqual(v1, v2)) return false;
        }
        return true;
    }

    private boolean valuesEqual(Object v1, Object v2) {
        // Handle numeric comparison (different DB drivers may return Integer, Long, BigDecimal, etc.)
        if (v1 instanceof Number && v2 instanceof Number) {
            return Double.compare(((Number) v1).doubleValue(), ((Number) v2).doubleValue()) == 0;
        }
        // Handle byte array comparison (BLOBs)
        if (v1 instanceof byte[] && v2 instanceof byte[]) {
            return java.util.Arrays.equals((byte[]) v1, (byte[]) v2);
        }
        // Handle date/time: normalize to string for cross-DB comparison
        // (SQLite may return String, MySQL may return java.sql.Date/Timestamp/LocalDateTime)
        return v1.toString().equals(v2.toString());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  MERGE MODE
    // ═══════════════════════════════════════════════════════════════════

    public void runMerge(String db1Url, String db1Username, String db1Password,
                         String db2Url, String db2Username, String db2Password,
                         String targetUrl, String targetUsername, String targetPassword,
                         boolean skipSchema, boolean testMode, boolean resume,
                         boolean serverSide, boolean serverSideDb2) throws Exception {
        if (testMode) {
            testRowLimit = 10;
            log.info("*** TEST MODE: limiting to {} rows per table (tier 1+) ***", testRowLimit);
        }
        log.info("Starting database merge...");

        Connection conn1 = null;
        Connection conn2 = null;
        Connection target = null;
        try {
            conn1 = connect(db1Url, db1Username, db1Password);
            conn2 = connect(db2Url, db2Username, db2Password);
            target = connect(targetUrl, targetUsername, targetPassword);

            conn1.setAutoCommit(true); // read-only sources
            conn2.setAutoCommit(true);
            target.setAutoCommit(false);

            if (isSQLite(targetUrl)) {
                target.createStatement().execute("PRAGMA foreign_keys = OFF");
            } else {
                // Disable FK constraint checking during merge for performance.
                // We control data integrity ourselves via ID offsets and FK remapping.
                target.createStatement().execute("SET FOREIGN_KEY_CHECKS = 0");
                log.info("Disabled MySQL foreign key checks for merge performance.");
            }

            // Detect and validate server-side transfer mode
            if (serverSide) {
                String db1Host = mysqlHostPort(db1Url);
                String targetHost = mysqlHostPort(targetUrl);
                db1Schema = dbNameFromUrl(db1Url);
                if (db1Host == null || targetHost == null) {
                    log.warn("--server-side requires both DB1 and target to be MySQL. Falling back to standard mode.");
                } else if (!db1Host.equalsIgnoreCase(targetHost)) {
                    log.warn("--server-side: DB1 ({}) and target ({}) are on different servers. Falling back to standard mode.", db1Host, targetHost);
                } else if (!testCrossDbAccess(target, db1Schema)) {
                    log.warn("--server-side: target connection cannot SELECT from {}. Falling back to standard mode.", db1Schema);
                } else {
                    useServerSide = true;
                    log.info("*** SERVER-SIDE MODE: DB1 tables will be transferred via INSERT INTO...SELECT (server: {}) ***", db1Host);
                }
            }

            // Detect and validate server-side transfer mode for DB2
            if (serverSideDb2) {
                String db2Host = mysqlHostPort(db2Url);
                String targetHost2 = mysqlHostPort(targetUrl);
                db2Schema = dbNameFromUrl(db2Url);
                if (db2Host == null || targetHost2 == null) {
                    log.warn("--server-side-db2 requires both DB2 and target to be MySQL. Falling back to standard mode.");
                } else if (!db2Host.equalsIgnoreCase(targetHost2)) {
                    log.warn("--server-side-db2: DB2 ({}) and target ({}) are on different servers. Falling back to standard mode.", db2Host, targetHost2);
                } else if (!testCrossDbAccess(target, db2Schema)) {
                    log.warn("--server-side-db2: target connection cannot SELECT from {}. Falling back to standard mode.", db2Schema);
                } else {
                    useServerSideDb2 = true;
                    log.info("*** SERVER-SIDE DB2 MODE: eligible DB2 tables will be transferred via INSERT INTO...SELECT (server: {}) ***", db2Host);
                }
            }

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

            // Compute plate/location duplicate skip sets
            log.info("[Merge] Computing plate/location duplicate skip IDs...");
            computePlateLocationSkipIds(conn1, conn2);

            // Load completed steps if resuming
            Set<String> completedSteps = new HashSet<>();
            if (resume) {
                completedSteps = loadCompletedSteps(target);
                if (completedSteps.isEmpty()) {
                    log.info("[Merge] No previous progress found — starting fresh.");
                } else {
                    log.info("[Merge] Resuming merge. Completed steps: {}", completedSteps);
                }
            }

            // Step 1: Schema
            if (!completedSteps.contains("schema")) {
                if (skipSchema) {
                    log.info("[Merge  1/18] Skipping schema creation (--skip-schema)");
                } else {
                    log.info("[Merge  1/18] Creating target schema...");
                    createTargetSchema(target, targetUrl);
                }
                ensureProgressTable(target, targetUrl);
                if (!resume && !skipSchema) {
                    // Fresh run — no truncation needed, tables just created
                } else if (!resume && skipSchema) {
                    // Fresh run with skip-schema — truncate existing data
                    log.info("[Merge] Truncating existing target tables...");
                    truncateTargetTables(target, targetUrl);
                }
                // For resume: skip-schema tables already exist, no truncation
                recordStep(target, "schema");
                target.commit();
            } else {
                log.info("[Merge  1/18] Schema — already done, skipping.");
                ensureProgressTable(target, targetUrl);
            }

            // Step 2: Version and properties
            if (!completedSteps.contains("version_properties")) {
                log.info("[Merge  2/18] Merging version and properties...");
                mergeVersionAndProperties(conn1, conn2, target);
                recordStep(target, "version_properties");
                target.commit();
            } else {
                log.info("[Merge  2/18] version_properties — already done, skipping.");
            }

            // Step 3: CS cocktails
            if (!completedSteps.contains("cs_cocktail")) {
                log.info("[Merge  3/18] Merging cyclesequencing_cocktail (with dedup)...");
                mergeCsCocktails(conn1, conn2, target);
                recordStep(target, "cs_cocktail");
                target.commit();
            } else {
                log.info("[Merge  3/18] cs_cocktail — already done, skipping.");
                // Need to rebuild cscocktailMap from target for subsequent steps
                rebuildCsCocktailMap(conn1, conn2, target);
            }

            // Step 4: PCR cocktails
            if (!completedSteps.contains("pcr_cocktail")) {
                log.info("[Merge  4/18] Merging pcr_cocktail (with dedup)...");
                mergePcrCocktails(conn1, conn2, target);
                recordStep(target, "pcr_cocktail");
                target.commit();
            } else {
                log.info("[Merge  4/18] pcr_cocktail — already done, skipping.");
                rebuildPcrCocktailMap(conn1, conn2, target);
            }

            // Step 5: Thermocycle hierarchy
            if (!completedSteps.contains("thermocycle")) {
                log.info("[Merge  5/18] Merging thermocycle hierarchy (with dedup)...");
                mergeThermocycleHierarchy(conn1, conn2, target);
                recordStep(target, "thermocycle");
                target.commit();
            } else {
                log.info("[Merge  5/18] thermocycle — already done, skipping.");
                rebuildThermocycleMap(conn1, conn2, target);
            }

            // Steps 6-17: Table copies (each committed individually)
            String[][] tableSteps = {
                    {"6",  "failure_reason"},
                    {"7",  "gelimages"},
                    {"8",  "pcr_thermocycle"},
                    {"9",  "cs_thermocycle"},
                    {"10", "plate"},
                    {"11", "extraction"},
                    {"12", "workflow"},
                    {"13", "gel_quantification"},
                    {"14", "assembly"},
                    {"15", "pcr"},
                    {"16", "cyclesequencing"},
                    {"17", "traces"},
                    {"18", "sequencing_result"},
            };

            int maxRetries = 3;
            int[] retryCounts = new int[tableSteps.length];

            for (int si = 0; si < tableSteps.length; si++) {
                String stepNum = tableSteps[si][0];
                String stepName = tableSteps[si][1];
                if (completedSteps.contains(stepName)) {
                    log.info("[Merge {}/18] {} — already done, skipping.", stepNum, stepName);
                    // Still need to populate db1MaxIds for offset calculations
                    rebuildMaxIdForStep(conn1, stepName);
                    continue;
                }

                log.info("[Merge {}/18] Merging {}...", stepNum, stepName);
                try {
                    long stepStart = System.currentTimeMillis();
                    runTableStep(stepName, conn1, conn2, target);
                    recordStep(target, stepName);
                    target.commit();
                    long elapsed = (System.currentTimeMillis() - stepStart) / 1000;
                    log.info("[Merge {}/18] {} complete ({}m {}s)", stepNum, stepName, elapsed / 60, elapsed % 60);
                } catch (SQLException e) {
                    if (isConnectionError(e)) {
                        log.warn("Step {} hit a connection error: {}", stepName, e.getMessage());
                        log.info("Attempting to recover — reconnecting and checking if data was committed...");
                        // Reconnect all three connections
                        conn1 = reconnect(conn1, db1Url, db1Username, db1Password, true);
                        conn2 = reconnect(conn2, db2Url, db2Username, db2Password, true);
                        target = reconnect(target, targetUrl, targetUsername, targetPassword, false);
                        if (!isSQLite(targetUrl)) {
                            target.createStatement().execute("SET FOREIGN_KEY_CHECKS = 0");
                        }
                        // Check if the step actually completed (data is in target)
                        if (isStepDataPresent(target, stepName, conn1, conn2)) {
                            log.info("Step {} data verified in target — recording as complete and continuing.", stepName);
                            ensureProgressTable(target, targetUrl);
                            recordStep(target, stepName);
                            target.commit();
                            // Rebuild maxId for this step so subsequent offsets are correct
                            rebuildMaxIdForStep(conn1, stepName);
                        } else {
                            retryCounts[si]++;
                            if (retryCounts[si] > maxRetries) {
                                log.error("Step {} failed after {} retries. Run with --resume to continue later.", stepName, maxRetries);
                                throw e;
                            }
                            log.warn("Step {} data incomplete — auto-resuming (attempt {}/{}).", stepName, retryCounts[si], maxRetries);
                            si--; // rewind to retry this step
                        }
                    } else {
                        log.error("Step {} failed (non-connection error). Run with --resume to continue from here.", stepName, e);
                        try { target.rollback(); } catch (SQLException re) { log.error("Rollback failed", re); }
                        throw e;
                    }
                } catch (Exception e) {
                    log.error("Step {} failed. Run with --resume to continue from here.", stepName, e);
                    try { target.rollback(); } catch (SQLException re) { log.error("Rollback failed", re); }
                    throw e;
                }
            }

            // Clean up progress table
            cleanupProgressTable(target, targetUrl);
            target.commit();

            // Re-enable FK checks
            if (!isSQLite(targetUrl)) {
                target.createStatement().execute("SET FOREIGN_KEY_CHECKS = 1");
                log.info("Re-enabled MySQL foreign key checks.");
            }

            log.info("[Merge] Merge completed successfully! All changes committed.");
        } finally {
            closeQuietly(conn1);
            closeQuietly(conn2);
            closeQuietly(target);
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    /**
     * Check if a SQLException (or its cause chain) is a connection/communications error.
     */
    private boolean isConnectionError(SQLException e) {
        Throwable t = e;
        while (t != null) {
            String msg = t.getMessage();
            String className = t.getClass().getName();
            if (msg != null && (msg.contains("Communications link failure") ||
                    msg.contains("connection") && msg.contains("closed") ||
                    msg.contains("Socket") && msg.contains("timeout") ||
                    msg.contains("No operations allowed after connection closed") ||
                    msg.contains("connection was unexpectedly lost") ||
                    msg.contains("Can not read response from server") ||
                    msg.contains("unexpected end of stream") ||
                    msg.contains("Connection reset") ||
                    msg.contains("No space left on device"))) {
                return true;
            }
            if (className.contains("CommunicationsException") ||
                    className.contains("ConnectionIsClosedException") ||
                    className.contains("EOFException")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Close an existing connection (ignoring errors) and open a fresh one.
     */
    private Connection reconnect(Connection old, String url, String username, String password, boolean autoCommit) throws SQLException {
        closeQuietly(old);
        Connection conn = connect(url, username, password);
        conn.setAutoCommit(autoCommit);
        log.info("  Reconnected to {}", dbNameFromUrl(url));
        return conn;
    }

    /**
     * Verify that a step's data is present in the target by checking row counts.
     * Used after a connection error to determine if the step actually completed
     * before the error (e.g. error on ResultSet close after all data committed).
     */
    private boolean isStepDataPresent(Connection target, String stepName,
                                       Connection conn1, Connection conn2) {
        try {
            String tableName = stepNameToTable(stepName);
            if (tableName == null) return false;

            long sourceTotal = countRows(conn1, tableName) + countRows(conn2, tableName);
            long targetTotal = countRows(target, tableName);

            // Account for dedup skips: target may have fewer rows than source.
            // If target has at least as many as DB1, and at least 90% of total,
            // consider it done (exact match unlikely due to dedup).
            long db1Total = countRows(conn1, tableName);
            if (targetTotal >= db1Total && targetTotal >= sourceTotal * 0.9) {
                log.info("  Step {} data check: target has {} rows (source total: {}) — looks complete",
                        stepName, targetTotal, sourceTotal);
                return true;
            }
            log.warn("  Step {} data check: target has {} rows but source has {} — incomplete",
                    stepName, targetTotal, sourceTotal);
            return false;
        } catch (SQLException e) {
            log.warn("  Could not verify step {} data: {}", stepName, e.getMessage());
            return false;
        }
    }

    private String stepNameToTable(String stepName) {
        switch (stepName) {
            case "failure_reason": return "failure_reason";
            case "gelimages": return "gelimages";
            case "pcr_thermocycle": return "pcr_thermocycle";
            case "cs_thermocycle": return "cyclesequencing_thermocycle";
            case "plate": return "plate";
            case "extraction": return "extraction";
            case "workflow": return "workflow";
            case "gel_quantification": return "gel_quantification";
            case "assembly": return "assembly";
            case "pcr": return "pcr";
            case "cyclesequencing": return "cyclesequencing";
            case "traces": return "traces";
            case "sequencing_result": return "sequencing_result";
            default: return null;
        }
    }

    /**
     * Route a table step name to its merge method.
     */
    private void runTableStep(String stepName, Connection conn1, Connection conn2, Connection target) throws SQLException {
        switch (stepName) {
            case "failure_reason":
                mergeSimpleTable(conn1, conn2, target, "failure_reason",
                        "id, name, description",
                        "INSERT INTO failure_reason (id, name, description) VALUES (?, ?, ?)",
                        new String[]{"name", "description"}, new int[]{}, new int[]{});
                break;
            case "gelimages":
                mergeGelimages(conn1, conn2, target);
                break;
            case "pcr_thermocycle":
                mergeSimpleTable(conn1, conn2, target, "pcr_thermocycle",
                        "id, cycle",
                        "INSERT INTO pcr_thermocycle (id, cycle) VALUES (?, ?)",
                        new String[]{"cycle"}, new int[]{}, new int[]{1});
                break;
            case "cs_thermocycle":
                mergeSimpleTable(conn1, conn2, target, "cyclesequencing_thermocycle",
                        "id, cycle",
                        "INSERT INTO cyclesequencing_thermocycle (id, cycle) VALUES (?, ?)",
                        new String[]{"cycle"}, new int[]{}, new int[]{1});
                break;
            case "plate":
                mergePlate(conn1, conn2, target);
                break;
            case "extraction":
                mergeExtraction(conn1, conn2, target);
                break;
            case "workflow":
                mergeWorkflow(conn1, conn2, target);
                break;
            case "gel_quantification":
                mergeGelQuantification(conn1, conn2, target);
                break;
            case "assembly":
                mergeAssembly(conn1, conn2, target);
                break;
            case "pcr":
                mergePcr(conn1, conn2, target);
                break;
            case "cyclesequencing":
                mergeCyclesequencing(conn1, conn2, target);
                break;
            case "traces":
                mergeTraces(conn1, conn2, target);
                break;
            case "sequencing_result":
                mergeSequencingResult(conn1, conn2, target);
                break;
            default:
                throw new IllegalArgumentException("Unknown step: " + stepName);
        }
    }

    /**
     * Rebuild db1MaxIds for a previously completed step so offsets are correct.
     */
    private void rebuildMaxIdForStep(Connection conn1, String stepName) throws SQLException {
        switch (stepName) {
            case "failure_reason":
                db1MaxIds.put("failure_reason", maxId(conn1, "failure_reason"));
                break;
            case "gelimages":
                db1MaxIds.put("gelimages", maxId(conn1, "gelimages"));
                break;
            case "pcr_thermocycle":
                db1MaxIds.put("pcr_thermocycle", maxId(conn1, "pcr_thermocycle"));
                break;
            case "cs_thermocycle":
                db1MaxIds.put("cyclesequencing_thermocycle", maxId(conn1, "cyclesequencing_thermocycle"));
                break;
            case "plate":
                db1MaxIds.put("plate", maxId(conn1, "plate"));
                break;
            case "extraction":
                db1MaxIds.put("extraction", maxId(conn1, "extraction"));
                break;
            case "workflow":
                db1MaxIds.put("workflow", maxId(conn1, "workflow"));
                break;
            case "gel_quantification":
                db1MaxIds.put("gel_quantification", maxId(conn1, "gel_quantification"));
                break;
            case "assembly":
                db1MaxIds.put("assembly", maxId(conn1, "assembly"));
                break;
            case "pcr":
                db1MaxIds.put("pcr", maxId(conn1, "pcr"));
                break;
            case "cyclesequencing":
                db1MaxIds.put("cyclesequencing", maxId(conn1, "cyclesequencing"));
                break;
            case "traces":
                db1MaxIds.put("traces", maxId(conn1, "traces"));
                break;
            case "sequencing_result":
                // No id-based offset needed for sequencing_result
                break;
        }
    }

    // ─── Progress table helpers ──────────────────────────────────────

    private void ensureProgressTable(Connection target, String targetUrl) throws SQLException {
        try (Statement stmt = target.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS _merge_progress (step VARCHAR(64) PRIMARY KEY, completed_at " +
                    (isSQLite(targetUrl) ? "TIMESTAMP DEFAULT CURRENT_TIMESTAMP" : "DATETIME DEFAULT CURRENT_TIMESTAMP") + ")");
        }
    }

    private Set<String> loadCompletedSteps(Connection target) {
        Set<String> steps = new HashSet<>();
        try (Statement stmt = target.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT step FROM _merge_progress")) {
            while (rs.next()) {
                steps.add(rs.getString("step"));
            }
        } catch (SQLException e) {
            // Table doesn't exist yet — no steps completed
            log.debug("No progress table found: {}", e.getMessage());
        }
        return steps;
    }

    private void recordStep(Connection target, String stepName) throws SQLException {
        try (PreparedStatement ps = target.prepareStatement(
                "INSERT INTO _merge_progress (step) VALUES (?)")) {
            ps.setString(1, stepName);
            ps.executeUpdate();
        }
    }

    private void cleanupProgressTable(Connection target, String targetUrl) throws SQLException {
        try (Statement stmt = target.createStatement()) {
            if (isSQLite(targetUrl)) {
                stmt.execute("DROP TABLE IF EXISTS _merge_progress");
            } else {
                try {
                    stmt.execute("DROP TABLE _merge_progress");
                } catch (SQLException ignored) {}
            }
        }
    }

    /**
     * Rebuild cscocktailMap from already-merged data when resuming.
     * Matches DB2 cocktail rows to target rows to reconstruct the id mapping.
     */
    private void rebuildCsCocktailMap(Connection conn1, Connection conn2, Connection target) throws SQLException {
        String cols = "id, name, ddh2o, buffer, bigDye, notes, bufferConc, bigDyeConc, templateConc, " +
                "primerConc, primerAmount, extraItem, extraItemAmount, templateAmount";
        String compCols = "name, ddh2o, buffer, bigDye, notes, bufferConc, bigDyeConc, templateConc, " +
                "primerConc, primerAmount, extraItem, extraItemAmount, templateAmount";
        List<Map<String, Object>> db2Rows = fetchAllRows(conn2, "SELECT " + cols + " FROM cyclesequencing_cocktail ORDER BY id");
        List<Map<String, Object>> targetRows = fetchAllRows(target, "SELECT " + cols + " FROM cyclesequencing_cocktail ORDER BY id");
        for (Map<String, Object> db2Row : db2Rows) {
            int db2Id = ((Number) db2Row.get("id")).intValue();
            Map<String, Object> db2Comp = extractCompFields(db2Row, compCols.split(",\\s*"));
            for (Map<String, Object> tRow : targetRows) {
                Map<String, Object> tComp = extractCompFields(tRow, compCols.split(",\\s*"));
                if (rowsEqual(db2Comp, tComp)) {
                    cscocktailMap.put(db2Id, ((Number) tRow.get("id")).intValue());
                    break;
                }
            }
        }
        log.debug("Rebuilt cscocktailMap with {} entries", cscocktailMap.size());
    }

    /**
     * Rebuild pcrCocktailMap from already-merged data when resuming.
     */
    private void rebuildPcrCocktailMap(Connection conn1, Connection conn2, Connection target) throws SQLException {
        String cols = "id, name, ddH20, buffer, mg, bsa, dNTP, taq, notes, bufferConc, " +
                "mgConc, dNTPConc, taqConc, templateConc, bsaConc, fwPrAmount, fwPrConc, " +
                "revPrAmount, revPrConc, extraItem, extraItemAmount, templateAmount";
        String compCols = "name, ddH20, buffer, mg, bsa, dNTP, taq, notes, bufferConc, " +
                "mgConc, dNTPConc, taqConc, templateConc, bsaConc, fwPrAmount, fwPrConc, " +
                "revPrAmount, revPrConc, extraItem, extraItemAmount, templateAmount";
        List<Map<String, Object>> db2Rows = fetchAllRows(conn2, "SELECT " + cols + " FROM pcr_cocktail ORDER BY id");
        List<Map<String, Object>> targetRows = fetchAllRows(target, "SELECT " + cols + " FROM pcr_cocktail ORDER BY id");
        for (Map<String, Object> db2Row : db2Rows) {
            int db2Id = ((Number) db2Row.get("id")).intValue();
            Map<String, Object> db2Comp = extractCompFields(db2Row, compCols.split(",\\s*"));
            for (Map<String, Object> tRow : targetRows) {
                Map<String, Object> tComp = extractCompFields(tRow, compCols.split(",\\s*"));
                if (rowsEqual(db2Comp, tComp)) {
                    pcrCocktailMap.put(db2Id, ((Number) tRow.get("id")).intValue());
                    break;
                }
            }
        }
        log.debug("Rebuilt pcrCocktailMap with {} entries", pcrCocktailMap.size());
    }

    /**
     * Rebuild thermocycleMap from already-merged data when resuming.
     * Uses the same hierarchy comparison as the original merge.
     */
    private void rebuildThermocycleMap(Connection conn1, Connection conn2, Connection target) throws SQLException {
        List<ThermocycleHierarchy> db2Hierarchies = loadThermocycleHierarchies(conn2);
        List<ThermocycleHierarchy> targetHierarchies = loadThermocycleHierarchies(target);
        for (ThermocycleHierarchy db2Th : db2Hierarchies) {
            for (ThermocycleHierarchy tTh : targetHierarchies) {
                if (db2Th.structurallyEquals(tTh)) {
                    thermocycleMap.put(db2Th.thermocycleId, tTh.thermocycleId);
                    // Also rebuild cycle and state maps
                    for (int ci = 0; ci < db2Th.cycles.size() && ci < tTh.cycles.size(); ci++) {
                        cycleMap.put(db2Th.cycles.get(ci).cycleId, tTh.cycles.get(ci).cycleId);
                        for (int si = 0; si < db2Th.cycles.get(ci).states.size() &&
                                si < tTh.cycles.get(ci).states.size(); si++) {
                            stateMap.put(db2Th.cycles.get(ci).states.get(si).stateId,
                                    tTh.cycles.get(ci).states.get(si).stateId);
                        }
                    }
                    break;
                }
            }
        }
        log.debug("Rebuilt thermocycleMap with {} entries", thermocycleMap.size());
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

    /**
     * For each reaction table (extraction, pcr, cyclesequencing), find rows in each source DB
     * where multiple rows share the same (plate, location). Keep only the highest id (most recent).
     * Populate the skip ID sets used during merge.
     */
    private void computePlateLocationSkipIds(Connection conn1, Connection conn2) throws SQLException {
        computeSkipIdsForTable(conn1, "extraction", extractionSkipIds, "DB1");
        computeSkipIdsForTable(conn2, "extraction", extractionSkipIds, "DB2");
        computeSkipIdsForTable(conn1, "pcr", pcrSkipIds, "DB1");
        computeSkipIdsForTable(conn2, "pcr", pcrSkipIds, "DB2");
        computeSkipIdsForTable(conn1, "cyclesequencing", csSkipIds, "DB1");
        computeSkipIdsForTable(conn2, "cyclesequencing", csSkipIds, "DB2");

        int total = extractionSkipIds.size() + pcrSkipIds.size() + csSkipIds.size();
        if (total > 0) {
            log.info("Plate/location dedup: skipping {} extraction, {} pcr, {} cyclesequencing rows",
                    extractionSkipIds.size(), pcrSkipIds.size(), csSkipIds.size());
        }
    }

    private void computeSkipIdsForTable(Connection conn, String tableName,
                                         Set<Integer> skipIds, String dbLabel) throws SQLException {
        String sql = "SELECT id, plate, location FROM " + tableName +
                " WHERE (plate, location) IN (" +
                "  SELECT plate, location FROM " + tableName +
                "  GROUP BY plate, location HAVING COUNT(*) > 1" +
                ") ORDER BY plate, location, id";

        Map<String, List<Integer>> groupIds = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                int plate = rs.getInt("plate");
                int location = rs.getInt("location");
                groupIds.computeIfAbsent(plate + ":" + location, k -> new ArrayList<>()).add(id);
            }
        }

        for (List<Integer> ids : groupIds.values()) {
            int keepId = ids.stream().max(Integer::compare).orElse(0);
            for (int id : ids) {
                if (id != keepId) skipIds.add(id);
            }
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

        if (useServerSide) {
            long ssCount = serverSideCopyDb1(target, db1Schema, "cyclesequencing_cocktail", cols, null);
            log.info("  Copied {} rows from DB1 (server-side)", ssCount);
            if (!namesNeedingPrefix.isEmpty()) {
                serverSidePrefixNames(target, "cyclesequencing_cocktail", namesNeedingPrefix, db1Name);
            }
        } else {
            try (PreparedStatement ps = target.prepareStatement(insertSql)) {
                for (Map<String, Object> row : db1Rows) {
                    prefixDupName(row, namesNeedingPrefix, db1Name);
                    setRowParams(ps, row, cols.split(",\\s*"));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            log.info("  Copied {} rows from DB1", db1Rows.size());
        }

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

        db1MaxIds.put("cyclesequencing_cocktail", (long) db1Rows.size());
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
        if (useServerSide) {
            long ssCount = serverSideCopyDb1(target, db1Schema, "pcr_cocktail", cols, null);
            log.info("  Copied {} rows from DB1 (server-side)", ssCount);
            if (!namesNeedingPrefix.isEmpty()) {
                serverSidePrefixNames(target, "pcr_cocktail", namesNeedingPrefix, db1Name);
            }
        } else {
            try (PreparedStatement ps = target.prepareStatement(insertSql)) {
                for (Map<String, Object> row : db1Rows) {
                    prefixDupName(row, namesNeedingPrefix, db1Name);
                    setRowParams(ps, row, cols.split(",\\s*"));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            log.info("  Copied {} rows from DB1", db1Rows.size());
        }

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

        db1MaxIds.put("pcr_cocktail", (long) db1Rows.size());
        log.info("  DB2: {} duplicates, {} new insertions", dupes, inserted);
    }

    // ─── Phase 3: Thermocycle Hierarchy ────────────────────────────────

    private void mergeThermocycleHierarchy(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Phase 3: Merging thermocycle hierarchy...");

        // Load hierarchies
        List<ThermocycleHierarchy> h1 = loadThermocycleHierarchies(conn1);
        List<ThermocycleHierarchy> h2 = loadThermocycleHierarchies(conn2);

        // Copy all from DB1 to target (preserve IDs)
        if (useServerSide) {
            serverSideCopyDb1(target, db1Schema, "thermocycle", "id, name, notes", null);
            serverSideCopyDb1(target, db1Schema, "cycle", "id, thermocycleId, repeats", null);
            serverSideCopyDb1(target, db1Schema, "state", "id, temp, length, cycleId", null);
            log.info("  Copied DB1 thermocycle hierarchy (server-side)");
        } else {
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
        }

        int db1ThermocycleCount = h1.size();
        int db1CycleCount = h1.stream().mapToInt(th -> th.cycles.size()).sum();
        int db1StateCount = h1.stream().flatMap(th -> th.cycles.stream()).mapToInt(c -> c.states.size()).sum();

        db1MaxIds.put("thermocycle", (long) db1ThermocycleCount);
        db1MaxIds.put("cycle", (long) db1CycleCount);
        db1MaxIds.put("state", (long) db1StateCount);

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

        // Get max ID from DB1 for offset calculation
        long db1MaxId = maxId(conn1, tableName);
        db1MaxIds.put(tableName, db1MaxId);

        // Stream DB1 (server-side if eligible — no transform needed for DB1)
        long db1Count;
        if (useServerSide) {
            db1Count = serverSideCopyDb1(target, db1Schema, tableName, cols, null);
            log.info("  Copied {} rows from DB1 (server-side, maxId={})", db1Count, db1MaxId);
        } else {
            db1Count = streamingCopy(conn1, limitSql("SELECT " + cols + " FROM " + tableName + " ORDER BY id"),
                    target, insertSql, colNames, null);
            log.info("  Copied {} rows from DB1 (maxId={})", db1Count, db1MaxId);
        }

        // Stream DB2 with offset and FK mapping
        final long offset = db1MaxId;
        long db2Count = streamingCopy(conn2, limitSql("SELECT " + cols + " FROM " + tableName + " ORDER BY id"),
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

        long db1MaxId = maxId(conn1, "gelimages");
        db1MaxIds.put("gelimages", db1MaxId);
        final long plateOffset = db1MaxIds.getOrDefault("plate", 0L);

        Map<String, Long> db2Offsets = new HashMap<>();
        db2Offsets.put("id", db1MaxId);
        db2Offsets.put("plate", plateOffset);

        resumableCopy(conn1, conn2, target, "gelimages", cols, insertSql, colNames,
                null,
                row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1MaxId);
                    applyOffset(row, "plate", plateOffset);
                },
                db2Offsets);
    }

    private void mergePlate(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: plate");

        String cols = "id, name, date, size, type, thermocycle";
        String insertSql = "INSERT INTO plate (id, name, date, size, type, thermocycle) VALUES (?, ?, ?, ?, ?, ?)";
        String[] colNames = cols.split(",\\s*");

        long db1MaxId = maxId(conn1, "plate");
        db1MaxIds.put("plate", db1MaxId);

        long db1Count;
        if (useServerSide) {
            db1Count = serverSideCopyDb1(target, db1Schema, "plate", cols, null);
            log.info("  Copied {} rows from DB1 (server-side)", db1Count);
        } else {
            db1Count = streamingCopy(conn1, limitSql("SELECT " + cols + " FROM plate ORDER BY id"),
                    target, insertSql, colNames, null);
        }

        long db2Count = streamingCopy(conn2, limitSql("SELECT " + cols + " FROM plate ORDER BY id"),
                target, insertSql, colNames, row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1MaxId);
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

        long db1MaxId = maxId(conn1, "extraction");
        db1MaxIds.put("extraction", db1MaxId);
        final long plateOffset = db1MaxIds.getOrDefault("plate", 0L);

        resumableCopyWithSkip(conn1, conn2, target, "extraction", cols, insertSql, colNames,
                null,
                row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1MaxId);
                    applyOffset(row, "plate", plateOffset);
                },
                extractionSkipIds);
    }

    private void mergeWorkflow(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: workflow");

        String cols = "id, name, date, extractionId, locus";
        String insertSql = "INSERT INTO workflow (id, name, date, extractionId, locus) VALUES (?, ?, ?, ?, ?)";
        String[] colNames = cols.split(",\\s*");

        // Get DB1 max workflow number per locus for name offsetting
        Map<String, Integer> db1MaxPerLocus = getWorkflowMaxPerLocus(conn1);

        long db1MaxId = maxId(conn1, "workflow");
        db1MaxIds.put("workflow", db1MaxId);
        final long extractionOffset = db1MaxIds.getOrDefault("extraction", 0L);

        resumableCopy(conn1, conn2, target, "workflow", cols, insertSql, colNames,
                null,
                row -> {
                    int origId = ((Number) row.get("id")).intValue();
                    row.put("id", (int) (origId + db1MaxId));
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
    }

    private void mergeGelQuantification(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: gel_quantification");

        String cols = "id, date, extractionId, plate, location, technician, notes, volume, gelImage, " +
                "gelBuffer, gelConc, stain, stainConc, stainMethod, gelLadder, threshold, aboveThreshold";
        String insertSql = "INSERT INTO gel_quantification (id, date, extractionId, plate, location, technician, " +
                "notes, volume, gelImage, gelBuffer, gelConc, stain, stainConc, stainMethod, gelLadder, " +
                "threshold, aboveThreshold) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String[] colNames = cols.split(",\\s*");

        long db1MaxId = maxId(conn1, "gel_quantification");
        db1MaxIds.put("gel_quantification", db1MaxId);
        final long extractionOffset = db1MaxIds.getOrDefault("extraction", 0L);
        final long plateOffset = db1MaxIds.getOrDefault("plate", 0L);

        Map<String, Long> db2Offsets = new HashMap<>();
        db2Offsets.put("id", db1MaxId);
        db2Offsets.put("extractionid", extractionOffset);
        db2Offsets.put("plate", plateOffset);

        resumableCopy(conn1, conn2, target, "gel_quantification", cols, insertSql, colNames,
                null,
                row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1MaxId);
                    applyOffset(row, "extractionid", extractionOffset);
                    applyOffset(row, "plate", plateOffset);
                },
                db2Offsets);
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

        long db1MaxId = maxId(conn1, "assembly");
        db1MaxIds.put("assembly", db1MaxId);
        final long workflowOffset = db1MaxIds.getOrDefault("workflow", 0L);
        final long frOffset = db1MaxIds.getOrDefault("failure_reason", 0L);

        Map<String, Long> db2Offsets = new HashMap<>();
        db2Offsets.put("id", db1MaxId);
        db2Offsets.put("workflow", workflowOffset);
        db2Offsets.put("failure_reason", frOffset);

        resumableCopy(conn1, conn2, target, "assembly", cols, insertSql, colNames,
                null,
                row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1MaxId);
                    applyOffset(row, "workflow", workflowOffset);
                    Object frVal = row.get("failure_reason");
                    if (frVal != null) {
                        row.put("failure_reason", ((Number) frVal).intValue() + (int) frOffset);
                    }
                },
                db2Offsets);
    }

    private void mergePcr(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: pcr");

        String cols = "id, prName, prSequence, date, workflow, plate, location, cocktail, progress, " +
                "extractionId, thermocycle, cleanupPerformed, cleanupMethod, technician, notes, " +
                "revPrName, revPrSequence, gelimage";
        String placeholders = String.join(", ", Collections.nCopies(cols.split(",\\s*").length, "?"));
        String insertSql = "INSERT INTO pcr (" + cols + ") VALUES (" + placeholders + ")";
        String[] colNames = cols.split(",\\s*");

        long db1MaxId = maxId(conn1, "pcr");
        db1MaxIds.put("pcr", db1MaxId);
        final long workflowOffset = db1MaxIds.getOrDefault("workflow", 0L);
        final long plateOffset = db1MaxIds.getOrDefault("plate", 0L);

        resumableCopyWithSkip(conn1, conn2, target, "pcr", cols, insertSql, colNames,
                null,
                row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1MaxId);
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
                },
                pcrSkipIds);
    }

    private void mergeCyclesequencing(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: cyclesequencing");

        String cols = "id, primerName, primerSequence, technician, notes, date, workflow, thermocycle, " +
                "plate, location, extractionId, cocktail, progress, cleanupPerformed, cleanupMethod, " +
                "direction, gelimage";
        String placeholders = String.join(", ", Collections.nCopies(cols.split(",\\s*").length, "?"));
        String insertSql = "INSERT INTO cyclesequencing (" + cols + ") VALUES (" + placeholders + ")";
        String[] colNames = cols.split(",\\s*");

        long db1MaxId = maxId(conn1, "cyclesequencing");
        db1MaxIds.put("cyclesequencing", db1MaxId);
        final long workflowOffset = db1MaxIds.getOrDefault("workflow", 0L);
        final long plateOffset = db1MaxIds.getOrDefault("plate", 0L);

        resumableCopyWithSkip(conn1, conn2, target, "cyclesequencing", cols, insertSql, colNames,
                null,
                row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1MaxId);
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
                },
                csSkipIds);
    }

    private void mergeTraces(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: traces");

        String cols = "id, reaction, name, data";
        String insertSql = "INSERT INTO traces (id, reaction, name, data) VALUES (?, ?, ?, ?)";
        String[] colNames = cols.split(",\\s*");

        long db1MaxId = maxId(conn1, "traces");
        db1MaxIds.put("traces", db1MaxId);
        final long csOffset = db1MaxIds.getOrDefault("cyclesequencing", 0L);

        Map<String, Long> db2Offsets = new HashMap<>();
        db2Offsets.put("id", db1MaxId);
        db2Offsets.put("reaction", csOffset);

        resumableCopy(conn1, conn2, target, "traces", cols, insertSql, colNames,
                null,
                row -> {
                    row.put("id", ((Number) row.get("id")).intValue() + (int) db1MaxId);
                    applyOffset(row, "reaction", csOffset);
                },
                db2Offsets);
    }

    private void mergeSequencingResult(Connection conn1, Connection conn2, Connection target) throws SQLException {
        log.info("Merging table: sequencing_result");

        String cols = "reaction, assembly";
        String insertSql = "INSERT INTO sequencing_result (reaction, assembly) VALUES (?, ?)";
        String[] colNames = cols.split(",\\s*");

        long db1Count;
        if (useServerSide) {
            db1Count = serverSideCopyDb1(target, db1Schema, "sequencing_result", cols, null);
            log.info("  Copied {} rows from DB1 (server-side)", db1Count);
        } else {
            db1Count = streamingCopy(conn1, limitSql("SELECT " + cols + " FROM sequencing_result"),
                    target, insertSql, colNames, null);
        }
        final long csOffset = db1MaxIds.getOrDefault("cyclesequencing", 0L);
        final long assemblyOffset = db1MaxIds.getOrDefault("assembly", 0L);

        long db2Count;
        if (useServerSideDb2) {
            Map<String, Long> db2Offsets = new HashMap<>();
            db2Offsets.put("reaction", csOffset);
            db2Offsets.put("assembly", assemblyOffset);
            db2Count = serverSideCopyDb2(target, db2Schema, "sequencing_result", cols, db2Offsets, null);
            log.info("  Copied {} rows from DB2 (server-side)", db2Count);
        } else {
            db2Count = streamingCopy(conn2, limitSql("SELECT " + cols + " FROM sequencing_result"),
                    target, insertSql, colNames, row -> {
                        applyOffset(row, "reaction", csOffset);
                        applyOffset(row, "assembly", assemblyOffset);
                    });
        }

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
            } else if (val instanceof Boolean) {
                ps.setInt(i + 1, (Boolean) val ? 1 : 0);
            } else if (val instanceof Integer) {
                ps.setInt(i + 1, (Integer) val);
            } else if (val instanceof Long) {
                ps.setLong(i + 1, (Long) val);
            } else if (val instanceof Double) {
                ps.setDouble(i + 1, (Double) val);
            } else if (val instanceof Float) {
                ps.setFloat(i + 1, (Float) val);
            } else if (val instanceof java.math.BigDecimal) {
                ps.setBigDecimal(i + 1, (java.math.BigDecimal) val);
            } else if (val instanceof java.math.BigInteger) {
                ps.setLong(i + 1, ((java.math.BigInteger) val).longValue());
            } else if (val instanceof Number) {
                // Catch-all for any other numeric type
                ps.setDouble(i + 1, ((Number) val).doubleValue());
            } else if (val instanceof byte[]) {
                ps.setBytes(i + 1, (byte[]) val);
            } else if (val instanceof java.sql.Timestamp) {
                ps.setTimestamp(i + 1, (java.sql.Timestamp) val);
            } else if (val instanceof java.sql.Date) {
                ps.setDate(i + 1, (java.sql.Date) val);
            } else if (val instanceof java.time.LocalDateTime) {
                ps.setTimestamp(i + 1, java.sql.Timestamp.valueOf((java.time.LocalDateTime) val));
            } else if (val instanceof java.time.LocalDate) {
                ps.setDate(i + 1, java.sql.Date.valueOf((java.time.LocalDate) val));
            } else {
                // Handle SQLite boolean strings ("true"/"false") for MySQL TINYINT columns
                String strVal = val.toString();
                if ("true".equalsIgnoreCase(strVal)) {
                    ps.setInt(i + 1, 1);
                } else if ("false".equalsIgnoreCase(strVal)) {
                    ps.setInt(i + 1, 0);
                } else {
                    ps.setString(i + 1, strVal);
                }
            }
        }
    }
}
