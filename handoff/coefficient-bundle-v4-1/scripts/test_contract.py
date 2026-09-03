#!/usr/bin/env python3
"""Focused tests for the Python replay and numerical contract."""

from __future__ import annotations

import unittest

import numpy as np

from build_v41 import (
    LOST,
    PENDING,
    SEAT_MISSING,
    SETTLED,
    SKIPPED,
    Candidate,
    G_GRID,
    cell_profile,
    invariant_labels,
    resolve_targets,
)
from v41_model import (
    ZERO_FEATURE_AXES,
    fit_block,
    score_batch,
    score_one,
)


class LabelContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.prediction = 1_000_000_000
        self.targets = ((49, 5),)

    def resolve(self, rows, *, now_seconds=600, lag=0):
        candidates = [
            Candidate(self.prediction + seconds * 1_000_000, index, passed, seats)
            for index, (seconds, passed, seats) in enumerate(rows)
        ]
        return resolve_targets(
            self.prediction,
            44,
            self.targets,
            candidates,
            [item.observed_us for item in candidates],
            self.prediction + lag * 1_000_000,
            self.prediction + now_seconds * 1_000_000,
        )[0].state

    def test_exact_java_label_branches(self) -> None:
        self.assertEqual(SETTLED, self.resolve([(60, 49, 0)]))
        self.assertEqual(SEAT_MISSING, self.resolve([(60, 49, -1)]))
        self.assertEqual(SKIPPED, self.resolve([(40, 48, 9), (80, 50, 9)]))
        self.assertEqual(LOST, self.resolve([(91, 49, 9)]))
        self.assertEqual(SETTLED, self.resolve([(90, 49, 9)]))
        self.assertEqual(PENDING, self.resolve([(20, 45, 9)]))
        self.assertEqual(LOST, self.resolve([], now_seconds=7201))

    def test_continuous_guard_crossing_changes_event(self) -> None:
        candidates = [Candidate(self.prediction + 60_000_000, 1, 49, 9)]
        baseline, masks, state_changes, _event_changes = invariant_labels(
            self.prediction,
            44,
            self.targets,
            candidates,
            [item.observed_us for item in candidates],
            self.prediction + 8000_000_000,
        )
        self.assertEqual(SETTLED, baseline[0].state)
        self.assertTrue(masks[0] & (1 << G_GRID.index(30)))
        self.assertFalse(masks[0] & (1 << G_GRID.index(60)))
        self.assertTrue(state_changes[0] & (1 << G_GRID.index(60)))


class CellContractTest(unittest.TestCase):
    def test_population_z_and_inverse_square_neighbour(self) -> None:
        dtype = np.dtype(
            [
                ("arrival_day", "<i4"),
                ("arrival_slot", "u1"),
                ("target", "u1"),
                ("arrival", "u1"),
                ("current", "u1"),
            ]
        )
        rows = np.zeros(4, dtype=dtype)
        rows["arrival_day"] = [1, 2, 1, 2]
        rows["arrival_slot"] = 0
        rows["target"] = [1, 1, 2, 2]
        rows["arrival"] = [8, 8, 4, 4]
        rows["current"] = 10
        fill, _net, missing, stats = cell_profile(
            rows, np.asarray([10, 10, 10, 10]), 10
        )
        self.assertAlmostEqual(-1.0, fill[0, 1], places=6)
        self.assertAlmostEqual(1.0, fill[0, 2], places=6)
        self.assertAlmostEqual(0.6, fill[0, 3], places=6)
        self.assertEqual(0, missing[0, 1])
        self.assertEqual(1, missing[0, 3])
        self.assertEqual(2, stats["cells"])


class NumericalContractTest(unittest.TestCase):
    def test_vectorized_scorer_matches_left_to_right_scorer(self) -> None:
        random = np.random.default_rng(4)
        features = random.normal(size=(8, 31))
        features[:, ZERO_FEATURE_AXES] = 0.0
        hurdle = random.normal(scale=0.1, size=31)
        hurdle[list(ZERO_FEATURE_AXES)] = 0.0
        anchor = np.asarray([0.1, 0.8])
        sign = random.normal(scale=0.1, size=(2, 31))
        sign[:, ZERO_FEATURE_AXES] = 0.0
        bins = random.normal(scale=0.1, size=(2, 9, 31))
        bins[:, :, ZERO_FEATURE_AXES] = 0.0
        fitted = np.ones((2, 9), dtype=np.uint8)
        current = np.asarray([10, 20, 30, 40, 5, 0, 12, 22])
        capacity = np.full(8, 44)
        raw, expected, pmf = score_batch(
            features, current, capacity, hurdle, anchor, sign, bins, fitted
        )
        for index in range(8):
            one_raw, one_expected, one_pmf = score_one(
                features[index],
                int(current[index]),
                int(capacity[index]),
                hurdle,
                anchor,
                sign,
                bins,
                fitted,
            )
            self.assertAlmostEqual(one_raw, raw[index], places=13)
            self.assertAlmostEqual(one_expected, expected[index], places=12)
            self.assertTrue(np.allclose(one_pmf, pmf[index], atol=1e-13, rtol=0.0))

    def test_fitter_keeps_constant_zero_axes_positive_zero(self) -> None:
        random = np.random.default_rng(8)
        rows = 600
        features = random.normal(size=(rows, 31))
        features[:, ZERO_FEATURE_AXES] = 0.0
        features[:, 0] = 1.0
        capacity = np.full(rows, 44, dtype=np.int16)
        current = random.integers(0, 45, rows, dtype=np.int16)
        arrival = np.clip(current + random.integers(-5, 6, rows), 0, 44).astype(np.int16)
        fitted = fit_block(features, current, arrival, capacity)
        positive_zero = np.asarray([0.0], dtype=np.float64).view(np.uint64)[0]
        for tensor in (fitted.hurdle, fitted.sign, fitted.bins):
            self.assertTrue(
                np.all(tensor[..., ZERO_FEATURE_AXES].view(np.uint64) == positive_zero)
            )


if __name__ == "__main__":
    unittest.main()
