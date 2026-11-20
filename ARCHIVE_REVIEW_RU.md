# MOVNET 0.9.0 — отчёт о проверке

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
