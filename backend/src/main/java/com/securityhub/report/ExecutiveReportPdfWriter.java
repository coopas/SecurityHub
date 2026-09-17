package com.securityhub.report;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.securityhub.company.Company;
import com.securityhub.dashboard.dto.DashboardSummaryResponse;
import com.securityhub.dashboard.dto.ProjectSummaryResponse;
import com.securityhub.dashboard.dto.SeverityDistributionResponse;
import com.securityhub.dashboard.dto.StatusDistributionResponse;
import com.securityhub.dashboard.dto.TrendPointResponse;
import com.securityhub.dashboard.dto.TrendResponse;
import com.securityhub.security.AuthenticatedUser;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Layout, and nothing else. It receives the numbers already computed, the two row lists already
 * read and the two identities already resolved, and answers the bytes of the PDF.
 *
 * <p>No repository, no {@code SecurityContext}, no {@code HttpServletResponse}: that is what lets
 * the hardest part of this feature — does an accented or non-Latin-1 string survive into the
 * document — be covered by a plain unit test with no Spring context and no database.
 *
 * <p>Every string is drawn with a {@link ReportFonts} font. See that class for why no font may be
 * built any other way.
 */
@Component
public class ExecutiveReportPdfWriter {

    private static final float MARGIN = 40f;
    private static final float BOTTOM_MARGIN = 54f;

    private static final Color HEADER_BACKGROUND = new Color(0xEE, 0xF1, 0xF5);
    private static final Color RULE = new Color(0xC8, 0xCE, 0xD6);
    private static final Color MUTED = new Color(0x55, 0x5B, 0x66);

    /** Portuguese formatting, pinned: the document is read by a person in pt-BR, not by a parser. */
    private static final Locale PT_BR = new Locale("pt", "BR");

    /** Nothing to show is printed as an em dash rather than left blank, so a gap is never a bug. */
    private static final String EMPTY = "—";

    private final ReportFonts fonts;

    public ExecutiveReportPdfWriter(ReportFonts fonts) {
        this.fonts = fonts;
    }

    public byte[] write(Company company, AuthenticatedUser generatedBy, Instant generatedAt,
                        DashboardSummaryResponse summary,
                        List<SeverityDistributionResponse> severities,
                        List<StatusDistributionResponse> statuses,
                        TrendResponse trend,
                        List<Object[]> criticalOpen,
                        List<Object[]> overdue) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        Document document = new Document(PageSize.A4, MARGIN, MARGIN, MARGIN, BOTTOM_MARGIN);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PageFooter(fonts));
            document.addTitle("Relatório Executivo de Vulnerabilidades");
            document.addCreator("SecurityHub");
            document.open();

            header(document, company, generatedBy, generatedAt);
            executiveSummary(document, summary);
            severityDistribution(document, severities);
            statusDistribution(document, statuses);
            trendLine(document, trend);
            topProjects(document, summary.getTopProjects());
            criticalOpen(document, criticalOpen);
            overdue(document, overdue);

            document.close();
        } catch (Exception ex) {
            // Document.add and PdfPTable.setWidths throw the checked DocumentException; there is
            // no recovery from a layout failure, and the caller must not have to catch one.
            throw new IllegalStateException("Falha ao gerar o relatório executivo em PDF", ex);
        }
        return out.toByteArray();
    }

    // --- 1. header ----------------------------------------------------------

    private void header(Document document, Company company, AuthenticatedUser generatedBy,
                        Instant generatedAt) throws Exception {
        document.add(paragraph("Relatório Executivo de Vulnerabilidades", fonts.h1(), 0f, 2f));
        document.add(paragraph(company.getName(), fonts.h2(), 0f, 8f));

        Font muted = fonts.body();
        muted.setColor(MUTED);
        document.add(paragraph("Gerado em " + DateTimeFormatter.ISO_INSTANT.format(generatedAt)
                + " (UTC) por " + generatedBy.getName() + " <" + generatedBy.getEmail() + ">",
                muted, 0f, 1f));
        // Stated on the document itself because the endpoint takes no date range: every figure
        // below is an aggregation "as of now", and a reader who assumes otherwise misreads all
        // of them.
        document.add(paragraph("Este é um retrato da situação no momento da geração.",
                muted, 0f, 12f));
    }

    // --- 2. executive summary -----------------------------------------------

    private void executiveSummary(Document document, DashboardSummaryResponse summary) throws Exception {
        document.add(sectionTitle("Resumo executivo"));
        PdfPTable table = table(new float[] {70f, 30f});
        table.addCell(headerCell("Indicador"));
        table.addCell(headerCell("Valor", Element.ALIGN_RIGHT));
        scalar(table, "Total de vulnerabilidades", summary.getTotalVulnerabilities());
        scalar(table, "Em aberto (OPEN + IN_PROGRESS)", summary.getOpenVulnerabilities());
        scalar(table, "Críticas em aberto", summary.getCriticalOpenVulnerabilities());
        scalar(table, "Atrasadas", summary.getOverdueVulnerabilities());
        scalar(table, "Resolvidas", summary.getResolvedVulnerabilities());
        scalar(table, "Projetos", summary.getTotalProjects());
        scalar(table, "Ativos", summary.getTotalAssets());
        document.add(table);
    }

    // --- 3 and 4. distributions ---------------------------------------------

    private void severityDistribution(Document document,
                                      List<SeverityDistributionResponse> severities) throws Exception {
        long total = 0L;
        for (SeverityDistributionResponse slice : severities) {
            total += slice.getCount();
        }
        document.add(sectionTitle("Distribuição por severidade"));
        PdfPTable table = distributionTable("Severidade");
        for (SeverityDistributionResponse slice : severities) {
            table.addCell(cell(slice.getSeverity().name()));
            table.addCell(cell(String.valueOf(slice.getCount()), Element.ALIGN_RIGHT));
            table.addCell(cell(percentage(slice.getCount(), total), Element.ALIGN_RIGHT));
        }
        document.add(table);
    }

    /** Twin of {@link #severityDistribution}, kept separate for the same reason the service does. */
    private void statusDistribution(Document document,
                                    List<StatusDistributionResponse> statuses) throws Exception {
        long total = 0L;
        for (StatusDistributionResponse slice : statuses) {
            total += slice.getCount();
        }
        document.add(sectionTitle("Distribuição por status"));
        PdfPTable table = distributionTable("Status");
        for (StatusDistributionResponse slice : statuses) {
            table.addCell(cell(slice.getStatus().name()));
            table.addCell(cell(String.valueOf(slice.getCount()), Element.ALIGN_RIGHT));
            table.addCell(cell(percentage(slice.getCount(), total), Element.ALIGN_RIGHT));
        }
        document.add(table);
    }

    // --- 5. the window ------------------------------------------------------

    /**
     * One line, not thirty rows. The daily series is a chart on the dashboard; on paper the only
     * thing an executive reads from it is whether the backlog grew or shrank, which is the
     * balance. Reproducing the table would push the seven sections that do carry a decision onto
     * a second page.
     */
    private void trendLine(Document document, TrendResponse trend) throws Exception {
        long opened = 0L;
        long resolved = 0L;
        for (TrendPointResponse point : trend.getPoints()) {
            opened += point.getOpened();
            resolved += point.getResolved();
        }
        long balance = opened - resolved;
        document.add(sectionTitle("Últimos " + trend.getDays() + " dias"));
        document.add(paragraph("De " + trend.getFrom() + " a " + trend.getTo() + " (UTC): "
                + opened + " aberta(s), " + resolved + " resolvida(s), saldo "
                + (balance > 0 ? "+" : "") + balance + ".", fonts.body(), 0f, 2f));
    }

    // --- 6. top projects ----------------------------------------------------

    private void topProjects(Document document, List<ProjectSummaryResponse> projects) throws Exception {
        document.add(sectionTitle("Projetos com mais vulnerabilidades"));
        if (projects.isEmpty()) {
            document.add(empty("Nenhum projeto com vulnerabilidades registradas."));
            return;
        }
        PdfPTable table = table(new float[] {52f, 16f, 16f, 16f});
        table.addCell(headerCell("Projeto"));
        table.addCell(headerCell("Total", Element.ALIGN_RIGHT));
        table.addCell(headerCell("Em aberto", Element.ALIGN_RIGHT));
        table.addCell(headerCell("Atrasadas", Element.ALIGN_RIGHT));
        for (ProjectSummaryResponse project : projects) {
            table.addCell(cell(project.getProjectName()));
            table.addCell(cell(String.valueOf(project.getTotal()), Element.ALIGN_RIGHT));
            table.addCell(cell(String.valueOf(project.getOpen()), Element.ALIGN_RIGHT));
            table.addCell(cell(String.valueOf(project.getOverdue()), Element.ALIGN_RIGHT));
        }
        document.add(table);
    }

    // --- 7 and 8. the two listings ------------------------------------------

    /** Columns as selected by {@code ReportRepository.criticalOpen}, in that order. */
    private void criticalOpen(Document document, List<Object[]> rows) throws Exception {
        document.add(sectionTitle("Vulnerabilidades críticas em aberto"));
        if (rows.isEmpty()) {
            document.add(empty("Nenhuma vulnerabilidade crítica em aberto."));
            return;
        }
        PdfPTable table = table(new float[] {24f, 12f, 13f, 14f, 13f, 8f, 16f});
        table.addCell(headerCell("Título"));
        table.addCell(headerCell("Projeto"));
        table.addCell(headerCell("Ativo"));
        table.addCell(headerCell("Responsável"));
        table.addCell(headerCell("Descoberta"));
        table.addCell(headerCell("CVSS", Element.ALIGN_RIGHT));
        table.addCell(headerCell("CVE"));
        for (Object[] row : rows) {
            table.addCell(cell(text(row[0])));
            table.addCell(cell(text(row[1])));
            table.addCell(cell(text(row[2])));
            table.addCell(cell(text(row[3])));
            table.addCell(cell(text(row[4])));
            table.addCell(cell(score(row[5]), Element.ALIGN_RIGHT));
            table.addCell(cell(text(row[6])));
        }
        document.add(table);
    }

    /** Columns as selected by {@code ReportRepository.overdue}, in that order. */
    private void overdue(Document document, List<Object[]> rows) throws Exception {
        document.add(sectionTitle("Atrasadas (prazo vencido)"));
        if (rows.isEmpty()) {
            document.add(empty("Nenhuma vulnerabilidade com prazo vencido."));
            return;
        }
        PdfPTable table = table(new float[] {36f, 14f, 14f, 18f, 18f});
        table.addCell(headerCell("Título"));
        table.addCell(headerCell("Severidade"));
        table.addCell(headerCell("Prazo"));
        table.addCell(headerCell("Responsável"));
        table.addCell(headerCell("Projeto"));
        for (Object[] row : rows) {
            table.addCell(cell(text(row[0])));
            table.addCell(cell(text(row[1])));
            table.addCell(cell(text(row[2])));
            table.addCell(cell(text(row[3])));
            table.addCell(cell(text(row[4])));
        }
        document.add(table);
    }

    // --- building blocks ----------------------------------------------------

    private Paragraph sectionTitle(String label) {
        return paragraph(label, fonts.h2(), 14f, 5f);
    }

    private Paragraph paragraph(String label, Font font, float before, float after) {
        Paragraph paragraph = new Paragraph(label, font);
        paragraph.setSpacingBefore(before);
        paragraph.setSpacingAfter(after);
        return paragraph;
    }

    private Paragraph empty(String label) {
        Font font = fonts.body();
        font.setColor(MUTED);
        return paragraph(label, font, 0f, 2f);
    }

    private PdfPTable table(float[] widths) throws Exception {
        PdfPTable table = new PdfPTable(widths.length);
        table.setWidths(widths);
        table.setWidthPercentage(100f);
        // The header repeats on every page it spills onto; a continuation table with no header
        // is a column of numbers nobody can read.
        table.setHeaderRows(1);
        table.setSpacingBefore(2f);
        table.setSpacingAfter(4f);
        return table;
    }

    private PdfPTable distributionTable(String firstColumn) throws Exception {
        PdfPTable table = table(new float[] {60f, 20f, 20f});
        table.addCell(headerCell(firstColumn));
        table.addCell(headerCell("Quantidade", Element.ALIGN_RIGHT));
        table.addCell(headerCell("%", Element.ALIGN_RIGHT));
        return table;
    }

    private void scalar(PdfPTable table, String label, long value) {
        table.addCell(cell(label));
        table.addCell(cell(String.valueOf(value), Element.ALIGN_RIGHT));
    }

    private PdfPCell headerCell(String label) {
        return headerCell(label, Element.ALIGN_LEFT);
    }

    private PdfPCell headerCell(String label, int alignment) {
        PdfPCell cell = baseCell(label, fonts.bold(), alignment);
        cell.setBackgroundColor(HEADER_BACKGROUND);
        return cell;
    }

    private PdfPCell cell(String label) {
        return cell(label, Element.ALIGN_LEFT);
    }

    private PdfPCell cell(String label, int alignment) {
        return baseCell(label, fonts.body(), alignment);
    }

    private PdfPCell baseCell(String label, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(label, font));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(4f);
        cell.setBorderColor(RULE);
        return cell;
    }

    private String text(Object value) {
        if (value == null) {
            return EMPTY;
        }
        String label = value.toString().trim();
        return label.isEmpty() ? EMPTY : label;
    }

    /**
     * {@code cvss_score} is {@code numeric(3,1)} and arrives as whatever Hibernate chooses for a
     * native result, hence {@link Number}; {@code toPlainString} keeps 9.8 from ever printing as
     * 9.8E0.
     */
    private String score(Object value) {
        if (value == null) {
            return EMPTY;
        }
        Number number = (Number) value;
        return number instanceof BigDecimal
                ? ((BigDecimal) number).toPlainString()
                : String.valueOf(number.doubleValue());
    }

    /** Zero total is the empty tenant, not a division by zero. */
    private String percentage(long count, long total) {
        double share = total == 0L ? 0d : (100d * count) / total;
        return String.format(PT_BR, "%.1f%%", share);
    }
}
