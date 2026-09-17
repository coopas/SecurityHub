package com.securityhub.report;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;

/**
 * "Página X de Y" on every page.
 *
 * <p>Y is unknown while the pages are being written, so each page draws the prefix and then places
 * the same {@link PdfTemplate} after it. The template is a single form XObject referenced by every
 * page; filling it once at {@code onCloseDocument} — after the last {@code onEndPage} and before
 * the writer serialises it — is what puts the final count on all of them.
 *
 * <p>The count is tallied here instead of read from {@code writer.getPageNumber()} at close time,
 * which is already past the last page and differs by one between iText lineages. A field
 * incremented per page has no such ambiguity.
 */
class PageFooter extends PdfPageEventHelper {

    private static final String PREFIX = "Página ";
    private static final String INFIX = " de ";

    /** Wide enough for three digits at the footer size; a report is nowhere near a thousand pages. */
    private static final float TEMPLATE_WIDTH = 40f;
    private static final float TEMPLATE_HEIGHT = 12f;

    /** Below the bottom margin, in the gutter the content never reaches. */
    private static final float BASELINE_BELOW_MARGIN = 22f;

    private final BaseFont baseFont;
    private final float size;

    private PdfTemplate total;
    private int pages;

    PageFooter(ReportFonts fonts) {
        Font font = fonts.footer();
        this.baseFont = font.getBaseFont();
        this.size = font.getSize();
    }

    @Override
    public void onOpenDocument(PdfWriter writer, Document document) {
        total = writer.getDirectContent().createTemplate(TEMPLATE_WIDTH, TEMPLATE_HEIGHT);
    }

    @Override
    public void onEndPage(PdfWriter writer, Document document) {
        pages++;
        String label = PREFIX + pages + INFIX;
        float baseline = document.bottom() - BASELINE_BELOW_MARGIN;

        PdfContentByte canvas = writer.getDirectContent();
        canvas.beginText();
        canvas.setFontAndSize(baseFont, size);
        canvas.showTextAligned(Element.ALIGN_LEFT, label, document.left(), baseline, 0f);
        canvas.endText();
        // Butted against the measured width of the prefix so the two halves read as one string.
        canvas.addTemplate(total, document.left() + baseFont.getWidthPoint(label, size), baseline);
    }

    @Override
    public void onCloseDocument(PdfWriter writer, Document document) {
        total.beginText();
        total.setFontAndSize(baseFont, size);
        total.showTextAligned(Element.ALIGN_LEFT, String.valueOf(pages), 0f, 0f, 0f);
        total.endText();
    }
}
