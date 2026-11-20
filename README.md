# MOVNET 0.9.0

MOVNET — Android-клиент каталога фильмов и FastAPI backend. Android: Kotlin, Jetpack Compose и AndroidX Media3. Backend: FastAPI, SQLAlchemy и PostgreSQL.

## Структура

- `app/` — Android-приложение.
- `backend/` — API и Dockerfile для Render.
- `.github/workflows/android.yml` — автоматическая debug-сборка APK.

## Что работает в прототипе

- Серверный каталог, поиск и фильтры.
- Регистрация и вход по email/паролю; пароли хешируются Argon2id.
- JWT access token и восстановление профиля через `/me`.
- Локальное избранное.
- Media3-плеер для публичных демонстрационных MP4-ссылок.
- Серверные заготовки entitlement и одноразовых кодов.

## Ограничения и безопасность

- Демокарточки и тестовые MP4 не являются реальными фильмами. Загружайте только контент, права на который у MOVNET есть.
- Платёжный провайдер и webhook не подключены; `/payments/create` намеренно возвращает HTTP 501. Выбор тарифа в приложении — демонстрационный.
- Premium-потоки не выдаются публичным каталогом. Для реального Premium потребуется защищённая выдача потоков с проверкой entitlement и подписанными URL.
- Android пока не проверяет entitlement и не реализует полноценную серверную защиту платного просмотра. Не используйте текущую сборку для коммерческого запуска.
- Перед production необходимы миграции БД, rate limiting, подтверждение email, безопасное обновление/отзыв токенов, резервные копии, мониторинг и аудит безопасности.

## Render: развёртывание API

Создайте Web Service из этого репозитория:

- Branch: `release/0.9.0` (или `main` после слияния).
- Root Directory: `backend`.
- Runtime: Docker.
- Dockerfile Path: `Dockerfile`.
- Health Check Path: `/health`.

Добавьте переменные окружения в Render:

- `APP_ENV=production`
- `DATABASE_URL` — Internal Database URL вашей Render PostgreSQL.
- `JWT_SECRET` — уникальная случайная строка не короче 32 символов.
- `ADMIN_API_KEY` — отдельный случайный ключ не короче 32 символов.
- `JWT_ISSUER=movnet-api`
- `ACCESS_TOKEN_MINUTES=30`
- `CORS_ORIGINS` — список разрешённых origin через запятую, если он нужен web-клиентам.

Не публикуйте секреты в GitHub, APK или чате. Стандартные URL Render `postgresql://` и `postgres://` автоматически преобразуются для psycopg v3.

После деплоя проверьте `https://<имя-сервиса>.onrender.com/health` и `/docs`.

## Сборка APK

GitHub Actions запускается при push в `main`, `master` или `release/**`, а также вручную через **Actions → Build MOVNET APK → Run workflow**.

Для подключения приложения создайте GitHub Actions Variable `MOVNET_API_BASE_URL` со значением публичного HTTPS URL API без завершающего `/`. Затем скачайте артефакт `MOVNET-0.9.0-APK` из успешного запуска. Это debug APK для тестирования, не production-релиз.

## Локальный запуск backend

См. `backend/README.md`. Не используйте демонстрационные секреты на публичном сервере.
