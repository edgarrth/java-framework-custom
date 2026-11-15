#!/usr/bin/env python3
"""Validate local links in the repository's Markdown documentation without network requests."""
import argparse
from html.parser import HTMLParser
import json
from pathlib import Path
import subprocess
from urllib.parse import unquote, urlsplit

import markdown

ROOT = Path(__file__).resolve().parents[2]


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []

    def handle_starttag(self, tag, attributes):
        key = 'href' if tag == 'a' else 'src' if tag == 'img' else None
        if key:
            self.links.extend(value for name, value in attributes if name == key and value)


def validate_links(root, documents):
    root = Path(root).resolve()
    errors = []
    for document in documents:
        path = root/document
        if not path.resolve().is_relative_to(root):
            errors.append({'document': str(document), 'error': 'Documentation must resolve inside the repository'})
            continue
        parser = Links()
        parser.feed(markdown.markdown(path.read_text(encoding='utf-8'), extensions=['fenced_code', 'tables']))
        for link in parser.links:
            parsed = urlsplit(link)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            value = unquote(parsed.path)
            target = (root/value.lstrip('/') if value.startswith('/') else path.parent/value).resolve()
            if not target.is_relative_to(root) or not target.exists():
                errors.append({'document': str(document), 'link': link, 'error': 'Local link does not resolve inside the repository'})
    return errors


def check(root):
    root = Path(root).resolve()
    names = subprocess.check_output(['git', '-C', str(root), 'ls-files', '-co', '--exclude-standard', '-z']).decode().split('\0')
    documents = sorted({name for name in names if name.endswith('.md') and (root/name).is_file()
                        and not {'target', 'node_modules'}.intersection(Path(name).parts)})
    errors = validate_links(root, documents)
    if 'README.md' not in documents:
        errors.append({'document': 'README.md', 'error': 'The repository needs an entry-point README'})
    return {'passed': not errors, 'documentsChecked': len(documents), 'errors': errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    result = check(args.root)
    print(json.dumps(result, indent=2))
    return int(not result['passed'])


if __name__ == '__main__':
    raise SystemExit(main())
