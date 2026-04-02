// ── Enums ──────────────────────────────────────────────────────────────────

export type Continent      = 'AMERICA' | 'EUROPE' | 'ASIA';
export type AirportStatus  = 'NORMAL'  | 'ALERTA' | 'CRITICO';
export type FlightStatus   = 'SCHEDULED' | 'IN_FLIGHT' | 'COMPLETED' | 'CANCELLED';
export type ShipmentStatus = 'PENDING' | 'IN_ROUTE' | 'DELIVERED' | 'DELAYED' | 'CRITICAL';
export type StopStatus     = 'PENDING' | 'IN_TRANSIT' | 'COMPLETED';
export type AlgorithmType  = 'GENETIC' | 'ANT_COLONY';
export type SimScenario    = 'DAY_TO_DAY' | 'PERIOD_SIMULATION' | 'COLLAPSE_TEST';
export type ImportStatus   = 'SUCCESS' | 'PARTIAL' | 'FAILED';

// ── Domain entities ────────────────────────────────────────────────────────

export interface Airport {
  id:                  number;
  icaoCode:            string;
  city:                string;
  country:             string;
  continent:           Continent;
  maxStorageCapacity:  number;
  currentStorageLoad:  number;
  /** Calculated: currentStorageLoad / maxStorageCapacity × 100 */
  occupancyPct:        number;
  /** Calculated traffic-light status */
  status:              AirportStatus;
}

export interface Flight {
  id:                  number;
  flightCode:          string;
  originAirport:       Airport;
  destinationAirport:  Airport;
  isInterContinental:  boolean;
  maxCapacity:         number;
  currentLoad:         number;
  /** Calculated: currentLoad / maxCapacity × 100 */
  loadPct:             number;
  availableCapacity:   number;
  scheduledDeparture:  string;   // ISO-8601
  scheduledArrival:    string;
  status:              FlightStatus;
  transitTimeDays:     number;
}

export interface Shipment {
  id:                  number;
  shipmentCode:        string;
  airlineName:         string;
  originAirport:       Airport;
  destinationAirport:  Airport;
  luggageCount:        number;
  registrationDate:    string;
  deadline:            string;
  status:              ShipmentStatus;
  progressPercentage:  number;
  isInterContinental:  boolean;
}

export interface TravelStop {
  id:               number;
  stopOrder:        number;
  airportIcaoCode:  string;
  airportCity:      string;
  flightCode:       string | null;
  scheduledArrival: string | null;
  actualArrival:    string | null;
  stopStatus:       StopStatus;
}

export interface ShipmentDetail extends Omit<Shipment, 'originAirport' | 'destinationAirport'> {
  originIcaoCode:      string;
  originCity:          string;
  destinationIcaoCode: string;
  destinationCity:     string;
  stops:               TravelStop[];
}

// ── Simulation ─────────────────────────────────────────────────────────────

export interface SimulationConfig {
  id:                  number;
  scenario:            SimScenario;
  simulationDays:      number;
  executionMinutes:    number;
  normalThresholdPct:  number;
  warningThresholdPct: number;
  primaryAlgorithm:    AlgorithmType;
  secondaryAlgorithm:  AlgorithmType;
  isRunning:           boolean;
  startedAt:           string | null;
}

export interface OptimizationResult {
  algorithmName:       string;
  completedShipments:  number;
  completedPct:        number;
  avgTransitHours:     number;
  totalReplanning:     number;
  operationalCost:     number;
  flightUtilizationPct: number;
  saturatedAirports:   number;
  collapseReachedAt:   string | null;
}

// ── Import ─────────────────────────────────────────────────────────────────

export interface DataImportLog {
  id:           number;
  fileName:     string;
  importedAt:   string;
  totalRows:    number;
  successRows:  number;
  errorRows:    number;
  status:       ImportStatus;
  errorDetails: string | null;
}

// ── Dashboard ──────────────────────────────────────────────────────────────

export interface DashboardKpis {
  totalShipments:     number;
  activeShipments:    number;
  criticalShipments:  number;
  deliveredShipments: number;
  systemLoadPct:      number;
  collapseRisk:       number;
  totalAirports:      number;
  alertaAirports:     number;
  criticoAirports:    number;
}

export interface SystemStatus {
  totalAirports:   number;
  normalAirports:  number;
  alertaAirports:  number;
  criticoAirports: number;
  avgOccupancyPct: number;
  totalFlights:    number;
  scheduledFlights: number;
  inFlightFlights: number;
}

export interface CollapseRisk {
  risk:                      number;   // 0.0–1.0
  bottlenecks:               string[]; // ICAO codes
  estimatedHoursToCollapse:  number;   // -1 if stable
  systemLoadPct:             number;
}
