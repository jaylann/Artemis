## Design decision: lending rules without a subclass explosion

The circulation service began with two classes, `PrintedBook` and `EBook`. Three releases later, it contains `ShortLoanPrintedBook`, `StaffShortLoanPrintedBook`, and `RenewableEBook`. Each new policy duplicates validation and overdue-date calculations.

The next release introduces rules that vary independently:

| Dimension       | Examples                                    |
| --------------- | ------------------------------------------- |
| Member category | Undergraduate, postgraduate, staff          |
| Loan category   | Standard loan, seven-day course reserve     |
| Delivery format | Physical copy, time-limited digital license |

The librarian also needs policy changes to apply to **new loans only**. A loan already issued for 28 days must keep its agreed due date after the default changes to 21 days.

## Your task

Write a **600–800 word engineering decision note** proposing an incremental redesign. Address an audience of developers who must implement and maintain the change next semester.

### 1. Diagnose the problem · 3 points

Explain which dimensions are independent and show two combinations that the current hierarchy handles poorly. Identify duplicated responsibilities and a concrete risk to existing behavior. Naming a design principle without applying it to this system is insufficient.

### 2. Propose and illustrate the design · 4 points

Define the responsibilities of the book metadata, physical inventory or digital access, lending policy, and recorded loan. Include a small Java-like pseudocode example of policy selection and checkout.

```text
checkout(member, item, checkoutTime)
  → select an applicable policy
  → check eligibility and calculate terms
  → record the agreed terms on a new loan
```

Show how two policies can be tested through the same contract. Explain where a real “is a” relationship still justifies inheritance, if any. Your design must preserve the due date and the policy version agreed at checkout.

### 3. Defend the tradeoff and migration · 3 points

Compare your design with retaining the inheritance hierarchy and with a single conditional-based policy service. Discuss at least one cost of your preferred option, such as indirection or configuration complexity.

Describe a migration in two or three deployable steps. Include characterization tests for existing rules and a compatibility path for callers of the old classes. Avoid a proposal that requires rewriting all clients before the next release can ship.

## Submission format

Use the headings **Problem**, **Decision**, **Alternatives**, and **Migration** in your text submission. Include one pseudocode block and one concrete loan example. State assumptions explicitly; no external research is required. The word count excludes code.

## Final check

Can a staff member borrow a seven-day printed reserve without introducing another book subclass? Can an old 28-day loan retain its terms after the policy changes? Your note should answer both questions unambiguously.
