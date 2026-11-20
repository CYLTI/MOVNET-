from __future__ import annotations

import hashlib
import secrets
from datetime import datetime, timedelta, timezone
from typing import Generator

import jwt
from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pwdlib import PasswordHash
from pydantic import BaseModel, EmailStr, Field
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, create_engine, select, inspect, text as sql_text
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, relationship, sessionmaker


class Settings(BaseSettings):
    database_url: str = "sqlite:///./movnet.db"
    jwt_secret: str = "DEV_ONLY_CHANGE_THIS_SECRET_BEFORE_DEPLOYMENT"
    jwt_issuer: str = "movnet-api"
    access_token_minutes: int = 30
    cors_origins: str = "http://localhost:3000"
    admin_api_key: str = ""
    app_env: str = "development"
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()
if settings.app_env.lower() in {"production", "prod"}:
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
engine = create_engine(database_url, connect_args=connect_args, pool_pre_ping=True)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
password_hash = PasswordHash.recommended()
bearer = HTTPBearer(auto_error=False)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[str] = mapped_column(String(320), unique=True, index=True)
    display_name: Mapped[str] = mapped_column(String(80), default="")
    password_hash: Mapped[str] = mapped_column(String(512))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    entitlement: Mapped["Entitlement | None"] = relationship(back_populates="user", uselist=False)


class Entitlement(Base):
    __tablename__ = "entitlements"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), unique=True, index=True)
    plan: Mapped[str] = mapped_column(String(80))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    user: Mapped[User] = relationship(back_populates="entitlement")


class CatalogItem(Base):
    __tablename__ = "catalog_items"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    title: Mapped[str] = mapped_column(String(200), index=True)
    genre: Mapped[str] = mapped_column(String(80), index=True)
    year: Mapped[str] = mapped_column(String(8))
    premium: Mapped[bool] = mapped_column(Boolean, default=False)
    emoji: Mapped[str] = mapped_column(String(16), default="🎬")
    description: Mapped[str] = mapped_column(Text)
    content_type: Mapped[str] = mapped_column(String(40), index=True)
    meta: Mapped[str] = mapped_column(String(120), default="")
    stream_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    published: Mapped[bool] = mapped_column(Boolean, default=True, index=True)


class RedeemCode(Base):
    __tablename__ = "redeem_codes"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    code_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    plan: Mapped[str] = mapped_column(String(80))
    duration_days: Mapped[int] = mapped_column(Integer)
    redeemed: Mapped[bool] = mapped_column(Boolean, default=False)
    redeemed_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True)
    redeemed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


Base.metadata.create_all(bind=engine)
# Safe additive migration for existing Render databases; existing accounts are preserved.
with engine.begin() as migration:
    user_columns = {column["name"] for column in inspect(engine).get_columns("users")}
    if "display_name" not in user_columns:
        migration.execute(sql_text("ALTER TABLE users ADD COLUMN display_name VARCHAR(80) NOT NULL DEFAULT ''"))
# Starter catalogue data. Replace demo metadata/streams with content you own or license.
with SessionLocal() as seed_db:
    if seed_db.scalar(select(CatalogItem.id).limit(1)) is None:
        starter_items = [
            ("Последний рубеж", "Боевик", "2025", False, "🚀", "Команда исследователей отправляется на опасную миссию за пределы известной галактики.", "Фильмы", "2 ч 08 мин"),
            ("Тёмный город", "Детектив", "2024", False, "🌃", "Молодой следователь раскрывает тайну родного города.", "Фильмы", "1 ч 52 мин"),
            ("Ледяная планета", "Фантастика", "2025", True, "🪐", "Экспедиция ищет новый дом для человечества.", "Фильмы", "2 ч 14 мин"),
            ("Ночная смена", "Ужасы", "2023", True, "🌙", "Обычная ночная смена превращается в испытание.", "Фильмы", "1 ч 46 мин"),
            ("Город на краю", "Драма", "2025", False, "🏙️", "История людей, чьи судьбы пересекаются в большом городе.", "Сериалы", "1 сезон • 8 серий"),
            ("Код будущего", "Фантастика", "2024", True, "🤖", "Группа друзей обнаруживает загадочную технологию.", "Сериалы", "2 сезона • 16 серий"),
            ("Космические друзья", "Приключения", "2025", False, "🧑‍🚀", "Путешествие маленьких исследователей по далёким мирам.", "Мультфильмы", "1 ч 24 мин"),
            ("Лесные истории", "Семейный", "2024", False, "🦊", "Лисёнок и друзья учатся помогать друг другу.", "Мультфильмы", "1 ч 18 мин"),
            ("Мега-команда", "Комедия", "2025", False, "🦸", "Необычная команда спасает свой город.", "Мультсериалы", "3 сезона • 24 серии"),
            ("Тайны океана", "Приключения", "2024", True, "🐬", "Юные исследователи открывают секреты подводного мира.", "Мультсериалы", "1 сезон • 12 серий"),
        ]
        for row in starter_items:
            seed_db.add(CatalogItem(title=row[0], genre=row[1], year=row[2], premium=row[3], emoji=row[4], description=row[5], content_type=row[6], meta=row[7], stream_url=None))
        seed_db.commit()

# One clearly-labelled, openly licensed sample title for player testing.
# Big Buck Bunny is a Blender Foundation open movie under CC BY 3.0.
# This is a remote HTTPS test stream, not a file uploaded to MOVNET storage.
DEMO_TITLE = "Big Buck Bunny"
DEMO_STREAM_URL = "https://flutter.github.io/assets-for-api-docs/assets/videos/bee.mp4"
DEMO_DESCRIPTION = (
    "Открытый короткометражный мультфильм Blender Foundation. "
    "Добавлен в MOVNET только для проверки видеоплеера; это не фильм из коммерческого каталога. "
    "Лицензия CC BY 3.0: https://creativecommons.org/licenses/by/3.0/ "
    "Авторство: Blender Foundation / www.bigbuckbunny.org"
)
with SessionLocal() as seed_db:
    # Do not attach unrelated demo clips to fictional catalogue titles.
    for title in ("Последний рубеж", "Тёмный город", "Ледяная планета"):
        item = seed_db.scalar(select(CatalogItem).where(CatalogItem.title == title))
        if item is not None and item.stream_url and item.stream_url.startswith("https://storage.googleapis.com/gtv-videos-bucket/sample/"):
            item.stream_url = None

    demo_item = seed_db.scalar(select(CatalogItem).where(CatalogItem.title == DEMO_TITLE))
    if demo_item is None:
        demo_item = CatalogItem(
            title=DEMO_TITLE,
            genre="Мультфильм",
            year="2008",
            premium=False,
            emoji="🐰",
            description=DEMO_DESCRIPTION,
            content_type="Тестовые видео",
            meta="Короткометражный фильм • Blender Foundation",
            stream_url=DEMO_STREAM_URL,
            published=True,
        )
        seed_db.add(demo_item)
    else:
        # Keep the existing database row in sync when the demo stream URL changes.
        # Render uses a persistent database, so changing only the seed constant is not enough.
        demo_item.stream_url = DEMO_STREAM_URL
        demo_item.description = DEMO_DESCRIPTION
        demo_item.published = True
    seed_db.commit()

app = FastAPI(title="MOVNET API", version="0.10.0", description="MOVNET account, entitlement and catalogue API")
origins = [x.strip() for x in settings.cors_origins.split(",") if x.strip()]
app.add_middleware(CORSMiddleware, allow_origins=origins, allow_credentials=True, allow_methods=["GET", "POST", "PUT", "DELETE"], allow_headers=["Authorization", "Content-Type", "X-Admin-Key"])


class RegisterRequest(BaseModel):
    email: EmailStr
    display_name: str = Field(default="Пользователь", min_length=2, max_length=80)
    password: str = Field(min_length=8, max_length=128)


class ProfileUpdateRequest(BaseModel):
    display_name: str = Field(min_length=2, max_length=80)


class LoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=128)


class CatalogUpsertRequest(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    genre: str = Field(min_length=1, max_length=80)
    year: str = Field(min_length=4, max_length=8)
    premium: bool = False
    emoji: str = Field(default="🎬", max_length=16)
    description: str = Field(min_length=1, max_length=5000)
    content_type: str = Field(min_length=1, max_length=40)
    meta: str = Field(default="", max_length=120)
    stream_url: str | None = None
    published: bool = True


class RedeemRequest(BaseModel):
    code: str = Field(min_length=12, max_length=128)


def db_session() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def issue_token(user: User) -> str:
    now = datetime.now(timezone.utc)
    payload = {"sub": str(user.id), "iss": settings.jwt_issuer, "iat": now, "exp": now + timedelta(minutes=settings.access_token_minutes)}
    return jwt.encode(payload, settings.jwt_secret, algorithm="HS256")


def current_user(credentials: HTTPAuthorizationCredentials | None = Depends(bearer), db: Session = Depends(db_session)) -> User:
    if credentials is None:
        raise HTTPException(status_code=401, detail="Bearer token required")
    try:
        payload = jwt.decode(credentials.credentials, settings.jwt_secret, algorithms=["HS256"], issuer=settings.jwt_issuer)
        user_id = int(payload["sub"])
    except (jwt.PyJWTError, KeyError, ValueError):
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=401, detail="Account not found")
    return user



@app.get("/catalog")
def get_catalog(q: str | None = None, content_type: str | None = None, genre: str | None = None, premium: bool | None = None, db: Session = Depends(db_session)):
    """Public published catalogue, including configured HTTPS demo/licensed stream URLs."""
    stmt = select(CatalogItem).where(CatalogItem.published.is_(True)).order_by(CatalogItem.id)
    if q:
        stmt = stmt.where(CatalogItem.title.ilike(f"%{q.strip()}%"))
    if content_type:
        stmt = stmt.where(CatalogItem.content_type == content_type)
    if genre:
        stmt = stmt.where(CatalogItem.genre == genre)
    if premium is not None:
        stmt = stmt.where(CatalogItem.premium.is_(premium))
    rows = db.scalars(stmt).all()
    return [{"id": x.id, "title": x.title, "genre": x.genre, "year": x.year,
             "premium": x.premium, "emoji": x.emoji, "description": x.description,
             "type": x.content_type, "meta": x.meta, "stream_url": None if x.premium else x.stream_url} for x in rows]



@app.post("/admin/catalog", status_code=201)
def create_catalog_item(body: CatalogUpsertRequest, x_admin_key: str | None = Header(default=None), db: Session = Depends(db_session)):
    if not settings.admin_api_key or not x_admin_key or not secrets.compare_digest(x_admin_key, settings.admin_api_key):
        raise HTTPException(status_code=403, detail="Admin access denied")
    if body.stream_url and not body.stream_url.startswith("https://"):
        raise HTTPException(status_code=422, detail="stream_url must use HTTPS")
    item = CatalogItem(**body.model_dump())
    db.add(item); db.commit(); db.refresh(item)
    return {"id": item.id, "title": item.title, "published": item.published}


@app.put("/admin/catalog/{item_id}")
def update_catalog_item(item_id: int, body: CatalogUpsertRequest, x_admin_key: str | None = Header(default=None), db: Session = Depends(db_session)):
    if not settings.admin_api_key or not x_admin_key or not secrets.compare_digest(x_admin_key, settings.admin_api_key):
        raise HTTPException(status_code=403, detail="Admin access denied")
    if body.stream_url and not body.stream_url.startswith("https://"):
        raise HTTPException(status_code=422, detail="stream_url must use HTTPS")
    item = db.get(CatalogItem, item_id)
    if item is None:
        raise HTTPException(status_code=404, detail="Content not found")
    for key, value in body.model_dump().items():
        setattr(item, key, value)
    db.commit()
    return {"id": item.id, "title": item.title, "published": item.published}


@app.delete("/admin/catalog/{item_id}", status_code=204)
def hide_catalog_item(item_id: int, x_admin_key: str | None = Header(default=None), db: Session = Depends(db_session)):
    if not settings.admin_api_key or not x_admin_key or not secrets.compare_digest(x_admin_key, settings.admin_api_key):
        raise HTTPException(status_code=403, detail="Admin access denied")
    item = db.get(CatalogItem, item_id)
    if item is None:
        raise HTTPException(status_code=404, detail="Content not found")
    item.published = False
    db.commit()
    return None

@app.get("/catalog/{item_id}")
def get_catalog_item(item_id: int, db: Session = Depends(db_session)):
    item = db.get(CatalogItem, item_id)
    if item is None or not item.published:
        raise HTTPException(status_code=404, detail="Content not found")
    return {"id": item.id, "title": item.title, "genre": item.genre, "year": item.year,
            "premium": item.premium, "emoji": item.emoji, "description": item.description,
            "type": item.content_type, "meta": item.meta}

@app.get("/health")
def health():
    return {"status": "ok", "service": "movnet-api"}


@app.post("/auth/register", status_code=201)
def register(body: RegisterRequest, db: Session = Depends(db_session)):
    email = body.email.lower()
    if db.scalar(select(User).where(User.email == email)):
        raise HTTPException(status_code=409, detail="Email already registered")
    user = User(email=email, display_name=body.display_name.strip(), password_hash=password_hash.hash(body.password))
    db.add(user)
    db.commit()
    db.refresh(user)
    return {"access_token": issue_token(user), "token_type": "bearer", "expires_in": settings.access_token_minutes * 60}


@app.post("/auth/login")
def login(body: LoginRequest, db: Session = Depends(db_session)):
    user = db.scalar(select(User).where(User.email == body.email.lower()))
    if user is None or not password_hash.verify(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Incorrect email or password")
    return {"access_token": issue_token(user), "token_type": "bearer", "expires_in": settings.access_token_minutes * 60}


@app.get("/me")
def me(user: User = Depends(current_user)):
    return {"id": user.id, "email": user.email, "display_name": user.display_name, "created_at": user.created_at}


@app.put("/me")
def update_me(body: ProfileUpdateRequest, user: User = Depends(current_user), db: Session = Depends(db_session)):
    user.display_name = body.display_name.strip()
    db.commit()
    db.refresh(user)
    return {"id": user.id, "email": user.email, "display_name": user.display_name, "created_at": user.created_at}


@app.get("/entitlements")
def entitlements(user: User = Depends(current_user), db: Session = Depends(db_session)):
    item = db.scalar(select(Entitlement).where(Entitlement.user_id == user.id))
    active = bool(item and utc_aware(item.expires_at) > datetime.now(timezone.utc))
    return {"active": active, "plan": item.plan if active else None, "expires_at": item.expires_at if active else None}


def utc_aware(value: datetime) -> datetime:
    """Normalize SQLite's naive datetimes and PostgreSQL aware datetimes to UTC."""
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def digest_code(raw_code: str) -> str:
    return hashlib.sha256(raw_code.strip().upper().encode("utf-8")).hexdigest()


@app.post("/codes/redeem")
def redeem(body: RedeemRequest, user: User = Depends(current_user), db: Session = Depends(db_session)):
    # Codes are stored only as SHA-256 digests and redeemed in one DB transaction.
    code = db.scalar(select(RedeemCode).where(RedeemCode.code_hash == digest_code(body.code)).with_for_update())
    if code is None or code.redeemed:
        raise HTTPException(status_code=400, detail="Code is invalid or already used")
    now = datetime.now(timezone.utc)
    existing = db.scalar(select(Entitlement).where(Entitlement.user_id == user.id).with_for_update())
    start = utc_aware(existing.expires_at) if existing and utc_aware(existing.expires_at) > now else now
    expiry = start + timedelta(days=code.duration_days)
    if existing:
        existing.plan, existing.expires_at = code.plan, expiry
    else:
        db.add(Entitlement(user_id=user.id, plan=code.plan, expires_at=expiry))
    code.redeemed, code.redeemed_by, code.redeemed_at = True, user.id, now
    db.commit()
    return {"active": True, "plan": code.plan, "expires_at": expiry}


@app.post("/payments/create", status_code=501)
def create_payment(user: User = Depends(current_user)):
    # Intentionally disabled until a real provider and verified webhook are configured.
    raise HTTPException(status_code=501, detail="Payments are not configured. No payment was created and no subscription was activated.")


# Helper for local development only. Never expose this as a public endpoint.
def create_dev_code(db: Session, plan: str, duration_days: int) -> str:
    raw = secrets.token_urlsafe(18).upper()
    db.add(RedeemCode(code_hash=digest_code(raw), plan=plan, duration_days=duration_days))
    db.commit()
    return raw
