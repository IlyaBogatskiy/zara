# 👗 Zara Size Tracker Bot

**[🇷🇺 Русский](#-русский) · [🇬🇧 English](#-english) · [🇲🇪 Crnogorski](#-crnogorski) · [🇷🇸 Srpski](#-srpski)**

Telegram-бот, который следит за наличием размеров на сайте Zara и присылает уведомление, как только нужный размер появляется в продаже.

---

## 🇷🇺 Русский

### Что умеет бот

- 📦 **Карточка товара по ссылке** — пришлите ссылку на товар Zara и получите его название и список размеров, которых сейчас нет в наличии.
- 📌 **Подписка на размеры** — выберите кнопками один или несколько отсутствующих размеров, и бот начнёт следить за ними.
- 🛍 **Отслеживание товара целиком** — если Zara вообще не показывает размеры (страница «VIEW SIMILAR»), бот следит за появлением товара в принципе. Когда товар вернётся, а часть размеров всё ещё будет отсутствовать — предложит кнопкой продолжить следить именно за ними.
- 🔔 **Мгновенное уведомление** — как только размер появляется в наличии, приходит сообщение, и мониторинг этого размера автоматически останавливается.
- 🗂 **Меню подписок** — список всех отслеживаемых товаров, отписка от отдельного размера или от товара целиком.
- 💾 **Переживает рестарты** — подписки и последнее известное состояние размеров хранятся в PostgreSQL: после падения или перезапуска мониторинг продолжается ровно с того же места, без потерь и дублей уведомлений.

### Как это работает внутри

Проверка наличия идёт через **быстрый JSON-эндпоинт самой Zara** (~0.5 с на товар); при любой проблеме бот автоматически откатывается на **Selenium + headless Chrome** — медленнее, но максимально похоже на живого пользователя. Раз в 6 часов «канарейка» сверяет оба пути между собой и шлёт алерт оператору, если селекторы сломались. Один товар проверяется один раз за цикл, сколько бы чатов на него ни подписалось.

### Быстрый старт

**Вариант 1 — Docker (всё включено, ничего ставить не нужно):**

```bash
cp .env.example .env        # вписать TELEGRAM_BOT_TOKEN
docker compose up -d --build
```

Поднимутся PostgreSQL, Chrome (контейнер `selenium/standalone-chrome`) и бот. Swagger — `http://localhost:8100/swagger-ui.html`, живой браузер при отладке — `http://localhost:7900` (noVNC).

**Вариант 2 — локально.** Требования: **JDK 21**, **PostgreSQL**, **Google Chrome** (для Selenium-фоллбека).

```bash
# 1. База данных
createdb zara_bot

# 2. Переменные окружения (значения по умолчанию — в application.yaml)
export TELEGRAM_BOT_TOKEN=<токен от @BotFather>
export DB_URL=jdbc:postgresql://localhost:5432/zara_bot
export DB_USERNAME=zara
export DB_PASSWORD=zara_pass
export ZARA_ADMIN_CHAT_ID=<ваш chatId для технических алертов>   # опционально

# 3. Запуск
./mvnw spring-boot:run
```

Схема БД создаётся автоматически (`ddl-auto: update`); при первом старте на новой схеме данные из легаси-таблиц мигрируются сами.

### Конфигурация (`zara.*` в application.yaml)

| Свойство | По умолчанию | Описание |
|---|---|---|
| `zara.monitor.period-ms` | `60000` | период цикла мониторинга |
| `zara.api.enabled` | `true` | быстрый JSON-путь; `false` — всё через Selenium |
| `zara.driver.wait-seconds` | `15` | таймаут ожиданий Selenium |
| `zara.canary.enabled` | `true` | периодическая сверка Selenium с JSON API |
| `zara.admin-chat-id` | `0` | chatId оператора для алертов (0 — только лог) |
| `zara.user-agent` | Chrome 124 | общий User-Agent для обоих путей |

### Операторский API

Swagger UI: `http://localhost:8100/swagger-ui.html` — живая проверка наличия, подписки, **ручной запуск цикла мониторинга и канарейки**, метрики кэша. ⚠️ Без аутентификации — только для localhost.

### Тесты

```bash
# без внешних зависимостей (H2 + моки) + живой Zara API (нужна сеть)
./mvnw test -Dtest=SubscriptionServiceIntegrationTest,MonitoringSchedulerTest,ZaraTelegramListenerTest,ZaraApiClientTest
```

Подробности архитектуры: [`docs/CACHE_REDESIGN.md`](docs/CACHE_REDESIGN.md), бэклог: [`docs/IMPROVEMENTS.md`](docs/IMPROVEMENTS.md).

---

## 🇬🇧 English

### What the bot does

- 📦 **Product card by link** — send a Zara product link and get its name plus the list of currently out-of-stock sizes.
- 📌 **Size subscriptions** — pick one or more missing sizes with inline buttons and the bot starts watching them.
- 🛍 **Whole-product tracking** — when Zara hides the size list entirely (the "VIEW SIMILAR" page), the bot watches for the product to come back at all. Once it returns with some sizes still missing, a button offers to keep watching exactly those.
- 🔔 **Instant notification** — the moment a size is back in stock you get a message, and monitoring of that size stops automatically.
- 🗂 **Subscriptions menu** — list every tracked product, unsubscribe from a single size or the whole product.
- 💾 **Survives restarts** — subscriptions and the last known size availability live in PostgreSQL: after a crash or redeploy, monitoring resumes exactly where it left off, with no lost or duplicate notifications.

### How it works inside

Availability checks go through **Zara's own JSON endpoint** (~0.5 s per product); on any failure the bot silently falls back to **Selenium + headless Chrome** — slower, but as close to a real user as it gets. Every 6 hours a "canary" cross-checks both paths and alerts the operator if the CSS selectors broke. Each product is scraped once per cycle no matter how many chats subscribed to it.

### Quick start

**Option 1 — Docker (batteries included):**

```bash
cp .env.example .env        # fill in TELEGRAM_BOT_TOKEN
docker compose up -d --build
```

This starts PostgreSQL, Chrome (the `selenium/standalone-chrome` container) and the bot. Swagger — `http://localhost:8100/swagger-ui.html`, live browser for debugging — `http://localhost:7900` (noVNC).

**Option 2 — bare metal.** Requirements: **JDK 21**, **PostgreSQL**, **Google Chrome** (for the Selenium fallback).

```bash
createdb zara_bot

export TELEGRAM_BOT_TOKEN=<token from @BotFather>
export DB_URL=jdbc:postgresql://localhost:5432/zara_bot
export DB_USERNAME=zara
export DB_PASSWORD=zara_pass
export ZARA_ADMIN_CHAT_ID=<your chatId for technical alerts>   # optional

./mvnw spring-boot:run
```

The DB schema is created automatically (`ddl-auto: update`); on the first start with the new schema, legacy data migrates itself.

### Configuration (`zara.*` in application.yaml)

| Property | Default | Description |
|---|---|---|
| `zara.monitor.period-ms` | `60000` | monitoring cycle period |
| `zara.api.enabled` | `true` | fast JSON path; `false` — Selenium only |
| `zara.driver.wait-seconds` | `15` | Selenium wait timeout |
| `zara.canary.enabled` | `true` | periodic Selenium-vs-API cross-check |
| `zara.admin-chat-id` | `0` | operator chatId for alerts (0 — log only) |
| `zara.user-agent` | Chrome 124 | shared User-Agent for both paths |

### Operator API

Swagger UI: `http://localhost:8100/swagger-ui.html` — live availability checks, subscriptions, **manual triggers for the monitoring cycle and the canary**, cache metrics. ⚠️ No authentication — localhost only.

### Tests

```bash
# no external dependencies (H2 + mocks) + live Zara API (network required)
./mvnw test -Dtest=SubscriptionServiceIntegrationTest,MonitoringSchedulerTest,ZaraTelegramListenerTest,ZaraApiClientTest
```

Architecture details: [`docs/CACHE_REDESIGN.md`](docs/CACHE_REDESIGN.md), backlog: [`docs/IMPROVEMENTS.md`](docs/IMPROVEMENTS.md).

---

## 🇲🇪 Crnogorski

### Šta bot umije

- 📦 **Kartica proizvoda preko linka** — pošaljite link Zara proizvoda i dobićete njegov naziv i spisak veličina kojih trenutno nema na stanju.
- 📌 **Praćenje veličina** — izaberite dugmadima jednu ili više veličina koje nedostaju i bot počinje da ih prati.
- 🛍 **Praćenje cijelog proizvoda** — kad Zara uopšte ne prikazuje veličine (stranica „VIEW SIMILAR"), bot prati da li se proizvod uopšte vratio. Kad se vrati, a dio veličina i dalje nedostaje — dugme nudi da nastavi pratiti baš njih.
- 🔔 **Trenutno obavještenje** — čim se veličina pojavi na stanju, stiže poruka, a praćenje te veličine se automatski zaustavlja.
- 🗂 **Meni pretplata** — spisak svih praćenih proizvoda, odjava sa pojedine veličine ili cijelog proizvoda.
- 💾 **Preživljava restartovanja** — pretplate i posljednje poznato stanje veličina čuvaju se u PostgreSQL-u: poslije pada ili restarta praćenje se nastavlja tačno odakle je stalo, bez izgubljenih ili dupliranih obavještenja.

### Brzi početak

**Docker (sve uključeno):** `cp .env.example .env` (upišite `TELEGRAM_BOT_TOKEN`), zatim `docker compose up -d --build` — podižu se PostgreSQL, Chrome i bot.

**Ili lokalno** — potrebno: **JDK 21**, **PostgreSQL**, **Google Chrome** (za Selenium rezervni put).

```bash
createdb zara_bot

export TELEGRAM_BOT_TOKEN=<token od @BotFather>
export DB_URL=jdbc:postgresql://localhost:5432/zara_bot
export DB_USERNAME=zara
export DB_PASSWORD=zara_pass

./mvnw spring-boot:run
```

Provjera dostupnosti ide preko **brzog JSON endpointa same Zare** (~0.5 s po proizvodu), a u slučaju problema bot se automatski prebacuje na **Selenium + headless Chrome**. Svakih 6 sati „kanarinac" upoređuje oba puta i šalje upozorenje operatoru ako su se CSS selektori pokvarili. Swagger UI za testiranje: `http://localhost:8100/swagger-ui.html` (⚠️ bez autentifikacije — samo lokalno).

Testovi bez spoljnih zavisnosti: `./mvnw test -Dtest=SubscriptionServiceIntegrationTest,MonitoringSchedulerTest,ZaraTelegramListenerTest`.

---

## 🇷🇸 Srpski

### Šta bot ume

- 📦 **Kartica proizvoda preko linka** — pošaljite link Zara proizvoda i dobićete njegov naziv i spisak veličina kojih trenutno nema na stanju.
- 📌 **Praćenje veličina** — izaberite dugmićima jednu ili više veličina koje nedostaju i bot počinje da ih prati.
- 🛍 **Praćenje celog proizvoda** — kad Zara uopšte ne prikazuje veličine (stranica „VIEW SIMILAR"), bot prati da li se proizvod uopšte vratio. Kad se vrati, a deo veličina i dalje nedostaje — dugme nudi da nastavi da prati baš njih.
- 🔔 **Trenutno obaveštenje** — čim se veličina pojavi na stanju, stiže poruka, a praćenje te veličine se automatski zaustavlja.
- 🗂 **Meni pretplata** — spisak svih praćenih proizvoda, odjava sa pojedine veličine ili celog proizvoda.
- 💾 **Preživljava restartovanja** — pretplate i poslednje poznato stanje veličina čuvaju se u PostgreSQL-u: posle pada ili restarta praćenje se nastavlja tačno odakle je stalo, bez izgubljenih ili dupliranih obaveštenja.

### Brzi početak

**Docker (sve uključeno):** `cp .env.example .env` (upišite `TELEGRAM_BOT_TOKEN`), zatim `docker compose up -d --build` — podižu se PostgreSQL, Chrome i bot.

**Ili lokalno** — potrebno: **JDK 21**, **PostgreSQL**, **Google Chrome** (za Selenium rezervni put).

```bash
createdb zara_bot

export TELEGRAM_BOT_TOKEN=<token od @BotFather>
export DB_URL=jdbc:postgresql://localhost:5432/zara_bot
export DB_USERNAME=zara
export DB_PASSWORD=zara_pass

./mvnw spring-boot:run
```

Provera dostupnosti ide preko **brzog JSON endpointa same Zare** (~0.5 s po proizvodu), a u slučaju problema bot se automatski prebacuje na **Selenium + headless Chrome**. Na svakih 6 sati „kanarinac" upoređuje oba puta i šalje upozorenje operatoru ako su se CSS selektori pokvarili. Swagger UI za testiranje: `http://localhost:8100/swagger-ui.html` (⚠️ bez autentifikacije — samo lokalno).

Testovi bez spoljnih zavisnosti: `./mvnw test -Dtest=SubscriptionServiceIntegrationTest,MonitoringSchedulerTest,ZaraTelegramListenerTest`.

---

*Лицензия / License: private project.*
