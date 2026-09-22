// Canonical content shared by every demo pair; Markdown lives beside this module.
import { readFileSync } from 'node:fs';
const task = (key) => readFileSync(new URL(`./content/${key}.md`, import.meta.url), 'utf8').trim();
const exercise = (type, key, title, gradingInstructions, extra = {}) => ({
    type,
    key,
    title,
    problemStatement: type === 'quiz' ? undefined : task(key),
    gradingInstructions,
    difficulty: 'MEDIUM',
    maxPoints: 10,
    ...extra,
});
const question = (title, text, answers, explanation) => ({
    type: 'multiple-choice',
    title,
    text,
    explanation,
    points: 2.5,
    scoringType: 'ALL_OR_NOTHING',
    singleChoice: false,
    randomizeOrder: false,
    answerOptions: answers.map(([text, isCorrect, explanation]) => ({ text, isCorrect, explanation })),
});
export const description =
    'Design, implement, and evaluate maintainable software. This course connects object-oriented modeling, collection algorithms, and systematic testing through a library and order-management case study. Weekly exercises combine implementation, diagrams, written analysis, and technical documentation.';
export const exercises = [
    exercise(
        'programming',
        'sorting',
        'Library Pickup Queue - Sorting Strategies',
        '## Review guidance\n\n- Check correct sorting and preservation of duplicate dates.\n- Check the strategy interface, concrete strategies, and Context delegation.\n- Check policy selection at 10/11 dates and integration.\n\nThe programming score is calculated from the configured automated tests. Discuss complexity separately; do not award or deduct hidden manual points. Bubble sort is a teaching comparison, not a production recommendation.',
        { maxPoints: 20 },
    ),
    exercise(
        'programming',
        'collections',
        'Catalog Import - Normalization and Deduplication',
        '## Review guidance\n\n- Check trimming, removal of empty values, and deduplication of normalized titles.\n- Check natural ordering and unchanged caller-owned input.\n- Check the character total, empty input, and null rejection.\n\nThe automated tests determine the programming score. Accept either a TreeSet or a set-and-sort design; do not demand a particular collection implementation. Case folding and Unicode normalization would violate the stated contract.',
    ),
    exercise('quiz', 'ooquiz', 'Design Review: Policies and Domain Boundaries', undefined, {
        quizQuestions: [
            question(
                'Replace a lending rule without changing checkout',
                '### Scenario\n\nThe checkout service supports a standard 28-day loan and a seven-day course-reserve loan. Next semester, staff loans will use a different duration.\n\nThe team proposes a `LoanPolicy` interface with `termsFor(member, item)`. **Select all design decisions that preserve replaceability.** Each question is worth 2.5 points; all correct options and no incorrect options are required.',
                [
                    [
                        'Let checkout depend on `LoanPolicy`; select the concrete policy at the application boundary.',
                        true,
                        'Checkout uses a stable contract while policy selection can change independently.',
                    ],
                    ['Add an `instanceof` branch for every policy inside checkout.', false, 'Every new policy would require modifying the context, defeating replaceability.'],
                    [
                        'Test shared eligibility and result invariants against every policy implementation.',
                        true,
                        'Substitutable implementations must honor the same observable contract.',
                    ],
                    ['Require every policy to access the web request directly.', false, 'Coupling policies to transport details makes reuse and isolated testing harder.'],
                ],
                'The strategy contract belongs at the point of variation. Runtime selection and contract tests support replacing one implementation without changing checkout.',
            ),
            question(
                'Keep loan history when a copy is returned',
                '### Scenario\n\nCopy `C-101` was borrowed and returned in September, then borrowed again in October. The library must retain both transactions.\n\n**Which statements belong in a correct domain model?**',
                [
                    ['Every loan references exactly one physical copy and one member.', true, 'The loan records a single borrowing transaction.'],
                    ['A copy can be associated with at most one loan over its entire lifetime.', false, 'This would prevent retaining multiple historical loans.'],
                    [
                        'A copy may have many historical loans, with a separate constraint allowing at most one active loan.',
                        true,
                        'Static multiplicity and the conditional active-loan invariant express different rules.',
                    ],
                    ['The return date belongs on the bibliographic Book so every copy shares it.', false, 'Return dates describe individual loans, not publication metadata.'],
                ],
                'Multiplicity must permit history. The one-active-loan rule is a conditional invariant that must be enforced atomically when checking out a copy.',
            ),
            question(
                'A provider timeout crosses an abstraction boundary',
                '### Scenario\n\nA `PaymentGateway` returns `APPROVED`, `DECLINED`, or `PENDING`. The HTTP adapter loses its connection after sending an authorization request.\n\n**Which responses preserve the gateway contract?**',
                [
                    [
                        'Return `PENDING` and reconcile the original request with its stable identifier.',
                        true,
                        'The provider may have accepted the request before the connection failed.',
                    ],
                    ['Return `DECLINED` because no success response was received.', false, 'Lack of a response does not prove a negative business outcome.'],
                    [
                        'Keep HTTP status parsing and connection exceptions inside the adapter.',
                        true,
                        'The application should act on payment outcomes rather than transport-specific details.',
                    ],
                    ['Generate a new payment identifier every time the client retries.', false, 'A new identifier may cause the provider to authorize the same order twice.'],
                ],
                'An uncertain transport outcome must remain uncertain at the business boundary. Stable identifiers allow safe retries and reconciliation.',
            ),
            question(
                'Policy changes must not rewrite agreed terms',
                '### Scenario\n\nOn 1 October, a member receives a loan due on 29 October. On 10 October, the default duration changes from 28 to 21 days. Existing agreements must remain valid.\n\n**Which design choices satisfy this requirement?**',
                [
                    ['Record the agreed due date and policy version when creating the loan.', true, 'The transaction retains the terms actually applied at checkout.'],
                    ['Calculate every old loan’s due date from the latest policy whenever it is displayed.', false, 'This retroactively changes existing agreements.'],
                    ['Keep policy selection separate from the recorded loan terms.', true, 'Selecting rules for a new transaction differs from reading its historical outcome.'],
                    [
                        'Create a new Book subclass for every member category and policy version.',
                        false,
                        'Policy history is transaction data; a growing book hierarchy does not solve it cleanly.',
                    ],
                ],
                'Policies calculate terms for new loans. Persisted loan terms document a historical agreement and should not drift with later configuration.',
            ),
        ],
    }),
    exercise('quiz', 'testquiz', 'Release Readiness: Testing and Failure Analysis', undefined, {
        quizQuestions: [
            question(
                'Test the boundary where the strategy changes',
                '### Scenario\n\n`Policy.configure()` selects bubble sort for **at most 10 dates** and merge sort for larger lists. A refactor changes `size > 10` to `size >= 10`.\n\n**Which statements are correct?** Select all that apply; each question uses all-or-nothing scoring for 2.5 points.',
                [
                    ['A test asserting the strategy for exactly 10 dates detects the change.', true, 'Ten belongs to the bubble-sort partition in the contract.'],
                    ['Tests for 10 and 11 dates exercise both sides of the decision boundary.', true, 'These adjacent values distinguish inclusive from exclusive selection.'],
                    [
                        'Checking only that the final list is sorted will necessarily detect the bug.',
                        false,
                        'Both algorithms may sort correctly even when the policy chooses the wrong one.',
                    ],
                    [
                        'A test with 100 dates is enough to establish correctness at the boundary.',
                        false,
                        'Both versions select merge sort for 100, so that test misses the defect.',
                    ],
                ],
                'The oracle must observe the requirement that changed. Correct output ordering alone does not prove correct strategy selection.',
            ),
            question(
                'Choose assertions that expose catalog defects',
                '### Scenario\n\n`uniqueSortedTitles` must trim, deduplicate, sort, and preserve its input.\n\n```java\nvar input = new ArrayList<>(List.of(" B ", "A", "B", " "));\nvar actual = uniqueSortedTitles(input);\n```\n\n**Which assertions test the documented behavior?**',
                [
                    ['Assert that `actual` equals `["A", "B"]`.', true, 'This checks several transformations and their order with an explicit expected result.'],
                    ['Assert that `input` still equals `[" B ", "A", "B", " "]`.', true, 'Ownership is part of the contract and needs its own assertion.'],
                    ['Assert only that `actual` is not null.', false, 'A non-null result could still contain duplicates, blanks, or incorrect order.'],
                    ['Assert that `actual` is the same object as `input`.', false, 'The contract requires a new result and permits immutable caller input.'],
                ],
                'Useful assertions describe observable contracts. Separate assertions for the result and original input catch different classes of defect.',
            ),
            question(
                'Demonstrate that the last copy cannot be reserved twice',
                '### Scenario\n\nTwo application instances share one database. Both receive a request for the last available copy.\n\n**Which choices provide relevant evidence for the concurrency requirement?**',
                [
                    [
                        'Use independent transactions against the deployment database engine and coordinate competing requests.',
                        true,
                        'This exercises the real persistence mechanism at the contested boundary.',
                    ],
                    ['Check that exactly one request succeeds and exactly one active reservation remains.', true, 'Both the responses and persisted invariant matter.'],
                    [
                        'Mock the repository to return success once and conflict once, then conclude that the race is prevented.',
                        false,
                        'The mock scripts the desired result without testing the database invariant.',
                    ],
                    [
                        'Run the same test twice sequentially and treat it as equivalent to simultaneous requests.',
                        false,
                        'Sequential execution cannot expose the check-then-insert interleaving.',
                    ],
                ],
                'A unit test can check error handling, but the database race needs an integration test. Synchronization should create the contested interleaving rather than rely on an arbitrary sleep.',
            ),
            question(
                'A timeout followed by success must not duplicate payment',
                '### Scenario\n\nA payment test double records an authorization, times out on the first call, then reports approval when the same request is reconciled.\n\n**Which assertions and controls belong in the test?**',
                [
                    ['Assert that reconciliation uses the original idempotency key.', true, 'A stable key lets the provider recognize the same operation.'],
                    ['Assert that fulfillment starts only after the approved result is known.', true, 'Pending payment is not permission to ship.'],
                    [
                        'Use the real external provider so random failures make the test more realistic.',
                        false,
                        'Uncontrolled failures make the scenario unreliable and can trigger real side effects.',
                    ],
                    ['Make the result depend on a previous test having created an order.', false, 'Each test should establish its own state and be independently repeatable.'],
                ],
                'Control the failure sequence and observe the business invariant. A realistic test is one that exercises the intended failure path reliably, not one that depends on chance.',
            ),
        ],
    }),
    exercise(
        'modeling',
        'library',
        'Circulation Service - Books Copies and Loans',
        '## Rubric\n\n1. Domain concepts and responsibility placement: 4 points.\n2. Associations, role names, and both-end multiplicities: 4 points.\n3. Conditional invariants and atomic checkout explanation: 2 points.\n\nAccept equivalent models with explicit assumptions. Do not accept a lifetime 0..1 Loan multiplicity on BookCopy, because it discards history. Composition is optional and must be justified. Deduct a modeling defect once rather than in every category.',
        {
            diagramType: 'ClassDiagram',
            exampleSolutionExplanation:
                '## Reference reasoning\n\n- `Book` has 0..* copies; each `BookCopy` refers to exactly one book.\n- Each `Loan` links exactly one member and one copy; members and copies can have 0..* historical loans.\n- `returnDate` is optional and belongs to Loan. `close(returnDate)` changes that transaction only.\n- Notes constrain each copy to at most one loan with no return date and each member to at most five such loans.\n- The checkout operation checks member status and these limits atomically with creating a loan. Multiplicity alone cannot enforce conditional or concurrent rules.\n\nThe two loans in the worked example both reference C-101. C-102 remains available.',
        },
    ),
    exercise(
        'modeling',
        'orders',
        'Book Ordering - Payment Stock and Compensation',
        '## Rubric\n\n1. Successful flow and stock/payment prerequisites: 4 points.\n2. Guarded alternatives, backorder loop, and reservation compensation: 4 points.\n3. Safe concurrency and idempotent retry notes: 2 points.\n\nA timeout must remain pending until reconciled. Accept sequential fulfillment and notification if the student explicitly explains the concurrency option; do not reward a fork before prerequisites have succeeded. Releasing an unreserved item or shipping from a pending branch is a consistency defect.',
        {
            diagramType: 'ActivityDiagram',
            difficulty: 'HARD',
            exampleSolutionExplanation:
                '## Reference flow\n\nValidate address → reserve all stock → authorize payment. Invalid address ends before reservation. Unavailable stock branches to a backorder decision; acceptance waits for replenishment and loops to reservation. Decline releases stock and ends after notification. Timeout loops through reconciliation using the original request identifier until approved or declined.\n\nAfter approval, fulfillment-job creation and confirmation delivery may fork. A notification retry stays inside its branch and never repeats fulfillment. A join, if used, waits for both branches; a merge belongs only where alternatives reconverge.',
        },
    ),
    exercise(
        'text',
        'tradeoffs',
        'Design Decision - Configurable Lending Policies',
        '## Rubric\n\n- Diagnose independent dimensions and a concrete existing-behavior risk: 3 points.\n- Define cohesive responsibilities, policy contract, example checkout, and recorded terms: 4 points.\n- Compare alternatives, acknowledge a cost, and propose an incremental migration with tests: 3 points.\n\nReward justified simplicity. Do not require a specific pattern name. A proposal that recomputes historical due dates from current policy does not meet the key business constraint.',
        {
            exampleSolution:
                '## Problem\n\nMember category, loan category, and delivery format vary independently. Encoding their combinations in Book subclasses repeats rules and encourages changes to historical loans.\n\n## Decision\n\nKeep bibliographic data separate from inventory or digital access. A LoanPolicy evaluates eligibility and returns terms. Checkout selects the policy, validates the member, and records the agreed due date and policy version on Loan.\n\n```java\nLoanTerms terms = policies.forLoan(member, item).termsFor(member, item, clock.instant());\nreturn loans.create(member.id(), item.id(), terms.dueDate(), terms.policyVersion());\n```\n\nA 28-day loan remains due after 28 days even when the next policy version offers only 21.\n\n## Alternatives\n\nA small conditional service may be simpler initially. Separate strategies become worthwhile when rules change independently, but add indirection and configuration validation. Inheritance can still represent a genuine stable specialization; it should not enumerate policy combinations.\n\n## Migration\n\nFirst capture current behavior with characterization tests. Next delegate existing subclasses through the policy interface while preserving their public API. Finally migrate callers and persist policy versions and agreed terms for new loans. Backfill old terms from recorded due dates rather than recalculating them.',
        },
    ),
    exercise(
        'text',
        'review',
        'Pull Request Review - Catalog Import Reliability',
        '## Rubric\n\n- Four distinct findings with actual and required behavior: 5 points.\n- Correct replacement and ownership explanation: 3 points.\n- Precise regression assertions: 2 points.\n\nDo not count null rejection itself as missing: the code does throw NullPointerException, but can mutate earlier entries before reaching a null. Require the student to distinguish that real defect. Accept the trim/isBlank semantic mismatch as an additional well-demonstrated finding.',
        {
            exampleSolution:
                '## Findings\n\n- An ArrayList containing two spaces-only entries leaves one behind: removal shifts the next element into the index that the loop skips.\n- `[" Patterns ", "Patterns"]` produces duplicate normalized values; sorting does not deduplicate.\n- The method modifies and returns the caller’s collection. An ArrayList changes, while `List.of(" B ", "A")` rejects the attempted set.\n- With a mutable `[" B ", null]`, the first element becomes `"B"` before the null throws. Invalid input can therefore leave caller state changed.\n\n## Proposed correction\n\nValidate the list and elements, then trim into a new collection, discard empty results, deduplicate, and sort. A TreeSet using natural order followed by a new ArrayList satisfies the contract.\n\n## Regression tests\n\n1. Two adjacent blanks produce an empty result and preserve the input.\n2. `[" Patterns ", "Patterns"]` produces `["Patterns"]`.\n3. An immutable `[" B ", "A"]` produces `["A", "B"]`.\n4. A mutable `[" B ", null]` throws NullPointerException and remains unchanged.\n5. `["java", "Java"]` preserves both titles in natural order.',
        },
    ),
    exercise(
        'file-upload',
        'architecture',
        'Architecture Decision Record - Concurrent Reservations',
        '## Rubric\n\n- Context and measurable quality scenarios: 3 points.\n- Component boundaries and competing-request trace: 3 points.\n- Atomic invariant, idempotency, alternatives, and notification consistency: 3 points.\n- Readability, stated assumptions, and residual risks: 1 point.\n\nAccept row locking, optimistic concurrency, or another correctly argued relational design. A process-local lock is insufficient across two application instances. Do not award concurrency credit for checking availability outside the write transaction.',
        {
            filePattern: 'pdf',
            difficulty: 'HARD',
            exampleSolution:
                'A strong report serializes or conditionally updates the contested copy in the shared database, checks active/expired reservation state inside that transaction, and persists the outcome under a unique idempotency key. A transactional outbox can separate committing a reservation from retrying email. The losing request returns a clear conflict, while a replay of the winning request returns its original outcome. The ADR compares contention, retry behavior, and operational complexity, then proposes a competing-transaction test and a separate load test.',
        },
    ),
    exercise(
        'file-upload',
        'testplan',
        'Release Test Plan - Catalog Import and Checkout',
        '## Rubric\n\n- At least ten concrete cases tracing every named requirement: 3 points.\n- Boundary, failure, idempotency, and true concurrent-integration coverage: 4 points.\n- Prioritized execution, release gate, cleanup, evidence, and residual risks: 3 points.\n\nConcurrency evidence must use independent transactions and explicit coordination. Reject assertions such as “works correctly” without an oracle. A high pass percentage does not justify releasing with a violated stock or payment invariant.',
        {
            filePattern: 'pdf',
            exampleSolution:
                'Use unit cases for empty/blank input, normalized duplicates, case-sensitive order, immutable input, and null rejection without mutation. Use a real database for two competing reservations and for idempotency-key persistence. Control payment approval, decline, and timeout-then-approval through a provider double. Assert stock cleanup on decline, one authorization per order, and no fulfillment while pending. Run fast deterministic checks per change and a small two-instance staging suite before release. Block release on any invariant failure, retain request IDs and database evidence, and schedule separate load/provider-contract validation.',
        },
    ),
];
export const lectures = [
    {
        title: 'Object-Oriented Design',
        units: [
            {
                name: 'Responsibilities and domain boundaries',
                content: `# Responsibilities and domain boundaries

A useful object model assigns information and behavior to concepts that change for the same reason. A Book describes a publication; a BookCopy describes inventory. Combining them makes it difficult to represent several physical copies without repeating bibliographic metadata.

A Loan is more than a connection between a member and a copy. It records checkout, due, and return dates, and it remains meaningful after the copy is returned. Modeling the loan explicitly gives lending rules a natural home.

## From requirements to invariants
Translate a requirement such as “a copy cannot be borrowed twice” into an invariant: at most one active loan references a copy. A diagram communicates relationships, but enforcing the invariant requires an atomic operation at the persistence boundary.

## Reflection
Which responsibilities belong to the entity, which require a coordinating service, and which belong to a repository? Use the Circulation Service exercise to justify the distinction.`,
            },
            {
                name: 'Composition and replaceable policies',
                content: `# Composition and replaceable policies

Inheritance represents a stable “is a” relationship. It becomes awkward when independent features each introduce subclasses. Discount rules, lending rules, and delivery formats vary independently, so their combinations should not determine an expanding class hierarchy.

Composition represents each variation behind a small interface. A lending service receives a LoanPolicy, while a pricing service receives a PricePolicy. Callers depend on contracts instead of concrete implementations.

## Strategy in practice
A sorting context delegates to a SortStrategy. A separate selection policy chooses BubbleSort for small collections and MergeSort for larger ones. This keeps algorithm implementation, algorithm selection, and client behavior separate.

## Tradeoff
Indirection adds interfaces and objects. Use it where variation exists or is reasonably expected; unnecessary abstraction can hide simple behavior. Explain the tradeoff rather than assuming every interface improves a design.`,
            },
        ],
    },
    {
        title: 'Algorithms and Collections',
        units: [
            {
                name: 'Sorting contracts and complexity',
                content: `# Sorting contracts and complexity

An ascending sort must preserve the input multiset while ordering adjacent values. Duplicates, empty input, and already sorted input are part of the contract rather than unusual exceptions.

Bubble sort repeatedly exchanges adjacent out-of-order elements. Its worst-case running time is quadratic. Merge sort divides the input, sorts each half, and merges the ordered halves in linear time; the recurrence gives O(n log n) time and additional storage.

## Choosing an algorithm
Asymptotic complexity describes growth, not the complete cost of a small input. A policy can choose an algorithm based on collection size while the context remains independent of that decision.

## Exercise preparation
Write down invariants before implementation: every original value appears the same number of times, the result is ordered, and the method follows its documented mutation policy. Test each property with concrete examples.`,
            },
            {
                name: 'Normalization, uniqueness, and ownership',
                content: `# Normalization, uniqueness, and ownership

Data pipelines must define the order of transformations. Trimming titles before removing duplicates treats “Patterns” and “ Patterns ” as the same title. Removing duplicates first would miss this equivalence.

A set represents uniqueness, while a list represents an ordered sequence and may contain duplicates. A sorted set can combine two concerns, but its comparison rules also determine which values are considered equivalent. Always check that these rules match the requirements.

## Ownership
Mutating a caller-owned list can surprise the caller or fail when the input is unmodifiable. Build a new result when the contract promises no mutation. Validate nulls before doing work that could partially modify state.

## Cost
For n titles, normalization requires inspecting their characters and sorting typically costs O(n log n) comparisons. State both the input-size measure and the assumptions behind a complexity claim.`,
            },
        ],
    },
    {
        title: 'Software Testing',
        units: [
            {
                name: 'Partitions, boundaries, and useful assertions',
                content: `# Partitions, boundaries, and useful assertions

A test suite samples behavior from the input space. Partition that space by meaningful differences: empty versus non-empty, unique versus duplicate, valid versus invalid. Then examine boundaries where the behavior changes.

The sorting policy changes at ten entries. Tests with ten and eleven entries directly exercise that boundary. For catalog normalization, adjacent blanks expose an index-shift bug that one blank may not reveal.

## Assertions
Assert observable requirements, including returned values, preserved inputs, and documented exceptions. A test that merely executes a method says little about correctness. Use a small input with an obvious expected result so failures explain the broken contract.

## Review task
Turn every concrete finding in a code review into a regression test. Explain why that test fails before the correction and passes afterward.`,
            },
            {
                name: 'Test levels and controlled dependencies',
                content: `# Test levels and controlled dependencies

Unit tests isolate small contracts and run quickly. Integration tests exercise boundaries such as a transaction and its database constraints. End-to-end tests verify that a user can complete an important workflow through the application.

A concurrent reservation requirement cannot be proven by mocking away the database. Use an integration test with competing transactions and verify that only one reservation succeeds. Conversely, a payment failure can be made deterministic with a controlled provider response.

## Repeatability
Control clocks, randomness, and mutable fixtures. Tests should not depend on execution order or a live external service. Reset only data owned by the test and preserve unrelated state.

## Evidence and limits
Passing functional tests does not establish performance under peak load. A useful test plan states what has been demonstrated and which risks still require load tests, operational observation, or manual review.`,
            },
        ],
    },
];
