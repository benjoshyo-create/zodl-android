package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.model.voting.BundleDelegationPhase
import co.electriccoin.zcash.ui.common.model.voting.DelegationPhase
import co.electriccoin.zcash.ui.common.model.voting.VotingDelegationPirPrecomputeResult
import co.electriccoin.zcash.ui.common.model.voting.VotingDelegationProof
import co.electriccoin.zcash.ui.common.model.voting.VotingPirLayout
import co.electriccoin.zcash.ui.common.provider.PirSnapshotResolver
import co.electriccoin.zcash.ui.common.provider.VotingCryptoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VotingProofPrecomputeRepositoryTest {
    @Test
    fun precomputeResolvesPirServerAndRunsAgainstVotingDb() =
        runBlocking {
            val cryptoClient = FakeVotingCryptoClient()
            val pirSnapshotResolver = FakePirSnapshotResolver("https://pir.example")
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val repository =
                VotingProofPrecomputeRepositoryImpl(
                    votingCryptoClient = cryptoClient.client,
                    pirSnapshotResolver = pirSnapshotResolver,
                    scope = scope
                )
            val request = precomputeRequest()

            repository.startDelegationPirPrecompute(request)

            val result =
                requireNotNull(repository.awaitDelegationPirPrecompute(request.key))
                    .getOrThrow()

            assertEquals(VotingDelegationPirPrecomputeResult(cachedCount = 2, fetchedCount = 3), result)
            assertEquals(
                listOf(
                    ResolveCall(
                        endpoints = listOf("https://pir-a", "https://pir-b"),
                        expectedSnapshotHeight = 123L
                    )
                ),
                pirSnapshotResolver.calls
            )
            assertEquals(
                listOf(
                    CryptoCall.OpenVotingDb("/tmp/voting.sqlite3"),
                    CryptoCall.SetWalletId(dbHandle = DB_HANDLE, walletId = "wallet-id", networkId = 0),
                    CryptoCall.PrecomputeDelegationPir(
                        dbHandle = DB_HANDLE,
                        roundId = "round-id",
                        bundleIndex = 1,
                        pirServerUrl = "https://pir.example",
                        pirLayout = VotingPirLayout(),
                        notesJson = "[notes]"
                    ),
                    CryptoCall.CloseVotingDb(DB_HANDLE)
                ),
                cryptoClient.calls
            )

            scope.cancel()
        }

    @Test
    fun precomputeFailureIsReturnedAsResultAndClosesVotingDb() =
        runBlocking {
            val failure = IllegalStateException("pir failed")
            val cryptoClient = FakeVotingCryptoClient(precomputeFailure = failure)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val repository =
                VotingProofPrecomputeRepositoryImpl(
                    votingCryptoClient = cryptoClient.client,
                    pirSnapshotResolver = FakePirSnapshotResolver("https://pir.example"),
                    scope = scope
                )
            val request = precomputeRequest()

            repository.startDelegationPirPrecompute(request)

            val result = requireNotNull(repository.awaitDelegationPirPrecompute(request.key))
            val thrown =
                assertFailsWith<IllegalStateException> {
                    result.getOrThrow()
                }

            assertEquals(failure, thrown)
            assertEquals(CryptoCall.CloseVotingDb(DB_HANDLE), cryptoClient.calls.last())

            scope.cancel()
        }

    @Test
    fun phaseRegressionPrecomputeFailureIsReturnedAsResultAndClosesVotingDb() =
        runBlocking {
            val failure = IllegalStateException("refusing to regress round phase from PROVED to DELEGATION")
            val cryptoClient = FakeVotingCryptoClient(precomputeFailure = failure)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val repository =
                VotingProofPrecomputeRepositoryImpl(
                    votingCryptoClient = cryptoClient.client,
                    pirSnapshotResolver = FakePirSnapshotResolver("https://pir.example"),
                    scope = scope
                )
            val request = precomputeRequest()

            repository.startDelegationPirPrecompute(request)

            val result = requireNotNull(repository.awaitDelegationPirPrecompute(request.key))
            val thrown =
                assertFailsWith<IllegalStateException> {
                    result.getOrThrow()
                }

            // A phase-regression-flavored failure during precompute is no longer swallowed into
            // a fake success (see VotingProofPrecomputeRepositoryImpl.runPrecompute) - it now
            // surfaces as a plain Result.failure, same as any other precompute failure, while
            // still closing the voting DB.
            assertEquals(failure, thrown)
            assertEquals(CryptoCall.CloseVotingDb(DB_HANDLE), cryptoClient.calls.last())

            scope.cancel()
        }

    @Test
    fun warmProvingCachesStartsOnlyOnce() =
        runBlocking {
            val cryptoClient = FakeVotingCryptoClient()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val repository =
                VotingProofPrecomputeRepositoryImpl(
                    votingCryptoClient = cryptoClient.client,
                    pirSnapshotResolver = FakePirSnapshotResolver("https://pir.example"),
                    scope = scope
                )

            repository.warmProvingCaches()
            repository.warmProvingCaches()
            yield()

            assertEquals(1, cryptoClient.warmupCount)

            scope.cancel()
        }

    @Test
    fun proofStageRunsAfterPirOnOwnHandle() =
        runBlocking {
            val cryptoClient = FakeVotingCryptoClient()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val repository = repository(cryptoClient, scope)
            val material = proofMaterial()
            val request = precomputeRequest(proofMaterial = material)

            repository.startDelegationPirPrecompute(request)

            requireNotNull(repository.awaitDelegationProof(request.key)).getOrThrow()

            // The proof opens and closes its own handle after the PIR stage has closed its one.
            assertEquals(
                listOf(
                    "openVotingDb",
                    "setWalletId",
                    "precomputeDelegationPir",
                    "closeVotingDb",
                    "openVotingDb",
                    "setWalletId",
                    "delegationPhases",
                    "buildAndProveDelegation",
                    "closeVotingDb"
                ),
                cryptoClient.calls.map { call -> call.label() }
            )
            // The key material is zeroed as soon as the proof no longer needs it.
            assertContentEquals(ByteArray(3), material.fvkBytes)
            assertContentEquals(ByteArray(3), material.hotkeySeed)
            assertContentEquals(ByteArray(3), material.seedFingerprint)

            scope.cancel()
        }

    @Test
    fun proofStageSkippedWithoutProofMaterial() =
        runBlocking {
            val cryptoClient = FakeVotingCryptoClient()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val repository = repository(cryptoClient, scope)
            val request = precomputeRequest()

            repository.startDelegationPirPrecompute(request)
            requireNotNull(repository.awaitDelegationPirPrecompute(request.key)).getOrThrow()

            assertNull(repository.awaitDelegationProof(request.key))
            assertTrue(cryptoClient.calls.none { call -> call is CryptoCall.BuildAndProveDelegation })

            scope.cancel()
        }

    @Test
    fun awaitDelegationProofReturnsNullWhenNotScheduled() =
        runBlocking {
            val cryptoClient = FakeVotingCryptoClient()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val repository = repository(cryptoClient, scope)

            assertNull(repository.awaitDelegationProof(precomputeRequest().key))

            scope.cancel()
        }

    @Test
    fun proofStageSkippedWhenBundleNotPcztBuilt() =
        runBlocking {
            val cryptoClient = FakeVotingCryptoClient(bundlePhase = DelegationPhase.PREPARED)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val repository = repository(cryptoClient, scope)
            val request = precomputeRequest(proofMaterial = proofMaterial())

            repository.startDelegationPirPrecompute(request)

            val outcome = requireNotNull(repository.awaitDelegationProof(request.key))

            assertFailsWith<IllegalStateException> { outcome.getOrThrow() }
            assertTrue(cryptoClient.calls.none { call -> call is CryptoCall.BuildAndProveDelegation })
            assertEquals(CryptoCall.CloseVotingDb(DB_HANDLE), cryptoClient.calls.last())

            scope.cancel()
        }

    @Test
    fun proofFailureSurfacesAsFailureAndClosesDb() =
        runBlocking {
            val failure = IllegalStateException("proof failed")
            val cryptoClient = FakeVotingCryptoClient(proofFailure = failure)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val repository = repository(cryptoClient, scope)
            val request = precomputeRequest(proofMaterial = proofMaterial())

            repository.startDelegationPirPrecompute(request)

            val outcome = requireNotNull(repository.awaitDelegationProof(request.key))

            assertEquals(failure, assertFailsWith<IllegalStateException> { outcome.getOrThrow() })
            assertEquals(CryptoCall.CloseVotingDb(DB_HANDLE), cryptoClient.calls.last())

            scope.cancel()
        }

    /**
     * Each proof blocks inside the fake until its sibling has entered it, so a run that still
     * proved one bundle at a time would time out there instead of reaching the assertion.
     */
    @Test
    fun proofStagesRunTwoAtATime() =
        runBlocking {
            val cryptoClient =
                FakeVotingCryptoClient(
                    provedBundleIndices = listOf(PROOF_BUNDLE_INDEX, PROOF_BUNDLE_INDEX + 1),
                    concurrentProofTarget = 2
                )
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val repository = repository(cryptoClient, scope)
            val first = precomputeRequest(proofMaterial = proofMaterial(), bundleIndex = PROOF_BUNDLE_INDEX)
            val second = precomputeRequest(proofMaterial = proofMaterial(), bundleIndex = PROOF_BUNDLE_INDEX + 1)

            repository.startDelegationPirPrecompute(first)
            repository.startDelegationPirPrecompute(second)
            requireNotNull(repository.awaitDelegationProof(first.key)).getOrThrow()
            requireNotNull(repository.awaitDelegationProof(second.key)).getOrThrow()

            assertEquals(2, cryptoClient.maxConcurrentProofs)

            scope.cancel()
        }

    @Test
    fun cancelBackgroundProofsClearsSecretsAndAwaitReturnsFailure() =
        runBlocking {
            val cryptoClient = FakeVotingCryptoClient(proofDelayMillis = PROOF_DELAY_MILLIS)
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val repository = repository(cryptoClient, scope)
            val material = proofMaterial()
            val request = precomputeRequest(proofMaterial = material)

            repository.startDelegationPirPrecompute(request)
            repository.cancelBackgroundProofs()

            assertContentEquals(ByteArray(3), material.fvkBytes)
            assertContentEquals(ByteArray(3), material.hotkeySeed)
            assertContentEquals(ByteArray(3), material.seedFingerprint)
            // The job is forgotten, so a caller arriving afterwards proves on demand instead.
            assertNull(repository.awaitDelegationProof(request.key))

            scope.cancel()
        }

    /**
     * The cancel zeroes the material the repository holds, but a native proof already under way
     * cannot be interrupted - it keeps reading its own copy to the end.
     */
    @Test
    fun cancelLeavesAnInFlightProofsKeyMaterialIntact() =
        runBlocking {
            val proofGate = ProofGate()
            val cryptoClient = FakeVotingCryptoClient(proofGate = proofGate)
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val repository = repository(cryptoClient, scope)
            val material = proofMaterial()
            val request = precomputeRequest(proofMaterial = material)

            repository.startDelegationPirPrecompute(request)
            assertTrue(proofGate.entered.await(GATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            repository.cancelBackgroundProofs()
            proofGate.release.countDown()
            assertTrue(proofGate.captured.await(GATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            assertContentEquals(ByteArray(3), material.fvkBytes)
            assertContentEquals(byteArrayOf(1, 2, 3), proofGate.fvkBytes)
            assertContentEquals(byteArrayOf(4, 5, 6), proofGate.hotkeySeed)
            assertContentEquals(byteArrayOf(7, 8, 9), proofGate.seedFingerprint)

            scope.cancel()
        }

    private fun repository(
        cryptoClient: FakeVotingCryptoClient,
        scope: CoroutineScope
    ) = VotingProofPrecomputeRepositoryImpl(
        votingCryptoClient = cryptoClient.client,
        pirSnapshotResolver = FakePirSnapshotResolver("https://pir.example"),
        scope = scope
    )

    private fun precomputeRequest(
        proofMaterial: VotingDelegationProofMaterial? = null,
        bundleIndex: Int = PROOF_BUNDLE_INDEX
    ) = VotingDelegationPirPrecomputeRequest(
        accountUuid = "account",
        walletId = "wallet-id",
        votingDbPath = "/tmp/voting.sqlite3",
        roundId = "round-id",
        bundleIndex = bundleIndex,
        pirEndpoints = listOf("https://pir-a", "https://pir-b"),
        pirLayout = VotingPirLayout(),
        expectedSnapshotHeight = 123L,
        networkId = 0,
        notesJson = "[notes]",
        proofMaterial = proofMaterial
    )

    private fun proofMaterial() =
        VotingDelegationProofMaterial(
            fvkBytes = byteArrayOf(1, 2, 3),
            hotkeySeed = byteArrayOf(4, 5, 6),
            seedFingerprint = byteArrayOf(7, 8, 9),
            accountIndex = 0,
            roundName = "Round"
        )
}

private const val PROOF_BUNDLE_INDEX = 1

private const val PROOF_DELAY_MILLIS = 100L

private const val GATE_TIMEOUT_MILLIS = 10_000L

/**
 * Holds a proof inside the fake until the test releases it, then records the key material the
 * proof was actually given.
 */
private class ProofGate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val captured = CountDownLatch(1)
    var fvkBytes: ByteArray? = null
    var hotkeySeed: ByteArray? = null
    var seedFingerprint: ByteArray? = null
}

private fun CryptoCall.label(): String =
    when (this) {
        is CryptoCall.OpenVotingDb -> "openVotingDb"
        is CryptoCall.SetWalletId -> "setWalletId"
        is CryptoCall.PrecomputeDelegationPir -> "precomputeDelegationPir"
        is CryptoCall.CloseVotingDb -> "closeVotingDb"
        is CryptoCall.DelegationPhases -> "delegationPhases"
        is CryptoCall.BuildAndProveDelegation -> "buildAndProveDelegation"
    }

private const val DB_HANDLE = 42L

private class FakePirSnapshotResolver(
    private val resolvedUrl: String
) : PirSnapshotResolver {
    val calls = mutableListOf<ResolveCall>()

    override suspend fun resolve(
        endpoints: List<String>,
        expectedSnapshotHeight: Long
    ): String {
        calls +=
            ResolveCall(
                endpoints = endpoints,
                expectedSnapshotHeight = expectedSnapshotHeight
            )
        return resolvedUrl
    }
}

private class FakeVotingCryptoClient(
    private val precomputeFailure: Exception? = null,
    private val proofFailure: Exception? = null,
    private val bundlePhase: DelegationPhase? = DelegationPhase.PCZT_BUILT,
    private val provedBundleIndices: List<Int> = listOf(PROOF_BUNDLE_INDEX),
    private val proofDelayMillis: Long = 0,
    private val proofGate: ProofGate? = null,
    concurrentProofTarget: Int? = null
) {
    val calls = mutableListOf<CryptoCall>()
    var warmupCount = 0
    var maxConcurrentProofs = 0
    private var activeProofs = 0

    /** Holds every proof until [concurrentProofTarget] of them are inside the fake at once. */
    private val concurrentProofs = concurrentProofTarget?.let { target -> CountDownLatch(target) }

    val client: VotingCryptoClient =
        Proxy.newProxyInstance(
            VotingCryptoClient::class.java.classLoader,
            arrayOf(VotingCryptoClient::class.java)
        ) { _, method, args ->
            when (method.name) {
                "openVotingDb" -> {
                    calls += CryptoCall.OpenVotingDb(args.valueAt(0))
                    DB_HANDLE
                }

                "setWalletId" -> {
                    calls +=
                        CryptoCall.SetWalletId(
                            dbHandle = args.valueAt(0),
                            walletId = args.valueAt(1),
                            networkId = args.valueAt(2)
                        )
                    Unit
                }

                "precomputeDelegationPir" -> {
                    calls +=
                        CryptoCall.PrecomputeDelegationPir(
                            dbHandle = args.valueAt(0),
                            roundId = args.valueAt(1),
                            bundleIndex = args.valueAt(2),
                            pirServerUrl = args.valueAt(3),
                            pirLayout = args.valueAt(4),
                            notesJson = args.valueAt(5)
                        )
                    precomputeFailure?.let { throw it }
                    VotingDelegationPirPrecomputeResult(cachedCount = 2, fetchedCount = 3)
                }

                "delegationPhases" -> {
                    calls += CryptoCall.DelegationPhases(args.valueAt(0), args.valueAt(1))
                    bundlePhase?.let { phase ->
                        provedBundleIndices.map { index -> BundleDelegationPhase(index, phase) }
                    } ?: emptyList<BundleDelegationPhase>()
                }

                "buildAndProveDelegation" -> {
                    synchronized(this) {
                        activeProofs += 1
                        maxConcurrentProofs = maxOf(maxConcurrentProofs, activeProofs)
                        calls += CryptoCall.BuildAndProveDelegation(args.valueAt(0), args.valueAt(2))
                    }
                    @Suppress("ForbiddenComment")
                    if (proofDelayMillis > 0) {
                        Thread.sleep(proofDelayMillis)
                    }
                    concurrentProofs?.let { latch ->
                        latch.countDown()
                        latch.await(GATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    }
                    proofGate?.let { gate ->
                        gate.entered.countDown()
                        gate.release.await(GATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                        gate.fvkBytes = args.valueAt<ByteArray>(6).copyOf()
                        gate.hotkeySeed = args.valueAt<ByteArray>(7).copyOf()
                        gate.seedFingerprint = args.valueAt<ByteArray>(8).copyOf()
                        gate.captured.countDown()
                    }
                    synchronized(this) { activeProofs -= 1 }
                    proofFailure?.let { throw it }
                    delegationProof()
                }

                "closeVotingDb" -> {
                    calls += CryptoCall.CloseVotingDb(args.valueAt(0))
                    Unit
                }

                "warmProvingCaches" -> {
                    warmupCount += 1
                    Unit
                }

                else -> {
                    method.handleObjectMethod(this, args)
                }
            }
        } as VotingCryptoClient
}

private data class ResolveCall(
    val endpoints: List<String>,
    val expectedSnapshotHeight: Long
)

private sealed interface CryptoCall {
    data class OpenVotingDb(
        val votingDbPath: String
    ) : CryptoCall

    data class SetWalletId(
        val dbHandle: Long,
        val walletId: String,
        val networkId: Int
    ) : CryptoCall

    data class PrecomputeDelegationPir(
        val dbHandle: Long,
        val roundId: String,
        val bundleIndex: Int,
        val pirServerUrl: String,
        val pirLayout: VotingPirLayout,
        val notesJson: String
    ) : CryptoCall

    data class CloseVotingDb(
        val dbHandle: Long
    ) : CryptoCall

    data class DelegationPhases(
        val dbHandle: Long,
        val roundId: String
    ) : CryptoCall

    data class BuildAndProveDelegation(
        val dbHandle: Long,
        val bundleIndex: Int
    ) : CryptoCall
}

private fun delegationProof() =
    VotingDelegationProof(
        proof = byteArrayOf(1),
        publicInputs = emptyList(),
        nfSigned = byteArrayOf(2),
        cmxNew = byteArrayOf(3),
        govNullifiers = emptyList(),
        vanComm = byteArrayOf(4),
        rk = byteArrayOf(5)
    )

@Suppress("UNCHECKED_CAST")
private fun <T> Array<Any?>?.valueAt(index: Int): T = this?.get(index) as T

private fun Method.handleObjectMethod(
    target: Any,
    args: Array<Any?>?
): Any? =
    when (name) {
        "equals" -> target === args?.firstOrNull()
        "hashCode" -> System.identityHashCode(target)
        "toString" -> target.toString()
        else -> error("Unexpected VotingCryptoClient call: $name")
    }
