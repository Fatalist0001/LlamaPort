@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.llamaport.app.ui.models

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamaport.app.UiState
import com.llamaport.app.cloud.CloudCompat
import com.llamaport.app.cloud.CloudEditorUi
import com.llamaport.app.cloud.CloudProbeRow
import com.llamaport.app.cloud.CloudProvider
import com.llamaport.app.cloud.CloudProviderDraft
import com.llamaport.app.cloud.CloudWire
import com.llamaport.app.cloud.MaxTokensField
import com.llamaport.app.cloud.hostOf
import com.llamaport.app.cloud.maskKey
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.ui.components.Eyebrow
import com.llamaport.app.ui.components.Hairline
import com.llamaport.app.ui.components.ObsidianButton
import com.llamaport.app.ui.components.ObsidianButtonStyle
import com.llamaport.app.ui.components.ObsidianChip
import com.llamaport.app.ui.components.ObsidianIconButton
import com.llamaport.app.ui.components.ObsidianSwitch
import com.llamaport.app.ui.components.ObsidianTag
import com.llamaport.app.ui.components.StateDot
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.motion.Reveal
import com.llamaport.app.ui.motion.disclosureEnter
import com.llamaport.app.ui.motion.disclosureExit
import com.llamaport.app.ui.motion.pressable
import com.llamaport.app.ui.theme.CardShape
import com.llamaport.app.ui.theme.EyebrowStyle
import com.llamaport.app.ui.theme.MonoSmall
import com.llamaport.app.ui.theme.ObsidianTheme
import com.llamaport.app.ui.theme.PillShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Облачные модели" block of the model sheet: configured providers and their
 * models, next to the local GGUF block. Tap a model to make it the active one.
 */
@Composable
internal fun CloudModelsBlock(
    state: UiState,
    onSelect: (String, String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (CloudProvider) -> Unit,
    onDelete: (String) -> Unit,
) {
    val palette = ObsidianTheme.palette
    val enabled = !state.generating && !state.loadingModel
    var pendingDelete by remember { mutableStateOf<CloudProvider?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(text = "облачные модели", color = palette.textMuted)
            Spacer(Modifier.weight(1f))
            if (state.activeCloud != null) {
                StateDot(color = palette.silver, size = 4.dp)
                Spacer(Modifier.width(6.dp))
                Text("АКТИВНА", style = MonoSmall, color = palette.silver)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            "API-провайдеры",
            style = MaterialTheme.typography.headlineSmall,
            color = palette.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "OpenAI-совместимый адрес и ключ. Модель отвечает через интернет, " +
                "локальный файл при этом не нужен.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(12.dp))

        if (state.providers.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(palette.card)
                    .border(1.dp, palette.hairline, MaterialTheme.shapes.medium)
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    AppIcons.Cloud,
                    contentDescription = null,
                    tint = palette.textMuted,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    "Провайдеры не настроены",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Например: OpenRouter, OpenAI, vLLM или llama.cpp server.",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
            }
        } else {
            state.providers.forEachIndexed { index, provider ->
                key(provider.id) {
                    Reveal(index = index) {
                        ProviderCard(
                            provider = provider,
                            activeModelId = state.activeCloud
                                ?.takeIf { it.providerId == provider.id }
                                ?.modelId,
                            enabled = enabled,
                            onSelect = { modelId -> onSelect(provider.id, modelId) },
                            onEdit = { onEdit(provider) },
                            onDelete = { pendingDelete = provider },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        ObsidianButton(
            text = "Добавить провайдера",
            onClick = onAdd,
            style = ObsidianButtonStyle.Secondary,
            leadingIcon = Icons.Filled.Add,
            enabled = !state.generating,
        )
    }

    pendingDelete?.let { provider ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = palette.card,
            title = {
                Column {
                    Eyebrow(text = "облачные модели", color = palette.textMuted)
                    Spacer(Modifier.height(5.dp))
                    Text("Удалить провайдера?", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                Text(
                    "«${provider.name}» (${hostOf(provider.baseUrl)}) будет удалён вместе " +
                        "с ключом и списком моделей.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(provider.id)
                        pendingDelete = null
                    },
                ) {
                    Text("Удалить", color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Отмена", color = palette.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun ProviderCard(
    provider: CloudProvider,
    activeModelId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = ObsidianTheme.palette
    val active = activeModelId != null
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(CardShape)
            .background(palette.card)
            .border(1.dp, if (active) palette.silver.copy(alpha = 0.38f) else palette.hairline, CardShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(palette.nested)
                    .border(1.dp, palette.hairline, MaterialTheme.shapes.extraSmall),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Cloud,
                    contentDescription = null,
                    tint = if (active) palette.silver else palette.silverDim,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    hostOf(provider.baseUrl),
                    style = MonoSmall,
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ObsidianTag(maskKey(provider.apiKey).uppercase(Locale.ROOT))
                    ObsidianTag(
                        if (provider.models.isEmpty()) {
                            "НЕТ МОДЕЛЕЙ"
                        } else {
                            "${provider.models.size} МОД."
                        },
                    )
                }
            }
            ObsidianIconButton(
                icon = Icons.Outlined.Create,
                contentDescription = "Изменить провайдера",
                onClick = onEdit,
                enabled = enabled,
                tint = palette.textSecondary,
                size = 32.dp,
                iconSize = 15.dp,
                pressEffect = false,
            )
            ObsidianIconButton(
                icon = Icons.Outlined.Delete,
                contentDescription = "Удалить провайдера",
                onClick = onDelete,
                enabled = enabled,
                tint = palette.textMuted,
                size = 32.dp,
                iconSize = 15.dp,
                pressEffect = false,
            )
        }

        if (provider.models.isEmpty()) {
            Hairline()
            Text(
                "Список моделей пуст — измените провайдера и добавьте вручную.",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            )
        } else {
            Hairline()
            provider.models.forEach { modelId ->
                CloudModelRow(
                    modelId = modelId,
                    active = modelId == activeModelId,
                    enabled = enabled,
                    onClick = { onSelect(modelId) },
                )
            }
        }
    }
}

@Composable
private fun CloudModelRow(
    modelId: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = ObsidianTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, hapticsEnabled = true, onClick = onClick)
            .padding(start = 13.dp, end = 11.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(
            color = if (active) palette.silver else palette.textMuted,
            size = 4.dp,
            pulse = false,
        )
        Spacer(Modifier.width(9.dp))
        Text(
            modelId,
            style = MaterialTheme.typography.bodySmall,
            color = if (active) palette.textPrimary else palette.textSecondary,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Box(
                Modifier
                    .clip(PillShape)
                    .background(palette.silver.copy(alpha = 0.12f))
                    .border(1.dp, palette.silver.copy(alpha = 0.35f), PillShape)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text("АКТИВНА", style = EyebrowStyle, color = palette.silver)
            }
        }
    }
}

/**
 * Provider form shown inside the same bottom sheet: name, base URL, key, model
 * list fetched from `/models` with a manual fallback, and the API check.
 */
@Composable
internal fun ProviderEditorPage(
    draft: CloudProviderDraft,
    editor: CloudEditorUi,
    onDraftChange: (CloudProviderDraft) -> Unit,
    onFetch: (CloudProviderDraft) -> Unit,
    onProbe: (String) -> Unit,
    onSave: (CloudProviderDraft) -> Unit,
    onCancel: () -> Unit,
) {
    val palette = ObsidianTheme.palette
    var manualExpanded by rememberSaveable { mutableStateOf(false) }
    var manualInput by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val urlError = if (draft.baseUrl.isBlank()) {
        null
    } else {
        runCatching { CloudWire.normalizeBaseUrl(draft.baseUrl) }.exceptionOrNull()?.message
    }
    val valid = draft.name.isNotBlank() && draft.baseUrl.isNotBlank() && urlError == null
    val busy = editor.fetching || editor.probing

    // The /models call may discover that the host needs a /v1 suffix; adopt the
    // corrected URL instead of making the user retype it.
    LaunchedEffect(editor.resolvedBaseUrl) {
        val resolved = editor.resolvedBaseUrl ?: return@LaunchedEffect
        if (resolved != draft.baseUrl) onDraftChange(draft.copy(baseUrl = resolved))
    }

    fun toggleModel(modelId: String, checked: Boolean) {
        val models = if (checked) {
            if (modelId in draft.models) draft.models else draft.models + modelId
        } else {
            draft.models - modelId
        }
        onDraftChange(draft.copy(models = models))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Eyebrow(text = "облачные модели", color = palette.textMuted)
        Spacer(Modifier.height(7.dp))
        Text(
            if (draft.id == null) "Новый провайдер" else "Провайдер",
            style = MaterialTheme.typography.headlineSmall,
            color = palette.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "OpenAI-совместимый API: POST /chat/completions и GET /models. " +
                "Ключ хранится в приватной папке приложения и не попадает в резервные копии.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
        )
        Spacer(Modifier.height(16.dp))

        CloudField(
            label = "название",
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it.take(48))) },
            placeholder = "OpenRouter, мой сервер…",
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(12.dp))
        CloudField(
            label = "base url",
            value = draft.baseUrl,
            onValueChange = { onDraftChange(draft.copy(baseUrl = it.trim())) },
            placeholder = "https://api.openai.com/v1",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            error = urlError,
        )
        if (draft.baseUrl.trim().startsWith("http://")) {
            Spacer(Modifier.height(7.dp))
            Notice(
                text = "Адрес без шифрования: трафик и ключ передаются открыто. " +
                    "Используйте http только для своей локальной сети.",
                color = palette.warning,
            )
        }
        Spacer(Modifier.height(12.dp))
        CloudKeyField(
            value = draft.apiKey,
            onValueChange = { onDraftChange(draft.copy(apiKey = it.trim())) },
        )

        Spacer(Modifier.height(16.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(text = "модели", color = palette.textMuted)
            Spacer(Modifier.weight(1f))
            Text(
                if (draft.models.isEmpty()) "не выбраны" else "выбрано ${draft.models.size}",
                style = MonoSmall,
                color = palette.textMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Получите список у провайдера или добавьте идентификатор вручную, " +
                "если /models недоступен.",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textMuted,
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ObsidianButton(
                text = if (editor.fetching) "Получаю список…" else "Получить список моделей",
                onClick = { onFetch(draft) },
                style = ObsidianButtonStyle.Secondary,
                enabled = !busy && valid,
                fillWidth = false,
                modifier = Modifier.weight(1f),
            )
            ObsidianButton(
                text = "Вручную",
                onClick = { manualExpanded = !manualExpanded },
                style = ObsidianButtonStyle.Ghost,
                fillWidth = false,
                modifier = Modifier.weight(0.5f),
            )
        }
        editor.fetchError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Notice(text = error, color = palette.error)
        }
        val fetched = editor.fetchedModels.filterNot { it.isBlank() }
        // Ids added by hand (or kept from an earlier save) stay visible even
        // before a new /models call, otherwise editing an existing provider
        // would look like it has no models at all.
        val manualModels = draft.models.filterNot { it in fetched }
        if (manualModels.isNotEmpty() && fetched.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Найдено моделей: ${fetched.size}",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
            )
        }
        if (manualModels.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (fetched.isEmpty()) "Выбранные модели" else "Добавлены вручную",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textMuted,
            )
            Spacer(Modifier.height(4.dp))
            manualModels.forEach { modelId ->
                ManualModelRow(
                    modelId = modelId,
                    polling = editor.probing,
                    onRemove = { toggleModel(modelId, false) },
                    onProbe = { onProbe(modelId) },
                )
            }
        }
        if (fetched.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Список провайдера — отметьте нужные",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textMuted,
            )
            Spacer(Modifier.height(4.dp))
            fetched.forEach { modelId ->
                SelectableModelRow(
                    modelId = modelId,
                    checked = modelId in draft.models,
                    polling = editor.probing,
                    onCheckedChange = { checked -> toggleModel(modelId, checked) },
                    onProbe = { onProbe(modelId) },
                )
            }
        }

        AnimatedVisibility(
            visible = manualExpanded || (editor.fetchedModels.isEmpty() && draft.models.isEmpty()),
            enter = disclosureEnter(),
            exit = disclosureExit(),
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        CloudField(
                            label = "идентификатор модели",
                            value = manualInput,
                            onValueChange = { manualInput = it },
                            placeholder = "gpt-4o-mini",
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                            onDone = {
                                val id = manualInput.trim()
                                if (id.isNotEmpty()) {
                                    if (id !in draft.models) {
                                        onDraftChange(draft.copy(models = draft.models + id))
                                    }
                                    manualInput = ""
                                    focusManager.clearFocus()
                                }
                            },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    ObsidianIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "Добавить идентификатор",
                        onClick = {
                            val id = manualInput.trim()
                            if (id.isNotEmpty()) {
                                if (id !in draft.models) {
                                    onDraftChange(draft.copy(models = draft.models + id))
                                }
                                manualInput = ""
                                focusManager.clearFocus()
                            }
                        },
                        enabled = manualInput.isNotBlank(),
                        container = palette.nested,
                        borderColor = palette.hairline,
                        tint = palette.textPrimary,
                        size = 40.dp,
                        iconSize = 18.dp,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(text = "проверка api", color = palette.textMuted)
            Spacer(Modifier.weight(1f))
            if (editor.probing) {
                StateDot(color = palette.silver, size = 4.dp, pulse = true)
                Spacer(Modifier.width(6.dp))
                Text("ПРОВЕРКА", style = MonoSmall, color = palette.silver)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Кнопка ▶ у модели отправляет два коротких запроса и показывает, какие " +
                "параметры генерации этот API принимает.",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textMuted,
        )
        if (editor.probe.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            ProbeReport(rows = editor.probe)
        }

        Spacer(Modifier.height(20.dp))
        ObsidianButton(
            text = if (draft.id == null) "Сохранить провайдера" else "Сохранить изменения",
            onClick = { onSave(draft) },
            enabled = valid && !busy,
        )
        Spacer(Modifier.height(8.dp))
        ObsidianButton(
            text = "Отмена",
            onClick = onCancel,
            style = ObsidianButtonStyle.Ghost,
            enabled = !busy,
        )
    }
}

/** Compatibility controls reused by the generation settings sheet. */
@Composable
internal fun CloudCompatControls(
    compat: CloudCompat,
    probing: Boolean,
    probe: List<CloudProbeRow>,
    onUpdate: ((CloudCompat) -> CloudCompat) -> Unit,
    onProbe: () -> Unit,
) {
    val palette = ObsidianTheme.palette
    CompatSwitch(
        label = "Отправлять top_k",
        description = "Вне спецификации OpenAI: часть API отвергает это поле.",
        checked = compat.topK,
        onChange = { checked -> onUpdate { it.copy(topK = checked) } },
    )
    CompatSwitch(
        label = "Отправлять temperature",
        description = "Некоторые reasoning-модели принимают только значение по умолчанию.",
        checked = compat.temperature,
        onChange = { checked -> onUpdate { it.copy(temperature = checked) } },
    )
    CompatSwitch(
        label = "Отправлять top_p",
        description = "Отключается вместе с temperature на строгих API.",
        checked = compat.topP,
        onChange = { checked -> onUpdate { it.copy(topP = checked) } },
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Поле лимита токенов",
        style = MaterialTheme.typography.bodyMedium,
        color = palette.textPrimary,
    )
    Spacer(Modifier.height(6.dp))
    MaxTokensFieldControl(
        value = compat.maxTokensField,
        onChange = { field -> onUpdate { it.copy(maxTokensField = field) } },
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Последняя проверка: " + formatCheckedAt(compat.checkedAt),
        style = MaterialTheme.typography.labelSmall,
        color = palette.textMuted,
    )
    Spacer(Modifier.height(8.dp))
    ObsidianButton(
        text = if (probing) "Проверяю…" else "Проверить API",
        onClick = onProbe,
        style = ObsidianButtonStyle.Secondary,
        enabled = !probing,
    )
    if (probe.isNotEmpty()) {
        Spacer(Modifier.height(9.dp))
        ProbeReport(rows = probe)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProbeReport(rows: List<CloudProbeRow>) {
    val palette = ObsidianTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(palette.surface)
            .border(1.dp, palette.hairline, MaterialTheme.shapes.small)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        rows.forEach { row ->
            Row(
                Modifier.padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top,
            ) {
                StateDot(
                    color = if (row.ok) palette.success else palette.warning,
                    size = 4.dp,
                    pulse = false,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    row.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    row.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.ok) palette.textSecondary else palette.warning,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaxTokensFieldControl(
    value: MaxTokensField,
    onChange: (MaxTokensField) -> Unit,
) {
    val options = listOf(
        MaxTokensField.Auto to "Авто",
        MaxTokensField.MaxTokens to "max_tokens",
        MaxTokensField.MaxCompletionTokens to "max_completion",
        MaxTokensField.None to "Не отправлять",
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (field, label) ->
            ObsidianChip(
                text = label,
                selected = value == field,
                onClick = { onChange(field) },
            )
        }
    }
}

@Composable
private fun CompatSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val palette = ObsidianTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.textPrimary)
            Text(description, style = MaterialTheme.typography.labelSmall, color = palette.textMuted)
        }
        ObsidianSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ManualModelRow(
    modelId: String,
    polling: Boolean,
    onRemove: () -> Unit,
    onProbe: () -> Unit,
) {
    val palette = ObsidianTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(palette.silver)
                .border(1.dp, palette.silver, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Check,
                contentDescription = null,
                tint = palette.onSilver,
                modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                modelId,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "добавлено вручную",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textMuted,
            )
        }
        ProbeButton(polling = polling, onProbe = onProbe)
        ObsidianIconButton(
            icon = Icons.Outlined.Delete,
            contentDescription = "Убрать модель",
            onClick = onRemove,
            tint = palette.textMuted,
            size = 30.dp,
            iconSize = 14.dp,
            pressEffect = false,
        )
    }
}

@Composable
private fun SelectableModelRow(
    modelId: String,
    checked: Boolean,
    polling: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onProbe: () -> Unit,
) {
    val palette = ObsidianTheme.palette
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckedChange(it)
                },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fill = if (checked) palette.silver else Color.Transparent
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(fill)
                .border(
                    1.dp,
                    if (checked) palette.silver else palette.hairlineStrong,
                    RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    tint = palette.onSilver,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            modelId,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ProbeButton(polling = polling, onProbe = onProbe)
    }
}

@Composable
private fun ProbeButton(polling: Boolean, onProbe: () -> Unit) {
    val palette = ObsidianTheme.palette
    ObsidianIconButton(
        icon = AppIcons.Play,
        contentDescription = "Проверить API этой моделью",
        onClick = onProbe,
        enabled = !polling,
        tint = palette.silverDim,
        size = 30.dp,
        iconSize = 13.dp,
        pressEffect = false,
    )
}

@Composable
private fun CloudField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    error: String? = null,
    onDone: (() -> Unit)? = null,
) {
    val palette = ObsidianTheme.palette
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        error != null -> palette.error.copy(alpha = 0.6f)
        focused -> palette.silver.copy(alpha = 0.5f)
        else -> palette.hairline
    }
    val border by animateColorAsState(
        targetValue = borderColor,
        animationSpec = if (reduced) snap() else tween(MotionTokens.Durations.MICRO),
        label = "cloudFieldBorder",
    )
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(palette.surface)
                .border(1.dp, border, MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Column {
                Text(label.uppercase(Locale.ROOT), style = EyebrowStyle, color = palette.textMuted)
                Spacer(Modifier.height(3.dp))
                Box {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textMuted,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.textPrimary),
                        cursorBrush = SolidColor(palette.silver),
                        interactionSource = interaction,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboardType,
                            imeAction = imeAction,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                onDone?.invoke()
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = palette.error,
            )
        }
    }
}

@Composable
private fun CloudKeyField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val palette = ObsidianTheme.palette
    var visible by rememberSaveable { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        targetValue = if (focused) palette.silver.copy(alpha = 0.5f) else palette.hairline,
        animationSpec = if (reduced) snap() else tween(MotionTokens.Durations.MICRO),
        label = "cloudKeyBorder",
    )
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(palette.surface)
                .border(1.dp, border, MaterialTheme.shapes.small)
                .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("КЛЮЧ API", style = EyebrowStyle, color = palette.textMuted)
                    Spacer(Modifier.height(3.dp))
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                "необязательно для локального сервера",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textMuted,
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.textPrimary),
                            cursorBrush = SolidColor(palette.silver),
                            interactionSource = interaction,
                            visualTransformation = if (visible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                TextButton(onClick = { visible = !visible }) {
                    Text(
                        if (visible) "скрыть" else "показать",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.silverDim,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Хранится локально как ${maskKey(value)}. Ключ не пишется в логи.",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textMuted,
        )
    }
}

@Composable
private fun Notice(text: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.24f), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StateDot(color = color, size = 5.dp)
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private fun formatCheckedAt(millis: Long): String {
    if (millis <= 0L) return "не проверяли"
    val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT)
    return format.format(Date(millis))
}
