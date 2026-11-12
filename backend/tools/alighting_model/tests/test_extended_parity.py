import json
from pathlib import Path
import unittest

import pandas as pd

from causal_worker_fixture import predict_tree_model
from extended_calibration import SETTLEMENT_WINDOWS, classify, settlement_window
from extended_forecast import FEATURES, origin_features


FIXTURE = Path(__file__).resolve().parents[3] / 'business/forecasting/src/test/resources/alighting/extended-parity.json'


class ExtendedParityTest(unittest.TestCase):
    def test_shared_time_cells_and_prediction(self):
        fixture = json.loads(FIXTURE.read_bytes())
        schedule = fixture['cells']
        cells, _ = classify(pd.DataFrame({'t_depart': pd.to_datetime([row['at'] for row in schedule], utc=True)}),
                            fixture['holidays'])
        self.assertEqual([row['cell'] for row in schedule], cells.tolist())
        for row, cell in zip(schedule, cells):
            at = pd.Timestamp(row['at']).tz_convert('Asia/Seoul')
            band, width = settlement_window(cell)
            self.assertEqual(row['bin'], (at.hour * 60 + at.minute - SETTLEMENT_WINDOWS[band][0]) // width)

        origin = fixture['origin']
        received = pd.Timestamp(origin['at'])
        targets = pd.DataFrame(dict(trip=[1], chain=[1], stop=[origin['stop']],
                                    t_arrive=[received + pd.Timedelta(minutes=3)]))
        observations = pd.DataFrame(dict(observation_id=[1], trip=[1], chain=[1], passed=[origin['passed']],
                                         received=[received], seats=[origin['seats']], capacity=[origin['capacity']]))
        inputs = origin_features(targets, observations)[FEATURES].iloc[0].tolist()
        self.assertEqual(fixture['inputs'], inputs)
        self.assertAlmostEqual(fixture['expected_seats'], predict_tree_model(fixture['seats'], inputs))
        self.assertAlmostEqual(fixture['expected_full'], predict_tree_model(fixture['full'], inputs))
