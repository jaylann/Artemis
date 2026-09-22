## Pull request review: a catalog import that loses records

A teammate proposes the following implementation for the supplier-import preview. The method passes a test with `['Algorithms', 'Patterns']`, but librarians report missing entries and inconsistent behavior when the preview is opened twice.

```java
List<String> normalize(List<String> titles) {
    for (int i = 0; i < titles.size(); i++) {
        if (titles.get(i).isBlank()) {
            titles.remove(i);
        } else {
            titles.set(i, titles.get(i).trim());
        }
    }
    Collections.sort(titles);
    return titles;
}
```

## The agreed contract

The output must contain trimmed, non-empty, unique titles in natural, case-sensitive order. Trimming follows `String.trim()` semantics. The method must not mutate its input and must accept an unmodifiable list. A null list or a null element must produce `NullPointerException` without changing caller-owned state.

## 1. Report reproducible findings · 5 points

Identify **at least four distinct correctness or API-contract issues**. For each, give:

- a short, constructive review comment;
- a concrete Java input, including its collection type where relevant;
- the actual behavior of this implementation;
- the required behavior and why the difference matters.

Use a table or one clearly labeled subsection per finding. Do not count the same mutation problem four times under different names. Trace an input with adjacent blank entries through the loop to explain the index-shift defect.

## 2. Propose the correction · 3 points

Provide Java code or precise pseudocode for a replacement. Separate input validation, normalization, uniqueness, and ordering. Explain why the replacement works with `List.of(...)` and cannot leave the original list partly modified when it encounters a null element.

A sorted set and a set followed by sorting are both acceptable. Changing case, merging editions, or silently dropping nulls would change the contract and requires a separate product decision.

## 3. Specify regression tests · 2 points

Provide at least four tests with **exact inputs and expected assertions**, not just names such as “test edge cases.” Cover the index-shift defect, duplicates introduced by trimming, input ownership, and invalid input.

Example of the expected level of precision:

```java
var input = new ArrayList<>(List.of(" B ", "A"));
assertEquals(List.of("A", "B"), normalize(input));
assertEquals(List.of(" B ", "A"), input);
```

## Submission format

Submit a **500–700 word review** using **Findings**, **Proposed correction**, and **Regression tests**. Code and tables do not count toward the limit. Prioritize observable defects over optional naming or formatting preferences. Explain the behavior of the code that is present, rather than listing hypothetical problems in unrelated systems.
