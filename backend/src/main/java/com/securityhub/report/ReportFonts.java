package com.securityhub.report;

import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

/**
 * The single source of every {@link Font} the executive report uses.
 *
 * <p>The TTF is parsed once, at startup, and the resulting {@link BaseFont} is shared: parsing a
 * 740 KB font file on every request would be pure waste, and {@code BaseFont} is immutable once
 * created.
 *
 * <p>Three rules this class exists to enforce (docs/adr/0008):
 *
 * <ul>
 * <li>never {@code new Font(Font.HELVETICA, ...)} and never {@code FontFactory.getFont(...)}.
 * Both resolve to a non-embedded base-14 font with WinAnsi encoding, which silently drops
 * every character outside Cp1252 — and the report prints strings the user typed: company,
 * project and vulnerability names. That is the bug the embedding exists to prevent, and
 * reintroducing it costs one line;</li>
 * <li>never {@code IDENTITY_H} with {@code NOT_EMBEDDED}. The document would reference glyph
 * ids of a font the viewer does not have, which renders as garbage rather than as blanks —
 * worse, because it looks like a corrupt file rather than a missing accent;</li>
 * <li>one font file only. Bold is {@code Font.BOLD} on the same embedded base font, simulated
 * by OpenPDF, rather than a second TTF to ship and license. {@code EMBEDDED} subsets, so the
 * output grows by the glyphs actually used — tens of kilobytes, not 740.</li>
 * </ul>
 */
@Component
public class ReportFonts {

    private static final String RESOURCE = "fonts/DejaVuSans.ttf";

    /**
     * The name handed to {@code createFont} still has to end in {@code .ttf}: it is what selects
     * the TrueType parser for the {@code byte[]} that follows, not a path that gets opened.
     */
    private static final String FONT_NAME = "DejaVuSans.ttf";

    private static final float BODY_SIZE = 9f;
    private static final float H1_SIZE = 18f;
    private static final float H2_SIZE = 12f;
    private static final float FOOTER_SIZE = 8f;

    private final BaseFont base;

    ReportFonts() {
        this.base = load();
    }

    public Font body() {
        return new Font(base, BODY_SIZE, Font.NORMAL);
    }

    public Font bold() {
        return new Font(base, BODY_SIZE, Font.BOLD);
    }

    public Font h1() {
        return new Font(base, H1_SIZE, Font.BOLD);
    }

    public Font h2() {
        return new Font(base, H2_SIZE, Font.BOLD);
    }

    public Font footer() {
        return new Font(base, FOOTER_SIZE, Font.NORMAL);
    }

    /**
     * The bytes are read from the {@link ClassPathResource} and passed to the {@code byte[]}
     * overload on purpose. The {@code String path} overload resolves the name against the file
     * system and the classloader with rules of its own, and inside the fat jar produced by
     * {@code spring-boot-maven-plugin} the font is an entry of a nested archive rather than a
     * file — it works in the IDE and fails in the packaged application.
     *
     * <p>A failure here is fatal at startup rather than at the first request: a report that
     * cannot embed its font has no fallback that would not be the WinAnsi bug above.
     */
    private static BaseFont load() {
        try (InputStream stream = new ClassPathResource(RESOURCE).getInputStream()) {
            byte[] bytes = FileCopyUtils.copyToByteArray(stream);
            return BaseFont.createFont(FONT_NAME, BaseFont.IDENTITY_H, BaseFont.EMBEDDED,
                    BaseFont.CACHED, bytes, null);
        // Exception and not IOException: com.lowagie.text.DocumentException is checked too.
        } catch (Exception ex) {
            throw new IllegalStateException("Não foi possível carregar a fonte " + RESOURCE, ex);
        }
    }
}
