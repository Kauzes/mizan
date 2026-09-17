# Taking a payment, and giving part of it back

Every request and response below was made against a running platform by
[`scripts/worked-example.sh`](../../scripts/worked-example.sh), which writes this file. CI runs the same
script with `--check` against the stack it starts, so this document cannot drift from the API: if a field
is renamed and this is not regenerated, the build fails.

Ids, timestamps and tokens are replaced with fixed placeholders so that two runs produce the same
document. Everything else is exactly what went over the wire.

Everything goes through the gateway on `http://localhost:8080`. Nothing here touches a service directly,
because nothing outside the platform can.

## 1. A merchant registers

Registering is the one write that needs no token. It creates the merchant, the owner's user and the
owner's role in one request.

**Every write takes an `Idempotency-Key`.** It is a key you choose, and it is how you ask again
safely: the same key with the same body is answered with what was already done rather than doing it
twice. Use a fresh one per operation, keep it as long as you might retry, and never reuse one for a
different request — that is refused, and section 6 shows what that looks like.

```http
POST /api/v1/merchants HTTP/1.1
Content-Type: application/json
Idempotency-Key: register-a-merchant-1

{
  "merchantName": "Worked Example Co",
  "fullName": "Ada Lovelace",
  "email": "ada@worked-example.test",
  "password": "correct-horse-battery-staple"
}
```

```http
HTTP/1.1 201
location: /api/v1/merchants/11111111-1111-1111-1111-111111111111
Content-Type: application/json
x-correlation-id: 0123456789abcdef0123456789abcdef

{
  "merchant": {
    "id": "11111111-1111-1111-1111-111111111111",
    "name": "Worked Example Co",
    "createdAt": "2026-01-01T00:00:00Z"
  },
  "owner": {
    "id": "33333333-3333-3333-3333-333333333333",
    "merchantId": "11111111-1111-1111-1111-111111111111",
    "email": "ada@worked-example.test",
    "fullName": "Ada Lovelace",
    "roles": [
      "OWNER"
    ],
    "createdAt": "2026-01-01T00:00:00Z"
  }
}
```

## 2. Signing in

An access token lasts fifteen minutes and a refresh token thirty days. Send the access token as a
bearer token on every request below. Refresh tokens are single use: presenting a spent one revokes
the whole sign in, because that is what a stolen token looks like.

```http
POST /api/v1/tokens HTTP/1.1
Content-Type: application/json

{
  "email": "ada@worked-example.test",
  "password": "correct-horse-battery-staple"
}
```

```http
HTTP/1.1 200
Content-Type: application/json
x-correlation-id: 0123456789abcdef0123456789abcdef

{
  "accessToken": "<a signed token>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "<a signed token>",
  "refreshExpiresIn": 2592000
}
```

## 3. Opening the account the money is owed into

The ledger opens nothing on anybody's behalf. Until this account exists, a capture has nowhere to
credit and is refused — which is a better failure than money landing in an account nobody chose.

```http
POST /api/v1/merchants/11111111-1111-1111-1111-111111111111/accounts HTTP/1.1
Content-Type: application/json
Authorization: Bearer <an access token>
Idempotency-Key: open-settlement-1

{
  "code": "settlement.try",
  "name": "Owed to the merchant, TRY",
  "type": "LIABILITY",
  "currency": "TRY"
}
```

```http
HTTP/1.1 201
location: /api/v1/merchants/11111111-1111-1111-1111-111111111111/accounts/33333333-3333-3333-3333-333333333333
Content-Type: application/json
x-correlation-id: 0123456789abcdef0123456789abcdef

{
  "id": "33333333-3333-3333-3333-333333333333",
  "merchantId": "11111111-1111-1111-1111-111111111111",
  "code": "settlement.try",
  "name": "Owed to the merchant, TRY",
  "type": "LIABILITY",
  "normalSide": "CREDIT",
  "currency": "TRY",
  "balance": 0,
  "createdAt": "2026-01-01T00:00:00Z"
}
```

## 4. Creating a payment

A payment is created before any card is involved. `reference` is yours, it identifies one payment
for this merchant, and reusing it is refused: that is how a retry that lost its answer cannot become
a second payment.

Amounts are minor units and an ISO 4217 code. 125000 TRY is 1,250.00 lira. There is no floating
point anywhere near money on this platform.

```http
POST /api/v1/merchants/11111111-1111-1111-1111-111111111111/payments HTTP/1.1
Content-Type: application/json
Authorization: Bearer <an access token>
Idempotency-Key: create-payment-1

{
  "amount": 125000,
  "currency": "TRY",
  "reference": "order-1",
  "description": "Two tickets"
}
```

```http
HTTP/1.1 201
location: /api/v1/merchants/11111111-1111-1111-1111-111111111111/payments/22222222-2222-2222-2222-222222222222
Content-Type: application/json
x-correlation-id: 0123456789abcdef0123456789abcdef

{
  "id": "22222222-2222-2222-2222-222222222222",
  "merchantId": "11111111-1111-1111-1111-111111111111",
  "amount": 125000,
  "currency": "TRY",
  "status": "CREATED",
  "allowedNext": [
    "AUTHORIZATION_UNKNOWN",
    "HELD_FOR_REVIEW",
    "AUTHORIZED",
    "DECLINED"
  ],
  "reference": "order-1",
  "description": "Two tickets",
  "acquirerReference": null,
  "cardLastFour": null,
  "declineReason": null,
  "ledgerEntryId": null,
  "riskVerdict": null,
  "riskScore": null,
  "riskReasons": null,
  "reviewRuling": null,
  "reviewRuledBy": null,
  "reviewRuledAt": null,
  "refundedAmount": 0,
  "refundableAmount": 0,
  "createdAt": "2026-01-01T00:00:00Z",
  "updatedAt": "2026-01-01T00:00:00Z",
  "history": [
    {
      "from": null,
      "to": "CREATED",
      "reason": null,
      "at": "2026-01-01T00:00:00Z",
      "traceId": "0123456789abcdef0123456789abcdef"
    }
  ]
}
```

## 5. Authorizing the card

This reserves the money with the acquirer and posts nothing to the books: an authorization is a
promise, not a movement. `allowedNext` says what may happen to the payment from here.

What the acquirer does is decided by the last four digits of the card, so every outcome can be
provoked without the platform knowing it is talking to a simulator. `0002` declines for
insufficient funds, `0069` approves but withholds the answer for longer than the caller waits, and
anything else approves.

```http
POST /api/v1/merchants/11111111-1111-1111-1111-111111111111/payments/22222222-2222-2222-2222-222222222222/authorize HTTP/1.1
Content-Type: application/json
Authorization: Bearer <an access token>
Idempotency-Key: authorize-payment-1

{
  "card": "4000000000000000"
}
```

```http
HTTP/1.1 200
Content-Type: application/json
x-correlation-id: 0123456789abcdef0123456789abcdef

{
  "id": "22222222-2222-2222-2222-222222222222",
  "merchantId": "11111111-1111-1111-1111-111111111111",
  "amount": 125000,
  "currency": "TRY",
  "status": "AUTHORIZED",
  "allowedNext": [
    "CAPTURED",
    "VOIDED"
  ],
  "reference": "order-1",
  "description": "Two tickets",
  "acquirerReference": "auth_000000000000",
  "cardLastFour": "0000",
  "declineReason": null,
  "ledgerEntryId": null,
  "riskVerdict": "APPROVE",
  "riskScore": 0,
  "riskReasons": "",
  "reviewRuling": null,
  "reviewRuledBy": null,
  "reviewRuledAt": null,
  "refundedAmount": 0,
  "refundableAmount": 0,
  "createdAt": "2026-01-01T00:00:00Z",
  "updatedAt": "2026-01-01T00:00:00Z",
  "history": [
    {
      "from": null,
      "to": "CREATED",
      "reason": null,
      "at": "2026-01-01T00:00:00Z",
      "traceId": "0123456789abcdef0123456789abcdef"
    },
    {
      "from": "CREATED",
      "to": "AUTHORIZED",
      "reason": null,
      "at": "2026-01-01T00:00:00Z",
      "traceId": "0123456789abcdef0123456789abcdef"
    }
  ]
}
```

## 6. The same key, a different request

Reusing an idempotency key for a request that is not the one it belongs to is refused with 409 and
`IDEMPOTENCY_KEY_REUSED`. Nothing is done. This is the check that makes a key safe to retry with:
a key can only ever mean one request.

```http
POST /api/v1/merchants/11111111-1111-1111-1111-111111111111/payments HTTP/1.1
Content-Type: application/json
Authorization: Bearer <an access token>
Idempotency-Key: create-payment-1

{
  "amount": 999,
  "currency": "TRY",
  "reference": "another-1"
}
```

```http
HTTP/1.1 409
Content-Type: application/problem+json
x-correlation-id: 0123456789abcdef0123456789abcdef

{
  "detail": "Idempotency-Key create-payment-1 was already used for a different request.",
  "instance": "/api/v1/merchants/11111111-1111-1111-1111-111111111111/payments",
  "status": 409,
  "title": "conflict",
  "type": "https://mizan.kauzes.dev/errors/conflict",
  "code": "CONFLICT",
  "correlationId": "33333333-3333-3333-3333-333333333333",
  "timestamp": "2026-01-01T00:00:00Z"
}
```

## 7. Capturing

Capturing takes the money and writes the entry. This is the movement: the books gain a balanced
journal entry, and `ledgerEntryId` on the payment names it.

```http
POST /api/v1/merchants/11111111-1111-1111-1111-111111111111/payments/22222222-2222-2222-2222-222222222222/capture HTTP/1.1
Authorization: Bearer <an access token>
Idempotency-Key: capture-payment-1
```

```http
HTTP/1.1 200
Content-Type: application/json
x-correlation-id: 0123456789abcdef0123456789abcdef

{
  "id": "22222222-2222-2222-2222-222222222222",
  "merchantId": "11111111-1111-1111-1111-111111111111",
  "amount": 125000,
  "currency": "TRY",
  "status": "CAPTURED",
  "allowedNext": [],
  "reference": "order-1",
  "description": "Two tickets",
  "acquirerReference": "auth_000000000000",
  "cardLastFour": "0000",
  "declineReason": null,
  "ledgerEntryId": "33333333-3333-3333-3333-333333333333",
  "riskVerdict": "APPROVE",
  "riskScore": 0,
  "riskReasons": "",
  "reviewRuling": null,
  "reviewRuledBy": null,
  "reviewRuledAt": null,
  "refundedAmount": 0,
  "refundableAmount": 125000,
  "createdAt": "2026-01-01T00:00:00Z",
  "updatedAt": "2026-01-01T00:00:00Z",
  "history": [
    {
      "from": null,
      "to": "CREATED",
      "reason": null,
      "at": "2026-01-01T00:00:00Z",
      "traceId": "0123456789abcdef0123456789abcdef"
    },
    {
      "from": "CREATED",
      "to": "AUTHORIZED",
      "reason": null,
      "at": "2026-01-01T00:00:00Z",
      "traceId": "0123456789abcdef0123456789abcdef"
    },
    {
      "from": "AUTHORIZED",
      "to": "CAPTURED",
      "reason": null,
      "at": "2026-01-01T00:00:00Z",
      "traceId": "0123456789abcdef0123456789abcdef"
    }
  ]
}
```

## 8. What an error looks like

Every failure is an RFC 9457 problem detail with a stable `code`, a sentence a person can act on,
and the correlation id of the request. Capturing a payment that is already captured is not a
transport failure and does not pretend to be one.

```http
POST /api/v1/merchants/11111111-1111-1111-1111-111111111111/payments/22222222-2222-2222-2222-222222222222/capture HTTP/1.1
Authorization: Bearer <an access token>
Idempotency-Key: capture-again-1
```

```http
HTTP/1.1 422
Content-Type: application/problem+json
x-correlation-id: 0123456789abcdef0123456789abcdef

{
  "detail": "A payment that is CAPTURED cannot be captured. That is where this payment ends.",
  "instance": "/api/v1/merchants/11111111-1111-1111-1111-111111111111/payments/22222222-2222-2222-2222-222222222222/capture",
  "status": 422,
  "title": "unprocessable",
  "type": "https://mizan.kauzes.dev/errors/unprocessable",
  "code": "UNPROCESSABLE",
  "correlationId": "33333333-3333-3333-3333-333333333333",
  "timestamp": "2026-01-01T00:00:00Z"
}
```

## 9. Refunding part of it

A refund is a new movement that names the capture it reverses, never an edit of it. The payment
keeps `refundedAmount` and `refundableAmount` so you never have to work out what is left.

```http
POST /api/v1/merchants/11111111-1111-1111-1111-111111111111/payments/22222222-2222-2222-2222-222222222222/refunds HTTP/1.1
Content-Type: application/json
Authorization: Bearer <an access token>
Idempotency-Key: refund-payment-1

{
  "amount": 25000,
  "currency": "TRY",
  "reason": "one ticket returned"
}
```

```http
HTTP/1.1 400
Content-Type: application/problem+json
x-correlation-id: 0123456789abcdef0123456789abcdef

{
  "detail": "The request failed validation.",
  "instance": "/api/v1/merchants/11111111-1111-1111-1111-111111111111/payments/22222222-2222-2222-2222-222222222222/refunds",
  "status": 400,
  "title": "validation-failed",
  "type": "https://mizan.kauzes.dev/errors/validation-failed",
  "code": "VALIDATION_FAILED",
  "correlationId": "33333333-3333-3333-3333-333333333333",
  "timestamp": "2026-01-01T00:00:00Z",
  "errors": [
    {
      "field": "reference",
      "message": "must not be blank"
    }
  ]
}
```

## 10. The payment, afterwards

Reading it back shows the history: every state it was in, when, and why. Deriving that from the
current status is not possible, so it is recorded rather than reconstructed.

```http
GET /api/v1/merchants/11111111-1111-1111-1111-111111111111/payments/22222222-2222-2222-2222-222222222222 HTTP/1.1
Authorization: Bearer <an access token>
```

```http
HTTP/1.1 200
Content-Type: application/json
x-correlation-id: 0123456789abcdef0123456789abcdef

{
  "id": "22222222-2222-2222-2222-222222222222",
  "merchantId": "11111111-1111-1111-1111-111111111111",
  "amount": 125000,
  "currency": "TRY",
  "status": "CAPTURED",
  "allowedNext": [],
  "reference": "order-1",
  "description": "Two tickets",
  "acquirerReference": "auth_000000000000",
  "cardLastFour": "0000",
  "declineReason": null,
  "ledgerEntryId": "33333333-3333-3333-3333-333333333333",
  "riskVerdict": "APPROVE",
  "riskScore": 0,
  "riskReasons": "",
  "reviewRuling": null,
  "reviewRuledBy": null,
  "reviewRuledAt": null,
  "refundedAmount": 0,
  "refundableAmount": 125000,
  "createdAt": "2026-01-01T00:00:00Z",
  "updatedAt": "2026-01-01T00:00:00Z",
  "history": [
    {
      "from": null,
      "to": "CREATED",
      "reason": null,
      "at": "2026-01-01T00:00:00Z",
      "traceId": "0123456789abcdef0123456789abcdef"
    },
    {
      "from": "CREATED",
      "to": "AUTHORIZED",
      "reason": null,
      "at": "2026-01-01T00:00:00Z",
      "traceId": "0123456789abcdef0123456789abcdef"
    },
    {
      "from": "AUTHORIZED",
      "to": "CAPTURED",
      "reason": null,
      "at": "2026-01-01T00:00:00Z",
      "traceId": "0123456789abcdef0123456789abcdef"
    }
  ]
}
```

## Rate limiting

Each merchant gets 100 requests a second with a burst of 200, counted in Redis across every gateway
instance. Over that, the answer is 429 with `RATE_LIMITED` and a `Retry-After` in seconds:

```json
{
  "type": "https://mizan.dev/problems/rate-limited",
  "title": "Too Many Requests",
  "status": 429,
  "code": "RATE_LIMITED",
  "detail": "This merchant is sending more than 100 requests a second. Nothing was done. Wait the number of seconds in Retry-After and send it again.",
  "correlationId": "0123456789abcdef0123456789abcdef"
}
```

**Nothing was done** is the part to build on: a rate limited request never reached a service, so
sending it again after `Retry-After` is safe, and sending it again with the same idempotency key is
safe whether or not it was ever done. This is not provoked in this document, because a hundred a
second against a laptop proves nothing about a deployment; the limit itself is tested in
`MerchantRateLimitTest` and measured under load in [docs/performance](../performance).

## What to read next

- The full API: every service's OpenAPI specification is in [docs/api](.), and the stack serves
  <http://localhost:8080/swagger-ui.html> with all of them in one place.
- Why it works this way: the decisions are indexed in [docs/adr](../adr/README.md).
