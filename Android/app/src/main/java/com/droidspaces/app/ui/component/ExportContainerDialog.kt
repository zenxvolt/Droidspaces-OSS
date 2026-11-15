package com.droidspaces.app.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidspaces.app.R
import com.droidspaces.app.ui.theme.JetBrainsMono
import com.droidspaces.app.util.AnimationUtils
import com.droidspaces.app.util.ArchiveFormat

/**
 * Asked before every export: which archive format to write, and, for a container that has
 * environment variables, whether to include them. They often hold passwords and tokens and
 * exports get shared, so the archive leaves them out unless the user turns this on.
 */
@Composable
fun ExportContainerDialog(
    containerName: String,
    envCount: Int,
    onConfirm: (format: ArchiveFormat, includeEnv: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // gz stays the default: it is what exports have always been, and any tool opens it.
    var format by remember { mutableStateOf(ArchiveFormat.GZIP) }
    var includeEnv by remember { mutableStateOf(false) }

    DsDialog(
        onDismiss = onDismiss,
        footer = {
            DialogFooterRow(
                dismissLabel = stringResource(R.string.cancel),
                confirmLabel = stringResource(R.string.export_action),
                onDismiss = onDismiss,
                onConfirm = { onConfirm(format, includeEnv) }
            )
        }
    ) {
        Text(
            text = stringResource(R.string.export_container_title, containerName),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        // The choice and its caveat share one block, so the caveat costs no gap while it is
        // hidden and the dialog grows smoothly when it appears.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .animateContentSize(animationSpec = AnimationUtils.mediumSpec()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ArchiveFormat.exportable.forEach { option ->
                // The label is the file suffix itself, machine text, so it is set in the mono face.
                DsRadioRow(
                    label = option.suffix,
                    fontFamily = JetBrainsMono,
                    description = stringResource(
                        when (option) {
                            ArchiveFormat.ZSTD -> R.string.export_format_zstd_desc
                            else -> R.string.export_format_gzip_desc
                        }
                    ),
                    selected = option == format,
                    onClick = { format = option }
                )
            }
            // Only the format that has a catch explains it.
            if (format == ArchiveFormat.ZSTD) {
                Text(
                    text = stringResource(R.string.export_format_zstd_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        // Only a container with env vars has something to decide.
        if (envCount > 0) {
            Text(
                text = pluralStringResource(R.plurals.export_env_body, envCount, envCount),
                style = MaterialTheme.typography.bodyMedium
            )
            ToggleCard(
                title = stringResource(R.string.export_env_include),
                description = stringResource(R.string.export_env_include_desc),
                checked = includeEnv,
                onCheckedChange = { includeEnv = it }
            )
        }
    }
}
