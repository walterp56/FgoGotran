"""Bounded, explicitly invoked DeepSeek A/B collector. Credentials stay in memory."""
import argparse
import concurrent.futures
import getpass
import json
from pathlib import Path
import random
import threading
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--requests', default='app/build/reports/emphasis-eval/requests.json')
    parser.add_argument('--out', default='temp/emphasis-eval/responses.json')
    parser.add_argument('--max-calls', type=int, default=144)
    parser.add_argument('--case')
    parser.add_argument('--target')
    args = parser.parse_args()
    if not 1 <= args.max_calls <= 144:
        raise SystemExit('Call cap must be 1..144')
    output = Path(args.out)
    if output.exists():
        raise SystemExit('Choose a new result path; existing results are never overwritten')
    fixture = json.loads(Path(args.requests).read_text(encoding='utf-8'))
    assert fixture['scope'] == 'emphasis'
    requests = [r for r in fixture['requests'] if (not args.case or r['id'] == args.case) and
                (not args.target or r['target'] == args.target)]
    jobs = [(trial, request) for trial in range(2) for request in requests]
    random.Random(20261030).shuffle(jobs)
    jobs = jobs[:args.max_calls]
    key = getpass.getpass('DeepSeek test key (memory only): ').strip()
    if not key:
        raise SystemExit('No key supplied')
    stop = threading.Event()
    lock = threading.Lock()
    errors = 0
    results = []

    def collect(job):
        nonlocal errors
        if stop.is_set():
            return None
        trial, source = job
        payload = {'model': 'deepseek-v4-flash', 'messages': source['messages'],
                   'temperature': 1.0, 'max_tokens': 256, 'thinking': {'type': 'disabled'}}
        request = urllib.request.Request('https://api.deepseek.com/v1/chat/completions',
            data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
            headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key})
        started = time.perf_counter()
        record = {k: source[k] for k in ('id', 'target', 'variant', 'format', 'expects_emphasis')}
        record['trial'] = trial
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                value = json.load(response)
            choice = value['choices'][0]
            record.update(content=choice['message'].get('content', ''),
                finish_reason=choice.get('finish_reason'), model=value.get('model'), usage=value.get('usage', {}))
        except urllib.error.HTTPError as exc:
            record['error'] = 'HTTP ' + str(exc.code)
            if exc.code in (401, 402, 403, 429):
                stop.set()
            with lock:
                errors += 1
                if errors >= 3:
                    stop.set()
        except Exception as exc:
            record['error'] = type(exc).__name__  # Never serialize credentials, request headers or URLs.
            with lock:
                errors += 1
                if errors >= 3:
                    stop.set()
        record['elapsed_ms'] = round((time.perf_counter() - started) * 1000, 2)
        return record

    output.parent.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for record in pool.map(collect, jobs):
            if record is not None:
                results.append(record)
                output.write_text(json.dumps({'scope': 'emphasis', 'concurrency': 3,
                    'call_cap': args.max_calls, 'runs': results}, ensure_ascii=False, indent=2), encoding='utf-8')
                if len(results) % 12 == 0 or 'error' in record:
                    print(f"Completed {len(results)}/{len(jobs)}; errors={errors}", flush=True)
    key = ''
    print(f'Saved {len(results)} responses; errors={errors}; stopped={stop.is_set()}', flush=True)


if __name__ == '__main__':
    main()
