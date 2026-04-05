package com.tasfb2b.service;

import com.tasfb2b.model.AlgorithmType;
import com.tasfb2b.service.algorithm.AntColonyOptimization;
import com.tasfb2b.service.algorithm.GeneticAlgorithm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlgorithmProfileService {

    private final GeneticAlgorithm geneticAlgorithm;
    private final AntColonyOptimization antColonyOptimization;

    public void applyOperationalWinnerProfile() {
        applyGaProfile(55, 24, 0.05);
        applyAcoProfile(20, 24, 0.10, 1.1, 2.1);
    }

    public void applyForPrimary(AlgorithmType primaryAlgorithm) {
        applyOperationalWinnerProfile();
        if (primaryAlgorithm == AlgorithmType.GENETIC) {
            applyGaProfile(55, 24, 0.05);
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
}
