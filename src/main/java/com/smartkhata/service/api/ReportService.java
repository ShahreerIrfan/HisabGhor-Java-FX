package com.smartkhata.service.api;

import com.smartkhata.model.dto.ReportCriteria;
import com.smartkhata.model.dto.ReportData;

public interface ReportService {

    ReportData generateReport(ReportCriteria criteria);

    byte[] exportToPdf(ReportData data);

    String exportToCsv(ReportData data);
}
