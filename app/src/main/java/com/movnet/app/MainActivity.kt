package com.movnet.app

import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.webrtc.SurfaceViewRenderer

private val Background = Color(0xFF090B10)
private val SurfaceDark = Color(0xFF171B25)
private val Accent = Color(0xFFE50935)
private val Muted = Color(0xFF9AA3B2)

private val contentTypes = listOf("Всё", "Фильмы", "Сериалы", "Мультфильмы", "Мультсериалы")
private val genres = listOf("Все жанры", "Боевик", "Комедия", "Фантастика", "Детектив", "Драма", "Ужасы", "Приключения", "Семейный", "Фэнтези")

data class Movie(
    val title: String,
    val genre: String,
    val year: String,
    val premium: Boolean,
    val emoji: String,
    val description: String,
    val type: String,
    val meta: String,
    val streamUrl: String? = null,
    val id: Int = 0,
    val posterUrl: String? = null,
    val rating: Float = 0f,
    val ratingCount: Int = 0
)

data class MovieReview(val author: String, val rating: Int, val text: String)

// Offline-safe catalogue: openly licensed Blender Foundation films.
private val openLicensedFallback = listOf(
    Movie("Big Buck Bunny", "Комедия", "2008", false, "🐰", "Открытый мультфильм Blender Foundation. Лицензия CC BY 3.0.", "Фильмы", "9 мин 56 сек • CC BY 3.0", "https://archive.org/download/BigBuckBunny_328/BigBuckBunny_512kb.mp4"),
    Movie("Sintel", "Фэнтези", "2010", false, "🐉", "Фэнтези-фильм Blender Foundation. Лицензия CC BY 3.0.", "Фильмы", "14 мин 48 сек • CC BY 3.0", "https://archive.org/download/Sintel/sintel-2048-stereo_512kb.mp4"),
    Movie("Tears of Steel", "Фантастика", "2012", false, "🤖", "Научно-фантастический фильм Blender Institute. Лицензия CC BY 3.0.", "Фильмы", "12 мин 14 сек • CC BY 3.0", "https://archive.org/download/Tears-of-Steel/tears_of_steel_720p.mp4"),
    Movie("Elephants Dream", "Фантастика", "2006", false, "🐘", "Открытый фильм Blender Foundation. Лицензия Creative Commons Attribution.", "Фильмы", "10 мин • CC BY", "https://archive.org/download/ElephantsDream_277/elephant_dreams_640_512kb.mp4")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MovnetAds.initialize(this)
        setContent { MovnetApp() }
    }
}

@Composable
fun MovnetApp() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("movnet_preferences", Context.MODE_PRIVATE) }
    var selectedMovie by remember { mutableStateOf<Movie?>(null) }
    var playingMovie by remember { mutableStateOf<Movie?>(null) }
    var showAccount by remember { mutableStateOf(false) }
    var showChats by remember { mutableStateOf(false) }
    var accountEmail by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf("") }
    var accountCreatedAt by remember { mutableStateOf("") }
    var accountPlan by remember { mutableStateOf("Free") }
    var accountIsCreator by remember { mutableStateOf(false) }
    var socialPublicId by remember { mutableStateOf("") }
    var authToken by remember { mutableStateOf(preferences.getString("access_token", null)) }
    var loggedIn by remember { mutableStateOf(authToken != null) }
    var catalogItems by remember { mutableStateOf(openLicensedFallback) }
    var catalogLoading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try { catalogItems = MovnetApi.loadCatalog(); catalogError = null }
        catch (e: Exception) { catalogError = e.message ?: "Нет соединения с сервером. Показана резервная подборка." }
        finally { catalogLoading = false }
    }
    LaunchedEffect(authToken) {
        val token = authToken
        if (token != null) {
            try {
                val profile = MovnetApi.loadProfile(token)
                socialPublicId = MovnetApi.loadSocialProfile(token).optString("public_id")
                accountEmail = profile.optString("email")
                accountName = profile.optString("display_name")
                accountCreatedAt = profile.optString("created_at")
                val entitlement = MovnetApi.loadEntitlements(token)
                accountPlan = if (entitlement.optBoolean("active")) entitlement.optString("plan", "Premium") else "Free"
                accountIsCreator = entitlement.optBoolean("is_creator", false)
            }
            catch (e: Exception) { if (e.message?.contains("Invalid or expired token", true) == true || e.message?.contains("Account not found", true) == true) { preferences.edit().remove("access_token").apply(); authToken = null; loggedIn = false } }
        }
    }
    var showPremium by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("Всё") }
    var selectedGenre by remember { mutableStateOf("Все жанры") }
    var premiumFilter by remember { mutableStateOf("Все") }
    var selectedTab by remember { mutableStateOf("Каталог") }
    // Избранное сохраняется локально и не пропадает после перезапуска приложения.
    val favoriteTitles = remember(preferences) {
        mutableStateListOf<String>().apply {
            addAll(preferences.getStringSet("favorite_titles", emptySet()) ?: emptySet())
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Background, surface = SurfaceDark, primary = Accent,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            when {
                playingMovie != null -> PlayerScreen(movie = playingMovie!!, hasPremium = accountPlan != "Free", onBack = { playingMovie = null })
                showChats -> ChatsScreen(
                    token = authToken,
                    onNavigate = { tab ->
                        selectedTab = tab
                        when (tab) {
                            "Профиль" -> { showChats = false; showAccount = true }
                            "Каталог", "Главная", "Избранное" -> showChats = false
                        }
                    }
                )
                showAccount -> AccountScreen(
                    email = accountEmail, displayName = accountName, createdAt = accountCreatedAt,
                    plan = accountPlan, loggedIn = loggedIn, isCreator = accountIsCreator, publicId = socialPublicId, authToken = authToken,
                    onEmail = { accountEmail = it }, onDisplayName = { accountName = it },
                    onRequestPasswordReset = { email -> MovnetApi.requestPasswordReset(email) },
                    onResetPassword = { email, code, newPassword -> MovnetApi.confirmPasswordReset(email, code, newPassword) },
                    onSubmit = { name, email, password, register ->
                        val token = MovnetApi.authenticate(email, password, register, name)
                        preferences.edit().putString("access_token", token).apply()
                        authToken = token; loggedIn = true; accountEmail = email.trim(); accountName = name.trim()
                    },
                    onSaveProfile = { name ->
                        val token = authToken ?: error("Сначала войдите в аккаунт")
                        val profile = MovnetApi.updateProfile(token, name)
                        accountName = profile.optString("display_name", name)
                    },
                    onLogout = {
                        preferences.edit().remove("access_token").apply()
                        authToken = null; loggedIn = false; accountEmail = ""; accountName = ""; accountCreatedAt = ""; accountPlan = "Free"; accountIsCreator = false; socialPublicId = ""
                    },
                    onBack = { showAccount = false }
                )
                showPremium -> PremiumScreen(onBack = { showPremium = false })
                selectedMovie != null -> MovieDetails(
                    movie = selectedMovie!!,
                    hasPremium = accountPlan != "Free",
                    authToken = authToken,
                    loggedIn = loggedIn,
                    onSubmitReview = { movieId, rating, reviewText ->
                        val token = authToken ?: error("Войди в аккаунт, чтобы оставить отзыв")
                        MovnetApi.submitReview(movieId, rating, reviewText, token)
                    },
                    onBack = { selectedMovie = null },
                    onPremium = { showPremium = true },
                    onPlay = {
                        val movieToPlay = selectedMovie
                        if (movieToPlay != null) {
                            if (accountPlan != "Free") playingMovie = movieToPlay
                            else {
                                val host = context as? ComponentActivity
                                if (host != null) MovnetAds.showPreRoll(host) { playingMovie = movieToPlay }
                                else playingMovie = movieToPlay
                            }
                        }
                    }
                )
                else -> CatalogScreen(
                    query = query, onQuery = { query = it },
                    catalog = catalogItems, catalogLoading = catalogLoading, catalogError = catalogError, hasPremium = accountPlan != "Free",
                    selectedType = selectedType, onType = { selectedType = it },
                    selectedGenre = selectedGenre, onGenre = { selectedGenre = it },
                    premiumFilter = premiumFilter, onPremiumFilter = { premiumFilter = it },
                    selectedTab = selectedTab, onTab = { selectedTab = it },
                    onChats = { selectedTab = "Чаты"; showChats = true },
                    favoriteTitles = favoriteTitles, onFavoriteToggle = { title ->
                        if (favoriteTitles.contains(title)) favoriteTitles.remove(title) else favoriteTitles.add(title)
                        preferences.edit().putStringSet("favorite_titles", favoriteTitles.toSet()).apply()
                    },
                    onMovie = { selectedMovie = it }, onPremium = { showPremium = true }, onAccount = { showAccount = true }
                )
            }
        }
    }
}

@Composable
fun CatalogScreen(
    query: String, onQuery: (String) -> Unit,
    catalog: List<Movie>, catalogLoading: Boolean, catalogError: String?, hasPremium: Boolean,
    selectedType: String, onType: (String) -> Unit,
    selectedGenre: String, onGenre: (String) -> Unit,
    premiumFilter: String, onPremiumFilter: (String) -> Unit,
    selectedTab: String, onTab: (String) -> Unit, onChats: () -> Unit,
    favoriteTitles: List<String>, onFavoriteToggle: (String) -> Unit,
    onMovie: (Movie) -> Unit, onPremium: () -> Unit, onAccount: () -> Unit
) {
    val filtered = catalog.filter { item ->
        val matchesTab = selectedTab != "Избранное" || favoriteTitles.contains(item.title)
        val matchesQuery = query.isBlank() || item.title.contains(query, true) ||
            item.genre.contains(query, true) || item.type.contains(query, true)
        val matchesType = selectedType == "Всё" || item.type == selectedType
        val matchesGenre = selectedGenre == "Все жанры" || item.genre == selectedGenre
        val matchesPremium = premiumFilter == "Все" ||
            (premiumFilter == "Бесплатные" && !item.premium) ||
            (premiumFilter == "Premium" && item.premium)
        matchesTab && matchesQuery && matchesType && matchesGenre && matchesPremium
    }

    Column(Modifier.fillMaxSize()) {
        // Навигация по каталогу закреплена сверху и не уезжает при прокрутке фильмов.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(Accent, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text("M", fontSize = 23.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(9.dp))
                Text("MOVNET", fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("КАТАЛОГ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Button(onClick = onPremium, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("★ Premium", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        OutlinedTextField(
            value = query, onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Поиск по названию, жанру…", color = Muted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Поиск") },
            singleLine = true, shape = RoundedCornerShape(15.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF303746),
                focusedTextColor = Color.White, unfocusedTextColor = Color.White
            )
        )

        Text("Категории", Modifier.padding(start = 18.dp, top = 7.dp, bottom = 5.dp), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            contentTypes.forEach { type ->
                FilterChip(
                    selected = selectedType == type, onClick = { onType(type) },
                    label = { Text(type) }, modifier = Modifier.padding(end = 6.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent, selectedLabelColor = Color.White,
                        containerColor = SurfaceDark, labelColor = Color.White
                    )
                )
            }
        }

        Text("Жанры", Modifier.padding(start = 18.dp, top = 5.dp, bottom = 5.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            genres.forEach { genre ->
                AssistChip(
                    onClick = { onGenre(genre) }, label = { Text(genre) },
                    modifier = Modifier.padding(end = 6.dp),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (selectedGenre == genre) Color(0xFF512033) else SurfaceDark,
                        labelColor = Color.White
                    )
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
            listOf("Все", "Бесплатные", "Premium").forEach { filter ->
                FilterChip(
                    selected = premiumFilter == filter, onClick = { onPremiumFilter(filter) },
                    label = { Text(filter) }, modifier = Modifier.padding(end = 6.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent, selectedLabelColor = Color.White,
                        containerColor = SurfaceDark, labelColor = Color.White
                    )
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(if (selectedTab == "Избранное") "Избранное" else "Каталог MOVNET", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("${filtered.size} найдено", color = Muted, fontSize = 11.sp)
        }

        // Прокручивается только список карточек; фильтры и нижняя навигация остаются на месте.
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (!hasPremium) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                        .background(SurfaceDark, RoundedCornerShape(14.dp)).padding(8.dp)) {
                        Text("РЕКЛАМА", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                        AndroidView(factory = { ctx -> MovnetAds.createTestBanner(ctx) },
                            modifier = Modifier.fillMaxWidth().height(50.dp))
                    }
                }
            }
            if (catalogLoading && catalog.isEmpty()) {
                item { CircularProgressIndicator(color = Accent, modifier = Modifier.padding(24.dp)) }
            } else if (catalogError != null && catalog.isEmpty()) {
                item { Text("Каталог сервера недоступен: $catalogError", color = Color(0xFFFFC857), modifier = Modifier.padding(20.dp)) }
            } else if (filtered.isEmpty()) {
                item {
                    Text("Ничего не найдено. Попробуй изменить поиск или фильтры.",
                        color = Muted, modifier = Modifier.fillMaxWidth().padding(24.dp))
                }
            } else {
                items(filtered) { item ->
                    MovieRow(
                        movie = item,
                        isFavorite = favoriteTitles.contains(item.title),
                        onClick = { onMovie(item) },
                        onFavorite = { onFavoriteToggle(item.title) }
                    )
                }
            }
        }

        NavigationBar(containerColor = SurfaceDark, contentColor = Color.White) {
            listOf("Главная", "Каталог", "Чаты", "Избранное", "Профиль").forEach { tab ->
                NavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { if (tab == "Чаты") onChats() else { onTab(tab); if (tab == "Профиль") onAccount() } },
                    icon = {
                        Text(
                            when (tab) {
                                "Главная" -> "⌂"
                                "Каталог" -> "▦"
                                "Избранное" -> "♡"
                                "Чаты" -> "▤"
                                else -> "●"
                            },
                            fontSize = 20.sp
                        )
                    },
                    label = { Text(tab, fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = Accent,
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted
                    )
                )
            }
        }
    }
}

@Composable
fun MovieRow(movie: Movie, isFavorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
            .background(SurfaceDark, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(92.dp).height(124.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF52243B), Color(0xFF242C42))), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            if (movie.posterUrl != null) AsyncImage(model = movie.posterUrl, contentDescription = "Постер фильма", modifier = Modifier.fillMaxSize())
            else Text(movie.emoji, fontSize = 42.sp)
        }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(movie.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(5.dp))
            Text("${movie.year} • ${movie.genre}", color = Muted, fontSize = 11.sp)
            Text(movie.type, color = Color(0xFFB7A4FF), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            if (movie.ratingCount > 0) Text("★ ${movie.rating}  (${movie.ratingCount})", color = Color(0xFFFFC857), fontSize = 11.sp)
            Text(movie.meta, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.height(7.dp))
            Text(if (movie.premium) "MOVNET PREMIUM" else "БЕСПЛАТНО",
                color = if (movie.premium) Color(0xFFFFC857) else Color(0xFF65D6A2),
                fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Icon(
            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (isFavorite) "Убрать из избранного" else "Добавить в избранное",
            tint = if (isFavorite) Accent else Muted,
            modifier = Modifier.clickable(onClick = onFavorite).padding(6.dp)
        )
    }
}

@Composable
fun MovieDetails(
    movie: Movie, hasPremium: Boolean, authToken: String?, loggedIn: Boolean,
    onSubmitReview: suspend (Int, Int, String) -> Unit,
    onBack: () -> Unit, onPremium: () -> Unit, onPlay: () -> Unit
) {
    var reviews by remember(movie.id) { mutableStateOf<List<MovieReview>>(emptyList()) }
    var chosenRating by remember(movie.id) { mutableIntStateOf(5) }
    var reviewText by remember(movie.id) { mutableStateOf("") }
    var reviewMessage by remember(movie.id) { mutableStateOf<String?>(null) }
    var reviewBusy by remember(movie.id) { mutableStateOf(false) }
    val reviewScope = rememberCoroutineScope()
    LaunchedEffect(movie.id) {
        if (movie.id > 0) runCatching { MovnetApi.loadReviews(movie.id) }.onSuccess { reviews = it }
    }
    val isSeries = movie.type == "Сериалы" || movie.type == "Мультсериалы"
    val seasonCount = when (movie.title) {
        "Код будущего" -> 2
        "Мега-команда" -> 3
        "Тайны океана" -> 1
        "Город на краю" -> 1
        else -> 1
    }
    val episodesPerSeason = when (movie.title) {
        "Код будущего" -> 8
        "Мега-команда" -> 8
        "Тайны океана" -> 12
        "Город на краю" -> 8
        else -> 6
    }
    var selectedSeason by remember(movie.title) { mutableIntStateOf(1) }
    var selectedEpisode by remember(movie.title) { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Text("‹  Назад", color = Muted, modifier = Modifier.clickable { onBack() }.padding(horizontal = 20.dp, vertical = 14.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Box(Modifier.fillMaxWidth().height(220.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF5B1D39), Color(0xFF202A42))), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center) {
                if (movie.posterUrl != null) AsyncImage(model = movie.posterUrl, contentDescription = "Постер фильма", modifier = Modifier.fillMaxSize())
                else Text(movie.emoji, fontSize = 78.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text(movie.title, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Text("${movie.year} • ${movie.type} • ${movie.genre}", color = Muted)
            Text(if (movie.ratingCount > 0) "★ ${movie.rating} / 5  •  ${movie.ratingCount} оценок" else "☆ Пока нет оценок",
                color = Color(0xFFFFC857), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Text(movie.meta, color = Muted, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            Text(movie.description, color = Color(0xFFD0D4DC), lineHeight = 22.sp)
            Spacer(Modifier.height(10.dp))
            if (hasPremium) {
                Text("✓ Подписка активна: доступ к Premium-каталогу открыт.", color = Color(0xFF65D6A2), fontSize = 12.sp)
            } else if (movie.premium) {
                Text("Этот материал входит в Premium-каталог.", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))

            if (movie.premium) {
                Spacer(Modifier.height(10.dp))
                if (hasPremium) {
                    Button(onClick = onPlay, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("▶  Смотреть с Premium")
                    }
                } else {
                    Text("Этот материал отмечен как доступный по подписке Premium.", color = Color(0xFFFFC857))
                    Button(onClick = onPremium, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text("Посмотреть тарифы")
                    }
                }
            }

            if (isSeries) {
                Spacer(Modifier.height(22.dp))
                Text("Сезоны и серии", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    (1..seasonCount).forEach { season ->
                        FilterChip(
                            selected = selectedSeason == season,
                            onClick = { selectedSeason = season; selectedEpisode = 0 },
                            label = { Text("Сезон $season") },
                            modifier = Modifier.padding(end = 8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent, selectedLabelColor = Color.White,
                                containerColor = SurfaceDark, labelColor = Color.White
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                (1..episodesPerSeason).forEach { episode ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp)
                            .background(SurfaceDark, RoundedCornerShape(13.dp))
                            .clickable { selectedEpisode = episode }
                            .padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(42.dp).background(Color(0xFF342333), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Text("▶", color = Accent, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("Серия $episode", fontWeight = FontWeight.Bold)
                            Text("${movie.title}: эпизод $episode", color = Muted, fontSize = 11.sp)
                        }
                        if (selectedEpisode == episode) Text("Выбрана", color = Color(0xFF65D6A2), fontSize = 11.sp)
                    }
                }
                if (selectedEpisode > 0) {
                    Spacer(Modifier.height(10.dp))
                    if (movie.premium && !hasPremium) {
                        Button(onClick = onPremium, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                            Text("Оформить Premium для просмотра")
                        }
                    } else {
                        Button(onClick = onPlay, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                            Text("▶  Смотреть сезон $selectedSeason, серию $selectedEpisode")
                        }
                    }
                    Text("Список серий демонстрационный. Для реального каталога нужно подключить лицензированный видеопоток для каждого эпизода.",
                        color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                }
            } else if (!movie.premium) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onPlay, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("▶  Смотреть")
                }
                Text("Открывается демонстрационный видеопоток Blender Foundation. Для каталога нужны собственные лицензированные видео.", color = Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFF303746))
            Text("Отзывы зрителей", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (movie.id > 0) {
                if (loggedIn) {
                    Text("Твоя оценка", color = Muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        (1..5).forEach { star ->
                            Text(if (star <= chosenRating) "★" else "☆", color = Color(0xFFFFC857), fontSize = 30.sp,
                                modifier = Modifier.clickable { chosenRating = star }.padding(end = 5.dp))
                        }
                    }
                    OutlinedTextField(value = reviewText, onValueChange = { if (it.length <= 1200) reviewText = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Напиши отзыв") },
                        minLines = 2, maxLines = 5)
                    Button(onClick = {
                        reviewScope.launch {
                            reviewBusy = true; reviewMessage = null
                            try {
                                onSubmitReview(movie.id, chosenRating, reviewText)
                                reviews = MovnetApi.loadReviews(movie.id)
                                reviewText = ""; reviewMessage = "Отзыв опубликован ✓"
                            } catch (e: Exception) { reviewMessage = e.message ?: "Не удалось отправить отзыв" }
                            finally { reviewBusy = false }
                        }
                    }, enabled = !reviewBusy, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                        Text(if (reviewBusy) "Отправляем…" else "Оставить отзыв")
                    }
                    reviewMessage?.let { Text(it, color = Muted, fontSize = 12.sp) }
                } else Text("Войди в аккаунт, чтобы оценить фильм и оставить отзыв.", color = Muted, fontSize = 12.sp)
                if (reviews.isEmpty()) Text("Пока нет отзывов. Будь первым!", color = Muted, fontSize = 13.sp)
                reviews.forEach { review ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${review.author}  •  ${"★".repeat(review.rating)}", color = Color(0xFFFFC857), fontWeight = FontWeight.Bold)
                            if (review.text.isNotBlank()) Text(review.text, color = Color(0xFFE2E5EB), modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            } else {
                Text("Отзывы появятся после загрузки фильма в серверный каталог.", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun PremiumScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedPlan by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("‹  Назад", color = Muted, modifier = Modifier.clickable { onBack() })
        Text("MOVNET", color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        Text("Выбери свою подписку", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
        Text("Прокрути список, чтобы сравнить все четыре тарифа.", color = Muted, fontSize = 13.sp)

        SubscriptionCard(
            "Premium", "199 ₽ / месяц", "До Full HD (1080p)",
            listOf("Без рекламных пауз", "Premium-каталог", "Офлайн-загрузки"),
            selected = selectedPlan == "Premium",
            onSelect = {
                selectedPlan = "Premium"
                Toast.makeText(context, "Тариф Premium выбран. Оплата пока не подключена.", Toast.LENGTH_LONG).show()
            }
        )
        SubscriptionCard(
            "Premium+", "300 ₽ / месяц", "До 2K / 4K*",
            listOf("Все возможности Premium", "Повышенное качество", "Офлайн-загрузки"),
            selected = selectedPlan == "Premium+",
            onSelect = {
                selectedPlan = "Premium+"
                Toast.makeText(context, "Тариф Premium+ выбран. Оплата пока не подключена.", Toast.LENGTH_LONG).show()
            }
        )
        SubscriptionCard(
            "Суперподписка — стандарт", "2 000 ₽ / год", "До Full HD (без 2K/4K)",
            listOf("Подписка на 12 месяцев", "Без рекламы", "Premium-каталог и загрузки"),
            selected = selectedPlan == "Суперподписка — стандарт",
            onSelect = {
                selectedPlan = "Суперподписка — стандарт"
                Toast.makeText(context, "Годовой тариф выбран. Оплата пока не подключена.", Toast.LENGTH_LONG).show()
            }
        )
        SubscriptionCard(
            "Суперподписка — 2K/4K", "2 500 ₽ / год", "До 2K / 4K*",
            listOf("Подписка на 12 месяцев", "Без рекламы", "Premium-каталог и загрузки"),
            selected = selectedPlan == "Суперподписка — 2K/4K",
            onSelect = {
                selectedPlan = "Суперподписка — 2K/4K"
                Toast.makeText(context, "Годовой тариф 2K/4K выбран. Оплата пока не подключена.", Toast.LENGTH_LONG).show()
            }
        )
        Text(
            "* Качество зависит от исходного видео, устройства и интернета. Выбор тарифа пока демонстрационный: платёжный провайдер и серверная активация не подключены.",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp
        )
    }
}

@Composable
private fun SubscriptionCard(
    title: String,
    price: String,
    quality: String,
    details: List<String>,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF30202B) else SurfaceDark
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(price, color = Color(0xFFFFC857), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(quality, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            details.forEach {
                Text("✓  $it", color = Color(0xFFE2E5EB), fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text(if (selected) "Тариф выбран" else "Выбрать тариф")
            }
        }
    }
}

@Composable
fun PlayerScreen(movie: Movie, hasPremium: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    // Compose may provide a ContextThemeWrapper rather than the Activity itself.
    val activity = remember(context) {
        var current: Context = context
        while (current is ContextWrapper && current !is ComponentActivity) {
            current = current.baseContext
        }
        current as? ComponentActivity
    }
    var fullScreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var playbackError by remember(movie.title) { mutableStateOf<String?>(null) }
    var isBuffering by remember(movie.title) { mutableStateOf(true) }
    var midrollShown by remember(movie.title) { mutableStateOf(false) }
    LaunchedEffect(movie.title, hasPremium, player) {
        if (!hasPremium) {
            while (!midrollShown) {
                kotlinx.coroutines.delay(3000)
                if (player.isPlaying && player.currentPosition >= 600_000L) {
                    midrollShown = true
                    player.pause()
                    if (activity != null) MovnetAds.showPreRoll(activity) { player.play() }
                    else player.play()
                }
            }
        }
    }
    val httpFactory = remember(context) {
        DefaultHttpDataSource.Factory()
            .setUserAgent("MOVNET Android Video Player")
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
    }
    val player = remember(context) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                if (playbackState == Player.STATE_READY) playbackError = null
            }
            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                playbackError = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Нет соединения или видеосервер не отвечает. Проверь интернет и нажми «Повторить»."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Сервер прислал неподдерживаемый видеофайл."
                    else -> "Не удалось запустить видео (${error.errorCodeName}). Проверь подключение и попробуй снова."
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(movie.streamUrl) {
        val url = movie.streamUrl?.takeIf { it.startsWith("https://") }
        playbackError = null
        isBuffering = true
        player.stop()
        player.clearMediaItems()
        if (url != null) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        } else {
            isBuffering = false
            playbackError = "Для этого фильма пока не настроен видеопоток."
        }
    }

    LaunchedEffect(fullScreen) {
        controlsVisible = true
        activity?.let { host ->
            host.requestedOrientation = if (fullScreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            if (fullScreen) {
                host.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                host.window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            } else {
                host.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                host.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
    }

    BackHandler(enabled = fullScreen) { fullScreen = false }

    DisposableEffect(player, activity) {
        onDispose {
            player.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!fullScreen) {
            Text("‹  Назад", color = Color.White,
                modifier = Modifier.clickable { onBack() }.padding(18.dp))
            Text(movie.title, color = Color.White, fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        }

        if (movie.streamUrl?.startsWith("https://") == true) {
            Box(modifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            controllerShowTimeoutMs = 3000
                            controllerHideOnTouch = true
                            controllerAutoShow = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setControllerVisibilityListener(
                                PlayerView.ControllerVisibilityListener { visibility ->
                                    controlsVisible = visibility == View.VISIBLE
                                }
                            )
                        }
                    },
                    modifier = if (fullScreen) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                if (isBuffering && playbackError == null) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center))
                }
                playbackError?.let { message ->
                    Column(
                        Modifier.align(Alignment.Center).fillMaxWidth().padding(22.dp)
                            .background(Color(0xDD171B25), RoundedCornerShape(14.dp)).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(message, color = Color.White, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            playbackError = null; isBuffering = true
                            player.seekTo(0); player.prepare(); player.playWhenReady = true
                        }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Повторить") }
                    }
                }
                // The fullscreen toggle follows the native player controls:
                // it disappears with them and returns when the viewer taps the video.
                if (controlsVisible) {
                    Text(
                        if (fullScreen) "⛶" else "⛶",
                        color = Color.White, fontSize = 26.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp)
                            .background(Color(0x99000000), RoundedCornerShape(8.dp))
                            .clickable { fullScreen = !fullScreen }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            if (!fullScreen) {
                Text(
                    "Открытый фильм с лицензией Creative Commons. Сведения об авторе и лицензии указаны в описании фильма.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(18.dp)
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(SurfaceDark),
                contentAlignment = Alignment.Center) {
                Text("Видео пока не добавлено", color = Muted)
            }
            Text("Для этого фильма пока не подключён видеопоток. Попробуйте позже.",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
        }

        if (!fullScreen) {
            Text("Для бесплатного тарифа рекламные места пока представлены макетом.",
                color = Color(0xFFFFC857), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 18.dp))
        }
    }
}
private data class SocialChatLine(val id: Int, val mine: Boolean, val text: String)

@Composable
private fun DirectChatDialog(friendName: String, friendId: Int, ownUserId: Int, token: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<SocialChatLine>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(friendId, token) {
        while (true) {
            runCatching {
                val data = MovnetApi.loadDirectMessages(friendId, lines.lastOrNull()?.id ?: 0, token)
                val fresh = (0 until data.length()).map { i ->
                    val item = data.getJSONObject(i)
                    SocialChatLine(item.optInt("id"), item.optInt("sender_id") == ownUserId, item.optString("body"))
                }
                if (fresh.isNotEmpty()) lines = lines + fresh
            }.onFailure { error = it.message }
            kotlinx.coroutines.delay(2500)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Чат с $friendName") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    if (lines.isEmpty()) Text("Начните общение!", color = Muted)
                    lines.forEach { line -> Text(line.text, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Color.White) }
                }
                error?.let { Text(it, color = Color(0xFFFF7A8A), fontSize = 11.sp) }
                OutlinedTextField(input, { input = it.take(2000) }, label = { Text("Сообщение") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = input.isNotBlank() && !sending, onClick = {
                scope.launch {
                    sending = true
                    try { MovnetApi.sendDirectMessage(friendId, input.trim(), token); input = ""; error = null }
                    catch (e: Exception) { error = e.message ?: "Не удалось отправить" }
                    finally { sending = false }
                }
            }) { Text("Отправить", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
fun AccountScreen(
    email: String, displayName: String, createdAt: String, plan: String, loggedIn: Boolean, isCreator: Boolean, publicId: String, authToken: String?,
    onEmail: (String) -> Unit, onDisplayName: (String) -> Unit,
    onSubmit: suspend (String, String, String, Boolean) -> Unit,
    onRequestPasswordReset: suspend (String) -> Unit,
    onResetPassword: suspend (String, String, String) -> Unit,
    onSaveProfile: suspend (String) -> Unit,
    onLogout: () -> Unit, onBack: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var resetCodeSent by remember { mutableStateOf(false) }
    var resetCode by remember { mutableStateOf("") }
    var resetNewPassword by remember { mutableStateOf("") }
    var resetBusy by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val initials = displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        .take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
        .ifBlank { email.firstOrNull()?.uppercaseChar()?.toString() ?: "M" }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("‹ Назад", color = Muted, modifier = Modifier.clickable { onBack() })
        Text("АККАУНТ MOVNET", color = Color(0xFFFFA6B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("Твой профиль", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)
        if (loggedIn) {
            Row(
                Modifier.fillMaxWidth().background(
                    Brush.linearGradient(listOf(Color(0xFF541329), Color(0xFF171B25))),
                    RoundedCornerShape(22.dp)
                ).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(66.dp).background(Accent, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center
                ) { Text(initials, fontSize = 25.sp, fontWeight = FontWeight.Black) }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(displayName.ifBlank { "Пользователь MOVNET" }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(email, color = Muted, fontSize = 12.sp)
                    Text("Аккаунт активен", color = Color(0xFF65D6A2), fontSize = 11.sp)
                    if (isCreator) Text("✦ СОЗДАТЕЛЬ MOVNET", color = Color(0xFFFFC857), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("МОЯ ПОДПИСКА", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(plan, color = if (plan == "Free") Color.White else Color(0xFFFFC857),
                        fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (plan == "Free") "Базовый доступ MOVNET" else "Подписка активна",
                        color = Muted, fontSize = 12.sp)
                }
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ТВОЙ MOVNET ID", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(publicId.ifBlank { "Загружаем…" }, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                    Text("Поделись ID с другом, чтобы он мог отправить заявку в друзья.", color = Muted, fontSize = 12.sp)
                }
            }
            var friendIdInput by remember { mutableStateOf("") }
            var friendStatus by remember { mutableStateOf<String?>(null) }
            var friendBusy by remember { mutableStateOf(false) }
            var friendRows by remember { mutableStateOf<List<Triple<String, String, Int>>>(emptyList()) }
            var ownUserId by remember { mutableIntStateOf(0) }
            var requestRows by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
            var activeChat by remember { mutableStateOf<Pair<String, Int>?>(null) }
            LaunchedEffect(authToken) {
                val token = authToken ?: return@LaunchedEffect
                runCatching {
                    ownUserId = MovnetApi.loadProfile(token).optInt("id")
                    val friendsJson = MovnetApi.loadFriends(token)
                    friendRows = (0 until friendsJson.length()).map { i ->
                        val item = friendsJson.getJSONObject(i)
                        Triple(item.optString("display_name"), item.optString("public_id"), item.optInt("user_id"))
                    }
                    val requestsJson = MovnetApi.loadFriendRequests(token)
                    requestRows = (0 until requestsJson.length()).map { i ->
                        val item = requestsJson.getJSONObject(i)
                        item.optInt("request_id") to (item.optString("display_name") + " • " + item.optString("public_id"))
                    }
                }.onFailure { friendStatus = it.message ?: "Не удалось загрузить друзей" }
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ДРУЗЬЯ MOVNET", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = friendIdInput, onValueChange = { friendIdInput = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(12) }, label = { Text("MOVNET ID друга") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !friendBusy && friendIdInput.length >= 6 && authToken != null, onClick = {
                        scope.launch {
                            friendBusy = true; friendStatus = null
                            try {
                                val result = MovnetApi.sendFriendRequest(friendIdInput, authToken!!)
                                friendStatus = result.optString("message", "Заявка отправлена"); friendIdInput = ""
                            } catch (e: Exception) { friendStatus = e.message ?: "Не удалось отправить заявку" }
                            finally { friendBusy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(if (friendBusy) "Отправляем…" else "Найти и добавить") }
                    friendStatus?.let { Text(it, color = Muted, fontSize = 12.sp) }
                    if (requestRows.isNotEmpty()) {
                        Text("ВХОДЯЩИЕ ЗАЯВКИ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        requestRows.forEach { (requestId, label) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), fontSize = 12.sp)
                                TextButton(onClick = { scope.launch {
                                    try {
                                        MovnetApi.acceptFriendRequest(requestId, authToken!!)
                                        requestRows = requestRows.filterNot { it.first == requestId }
                                        val json = MovnetApi.loadFriends(authToken)
                                        friendRows = (0 until json.length()).map { i -> val item = json.getJSONObject(i); Triple(item.optString("display_name"), item.optString("public_id"), item.optInt("user_id")) }
                                        friendStatus = "Вы теперь друзья!"
                                    } catch (e: Exception) { friendStatus = e.message ?: "Ошибка принятия заявки" }
                                } }) { Text("Принять", color = Accent) }
                            }
                        }
                    }
                    Text("МОИ ДРУЗЬЯ (${friendRows.size})", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (friendRows.isEmpty()) Text("Пока нет друзей. Найди знакомого по его MOVNET ID.", color = Muted, fontSize = 12.sp)
                    friendRows.forEach { (name, id, userId) ->
                        Row(Modifier.fillMaxWidth().background(Background, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(name.ifBlank { "Пользователь MOVNET" }, fontWeight = FontWeight.SemiBold); Text(id, color = Muted, fontSize = 11.sp) }
                            TextButton(onClick = { activeChat = name to userId }) { Text("Чат", color = Accent) }
                        }
                    }
                }
            }

            if (activeChat != null && authToken != null) DirectChatDialog(activeChat!!.first, activeChat!!.second, ownUserId, authToken!!, onDismiss = { activeChat = null })
            Text("Данные профиля", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = displayName, onValueChange = { onDisplayName(it); saved = false },
                label = { Text("Имя пользователя") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text("Дата регистрации: " + createdAt.substringBefore("T").ifBlank { "—" },
                color = Muted, fontSize = 12.sp)
            error?.let { Text(it, color = Color(0xFFFF7A8A)) }
            if (saved) Text("Профиль сохранён ✓", color = Color(0xFF65D6A2))
            Button(
                onClick = {
                    scope.launch {
                        busy = true; error = null; saved = false
                        try { onSaveProfile(displayName); saved = true }
                        catch (e: Exception) { error = e.message ?: "Не удалось сохранить профиль" }
                        finally { busy = false }
                    }
                },
                enabled = !busy && displayName.trim().length >= 2,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text(if (busy) "Сохраняем…" else "Сохранить профиль") }
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Выйти из аккаунта") }
        } else {
            Text(if (register) "Создай аккаунт MOVNET. Пароль должен содержать минимум 8 символов." else "Войди в свой аккаунт MOVNET. Если аккаунта ещё нет — переключись на регистрацию.",
                color = Muted, fontSize = 13.sp)
            OutlinedTextField(
                value = displayName, onValueChange = onDisplayName,
                label = { Text("Имя пользователя") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = email, onValueChange = onEmail,
                label = { Text("Электронная почта") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Пароль (минимум 8 символов)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            if (register) {
                OutlinedTextField(
                    value = confirmPassword, onValueChange = { confirmPassword = it },
                    label = { Text("Повтори пароль") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
            }
            error?.let { Text(it, color = Color(0xFFFF7A8A)) }
            Button(
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        try { onSubmit(displayName, email, password, register) }
                        catch (e: Exception) { error = e.message ?: "Не удалось выполнить запрос" }
                        finally { busy = false }
                    }
                },
                enabled = !busy && email.contains("@") &&
                    password.length >= (if (register) 8 else 1) &&
                    (!register || (displayName.trim().length >= 2 && password == confirmPassword)),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text(if (busy) "Подключение…" else if (register) "Создать аккаунт" else "Войти") }
            TextButton(onClick = {
                register = !register; error = null; password = ""; confirmPassword = ""
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (register) "Уже есть аккаунт? Войти" else "Впервые в MOVNET? Создать аккаунт",
                    color = Color(0xFFFFA6B8))
            }
            if (!register) {
                TextButton(onClick = {
                    showResetDialog = true; resetCodeSent = false
                    resetCode = ""; resetNewPassword = ""; resetMessage = null
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Забыли пароль?", color = Color(0xFFFFC857))
                }
            }
            Text("Пароль хранится на сервере только в виде защищённого хеша.",
                color = Muted, fontSize = 11.sp)
        }
        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { if (!resetBusy) showResetDialog = false },
                title = { Text("Восстановление пароля") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Укажи почту аккаунта. Мы отправим одноразовый код, действующий 10 минут.")
                        OutlinedTextField(value = email, onValueChange = onEmail, label = { Text("Электронная почта") }, singleLine = true)
                        if (resetCodeSent) {
                            OutlinedTextField(value = resetCode, onValueChange = { resetCode = it.filter(Char::isDigit).take(6) }, label = { Text("Код из письма") }, singleLine = true)
                            OutlinedTextField(value = resetNewPassword, onValueChange = { resetNewPassword = it }, label = { Text("Новый пароль (от 8 символов)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                        }
                        resetMessage?.let { Text(it, color = if (it.contains("успешно", true) || it.contains("отправлен", true)) Color(0xFF65D6A2) else Color(0xFFFF7A8A), fontSize = 12.sp) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !resetBusy && (if (resetCodeSent) resetCode.length == 6 && resetNewPassword.length >= 8 else email.contains("@")),
                        onClick = {
                            scope.launch {
                                resetBusy = true; resetMessage = null
                                try {
                                    if (!resetCodeSent) {
                                        onRequestPasswordReset(email)
                                        resetCodeSent = true
                                        resetMessage = "Если аккаунт существует, код отправлен на почту."
                                    } else {
                                        onResetPassword(email, resetCode, resetNewPassword)
                                        resetMessage = "Пароль успешно изменён. Теперь войди с новым паролем."
                                        resetCodeSent = false
                                        password = resetNewPassword
                                        showResetDialog = false
                                    }
                                } catch (e: Exception) { resetMessage = e.message ?: "Не удалось выполнить запрос." }
                                finally { resetBusy = false }
                            }
                        }) { Text(if (resetBusy) "Подожди…" else if (resetCodeSent) "Сменить пароль" else "Отправить код") }
                },
                dismissButton = { TextButton(onClick = { if (!resetBusy) showResetDialog = false }) { Text("Закрыть") } }
            )
        }
    }
}


private data class MovnetFriend(val name: String, val publicId: String, val userId: Int)
private data class MovnetMessage(val id: Int, val mine: Boolean, val body: String)
private data class IncomingMovnetCall(val id: Int, val name: String, val mediaType: String)

@Composable
private fun ChatsScreen(token: String?, onNavigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var ownUserId by remember { mutableIntStateOf(0) }
    var friends by remember { mutableStateOf<List<MovnetFriend>>(emptyList()) }
    var requests by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var selectedFriend by remember { mutableStateOf<MovnetFriend?>(null) }
    var publicIdInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var incomingCall by remember { mutableStateOf<IncomingMovnetCall?>(null) }
    var acceptedCall by remember { mutableStateOf<IncomingMovnetCall?>(null) }

    suspend fun refresh() {
        val activeToken = token ?: return
        ownUserId = MovnetApi.loadProfile(activeToken).optInt("id")
        val friendJson = MovnetApi.loadFriends(activeToken)
        friends = (0 until friendJson.length()).map { i ->
            val item = friendJson.getJSONObject(i)
            MovnetFriend(item.optString("display_name"), item.optString("public_id"), item.optInt("user_id"))
        }
        val requestJson = MovnetApi.loadFriendRequests(activeToken)
        requests = (0 until requestJson.length()).map { i ->
            val item = requestJson.getJSONObject(i)
            item.optInt("request_id") to (item.optString("display_name") + " • " + item.optString("public_id"))
        }
    }

    LaunchedEffect(token) {
        if (token == null) { loading = false; status = "Войди в аккаунт, чтобы пользоваться чатами."; return@LaunchedEffect }
        try { refresh() } catch (e: Exception) { status = e.message ?: "Не удалось загрузить чаты" }
        finally { loading = false }
        while (true) {
            try {
                val calls = MovnetApi.loadIncomingCalls(token)
                if (calls.length() > 0 && acceptedCall == null) {
                    val item = calls.getJSONObject(0)
                    incomingCall = IncomingMovnetCall(item.optInt("call_id"), item.optString("caller_name"), item.optString("media_type", "audio"))
                }
            } catch (_: Exception) { }
            kotlinx.coroutines.delay(4000)
        }
    }

    if (acceptedCall != null && token != null) {
        DirectCallScreen(call = acceptedCall!!, token = token, isCaller = false,
            onEnd = { acceptedCall = null; incomingCall = null })
        return
    }

    if (selectedFriend != null && token != null) {
        DirectChatScreen(friend = selectedFriend!!, ownUserId = ownUserId, token = token,
            onBack = { selectedFriend = null })
        return
    }

    incomingCall?.let { call ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (call.mediaType == "video") "Видеозвонок" else "Входящий звонок") },
            text = { Text(call.name + " звонит тебе") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            MovnetApi.updateDirectCallStatus(call.id, "accepted", token!!)
                            acceptedCall = call
                            incomingCall = null
                        } catch (e: Exception) { status = e.message ?: "Не удалось принять звонок"; incomingCall = null }
                    }
                }) { Text("Принять", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { runCatching { MovnetApi.updateDirectCallStatus(call.id, "rejected", token!!) }; incomingCall = null }
                }) { Text("Отклонить", color = Muted) }
            },
            containerColor = SurfaceDark
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("MOVNET", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("Чаты", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)
            }
            TextButton(onClick = { scope.launch { loading = true; status = null
                try { refresh() } catch (e: Exception) { status = e.message ?: "Ошибка обновления" }
                finally { loading = false } } }) { Text("Обновить", color = Accent) }
        }
        if (token == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(status ?: "Войди в аккаунт, чтобы открыть сообщения.", color = Muted)
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("НАЙТИ ДРУГА", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        OutlinedTextField(value = publicIdInput,
                            onValueChange = { publicIdInput = it.uppercase().filter(Char::isLetterOrDigit).take(12) },
                            label = { Text("MOVNET ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && publicIdInput.length >= 6, onClick = {
                            scope.launch {
                                busy = true; status = null
                                try {
                                    val result = MovnetApi.sendFriendRequest(publicIdInput, token)
                                    status = result.optString("message", "Заявка отправлена")
                                    publicIdInput = ""
                                    refresh()
                                } catch (e: Exception) { status = e.message ?: "Не удалось отправить заявку" }
                                finally { busy = false }
                            }
                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                            Text(if (busy) "Отправляем…" else "Добавить по ID")
                        }
                    }
                }
                if (requests.isNotEmpty()) {
                    Text("ЗАЯВКИ В ДРУЗЬЯ", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    requests.forEach { (requestId, label) ->
                        Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(14.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), fontSize = 13.sp)
                            TextButton(onClick = { scope.launch {
                                try { MovnetApi.acceptFriendRequest(requestId, token); refresh(); status = "Теперь вы друзья!" }
                                catch (e: Exception) { status = e.message ?: "Не удалось принять заявку" }
                            } }) { Text("Принять", color = Accent) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("СООБЩЕНИЯ", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("${friends.size} друзей", color = Muted, fontSize = 11.sp)
                }
                if (loading && friends.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                } else if (friends.isEmpty()) {
                    Text("Здесь появятся ваши переписки. Добавь друга по его MOVNET ID, чтобы начать общение.",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    friends.forEach { friend ->
                        Row(Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(16.dp))
                            .clickable { selectedFriend = friend }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).background(Brush.linearGradient(listOf(Accent, Color(0xFF7045C5))),
                                RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                Text(friend.name.firstOrNull()?.uppercase() ?: "M", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(friend.name.ifBlank { "Пользователь MOVNET" }, fontWeight = FontWeight.SemiBold)
                                Text(friend.publicId, color = Muted, fontSize = 11.sp)
                            }
                            Text("Открыть ›", color = Accent, fontSize = 12.sp)
                        }
                    }
                }
                status?.let { Text(it, color = if (it.contains("ошиб", true) || it.contains("не удалось", true)) Color(0xFFFF7A8A) else Muted, fontSize = 12.sp) }
                Spacer(Modifier.height(8.dp))
            }
        }
        NavigationBar(containerColor = SurfaceDark, contentColor = Color.White) {
            listOf("Главная", "Каталог", "Чаты", "Избранное", "Профиль").forEach { tab ->
                NavigationBarItem(selected = tab == "Чаты", onClick = { if (tab != "Чаты") onNavigate(tab) },
                    icon = { Text(when(tab) { "Главная" -> "⌂"; "Каталог" -> "▦"; "Чаты" -> "▤"; "Избранное" -> "♡"; else -> "●" }, fontSize = 20.sp) },
                    label = { Text(tab, fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White,
                        indicatorColor = Accent, unselectedIconColor = Muted, unselectedTextColor = Muted))
            }
        }
    }
}

@Composable
private fun DirectChatScreen(friend: MovnetFriend, ownUserId: Int, token: String, onBack: () -> Unit) {
    var activeCall by remember { mutableStateOf<IncomingMovnetCall?>(null) }
    if (activeCall != null) {
        DirectCallScreen(call = activeCall!!, token = token, isCaller = true,
            onEnd = { activeCall = null })
        return
    }
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<MovnetMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(friend.userId, token) {
        while (true) {
            try {
                val data = MovnetApi.loadDirectMessages(friend.userId, messages.lastOrNull()?.id ?: 0, token)
                val fresh = (0 until data.length()).map { i ->
                    val item = data.getJSONObject(i)
                    MovnetMessage(item.optInt("id"), item.optInt("sender_id") == ownUserId, item.optString("body"))
                }
                if (fresh.isNotEmpty()) messages = (messages + fresh).distinctBy { it.id }
                error = null
            } catch (e: Exception) { error = e.message ?: "Не удалось обновить сообщения" }
            finally { loading = false }
            kotlinx.coroutines.delay(2500)
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(Background)) {
        Row(Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable { onBack() }.padding(horizontal = 8.dp))
            Box(Modifier.size(42.dp).background(Brush.linearGradient(listOf(Accent, Color(0xFF7045C5))),
                RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Text(friend.name.firstOrNull()?.uppercase() ?: "M", fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(friend.name.ifBlank { "Пользователь MOVNET" }, fontWeight = FontWeight.Bold)
                Text("MOVNET • личный чат", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = {
                scope.launch {
                    try {
                        val result = MovnetApi.createDirectCall(friend.userId, "audio", token)
                        activeCall = IncomingMovnetCall(result.optInt("call_id"), friend.name, "audio")
                    } catch (e: Exception) { error = e.message ?: "Не удалось начать звонок" }
                }
            }) { Icon(Icons.Default.Call, contentDescription = "Аудиозвонок", tint = Accent) }
            IconButton(onClick = {
                scope.launch {
                    try {
                        val result = MovnetApi.createDirectCall(friend.userId, "video", token)
                        activeCall = IncomingMovnetCall(result.optInt("call_id"), friend.name, "video")
                    } catch (e: Exception) { error = e.message ?: "Не удалось начать видеозвонок" }
                }
            }) { Icon(Icons.Default.Videocam, contentDescription = "Видеозвонок", tint = Accent) }
            Text("⋮", color = Muted, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (loading && messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages, key = { it.id }) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(max = 300.dp).background(
                            if (message.mine) Color(0xFF4A285C) else SurfaceDark,
                            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp,
                                bottomStart = if (message.mine) 18.dp else 5.dp,
                                bottomEnd = if (message.mine) 5.dp else 18.dp)
                        ).padding(horizontal = 14.dp, vertical = 10.dp) {
                            Text(message.body, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)
                        }
                    }
                }
                if (messages.isEmpty()) item { Text("Начни разговор — отправь первое сообщение 👋", color = Muted, modifier = Modifier.fillMaxWidth().padding(20.dp)) }
            }
        }
        error?.let { Text(it, color = Color(0xFFFF7A8A), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
        Row(Modifier.fillMaxWidth().background(SurfaceDark).navigationBarsPadding().imePadding()
            .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it.take(2000) },
                modifier = Modifier.weight(1f), placeholder = { Text("Сообщение…") },
                shape = RoundedCornerShape(24.dp), maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF343947),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            Spacer(Modifier.width(8.dp))
            IconButton(enabled = input.isNotBlank() && !sending, onClick = {
                val body = input.trim()
                scope.launch {
                    sending = true
                    try {
                        MovnetApi.sendDirectMessage(friend.userId, body, token)
                        input = ""
                        val data = MovnetApi.loadDirectMessages(friend.userId, messages.lastOrNull()?.id ?: 0, token)
                        val fresh = (0 until data.length()).map { i -> val item = data.getJSONObject(i); MovnetMessage(item.optInt("id"), item.optInt("sender_id") == ownUserId, item.optString("body")) }
                        if (fresh.isNotEmpty()) messages = (messages + fresh).distinctBy { it.id }
                        error = null
                    } catch (e: Exception) { error = e.message ?: "Не удалось отправить сообщение" }
                    finally { sending = false }
                }
            }) {
                Text(if (sending) "…" else "➤", color = if (input.isNotBlank()) Accent else Muted, fontSize = 23.sp)
            }
        }
    }
}



@Composable
private fun DirectCallScreen(call: IncomingMovnetCall, token: String, isCaller: Boolean, onEnd: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val video = call.mediaType == "video"
    var callStatus by remember { mutableStateOf(if (isCaller) "ringing" else "accepted") }
    var error by remember { mutableStateOf<String?>(null) }
    var micEnabled by remember { mutableStateOf(true) }
    var cameraEnabled by remember { mutableStateOf(true) }
    var engine by remember { mutableStateOf<MovnetWebRtcCall?>(null) }
    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var permissionReady by remember { mutableStateOf(false) }
    val permissions = if (video) arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
        else arrayOf(android.Manifest.permission.RECORD_AUDIO)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionReady = permissions.all { result[it] == true }
        if (!permissionReady) error = "Разреши доступ к микрофону" + if (video) " и камере в настройках приложения." else " в настройках приложения."
    }
    LaunchedEffect(Unit) {
        permissionReady = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!permissionReady) permissionLauncher.launch(permissions)
    }
    LaunchedEffect(call.id) {
        while (true) {
            try {
                val remoteStatus = MovnetApi.loadDirectCall(call.id, token).optString("status")
                callStatus = remoteStatus
                if (remoteStatus == "ended" || remoteStatus == "rejected") { onEnd(); break }
            } catch (e: Exception) { error = e.message ?: "Не удалось проверить звонок" }
            kotlinx.coroutines.delay(1800)
        }
    }
    LaunchedEffect(permissionReady, callStatus, localRenderer, remoteRenderer) {
        if (permissionReady && callStatus == "accepted" && engine == null && (!video || (localRenderer != null && remoteRenderer != null))) {
            val media = MovnetWebRtcCall(context, call.id, token, scope, localRenderer, remoteRenderer, video) { error = it }
            engine = media
            media.start(isCaller)
        }
    }
    DisposableEffect(engine) { onDispose { engine?.dispose() } }
    Column(Modifier.fillMaxSize().background(Color(0xFF080A10)).navigationBarsPadding().statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (video) "MOVNET VIDEO" else "MOVNET CALL", color = Accent, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text(when(callStatus) { "ringing" -> "Вызов…"; "accepted" -> "На связи"; "rejected" -> "Отклонён"; else -> "Завершён" }, color = Muted, fontSize = 12.sp)
        }
        if (video) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(10.dp).background(Color.Black, RoundedCornerShape(22.dp))) {
                AndroidView(factory = { ctx -> SurfaceViewRenderer(ctx).also { remoteRenderer = it } }, modifier = Modifier.fillMaxSize())
                AndroidView(factory = { ctx -> SurfaceViewRenderer(ctx).also { localRenderer = it } },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(112.dp, 160.dp))
                if (callStatus == "ringing") Text("Ожидаем ответа…", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        } else {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(128.dp).background(Brush.linearGradient(listOf(Accent, Color(0xFF7045C5))), RoundedCornerShape(40.dp)),
                contentAlignment = Alignment.Center) { Text(call.name.firstOrNull()?.uppercase() ?: "M", fontSize = 52.sp, fontWeight = FontWeight.Bold) }
            Text(call.name, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            Text(if (callStatus == "ringing") "Ожидание ответа…" else "Аудиозвонок MOVNET", color = Muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.weight(1f))
        }
        error?.let { Text(it, color = Color(0xFFFF8494), fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { micEnabled = !micEnabled; engine?.setMicrophoneEnabled(micEnabled) },
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = SurfaceDark)) { Text(if (micEnabled) "🎙" else "🔇") }
            if (video) {
                FilledTonalIconButton(onClick = { cameraEnabled = !cameraEnabled; engine?.setCameraEnabled(cameraEnabled) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = SurfaceDark)) { Text(if (cameraEnabled) "📷" else "🚫") }
                FilledTonalIconButton(onClick = { engine?.switchCamera() },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = SurfaceDark)) { Text("🔄") }
            }
            IconButton(onClick = {
                scope.launch { runCatching { MovnetApi.updateDirectCallStatus(call.id, "ended", token) }; onEnd() }
            }, modifier = Modifier.size(64.dp).background(Color(0xFFE53950), RoundedCornerShape(50))) {
                Text("☎", color = Color.White, fontSize = 26.sp)
            }
        }
    }
}
