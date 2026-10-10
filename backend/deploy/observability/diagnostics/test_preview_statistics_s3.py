import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import preview_statistics_s3 as preview


class PreviewStatisticsS3Test(unittest.TestCase):
    def test_전체_스캔이나_정렬이_필요하면_표본을_읽지_않는다(self):
        # given
        plan = {"Node Type": "Limit", "Total Cost": 5,
                "Plans": [{"Node Type": "Seq Scan"}]}

        # when
        accepted = preview.bounded_plan(plan)

        # then
        self.assertFalse(accepted)

    def test_비용_한도를_넘는_조회는_거부한다(self):
        # given
        plan = {"Node Type": "Index Scan", "Total Cost": 1000}

        # when
        accepted = preview.bounded_plan(plan)

        # then
        self.assertFalse(accepted)

    def test_자료를_먼저_올리고_다시_받은_결과까지_같아야_성공한다(self):
        # given
        row = {"scoring_state": "SETTLED", "scored_at": "2026-10-09", "seats_on_arrival": 12}
        output = io.StringIO()
        objects = {}
        calls = []

        def transfer(arguments):
            calls.append(arguments)
            key = arguments[arguments.index("--key") + 1]
            if arguments[0] == "put-object":
                objects[key] = Path(arguments[arguments.index("--body") + 1]).read_bytes()
            else:
                Path(arguments[-1]).write_bytes(objects[key])

        with tempfile.TemporaryDirectory() as directory, \
             patch.object(preview, "connection_environment", return_value={}), \
             patch.object(preview, "sql", side_effect=[json.dumps([{"Plan": {
                 "Node Type": "Limit", "Total Cost": 10, "Plans": [{"Node Type": "Index Scan"}]}}]),
                 json.dumps(row) + "\n"]), \
             patch.object(preview.tempfile, "mkdtemp", return_value=directory), \
             patch.object(preview, "aws", side_effect=transfer), contextlib.redirect_stdout(output):
            # when
            preview.run()

        # then
        result = json.loads(output.getvalue())
        self.assertTrue(result["roundtrip_verified"])
        self.assertEqual(0, result["db_rows_deleted"])
        self.assertFalse(result["production_demand_calculation_verified"])
        self.assertEqual(12, result["summary"]["arrival_seats_sum"])
        self.assertTrue(calls[0][calls[0].index("--key") + 1].endswith("evaluations.jsonl"))
        self.assertTrue(calls[1][calls[1].index("--key") + 1].endswith("manifest.json"))
        self.assertEqual(["put-object", "put-object", "get-object", "get-object"], [call[0] for call in calls])

    def test_계획이_위험하면_S3에도_아무것도_올리지_않는다(self):
        # given
        with patch.object(preview, "connection_environment", return_value={}), \
             patch.object(preview, "sql", return_value=json.dumps([{"Plan": {
                 "Node Type": "Seq Scan", "Total Cost": 5}}])) as sql, \
             patch.object(preview, "aws") as aws:

            # when & then
            with self.assertRaisesRegex(RuntimeError, "Unexpected scan plan"):
                preview.run()
            self.assertEqual(1, sql.call_count)
            aws.assert_not_called()


if __name__ == "__main__":
    unittest.main()
