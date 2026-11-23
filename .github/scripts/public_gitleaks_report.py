#!/usr/bin/env python3
"""Selecciona campos técnicos de los hallazgos JSON de Gitleaks; nunca reescribe SARIF."""
import argparse
import json
from pathlib import Path

FIELDS = frozenset({'RuleID','Description','File','StartLine','EndLine','Commit','Fingerprint'})


def select(findings):
    if not isinstance(findings,list) or any(not isinstance(row,dict) for row in findings):
        raise ValueError('Expected a Gitleaks JSON finding array, not SARIF')
    selected=[]
    for row in findings:
        result={key:value for key,value in row.items() if key in FIELDS}
        if any(type(value) is not int or value<0 for key,value in result.items() if key in {'StartLine','EndLine'}):
            raise ValueError('Gitleaks line numbers must be nonnegative integers')
        if any(not isinstance(value,str) for key,value in result.items() if key not in {'StartLine','EndLine'}):
            raise ValueError('Gitleaks public technical fields must be strings')
        selected.append(result)
    return selected


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path);parser.add_argument('output',type=Path)
    args=parser.parse_args()
    if args.source.resolve()==args.output.resolve():raise ValueError('Write a selected public copy separately')
    args.output.write_text(json.dumps(select(json.loads(args.source.read_text())),indent=2)+'\n')


if __name__=='__main__':main()
