## Scenario: reconciling supplier catalogs

The university library receives title lists from two book suppliers. The same title may appear several times, sometimes padded with spaces. An import preview must show a deterministic list before librarians approve the records.

Implement the **title-normalization stage** in `edu.demo.CollectionProcessor`. This stage is deliberately narrow: it does not resolve ISBNs, merge editions, or guess whether differently capitalized titles describe the same book.

## API to implement

```java
public static List<String> uniqueSortedTitles(List<String> titles)
public static int totalCharacters(List<String> titles)
```

## 1. Normalize, then deduplicate

Apply the following rules in order:

1. Reject a null list or any null element with `NullPointerException`.
2. Remove leading and trailing whitespace using Java's `String.trim()` semantics.
3. Discard values that are empty after trimming.
4. Remove exact duplicates from the normalized values. Comparison is **case-sensitive**.
5. Return a new list in the natural order defined by `String.compareTo`.

Do not change the caller's list. An immutable input such as `List.of(...)` must work. Unicode normalization, locale-aware collation, and removal of whitespace inside titles are outside this exercise's contract.

### Acceptance examples

| Input                                            | Expected result              |
| ------------------------------------------------ | ---------------------------- |
| `["  Patterns ", "Algorithms", "Patterns", " "]` | `["Algorithms", "Patterns"]` |
| `["java", "Java", " Java "]`                     | `["Java", "java"]`           |
| `["", "   "]`                                    | `[]`                         |
| `[]`                                             | `[]`                         |
| `["Algorithms", null]`                           | `NullPointerException`       |

## 2. Calculate the preview size

`totalCharacters` returns the sum of `String.length()` for the **normalized, unique** titles. Reuse the normalization operation so the two methods cannot silently implement different rules.

```text
Input:  ["  Patterns ", "Algorithms", "Patterns", " "]
Titles: ["Algorithms", "Patterns"]
Total:  10 + 8 = 18
```

The result counts Java UTF-16 code units, not bytes or user-perceived characters. Assume the sum fits in an `int`.

## 3. Justify the collection choice

In a short code comment, explain which collection enforces uniqueness, where ordering happens, and why the input remains unchanged. Either a sorted set or a separate set-and-sort pipeline can satisfy the contract. Standard Java collection and sorting operations are allowed here.

## Submission and self-check

Submit the implementation through the exercise repository. Test normalization before deduplication, adjacent blank entries, case-sensitive ordering, an immutable input, empty input, and both null cases. Check the input again after the call; returning the right titles while modifying the original list is still incorrect.

## Assessment focus · 10 points

| Criterion                   | Evidence checked                                            |
| --------------------------- | ----------------------------------------------------------- |
| Normalization               | Trimming, filtering, and deduplication in the correct order |
| Ordering and ownership      | Deterministic output with unchanged caller-owned input      |
| Aggregate and invalid input | Character total, empty lists, and null rejection            |

The recorded score is the weighted proportion of passing automated tests, scaled to 10 points. The groups above describe coverage rather than separate manual point allocations. Your implementation must satisfy the full contract, including cases beyond the examples above.
