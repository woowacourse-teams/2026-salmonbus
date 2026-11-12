import argparse
import json
from pathlib import Path

from .wiki_update_cli import initialize_wiki


def main(argv=None):
    parser = argparse.ArgumentParser(description="Initialize managed Git Wiki documents")
    parser.add_argument("--wiki-directory", required=True)
    parser.add_argument("--seed-directory", required=True)
    parser.add_argument("--result", required=True)
    args = parser.parse_args(argv)
    changed = initialize_wiki(
        Path(args.wiki_directory).resolve(), Path(args.seed_directory).resolve()
    )
    Path(args.result).write_text(
        json.dumps({"changed": changed}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
