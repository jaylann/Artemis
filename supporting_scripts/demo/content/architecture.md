## Architecture brief: the last available library copy

During enrollment week, several students may reserve the same remaining copy of a course book at once. The current application performs “read availability, then insert reservation” in separate operations. Two requests can both report success.

Prepare a **3–4 page PDF architecture report** for a reservation service that handles this race and remains understandable to a small development team.

## Planning assumptions

These are design targets for the assignment, not measured production facts:

| Property         | Target or constraint                                                        |
| ---------------- | --------------------------------------------------------------------------- |
| Peak traffic     | 100 reservation requests per second                                         |
| Response time    | 95% of reservation decisions within 500 ms, excluding notification delivery |
| Consistency      | At most one active reservation for a physical copy                          |
| Retried requests | The same idempotency key returns the same recorded outcome                  |
| Deployment       | Two application instances sharing one relational database                   |
| Notifications    | Email may be unavailable for 15 minutes without blocking reservations       |

Assume authentication supplies a member identifier. A successful reservation holds a copy for 24 hours. Explain how expiration interacts with a new request; a background cleanup job alone must not be your only protection against a race.

## Required report structure

### 1. Context and measurable goals · 3 points

Identify users, boundaries, and the reservation API's observable outcomes. Translate the assumptions into at least three testable quality scenarios with a stimulus, operating conditions, and response measure.

### 2. Components and request flow · 3 points

Include a labeled component diagram covering the web client, reservation application, database, and notification worker. Add a short sequence diagram or numbered trace for two simultaneous requests for the same copy.

State where the transaction begins and ends. Explain how the losing request receives a clear conflict response. Distinguish protecting the business invariant from merely reducing the probability of a race.

### 3. Architectural decision record · 3 points

Write **ADR-001: Enforcing one active reservation per copy** using Context, Decision, Alternatives, and Consequences. Compare at least two concrete options, such as row locking and an optimistic conditional update.

Explain idempotent retries and the gap between committing a reservation and delivering its notification. If you use an outbox, describe who writes it, who reads it, and how duplicate delivery is handled. Justify a design appropriate for the stated scale.

### 4. Communication and limitations · 1 point

Use readable diagrams and a consistent vocabulary. End with two residual risks and how you would validate them. Cite external material if used; a bibliography is not required for your own reasoning.

## Submission checklist

Upload one PDF. Include a title and the exercise name, but no real student or borrower data. Diagrams must be legible at normal zoom. Your report must stand alone without access to source code or a verbal explanation.
