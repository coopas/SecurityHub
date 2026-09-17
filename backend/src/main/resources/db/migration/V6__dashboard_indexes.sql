-- — indexes for the dashboard aggregations .
--
-- A NEW migration, never an edit to V5: Flyway validates the checksum of every applied
-- migration, so changing V5 in place turns `docker compose up` on an existing volume into a
-- crash loop for everyone who already ran it.
--
-- HOW THIS WAS MEASURED
-- * scratch PostgreSQL 15, 200 000 vulnerabilities over 2 companies, 20 projects and
-- 500 assets, randomised severity, status, discovered_at, due_date and resolved_at;
-- heap 24 MB, 100 000 rows per tenant;
-- * `ANALYZE vulnerabilities` before every capture — without statistics the planner guesses
-- and every plan collected is meaningless;
-- * `EXPLAIN (ANALYZE, BUFFERS)` on the exact SQL of DashboardRepository, third consecutive
-- run so the cache state is stable, plus one pass with `enable_indexscan = off` and
-- `enable_bitmapscan = off` as the floor to compare against.
-- * "buffers" is the top-level `shared hit`. It is the honest metric here: wall-clock on a
-- warm 24 MB table understates the gain, because the whole table happens to be in cache.
--
-- query seq-scan floor V5 only with V6
-- summary scalars 3130 / 22.3 ms 3130 / 23.0 108 / 19.1 ms
-- summary topProjects 3182 / 35.0 ms 3182 / 40.9 509 / 26.8 ms
-- severity-distribution 3140 / 20.9 ms 85 / 15.8 85 / 13.0 ms
-- status-distribution 3140 / 20.7 ms 82 / 16.1 82 / 12.9 ms
-- trend?days=30 6283 / 28.1 ms 3159 / 16.5 27 / 4.7 ms
--
-- WHAT V5 ALREADY COVERS, AND IS NOT REPEATED HERE
-- * idx_vulnerabilities_company_severity and idx_vulnerabilities_company_status already
-- serve the two distribution endpoints as index-only scans (82-85 buffers). They get no
-- new index because they need none: what made them fast was writing `count(*)` instead of
-- `count(id)` in the JPQL, which keeps the primary key out of the aggregate and lets the
-- existing two-column index cover the whole query. Compare the seq-scan floor (3140
-- buffers) with the 82 they actually read.
-- * idx_vulnerabilities_company_discovered_at already serves the `opened` series of the
-- trend as an index-only scan. Only the `resolved` series was unindexed.

-- /dashboard/trend, `resolved` series — the clearest win of the three.
--
-- V5 indexed discovered_at but not resolved_at, so the trend ran one index-only scan and one
-- parallel sequential scan of the entire table for the same 30-day window.
-- 3 159 -> 27 buffers, 16.5 -> 4.7 ms warm, 33.3 -> 4.6 ms on a cold shared_buffers.
--
-- Partial for the same reason V5 made the assignee index partial: most rows of a healthy
-- backlog are unresolved, the trend never asks for them, and indexing those NULLs would only
-- make the index bigger. It costs 368 kB.
CREATE INDEX idx_vulnerabilities_company_resolved_at
    ON vulnerabilities (company_id, resolved_at)
    WHERE resolved_at IS NOT NULL;

-- /dashboard/summary, the five scalars.
--
-- Covering: company_id filters, and status, severity and due_date are every column the five
-- FILTER clauses touch, so the aggregate never visits the heap.
-- 3 130 -> 108 buffers, 23.0 -> 19.1 ms warm, 52.5 -> 18.3 ms cold. 1 592 kB.
--
-- Stated plainly: warm wall-clock improves by 17% and buffers by 29x. The buffer number is
-- the one that will still be true when the table no longer fits in shared_buffers, which is
-- also what the cold measurement shows.
CREATE INDEX idx_vulnerabilities_company_status_severity_due
    ON vulnerabilities (company_id, status, severity, due_date);

-- /dashboard/summary, the per-project breakdown.
--
-- Same idea one join earlier: asset_id is what the join to `assets` needs, status and due_date
-- are what the two FILTER clauses need, so the vulnerabilities side of the join is index-only
-- and only `assets` (500 rows) and `projects` (20 rows) are read from the heap.
-- 3 182 -> 509 buffers, 40.9 -> 26.8 ms warm, 64.8 -> 29.9 ms cold.
--
-- This is the expensive one: 7 200 kB, roughly 30% of the heap, because asset_id has high
-- cardinality and B-tree deduplication cannot fold it the way it folds the two enum columns
-- above. Kept because the gain is measurable at every cache state, but it is the first index
-- to reconsider if write throughput on this table ever becomes the constraint.
CREATE INDEX idx_vulnerabilities_company_asset_status_due
    ON vulnerabilities (company_id, asset_id, status, due_date);

-- Nothing is created for /severity-distribution or /status-distribution. "Indexes proven by
-- query plan WHEN NECESSARY" is explicit permission to conclude that an index is
-- not needed, and shipping a fourth index that costs write amplification on the busiest table
-- of the product while reading exactly the same 82 buffers as today would be a claimed
-- improvement rather than a real one.
