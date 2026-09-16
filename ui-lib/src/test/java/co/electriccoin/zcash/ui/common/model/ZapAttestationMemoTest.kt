package co.electriccoin.zcash.ui.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZapAttestationMemoTest {
    @Test
    fun `parses a well formed ZAP1 memo`() {
        val result = ZapAttestationMemo.parse("ZAP1:AGENT_ACTION:$VALID_HASH")

        assertEquals(ZapAttestationMemo.Protocol.ZAP1, result?.protocol)
        assertEquals("AGENT_ACTION", result?.eventType)
        assertEquals(VALID_HASH, result?.leafHash)
    }

    @Test
    fun `parses a legacy NSM1 memo`() {
        val result = ZapAttestationMemo.parse("NSM1:DEPLOYMENT:$VALID_HASH")

        assertEquals(ZapAttestationMemo.Protocol.NSM1, result?.protocol)
        assertEquals("DEPLOYMENT", result?.eventType)
    }

    @Test
    fun `tolerates surrounding whitespace and memo padding`() {
        val result = ZapAttestationMemo.parse("  ZAP1:MERKLE_ROOT:$VALID_HASH  \n")

        assertEquals("MERKLE_ROOT", result?.eventType)
    }

    @Test
    fun `humanises the event type for display`() {
        assertEquals(
            "Governance proposal",
            ZapAttestationMemo.parse("ZAP1:GOVERNANCE_PROPOSAL:$VALID_HASH")?.eventLabel
        )
        assertEquals(
            "Deployment",
            ZapAttestationMemo.parse("ZAP1:DEPLOYMENT:$VALID_HASH")?.eventLabel
        )
    }

    @Test
    fun `accepts event types not yet known to this build`() {
        val result = ZapAttestationMemo.parse("ZAP1:SOME_FUTURE_EVENT:$VALID_HASH")

        assertEquals("SOME_FUTURE_EVENT", result?.eventType)
        assertEquals("Some future event", result?.eventLabel)
    }

    @Test
    fun `rejects plain text`() {
        assertNull(ZapAttestationMemo.parse("Thanks for lunch"))
        assertNull(ZapAttestationMemo.parse(""))
    }

    @Test
    fun `rejects text that merely mentions the prefix`() {
        assertNull(ZapAttestationMemo.parse("Have a look at ZAP1:AGENT_ACTION:$VALID_HASH"))
    }

    @Test
    fun `rejects a missing or extra field`() {
        assertNull(ZapAttestationMemo.parse("ZAP1:AGENT_ACTION"))
        assertNull(ZapAttestationMemo.parse("ZAP1:AGENT_ACTION:$VALID_HASH:extra"))
        assertNull(ZapAttestationMemo.parse("ZAP1::$VALID_HASH"))
    }

    @Test
    fun `rejects a malformed event type`() {
        assertNull(ZapAttestationMemo.parse("ZAP1:agent_action:$VALID_HASH"))
        assertNull(ZapAttestationMemo.parse("ZAP1:1AGENT:$VALID_HASH"))
        assertNull(ZapAttestationMemo.parse("ZAP1:AGENT-ACTION:$VALID_HASH"))
    }

    @Test
    fun `rejects a malformed leaf hash`() {
        assertNull(ZapAttestationMemo.parse("ZAP1:AGENT_ACTION:nothexadecimal"))
        assertNull(ZapAttestationMemo.parse("ZAP1:AGENT_ACTION:abc"))
        assertNull(ZapAttestationMemo.parse("ZAP1:AGENT_ACTION:"))
    }

    @Test
    fun `rejects an unknown protocol prefix`() {
        assertNull(ZapAttestationMemo.parse("ZAP2:AGENT_ACTION:$VALID_HASH"))
    }

    private companion object {
        const val VALID_HASH = "4f3a1c9d8b2e5a7c04f3a1c9d8b2e5a7c04f3a1c9d8b2e5a7c04f3a1c9d8b2e5"
    }
}
