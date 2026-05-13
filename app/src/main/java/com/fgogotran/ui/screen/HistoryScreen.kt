package com.fgogotran.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fgogotran.translation.CachedTranslation
import com.fgogotran.translation.TranslationCacheDao
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Displays cached translation history from the Room translation_cache table.
 *
 * Each item shows:
 * - Original JP text (dimmed, max 2 lines, ellipsized)
 * - Translated CN text (full, primary color)
 * - Timestamp (formatted as MM/dd HH:mm)
 *
 * Note: currently the items list is empty (TODO: fetch all cached items from Room).
 * The itemCount in the title bar works via [TranslationCacheDao.count].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    cacheDao: TranslationCacheDao,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<CachedTranslation>>(emptyList()) }
    var itemCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        itemCount = cacheDao.count()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("翻譯歷史 (${itemCount})") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("返回", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    "尚無翻譯記錄\n開始玩FGO後會自動記錄",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items) { translation ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Original JP text (dimmed)
                            Text(
                                text = translation.jpText,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // Translated CN text
                            Text(
                                text = translation.cnText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            // Timestamp
                            Text(
                                text = formatTimestamp(translation.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Formats epoch millis as a short date-time string (e.g., "05/11 14:30"). */
private fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}
