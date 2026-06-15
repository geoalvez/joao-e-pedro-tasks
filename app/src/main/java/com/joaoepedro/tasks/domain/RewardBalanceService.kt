package com.joaoepedro.tasks.domain

import com.joaoepedro.tasks.data.RewardTransaction

class RewardBalanceService {
    fun balanceFor(personId: String, transactions: List<RewardTransaction>): Int {
        return transactions
            .filter { it.personId == personId }
            .sumOf { it.amount * it.type.sign }
    }

    fun validate(amount: Int, reason: String) {
        require(amount > 0) { "O valor precisa ser maior que zero." }
        require(reason.isNotBlank()) { "Informe um motivo." }
    }
}
