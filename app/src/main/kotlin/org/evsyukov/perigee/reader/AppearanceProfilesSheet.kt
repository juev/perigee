package org.evsyukov.perigee.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import kotlinx.coroutines.launch
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.ReaderPreferences
import org.json.JSONException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceProfilesSheet(
    preferences: ReaderPreferences,
    fontsReady: Boolean,
    restore: suspend (String?) -> String?,
    onDismiss: () -> Unit,
) {
    val profiles by preferences.appearanceProfiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val canUndo by preferences.canUndoAppearanceProfile.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var original by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var missingFace by rememberSaveable { mutableStateOf<String?>(null) }
    fun change(action: suspend () -> Unit) {
        if (working) return
        working = true
        failed = false
        missingFace = null
        scope.launch {
            try {
                action()
            } catch (_: IOException) {
                failed = true
            } catch (_: JSONException) {
                failed = true
            } catch (_: IllegalArgumentException) {
                failed = true
            } finally {
                working = false
            }
        }
    }
    fun edit(profile: String?) {
        original = profile
        name = profile.orEmpty()
        editing = true
    }
    ModalBottomSheet(
        onDismissRequest = { if (!working) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
            !working || it != androidx.compose.material3.SheetValue.Hidden
        }),
        scrimColor = Color.Transparent,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
                .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("appearance-profiles-sheet"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.appearance_profiles), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.appearance_profiles_hint))
            val list = profiles
            if (list == null) {
                Text(stringResource(R.string.appearance_profiles_unreadable))
            } else {
                TextButton(onClick = { edit(null) }, enabled = !working && list.size < ReaderPreferences.PROFILE_LIMIT,
                    modifier = Modifier.testTag("appearance-profile-save")) {
                    Text(stringResource(R.string.appearance_profile_save))
                }
                if (list.size >= ReaderPreferences.PROFILE_LIMIT) {
                    Text(stringResource(R.string.appearance_profile_limit, ReaderPreferences.PROFILE_LIMIT))
                }
                for (profile in list) {
                    TextButton(onClick = { selected = if (selected == profile) null else profile }, enabled = !working) {
                        Text(profile)
                    }
                    if (selected == profile) {
                        TextButton(onClick = { change { missingFace = restore(profile) } }, enabled = !working && fontsReady,
                            modifier = Modifier.testTag("appearance-profile-apply")) {
                            Text(stringResource(R.string.appearance_profile_apply, profile))
                        }
                        TextButton(onClick = { change { preferences.updateAppearanceProfile(profile) } }, enabled = !working) {
                            Text(stringResource(R.string.appearance_profile_update, profile))
                        }
                        TextButton(onClick = { edit(profile) }, enabled = !working) {
                            Text(stringResource(R.string.appearance_profile_rename, profile))
                        }
                        TextButton(onClick = { deleting = profile }, enabled = !working) {
                            Text(stringResource(R.string.appearance_profile_delete, profile))
                        }
                    }
                }
            }
            if (canUndo) {
                TextButton(onClick = { change { missingFace = restore(null) } }, enabled = !working && fontsReady,
                    modifier = Modifier.testTag("appearance-profile-undo")) {
                    Text(stringResource(R.string.appearance_profile_undo))
                }
            }
            if (failed) Text(stringResource(R.string.appearance_profile_failed), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error)
            missingFace?.let {
                Text(stringResource(R.string.appearance_profile_font_missing, it), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
    if (editing) {
        val title = name.trim()
        val valid = title.isNotEmpty() && title.length <= ReaderPreferences.PROFILE_NAME_LIMIT &&
            title.none { it.isISOControl() } && (title == original || profiles?.contains(title) == false)
        AlertDialog(
            onDismissRequest = { if (!working) editing = false },
            title = { Text(stringResource(if (original == null) R.string.appearance_profile_save else R.string.appearance_profile_name)) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, enabled = !working, singleLine = true,
                        label = { Text(stringResource(R.string.appearance_profile_name)) }, modifier = Modifier.testTag("appearance-profile-name"))
                    if (failed) Text(stringResource(R.string.appearance_profile_failed))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val prior = original
                    change {
                        if (prior == null) preferences.saveAppearanceProfile(title) else preferences.renameAppearanceProfile(prior, title)
                        selected = title
                        editing = false
                    }
                }, enabled = valid && !working, modifier = Modifier.testTag("appearance-profile-confirm")) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !working) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.appearance_profile_delete, profile)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    change { preferences.deleteAppearanceProfile(profile); selected = null }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
