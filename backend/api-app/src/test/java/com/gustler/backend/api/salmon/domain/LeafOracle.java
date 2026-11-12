package com.gustler.backend.api.salmon.domain;

import java.util.ArrayList;
import java.util.List;

final class LeafOracle {
    private LeafOracle() {
    }

    record Leaf(double probability, boolean achieved) {
    }

    static List<Leaf> attemptLeaves(double[] successChances) {
        List<Leaf> leaves = new ArrayList<>();
        double reachedHere = 1;
        for (double chance : successChances) {
            leaves.add(new Leaf(reachedHere * chance, true));
            reachedHere *= 1 - chance;
        }
        leaves.add(new Leaf(reachedHere, false));
        return leaves;
    }

    static List<Leaf> reboardLeaves(double[] oppositeChances, double[][] reboardChancesByBranch) {
        List<Leaf> leaves = new ArrayList<>();
        double reachedHere = 1;
        for (int branch = 0; branch < reboardChancesByBranch.length; branch++) {
            double boarded = reachedHere * oppositeChances[branch];
            for (Leaf inner : attemptLeaves(reboardChancesByBranch[branch])) {
                leaves.add(new Leaf(boarded * inner.probability(), inner.achieved()));
            }
            reachedHere *= 1 - oppositeChances[branch];
        }
        leaves.add(new Leaf(reachedHere, false));
        return leaves;
    }

    static double failure(List<Leaf> leaves) {
        double total = leaves.stream().mapToDouble(Leaf::probability).sum();
        if (Math.abs(total - 1) > 1e-9) {
            throw new AssertionError("leaves must sum to 1 but sum to " + total);
        }
        return leaves.stream().filter(leaf -> !leaf.achieved()).mapToDouble(Leaf::probability).sum();
    }
}
