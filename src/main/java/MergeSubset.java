import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import io.github.cdimascio.dotenv.Dotenv;

/**
 * Copies a subset of records from two MySQL schemas ("lims" and "labbench")
 * into a third schema ("merge" on DigitalOcean), preserving IDs from LIMS and
 * reindexing IDs from LABBENCH to avoid collisions (with FK remaps).
 *
 * What it copies:
 *  1) Global tables (not plate-scoped): thermocycle, cycle, state, pcr_thermocycle, cyclesequencing_thermocycle
 *  2) For up to 10 selected plates per source schema (or names filter):
 *     - plate
 *     - extraction (by plate)
 *     - workflow (by extraction)
 *     - assembly (by workflow) + failure_reason referenced
 *     - pcr (by plate) + pcr_cocktail referenced
 *     - cyclesequencing (by plate) + cyclesequencing_cocktail referenced
 *     - traces (by reaction)
 *     - gel_quantification (by plate)
 *     - gelimages (by plate)
 *     - sequencing_result (reaction, assembly) composite
 *
 * Assumptions:
 *  - DEST (merge) schema has identical structures to source.
 *  - LIMS rows keep their IDs; LABBENCH rows get offset = current MAX(id) in DEST.
 *  - FKs are updated via in-memory id maps.
 */
public class MergeSubset {

    // ==== CONFIG ====
    // .env lives in project root (beside pom.xml)
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

    // Sources (same host+port for both lims and labbench)
    private static final String SRC_HOST = dotenv.get("SRC_HOST", "127.0.0.1");
    private static final int    SRC_PORT = Integer.parseInt(dotenv.get("SRC_PORT", "13306"));
    private static final String SRC_USER = dotenv.get("SRC_USER", "");
    private static final String SRC_PASS = dotenv.get("SRC_PASS", "");

    // Destination (DigitalOcean) merge database
    private static final String  DST_HOST    = dotenv.get("DST_HOST", "localhost");
    private static final int     DST_PORT    = Integer.parseInt(dotenv.get("DST_PORT", "3306"));
    private static final String  DST_USER    = dotenv.get("DST_USER", "merge_user");
    private static final String  DST_PASS    = dotenv.get("DST_PASS", "");
    private static final boolean DST_USE_SSL = Boolean.parseBoolean(dotenv.get("DST_USE_SSL", "true"));

    // Which plates to include (by plate.name). If empty, auto-select up to MAX_PLATES by most recent date.
    private static final List<String> PLATE_NAMES_FILTER = Collections.emptyList();
    private static final int MAX_PLATES = 10; // per schema

    // Schemas
    private static final String SCHEMA_LIMS     = "lims";
    private static final String SCHEMA_LABBENCH = "labbench";
    private static final String SCHEMA_MERGE    = "lims_merge";

    // Tables with simple integer PK
    private static final Map<String, String> TABLE_PK = new LinkedHashMap<>();
    static {
        TABLE_PK.put("thermocycle", "id");
        TABLE_PK.put("cycle", "id");
        TABLE_PK.put("state", "id");
        TABLE_PK.put("pcr_thermocycle", "id");
        TABLE_PK.put("cyclesequencing_thermocycle", "id");

        TABLE_PK.put("plate", "id"); // composite PK(id,name) in schema; we still map id
        TABLE_PK.put("extraction", "id");
        TABLE_PK.put("workflow", "id");
        TABLE_PK.put("assembly", "id");
        TABLE_PK.put("failure_reason", "id");
        TABLE_PK.put("cyclesequencing", "id");
        TABLE_PK.put("pcr", "id");
        TABLE_PK.put("traces", "id");
        TABLE_PK.put("gel_quantification", "id");
        TABLE_PK.put("gelimages", "id");
        TABLE_PK.put("pcr_cocktail", "id");
        TABLE_PK.put("cyclesequencing_cocktail", "id");
    }

    // FKs that need rewriting (table -> (fkColumn -> referencedTable))
    private static final Map<String, Map<String, String>> FK_MAP = new HashMap<>();
    static {
        FK_MAP.put("cycle", mapOf("thermocycleId", "thermocycle"));
        FK_MAP.put("state", mapOf("cycleId", "cycle"));

        FK_MAP.put("pcr", mapOf(
                "plate", "plate",
                "workflow", "workflow",
                "cocktail", "pcr_cocktail"
        ));
        FK_MAP.put("cyclesequencing", mapOf(
                "plate", "plate",
                "workflow", "workflow",
                "cocktail", "cyclesequencing_cocktail"
        ));
        FK_MAP.put("traces", mapOf("reaction", "cyclesequencing"));
        FK_MAP.put("gel_quantification", mapOf(
                "extractionId", "extraction",
                "plate", "plate"
        ));
        FK_MAP.put("gelimages", mapOf("plate", "plate"));
        FK_MAP.put("workflow", mapOf("extractionId", "extraction"));
        FK_MAP.put("assembly", mapOf(
                "workflow", "workflow",
                "failure_reason", "failure_reason"
        ));
        // sequencing_result handled separately (composite)
    }

    // Unique text cols that might collide when mixing LABBENCH
    private static final Map<String, List<String>> UNIQUE_STR_COLS = new HashMap<>();
    static {
        UNIQUE_STR_COLS.put("workflow", Arrays.asList("name"));
        UNIQUE_STR_COLS.put("extraction", Arrays.asList("extractionId"));
    }

    public static void main(String[] args) throws Exception {
        try (
            Connection srcLims = openSrc(SCHEMA_LIMS);
            Connection srcLab  = openSrc(SCHEMA_LABBENCH);
            Connection dst     = openDst(SCHEMA_MERGE)
        ) {
            dst.setAutoCommit(false);

            // 1) Global tables first
            copyGlobalTables(srcLims, srcLab, dst);

            // 2) Build worksets
            Workset limsSet = buildWorksetForSchema(srcLims, SCHEMA_LIMS);
            Workset labSet  = buildWorksetForSchema(srcLab, SCHEMA_LABBENCH);

            // 3) Insert LIMS as-is
            System.out.println("\n== Insert LIMS subset (preserve IDs) ==");
            insertSubset(dst, limsSet, false);

            // 4) Insert LABBENCH with ID remap
            System.out.println("\n== Insert LABBENCH subset (reindex IDs) ==");
            insertSubset(dst, labSet, true);

            dst.commit();
            System.out.println("\nDONE.");
        }
    }

    // ---------- Connections ----------
    private static Connection openSrc(String schema) throws SQLException {
        String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC",
                SRC_HOST, SRC_PORT, schema);
        Properties p = new Properties();
        p.setProperty("user", SRC_USER);
        p.setProperty("password", SRC_PASS);
        p.setProperty("useUnicode", "true");
        p.setProperty("characterEncoding", "utf8");
        return DriverManager.getConnection(url, p);
    }

    private static Connection openDst(String schema) throws SQLException {
        String url = String.format("jdbc:mysql://%s:%d/%s?serverTimezone=UTC%s",
                DST_HOST, DST_PORT, schema, DST_USE_SSL ? "&useSSL=true&requireSSL=true" : "");
        Properties p = new Properties();
        p.setProperty("user", DST_USER);
        p.setProperty("password", DST_PASS);
        p.setProperty("useUnicode", "true");
        p.setProperty("characterEncoding", "utf8");
        return DriverManager.getConnection(url, p);
    }

    // ---------- Workset ----------
    private static class Workset {
        String schema;
        Map<String, Set<Long>> ids = new LinkedHashMap<>();
        Set<Long> pcrCocktailIds = new HashSet<>();
        Set<Long> csCocktailIds  = new HashSet<>();
        Set<Long> reactionIds    = new HashSet<>();
        Set<Long> assemblyIds    = new HashSet<>();
    }

    private static Workset buildWorksetForSchema(Connection src, String schema) throws SQLException {
        System.out.println("\nBuilding workset for " + schema);
        Workset ws = new Workset();
        ws.schema = schema;

        List<Long> plateIds = selectPlateIds(src);
        ws.ids.put("plate", new LinkedHashSet<>(plateIds));
        System.out.println("  Plates: " + plateIds);

        Set<Long> extractionIds = selectIds(src,
            "SELECT id FROM extraction WHERE plate IN (" + placeholders(plateIds.size()) + ")", plateIds);
        ws.ids.put("extraction", extractionIds);

        Set<Long> workflowIds = selectIds(src,
            "SELECT id FROM workflow WHERE extractionId IN (" + placeholders(extractionIds.size()) + ")", extractionIds);
        ws.ids.put("workflow", workflowIds);

        Set<Long> assemblyIds = selectIds(src,
            "SELECT id FROM assembly WHERE workflow IN (" + placeholders(workflowIds.size()) + ")", workflowIds);
        ws.ids.put("assembly", assemblyIds);
        ws.assemblyIds.addAll(assemblyIds);

        Set<Long> frIds = selectIds(src,
            "SELECT DISTINCT failure_reason FROM assembly WHERE failure_reason IS NOT NULL AND workflow IN (" +
                placeholders(workflowIds.size()) + ")", workflowIds);
        if (!frIds.isEmpty()) ws.ids.put("failure_reason", frIds);

        Set<Long> csIds = selectIds(src,
            "SELECT id FROM cyclesequencing WHERE plate IN (" + placeholders(plateIds.size()) + ")", plateIds);
        ws.ids.put("cyclesequencing", csIds);
        ws.reactionIds.addAll(csIds);

        Set<Long> pcrIds = selectIds(src,
            "SELECT id FROM pcr WHERE plate IN (" + placeholders(plateIds.size()) + ")", plateIds);
        ws.ids.put("pcr", pcrIds);

        ws.pcrCocktailIds = selectIds(src,
            "SELECT DISTINCT cocktail FROM pcr WHERE plate IN (" + placeholders(plateIds.size()) + ")", plateIds);
        ws.csCocktailIds = selectIds(src,
            "SELECT DISTINCT cocktail FROM cyclesequencing WHERE plate IN (" + placeholders(plateIds.size()) + ")", plateIds);

        Set<Long> traceIds = selectIds(src,
            "SELECT id FROM traces WHERE reaction IN (" + placeholders(csIds.size()) + ")", csIds);
        ws.ids.put("traces", traceIds);

        Set<Long> gqIds = selectIds(src,
            "SELECT id FROM gel_quantification WHERE plate IN (" + placeholders(plateIds.size()) + ")", plateIds);
        ws.ids.put("gel_quantification", gqIds);

        Set<Long> giIds = selectIds(src,
            "SELECT id FROM gelimages WHERE plate IN (" + placeholders(plateIds.size()) + ")", plateIds);
        ws.ids.put("gelimages", giIds);

        if (!ws.pcrCocktailIds.isEmpty()) ws.ids.put("pcr_cocktail", ws.pcrCocktailIds);
        if (!ws.csCocktailIds.isEmpty())  ws.ids.put("cyclesequencing_cocktail", ws.csCocktailIds);

        return ws;
    }

    private static List<Long> selectPlateIds(Connection src) throws SQLException {
        List<Long> out = new ArrayList<>();
        String sql;
        List<Object> params = new ArrayList<>();
        if (!PLATE_NAMES_FILTER.isEmpty()) {
            sql = "SELECT id FROM plate WHERE name IN (" + placeholders(PLATE_NAMES_FILTER.size()) +
                  ") ORDER BY date DESC, id DESC LIMIT ?";
            params.addAll(PLATE_NAMES_FILTER);
            params.add(MAX_PLATES);
        } else {
            sql = "SELECT id FROM plate ORDER BY date DESC, id DESC LIMIT ?";
            params.add(MAX_PLATES);
        }
        try (PreparedStatement ps = src.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
        }
        return out;
    }

    private static Set<Long> selectIds(Connection conn, String sql, Collection<?> values) throws SQLException {
        if (values == null || values.isEmpty()) return new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, new ArrayList<>(values));
            Set<Long> out = new LinkedHashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
            return out;
        }
    }

    // ---------- Global tables ----------
    private static void copyGlobalTables(Connection srcLims, Connection srcLab, Connection dst) throws SQLException {
        System.out.println("Copying global tables: thermocycle, cycle, state, pcr_thermocycle, cyclesequencing_thermocycle");
        insertWholeTable(dst, srcLims, "thermocycle", false);
        insertWholeTable(dst, srcLims, "cycle", false);
        insertWholeTable(dst, srcLims, "state", false);
        insertWholeTable(dst, srcLims, "pcr_thermocycle", false);
        insertWholeTable(dst, srcLims, "cyclesequencing_thermocycle", false);

        insertWholeTable(dst, srcLab, "thermocycle", true);
        insertWholeTable(dst, srcLab, "cycle", true);
        insertWholeTable(dst, srcLab, "state", true);
        insertWholeTable(dst, srcLab, "pcr_thermocycle", true);
        insertWholeTable(dst, srcLab, "cyclesequencing_thermocycle", true);
    }

    private static void insertWholeTable(Connection dst, Connection src, String table, boolean reindex) throws SQLException {
        String pk = TABLE_PK.get(table);
        if (pk == null) throw new IllegalArgumentException("No PK known for table " + table);
        System.out.println("  -> " + table + (reindex ? " (LABBENCH reindex)" : " (LIMS as-is)"));

        long offset = reindex ? currentMaxId(dst, table, pk) : 0L;
        Map<Long, Long> idMap = new HashMap<>();

        String sql = "SELECT * FROM " + table;
        try (Statement st = src.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            insertResultSet(dst, table, rs, reindex, pk, idMap, offset, Collections.emptyMap());
        }
    }

    // ---------- Insert subset ----------
    private static void insertSubset(Connection dst, Workset ws, boolean reindex) throws SQLException {
        Map<String, Map<Long, Long>> idMaps = new HashMap<>();

        insertByIds(dst, ws.schema, "plate", ws.ids.get("plate"), reindex, idMaps);
        insertByIds(dst, ws.schema, "extraction", ws.ids.get("extraction"), reindex, idMaps);
        insertByIds(dst, ws.schema, "workflow", ws.ids.get("workflow"), reindex, idMaps);

        if (ws.ids.containsKey("failure_reason")) {
            insertByIds(dst, ws.schema, "failure_reason", ws.ids.get("failure_reason"), reindex, idMaps);
        }

        insertByIds(dst, ws.schema, "assembly", ws.ids.get("assembly"), reindex, idMaps);

        if (ws.ids.containsKey("pcr_cocktail")) insertByIds(dst, ws.schema, "pcr_cocktail", ws.ids.get("pcr_cocktail"), reindex, idMaps);
        if (ws.ids.containsKey("cyclesequencing_cocktail")) insertByIds(dst, ws.schema, "cyclesequencing_cocktail", ws.ids.get("cyclesequencing_cocktail"), reindex, idMaps);

        insertByIds(dst, ws.schema, "pcr", ws.ids.get("pcr"), reindex, idMaps);
        insertByIds(dst, ws.schema, "cyclesequencing", ws.ids.get("cyclesequencing"), reindex, idMaps);
        insertByIds(dst, ws.schema, "traces", ws.ids.get("traces"), reindex, idMaps);
        insertByIds(dst, ws.schema, "gel_quantification", ws.ids.get("gel_quantification"), reindex, idMaps);
        insertByIds(dst, ws.schema, "gelimages", ws.ids.get("gelimages"), reindex, idMaps);

        insertSequencingResult(dst, ws.schema, ws.reactionIds, ws.assemblyIds, reindex, idMaps);
    }

    private static void insertByIds(Connection dst, String srcSchema, String table, Set<Long> ids, boolean reindex,
                                    Map<String, Map<Long, Long>> idMaps) throws SQLException {
        if (ids == null || ids.isEmpty()) return;
        String pk = TABLE_PK.get(table);
        if (pk == null) throw new IllegalArgumentException("No PK declared for table " + table);

        long offset = reindex ? currentMaxId(dst, table, pk) : 0L;
        Map<Long, Long> idMap = new HashMap<>();

        try (Connection src = openSrc(srcSchema)) {
            String sql = "SELECT * FROM " + table + " WHERE " + pk + " IN (" + placeholders(ids.size()) + ")";
            try (PreparedStatement ps = src.prepareStatement(sql)) {
                bind(ps, new ArrayList<>(ids));
                try (ResultSet rs = ps.executeQuery()) {
                    insertResultSet(dst, table, rs, reindex, pk, idMap, offset, idMaps);
                }
            }
        }
        idMaps.put(table, idMap);
    }

    private static void insertSequencingResult(Connection dst, String srcSchema, Set<Long> reactionIds, Set<Long> assemblyIds,
                                               boolean reindex, Map<String, Map<Long, Long>> idMaps) throws SQLException {
        if ((reactionIds == null || reactionIds.isEmpty()) && (assemblyIds == null || assemblyIds.isEmpty())) return;
        try (Connection src = openSrc(srcSchema)) {
            StringBuilder sb = new StringBuilder("SELECT reaction, assembly FROM sequencing_result WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (reactionIds != null && !reactionIds.isEmpty()) {
                sb.append(" AND reaction IN (").append(placeholders(reactionIds.size())).append(")");
                params.addAll(reactionIds);
            }
            if (assemblyIds != null && !assemblyIds.isEmpty()) {
                sb.append(" AND assembly IN (").append(placeholders(assemblyIds.size())).append(")");
                params.addAll(assemblyIds);
            }
            try (PreparedStatement ps = src.prepareStatement(sb.toString())) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    String sql = "INSERT INTO sequencing_result (reaction, assembly) VALUES (?, ?)";
                    try (PreparedStatement ins = dst.prepareStatement(sql)) {
                        int batch = 0;
                        while (rs.next()) {
                            long r = rs.getLong("reaction");
                            long a = rs.getLong("assembly");
                            Long newR = mapId(idMaps, "cyclesequencing", r, reindex, dst);
                            Long newA = mapId(idMaps, "assembly", a, reindex, dst);
                            ins.setLong(1, newR);
                            ins.setLong(2, newA);
                            ins.addBatch();
                            if (++batch % 1000 == 0) ins.executeBatch();
                        }
                        ins.executeBatch();
                    }
                }
            }
        }
    }

    private static Long mapId(Map<String, Map<Long, Long>> idMaps, String table, long oldId,
                              boolean reindex, Connection dst) throws SQLException {
        Map<Long, Long> m = idMaps.get(table);
        if (m != null && m.containsKey(oldId)) return m.get(oldId);
        if (reindex) {
            String pk = TABLE_PK.get(table);
            long off = currentMaxId(dst, table, pk);
            return oldId + off;
        }
        return oldId;
    }

    private static void insertResultSet(Connection dst, String table, ResultSet rs, boolean reindex, String pk,
                                        Map<Long, Long> idMap, long offset,
                                        Map<String, Map<Long, Long>> idMaps) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();

        String columnList = columnsList(md);
        String ph = String.join(", ", Collections.nCopies(cols, "?"));
        String sql = "INSERT INTO " + table + " (" + columnList + ") VALUES (" + ph + ")";

        try (PreparedStatement ins = dst.prepareStatement(sql)) {
            int batch = 0;
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    String col = md.getColumnLabel(i);
                    row.put(col, rs.getObject(i));
                }

                // PK remap
                if (row.get(pk) != null) {
                    long oldId = ((Number) row.get(pk)).longValue();
                    long newId = reindex ? oldId + offset : oldId;
                    row.put(pk, newId);
                    idMap.put(oldId, newId);
                }

                // FK remap
                Map<String, String> fks = FK_MAP.getOrDefault(table, Collections.emptyMap());
                for (Map.Entry<String, String> e : fks.entrySet()) {
                    String fkCol = e.getKey();
                    String refTable = e.getValue();
                    Object v = row.get(fkCol);
                    if (v != null) {
                        long oldFk = ((Number) v).longValue();
                        Map<Long, Long> refMap = idMaps.get(refTable);
                        long newFk = oldFk;
                        if (refMap != null && refMap.containsKey(oldFk)) {
                            newFk = refMap.get(oldFk);
                        } else if (reindex) {
                            String refPk = TABLE_PK.get(refTable);
                            long refOffset = currentMaxId(dst, refTable, refPk);
                            newFk = oldFk + refOffset;
                        }
                        row.put(fkCol, newFk);
                    }
                }

                // Unique string conflicts (LABBENCH only)
                if (reindex) {
                    List<String> uniq = UNIQUE_STR_COLS.getOrDefault(table, Collections.emptyList());
                    for (String ucol : uniq) {
                        Object v = row.get(ucol);
                        if (v != null) {
                            String s = v.toString();
                            if (existsValue(dst, table, ucol, s)) {
                                String s2 = s + "_lb";
                                int cnt = 1;
                                while (existsValue(dst, table, ucol, s2)) s2 = s + "_lb" + cnt++;
                                row.put(ucol, s2);
                            }
                        }
                    }
                }

                for (int i = 1; i <= cols; i++) {
                    ins.setObject(i, row.get(md.getColumnLabel(i)));
                }
                ins.addBatch();
                if (++batch % 1000 == 0) ins.executeBatch();
            }
            ins.executeBatch();
        }
    }

    private static boolean existsValue(Connection conn, String table, String col, String val) throws SQLException {
        String sql = "SELECT 1 FROM " + table + " WHERE " + col + " = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, val);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static long currentMaxId(Connection conn, String table, String pk) throws SQLException {
        String sql = "SELECT COALESCE(MAX(" + pk + "), 0) FROM " + table;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---------- Utils ----------
    private static String placeholders(int n) {
        if (n <= 0) return "";
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    private static void bind(PreparedStatement ps, List<?> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
    }

    private static String columnsList(ResultSetMetaData md) throws SQLException {
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) cols.add("`" + md.getColumnLabel(i) + "`");
        return String.join(", ", cols);
    }

    @SafeVarargs
    private static <K, V> Map<K, V> mapOf(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("Odd kv length");
        Map<K, V> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked") K k = (K) kv[i];
            @SuppressWarnings("unchecked") V v = (V) kv[i + 1];
            m.put(k, v);
        }
        return m;
    }
}

