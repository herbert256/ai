package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.UserNote
import com.ai.data.notesFor
import com.ai.data.runKey
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------
// User notes — free-text annotations the user pins to a report and its
// parts. The data + storage live in com.ai.data (UserNote, ReportStorage
// .addUserNote/updateUserNote/deleteUserNote, Report.notesFor). This file
// is the UI: the collapsible card stack shown at the top of each target
// screen, the add/edit editor overlay, and the 📒 all-notes screen.
//
// Target kinds (see [UserNote]): "REPORT", "AGENT", "SECONDARY",
// "FANOUT_RUN".
// ---------------------------------------------------------------------

/** What the note editor overlay is doing right now. Held in a per-screen
 *  `var ... by remember { mutableStateOf<NoteEdit?>(null) }`; non-null →
 *  the screen renders [UserNoteEditorOverlay] and early-returns. */
sealed interface NoteEdit {
    /** Add a brand-new note to the screen's target. */
    object Add : NoteEdit
    /** Edit an existing note (seeds the field with [text]). */
    data class Edit(val noteId: String, val text: String) : NoteEdit
}

/** One collapsible note card. Collapsed headline = the AI [UserNote.title]
 *  when present, else the first line of the note text. Expanded = the full
 *  note text + an "edited" timestamp; when not [readOnly] the header also
 *  carries ✏️ edit / 🗑 delete. The View family passes [readOnly] = true. */
@Composable
internal fun UserNoteCard(
    note: UserNote,
    readOnly: Boolean = false,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    var expanded by remember(note.id) { mutableStateOf(false) }
    val headline = note.title?.takeIf { it.isNotBlank() } ?: note.text
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(com.ai.data.MetadataIconsHolder.current.addNote, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                Text(
                    text = headline,
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (expanded && !readOnly) {
                    Text(com.ai.data.MetadataIconsHolder.current.edit, fontSize = 16.sp, modifier = Modifier.clickable { onEdit() }.padding(horizontal = 6.dp))
                    Text(com.ai.data.MetadataIconsHolder.current.delete, fontSize = 14.sp, modifier = Modifier.clickable { onDelete() }.padding(horizontal = 6.dp))
                }
                Text(if (expanded) "▾" else "▸", color = AppColors.TextTertiary, modifier = Modifier.padding(start = 4.dp))
            }
            if (expanded) {
                Text(note.text, color = AppColors.TextPrimary, fontSize = 14.sp)
                Text(
                    "edited ${formatNoteTime(note.updatedAt)}",
                    color = AppColors.TextTertiary, fontSize = 10.sp
                )
            }
        }
    }
}

/** A stack of [UserNoteCard]s (newest first; the list is already sorted
 *  by [Report.notesFor]). Renders nothing when empty — drop it at the top
 *  of a screen's content unconditionally. [readOnly] hides edit/delete. */
@Composable
internal fun UserNoteCards(
    notes: List<UserNote>,
    readOnly: Boolean = false,
    onEdit: (UserNote) -> Unit = {},
    onDelete: (UserNote) -> Unit = {}
) {
    if (notes.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        notes.forEach { note ->
            UserNoteCard(note, readOnly = readOnly, onEdit = { onEdit(note) }, onDelete = { onDelete(note) })
        }
    }
}

/** Read-only note display for the View family: loads this target's notes
 *  (re-reading on [ReportDataVersion] changes) and renders them as
 *  collapsible, non-editable cards. Drop right after the screen's
 *  ViewTitleBar. Renders nothing when the target has no notes. */
@Composable
internal fun ViewUserNotes(reportId: String, targetKind: String, targetId: String) {
    val context = LocalContext.current
    val dv by ReportDataVersion.version.collectAsState()
    val notes by produceState(emptyList<UserNote>(), reportId, targetKind, targetId, dv) {
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, reportId)?.notesFor(targetKind, targetId) ?: emptyList()
        }
    }
    if (notes.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        UserNoteCards(notes, readOnly = true)
    }
}

/** The note cards plus a baked-in delete handler that persists off the
 *  main thread. Edit is delegated up so the host screen can raise its
 *  editor overlay. */
@Composable
internal fun UserNotesSection(
    reportId: String,
    notes: List<UserNote>,
    onEdit: (UserNote) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    UserNoteCards(
        notes = notes,
        onEdit = onEdit,
        onDelete = { n -> scope.launch(Dispatchers.IO) { ReportStorage.deleteUserNote(context, reportId, n.id) } }
    )
}

/** Full-screen add/edit editor. Used as an overlay (early-return) from
 *  every screen that carries notes. */
@Composable
internal fun UserNoteEditorScreen(
    titleBarTitle: String,
    initialText: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    val canSave = text.trim().isNotBlank()
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_user_note_edit", title = titleBarTitle,
            subject = "Your own note — not sent to any model", onBackClick = onCancel
        )
        Button(
            onClick = { onSave(text.trim()) },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.SuccessAccent)
        ) { Text("Save note", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = AppColors.outlinedFieldColors()
        )
    }
}

/** Editor overlay wired to storage. Host screens render this when their
 *  `NoteEdit?` state is non-null and then early-return. */
@Composable
internal fun UserNoteEditorOverlay(
    reportId: String,
    targetKind: String,
    targetId: String,
    edit: NoteEdit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Fire AI title-generation after the note is persisted (add + edit).
    val generateTitle = com.ai.ui.shared.LocalGenerateNoteTitle.current
    UserNoteEditorScreen(
        titleBarTitle = if (edit is NoteEdit.Edit) "Edit note" else "Add note",
        initialText = (edit as? NoteEdit.Edit)?.text ?: "",
        onSave = { txt ->
            scope.launch(Dispatchers.IO) {
                if (edit is NoteEdit.Edit) {
                    ReportStorage.updateUserNote(context, reportId, edit.noteId, txt)
                    generateTitle(reportId, edit.noteId, txt)
                } else {
                    val saved = ReportStorage.addUserNote(context, reportId, targetKind, targetId, txt)
                    if (saved != null) generateTitle(reportId, saved.id, txt)
                }
            }
            onClose()
        },
        onCancel = onClose
    )
}

/** 📒 All-notes screen, reached from the Manage report icon bar. Lists
 *  every note in the report grouped by target, with edit / delete inline
 *  and ✍️ to add a fresh report-level note. */
@Composable
internal fun ReportNotesListScreen(
    reportId: String,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dv by ReportDataVersion.version.collectAsState()

    var noteEdit by remember { mutableStateOf<NoteEdit?>(null) }
    noteEdit?.let { ne ->
        // Adding from this screen attaches to the report itself.
        UserNoteEditorOverlay(reportId, "REPORT", reportId, ne) { noteEdit = null }
        return
    }

    data class Group(val label: String, val notes: List<UserNote>)
    val groups by produceState<List<Group>>(emptyList(), reportId, dv) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, reportId) ?: return@withContext emptyList()
            val secondaries = SecondaryResultStorage.listForReport(context, reportId)
            report.userNotes
                .sortedByDescending { it.createdAt }
                .groupBy { it.targetKind to it.targetId }
                .map { (_, notes) ->
                    Group(noteTargetLabel(notes.first(), report, secondaries, reportId), notes)
                }
                .sortedBy { it.label }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_notes", title = "User notes",
            subject = "Every note in this report", onBackClick = onBack,
            onAddNote = { noteEdit = NoteEdit.Add }
        )
        if (groups.isEmpty()) {
            Text(
                "No notes yet. Use ${com.ai.data.MetadataIconsHolder.current.addNote} on a report, model response, fan-out or secondary screen to add one.",
                color = AppColors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { group ->
                item(key = "h:${group.label}") {
                    Text(
                        group.label,
                        color = AppColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                items(group.notes.size, key = { i -> group.notes[i].id }) { i ->
                    val note = group.notes[i]
                    UserNoteCard(
                        note = note,
                        onEdit = { noteEdit = NoteEdit.Edit(note.id, note.text) },
                        onDelete = { scope.launch(Dispatchers.IO) { ReportStorage.deleteUserNote(context, reportId, note.id) } }
                    )
                }
            }
        }
    }
}

/** Human label for a note's target, used by the all-notes screen.
 *  Returns "Deleted item" when the target no longer resolves. */
private fun noteTargetLabel(
    note: UserNote,
    report: Report,
    secondaries: List<SecondaryResult>,
    reportId: String
): String = when (note.targetKind) {
    "REPORT" -> "Report"
    "AGENT" -> report.agents.firstOrNull { it.agentId == note.targetId }
        ?.let { "${it.provider} · ${shortModelName(it.model)}" } ?: "Deleted item"
    "SECONDARY" -> secondaries.firstOrNull { it.id == note.targetId }
        ?.let { secondaryShortLabel(it) } ?: "Deleted item"
    "FANOUT_RUN" -> secondaries.firstOrNull {
        it.metaPromptId != null && runKey(reportId, it.metaPromptId!!) == note.targetId
    }?.let { "Fan out · ${it.metaPromptName ?: "fan out"}" } ?: "Deleted item"
    else -> "Deleted item"
}

/** Compact label for one secondary row, for the all-notes grouping. */
private fun secondaryShortLabel(sr: SecondaryResult): String = when (sr.kind) {
    SecondaryKind.RERANK -> "Rerank"
    SecondaryKind.MODERATION -> "Moderation"
    SecondaryKind.TRANSLATE -> "Translation"
    SecondaryKind.TOURNAMENT ->
        if (sr.tournamentRole == "MATCH") "Tournament match · ${shortModelName(sr.model)}" else "Tournament"
    SecondaryKind.JUDGES ->
        if (sr.tournamentRole == "MATCH") "Judge match · ${shortModelName(sr.model)}" else "Judge the judges"
    SecondaryKind.COMPARE -> "Compare · ${shortModelName(sr.model)}"
    SecondaryKind.META -> {
        val name = sr.metaPromptName?.takeIf { it.isNotBlank() } ?: "Meta"
        if (sr.fanOutSourceAgentId != null) "$name · ${shortModelName(sr.model)}" else name
    }
}

private fun formatNoteTime(ts: Long): String =
    java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))
