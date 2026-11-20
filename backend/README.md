# MOVNET API 0.9.0

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
