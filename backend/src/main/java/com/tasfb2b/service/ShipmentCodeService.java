package com.tasfb2b.service;

import com.tasfb2b.model.GlobalSequence;
import com.tasfb2b.repository.GlobalSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class ShipmentCodeService {

    private static final String SHIPMENT_SEQUENCE = "SHIPMENT_CODE";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final GlobalSequenceRepository globalSequenceRepository;

    @Transactional
    public String nextCode(LocalDateTime registrationDate) {
        LocalDateTime effectiveDate = registrationDate == null ? LocalDateTime.now() : registrationDate;

        GlobalSequence sequence = globalSequenceRepository.findByNameForUpdate(SHIPMENT_SEQUENCE)
                .orElseGet(() -> globalSequenceRepository.save(GlobalSequence.builder()
                        .sequenceName(SHIPMENT_SEQUENCE)
                        .nextValue(1L)
                        .build()));

        long value = sequence.getNextValue();
        sequence.setNextValue(value + 1);
        globalSequenceRepository.save(sequence);

        return String.format("%09d-%s", value, effectiveDate.format(DATE_FMT));
    }
}
