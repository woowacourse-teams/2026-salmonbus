package com.gustler.backend.api.salmon.domain;

import static com.gustler.backend.api.salmon.domain.Requirements.requireText;
import static com.gustler.backend.api.salmon.domain.Requirements.requireDetour;

import com.gustler.backend.api.salmon.domain.Fields.*;
import com.gustler.backend.api.salmon.domain.StopLists.*;
import com.gustler.backend.api.salmon.domain.Timing.*;
import java.util.Objects;
import java.util.Optional;

/** 이동 후보(직접 도보, 재탑승)와 허용 판정 상태를 나타낸다. */
public final class Candidates {
    private Candidates() {
    }

    public enum CandidateKind { WALK, REBOARD }

    public sealed interface Candidate permits WalkCandidate, ReboardCandidate {
        String id();

        CandidateKind kind();

        Optional<Double> detour();
    }

    public record WalkCandidate(String id, Field<Span> walk, StopList list, Optional<Double> detour)
        implements Candidate {
        public WalkCandidate {
            requireText(id);
            Objects.requireNonNull(walk);
            Objects.requireNonNull(list);
            Objects.requireNonNull(detour);
            requireDetour(detour);
        }

        @Override
        public CandidateKind kind() {
            return CandidateKind.WALK;
        }
    }

    public record ReboardCandidate(String id, Field<Span> walkToOpposite, Field<Span> rideTime,
                                   Field<Span> walkAfterAlight, StopList opposite, StopList reboard,
                                   Optional<Double> detour) implements Candidate {
        public ReboardCandidate {
            requireText(id);
            Objects.requireNonNull(walkToOpposite);
            Objects.requireNonNull(rideTime);
            Objects.requireNonNull(walkAfterAlight);
            Objects.requireNonNull(opposite);
            Objects.requireNonNull(reboard);
            Objects.requireNonNull(detour);
            requireDetour(detour);
        }

        @Override
        public CandidateKind kind() {
            return CandidateKind.REBOARD;
        }
    }

    public enum Admissibility { VIOLATED, ALLOWED, UNCONFIRMED }
}
