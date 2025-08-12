# Merge LIMS

This project merges tables from two MySQL databases (`lims` and `labbench`) into a combined database (`lims_merge`).

## Prerequisites

- **Java 11** or later  
  Verify with:
  ```bash
  java -version
  ```
- **Maven 3.6+**  
  Verify with:
  ```bash
  mvn -v
  ```
- Access to the MySQL servers for `lims`, `labbench`, and the merge target (`lims_merge`).

## Setup

1. **Clone the repository**  
   ```bash
   git clone <repo_url>
   cd merge_lims
   ```

2. **Configure environment variables**  
   Copy the example `.env` file and fill in your connection details:
   ```bash
   cp env.example .env
   ```
   Example `.env`:
   ```env
   SRC_HOST=127.0.0.1
   SRC_PORT=your_port
   SRC_USER=your_username
   SRC_PASS=your_password
   SRC_SCHEMA=lims

   DST_HOST=<do_host>
   DST_PORT=<do_port>
   DST_USER=<do_user>
   DST_PASS=<do_password>
   DST_SCHEMA=lims_merge
   ```

3. **Ensure the merge schema exists**  
   If needed, create the destination schema from the LIMS schema:
   ```bash
   mysql -h <MERGE_HOST> -P <MERGE_PORT> -u <MERGE_USER> -p --ssl-mode=REQUIRED      -e "CREATE DATABASE IF NOT EXISTS lims_merge;"
   ```

## Build

Compile the project with Maven:
```bash
mvn clean package
```

This produces:
```
target/merge-subset-1.0-SNAPSHOT.jar
```

## Run

Execute the merge:
```bash
java -jar target/merge-subset-1.0-SNAPSHOT.jar
```

The tool will:
- Connect to both source databases (`lims` and `labbench`).
- Copy shared/global tables to the merge schema.
- Merge plate-level data according to project logic.

## Notes

- You must have **read access** to `lims` and `labbench` schemas and **write access** to the `merge` schema.
- Use `--no-data` dumps to clone structure if the merge schema is missing.
- Errors like “table doesn’t exist” usually mean you haven’t created the target schema.
