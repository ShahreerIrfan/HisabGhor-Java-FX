package com.smartkhata.util;

import com.smartkhata.model.dto.ReportData;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

/**
 * Robust RFC 4180 compliant CSV generator supporting Unicode (Bengali & English)
 * and UTF-8 Byte Order Mark (BOM) for native Microsoft Excel rendering.
 */
public final class CsvExporter {

    private static final String UTF8_BOM = "\uFEFF";

    private CsvExporter() {}

    public static String exportToCsv(ReportData data) {
        StringWriter sw = new StringWriter();
        sw.write(UTF8_BOM); // Excel UTF-8 recognition marker

        // 1. Report Metadata Headers
        sw.write(escape(data.businessName()) + "\n");
        if (data.businessAddress() != null && !data.businessAddress().isBlank()) {
            sw.write(escape(data.businessAddress()) + "\n");
        }
        if (data.businessPhone() != null && !data.businessPhone().isBlank()) {
            sw.write(escape("Phone: " + data.businessPhone()) + "\n");
        }
        sw.write(escape(data.title()) + "\n");
        sw.write(escape(data.periodOrAsOfLabel()) + "\n");
        sw.write(escape("Generated: " + data.generatedAt().toString().replace("T", " ")) + "\n\n");

        // 2. Summary KPI Totals if available
        if (data.summaryCards() != null && !data.summaryCards().isEmpty()) {
            sw.write("Summary Totals:\n");
            for (Map.Entry<String, String> entry : data.summaryCards().entrySet()) {
                sw.write(escape(entry.getKey()) + "," + escape(entry.getValue()) + "\n");
            }
            sw.write("\n");
        }

        // 3. Tabular Column Headers
        List<String> headers = data.headers();
        for (int i = 0; i < headers.size(); i++) {
            sw.write(escape(headers.get(i)));
            if (i < headers.size() - 1) {
                sw.write(",");
            }
        }
        sw.write("\n");

        // 4. Data Rows
        for (List<String> row : data.rows()) {
            for (int i = 0; i < row.size(); i++) {
                sw.write(escape(row.get(i)));
                if (i < row.size() - 1) {
                    sw.write(",");
                }
            }
            sw.write("\n");
        }

        return sw.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean containsSpecial = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (containsSpecial) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
