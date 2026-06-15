package com.joaoepedro.tasks.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

class AppRepository(context: Context) {
    private val dataFile = File(context.filesDir, "joao-e-pedro-tasks.json")

    fun load(): AppState {
        if (!dataFile.exists()) {
            val seeded = AppState.seeded()
            save(seeded)
            return seeded
        }
        return decode(dataFile.readText())
    }

    fun save(state: AppState) {
        dataFile.writeText(encode(state))
    }

    fun exportText(state: AppState): String = encode(state)

    fun importText(text: String): AppState {
        val imported = decode(text)
        save(imported)
        return imported
    }

    private fun encode(state: AppState): String {
        return JSONObject()
            .put("schemaVersion", 1)
            .put("people", JSONArray(state.people.map { person ->
                JSONObject()
                    .put("id", person.id)
                    .put("name", person.name)
                    .put("archived", person.archived)
                    .put("createdAt", person.createdAt.toString())
                    .put("birthDate", person.birthDate?.toString())
                    .put("schoolYear", person.schoolYear)
                    .put("photoUri", person.photoUri)
            }))
            .put("tasks", JSONArray(state.tasks.map { task ->
                JSONObject()
                    .put("id", task.id)
                    .put("title", task.title)
                    .put("periodicity", task.periodicity.name)
                    .put("startDate", task.startDate.toString())
                    .put("participantIds", JSONArray(task.participantIds))
                    .put("rotationOffset", task.rotationOffset)
                    .put("active", task.active)
            }))
            .put("missions", JSONArray(state.missions.map { mission ->
                JSONObject()
                    .put("id", mission.id)
                    .put("title", mission.title)
                    .put("rewardPersonId", mission.rewardPersonId)
                    .put("rewardAmount", mission.rewardAmount)
                    .put("participantIds", JSONArray(mission.participantIds))
                    .put("phases", JSONArray(mission.phases.map { phase ->
                        JSONObject()
                            .put("id", phase.id)
                            .put("title", phase.title)
                            .put("checked", phase.checked)
                            .put("checkedParticipantIds", JSONArray(phase.checkedParticipantIds))
                    }))
                    .put("completedAt", mission.completedAt?.toString())
                    .put("rewardTransactionId", mission.rewardTransactionId)
                    .put("rewardPaidParticipantIds", JSONArray(mission.rewardPaidParticipantIds))
            }))
            .put("transactions", JSONArray(state.transactions.map { tx ->
                JSONObject()
                    .put("id", tx.id)
                    .put("personId", tx.personId)
                    .put("type", tx.type.name)
                    .put("amount", tx.amount.toString())
                    .put("reason", tx.reason)
                    .put("createdAt", tx.createdAt.toString())
                    .put("reversed", tx.reversed)
            }))
            .put("dailyAllowance", JSONObject()
                .put("amountPerDay", state.dailyAllowance.amountPerDay)
                .put("enabledSince", state.dailyAllowance.enabledSince?.toString())
                .put("lastProcessedDate", state.dailyAllowance.lastProcessedDate?.toString())
            )
            .put("pointValueCents", state.pointValueCents)
            .put("language", state.language)
            .put("biometricEnabled", state.biometricEnabled)
            .toString(2)
    }

    private fun decode(text: String): AppState {
        val root = JSONObject(text)
        val people = root.optJSONArray("people").toList { item ->
            Person(
                id = item.getString("id"),
                name = item.getString("name"),
                archived = item.optBoolean("archived", false),
                createdAt = item.optString("createdAt").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { LocalDate.parse(it) } ?: LocalDate.now(),
                birthDate = item.optString("birthDate").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { LocalDate.parse(it) },
                schoolYear = item.optString("schoolYear").takeIf { it.isNotBlank() && it != "null" },
                photoUri = item.optString("photoUri").takeIf { it.isNotBlank() && it != "null" }
            )
        }
        val tasks = root.optJSONArray("tasks").toList { item ->
            ActivityTask(
                id = item.getString("id"),
                title = item.getString("title"),
                periodicity = Periodicity.valueOf(item.getString("periodicity")),
                startDate = LocalDate.parse(item.getString("startDate")),
                participantIds = item.getJSONArray("participantIds").strings(),
                rotationOffset = item.optInt("rotationOffset", 0),
                active = item.optBoolean("active", true)
            )
        }
        val missions = root.optJSONArray("missions").toList { item ->
            val participantIds = item.optJSONArray("participantIds").strings()
            Mission(
                id = item.getString("id"),
                title = item.getString("title"),
                rewardPersonId = item.optString("rewardPersonId").takeIf { it.isNotBlank() && it != "null" }
                    ?: participantIds.firstOrNull().orEmpty(),
                rewardAmount = item.getInt("rewardAmount"),
                participantIds = participantIds,
                phases = item.optJSONArray("phases").toList { phase ->
                    val checkedParticipantIds = phase.optJSONArray("checkedParticipantIds").strings()
                        .ifEmpty { if (phase.optBoolean("checked", false)) participantIds else emptyList() }
                    MissionPhase(
                        id = phase.getString("id"),
                        title = phase.getString("title"),
                        checked = phase.optBoolean("checked", false),
                        checkedParticipantIds = checkedParticipantIds
                    )
                },
                completedAt = item.optString("completedAt").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { LocalDate.parse(it) },
                rewardTransactionId = item.optString("rewardTransactionId").takeIf { it.isNotBlank() && it != "null" },
                rewardPaidParticipantIds = item.optJSONArray("rewardPaidParticipantIds").strings()
                    .ifEmpty {
                        if (item.optString("rewardTransactionId").isNotBlank() && item.optString("rewardTransactionId") != "null") {
                            participantIds
                        } else {
                            emptyList()
                        }
                    }
            )
        }
        val transactions = root.optJSONArray("transactions").toList { item ->
            RewardTransaction(
                id = item.getString("id"),
                personId = item.getString("personId"),
                type = TransactionType.valueOf(item.getString("type")),
                amount = item.optString("amount", "0").toDoubleOrNull() ?: item.optDouble("amount", 0.0),
                reason = item.getString("reason"),
                createdAt = LocalDate.parse(item.getString("createdAt")),
                reversed = item.optBoolean("reversed", false)
            )
        }
        val dailyAllowanceJson = root.optJSONObject("dailyAllowance")
        val dailyAllowance = if (dailyAllowanceJson != null) {
            DailyAllowanceConfig(
                amountPerDay = dailyAllowanceJson.optInt("amountPerDay", 0),
                enabledSince = dailyAllowanceJson.optString("enabledSince").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { LocalDate.parse(it) },
                lastProcessedDate = dailyAllowanceJson.optString("lastProcessedDate").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { LocalDate.parse(it) }
            )
        } else {
            DailyAllowanceConfig()
        }
        val pointValueCents = root.optInt("pointValueCents", 33)
        val language = root.optString("language", "pt").takeIf { it.isNotBlank() } ?: "pt"
        val biometricEnabled = root.optBoolean("biometricEnabled", false)
        return AppState(people = people, tasks = tasks, missions = missions, transactions = transactions, dailyAllowance = dailyAllowance, pointValueCents = pointValueCents, language = language, biometricEnabled = biometricEnabled)
    }
}

private fun <T> JSONArray?.toList(mapper: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).map { index -> mapper(getJSONObject(index)) }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { index -> getString(index) }
}
