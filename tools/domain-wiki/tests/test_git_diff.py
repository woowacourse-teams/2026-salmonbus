import subprocess
import tempfile
import unittest
from pathlib import Path

from domain_wiki.git_diff import read_git_diff


class GitDiffTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", "-b", "main", str(self.root)], check=True)
        subprocess.run(
            ["git", "-C", str(self.root), "config", "user.email", "test@example.com"],
            check=True,
        )
        subprocess.run(
            ["git", "-C", str(self.root), "config", "user.name", "Test"],
            check=True,
        )
        (self.root / "shared.txt").write_text("base\n", encoding="utf-8")
        self._commit("base")
        self.base = self._rev()

        subprocess.run(
            ["git", "-C", str(self.root), "checkout", "-qb", "feature"], check=True
        )
        (self.root / "feature.txt").write_text("feature\n", encoding="utf-8")
        self._commit("feature")
        self.feature = self._rev()

        subprocess.run(
            ["git", "-C", str(self.root), "checkout", "-q", "main"], check=True
        )
        (self.root / "main.txt").write_text("main\n", encoding="utf-8")
        self._commit("main advanced")
        self.main = self._rev()

    def tearDown(self):
        self.temp.cleanup()

    def test_pr_diff_uses_merge_base_when_base_branch_has_advanced(self):
        pr_diff = read_git_diff(
            self.root, self.main, self.feature, use_merge_base=True
        )
        direct_diff = read_git_diff(self.root, self.main, self.feature)

        self.assertEqual(self.base, pr_diff.base_sha)
        self.assertEqual(("feature.txt",), pr_diff.changed_paths)
        self.assertEqual(("feature.txt", "main.txt"), direct_diff.changed_paths)

    def _commit(self, message):
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        subprocess.run(
            ["git", "-C", str(self.root), "commit", "-qm", message], check=True
        )

    def _rev(self):
        return subprocess.check_output(
            ["git", "-C", str(self.root), "rev-parse", "HEAD"], text=True
        ).strip()


if __name__ == "__main__":
    unittest.main()
