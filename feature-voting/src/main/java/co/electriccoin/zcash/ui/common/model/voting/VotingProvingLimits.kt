package co.electriccoin.zcash.ui.common.model.voting

/**
 * How many delegation or vote proofs a round may have in flight at once, shared by the foreground
 * submission and the background proof stage so the two cannot add up to more than they intend.
 *
 * It matches the trimmed bundle cap, so every bundle of a round proves in parallel: the SDK gives
 * each bundle its own database connection and proves outside the session mutex, so a second proof
 * no longer queues behind the first.
 */
internal object VotingProvingLimits {
    const val MAX_CONCURRENT_PROOFS = 2
}
