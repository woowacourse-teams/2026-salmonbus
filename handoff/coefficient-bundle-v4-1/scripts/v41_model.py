#!/usr/bin/env python3
"""Numerical contract shared by the v4-1 builder and independent validator."""

from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass
from typing import Mapping

import numpy as np


ROUTES = ("1650", "3330")
HORIZONS = tuple(range(1, 13))
FEATURE_NAMES = (
    "constant",
    "is_morning",
    "is_evening",
    "new_time_slot",
    "seats_left_ratio",
    "is_full",
    "low_seat_band",
    "crowd_level_1",
    "crowd_level_2",
    "crowd_level_3",
    "crowd_level_4",
    "maximum_seats_ratio",
    "seat_slope",
    "seat_slope_missing",
    "full_seat_streak",
    "preceding_vehicle_is_full",
    "preceding_vehicle_seats_ratio",
    "preceding_vehicle_missing",
    "route",
    "stop_position_on_route",
    "stop_position_basis_0",
    "stop_position_basis_1",
    "stop_position_basis_2",
    "stop_position_basis_3",
    "stop_position_basis_4",
    "stop_position_basis_5",
    "stop_position_basis_6",
    "stop_position_basis_7",
    "fill_rate_score",
    "net_boarding_segment_score",
    "filled_by_neighbours",
)
ZERO_FEATURE_AXES = (3, 18, 20, 21, 22, 23, 24, 25, 26, 27)
ACTIVE_FEATURE_AXES = tuple(index for index in range(31) if index not in ZERO_FEATURE_AXES)
RELATIVE_BIN_EDGES = np.asarray((0.0, 0.03, 0.07, 0.12, 0.2, 0.32, 0.48, 0.7, 1.0), dtype=np.float64)
TENSOR_SPECS = {
    "hurdle_coefficients": ("F64", (2, 12, 31)),
    "anchor_coefficients": ("F64", (2, 12, 2)),
    "sign_coefficients": ("F64", (2, 12, 2, 31)),
    "bin_coefficients": ("F64", (2, 12, 2, 9, 31)),
    "bin_fitted": ("U8", (2, 12, 2, 9)),
}


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_json(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def sigmoid(values: np.ndarray | float) -> np.ndarray:
    array = np.asarray(values, dtype=np.float64)
    return 1.0 / (1.0 + np.exp(-np.clip(array, -30.0, 30.0)))


def fit_irls(
    matrix: np.ndarray,
    labels: np.ndarray,
    ridge: float = 1.0,
    iterations: int = 60,
) -> tuple[np.ndarray, int]:
    matrix = np.asarray(matrix, dtype=np.float64)
    labels = np.asarray(labels, dtype=np.float64)
    if matrix.ndim != 2 or labels.shape != (matrix.shape[0],) or not matrix.shape[0]:
        raise ValueError("invalid_irls_input")
    coefficients = np.zeros(matrix.shape[1], dtype=np.float64)
    penalty = np.eye(matrix.shape[1], dtype=np.float64) * float(ridge)
    used = 0
    for used in range(1, iterations + 1):
        probabilities = sigmoid(matrix @ coefficients)
        weights = probabilities * (1.0 - probabilities) + 1e-9
        hessian = matrix.T @ (matrix * weights[:, None]) + penalty
        gradient = matrix.T @ (labels - probabilities) - float(ridge) * coefficients
        try:
            step = np.linalg.solve(hessian, gradient)
        except np.linalg.LinAlgError:
            step = np.linalg.lstsq(hessian, gradient, rcond=None)[0]
        coefficients += step
        if float(np.max(np.abs(step))) < 1e-10:
            break
    if not np.all(np.isfinite(coefficients)):
        raise ValueError("non_finite_irls_coefficients")
    return coefficients, used


def fit_l1_anchor(
    current: np.ndarray,
    arrival: np.ndarray,
    capacity: np.ndarray,
    iterations: int = 40,
) -> tuple[np.ndarray, int]:
    current = np.asarray(current, dtype=np.float64)
    arrival = np.asarray(arrival, dtype=np.float64)
    capacity = np.asarray(capacity, dtype=np.float64)
    if not len(current) or np.any(capacity <= 0):
        raise ValueError("invalid_anchor_input")
    matrix = np.column_stack((np.ones_like(current), current / capacity))
    target = arrival / capacity
    epsilon = 0.5 / float(np.mean(capacity))
    coefficients = np.linalg.lstsq(matrix, target, rcond=None)[0]
    used = 0
    for used in range(1, iterations + 1):
        residual = target - matrix @ coefficients
        weights = 1.0 / np.maximum(np.abs(residual), epsilon)
        weighted = matrix * weights[:, None]
        updated = np.linalg.lstsq(weighted.T @ matrix, weighted.T @ target, rcond=None)[0]
        if float(np.max(np.abs(updated - coefficients))) < 1e-9:
            coefficients = updated
            break
        coefficients = updated
    if not np.all(np.isfinite(coefficients)):
        raise ValueError("non_finite_anchor_coefficients")
    return np.asarray(coefficients, dtype=np.float64), used


def anchor_locations(current: np.ndarray, capacity: np.ndarray, anchor: np.ndarray) -> np.ndarray:
    current = np.asarray(current, dtype=np.float64)
    capacity = np.asarray(capacity, dtype=np.float64)
    located = np.clip((anchor[0] + anchor[1] * current / capacity) * capacity, 0.0, capacity)
    return np.clip(np.rint(located), 0.0, 70.0).astype(np.int16)


def relative_bin_index(magnitude: np.ndarray, scale: np.ndarray) -> np.ndarray:
    thresholds = np.floor(RELATIVE_BIN_EDGES[None, :] * scale[:, None])
    counted = np.sum(thresholds < magnitude[:, None], axis=1)
    return np.clip(counted - 1, 0, 8).astype(np.int8)


@dataclass(frozen=True)
class BlockFit:
    hurdle: np.ndarray
    anchor: np.ndarray
    sign: np.ndarray
    bins: np.ndarray
    fitted: np.ndarray
    counts: Mapping[str, object]


def _restore_zero_coefficients(active: np.ndarray) -> np.ndarray:
    result = np.zeros(31, dtype=np.float64)
    result[np.asarray(ACTIVE_FEATURE_AXES)] = active
    return result


def fit_block(
    features: np.ndarray,
    current: np.ndarray,
    arrival: np.ndarray,
    capacity: np.ndarray,
) -> BlockFit:
    features = np.asarray(features, dtype=np.float64)
    current = np.asarray(current, dtype=np.int16)
    arrival = np.asarray(arrival, dtype=np.int16)
    capacity = np.asarray(capacity, dtype=np.int16)
    if features.ndim != 2 or features.shape[1] != 31 or not len(features):
        raise ValueError("invalid_block_features")
    if np.any(~np.isfinite(features)):
        raise ValueError("non_finite_features")
    if np.any((current < 0) | (current > 70) | (arrival < 0) | (arrival > 70)):
        raise ValueError("seat_out_of_range")
    if np.any((capacity < 1) | (capacity > 70)):
        raise ValueError("capacity_out_of_range")
    if np.any(features[:, ZERO_FEATURE_AXES] != 0.0):
        raise ValueError("nonzero_constant_feature")

    active_matrix = features[:, ACTIVE_FEATURE_AXES]
    hurdle_active, hurdle_iterations = fit_irls(active_matrix, (arrival == 0).astype(np.float64))
    hurdle = _restore_zero_coefficients(hurdle_active)
    anchor, anchor_iterations = fit_l1_anchor(current, arrival, capacity)

    positive = arrival > 0
    if not np.any(positive):
        raise ValueError("no_positive_seat_arrivals")
    positive_matrix = active_matrix[positive]
    locations = anchor_locations(current, capacity, anchor)[positive]
    residual = locations.astype(np.int16) - arrival[positive]
    sign = np.zeros((2, 31), dtype=np.float64)
    sign_iterations = []
    for head, labels in enumerate((residual == 0, residual > 0)):
        fitted_head, used = fit_irls(positive_matrix, labels.astype(np.float64))
        sign[head] = _restore_zero_coefficients(fitted_head)
        sign_iterations.append(used)

    bins = np.zeros((2, 9, 31), dtype=np.float64)
    fitted = np.zeros((2, 9), dtype=np.uint8)
    bin_iterations: dict[str, int] = {}
    usable_capacity = np.minimum(capacity[positive].astype(np.float64), 70.0)
    scales = (
        np.maximum(locations.astype(np.float64), 1.0),
        np.maximum(usable_capacity - locations.astype(np.float64), 1.0),
    )
    direction_counts = []
    bin_positive_counts: list[list[int]] = []
    for direction, mask in enumerate((residual > 0, residual < 0)):
        direction_count = int(np.sum(mask))
        direction_counts.append(direction_count)
        positive_counts = [0] * 9
        if direction_count >= 50:
            assigned = relative_bin_index(
                np.abs(residual[mask]).astype(np.float64), scales[direction][mask]
            )
            direction_matrix = positive_matrix[mask]
            for bin_index in range(9):
                labels = assigned == bin_index
                positive_counts[bin_index] = int(np.sum(labels))
                if positive_counts[bin_index] < 10:
                    continue
                fitted_active, used = fit_irls(direction_matrix, labels.astype(np.float64))
                bins[direction, bin_index] = _restore_zero_coefficients(fitted_active)
                fitted[direction, bin_index] = 1
                bin_iterations[f"{direction}:{bin_index}"] = used
        bin_positive_counts.append(positive_counts)

    for tensor in (hurdle, anchor, sign, bins):
        if not np.all(np.isfinite(tensor)):
            raise ValueError("non_finite_fitted_tensor")
    if any(np.signbit(tensor[..., ZERO_FEATURE_AXES]).any() for tensor in (hurdle, sign, bins)):
        raise ValueError("negative_zero_constant_coefficient")
    return BlockFit(
        hurdle=hurdle,
        anchor=anchor,
        sign=sign,
        bins=bins,
        fitted=fitted,
        counts={
            "rows": int(len(features)),
            "fullRows": int(np.sum(arrival == 0)),
            "positiveSeatRows": int(np.sum(positive)),
            "directionRows": direction_counts,
            "binPositiveRows": bin_positive_counts,
            "iterations": {
                "hurdle": hurdle_iterations,
                "anchor": anchor_iterations,
                "sign": sign_iterations,
                "bins": bin_iterations,
            },
        },
    )


def fit_hurdle_anchor(
    features: np.ndarray,
    current: np.ndarray,
    arrival: np.ndarray,
    capacity: np.ndarray,
) -> tuple[np.ndarray, np.ndarray]:
    active, _ = fit_irls(
        np.asarray(features, dtype=np.float64)[:, ACTIVE_FEATURE_AXES],
        (np.asarray(arrival) == 0).astype(np.float64),
    )
    return (
        _restore_zero_coefficients(active),
        fit_l1_anchor(current, arrival, capacity)[0],
    )


def java_order_linear(features: np.ndarray, coefficients: np.ndarray) -> float:
    total = 0.0
    for feature, coefficient in zip(features, coefficients, strict=True):
        total += float(feature) * float(coefficient)
    return total


def prior_shift(raw: float, row_count: int, actual_full: int, raw_total: float) -> float:
    clipped = min(1.0 - 1e-6, max(1e-6, float(raw)))
    if row_count < 50:
        return clipped
    average = min(1.0 - 1e-6, max(1e-6, raw_total / row_count))
    target = min(
        1.0 - 1e-6,
        max(1e-6, (actual_full + 200.0 * average) / (row_count + 200.0)),
    )
    logit = lambda p: math.log(p / (1.0 - p))
    shift = min(3.0, max(-3.0, logit(target) - logit(average)))
    return float(sigmoid(logit(clipped) + shift))


def score_one(
    features: np.ndarray,
    current: int,
    capacity: int,
    hurdle: np.ndarray,
    anchor: np.ndarray,
    sign: np.ndarray,
    bins: np.ndarray,
    fitted: np.ndarray,
    *,
    same_day: tuple[int, int, float] | None = None,
) -> tuple[float, float, np.ndarray]:
    raw = float(sigmoid(java_order_linear(features, hurdle)))
    full = raw if same_day is None else prior_shift(raw, *same_day)
    usable_capacity = max(int(capacity), 1)
    located = min(
        float(usable_capacity),
        max(0.0, (float(anchor[0]) + float(anchor[1]) * current / usable_capacity) * usable_capacity),
    )
    anchor_seats = int(min(70.0, max(0.0, float(np.rint(located)))))
    same = float(sigmoid(java_order_linear(features, sign[0])))
    below = float(sigmoid(java_order_linear(features, sign[1])))
    above = max(1.0 - same - below, 1e-6)
    direction = np.asarray((same, below, above), dtype=np.float64)
    direction /= np.sum(direction)
    residual = np.zeros(91, dtype=np.float64)
    residual[40] = direction[0]
    for direction_index, sign_value in ((0, 1), (1, -1)):
        weight = float(direction[direction_index + 1])
        if not np.any(fitted[direction_index]):
            residual[40] += weight
            continue
        chance = np.empty(9, dtype=np.float64)
        for bin_index in range(9):
            chance[bin_index] = (
                float(sigmoid(java_order_linear(features, bins[direction_index, bin_index])))
                if fitted[direction_index, bin_index]
                else 1e-6
            )
        chance /= max(float(np.sum(chance)), 1e-9)
        scale = (
            max(float(anchor_seats), 1.0)
            if direction_index == 0
            else max(float(min(capacity, 70) - anchor_seats), 1.0)
        )
        allocated = 0.0
        for bin_index in range(9):
            lowest = max(math.floor(float(RELATIVE_BIN_EDGES[bin_index]) * scale) + 1, 1)
            highest = (
                min(math.floor(float(RELATIVE_BIN_EDGES[bin_index + 1]) * scale), 50)
                if bin_index + 1 < 9
                else 50
            )
            if highest < lowest:
                continue
            mass = weight * float(chance[bin_index])
            allocated += mass
            per = mass / (highest - lowest + 1)
            for magnitude in range(lowest, highest + 1):
                value = sign_value * magnitude
                value = max(value, -40)
                residual[value + 40] += per
        if allocated < weight:
            residual[40] += weight - allocated
    residual = np.maximum(residual, 0.0)
    residual /= max(float(np.sum(residual)), 1e-12)
    seats = np.zeros(71, dtype=np.float64)
    capped = min(max(capacity, 1), 70)
    for index, probability in enumerate(residual):
        residual_value = index - 40
        seat = int(min(capped, max(0, anchor_seats - residual_value)))
        seats[seat] += probability
    seats /= max(float(np.sum(seats)), 1e-12)
    remainder = float(np.sum(seats[1:]))
    result = np.zeros(71, dtype=np.float64)
    if remainder > 0.0:
        result[1:] = seats[1:] * (1.0 - full) / remainder
    else:
        result[max(1, min(anchor_seats, capped))] = 1.0 - full
    result[0] = full
    result /= float(np.sum(result))
    return raw, float(np.dot(result, np.arange(71, dtype=np.float64))), result


def score_batch(
    features: np.ndarray,
    current: np.ndarray,
    capacity: np.ndarray,
    hurdle: np.ndarray,
    anchor: np.ndarray,
    sign: np.ndarray,
    bins: np.ndarray,
    fitted: np.ndarray,
    *,
    full_probability: np.ndarray | None = None,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Vectorized Java-equivalent scorer for evaluation; golden uses score_one."""

    features = np.asarray(features, dtype=np.float64)
    current = np.asarray(current, dtype=np.int16)
    capacity = np.asarray(capacity, dtype=np.int16)
    raw = np.asarray(sigmoid(features @ np.asarray(hurdle, dtype=np.float64)), dtype=np.float64)
    full = raw if full_probability is None else np.asarray(full_probability, dtype=np.float64)
    locations = anchor_locations(current, capacity, anchor).astype(np.int16)
    same = sigmoid(features @ np.asarray(sign[0], dtype=np.float64))
    below = sigmoid(features @ np.asarray(sign[1], dtype=np.float64))
    above = np.maximum(1.0 - same - below, 1e-6)
    direction = np.column_stack((same, below, above))
    direction /= np.sum(direction, axis=1, keepdims=True)

    row_count = len(features)
    residual = np.zeros((row_count, 91), dtype=np.float64)
    residual[:, 40] = direction[:, 0]
    usable_capacity = np.minimum(np.maximum(capacity, 1), 70).astype(np.int16)
    row_axis = np.arange(row_count)
    for direction_index, sign_value in ((0, 1), (1, -1)):
        weight = direction[:, direction_index + 1]
        if not np.any(fitted[direction_index]):
            residual[:, 40] += weight
            continue
        chance = np.full((row_count, 9), 1e-6, dtype=np.float64)
        for bin_index in range(9):
            if fitted[direction_index, bin_index]:
                chance[:, bin_index] = sigmoid(
                    features @ np.asarray(bins[direction_index, bin_index], dtype=np.float64)
                )
        chance /= np.maximum(np.sum(chance, axis=1, keepdims=True), 1e-9)
        scale = (
            np.maximum(locations, 1)
            if direction_index == 0
            else np.maximum(usable_capacity - locations, 1)
        )
        allocated = np.zeros(row_count, dtype=np.float64)
        for scale_value in np.unique(scale):
            selected = np.flatnonzero(scale == scale_value)
            for bin_index in range(9):
                lowest = max(
                    math.floor(float(RELATIVE_BIN_EDGES[bin_index]) * int(scale_value)) + 1,
                    1,
                )
                highest = (
                    min(
                        math.floor(float(RELATIVE_BIN_EDGES[bin_index + 1]) * int(scale_value)),
                        50,
                    )
                    if bin_index + 1 < 9
                    else 50
                )
                if highest < lowest:
                    continue
                mass = weight[selected] * chance[selected, bin_index]
                allocated[selected] += mass
                per = mass / (highest - lowest + 1)
                for magnitude in range(lowest, highest + 1):
                    value = max(sign_value * magnitude, -40)
                    residual[selected, value + 40] += per
        residual[:, 40] += np.maximum(weight - allocated, 0.0)
    residual = np.maximum(residual, 0.0)
    residual /= np.maximum(np.sum(residual, axis=1, keepdims=True), 1e-12)

    scattered = np.zeros((row_count, 71), dtype=np.float64)
    for residual_index in range(91):
        residual_value = residual_index - 40
        seat = np.minimum(
            usable_capacity,
            np.maximum(0, locations - residual_value),
        ).astype(np.int16)
        np.add.at(scattered, (row_axis, seat), residual[:, residual_index])
    scattered /= np.maximum(np.sum(scattered, axis=1, keepdims=True), 1e-12)
    remainder = np.sum(scattered[:, 1:], axis=1)
    result = np.zeros((row_count, 71), dtype=np.float64)
    ordinary = remainder > 0.0
    result[ordinary, 1:] = (
        scattered[ordinary, 1:]
        * ((1.0 - full[ordinary]) / remainder[ordinary])[:, None]
    )
    empty = np.flatnonzero(~ordinary)
    if len(empty):
        fallback = np.maximum(1, np.minimum(locations[empty], usable_capacity[empty]))
        result[empty, fallback] = 1.0 - full[empty]
    result[:, 0] = full
    result /= np.sum(result, axis=1, keepdims=True)
    expected = result @ np.arange(71, dtype=np.float64)
    return raw, expected, result


def tensor_declarations() -> dict[str, object]:
    return {
        name: {"dtype": dtype, "shape": list(shape)}
        for name, (dtype, shape) in TENSOR_SPECS.items()
    }
