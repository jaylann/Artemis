# Offline Atlas analysis

Run `python3 analyze.py --help` for the interface. See
[the evidence package](../../benchmark-results/README.md) for complete reproduction,
source provenance and live-protocol instructions. Accounting uses the standard
library and Decimal. Figures additionally require `requirements.txt`.

The analyzer validates immutable inputs before emitting results. `--baseline`
adds a descriptive comparison without pooling campaigns. An audit failure exits
nonzero and records `criterion: incomplete`; it never removes a bad observation
from the dataset and reports the remainder as success. Output directories are
caller-selected and must not overlap either input archive.

The pinned figure environment requires Python 3.12+. Accounting with `--no-figures`
uses only the Python standard library and supports Python 3.10+.
