import type { APIRequestContext } from "@playwright/test";

/**
 * A merchant, and the part a merchant's own server plays.
 *
 * Taking a payment is not something the console does, and deliberately so: a card never
 * touches this application. A merchant's server creates the payment, presents the card and
 * captures it, and the console is where a person looks at what happened and gives money back.
 *
 * So the journey plays both parts. These calls are the merchant's server, made with real HTTP
 * against the real platform — not fixtures, not stubs, not rows inserted into a database. The
 * browser does everything a person would do.
 */
export interface Merchant {
  readonly id: string;
  readonly email: string;
  readonly password: string;
  readonly token: string;
}

const CARD = "4000000000000000";

export async function aMerchant(api: APIRequestContext): Promise<Merchant> {
  const run = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const email = `browser-${run}@mizan.local`;
  const password = "correct-horse-battery-staple";

  const registered = await api.post("/api/v1/merchants", {
    headers: { "Idempotency-Key": crypto.randomUUID() },
    data: {
      merchantName: `Browser Journey ${run}`,
      fullName: "Ada Lovelace",
      email,
      password,
    },
  });
  expectOk(registered.status(), 201, "registering a merchant");
  const { merchant } = (await registered.json()) as { merchant: { id: string } };

  const signedIn = await api.post("/api/v1/tokens", { data: { email, password } });
  expectOk(signedIn.status(), 200, "signing in");
  const { accessToken } = (await signedIn.json()) as { accessToken: string };

  // The account the money will be owed into. The ledger opens none on anybody's behalf, so
  // this is a step a real merchant takes too.
  const opened = await api.post(`/api/v1/merchants/${merchant.id}/accounts`, {
    headers: { Authorization: `Bearer ${accessToken}`, "Idempotency-Key": crypto.randomUUID() },
    data: {
      code: "settlement.try",
      name: "Owed to the merchant, TRY",
      type: "LIABILITY",
      currency: "TRY",
    },
  });
  expectOk(opened.status(), 201, "opening a settlement account");

  return { id: merchant.id, email, password, token: accessToken };
}

/** A payment taken and captured, the way a merchant's own server would. */
export async function aCapturedPayment(
  api: APIRequestContext,
  merchant: Merchant,
  amount: number,
): Promise<{ id: string; reference: string }> {
  const reference = `order-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const headers = {
    Authorization: `Bearer ${merchant.token}`,
    "Idempotency-Key": crypto.randomUUID(),
  };

  const created = await api.post(`/api/v1/merchants/${merchant.id}/payments`, {
    headers,
    data: { amount, currency: "TRY", reference },
  });
  expectOk(created.status(), 201, "creating a payment");
  const { id } = (await created.json()) as { id: string };

  const authorized = await api.post(
    `/api/v1/merchants/${merchant.id}/payments/${id}/authorize`,
    { headers: { ...headers, "Idempotency-Key": crypto.randomUUID() }, data: { card: CARD } },
  );
  expectOk(authorized.status(), 200, "authorizing a payment");

  const captured = await api.post(`/api/v1/merchants/${merchant.id}/payments/${id}/capture`, {
    headers: { ...headers, "Idempotency-Key": crypto.randomUUID() },
    data: {},
  });
  expectOk(captured.status(), 200, "capturing a payment");

  return { id, reference };
}

function expectOk(got: number, wanted: number, what: string): void {
  if (got !== wanted) {
    throw new Error(`${what} answered ${got}, expected ${wanted}`);
  }
}
