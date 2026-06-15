package com.joaoepedro.tasks.domain

import com.joaoepedro.tasks.data.AppState
import com.joaoepedro.tasks.data.DailyAllowanceConfig
import com.joaoepedro.tasks.data.Person
import com.joaoepedro.tasks.data.RewardTransaction
import com.joaoepedro.tasks.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyAllowanceServiceTest {

    private val today = LocalDate.of(2026, 6, 15)
    private val joao = Person(id = "joao", name = "João", createdAt = LocalDate.of(2026, 6, 1))
    private val pedro = Person(id = "pedro", name = "Pedro", createdAt = LocalDate.of(2026, 6, 1))

    private fun stateWith(
        config: DailyAllowanceConfig,
        people: List<Person> = listOf(joao, pedro),
        transactions: List<RewardTransaction> = emptyList()
    ) = AppState(people = people, dailyAllowance = config, transactions = transactions)

    private fun allowanceTransaction(personId: String, date: LocalDate) = RewardTransaction(
        personId = personId,
        type = TransactionType.DAILY_ALLOWANCE,
        amount = 5,
        reason = "Mesada diária",
        createdAt = date
    )

    // T1 — enabledSince null → nenhuma transação gerada
    @Test
    fun `config desativada nao gera transacoes`() {
        val state = stateWith(DailyAllowanceConfig(amountPerDay = 5, enabledSince = null))
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        assertTrue(result.isEmpty())
    }

    // T2 — amountPerDay = 0 → nenhuma transação gerada mesmo com enabledSince
    @Test
    fun `valor zero nao gera transacoes`() {
        val state = stateWith(DailyAllowanceConfig(amountPerDay = 0, enabledSince = today))
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        assertTrue(result.isEmpty())
    }

    // T3 — config ativa, nenhum depósito ainda → gera para hoje e ambas as pessoas
    @Test
    fun `config ativa sem depositos anteriores gera transacao de hoje para cada pessoa`() {
        val state = stateWith(DailyAllowanceConfig(amountPerDay = 5, enabledSince = today))
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        assertEquals(2, result.size)
        assertTrue(result.any { it.personId == "joao" && it.createdAt == today })
        assertTrue(result.any { it.personId == "pedro" && it.createdAt == today })
    }

    // T4 — depósito de hoje já existe → idempotência, zero novas transações
    @Test
    fun `deposito de hoje ja existente nao gera duplicata`() {
        val existing = listOf(
            allowanceTransaction("joao", today),
            allowanceTransaction("pedro", today)
        )
        val state = stateWith(DailyAllowanceConfig(amountPerDay = 5, enabledSince = today), transactions = existing)
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        assertTrue(result.isEmpty())
    }

    // T5 — app fechado 3 dias → backfill de 3 transações por pessoa
    @Test
    fun `app fechado 3 dias gera backfill para cada dia`() {
        val start = today.minusDays(2)
        val state = stateWith(DailyAllowanceConfig(amountPerDay = 5, enabledSince = start))
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        // 3 dias × 2 pessoas = 6
        assertEquals(6, result.size)
        val joaoDates = result.filter { it.personId == "joao" }.map { it.createdAt }.toSet()
        assertEquals(setOf(start, start.plusDays(1), today), joaoDates)
    }

    // T6 — pessoa arquivada é ignorada mesmo com config ativa
    @Test
    fun `pessoa arquivada e ignorada`() {
        val pedroArchived = pedro.copy(archived = true)
        val state = stateWith(
            DailyAllowanceConfig(amountPerDay = 5, enabledSince = today),
            people = listOf(joao, pedroArchived)
        )
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        assertTrue(result.all { it.personId == "joao" })
        assertEquals(1, result.size)
    }

    // T7 — enabledSince = amanhã → nenhum depósito gerado ainda
    @Test
    fun `config com enabledSince amanha nao gera deposito hoje`() {
        val state = stateWith(DailyAllowanceConfig(amountPerDay = 5, enabledSince = today.plusDays(1)))
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        assertTrue(result.isEmpty())
    }

    // T8 — múltiplas pessoas têm depósitos independentes
    @Test
    fun `multiplas pessoas recebem depositos independentemente`() {
        val existingJoao = listOf(allowanceTransaction("joao", today))
        val state = stateWith(DailyAllowanceConfig(amountPerDay = 5, enabledSince = today), transactions = existingJoao)
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        assertEquals(1, result.size)
        assertEquals("pedro", result.first().personId)
    }

    // T9 — pessoa adicionada depois de enabledSince: backfill começa em person.createdAt
    @Test
    fun `pessoa adicionada depois do enabledSince faz backfill desde createdAt`() {
        val enabledSince = LocalDate.of(2026, 6, 1)
        val newPerson = Person(id = "nova", name = "Nova", createdAt = today.minusDays(1))
        val state = stateWith(
            DailyAllowanceConfig(amountPerDay = 5, enabledSince = enabledSince),
            people = listOf(newPerson)
        )
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        val dates = result.map { it.createdAt }.toSet()
        // backfill só desde createdAt (hoje-1) até hoje = 2 dias
        assertEquals(2, result.size)
        assertTrue(dates.contains(today.minusDays(1)))
        assertTrue(dates.contains(today))
    }

    // T10 — processAllowances chamado duas vezes no mesmo dia → idempotência total
    @Test
    fun `chamada dupla no mesmo dia nao gera duplicatas`() {
        val state = stateWith(DailyAllowanceConfig(amountPerDay = 5, enabledSince = today))
        val firstRun = DailyAllowanceService.computeMissingDeposits(state, today)
        val stateAfterFirst = state.copy(transactions = state.transactions + firstRun)
        val secondRun = DailyAllowanceService.computeMissingDeposits(stateAfterFirst, today)
        assertTrue(secondRun.isEmpty())
    }

    // T11 — transações geradas têm type = DAILY_ALLOWANCE
    @Test
    fun `transacoes geradas tem tipo DAILY_ALLOWANCE`() {
        val state = stateWith(DailyAllowanceConfig(amountPerDay = 5, enabledSince = today))
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        assertTrue(result.all { it.type == TransactionType.DAILY_ALLOWANCE })
    }

    // T12 — transações têm o amount correto da config
    @Test
    fun `transacoes geradas tem o amount correto`() {
        val state = stateWith(DailyAllowanceConfig(amountPerDay = 10, enabledSince = today))
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        assertTrue(result.all { it.amount == 10 })
    }

    // T13 — nenhuma pessoa ativa → nenhuma transação
    @Test
    fun `sem pessoas ativas nao gera transacoes`() {
        val state = stateWith(
            DailyAllowanceConfig(amountPerDay = 5, enabledSince = today),
            people = listOf(joao.copy(archived = true), pedro.copy(archived = true))
        )
        val result = DailyAllowanceService.computeMissingDeposits(state, today)
        assertTrue(result.isEmpty())
    }
}
