package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Material's bottom sheet, dragged only by touch. A sheet drags down to
// close, and under a mouse a click that moved a few pixels between press
// and release became a drag of the sheet and never reached the row: a
// group's "Mark group as read" needed a steady hand (a user, 2026-10-27).
// A pointer closes the sheet by clicking outside it or with Esc, so it
// gets no handle that does nothing. ModalBottomSheetGuardTest keeps app
// code on this one.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    sheetState = sheetState,
    sheetGesturesEnabled = isTouchPrimary,
    dragHandle = if (isTouchPrimary) ({ BottomSheetDefaults.DragHandle() }) else null,
    content = content,
)
