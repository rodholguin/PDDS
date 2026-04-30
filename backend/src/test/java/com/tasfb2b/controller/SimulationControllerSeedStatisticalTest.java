package com.tasfb2b.controller;

import com.tasfb2b.dto.SimulationTimeModeDto;
import com.tasfb2b.model.AlgorithmType;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.model.SimulationScenario;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.service.AlgorithmRaceService;
import com.tasfb2b.service.CollapseMonitorService;
import com.tasfb2b.service.FutureDemandProjectionService;
import com.tasfb2b.service.OperationalBootstrapService;
import com.tasfb2b.service.FlightScheduleService;
import com.tasfb2b.service.RoutePlannerService;
import com.tasfb2b.service.AlgorithmProfileService;
import com.tasfb2b.service.SimulationEngineService;
import com.tasfb2b.service.SimulationExportService;
import com.tasfb2b.service.SimulationRuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SimulationController.class)
class SimulationControllerSeedStatisticalTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SimulationConfigRepository configRepository;
    @MockBean
    private RoutePlannerService routePlannerService;
    @MockBean
    private CollapseMonitorService collapseMonitorService;
    @MockBean
    private com.tasfb2b.repository.ShipmentRepository shipmentRepository;
    @MockBean
    private SimulationRuntimeService runtimeService;
    @MockBean
    private AlgorithmRaceService algorithmRaceService;
    @MockBean
    private OperationalBootstrapService operationalBootstrapService;
    @MockBean
    private SimulationExportService simulationExportService;
    @MockBean
    private SimulationEngineService simulationEngineService;
    @MockBean
    private AlgorithmProfileService algorithmProfileService;
    @MockBean
    private FlightScheduleService flightScheduleService;
    @MockBean
    private FutureDemandProjectionService futureDemandProjectionService;
    @MockBean
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setup() {
        SimulationConfig cfg = SimulationConfig.builder()
                .id(1L)
                .scenario(SimulationScenario.DAY_TO_DAY)
                .simulationDays(5)
                .executionMinutes(60)
                .normalThresholdPct(70)
                .warningThresholdPct(90)
                .primaryAlgorithm(AlgorithmType.GENETIC)
                .secondaryAlgorithm(AlgorithmType.ANT_COLONY)
                .isRunning(false)
                .build();

        when(configRepository.findAll()).thenReturn(java.util.List.of(cfg));
        when(runtimeService.getState()).thenReturn(new com.tasfb2b.dto.SimulationStateDto(
                1L,
                SimulationScenario.DAY_TO_DAY,
                5,
                60,
                8,
                3,
                1,
                5,
                700,
                800,
                70,
                90,
                null,
                null,
                null,
                false,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                AlgorithmType.GENETIC,
                AlgorithmType.ANT_COLONY,
                false,
                false,
                false,
                1,
                SimulationTimeModeDto.REAL_TIME,
                1L,
                1L,
                1_000L,
                0L,
                0L,
                0L,
                0L,
                null,
                null,
                null,
                false,
                null,
                null,
                0L,
                0L,
                0L,
                0L,
                0L,
                null,
                0L,
                0L,
                null,
                null,
                null,
                null,
                null,
                null,
                java.time.LocalDateTime.now()
        ));
    }

    @Test
    void seedStatisticalReturnsCreatedCount() throws Exception {
        when(operationalBootstrapService.replenishStatisticalVolume(anyInt(), anyInt())).thenReturn(7);

        mockMvc.perform(post("/api/simulation/seed-statistical").param("avg", "8").param("variance", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Volumen estadistico generado"))
                .andExpect(jsonPath("$.created").value(7));
    }
}
