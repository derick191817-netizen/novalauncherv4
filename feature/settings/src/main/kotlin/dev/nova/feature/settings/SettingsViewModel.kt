package dev.nova.feature.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nova.core.common.AppDispatchers
import dev.nova.core.domain.model.IconPackInfo
import dev.nova.core.domain.model.LauncherSettings
import dev.nova.core.domain.usecase.ExportBackupUseCase
import dev.nova.core.domain.usecase.ImportBackupUseCase
import dev.nova.core.domain.usecase.ListIconPacksUseCase
import dev.nova.core.domain.usecase.ObserveSettingsUseCase
import dev.nova.core.domain.usecase.UpdateSettingsUseCase
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado da tela de configurações. */
@Immutable
data class SettingsUiState(
    val settings: LauncherSettings = LauncherSettings(),
    val iconPacks: List<IconPackInfo> = emptyList(),
)

/** Preferências (DataStore), icon packs e backup/restore. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    observeSettings: ObserveSettingsUseCase,
    private val updateSettings: UpdateSettingsUseCase,
    private val listIconPacks: ListIconPacksUseCase,
    private val exportBackup: ExportBackupUseCase,
    private val importBackup: ImportBackupUseCase,
    private val dispatchers: AppDispatchers,
) : ViewModel() {

    private val packs = MutableStateFlow<List<IconPackInfo>>(emptyList())
    private val _message = MutableStateFlow<String?>(null)

    /** Mensagem única para exibir (Toast); chame [messageShown] depois. */
    val message: StateFlow<String?> = _message

    val uiState: StateFlow<SettingsUiState> = combine(observeSettings(), packs) { s, p -> SettingsUiState(s, p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), SettingsUiState())

    init {
        viewModelScope.launch { packs.value = listIconPacks() }
    }

    fun update(transform: (LauncherSettings) -> LauncherSettings) {
        viewModelScope.launch { updateSettings(transform) }
    }

    fun messageShown() { _message.update { null } }

    /** Grava o backup JSON em [uri] (escolhido pelo seletor de arquivos; pode ser o Google Drive). */
    fun export(uri: Uri) {
        viewModelScope.launch {
            val ok = try {
                val json = exportBackup()
                withContext(dispatchers.io) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null
                }
            } catch (_: IOException) {
                false
            }
            _message.value = if (ok) "Backup salvo" else "Não foi possível salvar o backup"
        }
    }

    /** Restaura o backup JSON de [uri]. */
    fun import(uri: Uri) {
        viewModelScope.launch {
            val json = try {
                withContext(dispatchers.io) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
            } catch (_: IOException) {
                null
            }
            val ok = json != null && importBackup(json)
            _message.value = if (ok) "Backup restaurado" else "Arquivo de backup inválido"
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
