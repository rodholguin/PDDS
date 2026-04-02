package com.tasfb2b.repository;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Continent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AirportRepository extends JpaRepository<Airport, Long> {

    /** Todos los aeropuertos de un continente. */
    List<Airport> findByContinent(Continent continent);

    /** Búsqueda por código ICAO (único). */
    Optional<Airport> findByIcaoCode(String icaoCode);

    /** Aeropuertos con carga actual por encima de un porcentaje dado. */
    List<Airport> findByCurrentStorageLoadGreaterThanEqual(Integer minLoad);
}
