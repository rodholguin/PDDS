package com.tasfb2b.dto;

import java.util.List;
import java.util.Map;

public record EnviosDatasetImportResultDto(
        int processedAirports,
        int selectedAirports,
        int processedFiles,
        int totalFiles,
        int requestedRows,
        int importedRows,
        int failedRows,
        Map<String, Integer> failureByCause,
        List<String> selectedOriginIcaos,
        String algorithmUsed
) {}
