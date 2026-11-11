package com.netpulse.incident;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure decision helper mirroring {@code netpulse_record_monitoring_check}.
 * Used for unit tests; PostgreSQL remains the production authority.
 */
public final class IncidentDetectionLogic {

    public enum Action {
        NONE,
        OPEN,
        RESOLVE
    }

    public record CheckObservation(Instant checkedAt, boolean success, String id) {
        public CheckObservation {
            Objects.requireNonNull(checkedAt, "checkedAt");
            Objects.requireNonNull(id, "id");
        }
    }

    public record OpenIncident(Instant startedAt) {
        public OpenIncident {
            Objects.requireNonNull(startedAt, "startedAt");
        }
    }

    public record Decision(Action action, Instant timestamp) {
    }

    private IncidentDetectionLogic() {
    }

    /**
     * @param checksAll all checks for the monitor including the newly inserted one
     * @param openIncident currently open incident, if any
     */
    public static Decision decide(List<CheckObservation> checksAll, OpenIncident openIncident) {
        if (checksAll == null || checksAll.isEmpty()) {
            return new Decision(Action.NONE, null);
        }

        List<CheckObservation> ordered = checksAll.stream()
                .sorted(Comparator
                        .comparing(CheckObservation::checkedAt)
                        .reversed()
                        .thenComparing(CheckObservation::id, Comparator.reverseOrder()))
                .toList();

        CheckObservation latest = ordered.get(0);
        CheckObservation second = ordered.size() > 1 ? ordered.get(1) : null;

        if (openIncident != null && latest.success()) {
            if (!latest.checkedAt().isBefore(openIncident.startedAt())) {
                return new Decision(Action.RESOLVE, latest.checkedAt());
            }
            return new Decision(Action.NONE, null);
        }

        if (openIncident == null
                && !latest.success()
                && second != null
                && !second.success()) {
            return new Decision(Action.OPEN, second.checkedAt());
        }

        return new Decision(Action.NONE, null);
    }

    public static Optional<CheckObservation> latest(List<CheckObservation> checks) {
        return checks.stream()
                .max(Comparator
                        .comparing(CheckObservation::checkedAt)
                        .thenComparing(CheckObservation::id));
    }
}
