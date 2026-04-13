package com.tasfb2b.service;

import com.tasfb2b.model.GlobalSequence;
import com.tasfb2b.repository.GlobalSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShipmentCodeRangeService {

    private static final String SHIPMENT_SEQUENCE = "SHIPMENT_CODE";

    private final GlobalSequenceRepository globalSequenceRepository;

    @Transactional
    public SequenceRange allocateRange(long blockSize) {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize debe ser mayor que cero");
        }

        GlobalSequence sequence = globalSequenceRepository.findByNameForUpdate(SHIPMENT_SEQUENCE)
                .orElseGet(() -> globalSequenceRepository.save(GlobalSequence.builder()
                        .sequenceName(SHIPMENT_SEQUENCE)
                        .nextValue(1L)
                        .build()));

        long start = Math.max(1L, sequence.getNextValue());
        long end = start + blockSize - 1;
        sequence.setNextValue(end + 1);
        globalSequenceRepository.save(sequence);
        return new SequenceRange(start, end);
    }

    public record SequenceRange(long startInclusive, long endInclusive) {}
}
