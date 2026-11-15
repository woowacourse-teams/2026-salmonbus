"""Check that query-origins.json matches the SQL origin tags in the sources. Never opens a DB connection."""
import json
import re
import unittest
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[4]
REGISTRY = Path(__file__).with_name('query-origins.json')
# postgres-statements.yaml and postgres-extra.yaml read the tag with this pattern.
TAG = re.compile(r'salmonbus:([a-z_.]+) \*/')
NAME = re.compile(r'[a-z_.]+')
# Sources that build the tag from a name instead of writing it out.
TABLE_INSERT = 'salmonbus:%s.insert */'
REQUEST_ORIGIN = '" /* salmonbus:" + origin + " */"'


def main_sources():
    for path in sorted((REPOSITORY / 'backend').rglob('*.java')):
        parts = path.relative_to(REPOSITORY).parts
        if 'build' not in parts and 'src' in parts and 'main' in parts:
            yield path


def written_tags():
    tags = {}
    for path in main_sources():
        for tag in TAG.findall(path.read_text(encoding='utf-8')):
            tags.setdefault(tag, set()).add(path.relative_to(REPOSITORY).as_posix())
    return tags


class QueryOriginsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registry = json.loads(REGISTRY.read_text(encoding='utf-8'))
        cls.written = written_tags()

    def test_every_tag_written_in_sources_is_registered_with_its_source(self):
        for tag, sources in sorted(self.written.items()):
            with self.subTest(tag=tag):
                self.assertIn(tag, self.registry)
                self.assertIn(self.registry[tag]['source'], sources)

    def test_every_registered_origin_exists_in_its_source(self):
        for tag, entry in sorted(self.registry.items()):
            with self.subTest(tag=tag):
                source = REPOSITORY / entry['source']
                self.assertTrue(source.is_file(), entry['source'])
                if tag in self.written:
                    continue
                text = source.read_text(encoding='utf-8')
                if tag.endswith('.insert'):
                    self.assertIn(TABLE_INSERT, text)
                    self.assertTrue(self.inserts_into(tag.removesuffix('.insert')), tag)
                    continue
                self.assertIn(REQUEST_ORIGIN, text)
                self.assertIn(f'"{tag}"', text)

    def test_registered_names_match_the_exported_pattern(self):
        for tag in self.registry:
            with self.subTest(tag=tag):
                self.assertRegex(tag, f'^{NAME.pattern}$')

    def test_origins_are_named_after_the_work_not_a_stage_prefix(self):
        self.assertEqual([], [tag for tag in self.registry if tag.startswith('pipeline')])

    @staticmethod
    def inserts_into(table):
        return any(f'new MultiRowInsert("{table}"' in path.read_text(encoding='utf-8') for path in main_sources())


if __name__ == '__main__':
    unittest.main()
