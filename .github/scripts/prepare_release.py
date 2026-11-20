from pathlib import Path
import shutil

root = Path(".")
app_gradle = root / "app/build.gradle.kts"
s = app_gradle.read_text()
s = s.replace("versionCode = 8", "versionCode = 9")
s = s.replace('versionName = "0.8.0"', 'versionName = "0.9.0"')
app_gradle.write_text(s)

backend = root / "backend/app/main.py"
s = backend.read_text()
s = s.replace(
    '    admin_api_key: str = ""\n    model_config',
    '    admin_api_key: str = ""\n    app_env: str = "development"\n    model_config'
)
old = '''if settings.jwt_secret == "DEV_ONLY_CHANGE_THIS_SECRET_BEFORE_DEPLOYMENT":
    print("WARNING: using development JWT secret; set JWT_SECRET before deployment")

connect_args = {"check_same_thread": False} if settings.database_url.startswith("sqlite") else {}
engine = create_engine(settings.database_url, connect_args=connect_args, pool_pre_ping=True)'''
new = '''if settings.app_env.lower() in {"production", "prod"}:
    if settings.jwt_secret == "DEV_ONLY_CHANGE_THIS_SECRET_BEFORE_DEPLOYMENT" or len(settings.jwt_secret) < 32:
        raise RuntimeError("Production requires a unique JWT_SECRET of at least 32 characters")
    if not settings.admin_api_key or len(settings.admin_api_key) < 32:
        raise RuntimeError("Production requires an ADMIN_API_KEY of at least 32 characters")
elif settings.jwt_secret == "DEV_ONLY_CHANGE_THIS_SECRET_BEFORE_DEPLOYMENT":
    print("WARNING: using development JWT secret; set JWT_SECRET before deployment")

# Render PostgreSQL URLs are often postgresql://; this project uses psycopg v3.
database_url = settings.database_url
if database_url.startswith("postgres://"):
    database_url = "postgresql+psycopg://" + database_url[len("postgres://"):]
elif database_url.startswith("postgresql://"):
    database_url = "postgresql+psycopg://" + database_url[len("postgresql://"):]

connect_args = {"check_same_thread": False} if database_url.startswith("sqlite") else {}
engine = create_engine(database_url, connect_args=connect_args, pool_pre_ping=True)'''
if old not in s:
    raise SystemExit("Could not find expected database setup block")
s = s.replace(old, new)
s = s.replace(
    'app = FastAPI(title="MOVNET API", version="0.1.0", description="Account and entitlement API prototype")',
    'app = FastAPI(title="MOVNET API", version="0.9.0", description="MOVNET account, entitlement and catalogue API")'
)
s = s.replace(
    'allow_headers=["Authorization", "Content-Type"]',
    'allow_headers=["Authorization", "Content-Type", "X-Admin-Key"]'
)
s = s.replace(
    '"type": x.content_type, "meta": x.meta, "stream_url": x.stream_url} for x in rows]',
    '"type": x.content_type, "meta": x.meta, "stream_url": None if x.premium else x.stream_url} for x in rows]'
)
backend.write_text(s)

(root / "backend/.env.example").write_text("""# Example only. Set real values in your hosting provider; never commit a real .env.
POSTGRES_PASSWORD=replace_with_a_strong_database_password
DATABASE_URL=postgresql://movnet:replace_with_a_strong_database_password@db:5432/movnet
JWT_SECRET=replace_with_at_least_32_random_bytes
JWT_ISSUER=movnet-api
ACCESS_TOKEN_MINUTES=30
APP_ENV=production
CORS_ORIGINS=http://localhost:3000
ADMIN_API_KEY=replace_with_a_long_random_admin_key
""")

(root / ".github/workflows/android.yml").write_text("""name: Build MOVNET APK

on:
  push:
    branches: [main, master, "release/**"]
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout source
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.9"

      - name: Build debug APK
        run: gradle --no-daemon -PMOVNET_API_BASE_URL="${{ vars.MOVNET_API_BASE_URL }}" assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: MOVNET-0.9.0-APK
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 14
""")

(root / "backend/Dockerfile").write_text("""FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \\
    PYTHONUNBUFFERED=1
WORKDIR /srv

COPY requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
""")

(root / "README.md").write_text("""# MOVNET 0.9.0

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
""")

(root / "backend/README.md").write_text("""# MOVNET API 0.9.0

FastAPI backend. Реализованы регистрация `/auth/register`, вход `/auth/login`, Argon2id, JWT, профиль `/me`, статус подписки `/entitlements`, каталог `/catalog` и административное управление каталогом через `X-Admin-Key`. Есть одноразовые коды `/codes/redeem`.

## Render (Docker Web Service)

- Root Directory: `backend`
- Runtime: Docker
- Dockerfile Path: `Dockerfile`
- Health Check Path: `/health`

Подключите Render PostgreSQL и задайте Environment:

- `APP_ENV=production`
- `DATABASE_URL` — Internal Database URL от Render PostgreSQL.
- `JWT_SECRET` — случайное значение не короче 32 символов.
- `ADMIN_API_KEY` — отдельное случайное значение не короче 32 символов.
- `JWT_ISSUER=movnet-api`
- `ACCESS_TOKEN_MINUTES=30`
- `CORS_ORIGINS` — при необходимости список origin через запятую.

При `APP_ENV=production` сервер откажется запускаться с демонстрационным/коротким JWT secret или пустым/коротким admin key. Стандартные Render URL `postgresql://` и `postgres://` автоматически преобразуются для psycopg v3.

Проверка после публикации: `GET /health`; документация: `/docs`.

## Локально через Docker Compose

Скопируйте `.env.example` в `.env`, замените все значения на локальные секреты, затем выполните `docker compose up --build`. Не коммитьте `.env`.

## Администрирование каталога

`POST /admin/catalog`, `PUT /admin/catalog/{id}`, `DELETE /admin/catalog/{id}` используют заголовок `X-Admin-Key`. Потоки допускаются только по HTTPS. Публичный `/catalog` не возвращает `stream_url` для карточек Premium. Перед коммерческим запуском нужна отдельная авторизованная выдача подписанных потоков и серверная проверка entitlement.

## Ограничения

Каталог при первом запуске наполняется демонстрационными карточками; три бесплатные карточки получают тестовые MP4. Это не реальные фильмы. Платежи не подключены, `/payments/create` возвращает HTTP 501. Для production также нужны миграции Alembic, rate limiting, подтверждение email, refresh-token rotation/revocation, бэкапы и аудит.
""")

(root / "ARCHIVE_REVIEW_RU.md").write_text("""# MOVNET 0.9.0 — отчёт о проверке

## Структура

- Android: Kotlin, Jetpack Compose, Media3.
- Backend: FastAPI, SQLAlchemy, PostgreSQL через psycopg v3.
- GitHub Actions: debug APK собирается из исходников в корне репозитория, без ручного извлечения ZIP.
- Render: backend находится в корне `backend/`, Dockerfile — `backend/Dockerfile`.

## Исправления 0.9.0

- Исходники вынесены из ZIP в обычную структуру репозитория, чтобы Render мог найти `backend`.
- Версия Android повышена до 0.9.0 / versionCode 9.
- Workflow сборки APK переведён на прямую сборку исходников.
- Добавлена совместимость с типовыми `postgresql://` и `postgres://` URL Render при использовании psycopg v3.
- Для production добавлена проверка обязательных JWT и admin секретов.
- Заголовок `X-Admin-Key` добавлен в CORS allow headers.
- Публичный каталог больше не раскрывает stream URL для Premium-карточек.
- Удалены временный `main.py.tmp` и скомпилированный Python cache из публикуемой структуры.

## Что остаётся незавершённым

- Оплата и проверяемые платёжные webhook.
- Защищённая выдача Premium-потоков с проверкой entitlement и подписанными URL.
- Реальные лицензированные фильмы, постеры и потоки.
- Подтверждение email, восстановление пароля, ротация refresh-токенов, rate limiting и миграции БД.
- Настоящая рекламная сеть.

Проверки синтаксиса Python должны выполняться в CI. Полная Android-компиляция и проверка на реальном Render окружении подтверждаются успешным GitHub Actions и health check после публикации.
""")

(root / "GITHUB_MOBILE_BUILD_RU.md").write_text("""# Сборка MOVNET APK с телефона

1. Откройте репозиторий и вкладку **Actions**.
2. Выберите **Build MOVNET APK**.
3. Нажмите **Run workflow** и подтвердите запуск.
4. Дождитесь зелёной галочки у запуска.
5. Откройте запуск и скачайте артефакт **MOVNET-0.9.0-APK** внизу страницы.
6. Распакуйте архив и установите `app-debug.apk` на Android. При необходимости разрешите установку приложений из выбранного источника.

Для подключения к API заранее добавьте GitHub Actions Variable `MOVNET_API_BASE_URL` с публичным HTTPS URL backend без завершающего слеша.

Это debug APK для тестирования. Для публикации потребуется release-сборка с подписью.
""")

changelog = root / "CHANGELOG.md"
if changelog.exists():
    old = changelog.read_text()
    changelog.write_text("""# CHANGELOG

## 0.9.0 — 20.11.2026
- Исходники проекта извлечены из архива и размещены в корне репозитория.
- Render backend теперь имеет ожидаемый путь `backend/Dockerfile`.
- Android versionName/versionCode обновлены до 0.9.0/9.
- Добавлена поддержка типовых PostgreSQL URL Render с psycopg v3.
- Добавлены production-проверки обязательных секретов.
- Публичный каталог не раскрывает URL потоков Premium-карточек.
- GitHub Actions собирает APK непосредственно из дерева исходников.

""" + old)

# Remove archive and temporary artifacts now that the source tree is extracted.
(root / "MOVNET_0.8.0_MP4_test.zip").unlink(missing_ok=True)
(root / "backend/app/main.py.tmp").unlink(missing_ok=True)
shutil.rmtree(root / "backend/app/__pycache__", ignore_errors=True)
shutil.rmtree(root / "extracted", ignore_errors=True)

# This bootstrap workflow and script are one-shot. Remove them from the release branch.
(root / ".github/workflows/prepare-release.yml").unlink(missing_ok=True)
(root / ".github/scripts/prepare_release.py").unlink(missing_ok=True)
try:
    (root / ".github/scripts").rmdir()
except OSError:
    pass
