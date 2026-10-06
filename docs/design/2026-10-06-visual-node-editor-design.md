# Visual Node Editor — дизайн

Дата: 2026-10-06. Статус: утверждён (brainstorming), готов к плану реализации.
Ветка: `visual-editor`.

## 1. Контекст и цель

VFXweaver — клиентский VFX-мод для Minecraft (Fabric + NeoForge, 6 сборочных нод:
`26.2`, `26.1.2`, `1.21.11` и их `-neoforge`-варианты). Эффекты описываются датапаком:
`data/<namespace>/vfx/<name>.json`.

Цель: встроить в VFXweaver **визуальный редактор эффектов на нодах** (UX как в Blender /
DaVinci Fusion / Scratch). Редактор:

- не привязан к конкретным эффектам — **парсит** их из VFXweaver, поэтому новые эффекты и
  параметры, добавленные в VFXweaver, автоматически становятся доступны;
- поддерживает бинды, выражения (`expr`), очереди (collections), ключи и все формы параметров;
- компилирует проект в обычный VFXweaver-датапак и **проигрывает результат в живом клиенте**;
- экспортируется в мир или в папку/zip (для серверов, куда датапак иначе не положить).

Редактор — **встроенная фича самого VFXweaver** (тот же modid, общая сборка Stonecutter), а не
отдельный мод.

## 2. Ограничения (нельзя нарушать)

1. **Конечный пользователь ничего не доустанавливает.** Никаких Node/Tauri/Electron. UI — локальный
   веб из jar, работает офлайн, браузер у пользователя уже есть.
2. **Никаких новых Java-зависимостей.** Только JDK (включая `com.sun.net.httpserver`) и уже
   имеющиеся библиотеки мода.
3. **`src/main` никогда не ссылается на `src/client`.** Весь код редактора (HTTP-сервер, SSE,
   статика, мост) — в `src/client/java` и `src/client/resources`. Иначе dedicated server падает
   `NoClassDefFoundError`.
4. **Не трогать и не переупорядочивать `VFXShaderPrograms.registerPost(...)`** — порядок имён параметров
   = порядок полей std140 `Config` в GLSL. Сдвиг молча ломает значения. Схема — параллельный
   источник, а не замена `configParams`.
5. **Датапак-формат обратно совместим**: только аддитивные изменения, поля не переименовывать и не
   удалять. Метаданные редактора (позиции эффект-нод/очередей, viewport) — **не** в формате эффекта.
6. **Сетевой протокол** (если когда-либо тронем) требует бампа `VFXTriggerPayload.PROTOCOL_VERSION`.
   В рамках v1 сетевой протокол не меняется вовсе.
7. **Не детектить версию MC в рантайме** для экспорта — формат `pack.mcmeta` берётся из
   compile-time константы ноды (Stonecutter).
8. **Определение готовности:** собираются **все шесть нод**; визуальный результат подтверждается
   запуском игры (Gradle-сборка не доказывает, что шейдер скомпилировался).

## 3. Объём

### v1 (вертикальный срез)
- Создать / открыть проект (датапак) → вкладки-эффекты (несколько эффектов в одном датапаке).
- Эффект-нода **пост-эффекта** (тип из схемы, сокеты = параметры) + value-ноды.
- Таймлайн с ключами, транспорт (play/pause/stop/loop), плейхед, scrub.
- Live-play в самой игре.
- Инспектор: слайдеры/поля/цвета/enum, bind, expr, keyframes.
- Undo/redo.
- Экспорт (в мир или папка/zip) и импорт всего датапака.
- Интроспекция: `VFXEffectSchema` + check-скрипт для **всех** типов.

### Позже (после v1)
- Маски (`mask`) и per-pixel поля (`field`).
- Collection-очереди (нода-очередь) как полноценный редактор.
- Не-пост типы (world / entity / camera / window / screen_image / surface / sky) в UI.
- Subgraph-ноды (макросы).
- Мультивыделение, копипаст блоков, группы/комментарии.

## 4. Принятые решения

| Вопрос | Решение | Почему |
|---|---|---|
| Хост редактора | Локальный веб, который отдаёт сам мод (`127.0.0.1`, статика в jar) | Без хостинга и без докачки; браузер уже есть |
| Канвас | Вендорить **LiteGraph.js** (MIT, vanilla JS) | Прецедент ComfyUI: типизированные сокеты, динамические ноды, очередь; без фреймворка |
| Источник схемы | Аддитивная интроспекция в VFXweaver: `VFXEffectSchema` + check-скрипт | Forward-compat: новый эффект появляется сам; схема проверяема |
| Структура | Внутри VFXweaver, тот же modid | Переиспользует Stonecutter-ноды, нет отдельной сборки/версионирования |
| Проект | Проект = датапак (namespace); вкладка = один `data/<ns>/vfx/<name>.json` | Соответствует формату; открытие пака даёт все вкладки |
| Хранение состояния редактора | `graph.meta` для value-нод (санкционировано) + sidecar `.vfxproj.json` для эффект-нод/очередей/viewport | `graph.meta` — штатное место («engine ignores it; may hold anything»); остального в формате нет |
| Применение в игре | Локальный слой `VFXDefinitionManager` (не зависит от экспорта) | Работает и на мультиплеер-клиенте, где world-датапак не перечитывается |
| Экспорт | Кнопка + выпадающий список: «В мир» (`saves/<world>/datapacks/<ns>/`) или папка/zip | Датапак нужен для сервера/шаринга; авто-`pack.mcmeta` под ноду |
| Синхрон web↔игра | Гибрид: compile-and-play для структурных изменений + инкремент (`set_param`/`set_keyframe`) для горячих правок | Превью == экспорт; отзывчивость как в Blender |
| Транспорт моста | SSE (push) + POST (команды), 0 Java-зависимостей | Достаточно для localhost; WS с нуля не нужен |
| Версия пакета | Compile-time константа Stonecutter | Runtime-детект ненадёжен |

## 5. Архитектура

```
┌─ Браузер (localhost) ──────────────────────────────┐
│  LiteGraph canvas   │  таймлайн/ключи │ инспектор  │
│         модель проекта (JS, источник истины)        │
│         IR нод/рёбер → компилятор в VFX-JSON        │
└───────────▲───────────────────────┬────────────────┘
     SSE (push) │                   │ POST (команды)
┌───────────────┴───────────────────▼────────────────┐
│  VFXweaver client — Локальный мост (JDK HttpServer) │
│  127.0.0.1:<random>  + токен  ·  статика из jar     │
│  /events /schema /compile /transport /set_param     │
│  /import /export                                    │
└───────┬───────────────────────────────┬─────────────┘
        │                               │
   VFXEffectSchema                 VFXDefinitionManager(local layer)
   (интроспекция)                  + play/stop/seek (elapsed-aware)
        │                               │
        └──────────► рендер VFXweaver ◄──┘     Экспорт → pack (world / zip)
```

Все компоненты — loader-agnostic, кроме точек входа. HTML/JS/CSS/LiteGraph — из
`src/client/resources/assets/vfxweaver/editor/`.

## 6. Компоненты

### 6.1 Локальный мост (Java, client)
- `com.sun.net.httpserver.HttpServer` (JDK), bind `127.0.0.1`, случайный свободный порт.
- Токен (`SecureRandom`, 128 бит) при старте; передаётся в UI (localStorage / URL-fragment).
- Раздаёт статику из classpath; REST-эндпоинты; SSE-канал `/events`.
- Стартует по команде/кнопке, не работает постоянно. Закрывает SSE по простою (30 с).
- Никаких loader-импортов: чистый JDK + внутренние классы мода.

### 6.2 Web-UI (LiteGraph)
- Модель проекта в JS — **единственный источник истины**. Внутри — абстрактный IR
  (`Node`, `Edge`, `Property`), который **сериализуется в формат VFXweaver** (блоки `graph`,
  `inputs`, `effects`/`collection`, `params`). IR даёт гибкость для будущих расширений.
- Эффект-нода: сокеты-параметры из `/schema` (сгруппированы по `group`).
- Value-ноды: существующие `graph`-kinds (`constant`, `time`, `random`, `noise`, `curve`, `math`,
  `mix`, `clamp`, `remap`, `bind`, `expr`, `compare`, `boolean`, `if`, `switch`, `subgraph`).
- Инспектор: контролы по `VFXParamDescriptor` (min/max/step/enum), bind-выбор, expr-редактор,
  keyframes.
- Таймлайн (нижняя панель): дорожка на анимируемый параметр, ключи `{time,value,easing}`,
  drag/snap/zoom, плейхед, транспорт.
- Undo/redo: стек команд.

### 6.3 Интроспекция — `VFXEffectSchema` (shared/main)
- `VFXParamDescriptor`: `name`, `kind` (`FLOAT`/`INT`/`BOOL`/`ENUM`/`COLOR_RGB`/`POSITION`),
  `default`, `min`, `max`, `step`, `group`, `animatable`, `bindable`, `fieldCapable`, `enumValues`,
  `neutral`.
- `VFXEffectDescriptor`: упорядоченный список `VFXParamDescriptor` + структурные опции
  (positions / entity-selector / sound / particle / shape / block / item / pattern / surface /
  anchor; позже mask / field / collection / window / screen_image).
- Реестр: `Map<VFXEffectType, VFXEffectDescriptor>`.
- Пост-эффекты: дескрипторы параллельно `registerPost` (сам `registerPost` не меняется).
  Не-пост: явные дескрипторы (параметры сейчас захардкожены в рендерерах).
- Нейтральные значения — из `VFXEffectType.neutralValue()`; field-capable — из
  `FIELD_INPUTS`.

### 6.4 Игровая интеграция
- Компилированное определение регистрируется в локальном слое `VFXDefinitionManager`
  (`registerLocal`) и играется существующим локальным play-путём.
- **Аддитивно** добавляется elapsed-aware локальный play/seek (клиентский;
  сетевой протокол не трогаем). Используется для scrub и детерминированного превью.
- Ключи с отрицательным временем («от текущего момента») конвертируются в абсолютные тики
  **в web-модели** при компиляции.

### 6.5 Экспорт/импорт
- Экспорт: все вкладки → `data/<ns>/vfx/*.json` + `pack.mcmeta` по compile-time формату ноды
  (26.2: `min_format: [107, 1]`, `max_format: 107`; 1.21.11 — уточнить отдельно) + sidecar.
  Пункты назначения: мир (`saves/<world>/datapacks/<ns>/`) или папка/zip.
- Импорт: папка/zip → парсим `data/<ns>/vfx/*.json` → вкладки. Позиции value-нод — из `graph.meta`,
  остальное — из sidecar; без sidecar ноды раскладываются автоматически.
- Что не удалось восстановить, не ломаем — показываем как есть.

## 7. Протокол моста

Все запросы — под токеном. Только `GET`/`POST`.

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/` | UI (статика) |
| GET | `/events` | SSE: `status`, ack `revision`, ошибки, события транспорта |
| GET | `/schema` | `VFXEffectSchema` (JSON) |
| POST | `/compile` | Полный слепок: VFX-JSON определения (+`revision`) → регистрируется и играется |
| POST | `/transport` | play / pause / stop / loop / seek |
| POST | `/set_param` | Инкремент: параметр (+`revision`) |
| POST | `/set_keyframe` | Инкремент: ключ (+`revision`) |
| POST | `/import` | Загрузить пак (папка/zip) |
| POST | `/export` | Экспорт (в мир / папка / zip) |

**Синхронизация:**
- Web — источник истины; игра — исполнитель.
- Монотонный `revision` в каждом сообщении.
- Структурные изменения → `revision++` + полный `/compile`.
- Горячие правки → `set_param`/`set_keyframe` с текущим `revision`; ack без совпадения —
  web форсит полный `/compile`.
- При переподключении SSE web запрашивает текущий `revision` игры и при расхождении синхронизирует
  полным compile.
- Drag: debounce 50–100 мс и batching.

## 8. Безопасность

- Bind только `127.0.0.1`; `SecureRandom` 128-bit токен.
- Проверка `Origin`/`Referer` на `http://127.0.0.1:<port>` / `http://localhost:<port>`.
- Только `GET`/`POST`; SSE-соединения закрываются по 30 с простоя.
- Сервер не стартует постоянно — по явной команде/кнопке.

## 9. План верификации

1. `./gradlew :26.2:build :26.1.2:build :1.21.11:build` и
   `./gradlew :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build` — все шесть.
2. `checkSchema` (Gradle-задача + CI): множество имён схемы == множество имён `registerPost` для
   каждого типа; дескриптор на каждый `VFXEffectType`; дефолты схемы == `neutralValue()` где
   нейтраль ≠ `NaN`.
3. Throwaway `main()`: компилятор JSON→`VFXDefinition`, round-trip импорт/экспорт, `pack.mcmeta`
   по ноде.
4. `javap` для `com.sun.net.httpserver.HttpServer` и loader/API-сигнатур (не угадывать).
5. Ручной in-game сценарий: открыть пак → правка → live-play → экспорт в мир → `/reload` →
   `/vfx play`.

## 10. Порядок реализации

1. `VFXEffectSchema` + `checkSchema`.
2. HTTP-сервер + SSE + токен в `src/client`.
3. Вендорить LiteGraph + базовый UI (открыть/вкладки/canvas).
4. Компилятор web → JSON → `VFXDefinition` (+ `graph`/`inputs`).
5. Транспорт + elapsed-aware seek.
6. Таймлайн + инкрементные обновления.
7. Импорт/экспорт (+ sidecar).

## 11. Открытые вопросы

- Точный формат `pack.mcmeta` для 1.21.11 (для 26.2 — `min_format: [107, 1]`, `max_format: 107`).
- Точные сигнатуры loader/API и `HttpServer` — подтвердить `javap` перед использованием.
- Поведение `graph.meta` при перезаписи определения движком (проверить, что round-trip сохраняет).
- Совместимость LiteGraph с целевыми браузерами (baseline из jar).

## 12. Риски

| Риск | Митигация |
|---|---|
| Расхождение web-модели и игры | `revision` + reconcile; web — единственный источник истины |
| Схема разъедется с рендерами | `checkSchema` в CI на каждый тип |
| Сломанный UBO из-за правок `registerPost` | `registerPost` не трогаем; схема параллельна |
| Scrub «щёлкает» / дорогой | elapsed-aware seek + debounce |
| UI в dedicated-server jar | ассеты и код — только в `src/client` |
| Огромный объём v1 | вертикальный срез: только пост-эффекты, без масок/полей/очередей |
