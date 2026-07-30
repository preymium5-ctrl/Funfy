package com.example.funfy.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.funfy.data.MediaFolder
import com.example.funfy.theme.CookiesmoAccent
import com.example.funfy.theme.CookiesmoMuted
import com.example.funfy.theme.CookiesmoSurface
import com.example.funfy.theme.CookiesmoTextMuted
import com.example.funfy.theme.CookiesmoTextPrimary
import com.example.funfy.theme.TextMetaBlue

@Composable
fun CreateFolderDialog(
    title: String = "New folder",
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CookiesmoSurface,
        title = {
            Text(title, color = CookiesmoTextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(48) },
                singleLine = true,
                placeholder = { Text("Folder name", color = CookiesmoTextMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CookiesmoTextPrimary,
                    unfocusedTextColor = CookiesmoTextPrimary,
                    focusedBorderColor = CookiesmoAccent,
                    unfocusedBorderColor = CookiesmoMuted,
                    cursorColor = CookiesmoAccent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clean = name.trim()
                    if (clean.isNotEmpty()) onCreate(clean)
                },
                enabled = name.trim().isNotEmpty(),
            ) {
                Text("Create", color = CookiesmoAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = CookiesmoTextPrimary)
            }
        },
    )
}

/**
 * Pick a destination folder (or root). [allowRoot] shows "No folder / All videos".
 */
@Composable
fun FolderPickerDialog(
    title: String,
    folders: List<MediaFolder>,
    allowRoot: Boolean = true,
    rootLabel: String = "No folder (root)",
    onDismiss: () -> Unit,
    onPick: (folderId: String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CookiesmoSurface,
        title = {
            Text(title, color = CookiesmoTextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (allowRoot) {
                    FolderPickRow(
                        icon = Icons.Default.FolderOpen,
                        label = rootLabel,
                        onClick = { onPick(null) },
                    )
                }
                if (folders.isEmpty()) {
                    Text(
                        text = "No folders yet — create one first.",
                        color = TextMetaBlue,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    folders.forEach { folder ->
                        FolderPickRow(
                            icon = Icons.Default.Folder,
                            label = folder.name,
                            onClick = { onPick(folder.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = CookiesmoTextPrimary)
            }
        },
    )
}

@Composable
private fun FolderPickRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CookiesmoSurface,
        border = BorderStroke(1.dp, CookiesmoMuted),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = CookiesmoAccent, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(label, color = CookiesmoTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun FolderListHeader(
    folders: List<MediaFolder>,
    counts: Map<String, Int>,
    unfiledCount: Int,
    onOpenRoot: () -> Unit,
    onOpenFolder: (MediaFolder) -> Unit,
    onCreateFolder: () -> Unit,
    onDeleteFolder: (MediaFolder) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Folders",
                color = CookiesmoTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = CookiesmoSurface,
                border = BorderStroke(1.dp, CookiesmoAccent),
                modifier = Modifier.clickable(onClick = onCreateFolder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = "New folder",
                        tint = CookiesmoAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New", color = CookiesmoAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        FolderChip(
            label = "All / unfiled",
            count = unfiledCount,
            onClick = onOpenRoot,
        )
        folders.forEach { folder ->
            FolderChip(
                label = folder.name,
                count = counts[folder.id] ?: 0,
                onClick = { onOpenFolder(folder) },
                onLongDelete = { onDeleteFolder(folder) },
            )
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    count: Int,
    onClick: () -> Unit,
    onLongDelete: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CookiesmoSurface,
        border = BorderStroke(1.dp, CookiesmoMuted),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Folder, null, tint = CookiesmoAccent, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = CookiesmoTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = count.toString(),
                color = CookiesmoTextMuted,
                fontSize = 12.sp,
            )
            if (onLongDelete != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Del",
                    color = Color(0xFFEF4444),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onLongDelete),
                )
            }
        }
    }
}
