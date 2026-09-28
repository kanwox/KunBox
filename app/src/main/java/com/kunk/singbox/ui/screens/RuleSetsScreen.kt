package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.FloatingPageLayout
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.rememberLocalNetworkPermissionRequest
import com.kunk.singbox.ui.navigation.Screen
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.ui.theme.LiquidGlassDialogEffect
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassCheckboxColors
import com.kunk.singbox.ui.theme.liquidGlassDialogContainerColor
import com.kunk.singbox.ui.theme.liquidGlassDialogPanel
import com.kunk.singbox.ui.theme.liquidGlassEmptyStatePanel
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassPressFeedback
import com.kunk.singbox.ui.theme.liquidGlassTextButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassTextButtonColors
import com.kunk.singbox.ui.theme.liquidGlassTextButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal val defaultRuleSetTags = setOf(
    "geosite-cn",
    "geoip-cn",
    "geosite-geolocation-!cn",
    "geosite-category-ads-all",
    "geosite-private"
)

@Composable
private fun Modifier.ruleSetInboundOptionPanel(isSelected: Boolean): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(
            shape = RoundedCornerShape(10.dp),
            selected = isSelected,
            shadowElevation = 4.dp
        )
    } else {
        this
    }
}

@Composable
private fun Modifier.ruleSetSortItemPressFeedback(
    enabled: Boolean,
    onClick: () -> Unit
): Modifier = liquidGlassPressFeedback(
    enabled = enabled,
    label = "liquid_glass_rule_set_sort_item_scale",
    onClick = onClick
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RuleSetsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val downloadingRuleSets by settingsViewModel.downloadingRuleSets.collectAsStateWithLifecycle()
    val defaultRuleSetDownloadState by settingsViewModel.defaultRuleSetDownloadState.collectAsStateWithLifecycle()
    val allNodes by nodesViewModel.allNodes.collectAsStateWithLifecycle()
    val nodesForSelection by nodesViewModel.filteredAllNodes.collectAsStateWithLifecycle()
    val profiles by profilesViewModel.profiles.collectAsStateWithLifecycle()
    val requestLocalNetworkPermission = rememberLocalNetworkPermissionRequest()

    DisposableEffect(Unit) {
        nodesViewModel.setAllNodesUiActive(true)
        onDispose {
            nodesViewModel.setAllNodesUiActive(false)
        }
    }

    LaunchedEffect(settings.ruleSets.isEmpty()) {
        if (settings.ruleSets.isEmpty()) {
            settingsViewModel.ensureDefaultRuleSetsReady()
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingRuleSet by remember { mutableStateOf<RuleSet?>(null) }
    val listState = rememberLazyListState()

    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateMapOf<String, Boolean>() }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Outbound/Inbound dialog states
    var outboundEditingRuleSet by remember { mutableStateOf<RuleSet?>(null) }
    var showOutboundModeDialog by remember { mutableStateOf(false) }
    var showTargetSelectionDialog by remember { mutableStateOf(false) }
    var showNodeSelectionDialog by remember { mutableStateOf(false) }
    var showInboundDialog by remember { mutableStateOf(false) }
    var targetSelectionTitle by remember { mutableStateOf("") }
    var targetOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val availableInbounds = listOf("tun", "mixed")

    val selectionNodes = nodesForSelection

    // Helper functions for node resolution
    fun resolveNodeByStoredValue(value: String?): NodeUi? {
        if (value.isNullOrBlank()) return null
        val parts = value.split("::", limit = 2)
        if (parts.size == 2) {
            val profileId = parts[0]
            val name = parts[1]
            return allNodes.find { it.sourceProfileId == profileId && it.name == name }
        }
        return allNodes.find { it.id == value } ?: allNodes.find { it.name == value }
    }

    fun toNodeRef(node: NodeUi): String = "${node.sourceProfileId}::${node.name}"

    // Reordering State
    val ruleSets = remember { mutableStateListOf<RuleSet>() }
    val reorderableLazyColumnState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = ruleSets.indexOfFirst { it.id == from.key }
        val toIndex = ruleSets.indexOfFirst { it.id == to.key }
        if (fromIndex != -1 && toIndex != -1) {
            val item = ruleSets.removeAt(fromIndex)
            ruleSets.add(toIndex, item)
        }
    }

    LaunchedEffect(settings.ruleSets) {
        if (!reorderableLazyColumnState.isAnyItemDragging) {
            val currentIds = ruleSets.map { it.id }.toSet()
            val newIds = settings.ruleSets.map { it.id }.toSet()

            if (currentIds != newIds || ruleSets.size != settings.ruleSets.size || ruleSets.isEmpty()) {
                ruleSets.clear()
                ruleSets.addAll(settings.ruleSets)
            } else {
                if (ruleSets.map { it.toString() } != settings.ruleSets.map { it.toString() }) {
                    settings.ruleSets.forEach { newRule ->
                        val index = ruleSets.indexOfFirst { it.id == newRule.id }
                        if (index != -1 && ruleSets[index] != newRule) {
                            ruleSets[index] = newRule
                        }
                    }
                }
            }
        }
    }

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems.clear()
    }

    fun toggleSelection(id: String) {
        selectedItems[id] = !(selectedItems[id] ?: false)
        if (selectedItems.none { it.value }) {
            exitSelectionMode()
        }
    }

    if (showAddDialog) {
        RuleSetEditorDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { ruleSet ->
                settingsViewModel.addRuleSet(ruleSet)
                showAddDialog = false
            }
        )
    }

    if (editingRuleSet != null) {
        val currentRuleSet = checkNotNull(editingRuleSet)
        RuleSetEditorDialog(
            initialRuleSet = currentRuleSet,
            onDismiss = { editingRuleSet = null },
            onConfirm = { ruleSet ->
                settingsViewModel.updateRuleSet(ruleSet)
                editingRuleSet = null
            },
            onDelete = {
                settingsViewModel.deleteRuleSet(currentRuleSet.id)
                editingRuleSet = null
            }
        )
    }

    if (defaultRuleSetDownloadState.isActive) {
        DefaultRuleSetProgressDialog(
            state = defaultRuleSetDownloadState,
            onCancel = { settingsViewModel.cancelDefaultRuleSetDownload() }
        )
    }

    // Pre-load string resources for use in callbacks
    val profilesDeletedMsg = stringResource(R.string.profiles_deleted)
    val selectProfileMsg = stringResource(R.string.rulesets_select_profile)

    if (showDeleteConfirmDialog) {
        val selectedCount = selectedItems.count { it.value }
        ConfirmDialog(
            title = stringResource(R.string.rulesets_delete_title),
            message = stringResource(R.string.rulesets_delete_batch_confirm, selectedCount),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                val idsToDelete = selectedItems.filter { it.value }.keys.toList()
                settingsViewModel.deleteRuleSets(idsToDelete)
                AppNotificationManager.showMessage(navController.context, profilesDeletedMsg)
                showDeleteConfirmDialog = false
                exitSelectionMode()
            },
            onDismiss = { showDeleteConfirmDialog = false }
        )
    }

    // Outbound Mode Dialog
    if (showOutboundModeDialog && outboundEditingRuleSet != null) {
        val currentRuleSet = checkNotNull(outboundEditingRuleSet)
        val options = RuleSetOutboundMode.entries.map { stringResource(it.displayNameRes) }
        val currentMode = currentRuleSet.outboundMode ?: RuleSetOutboundMode.DIRECT
        SingleSelectDialog(
            title = stringResource(R.string.rulesets_select_outbound),
            options = options,
            selectedIndex = RuleSetOutboundMode.entries.indexOf(currentMode),
            onSelect = { index ->
                val selectedMode = RuleSetOutboundMode.entries[index]
                val updatedRuleSet = currentRuleSet.copy(
                    outboundMode = selectedMode,
                    outboundValue = null
                )

                if (selectedMode == RuleSetOutboundMode.NODE ||
                    selectedMode == RuleSetOutboundMode.PROFILE
                ) {
                    outboundEditingRuleSet = updatedRuleSet
                    showOutboundModeDialog = false

                    when (selectedMode) {
                        RuleSetOutboundMode.NODE -> {
                            showNodeSelectionDialog = true
                        }
                        RuleSetOutboundMode.PROFILE -> {
                            targetSelectionTitle = selectProfileMsg
                            targetOptions = profiles.map { it.name to it.id }
                        }
                    }
                    if (selectedMode != RuleSetOutboundMode.NODE) {
                        showTargetSelectionDialog = true
                    }
                } else if (selectedMode == RuleSetOutboundMode.DIRECT) {
                    requestLocalNetworkPermission {
                        settingsViewModel.updateRuleSet(updatedRuleSet)
                    }
                    outboundEditingRuleSet = null
                    showOutboundModeDialog = false
                } else {
                    settingsViewModel.updateRuleSet(updatedRuleSet)
                    outboundEditingRuleSet = null
                    showOutboundModeDialog = false
                }
            },
            onDismiss = {
                showOutboundModeDialog = false
                outboundEditingRuleSet = null
            }
        )
    }

    // Target Selection Dialog
    if (showTargetSelectionDialog && outboundEditingRuleSet != null) {
        val currentRuleSet = checkNotNull(outboundEditingRuleSet)
        val currentValue = currentRuleSet.outboundValue
        val currentRef = resolveNodeByStoredValue(currentValue)?.let { toNodeRef(it) } ?: currentValue
        val selectedIndex = targetOptions.indexOfFirst { it.second == currentRef }
        SingleSelectDialog(
            title = targetSelectionTitle,
            options = targetOptions.map { it.first },
            selectedIndex = selectedIndex.coerceAtLeast(0),
            onSelect = { index ->
                val selectedValue = targetOptions.getOrNull(index)?.second ?: return@SingleSelectDialog
                val updatedRuleSet = currentRuleSet.copy(outboundValue = selectedValue)
                settingsViewModel.updateRuleSet(updatedRuleSet)
                showTargetSelectionDialog = false
                outboundEditingRuleSet = null
            },
            onDismiss = {
                showTargetSelectionDialog = false
                outboundEditingRuleSet = null
            }
        )
    }

    if (showNodeSelectionDialog && outboundEditingRuleSet != null) {
        val currentRuleSet = checkNotNull(outboundEditingRuleSet)
        val currentValue = currentRuleSet.outboundValue
        val selectedNode = resolveNodeByStoredValue(currentValue)
        NodePickerPage(
            title = stringResource(R.string.rulesets_select_node),
            profiles = profiles,
            allNodes = allNodes,
            displayedNodes = selectionNodes,
            selectedNodeId = selectedNode?.id,
            onSelectNode = { node ->
                val updatedRuleSet = currentRuleSet.copy(outboundValue = toNodeRef(node))
                settingsViewModel.updateRuleSet(updatedRuleSet)
            },
            onDismiss = {
                showNodeSelectionDialog = false
                outboundEditingRuleSet = null
            }
        )
    }

    // Inbound Dialog
    if (showInboundDialog && outboundEditingRuleSet != null) {
        val currentRuleSet = checkNotNull(outboundEditingRuleSet)
        AlertDialog(
            modifier = Modifier.liquidGlassDialogPanel(RoundedCornerShape(24.dp)),
            onDismissRequest = {
                showInboundDialog = false
                outboundEditingRuleSet = null
            },
            containerColor = liquidGlassDialogContainerColor(),
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.rulesets_select_inbound), color = MaterialTheme.colorScheme.onSurface) },
            text = {
                LiquidGlassDialogEffect()
                Column {
                    availableInbounds.forEach { inbound ->
                        val ruleSet = outboundEditingRuleSet ?: currentRuleSet
                        val inboundList = ruleSet.inbounds ?: emptyList()
                        val isSelected = inboundList.contains(inbound)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .ruleSetInboundOptionPanel(isSelected)
                                .liquidGlassPressFeedback(
                                    label = "liquid_glass_rule_set_inbound_option_scale"
                                ) {
                                    val currentInbounds = inboundList.toMutableList()
                                    if (currentInbounds.contains(inbound)) {
                                        currentInbounds.remove(inbound)
                                    } else {
                                        currentInbounds.add(inbound)
                                    }
                                    outboundEditingRuleSet = ruleSet.copy(inbounds = currentInbounds)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null,
                                colors = liquidGlassCheckboxColors()
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = inbound, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.liquidGlassTextButtonPanel(),
                    colors = liquidGlassTextButtonColors(
                        contentColor = liquidGlassTextButtonContentColor(MaterialTheme.colorScheme.primary)
                    ),
                    onClick = {
                        val ruleSet = outboundEditingRuleSet ?: currentRuleSet
                        settingsViewModel.updateRuleSet(ruleSet)
                        showInboundDialog = false
                        outboundEditingRuleSet = null
                    }
                ) {
                    Text(stringResource(R.string.common_ok))
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.liquidGlassTextButtonPanel(),
                    colors = liquidGlassTextButtonColors(
                        contentColor = liquidGlassTextButtonContentColor(
                            defaultColor = MaterialTheme.colorScheme.primary,
                            liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ),
                    onClick = {
                        showInboundDialog = false
                        outboundEditingRuleSet = null
                    }
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    FloatingPageLayout(
        title = if (isSelectionMode) {
            stringResource(R.string.rulesets_selection_mode, selectedItems.count { it.value })
        } else {
            stringResource(R.string.rulesets_title)
        },
        onBack = {
            if (isSelectionMode) {
                exitSelectionMode()
            } else {
                navController.popBackStack()
            }
        },
        actions = {
            if (isSelectionMode) {
                val selectedCount = selectedItems.count { it.value }
                IconButton(
                    onClick = { showDeleteConfirmDialog = true },
                    enabled = selectedCount > 0
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        tint = if (selectedCount > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            } else {
                IconButton(
                    onClick = { navController.navigate(Screen.RuleSetHub.route) }
                ) {
                    Icon(
                        Icons.Rounded.CloudDownload,
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = "Add",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        },
        circularAction = isSelectionMode
    ) { contentTopPadding ->
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background)
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = contentTopPadding + 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (ruleSets.isEmpty() && !defaultRuleSetDownloadState.isActive) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlassEmptyStatePanel()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.rulesets_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                items(ruleSets, key = { it.id }) { ruleSet ->
                    ReorderableItem(
                        state = reorderableLazyColumnState,
                        key = ruleSet.id,
                        enabled = !isSelectionMode
                    ) { isDragging ->
                        val dragScale by animateFloatAsState(
                            targetValue = if (isDragging) 1.02f else 1f,
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = 260f),
                            label = "dragScale"
                        )
                        val dragShadow by animateFloatAsState(
                            targetValue = if (isDragging) 8f else 0f,
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 260f),
                            label = "dragShadow"
                        )
                        val dragAlpha by animateFloatAsState(
                            targetValue = if (isDragging) 0.94f else 1f,
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = 280f),
                            label = "dragAlpha"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .longPressDraggableHandle(
                                    enabled = !isSelectionMode,
                                    onDragStarted = {
                                        haptic.performHapticFeedback(
                                            HapticFeedbackType.LongPress
                                        )
                                    },
                                    onDragStopped = {
                                        settingsViewModel.reorderRuleSets(ruleSets.toList())
                                    }
                                )
                                .graphicsLayer {
                                    scaleX = dragScale
                                    scaleY = dragScale
                                    shadowElevation = dragShadow
                                    alpha = dragAlpha
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                }
                                .ruleSetSortItemPressFeedback(
                                    enabled = isSelectionMode
                                ) {
                                    if (isSelectionMode) {
                                        toggleSelection(ruleSet.id)
                                    }
                                }
                        ) {
                            RuleSetItem(
                                ruleSet = ruleSet,
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedItems[ruleSet.id] ?: false,
                                isDownloading = downloadingRuleSets.contains(ruleSet.tag),
                                onClick = {
                                    if (isSelectionMode) {
                                        toggleSelection(ruleSet.id)
                                    }
                                },
                                onToggle = { enabled ->
                                    if (enabled && ruleSet.outboundMode == RuleSetOutboundMode.DIRECT) {
                                        requestLocalNetworkPermission {
                                            settingsViewModel.updateRuleSet(ruleSet.copy(enabled = true))
                                        }
                                    } else {
                                        settingsViewModel.updateRuleSet(ruleSet.copy(enabled = enabled))
                                    }
                                },
                                onEditClick = { editingRuleSet = ruleSet },
                                onDeleteClick = { settingsViewModel.deleteRuleSet(ruleSet.id) },
                                onOutboundClick = {
                                    outboundEditingRuleSet = ruleSet
                                    showOutboundModeDialog = true
                                },
                                onInboundClick = {
                                    outboundEditingRuleSet = ruleSet
                                    showInboundDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
