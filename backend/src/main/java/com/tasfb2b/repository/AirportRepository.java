package com.tasfb2b.repository;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Continent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE airport SET current_storage_load = 0 WHERE current_storage_load <> 0", nativeQuery = true)
    int resetStorageLoadFast();

    /**
     * Sobrescribe la capacidad de almacén de TODOS los aeropuertos con un valor uniforme.
     * Se usa al iniciar/configurar la simulación para aplicar la capacidad de nodo configurada
     * (intraNodeCapacity + interNodeCapacity) de {@code SimulationConfig}, convirtiendo el control
     * "Capacidad intra/inter" del front en un lever real sobre el colapso.
     *
     * @return número de aeropuertos actualizados
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE airport SET max_storage_capacity = :capacity", nativeQuery = true)
    int applyUniformStorageCapacity(@Param("capacity") int capacity);

    @Query("""
            SELECT COALESCE(AVG((a.currentStorageLoad * 100.0) / NULLIF(a.maxStorageCapacity, 0)), 0.0)
            FROM Airport a
            WHERE a.maxStorageCapacity > 0
            """)
    double averageOccupancyPct();
}
