package com.tasfb2b.service;

import com.tasfb2b.dto.ShipmentCreateDto;
import com.tasfb2b.model.AlgorithmType;
import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Continent;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.SimulationScenario;
import com.tasfb2b.repository.AirportRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class OperationalBootstrapService {

    private final AirportRepository airportRepository;
    private final ShipmentOrchestratorService shipmentOrchestratorService;
    private final SimulationConfigRepository simulationConfigRepository;
    private final DataImportService dataImportService;
    private final AlgorithmProfileService algorithmProfileService;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrap() {
        ensureSimulationConfig();
        ensureRealDatasetLoaded();
    }

    private void ensureSimulationConfig() {
        SimulationConfig config = simulationConfigRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> simulationConfigRepository.save(SimulationConfig.builder().build()));

        if (config.getScenario() == null) config.setScenario(SimulationScenario.DAY_TO_DAY);
        if (config.getSimulationDays() == null) config.setSimulationDays(5);
        if (config.getExecutionMinutes() == null) config.setExecutionMinutes(60);
        if (config.getNormalThresholdPct() == null) config.setNormalThresholdPct(70);
        if (config.getWarningThresholdPct() == null) config.setWarningThresholdPct(90);
        if (config.getInitialVolumeAvg() == null) config.setInitialVolumeAvg(8);
        if (config.getInitialVolumeVariance() == null) config.setInitialVolumeVariance(3);
        if (config.getFlightFrequencyMultiplier() == null) config.setFlightFrequencyMultiplier(1);
        if (config.getCancellationRatePct() == null) config.setCancellationRatePct(5);
        if (config.getIntraNodeCapacity() == null) config.setIntraNodeCapacity(700);
        if (config.getInterNodeCapacity() == null) config.setInterNodeCapacity(800);
        if (config.getIsRunning() == null) config.setIsRunning(false);
        config.setPrimaryAlgorithm(AlgorithmType.GENETIC);
        config.setSecondaryAlgorithm(AlgorithmType.ANT_COLONY);
        simulationConfigRepository.save(config);

        algorithmProfileService.applyForPrimary(config.getPrimaryAlgorithm());
    }

    private void ensureRealDatasetLoaded() {
        boolean hasAnyAirport = airportRepository.count() > 0;
        boolean hasRealDataset = airportRepository.findByIcaoCode("SKBO").isPresent();

        if (hasAnyAirport && hasRealDataset) {
            return;
        }

        try {
            DataImportService.DatasetImportSummary summary = dataImportService.importDefaultDataset();
            log.info(
                    "Dataset real cargado. Aeropuertos: {}/{} (errores: {}), vuelos: {}/{} (errores: {})",
                    summary.airports().getSuccessRows(),
                    summary.airports().getTotalRows(),
                    summary.airports().getErrorRows(),
                    summary.flights().getSuccessRows(),
                    summary.flights().getTotalRows(),
                    summary.flights().getErrorRows()
            );
        } catch (Exception ex) {
            log.error("No se pudo importar dataset real al iniciar: {}", ex.getMessage());
        }
    }

    @Transactional
    public int replenishStatisticalVolume(Integer avg, Integer variance) {
        int safeAvg = avg == null ? 8 : Math.max(1, avg);
        int safeVar = variance == null ? 3 : Math.max(0, variance);
        int generated = ThreadLocalRandom.current().nextInt(Math.max(1, safeAvg - safeVar), safeAvg + safeVar + 1);

        List<Airport> airports = airportRepository.findAll();
        if (airports.size() < 2) {
            return 0;
        }

        List<Airport> intraCandidates = airports.stream().filter(a -> a.getContinent() == Continent.AMERICA || a.getContinent() == Continent.EUROPE || a.getContinent() == Continent.ASIA).toList();
        if (intraCandidates.size() < 2) {
            intraCandidates = airports;
        }

        int created = 0;
        for (int i = 0; i < generated; i++) {
            Airport origin = intraCandidates.get(ThreadLocalRandom.current().nextInt(intraCandidates.size()));
            Airport destination = intraCandidates.get(ThreadLocalRandom.current().nextInt(intraCandidates.size()));
            int guard = 0;
            while (origin.getId().equals(destination.getId()) && guard < 10) {
                destination = intraCandidates.get(ThreadLocalRandom.current().nextInt(intraCandidates.size()));
                guard++;
            }
            if (origin.getId().equals(destination.getId())) continue;

            int luggage = ThreadLocalRandom.current().nextInt(10, 46);
            String airline = switch (i % 6) {
                case 0 -> "LATAM";
                case 1 -> "Iberia";
                case 2 -> "Air France";
                case 3 -> "Qatar";
                case 4 -> "Nippon";
                default -> "Copa";
            };

            ShipmentCreateDto dto = new ShipmentCreateDto(
                    airline,
                    origin.getIcaoCode(),
                    destination.getIcaoCode(),
                    luggage,
                    LocalDateTime.now().minusMinutes(ThreadLocalRandom.current().nextInt(2, 40)),
                    switch (i % 3) {
                        case 0 -> "Genetic Algorithm";
                        case 1 -> "Ant Colony Optimization";
                        default -> "Simulated Annealing";
                    }
            );
            try {
                shipmentOrchestratorService.createAndPlan(dto);
                created++;
            } catch (Exception ignored) {
                // Skip infeasible random pick and continue.
            }
        }
        return created;
    }
}
