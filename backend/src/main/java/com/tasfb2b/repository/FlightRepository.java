package com.tasfb2b.repository;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FlightRepository extends JpaRepository<Flight, Long> {

    Optional<Flight> findByFlightCode(String flightCode);

    /** Vuelos entre un par origen-destino específico. */
    List<Flight> findByOriginAirportAndDestinationAirport(
            Airport originAirport, Airport destinationAirport);

    /** Todos los vuelos en un estado dado. */
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findByStatus(FlightStatus status);

    List<Flight> findByStatusAndScheduledDepartureLessThanEqual(FlightStatus status, LocalDateTime cutoff);

    List<Flight> findByStatusAndScheduledArrivalLessThanEqual(FlightStatus status, LocalDateTime cutoff);

    @Override
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findAll();

    /** Cantidad de vuelos en estado dado (sin cargar entidades). */
    long countByStatus(FlightStatus status);

    /** Cantidad de vuelos activos en un instante dado (en vuelo). */
    @Query("""
            SELECT COUNT(f) FROM Flight f
            WHERE f.status = 'IN_FLIGHT'
              AND :now BETWEEN f.scheduledDeparture AND f.scheduledArrival
            """)
    long countActiveFlightsAt(@Param("now") LocalDateTime now);

    /** Vuelos saliendo desde un aeropuerto en un rango horario. */
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findByOriginAirportAndScheduledDepartureBetween(
            Airport originAirport,
            LocalDateTime from,
            LocalDateTime to
    );

    /** Vuelos SCHEDULED que parten desde ahora en adelante. */
    @Query("""
            SELECT f FROM Flight f
            WHERE f.status = 'SCHEDULED'
              AND f.scheduledDeparture >= :from
            ORDER BY f.scheduledDeparture ASC
            """)
    List<Flight> findScheduledFlights(@Param("from") LocalDateTime from);

    /** Vuelos que aún tienen capacidad disponible. */
    @Query("""
            SELECT f FROM Flight f
            WHERE f.status = 'SCHEDULED'
              AND f.currentLoad < f.maxCapacity
            ORDER BY f.scheduledDeparture ASC
            """)
    List<Flight> findFlightsWithAvailableCapacity();

    /** Vuelos inter-continentales con capacidad. */
    @Query("""
            SELECT f FROM Flight f
            WHERE f.isInterContinental = true
              AND f.status = 'SCHEDULED'
              AND f.currentLoad < f.maxCapacity
            """)
    List<Flight> findInterContinentalWithCapacity();

    /** Vuelos filtrados por status Y que partan en una fecha concreta. */
    @Query("""
            SELECT f FROM Flight f
            WHERE (:status IS NULL OR f.status = :status)
              AND (:dayStart IS NULL OR f.scheduledDeparture >= :dayStart)
              AND (:dayEnd   IS NULL OR f.scheduledDeparture < :dayEnd)
            ORDER BY f.scheduledDeparture ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findByStatusAndDate(
            @Param("status")   FlightStatus status,
            @Param("dayStart") LocalDateTime dayStart,
            @Param("dayEnd")   LocalDateTime dayEnd);

    @Query("""
            SELECT f FROM Flight f
            WHERE (:status IS NULL OR f.status = :status)
              AND (:code IS NULL OR LOWER(f.flightCode) LIKE CONCAT('%', :code, '%'))
              AND (:origin IS NULL OR f.originAirport.icaoCode = :origin)
              AND (:destination IS NULL OR f.destinationAirport.icaoCode = :destination)
              AND (:dayStart IS NULL OR f.scheduledDeparture >= :dayStart)
              AND (:dayEnd IS NULL OR f.scheduledDeparture < :dayEnd)
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Flight> search(
            @Param("status") FlightStatus status,
            @Param("code") String code,
            @Param("origin") String origin,
            @Param("destination") String destination,
            @Param("dayStart") LocalDateTime dayStart,
            @Param("dayEnd") LocalDateTime dayEnd,
            Pageable pageable);

    /** Todos los vuelos que pasan por un aeropuerto (origen o destino). */
    @Query("""
            SELECT f FROM Flight f
            WHERE f.originAirport = :airport
               OR f.destinationAirport = :airport
            ORDER BY f.scheduledDeparture ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findByAirport(@Param("airport") Airport airport);
}
