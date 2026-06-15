package com.joaoepedro.tasks.domain

import com.joaoepedro.tasks.data.AppState
import com.joaoepedro.tasks.data.RewardTransaction
import com.joaoepedro.tasks.data.TransactionType
import java.time.LocalDate

object DailyAllowanceService {

    fun computeMissingDeposits(state: AppState, today: LocalDate = LocalDate.now()): List<RewardTransaction> {
        val config = state.dailyAllowance
        val enabledSince = config.enabledSince ?: return emptyList()
        if (config.amountPerDay <= 0) return emptyList()

        return state.people
            .filter { !it.archived }
            .flatMap { person ->
                val personStart = maxOf(enabledSince, person.createdAt)
                val existingDates = state.transactions
                    .filter { it.personId == person.id && it.type == TransactionType.DAILY_ALLOWANCE }
                    .map { it.createdAt }
                    .toSet()

                generateSequence(personStart) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(today) }
                    .filter { it !in existingDates }
                    .map { date ->
                        RewardTransaction(
                            personId = person.id,
                            type = TransactionType.DAILY_ALLOWANCE,
                            amount = config.amountPerDay,
                            reason = "Mesada diária",
                            createdAt = date
                        )
                    }
                    .toList()
            }
    }
}
