package dev.kaorios.patcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kaorios.patcher.storage.DownloadsStore
import dev.kaorios.patcher.ui.PatchViewModel
import dev.kaorios.patcher.ui.navigation.KaoriosPatcherApp
import dev.kaorios.patcher.ui.navigation.PatchActions
import dev.kaorios.patcher.ui.service.PrefsRepository
import dev.kaorios.patcher.ui.theme.KaoriosPatcherTheme
import dev.kaorios.patcher.ui.theme.PrefDefaults
import dev.kaorios.patcher.ui.theme.CHANNEL_BETA
import dev.kaorios.patcher.ui.theme.PREF_UPDATE_CHANNEL
import dev.kaorios.patcher.workspace.Workspace
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val prefs = remember(context) { PrefsRepository.create(context, PrefDefaults) }
            val workspace = remember(context) { Workspace(File(context.filesDir, "kaorios_workspace")) }
            val downloads = remember(context) { DownloadsStore(context) }
            val viewModel: PatchViewModel = viewModel {
                PatchViewModel(workspace = workspace, downloads = downloads)
            }
            val state by viewModel.state.collectAsState()
            val log by viewModel.log.collectAsState()

            LaunchedEffect(Unit) { viewModel.refresh() }

            // Check for updates whenever the app enters the foreground (throttled inside
            // the ViewModel). The channel preference is read once per check.
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) {
                        lifecycleScope.launch {
                            val channel = prefs.getString(PREF_UPDATE_CHANNEL, CHANNEL_BETA)
                            viewModel.checkForUpdates(channel)
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // Deliberately NO LocalNavigationEventDispatcherOwner provider here. It existed
            // because ComponentActivity up to 1.9.3 published no owner and Miuix's
            // `NavigationBackHandler` threw on null; activity-compose 1.13.0 publishes one
            // per window through the view tree (ComponentActivity.initializeViewTreeOwners
            // and ComponentDialog.initializeViewTreeOwners), and
            // `LocalNavigationEventDispatcherOwner.current` falls back to
            // findViewTreeNavigationEventDispatcherOwner() on the current window's view.
            // Providing a single composition-wide owner broke exactly that per-window
            // resolution: Miuix's popup handlers bound the activity's dispatcher while the
            // back gesture landed on the dialog's own dispatcher, so popups stopped
            // dismissing on back. Each window now binds its own fed dispatcher, which is
            // also what the shell's PredictiveBackHandler rides on the activity window.
            KaoriosPatcherTheme(prefs) {
                KaoriosPatcherApp(
                    state = state,
                    prefs = prefs,
                    log = log,
                    actions = PatchActions(
                        onCorePatchChange = viewModel::setCorePatch,
                        onFlagSecureChange = viewModel::setFlagSecure,
                        onHideDevStatusChange = viewModel::setHideDevStatus,
                        onVfsChange = viewModel::setVfs,
                        onPull = viewModel::pullArtifacts,
                        onPatch = viewModel::patchAndBuild,
                        onClear = viewModel::clearWorkspace,
                        onRefresh = viewModel::refresh,
                        onGrantStorage = {
                            context.startActivity(downloads.grantIntent())
                            viewModel.refreshStorageAccess()
                        },
                        onFlash = viewModel::flashModule,
                        onExportLog = viewModel::exportLog,
                        onInstallUpdate = viewModel::installUpdate,
                        onDismissUpdate = viewModel::dismissUpdate,
                        onChannelChange = { channel ->
                            viewModel.checkForUpdates(channel, force = true)
                        },
                    ),
                )
            }
        }
    }
}
