package com.tasfb2b.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.tasfb2b.dto.SimulationKpisDto;
import com.tasfb2b.dto.SimulationResultsDto;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class SimulationExportService {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public byte[] toCsv(SimulationResultsDto results) {
        List<String[]> rows = buildRows(results);
        StringBuilder csv = new StringBuilder();
        csv.append("metric,value\n");
        for (String[] row : rows) {
            csv.append(escape(row[0])).append(',').append(escape(row[1])).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] toPdf(SimulationResultsDto results) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document();
        try {
            PdfWriter.getInstance(document, output);
            document.open();
            document.add(new Paragraph("Resultados de Simulacion"));
            document.add(new Paragraph("Generado: " + LocalDateTime.now().format(TS_FORMAT)));
            document.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(2);
            table.addCell("Metrica");
            table.addCell("Valor");

            for (String[] row : buildRows(results)) {
                table.addCell(row[0]);
                table.addCell(row[1]);
            }

            document.add(table);
        } catch (DocumentException e) {
            throw new IllegalStateException("No se pudo generar PDF de resultados", e);
        } finally {
            document.close();
        }
        return output.toByteArray();
    }

    private List<String[]> buildRows(SimulationResultsDto results) {
        List<String[]> rows = new ArrayList<>();
        SimulationKpisDto kpis = results.kpis();
        rows.add(new String[]{"benchmarkWinner", value(results.benchmarkWinner())});
        rows.add(new String[]{"deliveredOnTimePct", String.format("%.2f", kpis.deliveredOnTimePct())});
        rows.add(new String[]{"avgFlightOccupancyPct", String.format("%.2f", kpis.avgFlightOccupancyPct())});
        rows.add(new String[]{"avgNodeOccupancyPct", String.format("%.2f", kpis.avgNodeOccupancyPct())});
        rows.add(new String[]{"replannings", String.valueOf(kpis.replannings())});
        rows.add(new String[]{"delivered", String.valueOf(kpis.delivered())});
        rows.add(new String[]{"delayed", String.valueOf(kpis.delayed())});
        rows.add(new String[]{"active", String.valueOf(kpis.active())});
        rows.add(new String[]{"critical", String.valueOf(kpis.critical())});
        rows.add(new String[]{"simulatedEvents", String.valueOf(kpis.simulatedEvents())});
        return rows;
    }

    private String value(String text) {
        return text == null ? "N/A" : text;
    }

    private String escape(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return '"' + safe + '"';
    }
}
