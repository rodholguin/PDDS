package com.tasfb2b.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasfb2b.dto.SimulationTimeModeDto;
import com.tasfb2b.model.SimulationConfig;
import com.tasfb2b.repository.ShipmentRepository;
import com.tasfb2b.repository.SimulationConfigRepository;
import com.tasfb2b.service.AlgorithmProfileService;
import com.tasfb2b.service.AlgorithmRaceService;
import com.tasfb2b.service.CollapseMonitorService;
import com.tasfb2b.service.OperationalBootstrapService;
import com.tasfb2b.service.FlightScheduleService;
import com.tasfb2b.service.RoutePlannerService;
import com.tasfb2b.service.SimulationEngineService;
import com.tasfb2b.service.SimulationExportService;
import com.tasfb2b.service.SimulationRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SimulationController.class)
class SimulationControllerSpeedTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean private SimulationConfigRepository configRepository;
    @MockBean private RoutePlannerService routePlannerService;
    @MockBean private CollapseMonitorService collapseMonitorService;
    @MockBean private ShipmentRepository shipmentRepository;
    @MockBean private SimulationRuntimeService runtimeService;
    @MockBean private AlgorithmRaceService algorithmRaceService;
    @MockBean private OperationalBootstrapService operationalBootstrapService;
    @MockBean private SimulationExportService simulationExportService;
    @MockBean private SimulationEngineService simulationEngineService;
    @MockBean private AlgorithmProfileService algorithmProfileService;
    @MockBean private FlightScheduleService flightScheduleService;
    @MockBean private PlatformTransactionManager transactionManager;

    @Test
    void setSpeedEndpointAcceptsFastSpeed() throws Exception {
        when(runtimeService.currentSpeed()).thenReturn(20);
        doNothing().when(runtimeService).setSpeed(anyInt());
        when(runtimeService.getState()).thenReturn(new com.tasfb2b.dto.SimulationStateDto(
                1L,
                com.tasfb2b.model.SimulationScenario.DAY_TO_DAY,
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
                com.tasfb2b.model.AlgorithmType.ANT_COLONY,
                com.tasfb2b.model.AlgorithmType.GENETIC,
                false,
                false,
                20,
                SimulationTimeModeDto.REAL_TIME,
                1L,
                1L,
                0L,
                0L,
                null,
                null,
                null,
                java.time.LocalDateTime.now()
        ));

        mockMvc.perform(post("/api/simulation/speed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("speed", 20))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speed").value(20));
    }
}
