## Scenario: replace the circulation spreadsheet

The university library currently tracks loans in one spreadsheet row per checkout. Repeated book metadata and overwritten return dates make it difficult to answer two questions: **which physical copy is available now**, and **who borrowed it previously**?

Design the domain model for the new circulation service as a **UML class diagram** in the modeling editor. Model the lending domain, not screens, database tables, or HTTP controllers.

## Requirements from the librarian

| ID  | Requirement                                                                                                                                                      |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| R1  | A `Book` describes one edition: ISBN, title, and one or more author names.                                                                                       |
| R2  | A `BookCopy` identifies one physical item by a unique inventory number and shelf location. Each copy belongs to exactly one book; a book may have no copies yet. |
| R3  | A `Member` has a membership number, name, and account status. Suspended members cannot start a new loan.                                                         |
| R4  | A `Loan` records one member borrowing one copy, with checkout date, due date, and an optional return date. Returned loans remain in the system.                  |
| R5  | A copy has at most one active loan. A member may have at most five active loans. A loan is active precisely when its return date is absent.                      |
| R6  | Returning a copy closes the active loan; it does not delete its history or change the book's descriptive metadata.                                               |

For this exercise, multiple editions are separate `Book` objects. Fines, reservations, digital licenses, and interlibrary lending are outside the model.

## 1. Model the concepts and relationships · 4 points

Include `Book`, `BookCopy`, `Member`, and `Loan`. Add typed attributes and meaningful operations such as checking availability or closing a loan. You may introduce an enumeration for account status. Do not store borrower and due-date fields directly on `Book`.

## 2. Make cardinalities explicit · 4 points

Show role names and multiplicities at both ends of each association. Your model must retain **many historical loans for the same copy** while linking every individual loan to exactly one member and one copy.

Choose aggregation, composition, or an ordinary association deliberately. A composition diamond asserts lifecycle ownership; it is not a general decoration for “has a.”

## 3. Express rules the diagram alone cannot enforce · 2 points

Add UML notes for at least two invariants, including the one-active-loan-per-copy rule. Explain in the notes why historical multiplicity cannot be `0..1`. Indicate where an atomic checkout operation must check availability and create the new loan together.

### Walk through this example

```text
Book: B-17, "Designing Data-Intensive Applications"
Copies: C-101 and C-102
Member: M-42, active
L-1: M-42 borrowed C-101 on 01 October and returned it on 08 October
L-2: M-42 borrowed C-101 on 12 October; no return date yet
```

Both loans must remain representable. `C-101` is unavailable, while `C-102` can still be borrowed. A second active loan for `C-101` must violate an explicit rule.

## Submission checklist

- Submit one readable class diagram in Artemis, including your invariant notes.
- Check that every association has multiplicities and every date has a clear meaning.
- Verify the example above without inventing extra copies or deleting a loan.
- Favor a small, consistent model over adding unrelated classes.
