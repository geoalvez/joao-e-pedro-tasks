package com.joaoepedro.tasks

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import com.joaoepedro.tasks.data.ActivityTask
import com.joaoepedro.tasks.data.AppRepository
import com.joaoepedro.tasks.data.AppState
import com.joaoepedro.tasks.data.Mission
import com.joaoepedro.tasks.data.MissionPhase
import com.joaoepedro.tasks.data.Periodicity
import com.joaoepedro.tasks.data.Person
import com.joaoepedro.tasks.data.PunishmentSession
import com.joaoepedro.tasks.data.RewardTransaction
import com.joaoepedro.tasks.data.TransactionType
import com.joaoepedro.tasks.domain.DailyAllowanceService
import com.joaoepedro.tasks.domain.RewardBalanceService
import com.joaoepedro.tasks.domain.TaskRotationService
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class MainActivity : Activity() {
    private lateinit var repository: AppRepository
    private var state: AppState = AppState.seeded()
    private val rotation = TaskRotationService()
    private val balances = RewardBalanceService()
    private lateinit var root: LinearLayout
    private var selectedTab = Tab.Today
    private var pendingPhotoPersonId: String? = null
    private var pendingCameraFile: File? = null
    private var dashboardStartDate: LocalDate = LocalDate.now()
    private var dashboardEndDate: LocalDate = LocalDate.now()
    private val punishmentTicker = Handler(Looper.getMainLooper())
    private val punishmentTick = object : Runnable {
        override fun run() {
            processPunishmentExpirations()
            if (::root.isInitialized && selectedTab == Tab.Punishments) render()
            punishmentTicker.postDelayed(this, 1000)
        }
    }

    private val strings get() = AppStrings.get(state.language)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = COLOR_ORANGE_PRI
        @Suppress("DEPRECATION")
        window.navigationBarColor = COLOR_NAV_BG
        repository = AppRepository(this)
        state = repository.load()
        showSplash()
        Handler(Looper.getMainLooper()).postDelayed({ startRenderApp() }, 1200)
    }

    override fun onDestroy() {
        punishmentTicker.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated Android callback kept to avoid extra dependencies.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            EXPORT_REQUEST -> data?.data?.let { exportTo(it) }
            IMPORT_REQUEST -> data?.data?.let { importFrom(it) }
            PHOTO_REQUEST -> {
                val uri = data?.data ?: return
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val personId = pendingPhotoPersonId ?: return
                pendingPhotoPersonId = null
                state = state.copy(people = state.people.map { p ->
                    if (p.id == personId) p.copy(photoUri = uri.toString()) else p
                })
                repository.save(state)
                render()
                toast(strings.toastPhotoUpdated)
            }
            CAMERA_REQUEST -> {
                val file = pendingCameraFile ?: return
                pendingCameraFile = null
                val personId = pendingPhotoPersonId ?: return
                pendingPhotoPersonId = null
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                state = state.copy(people = state.people.map { p ->
                    if (p.id == personId) p.copy(photoUri = uri.toString()) else p
                })
                repository.save(state)
                render()
                toast(strings.toastPhotoUpdated)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPhotoPersonId?.let { launchCamera(it) }
            } else {
                pendingPhotoPersonId = null
                toast(strings.toastCameraDenied)
            }
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

    private fun startRenderApp() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PAGE)
        }
        setContentView(root)
        applySafeArea(root, 0, 0, 0, 0)
        if (state.biometricEnabled) {
            showBiometricPrompt {
                processDailyAllowances()
                scheduleRunningPunishmentAlarms()
                startPunishmentTicker()
                render()
            }
        } else {
            processDailyAllowances()
            scheduleRunningPunishmentAlarms()
            startPunishmentTicker()
            render()
        }
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle(strings.biometricTitle)
                .setSubtitle(strings.biometricSubtitle)
                .setNegativeButton(strings.biometricCancel, mainExecutor) { _, _ -> finish() }
                .build()
            prompt.authenticate(CancellationSignal(), mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) { finish() }
                    override fun onAuthenticationFailed() {}
                })
        } else {
            toast(strings.biometricNotAvailable)
            onSuccess()
        }
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
        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        when (selectedTab) {
            Tab.Today -> renderToday(content)
            Tab.People -> renderPeople(content)
            Tab.Tasks -> renderTasks(content)
            Tab.Missions -> renderMissions(content)
            Tab.Punishments -> renderPunishments(content)
            Tab.Dashboards -> renderDashboards(content)
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
            addView(text("João & Pedro", 26, true).apply { setTextColor(Color.WHITE) })
            addView(text(strings.headerSubtitle, 14, false).apply {
                setTextColor(Color.argb(210, 255, 255, 255))
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun bottomNav(): View {
        val navItems = listOf(
            Tab.Today    to (R.drawable.ic_nav_home     to strings.tabToday),
            Tab.People   to (R.drawable.ic_nav_family   to strings.tabFamily),
            Tab.Tasks    to (R.drawable.ic_nav_tasks    to strings.tabTasks),
            Tab.Missions to (R.drawable.ic_nav_missions to strings.tabMissions),
            Tab.Punishments to (R.drawable.ic_nav_punishment to "Castigo"),
            Tab.Dashboards to (R.drawable.ic_nav_dashboard to "Dash"),
            Tab.Rewards  to (R.drawable.ic_nav_rewards  to strings.tabBalance),
            Tab.Data     to (R.drawable.ic_nav_config   to strings.tabConfig)
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(COLOR_NAV_BG)
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, dp(72))
            navItems.forEach { (tab, meta) ->
                val (iconRes, label) = meta
                val isSelected = selectedTab == tab
                val iconColor = if (isSelected) COLOR_ORANGE_PRI else COLOR_MUTED
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    if (isSelected) setBackgroundColor(COLOR_ORANGE_SOFT)
                    setOnClickListener { selectedTab = tab; render() }
                    addView(ImageView(context).apply {
                        setImageResource(iconRes)
                        setColorFilter(iconColor)
                        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            topMargin = dp(8)
                        }
                    })
                    addView(TextView(context).apply {
                        text = label
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(iconColor)
                        typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setPadding(0, dp(3), 0, dp(6))
                    })
                })
            }
        }
    }

    private fun renderToday(content: LinearLayout) {
        content.addView(sectionTitle(strings.sectionToday))
        val activeTasks = state.tasks.filter { it.active }
        if (activeTasks.isEmpty()) content.addView(empty(strings.noActiveTasks))
        activeTasks.forEach { task ->
            val currentId = rotation.currentPersonId(task, LocalDate.now())
            val person = state.people.firstOrNull { it.id == currentId }?.name ?: strings.noPersonAssigned
            val todayCard = card {
                addView(text(task.title, 17, true))
                addView(text(strings.labelNow, 12, true).apply {
                    setTextColor(COLOR_MUTED)
                    setPadding(0, dp(12), 0, 0)
                })
                addView(text(person, 32, true).apply { setTextColor(COLOR_ACCENT) })
                addView(text("${strings.labelPeriodicity}${periodicityLabel(task.periodicity)}", 14, false).apply {
                    setPadding(0, dp(6), 0, 0)
                })
                addView(text("${strings.labelNextChange}${this@MainActivity.rotation.nextChangeDate(task)}", 14, false).apply {
                    setTextColor(COLOR_MUTED)
                })
                if (task.participantIds.size > 1) {
                    addView(secondaryButton(strings.btnSwapTasks) { invertFutureAssignments(task.id) })
                }
            }
            todayCard.setOnClickListener { showTaskCalendarDialog(task) }
            content.addView(todayCard)
        }
    }

    private fun renderPeople(content: LinearLayout) {
        content.addView(rowTitle(strings.sectionFamily) { showPersonDialog() })
        val people = state.people.filterNot { it.archived }
        if (people.isEmpty()) { content.addView(empty(strings.noPeople)); return }
        val personColors = listOf(COLOR_BLUE, COLOR_ORANGE_PRI, COLOR_GREEN, COLOR_CORAL)
        people.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            }
            pair.forEachIndexed { idx, person ->
                val avatarColor = personColors[(people.indexOf(person)) % personColors.size]
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
                    val avatarLp = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(12)
                    }
                    if (person.photoUri != null) {
                        addView(ImageView(context).apply {
                            layoutParams = avatarLp
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            runCatching { setImageURI(android.net.Uri.parse(person.photoUri)) }
                            clipToOutline = true
                            outlineProvider = object : android.view.ViewOutlineProvider() {
                                override fun getOutline(v: android.view.View, o: android.graphics.Outline) {
                                    o.setOval(0, 0, v.width, v.height)
                                }
                            }
                        })
                    } else {
                        addView(TextView(context).apply {
                            text = person.name.first().toString().uppercase()
                            textSize = 28f
                            setTextColor(Color.WHITE)
                            typeface = Typeface.DEFAULT_BOLD
                            gravity = Gravity.CENTER
                            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(avatarColor) }
                            layoutParams = avatarLp
                        })
                    }
                    addView(text(person.name, 17, true).apply { gravity = Gravity.CENTER })
                    if (person.birthDate != null || person.schoolYear != null) {
                        addView(text(
                            listOfNotNull(person.schoolYear, person.birthDate?.let { calcAge(it) }?.let { "$it ${strings.yearsOld}" }).joinToString(" · "),
                            12, false
                        ).apply {
                            gravity = Gravity.CENTER
                            setTextColor(COLOR_MUTED)
                            setPadding(0, dp(2), 0, 0)
                        })
                    }
                    addView(text("⭐ ${balance.formatPoints()} pts", 15, false).apply {
                        gravity = Gravity.CENTER
                        setTextColor(COLOR_ORANGE_PRI)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, dp(4), 0, 0)
                    })
                    setOnClickListener { showPersonProfileDialog(person) }
                }
                row.addView(personCard)
            }
            if (pair.size == 1) row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            content.addView(row)
        }
    }

    private fun renderTasks(content: LinearLayout) {
        content.addView(rowTitle(strings.sectionTasks) { showTaskDialog() })
        state.tasks.forEach { task ->
            val names = task.participantIds.mapNotNull { id -> state.people.firstOrNull { it.id == id }?.name }
            val taskCard = card {
                addView(text(task.title, 18, true))
                addView(text("${strings.labelOrder}${names.joinToString(" → ")}", 14, false))
                val currentId = this@MainActivity.rotation.currentPersonId(task, LocalDate.now())
                val currentName = state.people.firstOrNull { it.id == currentId }?.name ?: strings.noPersonAssigned
                addView(text("${strings.labelNextEvent}$currentName", 14, true).apply {
                    setTextColor(COLOR_ACCENT)
                    setPadding(0, dp(6), 0, 0)
                })
                if (task.rotationOffset != 0) {
                    addView(text("${strings.labelManualAdjust}${task.rotationOffset}", 14, false).apply { setTextColor(COLOR_MUTED) })
                }
                addView(text("${periodicityLabel(task.periodicity)} · ${task.startDate}", 14, false).apply { setTextColor(COLOR_MUTED) })
                addView(secondaryButton(strings.btnAssociatePerson) { showAddTaskParticipantDialog(task) })
                if (task.participantIds.isNotEmpty()) {
                    addView(secondaryButton(strings.btnRemovePerson) { showRemoveTaskParticipantDialog(task) })
                }
            }
            taskCard.setOnClickListener { showTaskCalendarDialog(task) }
            content.addView(taskCard)
        }
    }

    private fun renderMissions(content: LinearLayout) {
        content.addView(rowTitle(strings.sectionMissions) { showMissionDialog() })
        if (state.missions.isEmpty()) content.addView(empty(strings.noMissions))
        state.missions.forEach { mission ->
            val participants = mission.participantIds
                .mapNotNull { id -> state.people.firstOrNull { it.id == id }?.name }
                .joinToString(", ").ifBlank { strings.noParticipants }
            val missionCard = card {
                addView(text(mission.title, 18, true))
                addView(text(String.format(strings.labelRewardPerParticipant, mission.rewardAmount), 14, false).apply { setTextColor(COLOR_ACCENT) })
                addView(text("${strings.labelParticipants}$participants", 14, false).apply { setTextColor(COLOR_MUTED) })
                if (mission.phases.isEmpty()) addView(text(strings.noPhases, 14, false).apply { setTextColor(COLOR_MUTED) })
                mission.participantIds.forEach { personId ->
                    val personName = state.people.firstOrNull { it.id == personId }?.name ?: strings.noPersonAssigned
                    val done = mission.completedPhaseCountFor(personId)
                    val total = mission.phases.size
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = roundedDrawable(COLOR_BLUE_SOFT, 14f, 1, COLOR_BLUE)
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        addView(text(personName, 14, true), LinearLayout.LayoutParams(0, -2, 1f))
                        addView(text("$done/$total", 14, true).apply {
                            setTextColor(if (total > 0 && done == total) COLOR_GREEN else COLOR_BLUE)
                        })
                    }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, 0) })
                }
                val status = when {
                    mission.isCompleted() -> strings.statusCompleted
                    mission.isReadyToComplete() -> strings.statusReady
                    else -> strings.statusInProgress
                }
                addView(text(status, 14, true).apply {
                    setTextColor(if (mission.isCompleted()) COLOR_ACCENT else COLOR_MUTED)
                    setPadding(0, dp(8), 0, 0)
                })
                if (!mission.isCompleted()) {
                    addView(secondaryButton(strings.btnAddPhase) { showMissionPhaseDialog(mission) })
                    addView(secondaryButton(strings.btnAssociatePerson) { showAddMissionParticipantDialog(mission) })
                    if (mission.participantIds.isNotEmpty()) {
                        addView(secondaryButton(strings.btnRemovePerson) { showRemoveMissionParticipantDialog(mission) })
                    }
                }
                addView(secondaryButton(strings.btnDeleteMission) { confirmDeleteMission(mission.id, mission.title) })
            }
            missionCard.setOnClickListener { showMissionProgressDialog(mission.id) }
            content.addView(missionCard)
        }
    }

    private fun renderPunishments(content: LinearLayout) {
        content.addView(rowTitle("Castigos") { showPunishmentDialog() })
        val people = state.people.filterNot { it.archived }
        if (people.isEmpty()) {
            content.addView(empty(strings.noPeople))
            return
        }
        people.forEach { person ->
            val session = activePunishmentFor(person.id)
            val punishmentCard = card {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(person.name, 20, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(if (session == null) "Livre" else "Em castigo", 13, true).apply {
                        setTextColor(if (session == null) COLOR_GREEN else COLOR_CORAL)
                        gravity = Gravity.CENTER
                        background = roundedDrawable(if (session == null) COLOR_BLUE_SOFT else COLOR_CORAL_SOFT, 18f)
                        setPadding(dp(12), dp(5), dp(12), dp(5))
                    })
                })
                if (session == null) {
                    addView(text("Nenhum castigo ativo no momento.", 14, false).apply {
                        setTextColor(COLOR_MUTED)
                        setPadding(0, dp(8), 0, 0)
                    })
                    addView(secondaryButton("Iniciar castigo") { showPunishmentDialog(person.id) })
                } else {
                    val remaining = session.remainingMillis()
                    addView(text("Tempo restante", 13, true).apply {
                        setTextColor(COLOR_MUTED)
                        setPadding(0, dp(12), 0, 0)
                    })
                    addView(text(formatDuration(remaining), 36, true).apply {
                        setTextColor(COLOR_ORANGE_PRI)
                        setPadding(0, dp(2), 0, 0)
                    })
                    addView(text("Pode adicionar tempo sem parar o cronometro.", 13, false).apply {
                        setTextColor(COLOR_MUTED)
                        setPadding(0, dp(2), 0, 0)
                    })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(secondaryButton("+1 min") { addPunishmentTime(session.id, 60_000L) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(0, dp(14), dp(6), 0) })
                        addView(secondaryButton("+5 min") { addPunishmentTime(session.id, 5 * 60_000L) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(6), dp(14), 0, 0) })
                    })
                    addView(secondaryButton("Adicionar outro tempo") { showAddPunishmentTimeDialog(session) })
                    addView(secondaryButton("Encerrar castigo") { stopPunishment(session.id) })
                }
            }
            content.addView(punishmentCard)
        }
    }

    private fun showPunishmentDialog(defaultPersonId: String? = null) {
        val people = state.people.filterNot { it.archived }
        if (people.isEmpty()) { toast(strings.toastInformName); return }
        val layout = friendlyDialogLayout("Novo castigo", "Defina quem vai ficar refletindo e por quanto tempo.")
        val personSpinner = spinner(people.map { it.name })
        defaultPersonId?.let { id ->
            val index = people.indexOfFirst { it.id == id }
            if (index >= 0) personSpinner.setSelection(index)
        }
        val hoursInput = edit("Horas", "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val minutesInput = edit("Minutos", "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val secondsInput = edit("Segundos", "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val errorText = text("", 13, true).apply {
            setTextColor(COLOR_CORAL)
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        fun showErr(message: String) {
            errorText.text = message
            errorText.visibility = View.VISIBLE
        }
        layout.addView(label(strings.labelPerson))
        layout.addView(personSpinner)
        layout.addView(label("Tempo"))
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hoursInput, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(minutesInput, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(secondsInput, LinearLayout.LayoutParams(0, -2, 1f))
        })
        layout.addView(errorText)
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton("Iniciar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val duration = readDurationMillis(hoursInput, minutesInput, secondsInput)
                if (duration <= 0L) {
                    showErr("Informe um tempo maior que zero.")
                    return@setOnClickListener
                }
                val person = people[personSpinner.selectedItemPosition]
                val active = activePunishmentFor(person.id)
                if (active != null) {
                    addPunishmentTime(active.id, duration)
                } else {
                    startPunishment(person.id, duration)
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showAddPunishmentTimeDialog(session: PunishmentSession) {
        val person = state.people.firstOrNull { it.id == session.personId }
        val layout = friendlyDialogLayout("Adicionar tempo", person?.name ?: "Castigo ativo")
        val hoursInput = edit("Horas", "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val minutesInput = edit("Minutos", "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val secondsInput = edit("Segundos", "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val errorText = text("", 13, true).apply { setTextColor(COLOR_CORAL); visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        layout.addView(label("Tempo extra"))
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hoursInput, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(minutesInput, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(secondsInput, LinearLayout.LayoutParams(0, -2, 1f))
        })
        layout.addView(errorText)
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton("Adicionar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val duration = readDurationMillis(hoursInput, minutesInput, secondsInput)
                if (duration <= 0L) {
                    errorText.text = "Informe um tempo maior que zero."
                    errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                addPunishmentTime(session.id, duration)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun readDurationMillis(hoursInput: EditText, minutesInput: EditText, secondsInput: EditText): Long {
        val hours = hoursInput.text.toString().trim().toLongOrNull() ?: 0L
        val minutes = minutesInput.text.toString().trim().toLongOrNull() ?: 0L
        val seconds = secondsInput.text.toString().trim().toLongOrNull() ?: 0L
        return ((hours * 60L * 60L) + (minutes * 60L) + seconds) * 1000L
    }

    private fun startPunishment(personId: String, durationMillis: Long) {
        requestPunishmentNotificationPermissionIfNeeded()
        val now = System.currentTimeMillis()
        val session = PunishmentSession(personId = personId, startedAtMillis = now, endsAtMillis = now + durationMillis)
        state = state.copy(punishments = state.punishments + session)
        repository.save(state)
        schedulePunishmentAlarm(session)
        render()
    }

    private fun addPunishmentTime(sessionId: String, durationMillis: Long) {
        var updatedSession: PunishmentSession? = null
        val updated = state.punishments.map { session ->
            if (session.id == sessionId) {
                val base = maxOf(session.endsAtMillis, System.currentTimeMillis())
                session.copy(endsAtMillis = base + durationMillis, active = true, completedAlerted = false, finishedAtMillis = null).also { updatedSession = it }
            } else {
                session
            }
        }
        state = state.copy(punishments = updated)
        repository.save(state)
        updatedSession?.let { schedulePunishmentAlarm(it) }
        render()
    }

    private fun stopPunishment(sessionId: String) {
        val updated = state.punishments.map { session ->
            if (session.id == sessionId) session.copy(active = false, completedAlerted = true, finishedAtMillis = System.currentTimeMillis()) else session
        }
        state = state.copy(punishments = updated)
        repository.save(state)
        cancelPunishmentAlarm(sessionId)
        render()
    }

    private fun activePunishmentFor(personId: String): PunishmentSession? {
        return state.punishments
            .filter { it.personId == personId && it.isRunning() }
            .maxByOrNull { it.endsAtMillis }
    }

    private fun processPunishmentExpirations() {
        val finished = state.punishments.filter { it.active && it.remainingMillis() == 0L && !it.completedAlerted }
        if (finished.isEmpty()) return
        state = state.copy(punishments = state.punishments.map { session ->
            if (finished.any { it.id == session.id }) session.copy(active = false, completedAlerted = true, finishedAtMillis = session.endsAtMillis) else session
        })
        repository.save(state)
        finished.forEach { session ->
            val personName = state.people.firstOrNull { it.id == session.personId }?.name ?: "Participante"
            showPunishmentFinishedDialog(session.id, personName)
        }
    }

    private fun showPunishmentFinishedDialog(sessionId: String, personName: String) {
        PunishmentAlarmReceiver.playAlertSound(this)
        PunishmentAlarmReceiver.showNotification(this, sessionId, personName)
        AlertDialog.Builder(this)
            .setTitle("Castigo finalizado")
            .setMessage("$personName terminou o tempo de castigo.")
            .setPositiveButton(strings.btnClose, null)
            .show()
    }

    private fun startPunishmentTicker() {
        punishmentTicker.removeCallbacks(punishmentTick)
        punishmentTicker.post(punishmentTick)
    }

    private fun scheduleRunningPunishmentAlarms() {
        state.punishments.filter { it.isRunning() }.forEach { schedulePunishmentAlarm(it) }
    }

    private fun schedulePunishmentAlarm(session: PunishmentSession) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = punishmentPendingIntent(session.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, session.endsAtMillis, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, session.endsAtMillis, pendingIntent)
        }
    }

    private fun cancelPunishmentAlarm(sessionId: String) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        punishmentPendingIntent(sessionId, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager.cancel(it) }
    }

    private fun punishmentPendingIntent(sessionId: String, updateFlag: Int): PendingIntent? {
        val intent = Intent(this, PunishmentAlarmReceiver::class.java)
            .putExtra(PunishmentAlarmReceiver.EXTRA_PUNISHMENT_ID, sessionId)
        val flags = updateFlag or (
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return PendingIntent.getBroadcast(this, sessionId.hashCode(), intent, flags)
    }

    private fun requestPunishmentNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun renderDashboards(content: LinearLayout) {
        content.addView(sectionTitle("Dashboards"))
        content.addView(dashboardFilterCard())
        content.addView(card {
            addView(text("Pessoa x castigo", 20, true))
            addView(text("Quantidade de castigos e tempo acumulado no periodo selecionado.", 13, false).apply {
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(3), 0, dp(12))
            })
            val sessionsInRange = state.punishments.filter { isInDashboardRange(punishmentDate(it.startedAtMillis)) }
            if (sessionsInRange.isEmpty()) {
                addView(empty("Nenhum castigo registrado nesse periodo.").apply {
                    setPadding(0, dp(8), 0, dp(8))
                })
            } else {
                state.people.filterNot { it.archived }.forEach { person ->
                    val personSessions = sessionsInRange.filter { it.personId == person.id }
                    if (personSessions.isNotEmpty()) addView(punishmentReportRow(person, personSessions))
                }
            }
        })
    }

    private fun dashboardFilterCard(): View = card {
        addView(text("Periodo", 18, true))
        addView(text("${formatDate(dashboardStartDate)} ate ${formatDate(dashboardEndDate)}", 14, true).apply {
            setTextColor(COLOR_BLUE)
            setPadding(0, dp(2), 0, dp(10))
        })
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(periodButton("1 dia") { setDashboardPeriod(days = 1) })
                addView(periodButton("1 semana") { setDashboardPeriod(days = 7) })
                addView(periodButton("1 mes") { setDashboardPeriod(months = 1) })
                addView(periodButton("1 ano") { setDashboardPeriod(years = 1) })
            })
        }, LinearLayout.LayoutParams(-1, dp(46)))
        addView(secondaryButton("Escolher intervalo") { showDashboardDateRangeDialog() })
    }

    private fun periodButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ORANGE_PRI)
            background = roundedDrawable(COLOR_ORANGE_SOFT, 22f, 1, COLOR_ORANGE_PRI)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply { setMargins(0, 0, dp(8), 0) }
        }
    }

    private fun punishmentReportRow(person: Person, sessions: List<PunishmentSession>): View {
        val totalMillis = sessions.sumOf { it.elapsedMillis() }
        val activeCount = sessions.count { it.isRunning() }
        val averageMillis = if (sessions.isEmpty()) 0L else totalMillis / sessions.size
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(Color.WHITE, 16f, 1, COLOR_BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(person.name, 17, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(text("${sessions.size}x", 18, true).apply { setTextColor(COLOR_CORAL) })
            })
            addView(text("Tempo total: ${formatLongDuration(totalMillis)}", 14, true).apply {
                setTextColor(COLOR_ORANGE_PRI)
                setPadding(0, dp(6), 0, 0)
            })
            addView(text("Media por castigo: ${formatLongDuration(averageMillis)}", 13, false).apply {
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(2), 0, 0)
            })
            if (activeCount > 0) {
                addView(text("Em andamento no periodo: $activeCount", 13, true).apply {
                    setTextColor(COLOR_BLUE)
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }.withMargins(bottom = 10)
    }

    private fun setDashboardPeriod(days: Long = 0L, months: Long = 0L, years: Long = 0L) {
        val end = LocalDate.now()
        dashboardEndDate = end
        dashboardStartDate = when {
            years > 0L -> end.minusYears(years).plusDays(1)
            months > 0L -> end.minusMonths(months).plusDays(1)
            else -> end.minusDays((days - 1).coerceAtLeast(0L))
        }
        render()
    }

    private fun showDashboardDateRangeDialog() {
        val layout = friendlyDialogLayout("Intervalo do dashboard", "Defina a data inicio e data fim dos relatorios.")
        val startInput = edit("dd/mm/aaaa", formatDate(dashboardStartDate)).also { applyDateMask(it) }
        val endInput = edit("dd/mm/aaaa", formatDate(dashboardEndDate)).also { applyDateMask(it) }
        val errorText = text("", 13, true).apply { setTextColor(COLOR_CORAL); visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        layout.addView(label("Data inicio"))
        layout.addView(startInput)
        layout.addView(label("Data fim"))
        layout.addView(endInput)
        layout.addView(errorText)
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnSave, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val start = parseDate(startInput.text.toString().trim())
                val end = parseDate(endInput.text.toString().trim())
                when {
                    start == null || end == null -> {
                        errorText.text = "Informe as datas no formato dd/mm/aaaa."
                        errorText.visibility = View.VISIBLE
                    }
                    start.isAfter(end) -> {
                        errorText.text = "A data inicio nao pode ser maior que a data fim."
                        errorText.visibility = View.VISIBLE
                    }
                    else -> {
                        dashboardStartDate = start
                        dashboardEndDate = end
                        dialog.dismiss()
                        render()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun punishmentDate(millis: Long): LocalDate {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    private fun isInDashboardRange(date: LocalDate): Boolean {
        return !date.isBefore(dashboardStartDate) && !date.isAfter(dashboardEndDate)
    }

    private fun parseDate(text: String): LocalDate? {
        if (text.isBlank()) return null
        return runCatching { LocalDate.parse(text, java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrNull()
    }

    private fun formatDate(date: LocalDate): String {
        return date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }

    private fun formatLongDuration(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> "${hours}h ${minutes}min ${seconds}s"
            minutes > 0L -> "${minutes}min ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun renderRewards(content: LinearLayout) {
        content.addView(rowTitle(strings.sectionRewards) { showTransactionTypeChooser() })
        state.people.filterNot { it.archived }.forEach { person ->
            val balance = balances.balanceFor(person.id, state.transactions)
            val rewardCard = card {
                addView(text(person.name, 18, true))
                addView(text(String.format(strings.labelBalance, balance.formatPoints()), 22, true).apply { setTextColor(COLOR_ACCENT) })
                addView(text(formatReais(balance, state.pointValueCents), 15, true).apply {
                    setTextColor(COLOR_GREEN)
                    setPadding(0, dp(2), 0, dp(4))
                })
                state.transactions.filter { it.personId == person.id }
                    .sortedByDescending { it.createdAt }.take(5)
                    .forEach { tx ->
                        val signed = tx.amount * tx.type.sign
                        addView(text("${tx.createdAt}  ${txTypeLabel(tx.type)}: ${signed.formatSigned()} - ${tx.reason}", 13, false))
                    }
                addView(text(strings.tapForStatement, 12, true).apply {
                    setTextColor(COLOR_BLUE)
                    setPadding(0, dp(10), 0, 0)
                })
            }
            rewardCard.setOnClickListener { showPersonStatementDialog(person) }
            content.addView(rewardCard)
        }
    }

    private fun renderData(content: LinearLayout) {
        content.addView(sectionTitle(strings.sectionConfig))
        content.addView(card {
            addView(text(strings.cardLanguage, 18, true))
            addView(text(languageName(state.language), 14, false).apply { setTextColor(COLOR_MUTED) })
            addView(secondaryButton(strings.btnConfigure) { showLanguageDialog() })
        })
        content.addView(card {
            addView(text(strings.cardBiometric, 18, true))
            addView(text(if (state.biometricEnabled) strings.biometricOn else strings.biometricOff, 14, false).apply { setTextColor(COLOR_MUTED) })
            val btnLabel = if (state.biometricEnabled) strings.btnDisableBiometric else strings.btnEnableBiometric
            addView(secondaryButton(btnLabel) { toggleBiometric() })
        })
        val config = state.dailyAllowance
        content.addView(card {
            addView(text(strings.cardPointValue, 18, true))
            addView(text(String.format(strings.pointValueDesc, formatReais(1.0, state.pointValueCents)), 14, false).apply { setTextColor(COLOR_MUTED) })
            addView(secondaryButton(strings.btnConfigure) { showPointValueDialog() })
        })
        content.addView(card {
            addView(text(strings.cardDailyAllowance, 18, true))
            val statusText = if (config.enabledSince != null)
                String.format(strings.statusEnabledSince, config.enabledSince, config.amountPerDay)
            else strings.statusDisabled
            addView(text(statusText, 14, false).apply { setTextColor(COLOR_MUTED) })
            addView(secondaryButton(strings.btnConfigure) { showDailyAllowanceDialog() })
        })
        content.addView(card {
            addView(text(strings.cardBackup, 18, true))
            addView(text(strings.backupDesc, 14, false))
            addView(stackedButton(strings.btnGenerateBackup, true) { chooseExportFile() })
            addView(stackedButton(strings.btnRestoreBackup, false) { chooseImportFile() })
        })
        content.addView(card {
            addView(text(strings.cardSummary, 18, true))
            addView(text(String.format(strings.labelPeople, state.people.size), 14, false))
            addView(text(String.format(strings.labelActivities, state.tasks.size), 14, false))
            addView(text(String.format(strings.labelTransactions, state.transactions.size), 14, false))
        })
        content.addView(developerCard())
    }

    private fun showLanguageDialog() {
        AlertDialog.Builder(this)
            .setTitle(strings.titleLanguage)
            .setItems(arrayOf("🇧🇷 Português", "🇪🇸 Español", "🇺🇸 English")) { _, which ->
                val lang = when (which) { 0 -> "pt"; 1 -> "es"; else -> "en" }
                persist(state.copy(language = lang))
            }
            .show()
    }

    private fun toggleBiometric() {
        if (!state.biometricEnabled) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
                toast(strings.biometricNotAvailable)
                return
            }
            showBiometricPrompt { persist(state.copy(biometricEnabled = true)) }
        } else {
            persist(state.copy(biometricEnabled = false))
        }
    }

    private fun showPointValueDialog() {
        val layout = dialogLayout()
        layout.addView(label(strings.cardPointValue))
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = strings.hintPointValue
            val current = state.pointValueCents / 100.0
            setText(String.format(java.util.Locale.US, "%.2f", current))
            setSingleLine(true)
        }
        layout.addView(input)
        layout.addView(text(strings.notePointValue, 12, false).apply {
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(4), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle(strings.titlePointValue)
            .setView(layout)
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnSave) { _, _ ->
                val value = input.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (value == null || value <= 0) { toast(strings.toastInformAmount); return@setPositiveButton }
                state = state.copy(pointValueCents = (value * 100).toInt().coerceAtLeast(1))
                repository.save(state)
                render()
            }
            .show()
    }

    private fun showDailyAllowanceDialog() {
        val config = state.dailyAllowance
        val layout = dialogLayout()
        layout.addView(label(strings.labelAmountPerDay))
        val amountInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "0"
            setText(if (config.amountPerDay > 0) config.amountPerDay.toString() else "")
            setSingleLine(true)
        }
        layout.addView(amountInput)
        val builder = AlertDialog.Builder(this)
            .setTitle(strings.titleDailyAllowance)
            .setView(layout)
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(if (config.enabledSince != null) strings.btnSave else strings.btnActivate) { _, _ ->
                val amount = amountInput.text.toString().trim().toIntOrNull() ?: 0
                if (amount <= 0) { toast(strings.toastAmountGreaterZero); return@setPositiveButton }
                val newConfig = config.copy(amountPerDay = amount, enabledSince = config.enabledSince ?: LocalDate.now())
                state = state.copy(dailyAllowance = newConfig)
                processDailyAllowances()
                repository.save(state)
                render()
            }
        if (config.enabledSince != null) {
            builder.setNeutralButton(strings.btnDeactivate) { _, _ ->
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
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { setMargins(0, 0, dp(8), 0) })
            addView(text("Instagram @geoalvez", 14, true).apply { setTextColor(COLOR_BLUE) })
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/geoalvez/"))) }
        })
    }

    private fun showTaskCalendarDialog(task: ActivityTask) {
        val month = YearMonth.from(LocalDate.now())
        val firstDay = month.atDay(1)
        val assignments = rotation.assignmentsBetween(task, firstDay, month.atEndOfMonth()).toMap()
        val layout = dialogLayout()
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedDrawable(COLOR_BLUE_SOFT, 18f, 1, COLOR_BLUE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text(strings.labelCalendar, 13, true).apply { gravity = Gravity.CENTER; setTextColor(COLOR_BLUE) })
            addView(text("${month.monthValue}/${month.year}", 28, true).apply { gravity = Gravity.CENTER; setTextColor(COLOR_INK) })
            addView(text(String.format(strings.labelStartsAt, task.startDate), 12, false).apply { gravity = Gravity.CENTER; setTextColor(COLOR_MUTED) })
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(14)) })
        val grid = GridLayout(this).apply { columnCount = 7; rowCount = 7 }
        strings.weekDays.forEach { day ->
            grid.addView(text(day, 11, true).apply { gravity = Gravity.CENTER; setTextColor(COLOR_MUTED) }, GridLayout.LayoutParams().apply { width = dp(42); height = dp(28) })
        }
        repeat(firstDay.dayOfWeek.value - 1) {
            grid.addView(View(this), GridLayout.LayoutParams().apply { width = dp(42); height = dp(62) })
        }
        (1..month.lengthOfMonth()).forEach { day ->
            val date = month.atDay(day)
            val personId = assignments[date]
            val personName = if (date.isBefore(task.startDate)) "" else state.people.firstOrNull { it.id == personId }?.name ?: strings.noPersonAssigned
            grid.addView(calendarDayCell(date, personName, date.isBefore(task.startDate)), GridLayout.LayoutParams().apply { width = dp(44); height = dp(68); setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        layout.addView(grid)
        AlertDialog.Builder(this)
            .setTitle(task.title)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton(strings.btnClose, null)
            .show()
    }

    private fun calendarDayCell(date: LocalDate, personName: String, beforeStart: Boolean): View {
        val isToday = date == LocalDate.now()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedDrawable(
                when { beforeStart -> COLOR_DISABLED; isToday -> COLOR_CORAL_SOFT; personName.isNotBlank() -> COLOR_BLUE_SOFT; else -> Color.WHITE },
                14f, 1,
                when { beforeStart -> COLOR_DISABLED_STROKE; isToday -> COLOR_CORAL; personName.isNotBlank() -> COLOR_BLUE; else -> COLOR_BORDER }
            )
            setPadding(dp(2), dp(4), dp(2), dp(4))
            addView(text(date.dayOfMonth.toString(), 13, true).apply { gravity = Gravity.CENTER; setTextColor(if (beforeStart) COLOR_MUTED else COLOR_INK) })
            addView(text(if (beforeStart) "--" else personName.take(8), 10, true).apply {
                gravity = Gravity.CENTER
                setTextColor(when { beforeStart -> COLOR_MUTED; isToday -> COLOR_CORAL; else -> COLOR_BLUE })
                maxLines = 2
            })
        }
    }

    private fun showPersonDialog() {
        val layout = dialogLayout()
        val nameInput = edit(strings.hintChildName, "")
        val birthInput = edit("dd/mm/aaaa", "").also { applyDateMask(it) }
        val schoolInput = edit(strings.hintSchoolYear, "")
        layout.addView(label(strings.nameLabel))
        layout.addView(nameInput)
        layout.addView(label(strings.hintBirthDate))
        layout.addView(birthInput)
        layout.addView(label(strings.hintSchoolYear))
        layout.addView(schoolInput)
        AlertDialog.Builder(this)
            .setTitle(strings.titleNewPerson)
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnSave) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) { toast(strings.toastInformName); return@setPositiveButton }
                val rawBirth = birthInput.text.toString().trim()
                val birthDate = parseBirthDate(rawBirth)
                if (rawBirth.isNotBlank() && birthDate == null) { toast("Data inválida. Use dd/mm/aaaa."); return@setPositiveButton }
                val schoolYear = schoolInput.text.toString().trim().takeIf { it.isNotBlank() }
                persist(state.copy(people = state.people + Person(name = name, birthDate = birthDate, schoolYear = schoolYear)))
            }
            .show()
    }

    private fun showPersonProfileDialog(person: Person) {
        val layout = dialogLayout()
        val nameInput = edit(strings.hintChildName, person.name)
        val birthInput = edit("dd/mm/aaaa", person.birthDate?.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) ?: "").also { applyDateMask(it) }
        val schoolInput = edit(strings.hintSchoolYear, person.schoolYear ?: "")
        val photoStatus = TextView(this).apply {
            text = if (person.photoUri != null) strings.photoSet else strings.noPhotoSelected
            textSize = 13f
            setTextColor(if (person.photoUri != null) COLOR_GREEN else COLOR_MUTED)
            setPadding(0, dp(4), 0, 0)
        }
        layout.addView(profilePhotoPreview(person))
        layout.addView(label(strings.nameLabel))
        layout.addView(nameInput)
        layout.addView(label(strings.hintBirthDate))
        layout.addView(birthInput)
        layout.addView(label(strings.hintSchoolYear))
        layout.addView(schoolInput)
        layout.addView(label(strings.labelProfilePhoto))
        layout.addView(photoStatus)
        layout.addView(TextView(this).apply {
            text = strings.btnChoosePhoto
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ORANGE_PRI)
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { choosePhotoSource(person.id) }
        })
        val builder = AlertDialog.Builder(this)
            .setTitle(String.format(strings.titleEditProfile, person.name))
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnSave) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) { toast(strings.toastInformName); return@setPositiveButton }
                val rawBirth = birthInput.text.toString().trim()
                val birthDate = parseBirthDate(rawBirth)
                if (rawBirth.isNotBlank() && birthDate == null) { toast("Data inválida. Use dd/mm/aaaa."); return@setPositiveButton }
                val schoolYear = schoolInput.text.toString().trim().takeIf { it.isNotBlank() }
                persist(state.copy(people = state.people.map { p ->
                    if (p.id == person.id) p.copy(name = name, birthDate = birthDate, schoolYear = schoolYear) else p
                }))
            }
        if (!person.archived) {
            builder.setNeutralButton(strings.btnArchive) { _, _ ->
                persist(state.copy(people = state.people.map { p ->
                    if (p.id == person.id) p.copy(archived = true) else p
                }))
            }
        }
        builder.show()
    }

    private fun profilePhotoPreview(person: Person): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (person.photoUri != null) {
                    runCatching { setImageURI(Uri.parse(person.photoUri)) }
                        .onFailure { setImageResource(R.drawable.ic_default_avatar) }
                } else {
                    setImageResource(R.drawable.ic_default_avatar)
                }
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: android.view.View, o: android.graphics.Outline) {
                        o.setOval(0, 0, v.width, v.height)
                    }
                }
            }, LinearLayout.LayoutParams(dp(112), dp(112)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
    }

    private fun choosePhotoSource(personId: String) {
        AlertDialog.Builder(this)
            .setTitle(strings.titlePhotoSource)
            .setItems(arrayOf(strings.optCamera, strings.optGallery)) { _, which ->
                when (which) {
                    0 -> {
                        pendingPhotoPersonId = personId
                        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            launchCamera(personId)
                        } else {
                            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
                        }
                    }
                    1 -> {
                        pendingPhotoPersonId = personId
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "image/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        }
                        startActivityForResult(intent, PHOTO_REQUEST)
                    }
                }
            }
            .show()
    }

    private fun launchCamera(personId: String) {
        val dir = File(cacheDir, "profile_photos").also { it.mkdirs() }
        val file = File(dir, "photo_${personId}_${System.currentTimeMillis()}.jpg")
        pendingCameraFile = file
        pendingPhotoPersonId = personId
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
        }
        startActivityForResult(intent, CAMERA_REQUEST)
    }

    private fun parseBirthDate(text: String): java.time.LocalDate? {
        if (text.isBlank()) return null
        return runCatching { java.time.LocalDate.parse(text, java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrNull()
    }

    private fun applyDateMask(editText: EditText) {
        // TYPE_CLASS_PHONE shows numeric keyboard and accepts '/' inserted by the watcher
        editText.inputType = android.text.InputType.TYPE_CLASS_PHONE
        editText.addTextChangedListener(object : android.text.TextWatcher {
            private var isUpdating = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUpdating || s == null) return
                isUpdating = true
                val digits = s.filter { it.isDigit() }.take(8)
                val formatted = buildString {
                    digits.forEachIndexed { i, c ->
                        if (i == 2 || i == 4) append('/')
                        append(c)
                    }
                }
                if (s.toString() != formatted) {
                    s.replace(0, s.length, formatted)
                }
                editText.setSelection(minOf(formatted.length, editText.text?.length ?: 0))
                isUpdating = false
            }
        })
    }

    private fun showTaskDialog() {
        val layout = dialogLayout()
        val titleInput = edit(strings.hintActivityName, "")
        val periodicityItems = listOf(strings.periodicityDaily, strings.periodicityWeekly, strings.periodicityMonthly)
        val periodicitySpinner = spinner(periodicityItems)
        layout.addView(titleInput)
        layout.addView(label(strings.labelPeriodicity2))
        layout.addView(periodicitySpinner)
        AlertDialog.Builder(this)
            .setTitle(strings.titleNewActivity)
            .setView(layout)
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnSave) { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isBlank()) { toast(strings.toastInformName); return@setPositiveButton }
                val participants = state.people.filterNot { it.archived }.take(2).map { it.id }
                if (participants.size < 2) { toast(strings.toastRegisterTwo); return@setPositiveButton }
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
        if (candidates.isEmpty()) { toast(strings.toastNoPeopleAvailable); return }
        val personSpinner = spinner(candidates.map { it.name })
        val layout = dialogLayout().apply { addView(label(strings.labelPerson)); addView(personSpinner) }
        AlertDialog.Builder(this)
            .setTitle(strings.btnAssociatePerson)
            .setView(layout)
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnAssociate) { _, _ ->
                val person = candidates[personSpinner.selectedItemPosition]
                val updatedTasks = state.tasks.map { if (it.id == task.id) rotation.addParticipantAndMakeCurrent(it, person.id) else it }
                persist(state.copy(tasks = updatedTasks))
                toast(String.format(strings.toastNextEvent, person.name))
            }
            .show()
    }

    private fun showRemoveTaskParticipantDialog(task: ActivityTask) {
        val participants = task.participantIds.mapNotNull { id -> state.people.firstOrNull { it.id == id } }
        if (participants.isEmpty()) return
        val personSpinner = spinner(participants.map { it.name })
        val layout = dialogLayout().apply { addView(label(strings.labelPerson)); addView(personSpinner) }
        AlertDialog.Builder(this)
            .setTitle(strings.btnRemovePerson)
            .setView(layout)
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnDisassociate) { _, _ ->
                val person = participants[personSpinner.selectedItemPosition]
                val updatedTasks = state.tasks.map { if (it.id == task.id) rotation.removeParticipant(it, person.id) else it }
                persist(state.copy(tasks = updatedTasks))
            }
            .show()
    }

    private fun showMissionDialog() {
        val people = state.people.filterNot { it.archived }
        if (people.isEmpty()) { toast(strings.toastInformName); return }
        val layout = friendlyDialogLayout(strings.titleNewMission, strings.subtitleNewMission)
        val titleInput = edit(strings.hintMissionName, "")
        val rewardInput = edit(strings.hintRewardPoints, "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val phaseInput = edit(strings.hintPhaseName, "")
        val phaseCountInput = edit(strings.hintPhaseCount, "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val phases = mutableListOf<MissionPhase>()
        val phaseList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val errorText = text("", 13, true).apply { setTextColor(COLOR_CORAL); visibility = View.GONE; setPadding(0, dp(8), 0, dp(4)) }
        fun showErr(msg: String) { errorText.text = msg; errorText.visibility = View.VISIBLE }
        fun clearErr() { errorText.text = ""; errorText.visibility = View.GONE }
        fun refreshPhaseList() {
            phaseList.removeAllViews()
            if (phases.isEmpty()) {
                phaseList.addView(text(strings.noPhasesAdded, 13, false).apply { setTextColor(COLOR_MUTED); setPadding(0, dp(6), 0, dp(6)) })
            } else {
                phases.forEachIndexed { index, phase ->
                    phaseList.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = roundedDrawable(COLOR_BLUE_SOFT, 14f, 1, COLOR_BLUE)
                        setPadding(dp(10), dp(8), dp(8), dp(8))
                        addView(text("${index + 1}. ${phase.title}", 14, true), LinearLayout.LayoutParams(0, -2, 1f))
                        addView(text(strings.btnRemove, 12, true).apply {
                            gravity = Gravity.CENTER
                            setTextColor(COLOR_CORAL)
                            setOnClickListener { phases.removeAt(index); refreshPhaseList() }
                        }, LinearLayout.LayoutParams(dp(78), dp(34)))
                    }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
                }
            }
        }
        val participantChecks = people.map { person -> CheckBox(this).apply { text = person.name; textSize = 14f; setTextColor(COLOR_INK); isChecked = true } }
        layout.addView(label(strings.labelMissionData))
        layout.addView(titleInput)
        layout.addView(rewardInput)
        layout.addView(label(strings.labelParticipantsReceive))
        participantChecks.forEach { layout.addView(it) }
        layout.addView(label(strings.labelPhases))
        layout.addView(phaseInput)
        layout.addView(secondaryButton(strings.btnAddPhaseInDialog) {
            val title = phaseInput.text.toString().trim()
            if (title.isBlank()) { showErr(strings.toastInformName); return@secondaryButton }
            clearErr(); phases.add(MissionPhase(title = title)); phaseInput.setText(""); refreshPhaseList()
        })
        layout.addView(text(strings.orGenerateAuto, 13, true).apply { setTextColor(COLOR_MUTED); setPadding(0, dp(14), 0, dp(4)) })
        layout.addView(phaseCountInput)
        layout.addView(secondaryButton(strings.btnGeneratePhases) {
            val count = phaseCountInput.text.toString().toIntOrNull() ?: 0
            if (count <= 0) { showErr(strings.toastAmountGreaterZero); return@secondaryButton }
            clearErr(); phases.clear()
            phases.addAll((1..count).map { MissionPhase(title = String.format(strings.phaseTemplate, it, count)) }); refreshPhaseList()
        })
        refreshPhaseList(); layout.addView(phaseList); layout.addView(errorText)
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnSave, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text.toString().trim()
                val reward = rewardInput.text.toString().toIntOrNull() ?: 0
                val selectedParticipants = people.zip(participantChecks).filter { it.second.isChecked }.map { it.first.id }
                if (title.isBlank()) { showErr(strings.toastInformName); return@setOnClickListener }
                if (reward <= 0) { showErr(strings.toastAmountGreaterZero); return@setOnClickListener }
                if (selectedParticipants.isEmpty()) { showErr(strings.toastNoPeopleAvailable); return@setOnClickListener }
                if (phases.isEmpty()) { showErr(strings.btnAddPhaseInDialog); return@setOnClickListener }
                val mission = Mission(title = title, rewardPersonId = selectedParticipants.first(), rewardAmount = reward, participantIds = selectedParticipants, phases = phases.toList())
                persist(state.copy(missions = state.missions + mission)); dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showMissionProgressDialog(missionId: String) {
        val mission = state.missions.firstOrNull { it.id == missionId } ?: return
        val layout = friendlyDialogLayout("Avancar missao", mission.title)
        if (mission.participantIds.isEmpty()) {
            layout.addView(empty(strings.noParticipants))
        }
        mission.participantIds.forEach { personId ->
            val personName = state.people.firstOrNull { it.id == personId }?.name ?: strings.noPersonAssigned
            val paid = personId in mission.rewardPaidParticipantIds
            layout.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(Color.WHITE, 18f, 1, if (paid) COLOR_GREEN else COLOR_CARD_STROKE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(personName, 17, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text("${mission.completedPhaseCountFor(personId)}/${mission.phases.size}", 14, true).apply {
                        setTextColor(if (paid) COLOR_GREEN else COLOR_BLUE)
                    })
                })
                if (paid) {
                    addView(text("Recompensa ja creditada", 12, true).apply {
                        setTextColor(COLOR_GREEN)
                        setPadding(0, dp(4), 0, dp(4))
                    })
                }
                mission.phases.forEach { phase ->
                    addView(CheckBox(context).apply {
                        text = phase.title
                        textSize = 14f
                        setTextColor(COLOR_INK)
                        isChecked = personId in phase.checkedParticipantIds
                        isEnabled = !paid
                        setOnCheckedChangeListener { _, checked ->
                            toggleMissionPhase(mission.id, personId, phase.id, checked)
                        }
                    })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(12))
            })
        }
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton(strings.btnClose, null)
            .show()
    }

    private fun showMissionPhaseDialog(mission: Mission) {
        val layout = dialogLayout()
        val titleInput = edit(strings.hintPhaseName, "")
        layout.addView(titleInput)
        AlertDialog.Builder(this)
            .setTitle(strings.btnAddPhase)
            .setView(layout)
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnSave) { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isBlank()) { toast(strings.toastInformName); return@setPositiveButton }
                val updatedMissions = state.missions.map { if (it.id == mission.id) it.copy(phases = it.phases + MissionPhase(title = title)) else it }
                persist(state.copy(missions = updatedMissions))
            }
            .show()
    }

    private fun showAddMissionParticipantDialog(mission: Mission) {
        val candidates = state.people.filterNot { it.archived || it.id in mission.participantIds }
        if (candidates.isEmpty()) { toast(strings.toastNoPeopleAvailable); return }
        val personSpinner = spinner(candidates.map { it.name })
        val layout = dialogLayout().apply { addView(label(strings.labelPerson)); addView(personSpinner) }
        AlertDialog.Builder(this)
            .setTitle(strings.btnAssociatePerson)
            .setView(layout)
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnAssociate) { _, _ ->
                val person = candidates[personSpinner.selectedItemPosition]
                val updatedMissions = state.missions.map { if (it.id == mission.id) it.copy(participantIds = it.participantIds + person.id) else it }
                persist(state.copy(missions = updatedMissions))
            }
            .show()
    }

    private fun showRemoveMissionParticipantDialog(mission: Mission) {
        val participants = mission.participantIds.mapNotNull { id -> state.people.firstOrNull { it.id == id } }
        if (participants.isEmpty()) return
        val personSpinner = spinner(participants.map { it.name })
        val layout = dialogLayout().apply { addView(label(strings.labelPerson)); addView(personSpinner) }
        AlertDialog.Builder(this)
            .setTitle(strings.btnRemovePerson)
            .setView(layout)
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnDisassociate) { _, _ ->
                val person = participants[personSpinner.selectedItemPosition]
                val updatedMissions = state.missions.map { if (it.id == mission.id) it.copy(participantIds = it.participantIds.filterNot { id -> id == person.id }) else it }
                persist(state.copy(missions = updatedMissions))
            }
            .show()
    }

    private fun confirmDeleteMission(missionId: String, missionTitle: String) {
        AlertDialog.Builder(this)
            .setTitle(strings.btnDeleteMission)
            .setMessage(String.format(strings.confirmDeleteMission, missionTitle))
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnDeleteMission) { _, _ ->
                persist(state.copy(missions = state.missions.filterNot { it.id == missionId }))
                toast(strings.toastMissionDeleted)
            }
            .show()
    }

    private fun showTransactionTypeChooser() {
        AlertDialog.Builder(this)
            .setTitle(strings.titleMoveBalance)
            .setItems(arrayOf(strings.optAddBalance, strings.optWithdraw, strings.optPenalize)) { _, which ->
                when (which) {
                    0 -> showTransactionDialog(TransactionType.DEPOSIT)
                    1 -> showTransactionDialog(TransactionType.WITHDRAW)
                    else -> showTransactionDialog(TransactionType.PENALTY)
                }
            }
            .show()
    }

    private fun showTransactionDialog(defaultType: TransactionType = TransactionType.DEPOSIT, defaultPersonId: String? = null) {
        val people = state.people.filterNot { it.archived }
        if (people.isEmpty()) { toast(strings.toastInformName); return }
        var inputInReais = false
        val layout = dialogLayout()
        val personSpinner = spinner(people.map { it.name })
        val typeSpinner = spinner(TransactionType.entries.map { txTypeLabel(it) })
        val amountInput = edit(strings.hintAmountPoints, "").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val reasonInput = when (defaultType) {
            TransactionType.PENALTY -> edit(strings.optPenalize, "")
            TransactionType.WITHDRAW -> edit(strings.optWithdraw, "")
            TransactionType.DEPOSIT -> edit(strings.optAddBalance, "")
            TransactionType.DAILY_ALLOWANCE -> edit(strings.typeDailyAllowance, "")
        }
        val ptBtn = TextView(this).apply { text = strings.unitPoints; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        val brlBtn = TextView(this).apply { text = strings.unitBRL; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        fun refreshToggle() {
            ptBtn.background = roundedDrawable(if (!inputInReais) COLOR_ORANGE_PRI else COLOR_ORANGE_SOFT, 20f)
            ptBtn.setTextColor(if (!inputInReais) Color.WHITE else COLOR_ORANGE_PRI)
            brlBtn.background = roundedDrawable(if (inputInReais) COLOR_ORANGE_PRI else COLOR_ORANGE_SOFT, 20f)
            brlBtn.setTextColor(if (inputInReais) Color.WHITE else COLOR_ORANGE_PRI)
            amountInput.hint = if (inputInReais) strings.hintAmountBRL else strings.hintAmountPoints
        }
        ptBtn.setOnClickListener { inputInReais = false; refreshToggle() }
        brlBtn.setOnClickListener { inputInReais = true; refreshToggle() }
        refreshToggle()
        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ptBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
            addView(brlBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
            layoutParams = LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
        defaultPersonId?.let { id ->
            val idx = people.indexOfFirst { it.id == id }
            if (idx >= 0) personSpinner.setSelection(idx)
        }
        typeSpinner.setSelection(TransactionType.entries.indexOf(defaultType).coerceAtLeast(0))
        layout.addView(label(strings.labelPerson)); layout.addView(personSpinner)
        layout.addView(label(strings.labelType)); layout.addView(typeSpinner)
        layout.addView(label(strings.labelUnit)); layout.addView(toggleRow)
        layout.addView(amountInput); layout.addView(reasonInput)
        AlertDialog.Builder(this)
            .setTitle(strings.titleNewMovement)
            .setView(layout)
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnSave) { _, _ ->
                val rawValue = amountInput.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val amountInPoints = if (inputInReais) Math.round(rawValue / (state.pointValueCents / 100.0) * 100) / 100.0 else rawValue
                val reason = reasonInput.text.toString().trim()
                try { balances.validate(amountInPoints, reason) } catch (e: IllegalArgumentException) { toast(e.message ?: strings.toastInformAmount); return@setPositiveButton }
                val tx = RewardTransaction(personId = people[personSpinner.selectedItemPosition].id, type = TransactionType.entries[typeSpinner.selectedItemPosition], amount = amountInPoints, reason = reason)
                persist(state.copy(transactions = state.transactions + tx))
                defaultPersonId?.let { personId ->
                    Handler(Looper.getMainLooper()).post {
                        state.people.firstOrNull { it.id == personId }?.let { showPersonStatementDialog(it) }
                    }
                }
            }
            .show()
    }

    private fun reversePenalty(tx: RewardTransaction, person: Person) {
        AlertDialog.Builder(this)
            .setTitle(strings.btnReversePenalty)
            .setMessage(String.format(strings.confirmReversePenalty, tx.amount.formatPoints(), person.name, tx.reason))
            .setNegativeButton(strings.btnCancel, null)
            .setPositiveButton(strings.btnRevert) { _, _ ->
                val refund = RewardTransaction(personId = tx.personId, type = TransactionType.DEPOSIT, amount = tx.amount, reason = "${strings.reversalPrefix}${tx.reason}", createdAt = LocalDate.now())
                val updated = state.transactions.map { t -> if (t.id == tx.id) t.copy(reversed = true) else t } + refund
                persist(state.copy(transactions = updated))
                toast(strings.toastPenaltyReverted)
                showPersonStatementDialog(person)
            }
            .show()
    }

    private fun showPersonStatementDialog(person: Person) {
        val transactions = state.transactions.filter { it.personId == person.id }
            .sortedWith(compareByDescending<RewardTransaction> { it.createdAt }.thenByDescending { it.id })
        val balance = balances.balanceFor(person.id, state.transactions)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
            background = roundedDrawable(COLOR_PANEL, 20f)
        }
        layout.addView(text(strings.titleStatement, 26, true).apply { setTextColor(COLOR_INK) })
        layout.addView(text(person.name, 14, true).apply { setTextColor(COLOR_MUTED); setPadding(0, 0, 0, dp(12)) })
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(Color.WHITE, 16f, 1, COLOR_BORDER)
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text(strings.labelCurrentBalance, 13, true).apply { setTextColor(COLOR_MUTED) })
            addView(text("${balance.formatPoints()} pts", 28, true).apply { setTextColor(if (balance >= 0) COLOR_GREEN else COLOR_RED) })
            addView(text(formatReais(balance, state.pointValueCents), 15, true).apply { setTextColor(COLOR_GREEN) })
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(14)) })
        layout.addView(text(strings.labelMovements, 17, true).apply { setPadding(0, 0, 0, dp(8)) })
        if (transactions.isEmpty()) {
            layout.addView(text(strings.noMovements, 15, false).apply { gravity = Gravity.CENTER; setTextColor(COLOR_MUTED); setPadding(0, dp(22), 0, dp(22)) })
        } else {
            transactions.forEach { tx ->
                val signed = tx.amount * tx.type.sign
                val isReversedPenalty = tx.type == TransactionType.PENALTY && tx.reversed
                val borderColor = when { isReversedPenalty -> COLOR_MUTED; signed >= 0 -> COLOR_GREEN; else -> COLOR_RED }
                val txRow = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundedDrawable(Color.WHITE, 14f, 1, borderColor)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(text(tx.reason, 15, true).apply {
                                if (isReversedPenalty) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                            })
                            addView(text("${tx.createdAt} | ${txTypeLabel(tx.type)}${if (isReversedPenalty) " · ${strings.labelReversed}" else ""}", 12, false).apply { setTextColor(COLOR_MUTED) })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER or Gravity.END
                            addView(text("${signed.formatSigned()} pts", 16, true).apply {
                                gravity = Gravity.END
                                setTextColor(if (isReversedPenalty) COLOR_MUTED else if (signed >= 0) COLOR_GREEN else COLOR_RED)
                            })
                            addView(text(formatReais(signed, state.pointValueCents), 11, false).apply { gravity = Gravity.END; setTextColor(COLOR_MUTED) })
                        })
                    })
                    if (tx.type == TransactionType.PENALTY && !tx.reversed) {
                        addView(TextView(context).apply {
                            text = strings.btnReversePenalty
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(COLOR_BLUE)
                            setPadding(0, dp(6), 0, 0)
                            setOnClickListener { reversePenalty(tx, person) }
                        })
                    }
                }
                layout.addView(txRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
            }
        }
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(primaryButton(strings.btnAddBalance) { showTransactionDialog(TransactionType.DEPOSIT, person.id) }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(secondaryButton(strings.btnWithdraw) { showTransactionDialog(TransactionType.WITHDRAW, person.id) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, dp(8), dp(8), 0) })
                addView(secondaryButton(strings.btnPenalize) { showTransactionDialog(TransactionType.PENALTY, person.id) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, dp(8), 0, 0) })
            })
        })
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton(strings.btnClose, null)
            .show()
    }

    private fun invertFutureAssignments(taskId: String) {
        persist(state.copy(tasks = state.tasks.map { if (it.id == taskId) it.copy(rotationOffset = it.rotationOffset + 1) else it }))
        toast(strings.toastSequenceInverted)
    }

    private fun toggleMissionPhase(missionId: String, personId: String, phaseId: String, checked: Boolean) {
        var rewardTransaction: RewardTransaction? = null
        val updatedMissions = state.missions.map { mission ->
            if (mission.id == missionId && personId !in mission.rewardPaidParticipantIds) {
                val updatedPhases = mission.phases.map { phase ->
                    if (phase.id == phaseId) {
                        val ids = if (checked) {
                            (phase.checkedParticipantIds + personId).distinct()
                        } else {
                            phase.checkedParticipantIds.filterNot { it == personId }
                        }
                        phase.copy(checkedParticipantIds = ids, checked = ids.containsAll(mission.participantIds))
                    } else {
                        phase
                    }
                }
                val updatedMission = mission.copy(phases = updatedPhases)
                if (updatedMission.shouldCreditRewardFor(personId)) {
                    rewardTransaction = RewardTransaction(
                        personId = personId,
                        type = TransactionType.DEPOSIT,
                        amount = mission.rewardAmount.toDouble(),
                        reason = "Missao concluida: ${mission.title}"
                    )
                    updatedMission.markRewardPaidFor(personId)
                } else {
                    updatedMission
                }
            } else {
                mission
            }
        }
        val transactions = rewardTransaction?.let { state.transactions + it } ?: state.transactions
        persist(state.copy(missions = updatedMissions, transactions = transactions))
        rewardTransaction?.let {
            val personName = state.people.firstOrNull { person -> person.id == personId }?.name ?: strings.noPersonAssigned
            showMissionRewardDialog(personName, it.amount)
        }
    }

    private fun showMissionRewardDialog(personName: String, points: Double) {
        AlertDialog.Builder(this)
            .setTitle(strings.statusCompleted)
            .setMessage("$personName completou a missao. Sera adicionado ${points.formatPoints()} pontos a sua carteira.")
            .setPositiveButton(strings.btnClose, null)
            .show()
    }

    private fun completeMission(missionId: String) {
        val mission = state.missions.firstOrNull { it.id == missionId } ?: return
        val unpaidReadyParticipantIds = mission.participantIds
            .filter { mission.shouldCreditRewardFor(it) }
        if (unpaidReadyParticipantIds.isEmpty()) { toast(strings.toastAllPhases); return }
        val recipientIds = mission.participantIds.ifEmpty { listOf(mission.rewardPersonId).filter { it.isNotBlank() } }
        if (recipientIds.isEmpty()) { toast(strings.toastNoParticipants); return }
        val transactions = unpaidReadyParticipantIds.map { personId ->
            RewardTransaction(personId = personId, type = TransactionType.DEPOSIT, amount = mission.rewardAmount.toDouble(), reason = "Missao concluida: ${mission.title}")
        }
        val updatedMissions = state.missions.map {
            if (it.id == mission.id) {
                it.copy(
                    completedAt = if ((it.rewardPaidParticipantIds + unpaidReadyParticipantIds).distinct().containsAll(it.participantIds)) LocalDate.now() else it.completedAt,
                    rewardTransactionId = transactions.firstOrNull()?.id ?: it.rewardTransactionId,
                    rewardPaidParticipantIds = (it.rewardPaidParticipantIds + unpaidReadyParticipantIds).distinct()
                )
            } else it
        }
        persist(state.copy(missions = updatedMissions, transactions = state.transactions + transactions))
        toast(strings.toastRewardCredited)
    }

    private fun chooseExportFile() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "joao-e-pedro-tasks-backup.json")
        }, EXPORT_REQUEST)
    }

    private fun chooseImportFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
        }, IMPORT_REQUEST)
    }

    private fun exportTo(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(repository.exportText(state).toByteArray()) } ?: error("Cannot open file.")
        }.onSuccess { toast(strings.toastBackupExported) }.onFailure { toast(strings.toastInformAmount) }
    }

    private fun importFrom(uri: Uri) {
        runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Cannot read file.")
            repository.importText(text)
        }.onSuccess { state = it; render(); toast(strings.toastBackupRestored) }
         .onFailure { toast("${strings.toastInformAmount}: ${it.message}") }
    }

    private fun persist(newState: AppState) { state = newState; repository.save(state); render() }

    private fun periodicityLabel(p: Periodicity) = when (p) {
        Periodicity.DAILY -> strings.periodicityDaily
        Periodicity.WEEKLY -> strings.periodicityWeekly
        Periodicity.MONTHLY -> strings.periodicityMonthly
    }

    private fun txTypeLabel(t: TransactionType) = when (t) {
        TransactionType.DEPOSIT -> strings.typeDeposit
        TransactionType.WITHDRAW -> strings.typeWithdraw
        TransactionType.PENALTY -> strings.typePenalty
        TransactionType.DAILY_ALLOWANCE -> strings.typeDailyAllowance
    }

    private fun languageName(lang: String) = when (lang) { "es" -> "Español"; "en" -> "English"; else -> "Português" }

    private fun formatReais(points: Double, pointValueCents: Int): String {
        val total = points * pointValueCents / 100.0
        return String.format(java.util.Locale("pt", "BR"), "R$ %.2f", total)
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

    private fun sectionTitle(value: String): TextView = text(value, 24, true).apply { setPadding(0, dp(4), 0, dp(12)) }
    private fun empty(value: String): TextView = text(value, 16, false).apply { setTextColor(COLOR_MUTED) }
    private fun label(value: String): TextView = text(value, 13, true).apply { setPadding(0, dp(10), 0, dp(4)) }

    private fun text(value: String, size: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value; textSize = size.toFloat(); setTextColor(COLOR_INK)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.08f)
        }
    }

    private fun edit(hintValue: String, defaultValue: String): EditText {
        return EditText(this).apply { hint = hintValue; setText(defaultValue); setSingleLine(false) }
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
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
    }

    private fun friendlyDialogLayout(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(COLOR_PANEL, 20f)
            setPadding(dp(18), dp(16), dp(18), dp(12))
            addView(text(title, 24, true).apply { setTextColor(COLOR_INK) })
            addView(text(subtitle, 14, false).apply { setTextColor(COLOR_MUTED); setPadding(0, dp(2), 0, dp(14)) })
        }
    }

    private fun stackedButton(value: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = value; gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) COLOR_INK else COLOR_ORANGE_PRI)
            background = roundedDrawable(if (primary) COLOR_AMBER else Color.WHITE, 18f, if (primary) 0 else 2, COLOR_ORANGE_PRI)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(12), 0, 0) }
        }
    }

    private fun primaryButton(value: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = value; isAllCaps = false; setTextColor(COLOR_INK); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            background = roundedDrawable(COLOR_AMBER, 24f); elevation = dp(3).toFloat()
            setPadding(dp(16), 0, dp(16), 0); setOnClickListener { onClick() }
        }
    }

    private fun secondaryButton(value: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = value; gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ORANGE_PRI)
            background = roundedDrawable(Color.WHITE, 24f, 2, COLOR_ORANGE_PRI)
            setPadding(dp(12), 0, dp(12), 0); setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(14), 0, 0) }
        }
    }

    private fun roundedDrawable(color: Int, radiusDp: Float, strokeWidthDp: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; setColor(color)
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
                target.setPadding(left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop, right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom)
            }
            insets
        }
        view.requestApplyInsets()
    }

    private fun View.withMargins(bottom: Int): View {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(bottom)) }
        return this
    }

    private fun Double.formatPoints(): String {
        val s = String.format(java.util.Locale.US, "%.2f", this)
        return s.trimEnd('0').trimEnd('.')
    }

    private fun Double.formatSigned(): String { val f = formatPoints(); return if (this > 0) "+$f" else f }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class Tab { Today, People, Tasks, Missions, Punishments, Dashboards, Rewards, Data }
    private fun calcAge(birthDate: LocalDate): Int = java.time.Period.between(birthDate, LocalDate.now()).years

    private companion object {
        const val EXPORT_REQUEST           = 1101
        const val IMPORT_REQUEST           = 1102
        const val PHOTO_REQUEST            = 1103
        const val CAMERA_REQUEST           = 1104
        const val CAMERA_PERMISSION_REQUEST = 1105
        const val NOTIFICATION_PERMISSION_REQUEST = 1106
        val COLOR_PAGE         = Color.rgb(255, 255, 255)
        val COLOR_INK          = Color.rgb(38, 50, 56)
        val COLOR_MUTED        = Color.rgb(130, 130, 140)
        val COLOR_BLUE         = Color.rgb(77, 163, 255)
        val COLOR_BLUE_SOFT    = Color.rgb(229, 243, 255)
        val COLOR_ORANGE_PRI   = Color.rgb(255, 140, 66)
        val COLOR_ORANGE_SOFT  = Color.rgb(255, 236, 220)
        val COLOR_AMBER        = Color.rgb(255, 193, 7)
        val COLOR_CORAL        = Color.rgb(255, 138, 101)
        val COLOR_CORAL_SOFT   = Color.rgb(255, 236, 229)
        val COLOR_GREEN        = Color.rgb(76, 175, 80)
        val COLOR_RED          = Color.rgb(220, 38, 38)
        val COLOR_PANEL        = Color.rgb(250, 250, 252)
        val COLOR_ACCENT       = Color.rgb(255, 140, 66)
        val COLOR_BORDER       = Color.rgb(230, 225, 218)
        val COLOR_CARD_STROKE  = Color.rgb(255, 220, 190)
        val COLOR_DISABLED     = Color.rgb(244, 241, 235)
        val COLOR_DISABLED_STROKE = Color.rgb(229, 224, 216)
        val COLOR_NAV_BG       = Color.rgb(255, 255, 255)
    }
}
