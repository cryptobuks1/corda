package net.corda.node.services.persistence

import net.corda.core.context.InvocationContext
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.internal.FlowIORequest
import net.corda.core.internal.PLATFORM_VERSION
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.internal.CheckpointSerializationDefaults
import net.corda.core.serialization.internal.checkpointSerialize
import net.corda.node.internal.CheckpointIncompatibleException
import net.corda.node.internal.CheckpointVerifier
import net.corda.node.services.api.CheckpointStorage
import net.corda.node.services.statemachine.Checkpoint
import net.corda.node.services.statemachine.CheckpointState
import net.corda.node.services.statemachine.ErrorState
import net.corda.node.services.statemachine.FlowError
import net.corda.node.services.statemachine.FlowStart
import net.corda.node.services.statemachine.FlowState
import net.corda.node.services.statemachine.SubFlowVersion
import net.corda.node.services.transactions.PersistentUniquenessProvider
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.nodeapi.internal.persistence.DatabaseTransaction
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.core.SerializationEnvironmentRule
import net.corda.testing.core.TestIdentity
import net.corda.testing.internal.LogHelper
import net.corda.testing.internal.configureDatabase
import net.corda.testing.node.MockServices
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun CheckpointStorage.checkpoints(): List<Checkpoint.Serialized> {
    return getAllCheckpoints().use {
        it.map { it.second }.toList()
    }
}

class DBCheckpointStorageTests {
    private companion object {
        val ALICE = TestIdentity(ALICE_NAME, 70).party
    }

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    private lateinit var checkpointStorage: DBCheckpointStorage
    private lateinit var database: CordaPersistence

    @Before
    fun setUp() {
        LogHelper.setLevel(PersistentUniquenessProvider::class)
        database = configureDatabase(makeTestDataSourceProperties(), DatabaseConfig(), { null }, { null })
        newCheckpointStorage()
    }

    @After
    fun cleanUp() {
        database.close()
        LogHelper.reset(PersistentUniquenessProvider::class)
    }

    @Test(timeout = 300_000)
    fun `add new checkpoint`() {
        val (id, checkpoint) = newCheckpoint()
        val serializedFlowState = checkpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(checkpoint)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
        }
        database.transaction {
            assertEquals(serializedFlowState, checkpointStorage.checkpoints().single().serializedFlowState)
            assertEquals(
                checkpoint,
                checkpointStorage.checkpoints().single().deserialize()
            )
        }
        newCheckpointStorage()
        database.transaction {
            assertEquals(
                checkpoint,
                checkpointStorage.checkpoints().single().deserialize()
            )
            session.get(DBCheckpointStorage.DBFlowCheckpoint::class.java, id.uuid.toString()).also {
                assertNotNull(it)
                assertNotNull(it.blob)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `update a checkpoint`() {
        val (id, checkpoint) = newCheckpoint()
        val serializedFlowState = checkpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(checkpoint)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
        }
        val logic: FlowLogic<*> = object : FlowLogic<String>() {
            override fun call(): String {
                return "Updated flow logic"
            }
        }
        val updatedCheckpoint = checkpoint.copy(
            checkpointState = checkpoint.checkpointState.copy(numberOfSuspends = 20),
            flowState = FlowState.Unstarted(
                FlowStart.Explicit,
                logic.checkpointSerialize(context = CheckpointSerializationDefaults.CHECKPOINT_CONTEXT)
            ),
            progressStep = "I have made progress",
            flowIoRequest = FlowIORequest.SendAndReceive::class.java.simpleName
        )
        val updatedSerializedFlowState = updatedCheckpoint.serializeFlowState()
        database.transaction {
            checkpointStorage.updateCheckpoint(id, updatedCheckpoint, updatedSerializedFlowState)
        }
        database.transaction {
            assertEquals(
                updatedCheckpoint,
                checkpointStorage.checkpoints().single().deserialize()
            )
        }
    }

    @Test(timeout = 300_000)
    fun `remove checkpoint`() {
        val (id, checkpoint) = newCheckpoint()
        val serializedFlowState = checkpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(checkpoint)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
        }
        database.transaction {
            checkpointStorage.removeCheckpoint(id)
        }
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).isEmpty()
        }
        newCheckpointStorage()
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).isEmpty()
        }
    }

    @Test(timeout = 300_000)
    fun `add and remove checkpoint in single commit operation`() {
        val (id, checkpoint) = newCheckpoint()
        val serializedFlowState = checkpoint.serializeFlowState()
        val (id2, checkpoint2) = newCheckpoint()
        val serializedFlowState2 = checkpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(checkpoint)
            createMetadataRecord(checkpoint2)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
            checkpointStorage.addCheckpoint(id2, checkpoint2, serializedFlowState2)
            checkpointStorage.removeCheckpoint(id)
        }
        database.transaction {
            assertEquals(
                checkpoint2,
                checkpointStorage.checkpoints().single().deserialize()
            )
        }
        newCheckpointStorage()
        database.transaction {
            assertEquals(
                checkpoint2,
                checkpointStorage.checkpoints().single().deserialize()
            )
        }
    }

    @Test(timeout = 300_000)
    fun `add two checkpoints then remove first one`() {
        val (id, firstCheckpoint) = newCheckpoint()
        val serializedFirstFlowState = firstCheckpoint.serializeFlowState()

        database.transaction {
            createMetadataRecord(firstCheckpoint)
            checkpointStorage.addCheckpoint(id, firstCheckpoint, serializedFirstFlowState)
        }
        val (id2, secondCheckpoint) = newCheckpoint()
        val serializedSecondFlowState = secondCheckpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(secondCheckpoint)
            checkpointStorage.addCheckpoint(id2, secondCheckpoint, serializedSecondFlowState)
        }
        database.transaction {
            checkpointStorage.removeCheckpoint(id)
        }
        database.transaction {
            assertEquals(
                secondCheckpoint,
                checkpointStorage.checkpoints().single().deserialize()
            )
        }
        newCheckpointStorage()
        database.transaction {
            assertEquals(
                secondCheckpoint,
                checkpointStorage.checkpoints().single().deserialize()
            )
        }
    }

    @Test(timeout = 300_000)
    fun `add checkpoint and then remove after 'restart'`() {
        val (id, originalCheckpoint) = newCheckpoint()
        val serializedOriginalFlowState = originalCheckpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(originalCheckpoint)
            checkpointStorage.addCheckpoint(id, originalCheckpoint, serializedOriginalFlowState)
        }
        newCheckpointStorage()
        val reconstructedCheckpoint = database.transaction {
            checkpointStorage.checkpoints().single()
        }
        database.transaction {
            assertEquals(originalCheckpoint, reconstructedCheckpoint.deserialize())
            assertThat(reconstructedCheckpoint.serializedFlowState).isEqualTo(serializedOriginalFlowState)
                .isNotSameAs(serializedOriginalFlowState)
        }
        database.transaction {
            checkpointStorage.removeCheckpoint(id)
        }
        database.transaction {
            assertThat(checkpointStorage.checkpoints()).isEmpty()
        }
    }

    @Test(timeout = 300_000)
    fun `verify checkpoints compatible`() {
        val mockServices = MockServices(emptyList(), ALICE.name)
        database.transaction {
            val (id, checkpoint) = newCheckpoint(1)
            val serializedFlowState = checkpoint.serializeFlowState()
            createMetadataRecord(checkpoint)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
        }

        database.transaction {
            CheckpointVerifier.verifyCheckpointsCompatible(checkpointStorage, emptyList(), 1, mockServices, emptyList())
        }

        database.transaction {
            val (id1, checkpoint1) = newCheckpoint(2)
            val serializedFlowState1 = checkpoint1.serializeFlowState()
            createMetadataRecord(checkpoint1)
            checkpointStorage.addCheckpoint(id1, checkpoint1, serializedFlowState1)
        }

        assertThatThrownBy {
            database.transaction {
                CheckpointVerifier.verifyCheckpointsCompatible(checkpointStorage, emptyList(), 1, mockServices, emptyList())
            }
        }.isInstanceOf(CheckpointIncompatibleException::class.java)
    }

    @Test(timeout = 300_000)
    @Ignore
    fun `update checkpoint with result information creates new result database record`() {
        val result = "This is the result"
        val (id, checkpoint) = newCheckpoint()
        val serializedFlowState =
            checkpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(checkpoint)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
        }
        val updatedCheckpoint = checkpoint.copy(result = result)
        val updatedSerializedFlowState = updatedCheckpoint.serializeFlowState()
        database.transaction {
            checkpointStorage.updateCheckpoint(id, updatedCheckpoint, updatedSerializedFlowState)
        }
        database.transaction {
            assertEquals(
                result,
                checkpointStorage.getCheckpoint(id)!!.deserialize().result
            )
            assertNotNull(session.get(DBCheckpointStorage.DBFlowCheckpoint::class.java, id.uuid.toString()).result)
            val criteria = session.criteriaBuilder.createQuery(DBCheckpointStorage.DBFlowResult::class.java)
            criteria.select(criteria.from(DBCheckpointStorage.DBFlowResult::class.java))
            assertEquals(1, session.createQuery(criteria).resultList.size)
        }
    }

    @Test(timeout = 300_000)
    @Ignore
    fun `update checkpoint with result information updates existing result database record`() {
        val result = "This is the result"
        val somehowThereIsANewResult = "Another result (which should not be possible!)"
        val (id, checkpoint) = newCheckpoint()
        val serializedFlowState =
            checkpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(checkpoint)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
        }
        val updatedCheckpoint = checkpoint.copy(result = result)
        val updatedSerializedFlowState = updatedCheckpoint.serializeFlowState()
        database.transaction {
            checkpointStorage.updateCheckpoint(id, updatedCheckpoint, updatedSerializedFlowState)
        }
        val updatedCheckpoint2 = checkpoint.copy(result = somehowThereIsANewResult)
        val updatedSerializedFlowState2 = updatedCheckpoint2.serializeFlowState()
        database.transaction {
            checkpointStorage.updateCheckpoint(id, updatedCheckpoint2, updatedSerializedFlowState2)
        }
        database.transaction {
            assertEquals(
                somehowThereIsANewResult,
                checkpointStorage.getCheckpoint(id)!!.deserialize().result
            )
            assertNotNull(session.get(DBCheckpointStorage.DBFlowCheckpoint::class.java, id.uuid.toString()).result)
            val criteria = session.criteriaBuilder.createQuery(DBCheckpointStorage.DBFlowResult::class.java)
            criteria.select(criteria.from(DBCheckpointStorage.DBFlowResult::class.java))
            assertEquals(1, session.createQuery(criteria).resultList.size)
        }
    }

    @Test(timeout = 300_000)
    fun `removing result information from checkpoint deletes existing result database record`() {
        val result = "This is the result"
        val (id, checkpoint) = newCheckpoint()
        val serializedFlowState =
            checkpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(checkpoint)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
        }
        val updatedCheckpoint = checkpoint.copy(result = result)
        val updatedSerializedFlowState = updatedCheckpoint.serializeFlowState()
        database.transaction {
            checkpointStorage.updateCheckpoint(id, updatedCheckpoint, updatedSerializedFlowState)
        }
        val updatedCheckpoint2 = checkpoint.copy(result = null)
        val updatedSerializedFlowState2 = updatedCheckpoint2.serializeFlowState()
        database.transaction {
            checkpointStorage.updateCheckpoint(id, updatedCheckpoint2, updatedSerializedFlowState2)
        }
        database.transaction {
            assertNull(checkpointStorage.getCheckpoint(id)!!.deserialize().result)
            assertNull(session.get(DBCheckpointStorage.DBFlowCheckpoint::class.java, id.uuid.toString()).result)
            val criteria = session.criteriaBuilder.createQuery(DBCheckpointStorage.DBFlowResult::class.java)
            criteria.select(criteria.from(DBCheckpointStorage.DBFlowResult::class.java))
            assertEquals(0, session.createQuery(criteria).resultList.size)
        }
    }

    @Test(timeout = 300_000)
    fun `update checkpoint with error information creates a new error database record`() {
        val exception = IllegalStateException("I am a naughty exception")
        val (id, checkpoint) = newCheckpoint()
        val serializedFlowState = checkpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(checkpoint)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
        }
        val updatedCheckpoint = checkpoint.addError(exception)
        val updatedSerializedFlowState = updatedCheckpoint.serializeFlowState()
        database.transaction { checkpointStorage.updateCheckpoint(id, updatedCheckpoint, updatedSerializedFlowState) }
        database.transaction {
            // Checkpoint always returns clean error state when retrieved via [getCheckpoint]
            assertTrue(checkpointStorage.getCheckpoint(id)!!.deserialize().errorState is ErrorState.Clean)
            val exceptionDetails = session.get(DBCheckpointStorage.DBFlowCheckpoint::class.java, id.uuid.toString()).exceptionDetails
            assertNotNull(exceptionDetails)
            assertEquals(exception::class.java.name, exceptionDetails!!.type)
            assertEquals(exception.message, exceptionDetails.message)
            val criteria = session.criteriaBuilder.createQuery(DBCheckpointStorage.DBFlowException::class.java)
            criteria.select(criteria.from(DBCheckpointStorage.DBFlowException::class.java))
            assertEquals(1, session.createQuery(criteria).resultList.size)
        }
    }

    @Test(timeout = 300_000)
    fun `update checkpoint with new error information updates the existing error database record`() {
        val illegalStateException = IllegalStateException("I am a naughty exception")
        val illegalArgumentException = IllegalArgumentException("I am a very naughty exception")
        val (id, checkpoint) = newCheckpoint()
        val serializedFlowState = checkpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(checkpoint)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
        }
        val updatedCheckpoint1 = checkpoint.addError(illegalStateException)
        val updatedSerializedFlowState1 = updatedCheckpoint1.serializeFlowState()
        database.transaction { checkpointStorage.updateCheckpoint(id, updatedCheckpoint1, updatedSerializedFlowState1) }
        // Set back to clean
        val updatedCheckpoint2 = checkpoint.addError(illegalArgumentException)
        val updatedSerializedFlowState2 = updatedCheckpoint2.serializeFlowState()
        database.transaction { checkpointStorage.updateCheckpoint(id, updatedCheckpoint2, updatedSerializedFlowState2) }
        database.transaction {
            assertTrue(checkpointStorage.getCheckpoint(id)!!.deserialize().errorState is ErrorState.Clean)
            val exceptionDetails = session.get(DBCheckpointStorage.DBFlowCheckpoint::class.java, id.uuid.toString()).exceptionDetails
            assertNotNull(exceptionDetails)
            assertEquals(illegalArgumentException::class.java.name, exceptionDetails!!.type)
            assertEquals(illegalArgumentException.message, exceptionDetails.message)
            val criteria = session.criteriaBuilder.createQuery(DBCheckpointStorage.DBFlowException::class.java)
            criteria.select(criteria.from(DBCheckpointStorage.DBFlowException::class.java))
            assertEquals(1, session.createQuery(criteria).resultList.size)
        }
    }

    @Test(timeout = 300_000)
    fun `clean checkpoints delete the error record from the database`() {
        val exception = IllegalStateException("I am a naughty exception")
        val (id, checkpoint) = newCheckpoint()
        val serializedFlowState = checkpoint.serializeFlowState()
        database.transaction {
            createMetadataRecord(checkpoint)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
        }
        val updatedCheckpoint = checkpoint.addError(exception)
        val updatedSerializedFlowState = updatedCheckpoint.serializeFlowState()
        database.transaction { checkpointStorage.updateCheckpoint(id, updatedCheckpoint, updatedSerializedFlowState) }
        database.transaction {
            // Checkpoint always returns clean error state when retrieved via [getCheckpoint]
            assertTrue(checkpointStorage.getCheckpoint(id)!!.deserialize().errorState is ErrorState.Clean)
        }
        // Set back to clean
        database.transaction { checkpointStorage.updateCheckpoint(id, checkpoint, serializedFlowState) }
        database.transaction {
            assertTrue(checkpointStorage.getCheckpoint(id)!!.deserialize().errorState is ErrorState.Clean)
            assertNull(session.get(DBCheckpointStorage.DBFlowCheckpoint::class.java, id.uuid.toString()).exceptionDetails)
            val criteria = session.criteriaBuilder.createQuery(DBCheckpointStorage.DBFlowException::class.java)
            criteria.select(criteria.from(DBCheckpointStorage.DBFlowException::class.java))
            assertEquals(0, session.createQuery(criteria).resultList.size)
        }
    }

    @Test(timeout = 300_000)
    fun `Checkpoint can be updated with flow io request information`() {
        val (id, checkpoint) = newCheckpoint(1)
        database.transaction {
            val serializedFlowState = checkpoint.flowState.checkpointSerialize(context = CheckpointSerializationDefaults.CHECKPOINT_CONTEXT)
            checkpointStorage.addCheckpoint(id, checkpoint, serializedFlowState)
            val checkpointFromStorage = checkpointStorage.getCheckpoint(id)
            assertNull(checkpointFromStorage!!.flowIoRequest)
        }
        database.transaction {
            val newCheckpoint = checkpoint.copy(flowIoRequest = FlowIORequest.Sleep::class.java.simpleName)
            val serializedFlowState = newCheckpoint.flowState.checkpointSerialize(
                    context = CheckpointSerializationDefaults.CHECKPOINT_CONTEXT
            )
            checkpointStorage.updateCheckpoint(id, newCheckpoint, serializedFlowState)
        }
        database.transaction {
            val checkpointFromStorage = checkpointStorage.getCheckpoint(id)
            assertNotNull(checkpointFromStorage!!.flowIoRequest)
            val flowIORequest = checkpointFromStorage.flowIoRequest
            assertEquals(FlowIORequest.Sleep::class.java.simpleName, flowIORequest)
        }
    }

    private fun newCheckpointStorage() {
        database.transaction {
            checkpointStorage = DBCheckpointStorage(object : CheckpointPerformanceRecorder {
                override fun record(
                    serializedCheckpointState: SerializedBytes<CheckpointState>,
                    serializedFlowState: SerializedBytes<FlowState>
                ) {
                    // do nothing
                }
            })
        }
    }

    private fun newCheckpoint(version: Int = 1): Pair<StateMachineRunId, Checkpoint> {
        val id = StateMachineRunId.createRandom()
        val logic: FlowLogic<*> = object : FlowLogic<Unit>() {
            override fun call() {}
        }
        val frozenLogic = logic.checkpointSerialize(context = CheckpointSerializationDefaults.CHECKPOINT_CONTEXT)
        val checkpoint = Checkpoint.create(
            InvocationContext.shell(),
            FlowStart.Explicit,
            logic.javaClass,
            frozenLogic,
            ALICE,
            SubFlowVersion.CoreFlow(version),
            false
        )
            .getOrThrow()
        return id to checkpoint
    }

    private fun Checkpoint.serializeFlowState(): SerializedBytes<FlowState> {
        return flowState.checkpointSerialize(CheckpointSerializationDefaults.CHECKPOINT_CONTEXT)
    }

    private fun Checkpoint.Serialized.deserialize(): Checkpoint {
        return deserialize(CheckpointSerializationDefaults.CHECKPOINT_CONTEXT)
    }

    private fun Checkpoint.addError(exception: Exception): Checkpoint {
        return copy(
            errorState = ErrorState.Errored(
                listOf(
                    FlowError(
                        0,
                        exception
                    )
                ), 0, false
            )
        )
    }

    private fun DatabaseTransaction.createMetadataRecord(checkpoint: Checkpoint) {
        val metadata = DBCheckpointStorage.DBFlowMetadata(
            invocationId = checkpoint.checkpointState.invocationContext.trace.invocationId.value,
            flowId = null,
            flowName = "random.flow",
            userSuppliedIdentifier = null,
            startType = DBCheckpointStorage.StartReason.RPC,
            launchingCordapp = "this cordapp",
            platformVersion = PLATFORM_VERSION,
            rpcUsername = "Batman",
            invocationInstant = checkpoint.checkpointState.invocationContext.trace.invocationId.timestamp,
            receivedInstant = Instant.now(),
            startInstant = null,
            finishInstant = null
        )
        session.save(metadata)
    }
}
