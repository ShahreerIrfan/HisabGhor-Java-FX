package com.smartkhata.util;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.events.Event;
import com.itextpdf.kernel.events.IEventHandler;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.smartkhata.model.dto.ReportData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Clean, production-grade PDF report exporter with business letterhead,
 * professional typography, currency formatting, subtotals, and page numbers.
 */
public final class PdfReportExporter {

    private static final Logger log = LoggerFactory.getLogger(PdfReportExporter.class);
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final DeviceRgb BRAND_PRIMARY = new DeviceRgb(30, 96, 213);   // #1E60D5
    private static final DeviceRgb HEADER_BG = new DeviceRgb(241, 245, 249);      // #F1F5F9
    private static final DeviceRgb ZEBRA_BG = new DeviceRgb(248, 250, 252);       // #F8FAFC
    private static final DeviceRgb BORDER_COLOR = new DeviceRgb(226, 232, 240);   // #E2E8F0

    private PdfReportExporter() {}

    public static byte[] exportToPdf(ReportData data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);

        // Page Orientation: Landscape if many columns (> 5), otherwise Portrait
        boolean isLandscape = data.headers().size() > 5;
        PageSize pageSize = isLandscape ? PageSize.A4.rotate() : PageSize.A4;
        Document document = new Document(pdfDoc, pageSize);
        document.setMargins(36, 36, 45, 36);

        PdfFont font = PdfFontFactory.createFont(StandardFonts.HELVETICA);
        PdfFont boldFont = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);

        // Attach footer page numbering handler
        PageNumberHandler pageHandler = new PageNumberHandler(font);
        pdfDoc.addEventHandler(PdfDocumentEvent.END_PAGE, pageHandler);

        // 1. Business Letterhead Header
        Paragraph bizName = new Paragraph(data.businessName())
                .setFont(boldFont)
                .setFontSize(18)
                .setFontColor(BRAND_PRIMARY)
                .setMarginBottom(2);
        document.add(bizName);

        if (data.businessAddress() != null && !data.businessAddress().isBlank()) {
            document.add(new Paragraph(data.businessAddress())
                    .setFont(font)
                    .setFontSize(9)
                    .setFontColor(ColorConstants.DARK_GRAY)
                    .setMarginBottom(1));
        }

        if (data.businessPhone() != null && !data.businessPhone().isBlank()) {
            document.add(new Paragraph("Phone: " + data.businessPhone())
                    .setFont(font)
                    .setFontSize(9)
                    .setFontColor(ColorConstants.DARK_GRAY)
                    .setMarginBottom(4));
        }

        // Report Title & Period Banner
        Paragraph title = new Paragraph(data.title().toUpperCase())
                .setFont(boldFont)
                .setFontSize(13)
                .setFontColor(ColorConstants.BLACK)
                .setMarginTop(8)
                .setMarginBottom(2);
        document.add(title);

        Paragraph period = new Paragraph(data.periodOrAsOfLabel() + " | Generated: " + data.generatedAt().format(DATE_TIME_FMT))
                .setFont(font)
                .setFontSize(9)
                .setFontColor(ColorConstants.GRAY)
                .setMarginBottom(12);
        document.add(period);

        // 2. Summary KPI Cards (if present)
        if (data.summaryCards() != null && !data.summaryCards().isEmpty()) {
            Table summaryTable = new Table(UnitValue.createPercentArray(data.summaryCards().size())).useAllAvailableWidth();
            summaryTable.setMarginBottom(14);

            for (Map.Entry<String, String> entry : data.summaryCards().entrySet()) {
                Cell cardCell = new Cell()
                        .setBackgroundColor(HEADER_BG)
                        .setBorder(new SolidBorder(BORDER_COLOR, 1))
                        .setPadding(8);

                cardCell.add(new Paragraph(entry.getKey())
                        .setFont(font)
                        .setFontSize(8)
                        .setFontColor(ColorConstants.DARK_GRAY));
                cardCell.add(new Paragraph(entry.getValue())
                        .setFont(boldFont)
                        .setFontSize(11)
                        .setFontColor(BRAND_PRIMARY));

                summaryTable.addCell(cardCell);
            }
            document.add(summaryTable);
        }

        // 3. Tabular Report Data
        List<String> headers = data.headers();
        Table dataTable = new Table(UnitValue.createPercentArray(headers.size())).useAllAvailableWidth();
        dataTable.setMarginBottom(16);

        // Header Row
        for (String headerText : headers) {
            Cell hCell = new Cell()
                    .add(new Paragraph(headerText).setFont(boldFont).setFontSize(9).setFontColor(ColorConstants.BLACK))
                    .setBackgroundColor(HEADER_BG)
                    .setBorder(new SolidBorder(BORDER_COLOR, 1))
                    .setPadding(6);

            if (headerText.toLowerCase().contains("amount") || headerText.toLowerCase().contains("due")
                    || headerText.toLowerCase().contains("paid") || headerText.toLowerCase().contains("price")
                    || headerText.toLowerCase().contains("balance") || headerText.toLowerCase().contains("stock")) {
                hCell.setTextAlignment(TextAlignment.RIGHT);
            }
            dataTable.addHeaderCell(hCell);
        }

        // Body Rows
        int rowIndex = 0;
        for (List<String> row : data.rows()) {
            boolean isZebra = (rowIndex % 2 == 1);
            for (int colIndex = 0; colIndex < row.size(); colIndex++) {
                String val = row.get(colIndex);
                Cell bCell = new Cell()
                        .add(new Paragraph(val != null ? val : "—").setFont(font).setFontSize(8))
                        .setBorder(new SolidBorder(BORDER_COLOR, 0.5f))
                        .setPadding(5);

                if (isZebra) {
                    bCell.setBackgroundColor(ZEBRA_BG);
                }

                String h = headers.get(colIndex).toLowerCase();
                if (h.contains("amount") || h.contains("due") || h.contains("paid")
                        || h.contains("price") || h.contains("balance") || h.contains("stock")) {
                    bCell.setTextAlignment(TextAlignment.RIGHT);
                }
                dataTable.addCell(bCell);
            }
            rowIndex++;
        }

        document.add(dataTable);
        document.close();

        return baos.toByteArray();
    }

    private static class PageNumberHandler implements IEventHandler {
        private final PdfFont font;

        PageNumberHandler(PdfFont font) {
            this.font = font;
        }

        @Override
        public void handleEvent(Event event) {
            PdfDocumentEvent docEvent = (PdfDocumentEvent) event;
            PdfDocument pdfDoc = docEvent.getDocument();
            PdfPage page = docEvent.getPage();
            int pageNum = pdfDoc.getPageNumber(page);

            Rectangle pageSize = page.getPageSize();
            PdfCanvas pdfCanvas = new PdfCanvas(page.newContentStreamAfter(), page.getResources(), pdfDoc);
            Canvas canvas = new Canvas(pdfCanvas, pageSize);

            String text = "SmartKhata Financial Reports  •  Page " + pageNum;
            canvas.showTextAligned(new Paragraph(text).setFont(font).setFontSize(8).setFontColor(ColorConstants.GRAY),
                    pageSize.getWidth() / 2, 20, TextAlignment.CENTER);
            canvas.close();
        }
    }
}
