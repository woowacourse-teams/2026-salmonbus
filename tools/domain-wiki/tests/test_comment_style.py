import tempfile
import unittest
from pathlib import Path

from domain_wiki.comment_style import CommentStyleError, load_namukkun_style


class CommentStyleTest(unittest.TestCase):
    def test_loads_skill_and_meme_catalog(self):
        root = Path(__file__).resolve().parents[1] / "skills" / "namukkun"
        style = load_namukkun_style(root)
        self.assertEqual("namukkun", style["name"])
        self.assertIn("정책 판단", style["instructions"])
        self.assertIn("점수, 통과 기준, 정책 ID, 후보 ID를 쓰지 않는다", style["instructions"])
        self.assertIn("대충 하면~? 꼭 두 번 일한다~", style["memes"])
        self.assertIn("humanSuggestion", style["boundary"])
        self.assertIn("Keep machine identifiers only in the collapsed JSON", style["boundary"])

    def test_missing_skill_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(CommentStyleError, "cannot read"):
                load_namukkun_style(directory)


if __name__ == "__main__":
    unittest.main()
