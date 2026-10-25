import type { ApproachingVehicle } from "@/shared/api/routeForecast.types";

export const forecastFixtures = {
  available: {
    vehicleId: null,
    remainingStops: 1,
    forecast: {
      status: "AVAILABLE",
      seatAvailableProbability: 0.8,
      expectedSeats: 5.9,
    },
  },
  withoutExpectedSeats: {
    vehicleId: null,
    remainingStops: 1,
    forecast: {
      status: "AVAILABLE",
      seatAvailableProbability: 0.8,
    },
  },
  zeroSeats: {
    vehicleId: null,
    remainingStops: 1,
    forecast: {
      status: "AVAILABLE",
      seatAvailableProbability: 0.0,
      expectedSeats: 0.0,
    },
  },
  unavailable: {
    vehicleId: null,
    remainingStops: 1,
    forecast: {
      status: "UNAVAILABLE",
    },
  },
} satisfies Record<string, ApproachingVehicle>;
