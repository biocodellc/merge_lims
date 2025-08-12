mysqldump -h127.0.0.1 -P13306  \
  --no-data \
  --single-transaction \
  --skip-lock-tables \
  --no-tablespaces \
  --column-statistics=0 \
  lims > lims_schema.sql

