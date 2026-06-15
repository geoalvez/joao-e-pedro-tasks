package com.joaoepedro.tasks.domain

import com.joaoepedro.tasks.data.ActivityTask
import com.joaoepedro.tasks.data.Mission
import com.joaoepedro.tasks.data.MissionPhase
import com.joaoepedro.tasks.data.Periodicity
import com.joaoepedro.tasks.data.PunishmentSession
import com.joaoepedro.tasks.data.RewardTransaction
import com.joaoepedro.tasks.data.TransactionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class DomainServicesTest {
    private val rotation = TaskRotationService()
    private val balances = RewardBalanceService()

    @Test
    fun dailyTaskAlternatesEveryDay() {
        val task = ActivityTask(
            title = "Banho",
            periodicity = Periodicity.DAILY,
            startDate = LocalDate.of(2026, 6, 15),
            participantIds = listOf("joao", "pedro")
        )

        assertEquals("joao", rotation.currentPersonId(task, LocalDate.of(2026, 6, 15)))
        assertEquals("pedro", rotation.currentPersonId(task, LocalDate.of(2026, 6, 16)))
        assertEquals("joao", rotation.currentPersonId(task, LocalDate.of(2026, 6, 17)))
    }

    @Test
    fun taskHasNoAssignedPersonBeforeStartDate() {
        val task = ActivityTask(
            title = "Banho",
            periodicity = Periodicity.DAILY,
            startDate = LocalDate.of(2026, 6, 15),
            participantIds = listOf("joao", "pedro")
        )

        assertNull(rotation.currentPersonId(task, LocalDate.of(2026, 6, 14)))
    }

    @Test
    fun weeklyTaskAlternatesEveryWeek() {
        val task = ActivityTask(
            title = "Semana",
            periodicity = Periodicity.WEEKLY,
            startDate = LocalDate.of(2026, 6, 1),
            participantIds = listOf("joao", "pedro")
        )

        assertEquals("joao", rotation.currentPersonId(task, LocalDate.of(2026, 6, 7)))
        assertEquals("pedro", rotation.currentPersonId(task, LocalDate.of(2026, 6, 8)))
        assertEquals("joao", rotation.currentPersonId(task, LocalDate.of(2026, 6, 15)))
    }

    @Test
    fun assignmentsBetweenReturnsDailyHistoryForRange() {
        val task = ActivityTask(
            title = "Banho",
            periodicity = Periodicity.DAILY,
            startDate = LocalDate.of(2026, 6, 15),
            participantIds = listOf("joao", "pedro")
        )

        val history = rotation.assignmentsBetween(
            task,
            LocalDate.of(2026, 6, 15),
            LocalDate.of(2026, 6, 18)
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 6, 15) to "joao",
                LocalDate.of(2026, 6, 16) to "pedro",
                LocalDate.of(2026, 6, 17) to "joao",
                LocalDate.of(2026, 6, 18) to "pedro"
            ),
            history
        )
    }

    @Test
    fun manualOffsetChangesCurrentAndFutureAssignments() {
        val task = ActivityTask(
            title = "Banho",
            periodicity = Periodicity.DAILY,
            startDate = LocalDate.of(2026, 6, 15),
            participantIds = listOf("joao", "pedro"),
            rotationOffset = 1
        )

        assertEquals("pedro", rotation.currentPersonId(task, LocalDate.of(2026, 6, 15)))
        assertEquals("joao", rotation.currentPersonId(task, LocalDate.of(2026, 6, 16)))
        assertEquals("pedro", rotation.currentPersonId(task, LocalDate.of(2026, 6, 17)))
    }

    @Test
    fun addedParticipantBecomesCurrentAndFutureSequenceIsRecalculated() {
        val task = ActivityTask(
            title = "Banho",
            periodicity = Periodicity.DAILY,
            startDate = LocalDate.of(2026, 6, 15),
            participantIds = listOf("joao", "pedro")
        )

        val updated = rotation.addParticipantAndMakeCurrent(
            task = task,
            personId = "maria",
            today = LocalDate.of(2026, 6, 16)
        )

        assertEquals(listOf("joao", "pedro", "maria"), updated.participantIds)
        assertEquals("maria", rotation.currentPersonId(updated, LocalDate.of(2026, 6, 16)))
        assertEquals("joao", rotation.currentPersonId(updated, LocalDate.of(2026, 6, 17)))
        assertEquals("pedro", rotation.currentPersonId(updated, LocalDate.of(2026, 6, 18)))
    }

    @Test
    fun lastAddedParticipantWinsWhenPeopleAreAddedInSequence() {
        val task = ActivityTask(
            title = "Banho",
            periodicity = Periodicity.DAILY,
            startDate = LocalDate.of(2026, 6, 15),
            participantIds = listOf("joao", "pedro")
        )

        val withMaria = rotation.addParticipantAndMakeCurrent(task, "maria", LocalDate.of(2026, 6, 16))
        val withAna = rotation.addParticipantAndMakeCurrent(withMaria, "ana", LocalDate.of(2026, 6, 16))

        assertEquals("ana", rotation.currentPersonId(withAna, LocalDate.of(2026, 6, 16)))
    }

    @Test
    fun missionRequiresAllPhasesCheckedBeforeCompletion() {
        val mission = Mission(
            title = "Arrumar quarto",
            rewardPersonId = "joao",
            rewardAmount = 10,
            participantIds = listOf("joao", "pedro"),
            phases = listOf(
                MissionPhase(title = "Guardar brinquedos", checkedParticipantIds = listOf("joao", "pedro")),
                MissionPhase(title = "Varrer", checkedParticipantIds = listOf("joao"))
            )
        )

        assertFalse(mission.isReadyToComplete())
        assertFalse(mission.isReadyToCompleteFor("pedro"))
        assertTrue(mission.copy(phases = mission.phases.map {
            it.copy(checkedParticipantIds = listOf("joao", "pedro"))
        }).isReadyToComplete())
    }

    @Test
    fun missionProgressIsIndividualPerParticipant() {
        val mission = Mission(
            title = "Arrumar quarto",
            rewardPersonId = "joao",
            rewardAmount = 10,
            participantIds = listOf("joao", "pedro"),
            phases = listOf(
                MissionPhase(title = "Etapa 1", checkedParticipantIds = listOf("joao")),
                MissionPhase(title = "Etapa 2")
            )
        )

        assertEquals(1, mission.completedPhaseCountFor("joao"))
        assertEquals(0, mission.completedPhaseCountFor("pedro"))
    }

    @Test
    fun missionRewardIsCreditedOnlyForParticipantWhoCompletedOnce() {
        val mission = Mission(
            title = "Arrumar quarto",
            rewardPersonId = "joao",
            rewardAmount = 10,
            participantIds = listOf("joao", "pedro"),
            phases = listOf(
                MissionPhase(title = "Etapa 1", checkedParticipantIds = listOf("joao")),
                MissionPhase(title = "Etapa 2", checkedParticipantIds = listOf("joao"))
            )
        )

        assertTrue(mission.shouldCreditRewardFor("joao"))
        assertFalse(mission.shouldCreditRewardFor("pedro"))

        val paid = mission.markRewardPaidFor("joao")

        assertFalse(paid.shouldCreditRewardFor("joao"))
        assertFalse(paid.isCompleted())
    }

    @Test
    fun missionWithoutPhasesIsNotReadyToComplete() {
        val mission = Mission(title = "Sem fases", rewardPersonId = "joao", rewardAmount = 10)

        assertFalse(mission.isReadyToComplete())
    }

    @Test
    fun balanceUsesLedgerSigns() {
        val transactions = listOf(
            RewardTransaction(personId = "joao", type = TransactionType.DEPOSIT, amount = 10.0, reason = "Ajudou"),
            RewardTransaction(personId = "joao", type = TransactionType.WITHDRAW, amount = 3.0, reason = "Usou"),
            RewardTransaction(personId = "joao", type = TransactionType.PENALTY, amount = 2.0, reason = "Penalidade")
        )

        assertEquals(5.0, balances.balanceFor("joao", transactions), 0.001)
    }

    @Test
    fun transactionRequiresPositiveAmountAndReason() {
        expectIllegalArgument { balances.validate(0.0, "ok") }
        expectIllegalArgument { balances.validate(1.0, "") }
    }

    @Test
    fun punishmentSessionUsesEndTimeToCalculateRemainingTime() {
        val session = PunishmentSession(
            personId = "joao",
            startedAtMillis = 1_000L,
            endsAtMillis = 61_000L
        )

        assertEquals(30_000L, session.remainingMillis(31_000L))
        assertTrue(session.isRunning(31_000L))
        assertEquals(0L, session.remainingMillis(70_000L))
        assertFalse(session.isRunning(70_000L))
        assertEquals(69_000L, session.elapsedMillis(70_000L))
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
