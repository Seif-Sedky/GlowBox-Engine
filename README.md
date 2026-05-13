# GlowBox Engine

The Query Way To Your Data

Visualizer is still underway.

## Overview

GlowBox Engine is a single-user, educational, disk-based storage engine built from scratch in Java. It includes a real storage layer, buffer pool, linear hash index, cost-based query optimizer, and a SQL parser backed by JSqlParser. Every query runs against actual binary `.db` files on disk.

I desinged this prioritizing heavy decoupling to be able to add features easily following the classic SOLID principles
### Engine layers

| Layer | Responsibility |
|---|---|
| Disk & File Manager | Raw byte reads and writes to `.db` files. Fixed 256-byte pages. |
| Buffer Pool Manager | LRU cache of 16 frames to reduce disk I/O across queries. |
| Catalog | In-memory schema registry for tables, columns, and index metadata. |
| Record Layer | Typed fields, records, record IDs, and serialization to bytes. |
| Heap File | Unordered page collection per table for INSERT, SCAN, and DELETE. |
| Index | Disk-backed Linear Hash index on a single column. |
| Execution Operators | Bulk-materialization Volcano model with one operator per operation. |
| Query Optimizer | Rule-based optimizer with cost validation for access path and join choice. |
| Parser | JSqlParser adapter that converts SQL strings into parsed statements, Note that this part was fully vibe coded, unfortunatley I did not have enough knowledge nor time to understnad how to implement parsers or use JSQLparser. |

> Page size is deliberately small (256 bytes) so that splits, evictions, and page reads happen frequently and are easy to observe.


## Data types

All types are fixed-length. Variable-length types are not supported.

| Type | Size | Java mapping | Literal syntax |
|---|---:|---|---|
| INT | 4 bytes | Integer | `42`, `-7`, `0` |
| BOOLEAN | 1 byte | Boolean | `TRUE`, `FALSE` |
| CHAR(n) | n bytes | String (space-padded) | `'Alice'`, `'NY'` |

**Not supported:** `VARCHAR`, `FLOAT`, `DATE`, `DECIMAL`, and `NULL`.

## Data Definition Language (DDL)

### CREATE TABLE

Creates a new table and allocates its `.db` file on disk.

```sql
CREATE TABLE table_name (
    column_name data_type [, column_name data_type ...]
)
```

Examples:

```sql
CREATE TABLE employees (id INT, name CHAR(30), salary INT, active BOOLEAN)
CREATE TABLE locations (id INT, city CHAR(20), x INT, y INT)
```

Column order determines byte layout on disk. Offsets are assigned automatically from left to right.

### DROP TABLE

Removes the table from the catalog, deletes its `.db` file, and drops all associated indexes.

```sql
DROP TABLE table_name
```

Example:

```sql
DROP TABLE employees
```

> This is irreversible. All data and indexes for the table are permanently deleted.

### CREATE INDEX

Builds a Linear Hash index on one column of an existing table. Existing rows are scanned and inserted into the index automatically.

```sql
CREATE INDEX index_name ON table_name (column_name)
```

Examples:

```sql
CREATE INDEX idx_salary ON employees (salary)
CREATE INDEX idx_city ON locations (city)
```

Only one index type is supported: Linear Hash. Only single-column indexes are supported.

### DROP INDEX

Removes the index from the catalog and deletes its `.db` file.

```sql
DROP INDEX index_name
```

Example:

```sql
DROP INDEX idx_salary
```

## Data Manipulation Language (DML)

### INSERT

Inserts one row into a table. Values must be given in column-declaration order.

```sql
INSERT INTO table_name VALUES (value1, value2, ...)
```

Examples:

```sql
INSERT INTO employees VALUES (1, 'Alice', 75000, TRUE)
INSERT INTO employees VALUES (2, 'Bob', 52000, FALSE)
INSERT INTO locations VALUES (1, 'Cairo', 31, 30)
```

| Type | Literal format | Example |
|---|---|---|
| INT | Plain integer, no quotes | `42`, `-5`, `0` |
| BOOLEAN | `TRUE` or `FALSE` | `TRUE`, `FALSE` |
| CHAR(n) | Single-quoted string | `'Alice'`, `'New York'` |

Named column lists such as `INSERT INTO t (col1, col2) VALUES (...)` are not supported.

### SELECT

Reads records from one or two tables. All SELECT statements use `SELECT *`; column projection is not supported.

#### Single-table SELECT

```sql
SELECT * FROM table_name
SELECT * FROM table_name WHERE condition
SELECT DISTINCT * FROM table_name WHERE condition
```

Examples:

```sql
SELECT * FROM employees
SELECT * FROM employees WHERE salary = 75000
SELECT * FROM employees WHERE salary > 40000
SELECT * FROM employees WHERE salary BETWEEN 30000 AND 60000
SELECT DISTINCT * FROM employees WHERE active = TRUE
```

#### Two-table JOIN using comma style

The join condition must be written in the WHERE clause as `table.col = table.col`. An optional `AND` filter on one table may follow.

```sql
SELECT * FROM t1 [alias1], t2 [alias2]
WHERE t1.col = t2.col [AND filter_condition]
```

Examples:

```sql
SELECT * FROM employees e, departments d WHERE e.dept_id = d.id
SELECT * FROM employees e, departments d
WHERE e.dept_id = d.id AND e.salary > 40000
```

#### Two-table JOIN using explicit JOIN syntax

```sql
SELECT * FROM t1 [alias1] JOIN t2 [alias2] ON col1 = col2
[WHERE filter_condition]
```

Examples:

```sql
SELECT * FROM employees JOIN departments ON dept_id = id
SELECT * FROM employees e JOIN departments d ON dept_id = id
WHERE e.salary > 50000
```

Limitations:

- Only `SELECT *` is allowed.
- More than two tables in a single query are not supported.
- Only one `AND` level in `WHERE` is supported.
- `OR`, `NOT`, nested conditions, `ORDER BY`, `GROUP BY`, `HAVING`, `LIMIT`, `OFFSET`, and subqueries are not supported.

### UPDATE

Updates one or more columns for all rows matching an optional `WHERE` condition. Omitting `WHERE` updates all rows.

```sql
UPDATE table_name SET col = val [, col2 = val2 ...] [WHERE condition]
```

Examples:

```sql
UPDATE employees SET salary = 80000 WHERE id = 1
UPDATE employees SET salary = 60000, active = FALSE WHERE id = 3
UPDATE employees SET active = TRUE
```

### DELETE

Deletes all rows matching an optional `WHERE` condition. Omitting `WHERE` deletes all rows.

```sql
DELETE FROM table_name [WHERE condition]
```

Examples:

```sql
DELETE FROM employees WHERE salary < 20000
DELETE FROM employees WHERE id = 5
DELETE FROM employees
```

> `DELETE` without `WHERE` permanently removes every row in the table.

## WHERE conditions

The `WHERE` clause supports these predicate forms. At most one `AND` between two predicates is supported.

| Predicate | Syntax | Index usable? |
|---|---|---|
| Equality | `col = value` | Yes — hash lookup |
| Less than | `col < value` | No — full scan |
| Greater than | `col > value` | No — full scan |
| Less or equal | `col <= value` | No — treated as `<` |
| Greater/equal | `col >= value` | No — treated as `>` |
| Range | `col BETWEEN low AND high` | No — full scan |
| Join equality | `table.col = table.col` | N/A |
| AND | `condition AND condition` | Depends on each part |

The Linear Hash index only supports equality (`=`) lookups. All other predicates force a full sequential scan even if an index exists.

## Set operations

Set operations combine the results of exactly two `SELECT` statements. Both sides must produce compatible records with the same column count and types. Duplicate elimination is automatic.

### UNION

Returns all rows that appear in either result set.

```sql
SELECT * FROM t1 [WHERE ...]
UNION
SELECT * FROM t2 [WHERE ...]
```

Example:

```sql
SELECT * FROM full_time_employees WHERE active = TRUE
UNION
SELECT * FROM contractors WHERE active = TRUE
```

### INTERSECT

Returns only rows that appear in both result sets.

```sql
SELECT * FROM t1 [WHERE ...]
INTERSECT
SELECT * FROM t2 [WHERE ...]
```

### EXCEPT

Returns rows that appear in the left result set but not in the right.

```sql
SELECT * FROM t1 [WHERE ...]
EXCEPT
SELECT * FROM t2 [WHERE ...]
```

Example:

```sql
SELECT * FROM all_employees
EXCEPT
SELECT * FROM on_leave_employees
```

Limitations:

- Chained set operations such as `A UNION B UNION C` are not supported.
- `UNION ALL` is not supported.
- All set operations deduplicate.

## Query optimizer

The optimizer is rule-based with cost validation. It makes three independent decisions per query, logs each decision with cost estimates, and exposes the trace through `EXPLAIN`.

### Decision 1 — Access method

| Condition | Chosen operator | Cost basis |
|---|---|---|
| No `WHERE` clause | `SeqScan` | Reads all pages |
| Equality (`=`) with index on column | `SelectIndex` | 1 index page + matching heap pages |
| Equality (`=`) with no index | `SelectLinear` | Total page count |
| Range predicate | `SelectLinear` | Hash index cannot serve range queries |
| Index exists but sequential scan is cheaper | `SelectLinear` | Cost comparison decides |

Selectivity for equality predicates is estimated from the column histogram for `INT` columns or the distinct value count for `CHAR` and `BOOLEAN` columns maintained by `TableStats`.

### Decision 2 — Join algorithm

| Condition | Chosen algorithm | Cost basis |
|---|---|---|
| `PREFER_BNL` hint | Block Nested Loop | User override |
| `PREFER_MERGE` hint | Merge Join | User override |
| `AUTO` (default) | Lower cost wins | BNL cost vs Merge Join cost compared |
| Small relations | BNL preferred | Sort overhead not worth it |
| Large relations | Merge Join preferred | Sort cost amortised over large scan |

BNL cost is estimated as `ceil(outerRows / blockSize) × innerRows`. Merge Join cost is estimated as `N log N` for sorting plus `N + M` for merge.

### Decision 3 — DISTINCT method

| Context | Chosen algorithm | Reason |
|---|---|---|
| After a JOIN | Sort-based distinct | Output may be partially sorted; dedup is cheaper |
| Single-table query | Hash-based distinct | `O(n)` single pass, no sort needed |

### EXPLAIN

Use the `EXPLAIN` flag in the UI or pass `explain=true` to `StatementExecutor.execute()` to receive the optimizer trace with query results. The trace shows:

1. The logical plan tree.
2. Each decision point with the chosen operator.
3. The estimated cost of the chosen option and the rejected alternative.
4. The reasoning behind each choice in plain text.

## Execution operators

All operators use bulk materialization, so `execute()` returns `List<Record>`. Operators are composed into a tree and each calls its children first.

### Scan operators

| Operator | Triggered by | Description |
|---|---|---|
| `SeqScanOperator` | `SELECT` with no `WHERE` | Reads every live slot in every heap page in order. |
| `SelectLinearOperator` | `SELECT` with non-equality or no index | Scans heap, applies predicate, returns matches. |
| `SelectIndexOperator` | `SELECT` with equality + index on column | Hash lookup in index, then fetch matching heap records. |

### Join operators

| Operator | Algorithm | Description |
|---|---|---|
| `BNLJoinOperator` | Block Nested Loop | Outer relation in blocks of 32, inner held in memory. |
| `MergeJoinOperator` | Sort-Merge | Simulates external sort, then performs a single merge pass. |

### Delete operators

| Operator | Used when | Description |
|---|---|---|
| `DeleteLinearOperator` | No index, or range/non-equality predicate | Full scan, deletes matching rows, updates all indexes. |
| `DeleteIndexOperator` | Equality predicate + index on that column | Index lookup to find RIDs, then deletes from heap and all indexes. |

### Distinct operators

| Operator | Cost | Description |
|---|---|---|
| `SortBasedDistinctOperator` | `O(n log n)` | Sorts all records, then removes consecutive duplicates. |
| `HashBasedDistinctOperator` | `O(n)` | Single pass over input, keyed by full row value. |

### Set operators

| Operator | SQL keyword | Algorithm |
|---|---|---|
| `UnionOperator` | `UNION` | Larger relation streamed into seen-map; smaller relation emits only unseen rows. |
| `IntersectionOperator` | `INTERSECT` | Smaller relation into hash map; larger probes and emits on match. |
| `DifferenceOperator` | `EXCEPT` | Right into set, filter left, or left into map and cancel against right. |

> All set operators assume at least one relation fits entirely in memory. If neither does, the operation may still run but can cause `OutOfMemoryError` on very large datasets.

## Index reference — Linear Hash

The only supported index type is a disk-based Linear Hash index. It provides average `O(1)` lookup for exact equality queries.

### How it works

| Concept | Explanation |
|---|---|
| `i` (bit depth) | Number of least-significant bits used to select the bucket. Starts at 1 and increases as more buckets are unlocked. |
| `M` (split pointer) | Index of the last unlocked bucket. Buckets `0..M` are active. New buckets unlock at `M+1`. |
| Bucket lookup | `hash(key) & ((1<<i)-1) → bucket b`. If `b > M`, redirect with `b = b - 2^(i-1)`. |
| Overflow chain | When a bucket page is full, a new overflow page is appended and linked via a 4-byte pointer. |
| Split (insert) | After each insert, if utilization is above 80%, unlock bucket `M+1` and redistribute from its temp bucket. |
| Merge (delete) | After each delete, if utilization is below 80% and `M > 0`, merge bucket `M` back into its temp bucket. |
| Disk layout | Page 0 is the header. All other pages are bucket or overflow pages. |

### Bucket page layout

```text
[overflowPageNum : 4 bytes] [entryCount : 2 bytes] [entries ...]
```

Each entry:

```text
[key bytes] [tableId : 4B] [pageNumber : 4B] [slotNumber : 4B]
```

### Hash functions

| Column type | Hash formula |
|---|---|
| INT | `value & 0x7FFFFFFF` |
| BOOLEAN | `1` for TRUE, `0` for FALSE |
| CHAR(n) | `String.hashCode() & 0x7FFFFFFF` |

### Index limitations

1. Only equality (`=`) predicates use the index.
2. Range predicates (`<`, `>`, `BETWEEN`) always fall back to a full sequential scan.
3. Only single-column indexes are supported.
4. Maximum of 60 buckets per index (`i ≤ 5`).
5. The index file is fully self-contained on disk and survives application restarts.

## Unsupported features

### SQL features

| Feature | Notes |
|---|---|
| Column projection | `SELECT *` only. `SELECT col1, col2` is not supported. |
| Joins with 3+ tables | At most two tables per query. |
| `OR`, `NOT` conditions | Only simple predicates and one `AND` level are supported. |
| `ORDER BY` / `GROUP BY` | No sorting or aggregation in output. |
| `HAVING` / `LIMIT` / `OFFSET` | Not implemented. |
| Subqueries | No nested `SELECT`. |
| Aggregate functions | `COUNT`, `SUM`, `AVG`, `MIN`, `MAX` are not supported. |
| `NULL` values | No `NULL`; all columns are non-null. |
| `UNION ALL` | All set operations deduplicate. |
| Chained set operations | `A UNION B UNION C` is not supported. |
| `<=` and `>=` as distinct ops | Treated as strict `<` and `>` respectively. |
| Named `INSERT` column lists | `INSERT INTO t (col1, col2) VALUES (...)` is not supported. |
| `ALTER TABLE` | No schema modification after table creation. |

### Engine features

| Feature | Notes |
|---|---|
| Concurrency / transactions | Single-user, no locking, no rollback. |
| Crash recovery | No write-ahead log or checksums. |
| Variable-length types | `VARCHAR`, `TEXT`, `BLOB` are not supported. |
| Multiple index types | Only Linear Hash. |
| Composite indexes | Single-column only. |
| Range index scans | Hash index cannot serve range predicates. |
| Disk-spilling joins | All join data must fit in memory from at least one relation. |
| Catalog persistence | Schema is in-memory only; must be re-seeded on startup. |
| Statistics persistence | Histograms are rebuilt from inserts at runtime. |

## Small future improvements

To make the engine more usable for smaller real-world demos, these would help most:

- Query parsing enhancements so multiple statements can be entered and executed in one batch.
- Insert one statement at a time from the UI instead of forcing a single-query workflow.
- Catalog persistence so schemas survive restarts without needing to be re-created.
