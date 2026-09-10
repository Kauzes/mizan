package dev.kauzes.mizan.risk;

/**
 * One thing a rule noticed, and what it thought that was worth.
 *
 * <p>The reason scoring returns these rather than a number. A score of 68 tells a merchant
 * nothing they can act on and tells an analyst nothing they can check; "the amount is nine
 * times this merchant's usual, and this card has never been seen here" is a sentence somebody
 * can agree or disagree with.
 *
 * <p>It also means a wrong decision is debuggable. A scorer that cannot say why is one nobody
 * can fix, because there is nothing to point at.
 *
 * @param rule which rule fired, from the closed set
 * @param contribution how much it added to the score. Positive raises risk; a rule that
 *     lowers it says so with a negative number rather than by being absent
 * @param because what it saw, in words, with the actual values in them
 */
public record Signal(Rule rule, int contribution, String because) {

    public static Signal of(Rule rule, int contribution, String because) {
        return new Signal(rule, contribution, because);
    }
}
