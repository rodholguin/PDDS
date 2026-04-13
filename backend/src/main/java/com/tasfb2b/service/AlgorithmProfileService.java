package com.tasfb2b.service;

import com.tasfb2b.model.AlgorithmType;
import com.tasfb2b.service.algorithm.AntColonyOptimization;
import com.tasfb2b.service.algorithm.GeneticAlgorithm;
import com.tasfb2b.service.algorithm.SimulatedAnnealingOptimization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlgorithmProfileService {

    private final GeneticAlgorithm geneticAlgorithm;
    private final AntColonyOptimization antColonyOptimization;
    private final SimulatedAnnealingOptimization simulatedAnnealingOptimization;

    public void applyOperationalWinnerProfile() {
        applyGaProfile(55, 24, 0.05);
        applyAcoProfile(20, 24, 0.10, 1.1, 2.1);
        applySaProfile(220, 160.0, 0.965);
    }

    public void applyForPrimary(AlgorithmType primaryAlgorithm) {
        applyOperationalWinnerProfile();
        if (primaryAlgorithm == AlgorithmType.GENETIC) {
            applyGaProfile(55, 24, 0.05);
        } else if (primaryAlgorithm == AlgorithmType.SIMULATED_ANNEALING) {
            applySaProfile(220, 160.0, 0.965);
        } else {
            applyAcoProfile(20, 24, 0.10, 1.1, 2.1);
        }
    }

    public void applyGaProfile(int population, int generations, double mutationRate) {
        geneticAlgorithm.setPopulationSize(population);
        geneticAlgorithm.setGenerations(generations);
        geneticAlgorithm.setMutationRate(mutationRate);
    }

    public void applyAcoProfile(int ants, int iterations, double evaporation, double alpha, double beta) {
        antColonyOptimization.setNumAnts(ants);
        antColonyOptimization.setIterations(iterations);
        antColonyOptimization.setEvaporationRate(evaporation);
        antColonyOptimization.setAlpha(alpha);
        antColonyOptimization.setBeta(beta);
    }

    public void applySaProfile(int iterations, double initialTemperature, double coolingRate) {
        simulatedAnnealingOptimization.setIterations(iterations);
        simulatedAnnealingOptimization.setInitialTemperature(initialTemperature);
        simulatedAnnealingOptimization.setCoolingRate(coolingRate);
    }
}
