-- — lets a vulnerability that came from an import be deleted.
--
-- Found while testing the real stack, after V9 had already been applied: a vulnerability
-- created by an import could no longer be deleted. The `scan_findings` row pointing at it
-- held the deletion back, `DELETE /vulnerabilities/{id}` answered 409 and, by the children
-- rule, its asset and its project got stuck as well.
--
-- A new migration, and not a fix to V9, because V9 has already run: Flyway validates the
-- checksum of every applied migration, and editing it in place would turn `docker compose up`
-- into a crash loop for any database that had already received it. It is the same reason V6
-- gives, and it holds just as well when the only machine that applied it is the developer's.
--
-- `VulnerabilityService.delete` already documents the rule this file extends: a child with no
-- deletion endpoint of its own follows the parent instead of blocking it, otherwise a single
-- comment is enough to make a record indestructible. The imported finding is exactly that case.

-- The finding stays IMPORTED after the vulnerability is gone, and that is on purpose.
--
-- The `scan_findings` table is the history of what the report found and of what was done with
-- it. The import did import the finding, and the `imported_count` of that import stays true;
-- what changed afterwards was the fate of the vulnerability, not the decision taken in the
-- review. Marking it SKIPPED would make the history lie about an import that skipped
-- nothing.
--
-- The previous equivalence — IMPORTED if and only if there is a vulnerability — becomes an
-- implication in one direction only: it stays impossible for a row that was not imported to
-- carry a vulnerability, and it becomes possible for an imported row to have lost its own.
ALTER TABLE scan_findings
    DROP CONSTRAINT ck_scan_findings_status_vulnerability;

ALTER TABLE scan_findings
    ADD CONSTRAINT ck_scan_findings_status_vulnerability
        CHECK (status = 'IMPORTED' OR vulnerability_id IS NULL);

-- The database is what undoes the link, not the service.
--
-- The alternative was for `VulnerabilityService` to clear the `scan_findings` rows before
-- deleting, the way it already does with comments and attachments. The database solves it
-- better here: there is no deletion path that can forget to call the cleanup, and the
-- vulnerability module does not have to start knowing about the import one just for this.
ALTER TABLE scan_findings
    DROP CONSTRAINT fk_scan_findings_vulnerability;

ALTER TABLE scan_findings
    ADD CONSTRAINT fk_scan_findings_vulnerability
        FOREIGN KEY (vulnerability_id) REFERENCES vulnerabilities (id)
            ON DELETE SET NULL;
