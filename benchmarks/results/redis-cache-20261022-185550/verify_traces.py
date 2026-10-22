#!/usr/bin/env python3
"""Offline validation of saved Day24 traces; never connects to application/DB/Redis.

Usage: python3 verify_traces.py /path/to/results
Writes only <results>/independent-trace-verification.json. Exit 1 means a
trace/request validation failed; digest discrepancies remain explicit diagnostics.
"""
import argparse
import collections
import datetime
import hashlib
import json
import pathlib
import re
import sys

MSG = re.compile(r'\bmsg=("(?:\\.|[^"\\])*")')
SLOT = re.compile(r'^resource:(\d+):slots:(\d{4}-\d{2}-\d{2})$')
RESOURCE = re.compile(r'^resource:(\d+)$')
DECODER = json.JSONDecoder()


def load_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def request_record(line):
    """Accept both k6 logfmt messages and terminal-style unquoted JSON."""
    match = MSG.search(line)
    if match:
        value = json.loads(match.group(1))
        if isinstance(value, str) and 'cache_request' in value:
            obj = json.loads(value)
            if isinstance(obj, dict) and obj.get('kind') == 'cache_request':
                return obj
    if 'cache_request' in line:
        for match in re.finditer(r'\{', line):
            try:
                obj, _ = DECODER.raw_decode(line[match.start():])
            except json.JSONDecodeError:
                continue
            if isinstance(obj, dict) and obj.get('kind') == 'cache_request':
                return obj
        raise ValueError('cache_request marker exists but JSON could not be parsed')
    return None


def validate_block(directory, trace_path, global_resource_id):
    suffix = '.redis-owned-commands.ndjson'
    label = trace_path.name[:-len(suffix)]
    result = {'label': label, 'errors': [], 'checks': {}, 'source_sha256': {}}
    errors, checks = result['errors'], result['checks']

    def check(name, condition, detail=None):
        checks[name] = bool(condition)
        if not condition:
            errors.append({'check': name, 'detail': detail})

    paths = {
        'trace': trace_path,
        'manifest': directory / (label + '.manifest.json'),
        'requests': directory / (label + '.k6.log'),
        'evidence': directory / (label + '.evidence.json'),
    }
    for name, path in paths.items():
        check(name + '_exists', path.is_file(), path.name)
        if path.is_file():
            result['source_sha256'][path.name] = sha256(path)
    if not all(path.is_file() for path in paths.values()):
        result['trace_and_request_passed'] = False
        return result

    fixture = load_json(paths['manifest'])
    evidence = load_json(paths['evidence'])
    dates = fixture['dates']
    expected_dates = [item['date'] for item in dates]
    n = len(dates)
    arm = evidence.get('arm', label.rsplit('-', 1)[-1])
    result.update(arm=arm, expected_requests=n)
    check('known_arm', arm in ('cold', 'hot'), arm)
    check('manifest_dates_unique', len(set(expected_dates)) == n)

    events = []
    for number, line in enumerate(paths['trace'].read_text(encoding='utf-8').splitlines(), 1):
        if not line.strip():
            continue
        try:
            item = json.loads(line)
            if not isinstance(item, dict) or item.get('command') not in ('GET', 'SET') or not isinstance(item.get('key'), str):
                raise ValueError('invalid command record')
            events.append(item)
        except (ValueError, TypeError) as exc:
            errors.append({'check': 'trace_record_parse', 'line': number, 'detail': str(exc)})

    observed_ids = set()
    for item in events:
        match = SLOT.fullmatch(item['key']) or RESOURCE.fullmatch(item['key'])
        if match:
            observed_ids.add(int(match.group(1)))
    resource_id = fixture.get('resource_id', global_resource_id)
    if resource_id is None and len(observed_ids) == 1:
        resource_id = next(iter(observed_ids))
    check('resource_id_identified', resource_id is not None)
    if resource_id is None:
        result['trace_and_request_passed'] = False
        return result
    resource_id = int(resource_id)
    result['resource_id'] = resource_id
    check('only_expected_resource_id', observed_ids == {resource_id}, sorted(observed_ids))
    expected_keys = {f'resource:{resource_id}:slots:{date}' for date in expected_dates}
    resource_key = f'resource:{resource_id}'
    slot_gets, slot_sets = collections.Counter(), collections.Counter()
    resource_gets = resource_sets = 0
    for item in events:
        command, key = item['command'], item['key']
        if key == resource_key:
            if command == 'GET':
                resource_gets += 1
            else:
                resource_sets += 1
        elif SLOT.fullmatch(key):
            (slot_gets if command == 'GET' else slot_sets)[key] += 1
        else:
            errors.append({'check': 'unexpected_trace_key', 'detail': key})
    check('slot_get_keyset_equals_manifest', set(slot_gets) == expected_keys,
          {'missing': sorted(expected_keys - set(slot_gets))[:20], 'unexpected': sorted(set(slot_gets) - expected_keys)[:20]})
    check('each_slot_get_exactly_once', all(v == 1 for v in slot_gets.values()) and len(slot_gets) == n)
    if arm == 'cold':
        check('cold_slot_set_keyset_equals_manifest', set(slot_sets) == expected_keys)
        check('cold_each_slot_set_exactly_once', all(v == 1 for v in slot_sets.values()) and len(slot_sets) == n)
    elif arm == 'hot':
        check('hot_zero_slot_sets', sum(slot_sets.values()) == 0)
    check('resource_gets_equal_requests', resource_gets == n, resource_gets)
    check('resource_zero_sets', resource_sets == 0, resource_sets)
    result['observed_commands'] = {'slot_gets': sum(slot_gets.values()), 'unique_slot_get_keys': len(slot_gets),
                                   'slot_sets': sum(slot_sets.values()), 'resource_gets': resource_gets, 'resource_sets': resource_sets}

    records = []
    for number, line in enumerate(paths['requests'].read_text(encoding='utf-8', errors='strict').splitlines(), 1):
        try:
            item = request_record(line)
            if item is not None:
                records.append(item)
        except (ValueError, TypeError) as exc:
            errors.append({'check': 'request_record_parse', 'line': number, 'detail': str(exc)})
    indices = collections.Counter(item.get('index') for item in records)
    actual_dates = collections.Counter(item.get('date') for item in records)
    check('request_record_count_equals_manifest', len(records) == n, len(records))
    check('request_indices_exactly_cover_manifest', set(indices) == set(range(n)) and all(v == 1 for v in indices.values()))
    check('request_dates_exactly_cover_manifest', set(actual_dates) == set(expected_dates) and all(v == 1 for v in actual_dates.values()))
    mapping_ok = all(type(item.get('index')) is int and 0 <= item['index'] < n and item.get('date') == expected_dates[item['index']] for item in records)
    check('request_index_date_mapping_matches_manifest', mapping_ok)
    check('requests_all_http200_and_business_valid', all(item.get('status') == 200 and item.get('valid') is True for item in records) and len(records) == n)
    check('request_arm_matches_block', all(item.get('arm') == arm for item in records))
    check('k6_exit_zero', evidence.get('k6_exit') == 0, evidence.get('k6_exit'))
    result['observed_requests'] = len(records)

    expected_selects = n if arm == 'cold' else 0
    digest_count = evidence.get('slot_select_delta')
    sql = {'expected_slot_selects': expected_selects,
           'shared_digest_slot_select_delta': digest_count,
           'shared_digest_matches_expected': digest_count == expected_selects,
           'note': 'Digest mismatch remains a mismatch; Redis trace validation does not overwrite it.'}
    if 'slot_selects_via_connections' in evidence:
        sql.update(connection_select_delta=evidence.get('connection_select_delta'),
                   background_scan_delta=evidence.get('background_scan_delta'),
                   slot_selects_via_connections=evidence['slot_selects_via_connections'],
                   connection_derived_slot_count_matches=evidence['slot_selects_via_connections'] == expected_selects,
                   connection_count_limit='Derived from saved counters; stability and scheduler subtraction require separate review.')
    if 'connection_rows_sent_delta' in evidence:
        sql.update(connection_rows_sent_delta=evidence['connection_rows_sent_delta'],
                   expected_slot_rows_sent=2 * expected_selects,
                   connection_rows_sent_matches_expected=evidence['connection_rows_sent_delta'] == 2 * expected_selects)
    result['sql_diagnostics'] = sql
    result['trace_and_request_passed'] = not errors
    result['status'] = ('failed' if errors else 'trace_passed_digest_matched' if sql['shared_digest_matches_expected']
                        else 'trace_passed_digest_mismatch_preserved')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results_directory', type=pathlib.Path)
    args = parser.parse_args()
    directory = args.results_directory.resolve()
    if not directory.is_dir():
        parser.error('results_directory must be an existing directory')
    global_resource_id = None
    global_manifest = directory / 'fixtures-manifest.json'
    if global_manifest.is_file():
        global_resource_id = load_json(global_manifest).get('resource_id')
    blocks = []
    for path in sorted(directory.glob('*.redis-owned-commands.ndjson')):
        try:
            blocks.append(validate_block(directory, path, global_resource_id))
        except Exception as exc:
            blocks.append({'label': path.name.split('.redis-owned-commands.ndjson')[0],
                           'trace_and_request_passed': False, 'status': 'failed',
                           'errors': [{'check': 'unhandled_input_error', 'detail': f'{type(exc).__name__}: {exc}'}]})
    passed = bool(blocks) and all(block['trace_and_request_passed'] for block in blocks)
    mismatches = [block['label'] for block in blocks
                  if not block.get('sql_diagnostics', {}).get('shared_digest_matches_expected', False)]
    output = {'validator': pathlib.Path(__file__).name,
              'validated_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'results_directory': str(directory), 'block_count': len(blocks),
              'all_trace_and_request_checks_passed': passed,
              'shared_digest_mismatch_or_missing_blocks': mismatches,
              'sql_diagnostics_are_separate_from_trace_pass': True,
              'exit_code_policy': '0: all saved traces and request logs pass; 1: validation failure. SQL digest discrepancies are preserved separately.',
              'blocks': blocks}
    destination = directory / 'independent-trace-verification.json'
    destination.write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'output': str(destination), 'block_count': len(blocks),
                      'trace_and_request_passed': passed, 'digest_mismatches_or_missing': mismatches}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
