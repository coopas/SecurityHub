# ADR 0008 — OpenPDF for the executive report

- Status: accepted
- Date: 2026-09-17

## Context

The executive report has to be a PDF a manager can be sent. The project had no PDF library.

Generating it in the browser was considered first. It would add roughly 350 KB to a bundle
whose initial budget warns at 1.5 MB, and — decisively — it would recompute the dashboard
figures in TypeScript. The dashboard DTOs already carry a comment saying each number must have
exactly one home; a second implementation is how a report ends up disagreeing with the screen
it summarises.

## Decision

Generate the PDF on the server with **`com.github.librepdf:openpdf`**, pinned in `<properties>`
alongside the existing flyway, postgresql and docker-api pins.

The report calls `DashboardService` and reuses its aggregations unchanged. It adds only two
queries of its own, for the lists of critical-open and overdue findings.

### Alternatives

- **iText 5 / iText 7** — AGPL or commercial. Not compatible with what an MIT repository leads
  a reader to expect.
- **Apache PDFBox** — permissively licensed, but its API is a content stream: every string is
  positioned by hand and there are no table or flow primitives. A report with seven sections of
  tables becomes hundreds of lines of coordinate arithmetic.
- **JasperReports** — a template compiler and a large dependency tree for one document.

OpenPDF is a maintained fork of the last LGPL/MPL iText, runs on Java 8+, and provides
`Document`, `PdfPTable`, `Paragraph` and page events, which is the whole report.

## Fonts, which is where this usually goes wrong

The report is rendered with an **embedded TrueType font using `IDENTITY_H`**, never the
built-in base-14 fonts with WinAnsi encoding.

WinAnsi (Cp1252) does cover Portuguese, so the accents in our own labels would be fine. The
problem is that the report prints **strings the user typed**: company names, project names,
vulnerability titles. Anything outside Cp1252 renders as a blank box, silently, with nothing in
the pipeline reporting it.

Two failure modes to avoid explicitly:

- `IDENTITY_H` with `NOT_EMBEDDED` produces a document referencing glyph ids from a font the
  viewer does not have. That renders as garbage rather than blanks, which is worse because it
  looks like file corruption.
- `FontFactory.getFont(...)` and `new Font(Font.HELVETICA, ...)` both resolve to a
  non-embedded base-14 font and quietly reintroduce the problem.

One font file ships, `DejaVuSans.ttf`, with bold simulated by OpenPDF rather than a second
file. `EMBEDDED` subsets by default, so the generated PDF grows by the glyphs actually used —
tens of kilobytes, not the 742 KB of the source file.

## Consequences

- One compile dependency. LGPL obligations attach on distribution of a combined work and are
  met by depending on the unmodified published artifact; this is a server application that is
  not distributed.
- `DejaVuSans.ttf` is committed under `backend/src/main/resources/fonts/`, with its Bitstream
  Vera licence text beside it. Redistribution is permitted; shipping the licence is the part
  that is usually forgotten.
- **`org.apache.pdfbox:pdfbox` is added at test scope only**, for `PDFTextStripper`. A PDF that
  renders `Injeç?o` is a defect no byte-level assertion can catch, and accent encoding is the
  most likely thing to break here. This is the one place a dependency is added purely for a
  test.
- Generation is synchronous: six indexed aggregate queries and about sixty table rows, well
  under a second. Making it asynchronous would mean a job store, a status endpoint and a second
  authorization check on retrieval — an entire subsystem for a sub-second operation.
