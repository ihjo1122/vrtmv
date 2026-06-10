package com.vrtmv.app.ui.records

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vrtmv.app.data.recording.CaptureMode
import com.vrtmv.app.data.recording.RecordItem
import com.vrtmv.app.data.recording.RecordRepository
import com.vrtmv.app.ui.theme.ArCyan
import com.vrtmv.app.ui.theme.StatusError
import com.vrtmv.app.ui.theme.SurfaceDark
import com.vrtmv.app.ui.theme.SurfaceElevated
import com.vrtmv.app.ui.theme.SurfaceOverlay
import com.vrtmv.app.ui.theme.TextPrimary
import com.vrtmv.app.ui.theme.TextSecondary
import com.vrtmv.app.ui.theme.VrtmvTheme
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class RecordListActivity : ComponentActivity() {

    @Inject lateinit var repository: RecordRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VrtmvTheme {
                RecordListScreen(
                    repository = repository,
                    onItemClick = { item ->
                        startActivity(
                            Intent(this, RecordDetailActivity::class.java).apply {
                                putExtra(RecordDetailActivity.EXTRA_FILE_PATH, item.filePath)
                            }
                        )
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordListScreen(
    repository: RecordRepository,
    onItemClick: (RecordItem) -> Unit,
    onBack: () -> Unit,
    viewModel: RecordListViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    Scaffold(
        containerColor = SurfaceDark,
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = {
                        Text("${selected.size}개 선택", color = TextPrimary)
                    },
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Filled.Close, "선택 취소", tint = TextPrimary)
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = selected.isNotEmpty(),
                            onClick = { confirmDeleteSelected = true }
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "선택 삭제",
                                tint = if (selected.isNotEmpty()) StatusError else TextSecondary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceOverlay)
                )
            } else {
                TopAppBar(
                    title = { Text("실험 기록", color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "뒤로",
                                tint = TextPrimary
                            )
                        }
                    },
                    actions = {
                        if (items.isNotEmpty()) {
                            IconButton(onClick = { confirmDeleteAll = true }) {
                                Icon(
                                    Icons.Filled.DeleteSweep,
                                    contentDescription = "전체 삭제",
                                    tint = StatusError
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceOverlay)
                )
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(modifier = Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(items, key = { it.filePath }) { item ->
                    val isSelected = selected.contains(item.filePath)
                    RecordCell(
                        item = item,
                        repository = repository,
                        selectionMode = selectionMode,
                        isSelected = isSelected,
                        onClick = {
                            if (selectionMode) {
                                selected = if (isSelected) selected - item.filePath else selected + item.filePath
                                if (selected.isEmpty()) selectionMode = false
                            } else {
                                onItemClick(item)
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selected = setOf(item.filePath)
                            } else {
                                selected = if (isSelected) selected - item.filePath else selected + item.filePath
                                if (selected.isEmpty()) selectionMode = false
                            }
                        }
                    )
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            containerColor = SurfaceOverlay,
            title = { Text("전체 삭제", color = TextPrimary) },
            text = { Text("${items.size}개 기록을 모두 삭제할까요?", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    viewModel.deleteAll()
                }) { Text("삭제", color = StatusError) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text("취소", color = ArCyan)
                }
            }
        )
    }

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            containerColor = SurfaceOverlay,
            title = { Text("선택 삭제", color = TextPrimary) },
            text = { Text("${selected.size}개 기록을 삭제할까요?", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = selected
                    confirmDeleteSelected = false
                    exitSelection()
                    viewModel.deleteSelected(toDelete)
                }) { Text("삭제", color = StatusError) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) {
                    Text("취소", color = ArCyan)
                }
            }
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("아직 기록이 없습니다", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "카메라에서 객체/전체 모드로 분석하면 자동으로 저장됩니다",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordCell(
    item: RecordItem,
    repository: RecordRepository,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = item.filePath) {
        value = repository.loadThumbnail(item.filePath, 320)
    }
    val timeText = remember(item.epochMs) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(item.epochMs))
    }
    val modeLabel = when (item.captureMode) {
        CaptureMode.OBJECT_DETECTION -> "OBJECT"
        CaptureMode.OBJECT_DETECTION_NO_PADDING -> "OBJECT*"
        CaptureMode.FULL_FRAME -> "FULL"
    }

    val borderColor = when {
        isSelected -> ArCyan
        else -> ArCyan.copy(alpha = 0.25f)
    }
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceElevated)
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(SurfaceOverlay)) {
                Text(
                    "로딩…",
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ArCyan.copy(alpha = 0.18f))
            )
        }

        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(
                        if (isSelected) ArCyan else Color.Black.copy(alpha = 0.45f),
                        CircleShape
                    )
                    .border(
                        1.dp,
                        if (isSelected) ArCyan else Color.White.copy(alpha = 0.6f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modeLabel,
                    color = ArCyan,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    timeText,
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
