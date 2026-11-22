from __future__ import annotations

import hashlib
import secrets
import json
import smtplib
import urllib.error
import urllib.request
from email.message import EmailMessage
from datetime import datetime, timedelta, timezone
from typing import Generator

import jwt
from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pwdlib import PasswordHash
from pydantic import BaseModel, EmailStr, Field
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, create_engine, select, inspect, text as sql_text, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, relationship, sessionmaker


class Settings(BaseSettings):
    database_url: str = "sqlite:///./movnet.db"
    jwt_secret: str = "DEV_ONLY_CHANGE_THIS_SECRET_BEFORE_DEPLOYMENT"
    jwt_issuer: str = "movnet-api"
    access_token_minutes: int = 30
    cors_origins: str = "http://localhost:3000"
    admin_api_key: str = ""
    creator_emails: str = ""  # Comma-separated creator accounts with permanent Premium+
    app_env: str = "development"
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_username: str = ""
    smtp_password: str = ""
    smtp_from_email: str = ""
    resend_api_key: str = ""
    resend_from_email: str = "MOVNET <onboarding@resend.dev>"
    account_recovery_key: str = ""
    account_recovery_email: str = ""
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
    poster_url: Mapped[str | None] = mapped_column(Text, nullable=True)


class MovieReview(Base):
    __tablename__ = "movie_reviews"
    __table_args__ = (UniqueConstraint("user_id", "item_id", name="uq_movie_review_user_item"),)
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    item_id: Mapped[int] = mapped_column(ForeignKey("catalog_items.id"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    rating: Mapped[int] = mapped_column(Integer)
    text: Mapped[str] = mapped_column(String(1200), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class PasswordResetCode(Base):
    __tablename__ = "password_reset_codes"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    code_hash: Mapped[str] = mapped_column(String(64), index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    used: Mapped[bool] = mapped_column(Boolean, default=False)


class AccountRecoveryUse(Base):
    __tablename__ = "account_recovery_uses"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    key_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    used_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


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
    catalog_columns = {column["name"] for column in inspect(engine).get_columns("catalog_items")}
    if "poster_url" not in catalog_columns:
        migration.execute(sql_text("ALTER TABLE catalog_items ADD COLUMN poster_url TEXT"))
# MOVNET starter catalogue: openly licensed Blender Foundation films.
# Each entry is independently playable and carries attribution/license information.
OPEN_FILMS = [
    {
        "title": "Big Buck Bunny", "genre": "Комедия", "year": "2008",
        "emoji": "🐰", "description": "Открытый короткометражный мультфильм о добродушном зайце, который даёт отпор трём хулиганистым грызунам. Автор: Blender Foundation. Лицензия: Creative Commons Attribution 3.0 (CC BY 3.0). Атрибуция: © copyright 2008, Blender Foundation / www.bigbuckbunny.org. Изменения MOVNET: поток не изменён.",
        "meta": "9 мин 56 сек • CC BY 3.0",
        "stream_url": "https://archive.org/download/BigBuckBunny_328/BigBuckBunny_512kb.mp4",
    },
    {
        "title": "Sintel", "genre": "Фэнтези", "year": "2010",
        "emoji": "🐉", "description": "Фэнтези-история о девушке, которая отправляется на поиски маленького дракона. Автор: Blender Foundation. Лицензия: Creative Commons Attribution 3.0 (CC BY 3.0). Атрибуция: © copyright Blender Foundation / durian.blender.org. Изменения MOVNET: поток не изменён.",
        "meta": "14 мин 48 сек • CC BY 3.0",
        "stream_url": "https://archive.org/download/Sintel/sintel-2048-stereo_512kb.mp4",
    },
    {
        "title": "Tears of Steel", "genre": "Фантастика", "year": "2012",
        "emoji": "🤖", "description": "Научно-фантастический фильм о команде, пытающейся спасти Амстердам от вторжения роботов. Автор: Blender Institute. Лицензия: Creative Commons Attribution 3.0 (CC BY 3.0). Атрибуция: (CC) Blender Foundation / mango.blender.org. Изменения MOVNET: поток не изменён.",
        "meta": "12 мин 14 сек • CC BY 3.0",
        "stream_url": "https://archive.org/download/Tears-of-Steel/tears_of_steel_720p.mp4",
    },
    {
        "title": "Elephants Dream", "genre": "Фантастика", "year": "2006",
        "emoji": "🐘", "description": "Открытый фильм Blender Foundation. Лицензия: Creative Commons Attribution. Атрибуция: Blender Foundation / Netherlands Media Art Institute. Изменения MOVNET: поток не изменён.",
        "meta": "10 мин • CC BY",
        "stream_url": "https://archive.org/download/ElephantsDream_277/elephant_dreams_640_512kb.mp4",
    },
]

with SessionLocal() as seed_db:
    # Remove fictional placeholders from the public catalogue; retain rows for audit/history.
    placeholder_titles = [
        "Последний рубеж", "Тёмный город", "Ледяная планета", "Ночная смена",
        "Город на краю", "Код будущего", "Космические друзья",
        "Лесные истории", "Мега-команда", "Тайны океана", "Elephants Dream", "Spring",
    ]
    for title in placeholder_titles:
        item = seed_db.scalar(select(CatalogItem).where(CatalogItem.title == title))
        if item is not None:
            item.published = False

    for film in OPEN_FILMS:
        item = seed_db.scalar(select(CatalogItem).where(CatalogItem.title == film["title"]))
        values = {
            "genre": film["genre"], "year": film["year"], "premium": False,
            "emoji": film["emoji"], "description": film["description"],
            "content_type": "Фильмы", "meta": film["meta"],
            "stream_url": film["stream_url"], "published": True,
        }
        if item is None:
            seed_db.add(CatalogItem(title=film["title"], **values))
        else:
            for key, value in values.items():
                setattr(item, key, value)
    seed_db.commit()

app = FastAPI(title="MOVNET API", version="0.11.0", description="MOVNET account, entitlement and openly licensed film catalogue API")
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
    poster_url: str | None = None
    published: bool = True


class ReviewRequest(BaseModel):
    rating: int = Field(ge=1, le=5)
    text: str = Field(default="", max_length=1200)


class RedeemRequest(BaseModel):
    code: str = Field(min_length=12, max_length=128)


class PasswordResetRequest(BaseModel):
    email: EmailStr


class PasswordResetConfirm(BaseModel):
    email: EmailStr
    code: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")
    new_password: str = Field(min_length=8, max_length=128)


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
             "type": x.content_type, "meta": x.meta, "stream_url": None if x.premium else x.stream_url, "poster_url": x.poster_url,
             "rating": _rating_summary(db, x.id)["average"], "rating_count": _rating_summary(db, x.id)["count"]} for x in rows]



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
            "type": item.content_type, "meta": item.meta, "poster_url": item.poster_url}


def _rating_summary(db: Session, item_id: int) -> dict:
    rows = db.scalars(select(MovieReview.rating).where(MovieReview.item_id == item_id)).all()
    return {"average": round(sum(rows) / len(rows), 1) if rows else 0.0, "count": len(rows)}


@app.get("/catalog/{item_id}/reviews")
def get_movie_reviews(item_id: int, db: Session = Depends(db_session)):
    item = db.get(CatalogItem, item_id)
    if item is None or not item.published:
        raise HTTPException(status_code=404, detail="Content not found")
    rows = db.scalars(select(MovieReview).where(MovieReview.item_id == item_id).order_by(MovieReview.updated_at.desc()).limit(100)).all()
    users = {u.id: u.display_name or "Зритель MOVNET" for u in db.scalars(select(User).where(User.id.in_([r.user_id for r in rows]))).all()} if rows else {}
    summary = _rating_summary(db, item_id)
    return {"average": summary["average"], "count": summary["count"],
            "reviews": [{"id": r.id, "author": users.get(r.user_id, "Зритель MOVNET"),
                         "rating": r.rating, "text": r.text, "created_at": r.updated_at}
                        for r in rows]}


@app.post("/catalog/{item_id}/reviews")
def submit_movie_review(item_id: int, body: ReviewRequest, user: User = Depends(current_user), db: Session = Depends(db_session)):
    item = db.get(CatalogItem, item_id)
    if item is None or not item.published:
        raise HTTPException(status_code=404, detail="Content not found")
    review = db.scalar(select(MovieReview).where(MovieReview.item_id == item_id, MovieReview.user_id == user.id))
    now = datetime.now(timezone.utc)
    if review is None:
        review = MovieReview(item_id=item_id, user_id=user.id, rating=body.rating, text=body.text.strip(), created_at=now, updated_at=now)
        db.add(review)
    else:
        review.rating, review.text, review.updated_at = body.rating, body.text.strip(), now
    db.commit()
    return {"ok": True, **_rating_summary(db, item_id)}


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


def _send_password_reset_email(recipient: str, code: str) -> None:
    subject = "Код восстановления пароля MOVNET"
    text_body = (
        f"Код для смены пароля MOVNET: {code}\\n\\n"
        "Код действует 10 минут. Если вы не запрашивали смену пароля, просто проигнорируйте письмо."
    )

    # Render Free blocks outbound SMTP ports 25/465/587. Use the HTTPS API only.
    if not settings.resend_api_key:
        raise HTTPException(
            status_code=503,
            detail="Отправка писем не настроена: на сервере отсутствует RESEND_API_KEY."
        )

    payload = json.dumps({
        "from": settings.resend_from_email,
        "to": [recipient],
        "subject": subject,
        "text": text_body,
    }).encode("utf-8")
    request = urllib.request.Request(
        "https://api.resend.com/emails",
        data=payload,
        headers={
            "Authorization": f"Bearer {settings.resend_api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            if 200 <= response.status < 300:
                return
            raise HTTPException(status_code=502, detail="Почтовый сервис не принял письмо.")
    except urllib.error.HTTPError as exc:
        # Log provider response for diagnosis, but never log API keys or reset codes.
        try:
            provider_message = exc.read(1000).decode("utf-8", errors="replace")
        except Exception:
            provider_message = ""
        print(f"RESEND_HTTP_ERROR status={exc.code} response={provider_message[:1000]}")
        raise HTTPException(
            status_code=502,
            detail=f"Resend отклонил отправку (HTTP {exc.code}). Проверьте API key и подтверждение отправителя."
        ) from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        print(f"RESEND_NETWORK_ERROR type={type(exc).__name__}")
        raise HTTPException(
            status_code=502,
            detail="Не удалось связаться с почтовым сервисом Resend. Повторите позже."
        ) from exc

@app.post("/auth/password/reset/request")
def request_password_reset(body: PasswordResetRequest, db: Session = Depends(db_session)):
    email = body.email.lower()
    user = db.scalar(select(User).where(User.email == email))
    # Do not reveal whether an account exists.
    if user is None:
        return {"message": "Если аккаунт существует, код восстановления отправлен на почту."}
    now = datetime.now(timezone.utc)
    db.query(PasswordResetCode).filter(
        PasswordResetCode.user_id == user.id, PasswordResetCode.used.is_(False)
    ).update({"used": True})
    raw_code = f"{secrets.randbelow(1_000_000):06d}"
    db.add(PasswordResetCode(
        user_id=user.id,
        code_hash=hashlib.sha256(raw_code.encode("utf-8")).hexdigest(),
        expires_at=now + timedelta(minutes=10),
        attempts=0,
        used=False,
    ))
    db.commit()
    _send_password_reset_email(user.email, raw_code)
    return {"message": "Если аккаунт существует, код восстановления отправлен на почту."}


@app.post("/auth/password/reset/confirm")
def confirm_password_reset(body: PasswordResetConfirm, db: Session = Depends(db_session)):
    user = db.scalar(select(User).where(User.email == body.email.lower()))
    if user is None:
        raise HTTPException(status_code=400, detail="Неверный или просроченный код.")
    now = datetime.now(timezone.utc)
    reset = db.scalar(
        select(PasswordResetCode)
        .where(PasswordResetCode.user_id == user.id, PasswordResetCode.used.is_(False))
        .order_by(PasswordResetCode.id.desc())
    )
    if reset is None or reset.attempts >= 5 or utc_aware(reset.expires_at) <= now:
        raise HTTPException(status_code=400, detail="Неверный или просроченный код.")
    supplied_hash = hashlib.sha256(body.code.encode("utf-8")).hexdigest()
    if not secrets.compare_digest(supplied_hash, reset.code_hash):
        reset.attempts += 1
        db.commit()
        raise HTTPException(status_code=400, detail="Неверный или просроченный код.")
    user.password_hash = password_hash.hash(body.new_password)
    reset.used = True
    db.query(PasswordResetCode).filter(
        PasswordResetCode.user_id == user.id, PasswordResetCode.id != reset.id
    ).update({"used": True})
    db.commit()
    return {"message": "Пароль успешно изменён. Теперь войдите с новым паролем."}


@app.post("/admin/account/recover")
def recover_account(body: AccountRecoveryRequest, x_recovery_key: str | None = Header(default=None), db: Session = Depends(db_session)):
    """One-time, email-restricted emergency recovery. Remove ACCOUNT_RECOVERY_KEY after use."""
    configured_key = settings.account_recovery_key
    allowed_email = settings.account_recovery_email.strip().lower()
    if (not configured_key or not allowed_email or not x_recovery_key
            or not secrets.compare_digest(x_recovery_key, configured_key)
            or body.email.lower() != allowed_email):
        raise HTTPException(status_code=403, detail="Recovery access denied")
    key_hash = hashlib.sha256(configured_key.encode("utf-8")).hexdigest()
    if db.scalar(select(AccountRecoveryUse).where(AccountRecoveryUse.key_hash == key_hash)):
        raise HTTPException(status_code=410, detail="Recovery key has already been used")
    user = db.scalar(select(User).where(User.email == allowed_email))
    if user is None:
        raise HTTPException(status_code=404, detail="Account not found")
    user.password_hash = password_hash.hash(body.new_password)
    db.add(AccountRecoveryUse(key_hash=key_hash))
    db.query(PasswordResetCode).filter(PasswordResetCode.user_id == user.id).update({"used": True})
    db.commit()
    return {"message": "Password updated. Sign in with the new password."}


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
    # Creator privileges are configured server-side and cannot be self-assigned by clients.
    creator_emails = {value.strip().lower() for value in settings.creator_emails.split(",") if value.strip()}
    if user.email.lower() in creator_emails:
        return {"active": True, "plan": "Premium+", "expires_at": None, "is_creator": True}
    item = db.scalar(select(Entitlement).where(Entitlement.user_id == user.id))
    active = bool(item and utc_aware(item.expires_at) > datetime.now(timezone.utc))
    return {"active": active, "plan": item.plan if active else None, "expires_at": item.expires_at if active else None, "is_creator": False}


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
