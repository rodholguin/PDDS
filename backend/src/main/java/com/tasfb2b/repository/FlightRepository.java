package com.tasfb2b.repository;

import com.tasfb2b.model.Airport;
import com.tasfb2b.model.Flight;
import com.tasfb2b.model.FlightStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FlightRepository extends JpaRepository<Flight, Long>, JpaSpecificationExecutor<Flight> {

    Optional<Flight> findByFlightCode(String flightCode);

    @Override
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Optional<Flight> findById(Long id);

    /** Vuelos entre un par origen-destino específico. */
    List<Flight> findByOriginAirportAndDestinationAirport(
            Airport originAirport, Airport destinationAirport);

    /** Todos los vuelos en un estado dado. */
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findByStatus(FlightStatus status);

    List<Flight> findByStatusAndScheduledDepartureLessThanEqual(FlightStatus status, LocalDateTime cutoff);

    List<Flight> findByStatusAndScheduledArrivalLessThanEqual(FlightStatus status, LocalDateTime cutoff);

    @Query("""
            SELECT f FROM Flight f
            WHERE f.status = 'SCHEDULED'
              AND f.currentLoad < f.maxCapacity
              AND f.scheduledDeparture >= :from
              AND f.scheduledDeparture < :to
            ORDER BY f.scheduledDeparture ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findSchedulableFlightsBetween(@Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to);

    @Override
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findAll();

    @Query("SELECT MIN(f.scheduledDeparture) FROM Flight f WHERE f.scheduledDeparture IS NOT NULL")
    LocalDateTime findEarliestScheduledDeparture();

    @Query("""
            SELECT f FROM Flight f
            WHERE f.scheduledDeparture >= :from
              AND f.scheduledDeparture < :to
            ORDER BY f.scheduledDeparture ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findFlightsWithinWindow(@Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    @Query("""
            SELECT f FROM Flight f
            WHERE f.scheduledDeparture >= :templateFrom
              AND f.scheduledDeparture < :templateTo
              AND f.scheduledArrival IS NOT NULL
            ORDER BY f.flightCode ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findTemplateFlightsWithinWindow(@Param("templateFrom") LocalDateTime templateFrom,
                                                 @Param("templateTo") LocalDateTime templateTo);

    /** Cantidad de vuelos en estado dado (sin cargar entidades). */
    long countByStatus(FlightStatus status);

    /** Cantidad de vuelos activos en un instante dado (en vuelo). */
    @Query("""
            SELECT COUNT(f) FROM Flight f
            WHERE f.scheduledDeparture <= :now
              AND f.scheduledArrival > :now
              AND f.status <> 'CANCELLED'
            """)
    long countActiveFlightsAt(@Param("now") LocalDateTime now);

    @Query("""
            SELECT COUNT(f) FROM Flight f
            WHERE f.scheduledDeparture <= :now
              AND f.scheduledArrival > :now
              AND f.scheduledDeparture >= :dayStart
              AND f.scheduledDeparture < :dayEnd
              AND f.status <> 'CANCELLED'
            """)
    long countActiveFlightsAtWithinDay(@Param("now") LocalDateTime now,
                                       @Param("dayStart") LocalDateTime dayStart,
                                       @Param("dayEnd") LocalDateTime dayEnd);

    @Query("""
            SELECT f FROM Flight f
            WHERE f.scheduledDeparture <= :now
              AND f.scheduledArrival > :now
              AND f.scheduledDeparture >= :dayStart
              AND f.scheduledDeparture < :dayEnd
              AND f.status <> 'CANCELLED'
            ORDER BY f.scheduledDeparture ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findActiveFlightsAtWithinDay(@Param("now") LocalDateTime now,
                                              @Param("dayStart") LocalDateTime dayStart,
                                              @Param("dayEnd") LocalDateTime dayEnd);

    @Query("""
            SELECT COUNT(f) FROM Flight f
            WHERE f.scheduledDeparture <= :now
              AND f.scheduledArrival > :now
              AND f.scheduledDeparture >= :dayStart
              AND f.scheduledDeparture < :dayEnd
              AND f.status <> 'CANCELLED'
              AND f.currentLoad > 0
            """)
    long countLoadedActiveFlightsAtWithinDay(@Param("now") LocalDateTime now,
                                             @Param("dayStart") LocalDateTime dayStart,
                                             @Param("dayEnd") LocalDateTime dayEnd);

    /** Vuelos saliendo desde un aeropuerto en un rango horario. */
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findByOriginAirportAndScheduledDepartureBetween(
            Airport originAirport,
            LocalDateTime from,
            LocalDateTime to
    );

    @Query("""
            SELECT f FROM Flight f
            WHERE f.status = 'SCHEDULED'
              AND f.currentLoad < f.maxCapacity
              AND f.originAirport IN :origins
              AND f.scheduledDeparture >= :from
              AND f.scheduledDeparture < :to
            ORDER BY f.scheduledDeparture ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findSchedulableFlightsByOriginsAndWindow(@Param("origins") List<Airport> origins,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to);

    /** Vuelos SCHEDULED que parten desde ahora en adelante. */
    @Query("""
            SELECT f FROM Flight f
            WHERE f.status = 'SCHEDULED'
              AND f.scheduledDeparture >= :from
            ORDER BY f.scheduledDeparture ASC
            """)
    List<Flight> findScheduledFlights(@Param("from") LocalDateTime from);

    @Query("""
            SELECT COUNT(f) FROM Flight f
            WHERE f.status = 'SCHEDULED'
              AND f.scheduledDeparture >= :from
              AND f.scheduledDeparture < :to
              AND f.currentLoad > 0
            """)
    long countLoadedScheduledFlightsBetween(@Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);

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

    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    Page<Flight> findAll(org.springframework.data.jpa.domain.Specification<Flight> spec, Pageable pageable);

    /** Todos los vuelos que pasan por un aeropuerto (origen o destino). */
    @Query("""
            SELECT f FROM Flight f
            WHERE f.originAirport = :airport
               OR f.destinationAirport = :airport
            ORDER BY f.scheduledDeparture ASC
            """)
    @EntityGraph(attributePaths = {"originAirport", "destinationAirport"})
    List<Flight> findByAirport(@Param("airport") Airport airport);

    /** Earliest scheduled departure of a loaded flight. */
    @Query("SELECT MIN(f.scheduledDeparture) FROM Flight f WHERE f.status = 'SCHEDULED' AND f.currentLoad > 0")
    LocalDateTime findMinScheduledDeparture();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE flight SET status = 'SCHEDULED', current_load = 0 WHERE status <> 'SCHEDULED' OR current_load <> 0", nativeQuery = true)
    int resetOperationalStateFast();
}
