package com.smartkhata.util;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.smartkhata.model.dto.PurchaseOrderDetailDto;
import com.smartkhata.model.dto.PurchaseOrderItemViewDto;
import com.smartkhata.model.entity.Business;
import com.smartkhata.model.entity.PurchaseOrder;
import com.smartkhata.model.entity.Supplier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;

public final class PurchaseOrderPdfExporter {

    private static final DeviceRgb GROCERY_GREEN = new DeviceRgb(14, 123, 58);
    private static final DeviceRgb HEADER_BG = new DeviceRgb(240, 253, 244);
    private static final DeviceRgb BORDER_COLOR = new DeviceRgb(226, 232, 240);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private PurchaseOrderPdfExporter() {}

    public static byte[] exportToPdf(Business business, PurchaseOrderDetailDto detail) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document doc = new Document(pdfDoc, PageSize.A4);
        doc.setMargins(36, 36, 36, 36);

        PdfFont font = PdfFontFactory.createFont(StandardFonts.HELVETICA);
        PdfFont bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);

        PurchaseOrder po = detail.order();
        Supplier sup = detail.supplier();

        doc.add(new Paragraph(business != null ? business.name() : "SmartKhata Store")
                .setFont(bold).setFontSize(18).setFontColor(GROCERY_GREEN).setMarginBottom(2));
        if (business != null && business.address() != null) {
            doc.add(new Paragraph(business.address()).setFont(font).setFontSize(9).setFontColor(ColorConstants.DARK_GRAY));
        }

        doc.add(new Paragraph("PURCHASE ORDER — " + po.poNumber())
                .setFont(bold).setFontSize(14).setMarginTop(10).setMarginBottom(4));
        doc.add(new Paragraph("Status: " + po.status()
                + "   |   PO Date: " + po.poDate().format(DT_FMT)
                + (po.expectedDeliveryDate() != null ? "   |   Expected Delivery: " + po.expectedDeliveryDate() : ""))
                .setFont(font).setFontSize(9.5f).setFontColor(ColorConstants.DARK_GRAY));

        if (sup != null) {
            doc.add(new Paragraph("Supplier: " + sup.name()
                    + (sup.supplierCode() != null ? " (" + sup.supplierCode() + ")" : "")
                    + (sup.phone() != null ? "   |   Phone: " + sup.phone() : "")
                    + (sup.paymentTerms() != null ? "   |   Terms: " + sup.paymentTerms() : ""))
                    .setFont(font).setFontSize(9.5f).setMarginTop(4).setMarginBottom(10));
        }

        Table table = new Table(new float[]{4, 2, 1.5f, 2, 1.5f, 1.5f, 2}).setWidth(UnitValue.createPercentValue(100));
        String[] headers = {"Product", "Unit / Pack", "Qty", "Trade Price", "Discount", "Tax", "Line Total"};
        for (String h : headers) {
            table.addHeaderCell(new Cell().add(new Paragraph(h).setFont(bold).setFontSize(9))
                    .setBackgroundColor(HEADER_BG).setBorder(new SolidBorder(BORDER_COLOR, 0.75f)));
        }

        for (PurchaseOrderItemViewDto item : detail.items()) {
            table.addCell(cell(item.productName(), font, TextAlignment.LEFT));
            table.addCell(cell(item.purchaseUnit() + " (x" + item.packSize() + ")", font, TextAlignment.LEFT));
            table.addCell(cell(String.valueOf(item.orderedQty()), font, TextAlignment.RIGHT));
            table.addCell(cell(BengaliMoneyFormatter.format(item.tradePrice()), font, TextAlignment.RIGHT));
            table.addCell(cell(BengaliMoneyFormatter.format(item.discount()), font, TextAlignment.RIGHT));
            table.addCell(cell(BengaliMoneyFormatter.format(item.tax()), font, TextAlignment.RIGHT));
            table.addCell(cell(BengaliMoneyFormatter.format(item.lineTotal()), bold, TextAlignment.RIGHT));
        }
        doc.add(table);

        doc.add(new Paragraph(
                String.format("Subtotal: %s   |   Discount: %s   |   Tax: %s   |   Charges: %s",
                        BengaliMoneyFormatter.format(po.subtotal()),
                        BengaliMoneyFormatter.format(po.discount()),
                        BengaliMoneyFormatter.format(po.tax()),
                        BengaliMoneyFormatter.format(po.additionalCharges())))
                .setFont(font).setFontSize(9.5f).setTextAlignment(TextAlignment.RIGHT).setMarginTop(10));
        doc.add(new Paragraph("Grand Total: " + BengaliMoneyFormatter.format(po.grandTotal()))
                .setFont(bold).setFontSize(12).setFontColor(GROCERY_GREEN).setTextAlignment(TextAlignment.RIGHT));

        if (po.notes() != null && !po.notes().isBlank()) {
            doc.add(new Paragraph("Notes: " + po.notes()).setFont(font).setFontSize(9).setMarginTop(8));
        }

        doc.close();
        return baos.toByteArray();
    }

    private static Cell cell(String text, PdfFont font, TextAlignment align) {
        return new Cell().add(new Paragraph(text != null ? text : "").setFont(font).setFontSize(9))
                .setTextAlignment(align)
                .setBorder(new SolidBorder(BORDER_COLOR, 0.5f));
    }
}

