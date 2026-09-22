"""Read native completion evidence without adding application hooks.

Automatic NO_OP results deliberately do not emit a STOMP summary. The existing
scheduler DEBUG log supplies their terminal run ID and the dispatched batch size.
"""
import json
import re
import time
from pathlib import Path
from urllib.parse import urlsplit

FIRING = re.compile(r'atlas\.automatic course (\d+) firing run ([\w-]+) with (\d+) exercise\(s\) and (\d+) lecture unit\(s\)')
NO_OP = re.compile(r'atlas\.automatic course (\d+) run ([\w-]+) had no applicable changes; no summary broadcast')


def native_noop(lines, course_id):
    starts, ended = {}, set()
    evidence = []
    for line in lines:
        firing, done = FIRING.search(line), NO_OP.search(line)
        if firing and int(firing[1]) == course_id:
            starts[firing[2]] = int(firing[3]) + int(firing[4])
            evidence.append(line)
        if done and int(done[1]) == course_id:
            ended.add(done[2])
            evidence.append(line)
    if len(starts) > 1: raise RuntimeError('Multiple automatic runs in one observation')
    for run_id, count in starts.items():
        if run_id in ended:
            return {'courseId': course_id, 'runId': run_id, 'exerciseCount': count, 'status': 'NO_OP',
                    'completionEvidence': 'native-scheduler-log', 'nativeLogLines': evidence}
    return None


class Completion:
    def __init__(self, base, cookies, course_id, server_log):
        import websocket
        url = urlsplit(base)
        self.course_id = course_id
        self.log = Path(server_log).open()
        self.log.seek(0, 2)
        self.lines = []
        self.log_buffer = ''
        self.timeout_error = websocket.WebSocketTimeoutException
        try:
            self.ws = websocket.create_connection(
                ('wss' if url.scheme == 'https' else 'ws') + '://' + url.netloc + '/websocket/websocket',
                cookie='; '.join(c.name + '=' + c.value for c in cookies), origin=base, timeout=30)
            self.buffer = ''
            self.ws.send('CONNECT\naccept-version:1.2\nhost:' + url.netloc + '\nheart-beat:0,0\n\n\0')
            if not self.frame(30).startswith('CONNECTED\n'):
                raise RuntimeError('STOMP connection not acknowledged')
            self.ws.send(f'SUBSCRIBE\nid:benchmark\ndestination:/topic/atlas/orchestrator/{course_id}\nack:auto\nreceipt:ready\n\n\0')
            frame = self.frame(30)
            if not frame.startswith('RECEIPT\n') or 'receipt-id:ready' not in frame:
                raise RuntimeError('STOMP subscription not acknowledged')
        except BaseException:
            if hasattr(self, 'ws'): self.ws.close()
            self.log.close()
            raise

    def frame(self, timeout):
        deadline = time.monotonic() + timeout
        while '\0' not in self.buffer:
            remaining = deadline - time.monotonic()
            if remaining <= 0: raise TimeoutError('Completion notification missing')
            self.ws.settimeout(remaining)
            value = self.ws.recv()
            if not value: raise RuntimeError('Completion connection closed')
            self.buffer += value.decode() if isinstance(value, bytes) else value
            self.buffer = self.buffer.lstrip('\r\n')
        frame, self.buffer = self.buffer.split('\0', 1)
        if frame.startswith('ERROR'): raise RuntimeError('STOMP subscription rejected')
        return frame.replace('\r\n', '\n')

    def wait(self, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.log_buffer += self.log.read()
            *lines, self.log_buffer = self.log_buffer.split('\n')
            self.lines.extend(lines)
            noop = native_noop(self.lines, self.course_id)
            if noop: return noop
            try:
                frame = self.frame(min(1, deadline-time.monotonic()))
            except (TimeoutError, self.timeout_error):
                continue
            if not frame.startswith('MESSAGE\n'):
                raise RuntimeError('Expected completion message')
            summary = json.loads(frame.split('\n\n', 1)[1])
            if summary['courseId'] != self.course_id:
                raise RuntimeError('Wrong course in completion message')
            return summary
        raise TimeoutError('Native completion evidence missing')

    def close(self):
        self.ws.close()
        self.log.close()
