package com.securityhub.scan;

/**
 * What the importer decided about one finding, and what the operator may still change.
 *
 * <p>The three staging values are assigned at upload: {@code MATCHED} when the target resolved
 * to an asset of the chosen project, {@code UNMATCHED} when it did not, {@code DUPLICATE} when
 * a vulnerability of this company already carries the same fingerprint. The two terminal values
 * are assigned at confirm: {@code IMPORTED} for the findings that became a vulnerability and
 * {@code SKIPPED} for every other one.
 *
 * <p>{@code DUPLICATE} wins over {@code MATCHED} on purpose. A duplicate that matched an asset
 * is still a duplicate, and re-importing it would create a second copy of a finding somebody is
 * already working on — see the javadoc of {@code ScanImportService#confirm}.
 */
public enum ScanFindingStatus {
    MATCHED,
    UNMATCHED,
    DUPLICATE,
    IMPORTED,
    SKIPPED
}
