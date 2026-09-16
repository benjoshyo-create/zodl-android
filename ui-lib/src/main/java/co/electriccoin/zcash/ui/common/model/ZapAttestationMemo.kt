package co.electriccoin.zcash.ui.common.model

/**
 * A structured attestation memo as defined by the ZAP1 protocol.
 *
 * ZAP1 encodes lifecycle, governance and agent events into a shielded memo as a plain-text
 * marker of the form `ZAP1:{EVENT_TYPE}:{LEAF_HASH}`, where the leaf hash commits to the event
 * payload in a BLAKE2b Merkle tree whose roots are anchored on chain. The memo itself carries no
 * event content, only the commitment, so rendering it reveals nothing beyond what the sender
 * already published.
 *
 * `NSM1` is the same wire format under the protocol's previous name and is still found on chain,
 * so it is parsed and surfaced as legacy.
 *
 * Parsing is deliberately conservative: anything that does not match the grammar exactly returns
 * `null` so the caller falls back to rendering the memo as ordinary text.
 */
data class ZapAttestationMemo(
    val protocol: Protocol,
    val eventType: String,
    val leafHash: String,
) {
    /**
     * A human-readable form of [eventType], derived rather than mapped so that event types added
     * to the ZAP1 registry after this build still render sensibly instead of falling back to raw
     * screaming snake case. For example `AGENT_ACTION` becomes `Agent action`.
     */
    val eventLabel: String
        get() =
            eventType
                .split(WORD_SEPARATOR)
                .filter { it.isNotEmpty() }
                .joinToString(" ") { it.lowercase() }
                .replaceFirstChar { it.uppercaseChar() }

    enum class Protocol {
        ZAP1,
        NSM1
    }

    companion object {
        private const val ZAP1_PREFIX = "ZAP1:"
        private const val NSM1_PREFIX = "NSM1:"
        private const val FIELD_SEPARATOR = ':'
        private const val WORD_SEPARATOR = '_'
        private const val FIELD_COUNT = 2

        private val EVENT_TYPE_REGEX = Regex("^[A-Z][A-Z0-9_]{0,63}$")
        private val LEAF_HASH_REGEX = Regex("^[0-9a-fA-F]{8,128}$")

        /**
         * Returns the parsed attestation, or `null` when [memo] is not a well-formed ZAP1 or NSM1
         * marker. Callers should treat `null` as "render this as an ordinary text memo".
         */
        fun parse(memo: String): ZapAttestationMemo? {
            val trimmed = memo.trim()

            val protocol =
                when {
                    trimmed.startsWith(ZAP1_PREFIX) -> Protocol.ZAP1
                    trimmed.startsWith(NSM1_PREFIX) -> Protocol.NSM1
                    else -> return null
                }

            val body =
                when (protocol) {
                    Protocol.ZAP1 -> trimmed.removePrefix(ZAP1_PREFIX)
                    Protocol.NSM1 -> trimmed.removePrefix(NSM1_PREFIX)
                }

            val fields = body.split(FIELD_SEPARATOR)
            if (fields.size != FIELD_COUNT) return null

            val eventType = fields[0]
            val leafHash = fields[1]

            if (!EVENT_TYPE_REGEX.matches(eventType)) return null
            if (!LEAF_HASH_REGEX.matches(leafHash)) return null

            return ZapAttestationMemo(
                protocol = protocol,
                eventType = eventType,
                leafHash = leafHash
            )
        }
    }
}
