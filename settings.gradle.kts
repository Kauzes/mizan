rootProject.name = "mizan"

include(
    "common",
    "common-web",
    "common-test",
    "services:gateway",
    "services:identity-service",
    "services:ledger-service",
    "services:payment-service",
    "services:risk-service",
    "services:notification-service",
    "services:bank-simulator",
)
