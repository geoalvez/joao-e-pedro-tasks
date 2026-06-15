package com.joaoepedro.tasks.domain

import com.joaoepedro.tasks.data.ActivityTask
import com.joaoepedro.tasks.data.Periodicity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class TaskRotationService {
    fun currentPersonId(task: ActivityTask, today: LocalDate = LocalDate.now()): String? {
        if (!task.active || task.participantIds.isEmpty()) return null
        if (today.isBefore(task.startDate)) return null
        val elapsed = periodsBetween(task.startDate, today, task.periodicity).coerceAtLeast(0)
        val index = Math.floorMod(elapsed.toInt() + task.rotationOffset, task.participantIds.size)
        return task.participantIds[index]
    }

    fun nextChangeDate(task: ActivityTask, today: LocalDate = LocalDate.now()): LocalDate {
        val elapsed = periodsBetween(task.startDate, today, task.periodicity).coerceAtLeast(0)
        return when (task.periodicity) {
            Periodicity.DAILY -> task.startDate.plusDays(elapsed + 1)
            Periodicity.WEEKLY -> task.startDate.plusWeeks(elapsed + 1)
            Periodicity.MONTHLY -> task.startDate.plusMonths(elapsed + 1)
        }
    }

    fun assignmentsBetween(
        task: ActivityTask,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<Pair<LocalDate, String?>> {
        require(!endDate.isBefore(startDate)) { "A data final nao pode ser anterior a inicial." }
        return generateSequence(startDate) { date -> date.plusDays(1) }
            .takeWhile { !it.isAfter(endDate) }
            .map { date -> date to currentPersonId(task, date) }
            .toList()
    }

    fun addParticipantAndMakeCurrent(
        task: ActivityTask,
        personId: String,
        today: LocalDate = LocalDate.now()
    ): ActivityTask {
        if (personId in task.participantIds) return task
        val participants = task.participantIds + personId
        val elapsed = periodsBetween(task.startDate, today, task.periodicity).coerceAtLeast(0).toInt()
        val addedIndex = participants.lastIndex
        val offset = Math.floorMod(addedIndex - elapsed, participants.size)
        return task.copy(participantIds = participants, rotationOffset = offset)
    }

    fun removeParticipant(task: ActivityTask, personId: String): ActivityTask {
        return task.copy(participantIds = task.participantIds.filterNot { it == personId })
    }

    private fun periodsBetween(start: LocalDate, today: LocalDate, periodicity: Periodicity): Long {
        return when (periodicity) {
            Periodicity.DAILY -> ChronoUnit.DAYS.between(start, today)
            Periodicity.WEEKLY -> ChronoUnit.WEEKS.between(start, today)
            Periodicity.MONTHLY -> ChronoUnit.MONTHS.between(start, today)
        }
    }
}
