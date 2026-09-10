/** The shapes this console reads. Only the fields it uses, so a new one cannot go unnoticed. */

export interface PaymentRow {
  readonly id: string;
  readonly amount: number;
  readonly currency: string;
  readonly status: string;
  readonly reference: string;
  readonly riskVerdict: string | null;
  readonly riskScore: number | null;
  readonly cardLastFour: string | null;
  readonly createdAt: string;
}

export interface Transition {
  readonly from: string | null;
  readonly to: string;
  readonly because: string | null;
  readonly at: string;
}

export interface Payment extends PaymentRow {
  readonly description: string | null;
  readonly acquirerReference: string | null;
  readonly declineReason: string | null;
  readonly ledgerEntryId: string | null;
  readonly riskReasons: string | null;
  readonly reviewRuling: string | null;
  readonly reviewRuledBy: string | null;
  readonly reviewRuledAt: string | null;
  readonly refundedAmount: number;
  readonly refundableAmount: number;
  readonly allowedNext: readonly string[];
  readonly updatedAt: string;
  readonly history: readonly Transition[];
}

export interface Refund {
  readonly id: string;
  readonly amount: number;
  readonly currency: string;
  readonly reference: string;
  readonly status: string;
  readonly reason: string | null;
  readonly ledgerEntryId: string | null;
  readonly createdAt: string;
}

export interface Posting {
  readonly accountCode: string;
  readonly amount: number;
  readonly currency: string;
  readonly direction: string;
}

export interface Entry {
  readonly id: string;
  readonly externalReference: string;
  readonly description: string;
  readonly occurredAt: string;
  readonly postings: readonly Posting[];
}

export interface Delivery {
  readonly id: string;
  readonly endpoint_id: string;
  readonly payment_id: string;
  readonly event_type: string;
  readonly status: string;
  readonly attempts: number;
  readonly last_status_code: number | null;
  readonly last_error: string | null;
  readonly delivered_at: string | null;
  readonly created_at: string;
}

export interface Attempt {
  readonly attempt: number;
  readonly at: string;
  readonly status_code: number | null;
  readonly duration_ms: number | null;
  readonly error: string | null;
}
