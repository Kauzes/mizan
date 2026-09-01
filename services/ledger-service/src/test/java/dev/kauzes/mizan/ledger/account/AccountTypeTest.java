package dev.kauzes.mizan.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The sign convention, which is the one thing in a ledger that everything else assumes and
 * nothing else states.
 */
class AccountTypeTest {

    @Test
    void assetsAndExpensesGrowOnTheDebitSide() {
        assertThat(AccountType.ASSET.increasesOnDebit()).isTrue();
        assertThat(AccountType.EXPENSE.increasesOnDebit()).isTrue();
    }

    @Test
    void whatIsOwedOrEarnedGrowsOnTheCreditSide() {
        assertThat(AccountType.LIABILITY.increasesOnDebit()).isFalse();
        assertThat(AccountType.REVENUE.increasesOnDebit()).isFalse();
        assertThat(AccountType.EQUITY.increasesOnDebit()).isFalse();
    }

    @Test
    void aMerchantsBalanceIsSomethingThePlatformOwes() {
        // Worth stating outright, because it is the one most easily got backwards: the money
        // is the merchant's, held by the platform, so paying it out reduces what is owed.
        assertThat(AccountType.LIABILITY.normalSide()).isEqualTo("CREDIT");
    }

    @Test
    void everyTypeSaysWhichSideItSitsOn() {
        for (AccountType type : AccountType.values()) {
            assertThat(type.normalSide())
                    .as("%s should name a side", type)
                    .isIn("DEBIT", "CREDIT");
            assertThat(type.normalSide().equals("DEBIT"))
                    .as("%s should agree with itself", type)
                    .isEqualTo(type.increasesOnDebit());
        }
    }
}
