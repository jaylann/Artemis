## Scenario: an order succeeds only when stock and payment agree

The campus bookstore accepts online orders for course books. Its checkout spans three separate systems: inventory, a payment provider, and fulfillment. These systems do not share a database transaction.

Create a **UML activity diagram** for processing one order. Concentrate on decisions, waiting, and compensation; you do not need to model individual network calls.

## Agreed business rules

1. Validate the delivery address before touching stock or payment. An invalid address rejects the order and notifies the customer.
2. Attempt an atomic reservation for all order lines. Partial reservations are not permitted.
3. If stock is unavailable, offer a backorder. A declined offer ends the order. An accepted offer waits for replenishment, then retries reservation; acceptance alone does not authorize shipping.
4. After reservation succeeds, request payment authorization. A decline releases the reservation and notifies the customer.
5. A payment timeout is an **unknown outcome**, not a decline. Reconcile the original authorization using the same request identifier. Do not issue a second independent charge. Remain pending until the result is known.
6. Once payment is authorized, create the fulfillment job and send the confirmation. A failed notification is retried without creating a second fulfillment job.

Assume reservations remain valid during reconciliation. Cancellation after dispatch, partial shipment, and payment capture are outside this workflow.

## 1. Draw the successful path · 4 points

Use an initial node, clearly named actions, and explicit completion nodes. Include address validation, reservation, authorization, fulfillment creation, and confirmation. The successful path must prove that a shipment cannot be prepared without both reserved stock and authorized payment.

## 2. Model alternatives and compensation · 4 points

Use decisions with mutually exclusive guards such as `[valid]` and `[invalid]`. Distinguish **merge nodes**, which combine alternatives, from **join nodes**, which wait for concurrent branches.

Show the unavailable-stock loop, declined payment cleanup, and pending authorization reconciliation. A notification such as “payment failed” must not be sent merely because the provider timed out.

## 3. Identify safe concurrency · 2 points

After authorization succeeds, fulfillment-job creation and confirmation delivery may proceed independently. Use a fork and join if you model them concurrently, and show the notification retry path. Add a note that retries require stable operation identifiers.

### Trace these cases through your diagram

| Case                                                 | Expected outcome                                |
| ---------------------------------------------------- | ----------------------------------------------- |
| Invalid address                                      | No stock reservation and no payment request     |
| Unavailable stock; backorder declined                | Rejected order, no fulfillment                  |
| Reserved stock; payment declined                     | Reservation released, customer notified         |
| Authorization times out, then reconciles as approved | Exactly one authorization, fulfillment proceeds |
| Confirmation delivery fails after approval           | Retry notification, preserve the approved order |

## Submission checklist

Submit one activity diagram with concise notes. Every decision needs guards, every failure path needs an outcome, and every retry needs a clear return point. Avoid a single “handle error” action that hides the required business behavior.
