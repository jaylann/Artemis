## Release brief: catalog import and book checkout

The campus bookstore is preparing a release that changes supplier-catalog normalization and checkout orchestration. The team has one working day for automated release checks and a limited staging environment. Your job is to decide **what evidence is needed before release**, not to maximize the number of test cases.

Submit a **2–3 page PDF test plan** using the requirements below as the baseline.

## Requirements under test

| ID     | Requirement                                                                          |
| ------ | ------------------------------------------------------------------------------------ |
| CAT-01 | Trim titles, drop empty results, remove exact duplicates, and sort case-sensitively. |
| CAT-02 | Preserve caller-owned input; reject null lists and elements.                         |
| ORD-01 | Only an order with reserved stock and authorized payment reaches fulfillment.        |
| ORD-02 | Declined payment releases stock; a timeout stays pending until reconciled.           |
| ORD-03 | Retrying one order must not create duplicate authorizations or fulfillment jobs.     |
| RES-01 | Two competing requests for the last copy cannot both succeed.                        |

The payment and email providers offer controllable test doubles. The staging environment has two application instances and the same database engine used for deployment. Do not use real payments or customer information.

## 1. Build a traceability matrix · 3 points

Specify **at least ten test cases**. Every requirement above needs coverage, including a negative or boundary case where applicable. Use these columns:

```text
Test ID | Requirement | Level | Setup/input | Action | Expected outcome
```

For example, the catalog input `[' Patterns ', 'Patterns', ' ']` must produce `['Patterns']` and leave the original input unchanged. “No error occurs” is not a sufficient oracle.

## 2. Design failure and concurrency tests · 4 points

Include empty input, adjacent blanks, case-sensitive duplicates, a null element after a valid entry, declined payment, an authorization timeout followed by success, duplicate requests, and concurrent reservation attempts.

Explain how two independent requests are synchronized to compete for one copy. State the observable outcome for **both** requests and the final database state. Mocking the reservation repository cannot demonstrate that the database enforces the invariant.

Choose unit, integration, or end-to-end tests deliberately. Identify which boundaries need a real database and which external responses should be controlled. Use a fixed clock for reservation expiration and avoid arbitrary sleeps as synchronization.

## 3. Set the release gate · 3 points

Prioritize a fast pull-request suite and a smaller staging suite. Define actionable exit criteria, cleanup of test-owned records, and the evidence retained on failure. Explain what happens if a critical invariant test fails even when the overall pass rate is high.

Finish with two residual risks, such as sustained load or differences in a provider's production behavior, and the additional validation each requires. Passing functional tests alone does not demonstrate the response-time target.

## Submission checklist

Upload one PDF with the traceability matrix, environment assumptions, execution order, and release decision criteria. Keep expected outcomes precise enough for another developer to implement the tests without interviewing you.
