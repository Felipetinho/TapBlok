package com.cj.tapblok

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.cj.tapblok.database.BlockedApp
import com.cj.tapblok.database.BlockedAppDao
import com.cj.tapblok.ui.theme.TapBlokTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AppInfo(
    val appName: String,
    val packageName: String,
    val isSelected: Boolean = false,
    // Valores de configuração por app, lidos do banco pra mostrar na tela.
    val dailyBudgetMinutes: Int = 0,
    val cooldownMinutes: Int = 0
)

class AppSelectionViewModel(private val blockedAppDao: BlockedAppDao, private val application: Application) : ViewModel() {

    companion object {
        private val EXCLUDED_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.dialer",
            "com.sonyericsson.android.socialphonebook",
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            // Camera apps across major OEMs — the website promises the camera
            // can never be blocked
            "com.android.camera",
            "com.android.camera2",
            "com.google.android.GoogleCamera",
            "com.sec.android.app.camera",
            "com.oneplus.camera",
            "com.oplus.camera",
            "com.xiaomi.camera",
            "com.huawei.camera",
            "com.motorola.camera3",
            "org.codeaurora.snapcam",
            "com.sonymobile.photopro",
            "com.asus.camera"
        )
    }
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    init {
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = application.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val allApps = pm.queryIntentActivities(intent, 0)

            val baseAppList = allApps.mapNotNull { app ->
                val packageName = app.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == application.packageName || EXCLUDED_PACKAGES.contains(packageName)) {
                    return@mapNotNull null
                }
                try {
                    AppInfo(
                        appName = app.loadLabel(pm).toString(),
                        packageName = packageName
                    )
                } catch (e: Exception) {
                    android.util.Log.w("AppSelectionViewModel", "Skipping $packageName: ${e.message}")
                    null
                }
            }
                // queryIntentActivities returns one entry per launcher activity, so packages
                // with multiple launcher entries (Tasker, some OEM apps) would duplicate the
                // LazyColumn key and crash
                .distinctBy { it.packageName }
                .sortedBy { it.appName.lowercase() }

            blockedAppDao.getAllBlockedApps().collect { blockedApps ->
                // Indexa as fichas bloqueadas pelo pacote pra puxar orçamento/cooldown de cada uma.
                val blockedByPackage = blockedApps.associateBy { it.packageName }
                _apps.value = baseAppList.map { info ->
                    val blocked = blockedByPackage[info.packageName]
                    info.copy(
                        isSelected = blocked != null,
                        dailyBudgetMinutes = blocked?.dailyBudgetMinutes ?: 0,
                        cooldownMinutes = blocked?.cooldownMinutes ?: 0
                    )
                }
            }
        }
    }

    fun onAppSelectionChanged(app: AppInfo, isSelected: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isSelected) {
                blockedAppDao.insert(BlockedApp(packageName = app.packageName))
            } else {
                blockedAppDao.delete(BlockedApp(packageName = app.packageName))
            }
        }
    }

    // Salva orçamento e cooldown de um app. Garante que ele está bloqueado antes (insert é IGNORE,
    // então não sobrescreve quem já existe), depois grava os dois valores.
    fun onAppLimitsChanged(app: AppInfo, budgetMinutes: Int, cooldownMinutes: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            blockedAppDao.insert(BlockedApp(packageName = app.packageName))
            blockedAppDao.setBudgetMinutes(app.packageName, budgetMinutes)
            blockedAppDao.setCooldownMinutes(app.packageName, cooldownMinutes)
        }
    }

    fun selectAllApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val allAppPackages = apps.value.map { BlockedApp(it.packageName) }
            blockedAppDao.insertAll(allAppPackages)
        }
    }

    fun unselectAllApps() {
        viewModelScope.launch(Dispatchers.IO) {
            blockedAppDao.deleteAll()
        }
    }
}

class AppSelectionViewModelFactory(private val application: Application, private val blockedAppDao: BlockedAppDao) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppSelectionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppSelectionViewModel(blockedAppDao, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class AppSelectionActivity : ComponentActivity() {
    private val viewModel: AppSelectionViewModel by viewModels {
        AppSelectionViewModelFactory(
            application,
            (application as App).database.blockedAppDao()
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TapBlokTheme {
                val appList by viewModel.apps.collectAsState()
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Select Apps to Block") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                            actions = {
                                TextButton(onClick = { viewModel.selectAllApps() }) {
                                    Text("Select All")
                                }
                                TextButton(onClick = { viewModel.unselectAllApps() }) {
                                    Text("Unselect All")
                                }
                            }
                        )
                    }
                ) { padding ->
                    AppSelectionScreen(
                        apps = appList,
                        onAppCheckedChange = { app, isSelected ->
                            viewModel.onAppSelectionChanged(app, isSelected)
                        },
                        onAppLimitsChanged = { app, budget, cooldown ->
                            viewModel.onAppLimitsChanged(app, budget, cooldown)
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}

@Composable
fun AppSelectionScreen(
    apps: List<AppInfo>,
    onAppCheckedChange: (AppInfo, Boolean) -> Unit,
    onAppLimitsChanged: (AppInfo, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isServiceRunning by remember { mutableStateOf(isServiceRunning(context, AppMonitoringService::class.java)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceRunning = isServiceRunning(context, AppMonitoringService::class.java)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Qual app está com o diálogo de configuração aberto (null = nenhum).
    var configuringApp by remember { mutableStateOf<AppInfo?>(null) }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter { it.appName.contains(searchQuery.trim(), ignoreCase = true) }
    }

    Column(modifier = modifier.padding(all = 8.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )
        LazyColumn {
            items(filteredApps, key = { it.packageName }) { app ->
                AppListItem(
                    app = app,
                    onCheckedChange = { isSelected ->
                        onAppCheckedChange(app, isSelected)
                    },
                    onConfigureClick = { configuringApp = app },
                    isEnabled = !isServiceRunning
                )
            }
        }
    }

    // Diálogo de configuração de limites, aberto ao tocar no ícone de ajuste de um app.
    val editing = configuringApp
    if (editing != null) {
        LimitsDialog(
            app = editing,
            onDismiss = { configuringApp = null },
            onSave = { budget, cooldown ->
                onAppLimitsChanged(editing, budget, cooldown)
                configuringApp = null
            }
        )
    }
}

@Composable
fun LimitsDialog(
    app: AppInfo,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit
) {
    // Campos começam com os valores atuais do app (ou vazio se for 0 / não configurado).
    var budgetText by rememberSaveable {
        mutableStateOf(if (app.dailyBudgetMinutes > 0) app.dailyBudgetMinutes.toString() else "")
    }
    var cooldownText by rememberSaveable {
        mutableStateOf(if (app.cooldownMinutes > 0) app.cooldownMinutes.toString() else "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.appName) },
        text = {
            Column {
                Text(
                    "Deixe em branco (ou 0) para bloqueio total, sem orçamento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { new -> budgetText = new.filter { it.isDigit() } },
                    label = { Text("Orçamento diário (minutos)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = cooldownText,
                    onValueChange = { new -> cooldownText = new.filter { it.isDigit() } },
                    label = { Text("Cooldown (minutos, 0 = só a tag)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Ex: 180 minutos = 3 horas de espera.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val budget = budgetText.toIntOrNull() ?: 0
                val cooldown = cooldownText.toIntOrNull() ?: 0
                onSave(budget, cooldown)
            }) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun AppListItem(
    app: AppInfo,
    onCheckedChange: (Boolean) -> Unit,
    onConfigureClick: () -> Unit,
    isEnabled: Boolean
) {
    val context = LocalContext.current
    // Loaded once per row; the app can be uninstalled between list load and render,
    // in which case getApplicationIcon throws
    val appIcon = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isEnabled) { onCheckedChange(!app.isSelected) }
            .padding(vertical = 8.dp, horizontal = 8.dp)
    ) {
        Image(
            painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(appIcon)
                    .build()
            ),
            contentDescription = "${app.appName} icon",
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge
            )
            // Resumo dos limites configurados, só aparece pra app selecionado com orçamento.
            if (app.isSelected && app.dailyBudgetMinutes > 0) {
                val resumo = buildString {
                    append("${app.dailyBudgetMinutes} min/dia")
                    if (app.cooldownMinutes > 0) {
                        append(" · espera ${app.cooldownMinutes} min")
                    } else {
                        append(" · só a tag")
                    }
                }
                Text(
                    text = resumo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Botão de configurar limites, habilitado só quando o app está selecionado.
        TextButton(
            onClick = onConfigureClick,
            enabled = isEnabled && app.isSelected
        ) {
            Text("Limites")
        }
        Checkbox(
            checked = app.isSelected,
            onCheckedChange = onCheckedChange,
            enabled = isEnabled
        )
    }
}
