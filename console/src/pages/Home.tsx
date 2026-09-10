import { useSession } from "../session/SessionProvider";

/**
 * Where a signed in merchant lands, until MIZ-67 puts a dashboard here.
 *
 * It says what this console can currently do rather than showing an empty frame around
 * nothing. A page that promises panels it does not have yet is a worse first impression than
 * one that is honest about being early.
 */
export function Home() {
  const { caller, can } = useSession();

  const abilities: Array<{ permission: string; description: string }> = [
    { permission: "PAYMENT_READ", description: "Read this merchant's payments" },
    { permission: "PAYMENT_WRITE", description: "Take and refund payments" },
    { permission: "ENTRY_READ", description: "Read the books" },
    { permission: "REVIEW_RULE", description: "Rule on payments the platform held" },
    { permission: "WEBHOOK_MANAGE", description: "Manage webhook endpoints" },
    { permission: "USER_MANAGE", description: "Add people and change what they may do" },
  ];

  return (
    <main>
      <h1>Signed in</h1>
      <p className="muted">
        Merchant <code>{caller?.merchantId}</code>
      </p>

      <h2>What this account may do</h2>
      <ul className="abilities">
        {abilities.map((ability) => (
          <li key={ability.permission} className={can(ability.permission) ? "yes" : "no"}>
            <span aria-hidden="true">{can(ability.permission) ? "✓" : "·"}</span>
            <span>{ability.description}</span>
            <span className="sr-only">
              {can(ability.permission) ? "allowed" : "not allowed"}
            </span>
          </li>
        ))}
      </ul>

      <p className="muted">
        The pages behind these arrive one story at a time: payments, the review queue, the
        books, and settings.
      </p>
    </main>
  );
}
