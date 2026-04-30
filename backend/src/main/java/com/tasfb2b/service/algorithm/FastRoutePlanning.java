package com.tasfb2b.service.algorithm;

import com.tasfb2b.model.Flight;
import com.tasfb2b.model.Shipment;
import com.tasfb2b.model.TravelStop;

import java.util.List;

public final class FastRoutePlanning {

    private FastRoutePlanning() {
    }

    public static List<TravelStop> planBestEffort(Shipment shipment, List<Flight> availableFlights) {
        if (shipment == null || availableFlights == null || availableFlights.isEmpty()) {
            return List.of();
        }

        List<List<Flight>> routes = RoutePlanningSupport.enumerateRoutesFromCandidates(shipment, availableFlights);
        if (routes.isEmpty()) {
            return List.of();
        }

        return RoutePlanningSupport.toTravelStops(shipment, routes.get(0));
    }
}
