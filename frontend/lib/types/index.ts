export type Continent = 'AMERICA' | 'EUROPE' | 'ASIA';
export type AirportStatus = 'NORMAL' | 'ALERTA' | 'CRITICO';
export type FlightStatus = 'SCHEDULED' | 'IN_FLIGHT' | 'COMPLETED' | 'CANCELLED';
export type ShipmentStatus = 'PENDING' | 'IN_ROUTE' | 'DELIVERED' | 'DELAYED' | 'CRITICAL';
export type StopStatus = 'PENDING' | 'IN_TRANSIT' | 'COMPLETED';
export type AlgorithmType = 'GENETIC' | 'ANT_COLONY' | 'SIMULATED_ANNEALING';
export type SimScenario = 'DAY_TO_DAY' | 'PERIOD_SIMULATION' | 'COLLAPSE_TEST';
export type ImportStatus = 'SUCCESS' | 'PARTIAL' | 'FAILED';

export interface Airport {
  id: number;
  icaoCode: string;
  city: string;
  country: string;
  latitude: number;
  longitude: number;
  continent: Continent;
  maxStorageCapacity: number;
  currentStorageLoad: number;
  occupancyPct: number;
  status: AirportStatus;
}

export interface Flight {
  id: number;
  flightCode: string;
  originAirport: Airport;
  destinationAirport: Airport;
  isInterContinental: boolean;
  maxCapacity: number;
  currentLoad: number;
  loadPct: number;
  availableCapacity: number;
  scheduledDeparture: string;
  scheduledArrival: string;
  status: FlightStatus;
  transitTimeDays: number;
}

export interface FlightCapacityView {
  flightCode: string;
  originIcao: string;
  destinationIcao: string;
  routeType: 'INTRA' | 'INTER';
  maxCapacity: number;
  currentLoad: number;
  availableCapacity: number;
  scheduledDeparture: string;
}

export interface FlightSearchPage {
  content: Flight[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface Shipment {
  id: number;
  shipmentCode: string;
  airlineName: string;
  originAirport: Airport;
  destinationAirport: Airport;
  luggageCount: number;
  registrationDate: string;
  deadline: string;
  deliveredAt?: string | null;
  status: ShipmentStatus;
  progressPercentage: number;
  isInterContinental: boolean;
}

export interface TravelStop {
  id: number;
  stopOrder: number;
  airportIcaoCode: string;
  airportCity: string;
  airportLatitude: number;
  airportLongitude: number;
  flightCode: string | null;
  scheduledArrival: string | null;
  actualArrival: string | null;
  stopStatus: StopStatus;
}

export type ShipmentAuditType =
  | 'CREATED'
  | 'ROUTE_PLANNED'
  | 'ROUTE_REPLANNED'
  | 'DEPARTED'
  | 'ARRIVED'
  | 'EVENT_INJECTED'
  | 'DELIVERED'
  | 'DELAYED'
  | 'CRITICAL';

export interface ShipmentAuditLog {
  id: number;
  eventType: ShipmentAuditType;
  message: string;
  eventAt: string;
  airportIcao: string | null;
  airportLatitude: number | null;
  airportLongitude: number | null;
  flightCode: string | null;
}

export interface ShipmentDetail {
  id: number;
  shipmentCode: string;
  airlineName: string;
  originIcaoCode: string;
  originCity: string;
  destinationIcaoCode: string;
  destinationCity: string;
  luggageCount: number;
  registrationDate: string;
  deadline: string;
  deliveredAt?: string | null;
  status: ShipmentStatus;
  progressPercentage: number;
  isInterContinental: boolean;
  stops: TravelStop[];
  audit: ShipmentAuditLog[];
}

export interface ShipmentFeasibility {
  feasible: boolean;
  message: string;
  algorithm: string;
  candidateRoutes: number;
}

export interface ShipmentPlanningEvent {
  eventType: string;
  eventAt: string;
  reason: string;
  algorithm: string;
  route: string;
}

export interface SimulationConfig {
  id: number;
  scenario: SimScenario;
  simulationDays: number;
  executionMinutes: number;
  normalThresholdPct: number;
  warningThresholdPct: number;
  primaryAlgorithm: AlgorithmType;
  secondaryAlgorithm: AlgorithmType;
  isRunning: boolean;
  startedAt: string | null;
}

export interface SimulationState {
  id: number;
  scenario: SimScenario;
  simulationDays: number;
  executionMinutes: number;
  initialVolumeAvg: number;
  initialVolumeVariance: number;
  flightFrequencyMultiplier: number;
  cancellationRatePct: number;
  intraNodeCapacity: number;
  interNodeCapacity: number;
  normalThresholdPct: number;
  warningThresholdPct: number;
  primaryAlgorithm: AlgorithmType;
  secondaryAlgorithm: AlgorithmType;
  running: boolean;
  paused: boolean;
  speed: number;
  replannings: number;
  injectedEvents: number;
  startedAt: string | null;
  simulatedNow: string | null;
  lastTickAt: string | null;
  updatedAt: string;
}

export interface SimulationKpis {
  deliveredOnTimePct: number;
  avgFlightOccupancyPct: number;
  avgNodeOccupancyPct: number;
  replannings: number;
  delivered: number;
  delayed: number;
  active: number;
  critical: number;
  simulatedEvents: number;
}

export interface OptimizationResult {
  algorithmName: string;
  completedShipments: number;
  completedPct: number;
  avgTransitHours: number;
  totalReplanning: number;
  operationalCost: number;
  flightUtilizationPct: number;
  saturatedAirports: number;
  collapseReachedAt: string | null;
}

export interface SimulationResults {
  algorithms: Record<string, OptimizationResult>;
  kpis: SimulationKpis;
  benchmarkWinner: string;
}

export interface DataImportLog {
  id: number;
  fileName: string;
  importedAt: string;
  totalRows: number;
  successRows: number;
  errorRows: number;
  status: ImportStatus;
  errorDetails: string | null;
}

export interface DatasetImportResult {
  message: string;
  airports: DataImportLog;
  flights: DataImportLog;
}

export interface BenchmarkJobState {
  jobId: string;
  status: 'RUNNING' | 'DONE' | 'FAILED' | 'IDLE';
  message: string;
  startedAt: string | null;
  finishedAt: string | null;
  result: {
    generatedRows: number;
    createdShipments: number;
    winner: string;
    sampleSize: number;
    results: Record<string, OptimizationResult>;
    scenarios: Array<{
      scenario: string;
      createdShipments: number;
      cancelledFlights: number;
      replannings: number;
      sampleSize: number;
      winner: string;
    }>;
    confidence?: {
      winner: string;
      ci95Low: number;
      ci95High: number;
      deltaVsRunnerUp: number;
    };
  } | null;
}

export interface EnviosImportResult {
  processedAirports: number;
  selectedAirports: number;
  processedFiles: number;
  totalFiles: number;
  requestedRows: number;
  importedRows: number;
  failedRows: number;
  failureByCause: Record<string, number>;
  selectedOriginIcaos: string[];
  algorithmUsed: string;
}

export interface EnviosImportJobState {
  jobId: string;
  status: 'RUNNING' | 'DONE' | 'FAILED' | 'IDLE';
  message: string;
  startedAt: string | null;
  finishedAt: string | null;
  result: EnviosImportResult | null;
}

export interface DemandGenerationResult {
  scenario: string;
  requested: number;
  created: number;
  failed: number;
  seed: number;
  startedAt: string;
  finishedAt: string;
}

export interface DatasetStatus {
  totalShipments: number;
  pendingShipments: number;
  inRouteShipments: number;
  deliveredShipments: number;
  delayedShipments: number;
  criticalShipments: number;
}

export interface DashboardKpis {
  totalShipments: number;
  activeShipments: number;
  inRouteShipments: number;
  criticalShipments: number;
  deliveredShipments: number;
  systemLoadPct: number;
  collapseRisk: number;
  totalAirports: number;
  alertaAirports: number;
  criticoAirports: number;
}

export interface DashboardOverview {
  totalActiveFlights: number;
  shipmentsInRoute: number;
  totalShipmentsToday: number;
  inTransitToday: number;
  deliveredToday: number;
  slaCompliancePct: number;
  slaDeltaVsPreviousPct: number;
  unresolvedAlerts: number;
  overdueShipments: number;
  atRiskShipments: number;
  stalledShipments: number;
  activeIntraShipments: number;
  activeInterShipments: number;
  activeIntraPct: number;
  activeInterPct: number;
  availableNodesPct: number;
  avgDeliveryHours: number;
  avgCommittedHours: number;
  avgDeliveryDeltaHours: number;
  replanningsToday: number;
}

export interface RouteNetworkEdge {
  originIcao: string;
  originLatitude: number;
  originLongitude: number;
  destinationIcao: string;
  destinationLatitude: number;
  destinationLongitude: number;
  operational: boolean;
  suspended: boolean;
  scheduledCount: number;
  inFlightCount: number;
  cancelledCount: number;
}

export interface ShipmentSummary {
  id: number;
  shipmentCode: string;
  airlineName: string;
  originIcao: string;
  originLatitude: number;
  originLongitude: number;
  destinationIcao: string;
  destinationLatitude: number;
  destinationLongitude: number;
  status: ShipmentStatus;
  lastVisitedNode: string;
  currentLatitude: number;
  currentLongitude: number;
  remainingTime: string;
  progressPct: number;
  atRisk: boolean;
  overdue: boolean;
  criticalReason?: string | null;
}

export interface MapLiveShipment {
  shipmentId: number;
  shipmentCode: string;
  originIcao: string;
  destinationIcao: string;
  currentLatitude: number;
  currentLongitude: number;
  nextLatitude: number;
  nextLongitude: number;
  progressPct: number;
  originLatitude: number;
  originLongitude: number;
}

export interface ShipmentSearchResult {
  id: number;
  shipmentCode: string;
  airlineName: string;
  originIcao: string;
  originLatitude: number;
  originLongitude: number;
  destinationIcao: string;
  destinationLatitude: number;
  destinationLongitude: number;
  status: ShipmentStatus;
  lastVisitedNode: string;
  currentNode: string;
  currentLatitude: number;
  currentLongitude: number;
  remainingTime: string;
  progressPct: number;
  atRisk: boolean;
}

export interface FlightScheduleEntry {
  flightCode: string;
  originIcao: string;
  destinationIcao: string;
  departure: string;
  arrival: string;
  maxCapacity: number;
  currentLoad: number;
  availableCapacity: number;
  intercontinental: boolean;
}

export interface NodeDetail {
  id: number;
  icaoCode: string;
  city: string;
  country: string;
  continent: string;
  maxStorageCapacity: number;
  currentStorageLoad: number;
  availableCapacity: number;
  occupancyPct: number;
  status: AirportStatus;
  scheduledFlights: number;
  inFlightFlights: number;
  storedShipments: number;
  inboundShipments: number;
  outboundShipments: number;
  nextFlights: FlightScheduleEntry[];
}

export interface OperationalAlert {
  id: number;
  shipmentId: number | null;
  shipmentCode: string;
  type: string;
  status: string;
  note: string;
  resolvedBy: string | null;
  resolvedAt: string | null;
  resolutionNote: string | null;
}

export interface ResolveAlertRequest {
  user: string;
  note: string;
}

export interface SlaBreakdownRow {
  dimension: string;
  group: string;
  total: number;
  onTime: number;
  onTimePct: number;
}

export interface SlaReport {
  from: string;
  to: string;
  rows: SlaBreakdownRow[];
}

export interface SystemStatus {
  totalAirports: number;
  normalAirports: number;
  alertaAirports: number;
  criticoAirports: number;
  avgOccupancyPct: number;
  totalFlights: number;
  scheduledFlights: number;
  inFlightFlights: number;
}

export interface CollapseRisk {
  risk: number;
  bottlenecks: string[];
  estimatedHoursToCollapse: number;
  systemLoadPct: number;
}
