package com.movnet.app

import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
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

private val Background = Color(0xFF090B10)
private val SurfaceDark = Color(0xFF171B25)
private val Accent = Color(0xFFE50935)
private val Muted = Color(0xFF9AA3B2)

private val contentTypes = listOf("Всё", "Фильмы", "Сериалы", "Мультфильмы", "Мультсериалы")
private val genres = listOf("Все жанры", "Боевик", "Комедия", "Фантастика", "Детектив", "Драма", "Ужасы", "Приключения", "Семейный")

data class Movie(
    val title: String,
    val genre: String,
    val year: String,
    val premium: Boolean,
    val emoji: String,
    val description: String,
    val type: String,
    val meta: String,
    val streamUrl: String? = null
)

private val catalog = listOf(
    Movie("Последний рубеж", "Боевик", "2025", false, "🚀", "Команда исследователей отправляется на опасную миссию за пределы известной галактики.", "Фильмы", "2 ч 08 мин"),
    Movie("Тёмный город", "Детектив", "2024", false, "🌃", "Молодой следователь раскрывает тайну, которая меняет его представление о родном городе.", "Фильмы", "1 ч 52 мин"),
    Movie("Ледяная планета", "Фантастика", "2025", true, "🪐", "Экспедиция ищет новый дом для человечества на далёкой ледяной планете.", "Фильмы", "2 ч 14 мин"),
    Movie("Ночная смена", "Ужасы", "2023", true, "🌙", "Обычная ночная смена превращается в испытание, когда в здании гаснет свет.", "Фильмы", "1 ч 46 мин"),
    Movie("Город на краю", "Драма", "2025", false, "🏙️", "История людей, чьи судьбы пересекаются в большом городе.", "Сериалы", "1 сезон • 8 серий"),
    Movie("Код будущего", "Фантастика", "2024", true, "🤖", "Группа друзей обнаруживает загадочную технологию.", "Сериалы", "2 сезона • 16 серий"),
    Movie("Космические друзья", "Приключения", "2025", false, "🧑‍🚀", "Весёлое путешествие маленьких исследователей по далёким мирам.", "Мультфильмы", "1 ч 24 мин"),
    Movie("Лесные истории", "Семейный", "2024", false, "🦊", "Лисёнок и его друзья учатся помогать друг другу.", "Мультфильмы", "1 ч 18 мин"),
    Movie("Мега-команда", "Комедия", "2025", false, "🦸", "Необычная команда спасает свой город от забавных неприятностей.", "Мультсериалы", "3 сезона • 24 серии"),
    Movie("Тайны океана", "Приключения", "2024", true, "🐬", "Юные исследователи открывают секреты подводного мира.", "Мультсериалы", "1 сезон • 12 серий")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var accountEmail by remember { mutableStateOf("") }
    var authToken by remember { mutableStateOf(preferences.getString("access_token", null)) }
    var loggedIn by remember { mutableStateOf(authToken != null) }
    var catalogItems by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var catalogLoading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try { catalogItems = MovnetApi.loadCatalog(); catalogError = null }
        catch (e: Exception) { catalogError = e.message ?: "Не удалось загрузить каталог" }
        finally { catalogLoading = false }
    }
    LaunchedEffect(authToken) {
        val token = authToken
        if (token != null) {
            try { accountEmail = MovnetApi.loadProfile(token).optString("email") }
            catch (_: Exception) { preferences.edit().remove("access_token").apply(); authToken = null; loggedIn = false }
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
                playingMovie != null -> PlayerScreen(movie = playingMovie!!, onBack = { playingMovie = null })
                showAccount -> AccountScreen(
                    email = accountEmail, loggedIn = loggedIn,
                    onEmail = { accountEmail = it },
                    onSubmit = { email, password, register ->
                        val token = MovnetApi.authenticate(email, password, register)
                        preferences.edit().putString("access_token", token).apply()
                        authToken = token; loggedIn = true; accountEmail = email.trim()
                    },
                    onLogout = { preferences.edit().remove("access_token").apply(); authToken = null; loggedIn = false; accountEmail = "" },
                    onBack = { showAccount = false }
                )
                showPremium -> PremiumScreen(onBack = { showPremium = false })
                selectedMovie != null -> MovieDetails(
                    movie = selectedMovie!!,
                    onBack = { selectedMovie = null },
                    onPremium = { showPremium = true },
                    onPlay = { playingMovie = selectedMovie }
                )
                else -> CatalogScreen(
                    query = query, onQuery = { query = it },
                    catalog = catalogItems, catalogLoading = catalogLoading, catalogError = catalogError,
                    selectedType = selectedType, onType = { selectedType = it },
                    selectedGenre = selectedGenre, onGenre = { selectedGenre = it },
                    premiumFilter = premiumFilter, onPremiumFilter = { premiumFilter = it },
                    selectedTab = selectedTab, onTab = { selectedTab = it },
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
    catalog: List<Movie>, catalogLoading: Boolean, catalogError: String?,
    selectedType: String, onType: (String) -> Unit,
    selectedGenre: String, onGenre: (String) -> Unit,
    premiumFilter: String, onPremiumFilter: (String) -> Unit,
    selectedTab: String, onTab: (String) -> Unit,
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 26.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).background(Accent, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                        Text("M", fontSize = 25.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("MOVNET", fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                }
                Text("КАТАЛОГ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFF541329), Color(0xFF171B25))), RoundedCornerShape(22.dp))
                    .padding(20.dp)
            ) {
                Text("ТВОЙ КИНОМИР", color = Color(0xFFFFA6B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                Text("Истории на любой вкус", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(6.dp))
                Text("Фильмы, сериалы и анимация в одном месте.", color = Color(0xFFD5D8DF), fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Button(onClick = onPremium, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("Смотреть Premium")
                }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
                placeholder = { Text("Поиск по названию, жанру…", color = Muted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Поиск") },
                singleLine = true, shape = RoundedCornerShape(15.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF303746),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White
                )
            )
        }
        item {
            Text("Категории", Modifier.padding(start = 20.dp, bottom = 10.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                contentTypes.forEach { type ->
                    FilterChip(
                        selected = selectedType == type, onClick = { onType(type) },
                        label = { Text(type) }, modifier = Modifier.padding(end = 7.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent, selectedLabelColor = Color.White,
                            containerColor = SurfaceDark, labelColor = Color.White
                        )
                    )
                }
            }
            Spacer(Modifier.height(17.dp))
        }
        item {
            Text("Жанры", Modifier.padding(start = 20.dp, bottom = 10.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                genres.forEach { genre ->
                    AssistChip(
                        onClick = { onGenre(genre) }, label = { Text(genre) },
                        modifier = Modifier.padding(end = 7.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selectedGenre == genre) Color(0xFF512033) else SurfaceDark,
                            labelColor = Color.White
                        )
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Каталог MOVNET", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("${filtered.size} найдено", color = Muted, fontSize = 11.sp)
            }
            Row(Modifier.padding(horizontal = 18.dp, vertical = 9.dp)) {
                listOf("Все", "Бесплатные", "Premium").forEach { filter ->
                    FilterChip(
                        selected = premiumFilter == filter, onClick = { onPremiumFilter(filter) },
                        label = { Text(filter) }, modifier = Modifier.padding(end = 7.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent, selectedLabelColor = Color.White,
                            containerColor = SurfaceDark, labelColor = Color.White
                        )
                    )
                }
            }
        }
        if (catalogLoading) {
            item { CircularProgressIndicator(color = Accent, modifier = Modifier.padding(24.dp)) }
        } else if (catalogError != null) {
            item { Text("Каталог сервера недоступен: $catalogError", color = Color(0xFFFFC857), modifier = Modifier.padding(20.dp)) }
        } else if (filtered.isEmpty()) {
            item {
                Text("Ничего не найдено. Попробуй изменить поиск или фильтры.",
                    color = Muted, modifier = Modifier.fillMaxWidth().padding(24.dp))
            }
        } else {
            items(filtered) { item -> MovieRow(movie = item, isFavorite = favoriteTitles.contains(item.title), onClick = { onMovie(item) }, onFavorite = { onFavoriteToggle(item.title) }) }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Главная", "Каталог", "Избранное", "Профиль").forEach { tab ->
                    Text(tab, color = if (selectedTab == tab) Color.White else Muted,
                        fontSize = 12.sp, modifier = Modifier.clickable { onTab(tab); if (tab == "Профиль") onAccount() }.padding(5.dp))
                }
            }
            Text(if (selectedTab == "Избранное") "Избранное хранится только в памяти до закрытия приложения." else "Демонстрационный каталог: карточки и данные пока локальные.",
                color = Color(0xFF687181), fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
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
        Box(Modifier.width(92.dp).height(112.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF52243B), Color(0xFF242C42))), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) { Text(movie.emoji, fontSize = 42.sp) }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(movie.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(5.dp))
            Text("${movie.year} • ${movie.genre}", color = Muted, fontSize = 11.sp)
            Text(movie.type, color = Color(0xFFB7A4FF), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
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
fun MovieDetails(movie: Movie, onBack: () -> Unit, onPremium: () -> Unit, onPlay: () -> Unit) {
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
                contentAlignment = Alignment.Center) { Text(movie.emoji, fontSize = 78.sp) }
            Spacer(Modifier.height(18.dp))
            Text(movie.title, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Text("${movie.year} • ${movie.type} • ${movie.genre}", color = Muted)
            Text(movie.meta, color = Muted, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            Text(movie.description, color = Color(0xFFD0D4DC), lineHeight = 22.sp)
            Spacer(Modifier.height(10.dp))
            if (movie.premium) {
                Text("Офлайн-загрузки доступны подписчикам Premium. Активация подписки появится после подключения оплаты.", color = Muted, fontSize = 12.sp)
            } else {
                Text("Офлайн-загрузки доступны только с активной подпиской Premium.", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))

            if (movie.premium) {
                Text("Этот материал отмечен как доступный по подписке Premium.", color = Color(0xFFFFC857))
                Spacer(Modifier.height(10.dp))
                Button(onClick = onPremium, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("Посмотреть тарифы")
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
                    if (movie.premium) {
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
fun PlayerScreen(movie: Movie, onBack: () -> Unit) {
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
    val player = remember { ExoPlayer.Builder(context).build() }

    LaunchedEffect(movie.streamUrl) {
        val url = movie.streamUrl?.takeIf { it.startsWith("https://") }
        player.stop()
        player.clearMediaItems()
        if (url != null) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
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
                    "Тестовый MP4-поток. Это демонстрационное видео, а не сам фильм из названия карточки. Для публикации используйте видео, на которое у MOVNET есть права.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(18.dp)
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(SurfaceDark),
                contentAlignment = Alignment.Center) {
                Text("Видео пока не добавлено", color = Muted)
            }
            Text("Для этой карточки пока нет HTTPS-ссылки на видео. Добавьте stream_url в каталоге на сервере.",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
        }

        if (!fullScreen) {
            Text("Для бесплатного тарифа рекламные места пока представлены макетом.",
                color = Color(0xFFFFC857), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 18.dp))
        }
    }
}
@Composable
fun AccountScreen(
    email: String, loggedIn: Boolean, onEmail: (String) -> Unit,
    onSubmit: suspend (String, String, Boolean) -> Unit,
    onLogout: () -> Unit, onBack: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("‹ Назад", color = Muted, modifier = Modifier.clickable { onBack() })
        Text("Профиль MOVNET", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
        if (loggedIn) {
            Text("Вы вошли как $email", color = Color(0xFF65D6A2))
            Text("Аккаунт подтверждён сервером MOVNET.", color = Muted)
            Button(onClick = onLogout, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Выйти") }
        } else {
            Text(if (register) "Создай аккаунт MOVNET. Пароль должен содержать не менее 10 символов." else "Войди в существующий аккаунт MOVNET.", color = Muted)
            OutlinedTextField(value = email, onValueChange = onEmail, label = { Text("Электронная почта") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = Color(0xFFFF7A8A)) }
            Button(onClick = {
                scope.launch {
                    busy = true; error = null
                    try { onSubmit(email, password, register) }
                    catch (e: Exception) { error = e.message ?: "Не удалось выполнить запрос" }
                    finally { busy = false }
                }
            }, enabled = !busy && email.contains("@") && password.length >= if (register) 10 else 1,
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text(if (busy) "Подключение…" else if (register) "Зарегистрироваться" else "Войти")
            }
            TextButton(onClick = { register = !register; error = null }) {
                Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться", color = Color(0xFFFFA6B8))
            }
            Text("Для работы авторизации необходимо развернуть backend и указать HTTPS-адрес API при сборке приложения.", color = Muted, fontSize = 12.sp)
        }
    }
}
