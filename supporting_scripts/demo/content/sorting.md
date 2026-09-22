## Scenario: the morning pickup queue

At 08:00, the university library prepares books that readers have reserved. A legacy service exports pickup deadlines as `java.util.Date` values. A small branch usually has fewer than ten reservations; the central library processes several hundred. The desk application needs these dates in ascending order before it prints the work list.

Your task is to implement a **replaceable sorting strategy** inside the supplied Java project. The size-based selection rule is a teaching constraint, not a claim that bubble sort is the best production choice.

## Learning goals

- Separate an algorithm's implementation from the code that chooses it.
- Implement and compare a quadratic sort and a divide-and-conquer sort.
- Turn ordering, input preservation, and boundary rules into tests.

## 1. Establish the strategy contract

Use package `edu.demo` and retain the class names expected by the supplied tests.

```java
public interface SortStrategy {
    void performSort(List<Date> input);
}
```

Both `BubbleSort` and `MergeSort` implement this interface. Sort the **supplied list in place**, from earliest to latest. Keep every occurrence of every date: two reservations with the same deadline must remain two entries. Empty lists, a single entry, and already sorted input are valid. Assume a non-null, mutable list containing non-null dates.

## 2. Implement the algorithms

1. **Bubble sort:** compare adjacent dates and exchange out-of-order pairs. Stop when a full pass makes no changes.
2. **Merge sort:** split the input, sort the parts recursively, and merge them back. Keep the base case explicit.
3. Do not use `List.sort`, `Collections.sort`, `Arrays.sort`, or `Stream.sorted` inside either algorithm.

### Worked example

```text
Input:  [2026-10-08, 2026-10-06, 2026-10-08, 2026-10-05]
Output: [2026-10-05, 2026-10-06, 2026-10-08, 2026-10-08]
```

Dates here are illustrative. The implementation must also compare different times on the same day; do not compare formatted date strings.

## 3. Wire the context and selection policy

`Context` holds the dates and a `SortStrategy`. Its `sort()` method delegates to the configured strategy. Preserve `getDates`, `setDates`, `getSortAlgorithm`, and `setSortAlgorithm`.

`Policy(Context context).configure()` selects a strategy using the current list size:

| Number of dates | Required strategy |
| --------------- | ----------------- |
| 0 through 10    | `BubbleSort`      |
| 11 or more      | `MergeSort`       |

Complete `Client` to demonstrate configuring and sorting a queue. Keep selection logic in `Policy`, rather than duplicating it in the algorithms.

## Submission and self-check

Submit the Java implementation through the exercise repository. Before submitting, check **0, 1, 10, and 11 entries**, repeated deadlines, reverse order, and times on the same date. Include a short code comment comparing worst-case time and auxiliary space. The supplied automated tests check behavior and the strategy structure; passing only the happy-path example is insufficient.

## Assessment focus · 20 points

| Criterion          | Evidence checked                                                      |
| ------------------ | --------------------------------------------------------------------- |
| Sorting behavior   | Correct ordering and preservation of duplicate dates                  |
| Strategy structure | Interface, concrete implementations, and delegation through `Context` |
| Selection policy   | Required strategies on both sides of the 10/11 boundary               |

The recorded score is the weighted proportion of passing automated tests, scaled to 20 points. The groups above describe coverage rather than separate manual point allocations. Complexity comments are discussed during the exercise review.
