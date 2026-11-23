package com.movnet.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WatchBg = Color(0xFF090B10)
private val WatchCard = Color(0xFF171B25)
private val WatchAccent = Color(0xFFE50935)
private val WatchMuted = Color(0xFF9AA3B2)

@Composable
fun WatchTogetherScreen(catalog: List<Movie>, token: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var codeInput by remember { mutableStateOf("") }
    var roomId by remember { mutableIntStateOf(0) }
    var roomCode by remember { mutableStateOf("") }
    var activeMovie by remember { mutableStateOf<Movie?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    if (roomId > 0 && activeMovie != null) {
        WatchRoomPlayer(movie = activeMovie!!, roomId = roomId, roomCode = roomCode, token = token,
            onLeave = {
                scope.launch { runCatching { MovnetApi.leaveWatchRoom(roomId, token) } }
                roomId = 0; roomCode = ""; activeMovie = null
            })
        return
    }

    Column(Modifier.fillMaxSize().background(WatchBg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹  Назад", color = WatchMuted, modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(18.dp))
            Text("Совместный просмотр", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ПОДКЛЮЧИТЬСЯ К КОМНАТЕ", color = WatchMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = codeInput, onValueChange = {
                    codeInput = it.uppercase().filter(Char::isLetterOrDigit).take(12)
                }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Код приглашения") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                Spacer(Modifier.width(8.dp))
                Button(enabled = !busy && codeInput.length >= 6, onClick = {
                    scope.launch {
                        busy = true; error = null
                        try {
                            val joined = MovnetApi.joinWatchRoom(codeInput, token)
                            val id = joined.optInt("room_id")
                            val room = MovnetApi.loadWatchRoom(id, token)
                            val movie = catalog.firstOrNull { it.id == room.optInt("item_id") }
                                ?: error("Фильм этой комнаты отсутствует в текущем каталоге.")
                            if (movie.streamUrl.isNullOrBlank()) error("У фильма пока нет видеопотока.")
                            roomId = id; roomCode = room.optString("code"); activeMovie = movie
                        } catch (e: Exception) { error = e.message ?: "Не удалось войти в комнату" }
                        finally { busy = false }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = WatchAccent)) { Text("Войти") }
            }
            error?.let { Text(it, color = Color(0xFFFF8494), fontSize = 12.sp) }
            HorizontalDivider(color = Color(0xFF303746))
            Text("СОЗДАТЬ КОМНАТУ", color = WatchMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("Выбери фильм — MOVNET создаст код, которым можно поделиться с другом.",
                color = WatchMuted, fontSize = 12.sp)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(catalog.filter { it.id > 0 && !it.streamUrl.isNullOrBlank() }) { movie ->
                Row(Modifier.fillMaxWidth().background(WatchCard, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(movie.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("${movie.year} • ${movie.genre}", color = WatchMuted, fontSize = 11.sp)
                    }
                    Button(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true; error = null
                            try {
                                val created = MovnetApi.createWatchRoom(movie.id, token)
                                roomId = created.optInt("room_id")
                                roomCode = created.optString("code")
                                activeMovie = movie
                            } catch (e: Exception) { error = e.message ?: "Не удалось создать комнату" }
                            finally { busy = false }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = WatchAccent)) {
                        Text(if (busy) "…" else "Создать")
                    }
                }
            }
        }
    }
}

private data class WatchMessage(val id: Int, val author: String, val body: String)

@Composable
private fun WatchRoomPlayer(movie: Movie, roomId: Int, roomCode: String, token: String, onLeave: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    var version by remember(roomId) { mutableIntStateOf(-1) }
    var syncStatus by remember(roomId) { mutableStateOf("Подключаем комнату…") }
    var messages by remember(roomId) { mutableStateOf<List<WatchMessage>>(emptyList()) }
    var messageInput by remember { mutableStateOf("") }
    var chatError by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(player, movie.streamUrl) {
        val url = movie.streamUrl
        if (!url.isNullOrBlank()) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = false
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    LaunchedEffect(roomId, player) {
        var lastMessageId = 0
        var lastPublishedPosition = -1L
        var lastPublishedPlaying = false
        while (true) {
            try {
                val state = MovnetApi.loadWatchRoom(roomId, token)
                val serverVersion = state.optInt("version")
                val serverPosition = state.optLong("playback_position") * 1000L
                val serverPlaying = state.optBoolean("is_playing")
                if (version == -1 || serverVersion > version) {
                    if (kotlin.math.abs(player.currentPosition - serverPosition) > 2500L) player.seekTo(serverPosition)
                    if (serverPlaying) player.play() else player.pause()
                    version = serverVersion
                    lastPublishedPosition = serverPosition
                    lastPublishedPlaying = serverPlaying
                    syncStatus = "Синхронизировано • версия $serverVersion"
                } else {
                    val localPosition = player.currentPosition
                    val localPlaying = player.isPlaying
                    val moved = lastPublishedPosition < 0L ||
                        kotlin.math.abs(localPosition - lastPublishedPosition) > 4500L
                    val playChanged = localPlaying != lastPublishedPlaying
                    if (moved || playChanged) {
                        val updated = MovnetApi.updateWatchRoomState(roomId, localPosition, localPlaying, version, token)
                        version = updated.optInt("version", version + 1)
                        lastPublishedPosition = localPosition
                        lastPublishedPlaying = localPlaying
                        syncStatus = "Синхронизировано • версия $version"
                    }
                }
                val fresh = MovnetApi.loadWatchRoomMessages(roomId, lastMessageId, token)
                for (i in 0 until fresh.length()) {
                    val item = fresh.getJSONObject(i)
                    val id = item.optInt("id")
                    messages = messages + WatchMessage(id, item.optString("display_name"), item.optString("body"))
                    lastMessageId = maxOf(lastMessageId, id)
                }
                chatError = null
            } catch (e: Exception) {
                syncStatus = e.message ?: "Проблема синхронизации"
            }
            delay(1600)
        }
    }

    Column(Modifier.fillMaxSize().background(WatchBg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("КОМНАТА $roomCode", color = WatchAccent, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text(movie.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            TextButton(onClick = onLeave) { Text("Выйти", color = WatchMuted) }
        }
        AndroidView(factory = { ctx -> PlayerView(ctx).apply {
            this.player = player
            useController = true
            controllerAutoShow = true
        } }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Text(syncStatus, color = WatchMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
        Text("Поделись кодом $roomCode с другом. Пауза, запуск и перемотка синхронизируются автоматически.",
            color = WatchMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        HorizontalDivider(color = Color(0xFF303746), modifier = Modifier.padding(top = 10.dp))
        Text("ЧАТ КОМНАТЫ", color = WatchMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages) { msg ->
                Text("${msg.author}: ${msg.body}", color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(WatchCard, RoundedCornerShape(10.dp)).padding(9.dp))
            }
        }
        chatError?.let { Text(it, color = Color(0xFFFF8494), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = messageInput, onValueChange = { messageInput = it.take(1000) },
                modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Сообщение…") })
            Spacer(Modifier.width(8.dp))
            Button(enabled = messageInput.isNotBlank() && !sending, onClick = {
                val body = messageInput.trim()
                scope.launch {
                    sending = true
                    try { MovnetApi.sendWatchRoomMessage(roomId, body, token); messageInput = "" }
                    catch (e: Exception) { chatError = e.message ?: "Не удалось отправить сообщение" }
                    finally { sending = false }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = WatchAccent)) { Text("➤") }
        }
    }
}
