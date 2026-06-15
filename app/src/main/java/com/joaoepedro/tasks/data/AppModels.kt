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
    val checked: Boolean = false
)

data class Mission(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val rewardPersonId: String,
    val rewardAmount: Int,
    val participantIds: List<String> = emptyList(),
    val phases: List<MissionPhase> = emptyList(),
    val completedAt: LocalDate? = null,
    val rewardTransactionId: String? = null
) {
    fun isReadyToComplete(): Boolean = phases.isNotEmpty() && phases.all { it.checked }
    fun isCompleted(): Boolean = completedAt != null
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

data class AppState(
    val people: List<Person> = emptyList(),
    val tasks: List<ActivityTask> = emptyList(),
    val missions: List<Mission> = emptyList(),
    val transactions: List<RewardTransaction> = emptyList(),
    val dailyAllowance: DailyAllowanceConfig = DailyAllowanceConfig(),
    val pointValueCents: Int = 33
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
