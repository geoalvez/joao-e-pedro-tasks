package com.joaoepedro.tasks.data

import java.time.LocalDate
import java.util.UUID

enum class Periodicity(val label: String) {
    DAILY("Diária"),
    WEEKLY("Semanal"),
    MONTHLY("Mensal")
}

enum class TransactionType(val label: String, val sign: Int) {
    DEPOSIT("Depósito", 1),
    WITHDRAW("Saque", -1),
    PENALTY("Penalidade", -1),
    DAILY_ALLOWANCE("Mesada diária", 1)
}

data class Person(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val archived: Boolean = false,
    val createdAt: LocalDate = LocalDate.now(),
    val birthDate: LocalDate? = null,
    val schoolYear: String? = null,
    val photoUri: String? = null
)

data class ActivityTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val periodicity: Periodicity = Periodicity.DAILY,
    val startDate: LocalDate = LocalDate.now(),
    val participantIds: List<String>,
    val rotationOffset: Int = 0,
    val active: Boolean = true
)

data class MissionPhase(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val checked: Boolean = false,
    val checkedParticipantIds: List<String> = emptyList()
)

data class Mission(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val rewardPersonId: String,
    val rewardAmount: Int,
    val participantIds: List<String> = emptyList(),
    val phases: List<MissionPhase> = emptyList(),
    val completedAt: LocalDate? = null,
    val rewardTransactionId: String? = null,
    val rewardPaidParticipantIds: List<String> = emptyList()
) {
    fun isReadyToComplete(): Boolean = participantIds.isNotEmpty() &&
        participantIds.all { isReadyToCompleteFor(it) }
    fun isReadyToCompleteFor(personId: String): Boolean =
        phases.isNotEmpty() && phases.all { personId in it.checkedParticipantIds }
    fun completedPhaseCountFor(personId: String): Int = phases.count { personId in it.checkedParticipantIds }
    fun shouldCreditRewardFor(personId: String): Boolean =
        isReadyToCompleteFor(personId) && personId !in rewardPaidParticipantIds
    fun markRewardPaidFor(personId: String): Mission =
        copy(rewardPaidParticipantIds = (rewardPaidParticipantIds + personId).distinct())
    fun isCompleted(): Boolean = participantIds.isNotEmpty() &&
        participantIds.all { it in rewardPaidParticipantIds }
}

data class RewardTransaction(
    val id: String = UUID.randomUUID().toString(),
    val personId: String,
    val type: TransactionType,
    val amount: Double,
    val reason: String,
    val createdAt: LocalDate = LocalDate.now(),
    val reversed: Boolean = false
)

data class DailyAllowanceConfig(
    val amountPerDay: Int = 0,
    val enabledSince: LocalDate? = null,
    val lastProcessedDate: LocalDate? = null
)

data class PunishmentSession(
    val id: String = UUID.randomUUID().toString(),
    val personId: String,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val endsAtMillis: Long,
    val active: Boolean = true,
    val completedAlerted: Boolean = false,
    val finishedAtMillis: Long? = null
) {
    fun remainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        (endsAtMillis - nowMillis).coerceAtLeast(0)

    fun isRunning(nowMillis: Long = System.currentTimeMillis()): Boolean =
        active && remainingMillis(nowMillis) > 0

    fun elapsedMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val finish = finishedAtMillis ?: if (active) nowMillis else endsAtMillis
        return (finish - startedAtMillis).coerceAtLeast(0)
    }
}

data class AppState(
    val people: List<Person> = emptyList(),
    val tasks: List<ActivityTask> = emptyList(),
    val missions: List<Mission> = emptyList(),
    val transactions: List<RewardTransaction> = emptyList(),
    val punishments: List<PunishmentSession> = emptyList(),
    val dailyAllowance: DailyAllowanceConfig = DailyAllowanceConfig(),
    val pointValueCents: Int = 33,
    val language: String = "pt",
    val biometricEnabled: Boolean = false
) {
    companion object {
        fun seeded(): AppState {
            val joao = Person(name = "João")
            val pedro = Person(name = "Pedro")
            return AppState(
                people = listOf(joao, pedro),
                tasks = listOf(
                    ActivityTask(
                        title = "Tomar banho primeiro - turno do dia",
                        participantIds = listOf(joao.id, pedro.id)
                    ),
                    ActivityTask(
                        title = "Tomar banho primeiro - turno da noite",
                        participantIds = listOf(pedro.id, joao.id)
                    )
                )
            )
        }
    }
}
