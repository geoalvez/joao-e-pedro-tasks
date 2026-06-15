package com.joaoepedro.tasks

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.joaoepedro.tasks.data.ActivityTask
import com.joaoepedro.tasks.data.AppRepository
import com.joaoepedro.tasks.data.AppState
import com.joaoepedro.tasks.data.Mission
import com.joaoepedro.tasks.data.MissionPhase
import com.joaoepedro.tasks.data.Periodicity
import com.joaoepedro.tasks.data.Person
import com.joaoepedro.tasks.data.RewardTransaction
import com.joaoepedro.tasks.data.TransactionType
import com.joaoepedro.tasks.domain.DailyAllowanceService
import com.joaoepedro.tasks.domain.RewardBalanceService
import com.joaoepedro.tasks.domain.TaskRotationService
import java.time.LocalDate
import java.time.YearMonth

class MainActivity : Activity() {
    private lateinit var repository: AppRepository
    private var state: AppState = AppState.seeded()
    private val rotation = TaskRotationService()
    private val balances = RewardBalanceService()
    private lateinit var root: LinearLayout
    private var selectedTab = Tab.Today

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = COLOR_ORANGE_PRI
        @Suppress("DEPRECATION")
        window.navigationBarColor = COLOR_NAV_BG
        repository = AppRepository(this)
        state = repository.load()
        showSplash()
        Handler(Looper.getMainLooper()).postDelayed({ renderApp() }, 1200)
    }

    @Deprecated("Deprecated Android callback kept to avoid extra dependencies.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            EXPORT_REQUEST -> exportTo(data.data!!)
            IMPORT_REQUEST -> importFrom(data.data!!)
        }
    }

    private fun showSplash() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_ORANGE_PRI)
        }
        container.addView(ImageView(this).apply {
            setImageResource(R.drawable.jp_games_capa_2)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        container.addView(text("João e Pedro Tasks", 24, true).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        setContentView(container)
        applySafeArea(container, dp(20), dp(20), dp(20), dp(20))
    }

    private fun renderApp() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PAGE)
        }
        setContentView(root)
        applySafeArea(root, 0, 0, 0, 0)
        processDailyAllowances()
        render()
    }

    private fun processDailyAllowances() {
        val newTransactions = DailyAllowanceService.computeMissingDeposits(state)
        if (newTransactions.isNotEmpty()) {
            state = state.copy(transactions = state.transactions + newTransactions)
            repository.save(state)
        }
    }

    private fun render() {
        root.removeAllViews()
        root.setBackgroundColor(COLOR_PAGE)
        root.addView(header())
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        when (selectedTab) {
            Tab.Today -> renderToday(content)
            Tab.People -> renderPeople(content)
            Tab.Tasks -> renderTasks(content)
            Tab.Missions -> renderMissions(content)
            Tab.Rewards -> renderRewards(content)
            Tab.Data -> renderData(content)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomNav())
    }

    private fun header(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_ORANGE_PRI)
            setPadding(dp(20), dp(20), dp(20), dp(16))
            addView(text("João & Pedro", 26, true).apply {
                setTextColor(Color.WHITE)
            })
            addView(text("Tarefas & Recompensas ⭐", 14, false).apply {
                setTextColor(Color.argb(210, 255, 255, 255))
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun bottomNav(): View {
        val navItems = listOf(
            Tab.Today    to ("🏠️" to "Início"),
            Tab.People   to ("👨‍👩‍👦️" to "Família"),
            Tab.Tasks    to ("✅️" to "Tarefas"),
            Tab.Missions to ("🎯️" to "Missões"),
            Tab.Rewards  to ("🎁️" to "Saldo"),
            Tab.Data     to ("⚙️" to "Config")
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(COLOR_NAV_BG)
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, dp(72))
            navItems.forEach { (tab, meta) ->
                val (icon, label) = meta
                val isSelected = selectedTab == tab
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    if (isSelected) setBackgroundColor(COLOR_ORANGE_SOFT)
                    setOnClickListener { selectedTab = tab; render() }
                    addView(TextView(context).apply {
                        text = icon
                        textSize = 24f
                        gravity = Gravity.CENTER
                        setPadding(0, dp(6), 0, 0)
                    })
                    addView(TextView(context).apply {
                        text = label
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(if (isSelected) COLOR_ORANGE_PRI else COLOR_MUTED)
                        typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setPadding(0, dp(2), 0, dp(6))
                    })
                })
            }
        }
    }

    private fun renderToday(content: LinearLayout) {
        content.addView(sectionTitle("Hoje"))
        val activeTasks = state.tasks.filter { it.active }
        if (activeTasks.isEmpty()) {
            content.addView(empty("Nenhuma atividade ativa ainda."))
        }
        activeTasks.forEach { task ->
            val currentId = rotation.currentPersonId(task, LocalDate.now())
            val person = state.people.firstOrNull { it.id == currentId }?.name ?: "Sem pessoa"
            val todayCard = card {
                addView(text(task.title, 17, true))
                addView(text("Agora", 12, true).apply {
                    setTextColor(COLOR_MUTED)
                    setPadding(0, dp(12), 0, 0)
                })
                addView(text(person, 32, true).apply { setTextColor(COLOR_ACCENT) })
                addView(text("Periodicidade: ${task.periodicity.label}", 14, false).apply {
                    setPadding(0, dp(6), 0, 0)
                })
                addView(text("Próxima alternância: ${this@MainActivity.rotation.nextChangeDate(task)}", 14, false).apply {
                    setTextColor(COLOR_MUTED)
                })
                if (task.participantIds.size > 1) {
                    addView(secondaryButton("Trocar próximas tarefas") {
                        invertFutureAssignments(task.id)
                    })
                }
            }
            todayCard.setOnClickListener { showTaskCalendarDialog(task) }
            content.addView(todayCard)
        }
    }

    private fun renderPeople(content: LinearLayout) {
        content.addView(rowTitle("Família") { showPersonDialog() })
        val people = state.people.filterNot { it.archived }
        if (people.isEmpty()) {
            content.addView(empty("Nenhuma pessoa cadastrada."))
            return
        }
        val personColors = listOf(COLOR_BLUE, COLOR_ORANGE_PRI, COLOR_GREEN, COLOR_CORAL)
        people.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(14)
                }
            }
            pair.forEachIndexed { idx, person ->
                val colorIndex = (people.indexOf(person)) % personColors.size
                val avatarColor = personColors[colorIndex]
                val balance = balances.balanceFor(person.id, state.transactions)
                val personCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = roundedDrawable(Color.WHITE, 20f, 2, COLOR_CARD_STROKE)
                    elevation = dp(4).toFloat()
                    setPadding(dp(12), dp(20), dp(12), dp(16))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginStart = if (idx == 0) 0 else dp(8)
                        marginEnd = if (idx == pair.lastIndex) 0 else dp(8)
                    }
                    addView(TextView(context).apply {
                        text = person.name.first().toString().uppercase()
                        textSize = 28f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(avatarColor)
                        }
                        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            bottomMargin = dp(12)
                        }
                    })
                    addView(text(person.name, 17, true).apply {
                        gravity = Gravity.CENTER
                    })
                    addView(text("⭐ $balance pts", 15, false).apply {
                        gravity = Gravity.CENTER
                        setTextColor(COLOR_ORANGE_PRI)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, dp(4), 0, 0)
                    })
                }
                row.addView(personCard)
            }
            if (pair.size == 1) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
            }
            content.addView(row)
        }
    }

    private fun renderTasks(content: LinearLayout) {
        content.addView(rowTitle("Atividades") { showTaskDialog() })
        state.tasks.forEach { task ->
            val names = task.participantIds.mapNotNull { id -> state.people.firstOrNull { it.id == id }?.name }
            val taskCard = card {
                addView(text(task.title, 18, true))
                addView(text("Ordem: ${names.joinToString(" -> ")}", 14, false))
                val currentId = this@MainActivity.rotation.currentPersonId(task, LocalDate.now())
                val currentName = state.people.firstOrNull { it.id == currentId }?.name ?: "Sem pessoa"
                addView(text("Proximo evento: $currentName", 14, true).apply {
                    setTextColor(COLOR_ACCENT)
                    setPadding(0, dp(6), 0, 0)
                })
                if (task.rotationOffset != 0) {
                    addView(text("Ajuste manual aplicado: ${task.rotationOffset}", 14, false).apply {
                        setTextColor(COLOR_MUTED)
                    })
                }
                addView(text("${task.periodicity.label} desde ${task.startDate}", 14, false).apply {
                    setTextColor(COLOR_MUTED)
                })
                addView(secondaryButton("Associar pessoa") { showAddTaskParticipantDialog(task) })
                if (task.participantIds.isNotEmpty()) {
                    addView(secondaryButton("Desassociar pessoa") { showRemoveTaskParticipantDialog(task) })
                }
            }
            taskCard.setOnClickListener { showTaskCalendarDialog(task) }
            content.addView(taskCard)
        }
    }

    private fun renderMissions(content: LinearLayout) {
        content.addView(rowTitle("Missoes") { showMissionDialog() })
        if (state.missions.isEmpty()) {
            content.addView(empty("Nenhuma missao cadastrada."))
        }
        state.missions.forEach { mission ->
            val participants = mission.participantIds
                .mapNotNull { id -> state.people.firstOrNull { it.id == id }?.name }
                .joinToString(", ")
                .ifBlank { "Sem participantes" }
            content.addView(card {
                addView(text(mission.title, 18, true))
                addView(text("Recompensa: ${mission.rewardAmount} pontos para cada participante", 14, false).apply {
                    setTextColor(COLOR_ACCENT)
                })
                addView(text("Participantes: $participants", 14, false).apply {
                    setTextColor(COLOR_MUTED)
                })
                if (mission.phases.isEmpty()) {
                    addView(text("Sem fases ainda.", 14, false).apply { setTextColor(COLOR_MUTED) })
                }
                mission.phases.forEach { phase ->
                    addView(CheckBox(context).apply {
                        text = phase.title
                        textSize = 14f
                        setTextColor(COLOR_INK)
                        isChecked = phase.checked
                        isEnabled = !mission.isCompleted()
                        setOnCheckedChangeListener { _, checked ->
                            toggleMissionPhase(mission.id, phase.id, checked)
                        }
                    })
                }
                val status = when {
                    mission.isCompleted() -> "Concluida"
                    mission.isReadyToComplete() -> "Pronta para concluir"
                    else -> "Em andamento"
                }
                addView(text(status, 14, true).apply {
                    setTextColor(if (mission.isCompleted()) COLOR_ACCENT else COLOR_MUTED)
                    setPadding(0, dp(8), 0, 0)
                })
                if (!mission.isCompleted()) {
                    addView(secondaryButton("Adicionar fase") { showMissionPhaseDialog(mission) })
                    addView(secondaryButton("Associar pessoa") { showAddMissionParticipantDialog(mission) })
                    if (mission.participantIds.isNotEmpty()) {
                        addView(secondaryButton("Desassociar pessoa") { showRemoveMissionParticipantDialog(mission) })
                    }
                    if (mission.isReadyToComplete()) {
                        addView(primaryButton("Concluir e creditar") { completeMission(mission.id) })
                    }
                }
            })
        }
    }

    private fun renderRewards(content: LinearLayout) {
        content.addView(rowTitle("Recompensas") { showTransactionTypeChooser() })
        state.people.filterNot { it.archived }.forEach { person ->
            val balance = balances.balanceFor(person.id, state.transactions)
            val rewardCard = card {
                addView(text(person.name, 18, true))
                addView(text("Saldo: $balance pontos", 22, true).apply { setTextColor(COLOR_ACCENT) })
                state.transactions
                    .filter { it.personId == person.id }
                    .sortedByDescending { it.createdAt }
                    .take(5)
                    .forEach { tx ->
                        val signed = tx.amount * tx.type.sign
                        addView(text("${tx.createdAt}  ${tx.type.label}: ${signed.formatSigned()} - ${tx.reason}", 13, false))
                    }
                addView(text("Toque para ver o extrato completo", 12, true).apply {
                    setTextColor(COLOR_BLUE)
                    setPadding(0, dp(10), 0, 0)
                })
            }
            rewardCard.setOnClickListener { showPersonStatementDialog(person) }
            content.addView(rewardCard)
        }
    }

    private fun renderData(content: LinearLayout) {
        content.addView(sectionTitle("Ajuda"))
        val config = state.dailyAllowance
        content.addView(card {
            addView(text("Mesada diária", 18, true))
            val statusText = if (config.enabledSince != null)
                "Ativa desde ${config.enabledSince} · ${config.amountPerDay} pontos/dia"
            else
                "Desativada"
            addView(text(statusText, 14, false).apply { setTextColor(COLOR_MUTED) })
            addView(secondaryButton("Configurar") { showDailyAllowanceDialog() })
        })
        content.addView(card {
            addView(text("Backup local", 18, true))
            addView(text("Exporte um arquivo JSON e copie para outro aparelho. Ao importar, os dados atuais são substituídos pelo arquivo escolhido.", 14, false))
            addView(stackedButton("Gerar arquivo de backup", true) { chooseExportFile() })
            addView(stackedButton("Restaurar de arquivo", false) { chooseImportFile() })
        })
        content.addView(card {
            addView(text("Resumo", 18, true))
            addView(text("${state.people.size} pessoas", 14, false))
            addView(text("${state.tasks.size} atividades", 14, false))
            addView(text("${state.transactions.size} movimentações", 14, false))
        })
        content.addView(developerCard())
    }

    private fun showDailyAllowanceDialog() {
        val config = state.dailyAllowance
        val layout = dialogLayout()
        layout.addView(label("Valor por dia (pontos)"))
        val amountInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "0"
            setText(if (config.amountPerDay > 0) config.amountPerDay.toString() else "")
            setSingleLine(true)
        }
        layout.addView(amountInput)
        val builder = AlertDialog.Builder(this)
            .setTitle("Mesada diária")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(if (config.enabledSince != null) "Salvar" else "Ativar") { _, _ ->
                val amount = amountInput.text.toString().trim().toIntOrNull() ?: 0
                if (amount <= 0) {
                    toast("Informe um valor maior que zero.")
                    return@setPositiveButton
                }
                val newConfig = config.copy(
                    amountPerDay = amount,
                    enabledSince = config.enabledSince ?: LocalDate.now()
                )
                state = state.copy(dailyAllowance = newConfig)
                processDailyAllowances()
                repository.save(state)
                render()
            }
        if (config.enabledSince != null) {
            builder.setNeutralButton("Desativar") { _, _ ->
                state = state.copy(dailyAllowance = config.copy(enabledSince = null))
                repository.save(state)
                render()
            }
        }
        builder.show()
    }

    private fun developerCard(): View = card {
        addView(text("Powered by", 18, true))
        addView(text("GG Soft", 22, true).apply {
            setTextColor(COLOR_BLUE)
            setPadding(0, dp(6), 0, 0)
        })
        addView(text("www.geoalvez.com", 14, false).apply {
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.instagram_logo)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                setMargins(0, 0, dp(8), 0)
            })
            addView(text("Instagram @geoalvez", 14, true).apply {
                setTextColor(COLOR_BLUE)
            })
            setOnClickListener { openInstagramProfile() }
        })
    }

    private fun openInstagramProfile() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/geoalvez/"))
        startActivity(intent)
    }

    private fun showTaskCalendarDialog(task: ActivityTask) {
        val month = YearMonth.from(LocalDate.now())
        val firstDay = month.atDay(1)
        val lastDay = month.atEndOfMonth()
        val assignments = rotation.assignmentsBetween(task, firstDay, lastDay).toMap()
        val layout = dialogLayout()
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedDrawable(COLOR_BLUE_SOFT, 18f, 1, COLOR_BLUE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text("Calendario da atividade", 13, true).apply {
                gravity = Gravity.CENTER
                setTextColor(COLOR_BLUE)
            })
            addView(text("${month.monthValue}/${month.year}", 28, true).apply {
                gravity = Gravity.CENTER
                setTextColor(COLOR_INK)
            })
            addView(text("Comeca em ${task.startDate}", 12, false).apply {
                gravity = Gravity.CENTER
                setTextColor(COLOR_MUTED)
            })
        }, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(14))
        })
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 7
        }
        listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sab", "Dom").forEach { day ->
            grid.addView(text(day, 11, true).apply {
                gravity = Gravity.CENTER
                setTextColor(COLOR_MUTED)
            }, GridLayout.LayoutParams().apply {
                width = dp(42)
                height = dp(28)
            })
        }
        repeat(firstDay.dayOfWeek.value - 1) {
            grid.addView(View(this), GridLayout.LayoutParams().apply {
                width = dp(42)
                height = dp(62)
            })
        }
        (1..month.lengthOfMonth()).forEach { day ->
            val date = month.atDay(day)
            val personId = assignments[date]
            val personName = if (date.isBefore(task.startDate)) {
                ""
            } else {
                state.people.firstOrNull { it.id == personId }?.name ?: "Sem pessoa"
            }
            grid.addView(calendarDayCell(date, personName, date.isBefore(task.startDate)), GridLayout.LayoutParams().apply {
                width = dp(44)
                height = dp(68)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        layout.addView(grid)
        AlertDialog.Builder(this)
            .setTitle(task.title)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun calendarDayCell(date: LocalDate, personName: String, beforeStart: Boolean): View {
        val isToday = date == LocalDate.now()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedDrawable(
                when {
                    beforeStart -> COLOR_DISABLED
                    isToday -> COLOR_CORAL_SOFT
                    personName.isNotBlank() -> COLOR_BLUE_SOFT
                    else -> Color.WHITE
                },
                14f,
                1,
                when {
                    beforeStart -> COLOR_DISABLED_STROKE
                    isToday -> COLOR_CORAL
                    personName.isNotBlank() -> COLOR_BLUE
                    else -> COLOR_BORDER
                }
            )
            setPadding(dp(2), dp(4), dp(2), dp(4))
            addView(text(date.dayOfMonth.toString(), 13, true).apply {
                gravity = Gravity.CENTER
                setTextColor(if (beforeStart) COLOR_MUTED else COLOR_INK)
            })
            addView(text(if (beforeStart) "--" else personName.take(8), 10, true).apply {
                gravity = Gravity.CENTER
                setTextColor(
                    when {
                        beforeStart -> COLOR_MUTED
                        isToday -> COLOR_CORAL
                        else -> COLOR_BLUE
                    }
                )
                maxLines = 2
            })
        }
    }

    private fun showPersonDialog() {
        val layout = dialogLayout()
        val nameInput = edit("Nome da pessoa", "")
        layout.addView(nameInput)
        AlertDialog.Builder(this)
            .setTitle("Nova pessoa")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    toast("Informe um nome.")
                    return@setPositiveButton
                }
                persist(state.copy(people = state.people + Person(name = name)))
            }
            .show()
    }

    private fun showTaskDialog() {
        val layout = dialogLayout()
        val titleInput = edit("Nome da atividade", "Tomar banho primeiro - turno do dia")
        val periodicitySpinner = spinner(Periodicity.entries.map { it.label })
        layout.addView(titleInput)
        layout.addView(label("Periodicidade"))
        layout.addView(periodicitySpinner)
        AlertDialog.Builder(this)
            .setTitle("Nova atividade")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isBlank()) {
                    toast("Informe um nome.")
                    return@setPositiveButton
                }
                val participants = state.people.filterNot { it.archived }.take(2).map { it.id }
                if (participants.size < 2) {
                    toast("Cadastre pelo menos duas pessoas.")
                    return@setPositiveButton
                }
                val task = ActivityTask(
                    title = title,
                    periodicity = Periodicity.entries[periodicitySpinner.selectedItemPosition],
                    participantIds = participants
                )
                persist(state.copy(tasks = state.tasks + task))
            }
            .show()
    }

    private fun showAddTaskParticipantDialog(task: ActivityTask) {
        val candidates = state.people.filterNot { it.archived || it.id in task.participantIds }
        if (candidates.isEmpty()) {
            toast("Nao ha pessoas disponiveis para associar.")
            return
        }
        val personSpinner = spinner(candidates.map { it.name })
        val layout = dialogLayout().apply {
            addView(label("Pessoa"))
            addView(personSpinner)
        }
        AlertDialog.Builder(this)
            .setTitle("Associar pessoa")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Associar") { _, _ ->
                val person = candidates[personSpinner.selectedItemPosition]
                val updatedTasks = state.tasks.map {
                    if (it.id == task.id) rotation.addParticipantAndMakeCurrent(it, person.id) else it
                }
                persist(state.copy(tasks = updatedTasks))
                toast("${person.name} sera o proximo evento.")
            }
            .show()
    }

    private fun showRemoveTaskParticipantDialog(task: ActivityTask) {
        val participants = task.participantIds.mapNotNull { id -> state.people.firstOrNull { it.id == id } }
        if (participants.isEmpty()) return
        val personSpinner = spinner(participants.map { it.name })
        val layout = dialogLayout().apply {
            addView(label("Pessoa"))
            addView(personSpinner)
        }
        AlertDialog.Builder(this)
            .setTitle("Desassociar pessoa")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Desassociar") { _, _ ->
                val person = participants[personSpinner.selectedItemPosition]
                val updatedTasks = state.tasks.map {
                    if (it.id == task.id) rotation.removeParticipant(it, person.id) else it
                }
                persist(state.copy(tasks = updatedTasks))
            }
            .show()
    }

    private fun showMissionDialog() {
        val people = state.people.filterNot { it.archived }
        if (people.isEmpty()) {
            toast("Cadastre uma pessoa primeiro.")
            return
        }
        val layout = friendlyDialogLayout(
            title = "Nova missao",
            subtitle = "Defina participantes, recompensa e etapas."
        )
        val titleInput = edit("Nome da missao", "")
        val rewardInput = edit("Valor em pontos por participante", "").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val phaseInput = edit("Nome da etapa", "")
        val phaseCountInput = edit("Quantidade de etapas", "").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val phases = mutableListOf<MissionPhase>()
        val phaseList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val errorText = text("", 13, true).apply {
            setTextColor(COLOR_CORAL)
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(4))
        }
        fun showMissionError(message: String) {
            errorText.text = message
            errorText.visibility = View.VISIBLE
        }
        fun clearMissionError() {
            errorText.text = ""
            errorText.visibility = View.GONE
        }
        fun refreshPhaseList() {
            phaseList.removeAllViews()
            if (phases.isEmpty()) {
                phaseList.addView(text("Nenhuma etapa adicionada.", 13, false).apply {
                    setTextColor(COLOR_MUTED)
                    setPadding(0, dp(6), 0, dp(6))
                })
            } else {
                phases.forEachIndexed { index, phase ->
                    phaseList.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = roundedDrawable(COLOR_BLUE_SOFT, 14f, 1, COLOR_BLUE)
                        setPadding(dp(10), dp(8), dp(8), dp(8))
                        addView(text("${index + 1}. ${phase.title}", 14, true), LinearLayout.LayoutParams(0, -2, 1f))
                        addView(text("Remover", 12, true).apply {
                            gravity = Gravity.CENTER
                            setTextColor(COLOR_CORAL)
                            setOnClickListener {
                                phases.removeAt(index)
                                refreshPhaseList()
                            }
                        }, LinearLayout.LayoutParams(dp(78), dp(34)))
                    }, LinearLayout.LayoutParams(-1, -2).apply {
                        setMargins(0, 0, 0, dp(8))
                    })
                }
            }
        }
        val participantChecks = people.map { person ->
            CheckBox(this).apply {
                text = person.name
                textSize = 14f
                setTextColor(COLOR_INK)
                isChecked = true
            }
        }
        layout.addView(label("Dados da missao"))
        layout.addView(titleInput)
        layout.addView(rewardInput)
        layout.addView(label("Participantes que recebem"))
        participantChecks.forEach { layout.addView(it) }
        layout.addView(label("Etapas"))
        layout.addView(phaseInput)
        layout.addView(secondaryButton("Adicionar etapa") {
            val title = phaseInput.text.toString().trim()
            if (title.isBlank()) {
                showMissionError("Informe o nome da etapa antes de adicionar.")
                return@secondaryButton
            }
            clearMissionError()
            phases.add(MissionPhase(title = title))
            phaseInput.setText("")
            refreshPhaseList()
        })
        layout.addView(text("Ou gere automaticamente", 13, true).apply {
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(14), 0, dp(4))
        })
        layout.addView(phaseCountInput)
        layout.addView(secondaryButton("Gerar etapas") {
            val count = phaseCountInput.text.toString().toIntOrNull() ?: 0
            if (count <= 0) {
                showMissionError("Informe uma quantidade de etapas maior que zero.")
                return@secondaryButton
            }
            clearMissionError()
            phases.clear()
            phases.addAll((1..count).map { MissionPhase(title = "Etapa $it/$count") })
            refreshPhaseList()
        })
        refreshPhaseList()
        layout.addView(phaseList)
        layout.addView(errorText)
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text.toString().trim()
                val reward = rewardInput.text.toString().toIntOrNull() ?: 0
                val selectedParticipants = people
                    .zip(participantChecks)
                    .filter { it.second.isChecked }
                    .map { it.first.id }
                if (title.isBlank()) {
                    showMissionError("Informe o nome da missao.")
                    return@setOnClickListener
                }
                if (reward <= 0) {
                    showMissionError("Informe um valor em pontos maior que zero.")
                    return@setOnClickListener
                }
                if (selectedParticipants.isEmpty()) {
                    showMissionError("Selecione pelo menos um participante.")
                    return@setOnClickListener
                }
                if (phases.isEmpty()) {
                    showMissionError("Adicione pelo menos uma etapa.")
                    return@setOnClickListener
                }
                val mission = Mission(
                    title = title,
                    rewardPersonId = selectedParticipants.first(),
                    rewardAmount = reward,
                    participantIds = selectedParticipants,
                    phases = phases.toList()
                )
                persist(state.copy(missions = state.missions + mission))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showMissionPhaseDialog(mission: Mission) {
        val layout = dialogLayout()
        val titleInput = edit("Nome da fase", "")
        layout.addView(titleInput)
        AlertDialog.Builder(this)
            .setTitle("Nova fase")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isBlank()) {
                    toast("Informe um nome.")
                    return@setPositiveButton
                }
                val updatedMissions = state.missions.map {
                    if (it.id == mission.id) it.copy(phases = it.phases + MissionPhase(title = title)) else it
                }
                persist(state.copy(missions = updatedMissions))
            }
            .show()
    }

    private fun showAddMissionParticipantDialog(mission: Mission) {
        val candidates = state.people.filterNot { it.archived || it.id in mission.participantIds }
        if (candidates.isEmpty()) {
            toast("Nao ha pessoas disponiveis para associar.")
            return
        }
        val personSpinner = spinner(candidates.map { it.name })
        val layout = dialogLayout().apply {
            addView(label("Pessoa"))
            addView(personSpinner)
        }
        AlertDialog.Builder(this)
            .setTitle("Associar pessoa")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Associar") { _, _ ->
                val person = candidates[personSpinner.selectedItemPosition]
                val updatedMissions = state.missions.map {
                    if (it.id == mission.id) it.copy(participantIds = it.participantIds + person.id) else it
                }
                persist(state.copy(missions = updatedMissions))
            }
            .show()
    }

    private fun showRemoveMissionParticipantDialog(mission: Mission) {
        val participants = mission.participantIds.mapNotNull { id -> state.people.firstOrNull { it.id == id } }
        if (participants.isEmpty()) return
        val personSpinner = spinner(participants.map { it.name })
        val layout = dialogLayout().apply {
            addView(label("Pessoa"))
            addView(personSpinner)
        }
        AlertDialog.Builder(this)
            .setTitle("Desassociar pessoa")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Desassociar") { _, _ ->
                val person = participants[personSpinner.selectedItemPosition]
                val updatedMissions = state.missions.map {
                    if (it.id == mission.id) it.copy(participantIds = it.participantIds.filterNot { id -> id == person.id }) else it
                }
                persist(state.copy(missions = updatedMissions))
            }
            .show()
    }

    private fun showTransactionTypeChooser() {
        AlertDialog.Builder(this)
            .setTitle("Movimentar saldo")
            .setItems(arrayOf("Adicionar saldo", "Executar saque", "Remover por penalidade")) { _, which ->
                when (which) {
                    0 -> showTransactionDialog(TransactionType.DEPOSIT)
                    1 -> showTransactionDialog(TransactionType.WITHDRAW)
                    else -> showTransactionDialog(TransactionType.PENALTY)
                }
            }
            .show()
    }

    private fun showTransactionDialog(
        defaultType: TransactionType = TransactionType.DEPOSIT,
        defaultPersonId: String? = null
    ) {
        val people = state.people.filterNot { it.archived }
        if (people.isEmpty()) {
            toast("Cadastre uma pessoa primeiro.")
            return
        }
        val layout = dialogLayout()
        val personSpinner = spinner(people.map { it.name })
        val typeSpinner = spinner(TransactionType.entries.map { it.label })
        val amountInput = edit("Valor em pontos", "10").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val reasonInput = when (defaultType) {
            TransactionType.PENALTY -> edit("Penalidade por...", "")
            TransactionType.WITHDRAW -> edit("Motivo do saque", "")
            TransactionType.DEPOSIT -> edit("Motivo", "Ajudou em casa")
            TransactionType.DAILY_ALLOWANCE -> edit("Motivo", "Mesada diária")
        }
        defaultPersonId?.let { id ->
            val personIndex = people.indexOfFirst { it.id == id }
            if (personIndex >= 0) personSpinner.setSelection(personIndex)
        }
        typeSpinner.setSelection(TransactionType.entries.indexOf(defaultType).coerceAtLeast(0))
        layout.addView(label("Pessoa"))
        layout.addView(personSpinner)
        layout.addView(label("Tipo"))
        layout.addView(typeSpinner)
        layout.addView(amountInput)
        layout.addView(reasonInput)
        AlertDialog.Builder(this)
            .setTitle("Nova movimentação")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val amount = amountInput.text.toString().toIntOrNull() ?: 0
                val reason = reasonInput.text.toString().trim()
                try {
                    balances.validate(amount, reason)
                } catch (error: IllegalArgumentException) {
                    toast(error.message ?: "Dados invalidos.")
                    return@setPositiveButton
                }
                val tx = RewardTransaction(
                    personId = people[personSpinner.selectedItemPosition].id,
                    type = TransactionType.entries[typeSpinner.selectedItemPosition],
                    amount = amount,
                    reason = reason
                )
                persist(state.copy(transactions = state.transactions + tx))
            }
            .show()
    }

    private fun showPersonStatementDialog(person: Person) {
        val transactions = state.transactions
            .filter { it.personId == person.id }
            .sortedWith(compareByDescending<RewardTransaction> { it.createdAt }.thenByDescending { it.id })
        val balance = balances.balanceFor(person.id, state.transactions)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            background = roundedDrawable(COLOR_PANEL, 20f)
        }
        layout.addView(text("Extrato", 26, true).apply {
            setTextColor(COLOR_INK)
        })
        layout.addView(text(person.name, 14, true).apply {
            setTextColor(COLOR_MUTED)
            setPadding(0, 0, 0, dp(12))
        })
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(Color.WHITE, 16f, 1, COLOR_BORDER)
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text("Saldo atual", 13, true).apply {
                setTextColor(COLOR_MUTED)
            })
            addView(text("$balance pontos", 28, true).apply {
                setTextColor(if (balance >= 0) COLOR_GREEN else COLOR_RED)
            })
        }, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(14))
        })
        layout.addView(text("Movimentacoes", 17, true).apply {
            setPadding(0, 0, 0, dp(8))
        })
        if (transactions.isEmpty()) {
            layout.addView(text("Nenhuma movimentacao ainda", 15, false).apply {
                gravity = Gravity.CENTER
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(22), 0, dp(22))
            })
        } else {
            transactions.forEach { tx ->
                val signed = tx.amount * tx.type.sign
                layout.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedDrawable(Color.WHITE, 14f, 1, if (signed >= 0) COLOR_GREEN else COLOR_RED)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(tx.reason, 15, true))
                        addView(text("${tx.createdAt} | ${tx.type.label}", 12, false).apply {
                            setTextColor(COLOR_MUTED)
                        })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text("${signed.formatSigned()} pts", 16, true).apply {
                        gravity = Gravity.CENTER
                        setTextColor(if (signed >= 0) COLOR_GREEN else COLOR_RED)
                    })
                }, LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, 0, 0, dp(8))
                })
            }
        }
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(primaryButton("Adicionar saldo") {
                showTransactionDialog(TransactionType.DEPOSIT, person.id)
            }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(secondaryButton("Saque") {
                    showTransactionDialog(TransactionType.WITHDRAW, person.id)
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    setMargins(0, dp(8), dp(8), 0)
                })
                addView(secondaryButton("Penalizar") {
                    showTransactionDialog(TransactionType.PENALTY, person.id)
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    setMargins(0, dp(8), 0, 0)
                })
            })
        })
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun invertFutureAssignments(taskId: String) {
        val updatedTasks = state.tasks.map { task ->
            if (task.id == taskId) {
                task.copy(rotationOffset = task.rotationOffset + 1)
            } else {
                task
            }
        }
        persist(state.copy(tasks = updatedTasks))
        toast("Sequência invertida para os próximos períodos.")
    }

    private fun toggleMissionPhase(missionId: String, phaseId: String, checked: Boolean) {
        val updatedMissions = state.missions.map { mission ->
            if (mission.id == missionId && !mission.isCompleted()) {
                mission.copy(phases = mission.phases.map { phase ->
                    if (phase.id == phaseId) phase.copy(checked = checked) else phase
                })
            } else {
                mission
            }
        }
        persist(state.copy(missions = updatedMissions))
    }

    private fun completeMission(missionId: String) {
        val mission = state.missions.firstOrNull { it.id == missionId } ?: return
        if (mission.isCompleted()) {
            toast("Missao ja concluida.")
            return
        }
        if (!mission.isReadyToComplete()) {
            toast("Marque todas as fases antes de concluir.")
            return
        }
        val recipientIds = mission.participantIds.ifEmpty {
            listOf(mission.rewardPersonId).filter { it.isNotBlank() }
        }
        if (recipientIds.isEmpty()) {
            toast("A missao nao tem participantes.")
            return
        }
        val transactions = recipientIds.map { personId ->
            RewardTransaction(
                personId = personId,
                type = TransactionType.DEPOSIT,
                amount = mission.rewardAmount,
                reason = "Missao concluida: ${mission.title}"
            )
        }
        val updatedMissions = state.missions.map {
            if (it.id == mission.id) {
                it.copy(completedAt = LocalDate.now(), rewardTransactionId = transactions.first().id)
            } else {
                it
            }
        }
        persist(state.copy(missions = updatedMissions, transactions = state.transactions + transactions))
        toast("Recompensa creditada.")
    }

    private fun chooseExportFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "joao-e-pedro-tasks-backup.json")
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    private fun chooseImportFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, IMPORT_REQUEST)
    }

    private fun exportTo(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(repository.exportText(state).toByteArray())
            } ?: error("Não foi possível abrir o arquivo.")
        }.onSuccess {
            toast("Backup exportado.")
        }.onFailure {
            toast("Falha ao exportar: ${it.message}")
        }
    }

    private fun importFrom(uri: Uri) {
        runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Não foi possível ler o arquivo.")
            repository.importText(text)
        }.onSuccess {
            state = it
            render()
            toast("Backup restaurado.")
        }.onFailure {
            toast("Arquivo invalido: ${it.message}")
        }
    }

    private fun persist(newState: AppState) {
        state = newState
        repository.save(state)
        render()
    }

    private fun card(build: LinearLayout.() -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(Color.WHITE, 20f, 2, COLOR_CARD_STROKE)
            elevation = dp(3).toFloat()
            setPadding(dp(18), dp(16), dp(18), dp(16))
            build()
        }.withMargins(bottom = 14)
    }

    private fun rowTitle(title: String, onAdd: () -> Unit): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionTitle(title), LinearLayout.LayoutParams(0, -2, 1f))
            addView(primaryButton("+") { onAdd() }, LinearLayout.LayoutParams(dp(48), dp(42)))
        }
    }

    private fun sectionTitle(value: String): TextView = text(value, 24, true).apply {
        setPadding(0, dp(4), 0, dp(12))
    }

    private fun empty(value: String): TextView = text(value, 16, false).apply {
        setTextColor(COLOR_MUTED)
    }

    private fun label(value: String): TextView = text(value, 13, true).apply {
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun text(value: String, size: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(COLOR_INK)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.08f)
        }
    }

    private fun edit(hintValue: String, defaultValue: String): EditText {
        return EditText(this).apply {
            hint = hintValue
            setText(defaultValue)
            setSingleLine(false)
        }
    }

    private fun spinner(items: List<String>): Spinner {
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = Unit
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    private fun dialogLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
    }

    private fun friendlyDialogLayout(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(COLOR_PANEL, 20f)
            setPadding(dp(18), dp(16), dp(18), dp(12))
            addView(text(title, 24, true).apply {
                setTextColor(COLOR_INK)
            })
            addView(text(subtitle, 14, false).apply {
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(2), 0, dp(14))
            })
        }
    }

    private fun stackedButton(value: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = value
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) COLOR_INK else COLOR_ORANGE_PRI)
            background = roundedDrawable(
                if (primary) COLOR_AMBER else Color.WHITE,
                18f,
                if (primary) 0 else 2,
                COLOR_ORANGE_PRI
            )
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply {
                setMargins(0, dp(12), 0, 0)
            }
        }
    }

    private fun primaryButton(value: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = value
            isAllCaps = false
            setTextColor(COLOR_INK)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedDrawable(COLOR_AMBER, 24f)
            elevation = dp(3).toFloat()
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { onClick() }
        }
    }

    private fun secondaryButton(value: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = value
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ORANGE_PRI)
            background = roundedDrawable(Color.WHITE, 24f, 2, COLOR_ORANGE_PRI)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply {
                setMargins(0, dp(14), 0, 0)
            }
        }
    }

    private fun roundedDrawable(
        color: Int,
        radiusDp: Float,
        strokeWidthDp: Int = 0,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private fun applySafeArea(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                target.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                target.setPadding(
                    left + insets.systemWindowInsetLeft,
                    top + insets.systemWindowInsetTop,
                    right + insets.systemWindowInsetRight,
                    bottom + insets.systemWindowInsetBottom
                )
            }
            insets
        }
        view.requestApplyInsets()
    }

    private fun View.withMargins(bottom: Int): View {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(bottom))
        }
        return this
    }

    private fun Int.formatSigned(): String = if (this > 0) "+$this" else toString()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Tab(val label: String) {
        Today("Hoje"),
        People("Pessoas"),
        Tasks("Atividades"),
        Missions("Missoes"),
        Rewards("Recompensas"),
        Data("Ajuda")
    }

    private companion object {
        const val EXPORT_REQUEST = 1101
        const val IMPORT_REQUEST = 1102
        val COLOR_PAGE = Color.rgb(255, 255, 255)
        val COLOR_INK = Color.rgb(38, 50, 56)
        val COLOR_MUTED = Color.rgb(130, 130, 140)
        val COLOR_BLUE = Color.rgb(77, 163, 255)
        val COLOR_BLUE_SOFT = Color.rgb(229, 243, 255)
        val COLOR_ORANGE_PRI = Color.rgb(255, 140, 66)
        val COLOR_ORANGE_SOFT = Color.rgb(255, 236, 220)
        val COLOR_AMBER = Color.rgb(255, 193, 7)
        val COLOR_CORAL = Color.rgb(255, 138, 101)
        val COLOR_CORAL_SOFT = Color.rgb(255, 236, 229)
        val COLOR_GREEN = Color.rgb(76, 175, 80)
        val COLOR_RED = Color.rgb(220, 38, 38)
        val COLOR_YELLOW = Color.rgb(255, 213, 79)
        val COLOR_HEADER = Color.rgb(255, 140, 66)
        val COLOR_PANEL = Color.rgb(250, 250, 252)
        val COLOR_ACCENT = Color.rgb(255, 140, 66)
        val COLOR_ACCENT_SOFT = Color.rgb(255, 236, 220)
        val COLOR_SOFT_SURFACE = Color.rgb(248, 246, 242)
        val COLOR_BORDER = Color.rgb(230, 225, 218)
        val COLOR_CARD_STROKE = Color.rgb(255, 220, 190)
        val COLOR_DISABLED = Color.rgb(244, 241, 235)
        val COLOR_DISABLED_STROKE = Color.rgb(229, 224, 216)
        val COLOR_NAV_BG = Color.rgb(255, 255, 255)
    }
}
