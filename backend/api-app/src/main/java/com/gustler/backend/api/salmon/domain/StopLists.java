package com.gustler.backend.api.salmon.domain;

import static com.gustler.backend.api.salmon.domain.Requirements.requireText;

import com.gustler.backend.api.salmon.domain.Fields.*;
import com.gustler.backend.api.salmon.domain.Timing.*;
import java.util.List;
import java.util.Objects;

/** 정류장 도착 목록과 그 항목, 창 덮임 상태를 나타낸다. */
public final class StopLists {
    private StopLists() {
    }

    public record StopEntry(String id, Field<Offset> arrival) {
        public StopEntry {
            requireText(id);
            Objects.requireNonNull(arrival);
        }
    }

    public enum Coverage { COVERS_WINDOW, COVERAGE_UNKNOWN }

    public sealed interface StopList permits Queried, RestoredOnly, Unavailable {
        List<StopEntry> entries();
    }

    public record Queried(List<StopEntry> entries, Coverage coverage) implements StopList {
        public Queried {
            entries = List.copyOf(entries);
            Objects.requireNonNull(coverage);
        }
    }

    public record RestoredOnly(List<StopEntry> entries) implements StopList {
        public RestoredOnly {
            entries = List.copyOf(entries);
        }
    }

    public record Unavailable(MissingReason reason) implements StopList {
        public Unavailable {
            Objects.requireNonNull(reason);
        }

        @Override
        public List<StopEntry> entries() {
            return List.of();
        }
    }
}
