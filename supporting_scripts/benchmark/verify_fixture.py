"""Validate all 144 initial/revised fixture payloads with the real Java DTOs/extractor."""
import argparse
import json
import os
import subprocess
from copy import deepcopy
from pathlib import Path

import heterogeneous as h
import seed

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--classpath-file', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
cases = []
for obj in h.objects(seed.fixture()):
    for phase in ('initial', 'semantic-update'):
        value = deepcopy(obj)
        if phase == 'semantic-update': value.update(value['semanticUpdate'])
        cases.append({'key': obj['id'], 'kind': obj['kind'], 'type': obj['type'], 'phase': phase,
                      'payload': h.payload(seed, value, 1)})
requests = a.output/'dto-cases.json'
requests.write_text(json.dumps(cases, indent=2)+'\n')
subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/java'), '-cp', a.classpath_file.read_text().strip(),
                str(Path(__file__).with_name('VerifyHeterogeneousFixture.java')), str(requests.resolve()),
                str((a.output/'extraction.json').resolve())], check=True)
