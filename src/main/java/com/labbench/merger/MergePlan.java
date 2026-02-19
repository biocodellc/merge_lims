package com.labbench.merger;

import java.util.*;

/**
 * Data structure holding the results of a merge plan analysis.
 * Used by both plan mode (display) and merge mode (execution guide).
 */
public class MergePlan {

    // Version info
    private int db1Version = -1;
    private int db2Version = -1;
    private boolean versionsMatch = false;

    // Properties
    private String db1FullVersion;
    private String db2FullVersion;
    private boolean fullVersionsMatch = false;
    private long db1BackgroundTasksStarted;
    private long db2BackgroundTasksStarted;
    private long db1BackgroundTasksFailed;
    private long db2BackgroundTasksFailed;

    // Record counts per table: tableName -> count
    private final Map<String, Long> db1Counts = new LinkedHashMap<>();
    private final Map<String, Long> db2Counts = new LinkedHashMap<>();

    // Deduplication results
    private int cscocktailDuplicates = 0;
    private int cscocktailUnique = 0;
    private int cscocktailDuplicateNames = 0;
    private final List<String> cscocktailDupNameList = new ArrayList<>();
    private int pcrCocktailDuplicates = 0;
    private int pcrCocktailUnique = 0;
    private int pcrCocktailDuplicateNames = 0;
    private final List<String> pcrCocktailDupNameList = new ArrayList<>();
    private int thermocycleDuplicates = 0;
    private int thermocycleUnique = 0;
    private int cycleDuplicatesRemoved = 0;
    private int stateDuplicatesRemoved = 0;

    // Workflow rename info
    private boolean workflowRenameConflict = false;
    private String workflowConflictDetail;

    // Extraction/plate uniqueness
    private boolean extractionIdsUnique = true;
    private String extractionIdConflictDetail;
    private boolean extractionBarcodesUnique = true;
    private String extractionBarcodeConflictDetail;
    private boolean plateNamesUnique = true;
    private String plateNameConflictDetail;

    // Errors
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    // Permission check results
    private final List<String> permissionResults = new ArrayList<>();

    // ID mapping details for plan display
    // Each entry: "DB2 id X -> Target id Y"
    private final List<String> cscocktailMappings = new ArrayList<>();
    private final List<String> pcrCocktailMappings = new ArrayList<>();
    private final List<String> thermocycleMappings = new ArrayList<>();
    private final List<String> cycleMappings = new ArrayList<>();
    private final List<String> stateMappings = new ArrayList<>();

    // Duplicate extraction IDs
    private final List<String> duplicateExtractionIds = new ArrayList<>();

    // Duplicate extraction barcodes
    private final List<String> duplicateExtractionBarcodes = new ArrayList<>();
    private final Set<String> barcodeWarningPlatesDb1 = new LinkedHashSet<>();
    private final Set<String> barcodeWarningPlatesDb2 = new LinkedHashSet<>();

    // ---- Tables in processing order ----
    public static final List<String> ALL_TABLES = Arrays.asList(
            "databaseversion", "properties",
            "cyclesequencing_cocktail", "pcr_cocktail",
            "thermocycle", "cycle", "state",
            "failure_reason", "gelimages", "pcr_thermocycle", "cyclesequencing_thermocycle",
            "plate", "extraction", "workflow",
            "gel_quantification", "assembly",
            "pcr", "cyclesequencing",
            "traces", "sequencing_result"
    );

    // Getters and setters

    public int getDb1Version() { return db1Version; }
    public void setDb1Version(int v) { db1Version = v; }
    public int getDb2Version() { return db2Version; }
    public void setDb2Version(int v) { db2Version = v; }
    public boolean isVersionsMatch() { return versionsMatch; }
    public void setVersionsMatch(boolean v) { versionsMatch = v; }

    public String getDb1FullVersion() { return db1FullVersion; }
    public void setDb1FullVersion(String v) { db1FullVersion = v; }
    public String getDb2FullVersion() { return db2FullVersion; }
    public void setDb2FullVersion(String v) { db2FullVersion = v; }
    public boolean isFullVersionsMatch() { return fullVersionsMatch; }
    public void setFullVersionsMatch(boolean v) { fullVersionsMatch = v; }

    public long getDb1BackgroundTasksStarted() { return db1BackgroundTasksStarted; }
    public void setDb1BackgroundTasksStarted(long v) { db1BackgroundTasksStarted = v; }
    public long getDb2BackgroundTasksStarted() { return db2BackgroundTasksStarted; }
    public void setDb2BackgroundTasksStarted(long v) { db2BackgroundTasksStarted = v; }
    public long getDb1BackgroundTasksFailed() { return db1BackgroundTasksFailed; }
    public void setDb1BackgroundTasksFailed(long v) { db1BackgroundTasksFailed = v; }
    public long getDb2BackgroundTasksFailed() { return db2BackgroundTasksFailed; }
    public void setDb2BackgroundTasksFailed(long v) { db2BackgroundTasksFailed = v; }

    public Map<String, Long> getDb1Counts() { return db1Counts; }
    public Map<String, Long> getDb2Counts() { return db2Counts; }

    public int getCscocktailDuplicates() { return cscocktailDuplicates; }
    public void setCscocktailDuplicates(int v) { cscocktailDuplicates = v; }
    public int getCscocktailUnique() { return cscocktailUnique; }
    public void setCscocktailUnique(int v) { cscocktailUnique = v; }
    public int getCscocktailDuplicateNames() { return cscocktailDuplicateNames; }
    public void setCscocktailDuplicateNames(int v) { cscocktailDuplicateNames = v; }
    public List<String> getCscocktailDupNameList() { return cscocktailDupNameList; }
    public int getPcrCocktailDuplicates() { return pcrCocktailDuplicates; }
    public void setPcrCocktailDuplicates(int v) { pcrCocktailDuplicates = v; }
    public int getPcrCocktailUnique() { return pcrCocktailUnique; }
    public void setPcrCocktailUnique(int v) { pcrCocktailUnique = v; }
    public int getPcrCocktailDuplicateNames() { return pcrCocktailDuplicateNames; }
    public void setPcrCocktailDuplicateNames(int v) { pcrCocktailDuplicateNames = v; }
    public List<String> getPcrCocktailDupNameList() { return pcrCocktailDupNameList; }
    public int getThermocycleDuplicates() { return thermocycleDuplicates; }
    public void setThermocycleDuplicates(int v) { thermocycleDuplicates = v; }
    public int getThermocycleUnique() { return thermocycleUnique; }
    public void setThermocycleUnique(int v) { thermocycleUnique = v; }
    public int getCycleDuplicatesRemoved() { return cycleDuplicatesRemoved; }
    public void setCycleDuplicatesRemoved(int v) { cycleDuplicatesRemoved = v; }
    public int getStateDuplicatesRemoved() { return stateDuplicatesRemoved; }
    public void setStateDuplicatesRemoved(int v) { stateDuplicatesRemoved = v; }

    public boolean isWorkflowRenameConflict() { return workflowRenameConflict; }
    public void setWorkflowRenameConflict(boolean v) { workflowRenameConflict = v; }
    public String getWorkflowConflictDetail() { return workflowConflictDetail; }
    public void setWorkflowConflictDetail(String v) { workflowConflictDetail = v; }

    public boolean isExtractionIdsUnique() { return extractionIdsUnique; }
    public void setExtractionIdsUnique(boolean v) { extractionIdsUnique = v; }
    public String getExtractionIdConflictDetail() { return extractionIdConflictDetail; }
    public void setExtractionIdConflictDetail(String v) { extractionIdConflictDetail = v; }
    public boolean isExtractionBarcodesUnique() { return extractionBarcodesUnique; }
    public void setExtractionBarcodesUnique(boolean v) { extractionBarcodesUnique = v; }
    public String getExtractionBarcodeConflictDetail() { return extractionBarcodeConflictDetail; }
    public void setExtractionBarcodeConflictDetail(String v) { extractionBarcodeConflictDetail = v; }
    public boolean isPlateNamesUnique() { return plateNamesUnique; }
    public void setPlateNamesUnique(boolean v) { plateNamesUnique = v; }
    public String getPlateNameConflictDetail() { return plateNameConflictDetail; }
    public void setPlateNameConflictDetail(String v) { plateNameConflictDetail = v; }

    public List<String> getErrors() { return errors; }
    public void addError(String e) { errors.add(e); }
    public List<String> getWarnings() { return warnings; }
    public void addWarning(String w) { warnings.add(w); }
    public List<String> getPermissionResults() { return permissionResults; }

    public List<String> getCscocktailMappings() { return cscocktailMappings; }
    public List<String> getPcrCocktailMappings() { return pcrCocktailMappings; }
    public List<String> getThermocycleMappings() { return thermocycleMappings; }
    public List<String> getCycleMappings() { return cycleMappings; }
    public List<String> getStateMappings() { return stateMappings; }
    public List<String> getDuplicateExtractionIds() { return duplicateExtractionIds; }
    public List<String> getDuplicateExtractionBarcodes() { return duplicateExtractionBarcodes; }
    public Set<String> getBarcodeWarningPlatesDb1() { return barcodeWarningPlatesDb1; }
    public Set<String> getBarcodeWarningPlatesDb2() { return barcodeWarningPlatesDb2; }

    public boolean canProceed() {
        return errors.isEmpty();
    }

    /**
     * Compute the expected target count for a given table.
     */
    public long getTargetCount(String table) {
        long c1 = db1Counts.getOrDefault(table, 0L);
        long c2 = db2Counts.getOrDefault(table, 0L);

        switch (table) {
            case "databaseversion":
                return 1;
            case "properties":
                return 3; // fullDatabaseVersion, backgroundTasksStarted, numberOfTimesBackgroundTasksFailed
            case "cyclesequencing_cocktail":
                return c1 + c2 - cscocktailDuplicates;
            case "pcr_cocktail":
                return c1 + c2 - pcrCocktailDuplicates;
            case "thermocycle":
                return c1 + c2 - thermocycleDuplicates;
            case "cycle":
                return c1 + c2 - cycleDuplicatesRemoved;
            case "state":
                return c1 + c2 - stateDuplicatesRemoved;
            default:
                return c1 + c2;
        }
    }

    /**
     * Print a formatted plan report.
     */
    public void printReport() {
        String sep = "═══════════════════════════════════════════════════════════════════════════";
        String thin = "───────────────────────────────────────────────────────────────────────────";

        System.out.println();
        System.out.println(sep);
        System.out.println("  LABBENCH DATABASE MERGE PLAN");
        System.out.println(sep);

        // Version validation
        System.out.println();
        System.out.println("  VERSION VALIDATION");
        System.out.println(thin);
        System.out.printf("  DB1 version:          %d%n", db1Version);
        System.out.printf("  DB2 version:          %d%n", db2Version);
        System.out.printf("  Versions match:       %s%n", versionsMatch ? "YES ✓" : "NO ✗");
        System.out.printf("  DB1 full version:     %s%n", db1FullVersion);
        System.out.printf("  DB2 full version:     %s%n", db2FullVersion);
        System.out.printf("  Full versions match:  %s%n", fullVersionsMatch ? "YES ✓" : "NO ✗");

        // Permissions
        if (!permissionResults.isEmpty()) {
            System.out.println();
            System.out.println("  DATABASE PERMISSIONS");
            System.out.println(thin);
            for (String result : permissionResults) {
                System.out.println("  " + result);
            }
        }

        // Properties merge
        System.out.println();
        System.out.println("  PROPERTIES MERGE");
        System.out.println(thin);
        System.out.printf("  fullDatabaseVersion:                %s (shared)%n", db1FullVersion);
        System.out.printf("  backgroundTasksStarted:             %d + %d = %d%n",
                db1BackgroundTasksStarted, db2BackgroundTasksStarted,
                db1BackgroundTasksStarted + db2BackgroundTasksStarted);
        System.out.printf("  numberOfTimesBackgroundTasksFailed: %d + %d = %d%n",
                db1BackgroundTasksFailed, db2BackgroundTasksFailed,
                db1BackgroundTasksFailed + db2BackgroundTasksFailed);

        // Deduplication analysis
        System.out.println();
        System.out.println("  DEDUPLICATION ANALYSIS");
        System.out.println(thin);
        System.out.printf("  %-30s %8s %8s %8s %8s%n", "Table", "DB1", "DB2", "Dupes", "Target");
        System.out.println("  " + thin.substring(2));
        System.out.printf("  %-30s %8d %8d %8d %8d%n", "cyclesequencing_cocktail",
                db1Counts.getOrDefault("cyclesequencing_cocktail", 0L),
                db2Counts.getOrDefault("cyclesequencing_cocktail", 0L),
                cscocktailDuplicates, getTargetCount("cyclesequencing_cocktail"));
        System.out.printf("  %-30s %8d %8d %8d %8d%n", "pcr_cocktail",
                db1Counts.getOrDefault("pcr_cocktail", 0L),
                db2Counts.getOrDefault("pcr_cocktail", 0L),
                pcrCocktailDuplicates, getTargetCount("pcr_cocktail"));
        System.out.printf("  %-30s %8d %8d %8d %8d%n", "thermocycle",
                db1Counts.getOrDefault("thermocycle", 0L),
                db2Counts.getOrDefault("thermocycle", 0L),
                thermocycleDuplicates, getTargetCount("thermocycle"));
        System.out.printf("  %-30s %8d %8d %8d %8d%n", "cycle",
                db1Counts.getOrDefault("cycle", 0L),
                db2Counts.getOrDefault("cycle", 0L),
                cycleDuplicatesRemoved, getTargetCount("cycle"));
        System.out.printf("  %-30s %8d %8d %8d %8d%n", "state",
                db1Counts.getOrDefault("state", 0L),
                db2Counts.getOrDefault("state", 0L),
                stateDuplicatesRemoved, getTargetCount("state"));

        // Duplicate name analysis
        System.out.println();
        System.out.println("  DUPLICATE NAME ANALYSIS");
        System.out.println(thin);
        System.out.printf("  cyclesequencing_cocktail: %d full-row duplicates, %d duplicate name(s)%n",
                cscocktailDuplicates, cscocktailDuplicateNames);
        if (!cscocktailDupNameList.isEmpty()) {
            System.out.println("    Duplicate names (will be prefixed with DB name during merge):");
            for (String n : cscocktailDupNameList) System.out.println("      - " + n);
        }
        System.out.printf("  pcr_cocktail:             %d full-row duplicates, %d duplicate name(s)%n",
                pcrCocktailDuplicates, pcrCocktailDuplicateNames);
        if (!pcrCocktailDupNameList.isEmpty()) {
            System.out.println("    Duplicate names (will be prefixed with DB name during merge):");
            for (String n : pcrCocktailDupNameList) System.out.println("      - " + n);
        }

        // Duplicates found
        List<String> csDupes = new ArrayList<>();
        for (String m : cscocktailMappings) { if (m.contains("[DUPLICATE]")) csDupes.add(m); }
        List<String> pcrDupes = new ArrayList<>();
        for (String m : pcrCocktailMappings) { if (m.contains("[DUPLICATE]")) pcrDupes.add(m); }
        List<String> tcDupes = new ArrayList<>();
        for (String m : thermocycleMappings) { if (m.contains("[DUPLICATE]")) tcDupes.add(m); }

        if (!csDupes.isEmpty() || !pcrDupes.isEmpty() || !tcDupes.isEmpty()) {
            System.out.println();
            System.out.println("  DUPLICATE DETAILS");
            System.out.println(thin);

            if (!csDupes.isEmpty()) {
                System.out.println("  Cyclesequencing Cocktail (" + csDupes.size() + " duplicate(s)):");
                for (String m : csDupes) System.out.println("    " + m);
            }
            if (!pcrDupes.isEmpty()) {
                System.out.println("  PCR Cocktail (" + pcrDupes.size() + " duplicate(s)):");
                for (String m : pcrDupes) System.out.println("    " + m);
            }
            if (!tcDupes.isEmpty()) {
                System.out.println("  Thermocycle (" + tcDupes.size() + " duplicate(s)):");
                for (String m : tcDupes) System.out.println("    " + m);
            }
        }

        // All table counts
        System.out.println();
        System.out.println("  ALL TABLE RECORD COUNTS");
        System.out.println(thin);
        System.out.printf("  %-30s %8s %8s %8s%n", "Table", "DB1", "DB2", "Target");
        System.out.println("  " + thin.substring(2));
        long totalTarget = 0;
        for (String table : ALL_TABLES) {
            long c1 = db1Counts.getOrDefault(table, 0L);
            long c2 = db2Counts.getOrDefault(table, 0L);
            long target = getTargetCount(table);
            totalTarget += target;
            System.out.printf("  %-30s %8d %8d %8d%n", table, c1, c2, target);
        }
        System.out.println("  " + thin.substring(2));
        System.out.printf("  %-30s %8s %8s %8d%n", "TOTAL", "", "", totalTarget);

        // Uniqueness checks
        System.out.println();
        System.out.println("  UNIQUENESS CHECKS");
        System.out.println(thin);
        System.out.printf("  Extraction IDs unique:       %s%n", extractionIdsUnique ? "YES ✓" : "NO ✗");
        if (!extractionIdsUnique) {
            System.out.printf("    Detail: %s%n", extractionIdConflictDetail);
            System.out.println("    Duplicate extractions (extractionId | DB1: plate / well | DB2: plate / well):");
            for (String eid : duplicateExtractionIds) {
                System.out.println("      - " + eid);
            }
        }
        System.out.printf("  Extraction barcodes unique:  %s%n", extractionBarcodesUnique ? "YES ✓" : "NO (warning)");
        if (!extractionBarcodesUnique) {
            System.out.printf("    Detail: %s%n", extractionBarcodeConflictDetail);
            if (!barcodeWarningPlatesDb1.isEmpty()) {
                System.out.println("    DB1 affected plates: " + String.join(", ", barcodeWarningPlatesDb1));
            }
            if (!barcodeWarningPlatesDb2.isEmpty()) {
                System.out.println("    DB2 affected plates: " + String.join(", ", barcodeWarningPlatesDb2));
            }
            // Write full details to file
            String filename = "duplicate_barcodes.txt";
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(filename))) {
                pw.println("Duplicate Extraction Barcode Report");
                pw.println("===================================");
                pw.println();
                for (String line : duplicateExtractionBarcodes) {
                    pw.println(line);
                }
            } catch (java.io.IOException e) {
                System.err.println("    Warning: could not write " + filename + ": " + e.getMessage());
            }
            System.out.println("    Full details written to: " + filename);
        }
        System.out.printf("  Plate names unique:          %s%n", plateNamesUnique ? "YES ✓" : "NO ✗");
        if (!plateNamesUnique) System.out.printf("    Detail: %s%n", plateNameConflictDetail);
        System.out.printf("  Workflow names ok:           %s%n", !workflowRenameConflict ? "YES ✓" : "NO ✗");
        if (workflowRenameConflict) System.out.printf("    Detail: %s%n", workflowConflictDetail);

        // Errors and warnings
        if (!warnings.isEmpty()) {
            System.out.println();
            System.out.println("  WARNINGS");
            System.out.println(thin);
            for (String w : warnings) {
                System.out.println("  ⚠ " + w);
            }
        }

        // Summary of all checks
        System.out.println();
        System.out.println("  CHECK SUMMARY");
        System.out.println(thin);

        // Permissions
        boolean permissionsOk = true;
        for (String r : permissionResults) {
            if (r.contains("MISSING") || r.contains("UNABLE TO CHECK")) { permissionsOk = false; break; }
        }
        System.out.printf("  %s Database permissions verified%n",
                permissionsOk ? "✓" : "✗");

        // Version
        System.out.printf("  %s Database versions match%n",
                versionsMatch ? "✓" : "✗");
        System.out.printf("  %s Full database versions match%n",
                fullVersionsMatch ? "✓" : "✗");

        // Extraction IDs
        System.out.printf("  %s No duplicate extraction.extractionId values between DB1 and DB2%n",
                extractionIdsUnique ? "✓" : "✗");

        // Extraction barcodes (warnings only — do not block merge)
        boolean db1BarcodesOk = true, db2BarcodesOk = true, crossBarcodesOk = true;
        for (String d : duplicateExtractionBarcodes) {
            if (d.startsWith("DB1 internal")) db1BarcodesOk = false;
            if (d.startsWith("DB2 internal")) db2BarcodesOk = false;
            if (d.startsWith("Cross-database")) crossBarcodesOk = false;
        }
        System.out.printf("  %s No duplicate extraction.extractionBarcode values within DB1%n",
                db1BarcodesOk ? "✓" : "⚠");
        System.out.printf("  %s No duplicate extraction.extractionBarcode values within DB2%n",
                db2BarcodesOk ? "✓" : "⚠");
        System.out.printf("  %s No duplicate extraction.extractionBarcode values between DB1 and DB2%n",
                crossBarcodesOk ? "✓" : "⚠");

        // Plate names
        System.out.printf("  %s No duplicate plate.name values between DB1 and DB2%n",
                plateNamesUnique ? "✓" : "✗");

        // Workflow names
        System.out.printf("  %s No workflow name conflicts after applying rename offset%n",
                !workflowRenameConflict ? "✓" : "✗");

        // Summary
        System.out.println();
        System.out.println(sep);
        if (canProceed()) {
            System.out.println("  RESULT: Merge CAN proceed ✓");
        } else {
            System.out.println("  RESULT: Merge CANNOT proceed ✗");
            System.out.println("  Fix the errors above before attempting merge.");
        }
        System.out.println(sep);
        System.out.println();
    }
}
