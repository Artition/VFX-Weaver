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
- Undo/redo: стек команд в web-модели. Undo откатывает web-модель и шлёт **полный
  `/compile`**; инкрементальный откат в игре не делаем (проще и без рассинхрона).
- Контекстное меню нод: delete / duplicate / properties.

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
  `FIELD_INPUTS`. `neutral` заполняется только когда значение конечно; при `Float.NaN`
  (параметр не фейдится) поле отсутствует (`neutral: null`).

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

### 6.6 Sidecar `.vfxproj.json`

Рядом с паком (или в zip) — минимальный файл состояния редактора. Хранит **только то, чего нет
в формате эффекта**: позиции эффект-нод/очередей, viewport/зум, порядок и активную вкладку,
версию. Граф value-нод (узлы, рёбра, их позиции) живёт в `graph`/`graph.meta` датапака и **не
дублируется** здесь.

```json
{
  "version": 1,
  "namespace": "my_vfx",
  "activeTab": "effect1",
  "tabOrder": ["effect1", "effect2"],
  "tabs": {
    "effect1": {
      "effectNodes": [{ "id": "fx1", "pos": [100, 200], "size": [220, 120] }],
      "queueNodes": [],
      "viewport": { "x": 0, "y": 0, "zoom": 1.0 },
      "timeline": { "scrollTick": 0, "pxPerTick": 2.0 }
    }
  }
}
```

- Позиции value-нод берутся из `graph.meta` (санкционированное место, переживает шаринг пака);
  sidecar их не заменяет.
- Sidecar отсутствует → импорт работает, ноды раскладываются автоматически.
- Sidecar не является «первичным хранилищем»: датапак самодостаточен, sidecar — удобство layout.

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

**Ошибки и восстановление:**
- `/compile` возвращает структурированную ошибку (`{"error": "...", "details": {...}}`; для ошибок
  парсинга — позиция/поле), а не «тихий» fail. Компилируемый JSON дополнительно прогоняется через
  Java-парсер `VFXDefinition.parse` — плохой JSON падает громко.
- SSE: авто-переподключение с экспоненциальным backoff (1s, 2s, 4s…), reconcile по `revision`.
- Инкрементная операция (`set_param`/`set_keyframe`) вернула ошибку → web откатывает правку в модели
  и шлёт полный `/compile`.
- Ошибки логируются и в консоли браузера, и в игре (`VFXLog`; частые — через `warnOnce`).

## 8. Безопасность

- Bind только `127.0.0.1`; `SecureRandom` 128-bit токен.
- Проверка `Origin`/`Referer` на `http://127.0.0.1:<port>` / `http://localhost:<port>`.
- Только `GET`/`POST`; SSE-соединения закрываются по 30 с простоя.
- Простой rate limiting на POST (защита от шторма/стороннего локального процесса).
- Сервер не стартует постоянно — по явной команде/кнопке.

## 9. План верификации

1. `./gradlew :26.2:build :26.1.2:build :1.21.11:build` и
   `./gradlew :26.2-neoforge:build :26.1.2-neoforge:build :1.21.11-neoforge:build` — все шесть.
2. `checkSchema` (Gradle-задача + CI): множество имён схемы == множество имён `registerPost` для
   каждого типа; дескриптор на каждый `VFXEffectType`; дефолты схемы == `neutralValue()` где
   нейтраль ≠ `NaN`.
3. Throwaway `main()`: компилятор JSON→`VFXDefinition`, round-trip импорт/экспорт, `pack.mcmeta`
   по ноде.
4. **Компилятор web** — чистая функция IR→VFX-JSON; главный валидатор — Java-парсер (п.3): если
   компилятор выдал невалидный JSON, парсинг падает. Плюс dev-only self-test страница (без Node)
   и фиксированные snapshot-JSON для сложных графов.
5. `javap` для `com.sun.net.httpserver.HttpServer` и loader/API-сигнатур (не угадывать).
6. **Браузеры:** baseline (Chrome/Firefox актуальные + предыдущая), проверка при загрузке UI — если
   `LiteGraph`/`canvas` недоступны, показываем явное сообщение, а не белый экран.
7. Ручной in-game сценарий: открыть пак → правка → live-play → экспорт в мир → `/reload` →
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
- Точные сигнатуры локального play/`registerLocal` (сверить по исходникам перед реализацией).
- Наличие переиспользуемых UI-хелперов (слайдеры/цветовые пикеры) в `src/client/java`.
- Baseline LiteGraph-браузеров и наличие нужных custom widgets.

## 12. Риски

| Риск | Митигация |
|---|---|
| Расхождение web-модели и игры | `revision` + reconcile; web — единственный источник истины |
| Схема разъедется с рендерами | `checkSchema` в CI на каждый тип |
| Сломанный UBO из-за правок `registerPost` | `registerPost` не трогаем; схема параллельна |
| Scrub «щёлкает» / дорогой | elapsed-aware seek + debounce |
| UI в dedicated-server jar | ассеты и код — только в `src/client` |
| Огромный объём v1 | вертикальный срез: только пост-эффекты, без масок/полей/очередей |

## 13. Post-v1: дизайн заложен заранее

Этот раздел — durable-заготовка, чтобы план на следующую версию можно было написать **без доступа
к прошлой переписке**. Ничего из этого не входит в v1; здесь зафиксированы решения, форматы и
точки расширения, от которых уже отталкивается v1-код.

### 13.1 Маски (`mask`)

- **Датапак:** top-level `mask` (парсер `VFXMaskParser`, модель `VFXMask`/`VFXMaskOp`/
  `VFXMaskPrimitive`/`VFXMaskFill`/`VFXMaskField`). Листья — примитивы (`VFXMaskShapeKind`),
  композиция через `op`; `fill` (`solid`/`stroke`/`aura`), `space` (screen/world), `volume`
  (surface/aura), `soft`/`stroke`/`field_amount`/`field_scale`.
- **Резервные параметры:** числовые листья автоматически регистрируются как параметры
  `mask.p<N>.*` (`center_x/y/z`, `rotation`, `p<J>`, `d<J>`, `soft`, `stroke`, `field_amount/scale`).
  **Редактор обязан**: (а) исключать `mask.` из обычного списка параметров эффект-ноды; (б) иметь
  отдельную группу «Mask»; (в) `mask.` — резервное имя, пользовательский параметр с этим префиксом
  затеняется.
- **UI:** нода `Mask` c дочерними `MaskLeaf`-нодами и деревом `op`. Листья подключаются к
  обычным value-нодам по своим `mask.p<N>.*`-сокетам.
- **Схема:** в `VFXEffectDescriptor.structural` добавить `supportsMask` (true только для
  пост-эффектов) и перечень доступных `VFXMaskShapeKind`. UI не хардкодит формы.
- **Ограничения (из кода):** маска только у пост-эффектов; world-overlay её игнорирует; composition
  флаттенит depth-first, индексы `<N>` — в порядке объявления; до 2 custom-листьев.

### 13.2 Per-pixel поля (`field`)

- **Датапак:** только field-capable входы (`VFXEffectType.fieldCapableInputs()`), форма
  `inputs: { "<name>": { "field": ... } | { "op": ... } }`. Модель — `dev.vfxweaver.field`
  (`VFXField`, `VFXFieldFn`, `VFXFieldOp`, `VFXGraphEvaluator`); leaf-функции компилируются в
  uniform-программу (`VFXFieldProgram`, `field.glsl`).
- **Схема уже готова:** флаг `fieldCapable` есть в `VFXParamDescriptor` с v1 — он гейтит
  «field-аффорданс» на сокете.
- **UI:** отдельный набор нод для полей (свой список kinds, **не** путать с value-графом),
  доступный только с field-capable сокета; сериализуется в `field`-объект.
- **Ограничения:** поле глубины/мира валидно только на screen layer 0 (иначе нейтраль, раз в
  определение — warning); caps по глубине/листьям/текстурам — из парсера.

### 13.3 Очереди / коллекции (`collection`)

- **Датапак:** `type: "collection"`, `effects: [{ effect, delay, duration, params, easing }]`;
  вложенность до `MAX_COLLECTION_DEPTH` (4), лимиты `MAX_SCHEDULED_EFFECTS` (128).
- **UI:** нода `Collection` — упорядоченный список дочерних **эффект-нод** с дорожкой `delay`
  (и опциональным `duration`/`easing`/`params` override). Вложенная коллекция — дочерняя нода,
  которая сама является collection-типом.
- **Вкладки:** вкладка остаётся одним определением. Коллекция — это определение; её канвас
  показывает дочерние эффект-ноды. Редактор таймлайна для коллекции — диаграмма задержек.
- **Схема:** `COLLECTION` уже имеет дескриптор без параметров (v1); structural-опция
  `childEffects` гейтит ноду.
- **Компиляция:** IR коллекции → `effects[]`; `params` ребёнка принимают те же формы, что и
  обычные (constant/start-end/keyframes/bind/expr/multiply).

### 13.4 Не-пост типы в UI

Параметры не-пост типов в v1 уже в схеме; в v2 к ним добавляются **structural-опции** в
`VFXEffectDescriptor`, которые UI показывает как свойства/под-редакторы ноды (не сокеты):

| Опция | Поле датапака | Типы |
|---|---|---|
| `positions` | `positions` (`[x,y,z]` или `{entity,point,dir,offset,distance}`), `region` | world overlay, `light_beam`, `pulse_ring`, `guide_line`, `particles`, `block_chain` |
| `entitySelector` | `entity_selector` | `entity_tint/outline/displace` |
| `sound` | `sound`, `sound_pos`, params `volume`/`pitch` | все |
| `particle` / `shape` / `block` / `item` | одноимённые поля | `particles`, `block_chain` |
| `pattern` / `surface` | top-level `pattern`/`surface` | `surface_pattern` |
| `anchor` | top-level `anchor` (`sky_mode` sugar — в params) | `sky_pattern` |
| `window` | `VFXWindowSpec` | `window_create/control` |
| `screenImage` | `VFXScreenImageSpec` | `screen_image` |

Правило: UI генерирует эти под-редакторы **по флагам схемы**, без ветвлений на конкретные типы —
иначе ломается forward-compat.

### 13.5 Subgraph-макросы

- **Датапак:** top-level `subgraphs` (id, inputs с дефолтами, nodes, edges, outputs) + ноды
  `kind: subgraph`; разворачиваются загрузчиком (`VFXSubgraphExpander`) до валидации. Входы
  инстанса подставляются в `"$name"`; id локальны, префиксуются при разворачивании; вложенность
  до 8, до 64 макросов.
- **UI:** «сгруппировать выделение в макрос» — создаёт запись в `subgraphs` вкладки и ноду
  `subgraph`; именованные входы/выходы (`$name`, `outputs` с порядком — первый = дефолтный).

### 13.6 QoL редактора (v2)

Мультивыделение, copy/paste/duplicate блоков, ЛК-меню (delete/duplicate/properties), группы и
комментарии на канвасе (LiteGraph groups), поиск по палитре, выравнивание, сворачивание нод.

### 13.7 Мост и экспорт (v2)

- **Seek:** пока реcompile+play с `elapsedTicks`. Если в VFXweaver появится настоящий
  seek/scrub у активного эффекта — переключить `/transport seek` на него (точка в
  `MinecraftEditorGame.seek`).
- **Транспорт моста:** SSE+POST, пока латентность устраивает; WS только если появится доказанная
  проблема (не реализовывать заранее).
- **Экспорт:** `vfx_curves/*.json` и `vfx_particles/*.json` (эдиторы кривых/пресетов), несколько
  namespace в одном проекте, версионирование/миграция `.vfxproj.json` (`"version"` уже есть).

## 14. Правила для будущих планов (durable conventions)

Чтобы план на v2+ писался по этой спеке без прошлой переписки:

- **Единственный источник схемы** — `VFXEffectSchema`; новый эффект/параметр добавляется туда и
  **обязан** пройти `scripts/check-vfx-schema.ps1`. `registerPost` не переупорядочивать никогда.
- **UI генерируется из схемы**, а не из имён эффектов/параметров; любое `if (type == ...)` в
  web-коде — сигнал, что данных не хватает в дескрипторе: расширяй дескриптор.
- **Формат датапака — только аддитивно**; новые поля добавлять в конец и переживать их отсутствие.
- **Новое структурное поле** → новая опция в `VFXEffectDescriptor.structural`, а не ветка в UI.
- **Web-модель — единственный источник истины**; игра — исполнитель; любое новое действие моста
  обязано нести `revision` и быть идемпотентным.
- **Тесты:** Java-логика — throwaway `main()`/PowerShell-скрипт по образцу `scripts/check-*.ps1`;
  компилятор web — фикстуры `selftest.html`, финальный валидатор — Java-парсер.
- **Определение готовности:** все шесть нод собираются; визуальное — только запуском игры.
- План хранится рядом со спекой: `docs/design/YYYY-MM-DD-*.md`; спека путешествует с планом.

## 15. Redesign v1.1 (по итогам консультации + теста в игре)

Сдвиг: **у редактора появляется своя view-model; JSON-формы — выходной артефакт компилятора.**
Это устраняет «выбор типа параметра», `start/end`, `collection`-в-палитре и дублирование полей.

### 15.1 Слот + фолдинг (инспектор = свёрнутый граф)
- **Слот** = любой вход (параметр эффект-ноды или поле value-ноды). Состояния взаимоисключающие:
  - **inline** — значение редактируется на месте (клик+drag = scrub, двойной клик = ввод). Без попапов.
  - **track** (≥1 ключ) — поле показывает значение на playhead; авто-ключ пишет ключ в текущее время.
  - **driven** (подключена нода) — поле read-only, бейдж источника, «перейти к ноде».
- Поверх любого состояния — опциональный **multiplier** (`×` → выбор bind): формат умеет `multiply`.
- **Ромб-«секундомер»** на слоте → «Анимировать» (создать трек с ключом на playhead). Работает и на value-нодах (п.17).
- **ПКМ по слоту** → «Заменить на выражение» / «Заменить на привязку» = авто-создание `expr`/`bind`-ноды и линка. `bind`/`expr` исчезают из выбора типа.
- Выбор «constant/animate/keyframe» исчезает: 0 ключей = константа, ≥1 = трек; `animate` = 2 ключа.
- «Показать как ноду» — трек материализуется в `curve`-ноду и обратно (мост таймлайн↔канвас).

### 15.2 Вкладка = композиция (эффект-ноды цепочкой)
- Вкладка = одно играбельное определение (файл), но внутри — **N эффект-нод** с портами потока `in`/`out`.
  Для пост-эффектов связь = порядок слоёв (blur→pixelate: pixelate поверх). Крайний левый без входа — источник (экран/мир).
- **Панель «Слои»** справа: порядок применения, drag-reorder, mute/solo, синхронна с канвасом.
- **Пустая вкладка валидна** (плейсхолдер «+ Добавить эффект») — баг «нельзя удалить последний» исчезает как класс.
- `collection` **не** виден как тип (артефакт сериализации).
- **Маппинг:** 1 нода → файл как сегодня (полная совместимость); N нод → корень `type:"collection"`,
  `effects:[{effect,delay,duration,params,easing}]` в порядке цепочки (не сортировать по delay).
- **Открытый вопрос движка:** гарантирован ли сегодня порядок применения пост-эффектов внутри `collection`.
  Если нет — аддитивное поле (`"mode":"chain"`/`"layer":N`) — разрешено (аддитивно). Проверить в игре.

### 15.3 Тайминг (правая панель, контекст по выделению)
- Секции: **Слои** / **Тайминг ноды** (`duration` в тиках или сек @20tps, `loop`, `persistent`, `fade_ticks`=0, `delay`) / **Параметры** (слоты).
- Компилятор **всегда** эмитит тайминг явно (включая дефолты) — пользователь не зависит от скрытых дефолтов датапака. Глобальные дефолты — в настройках проекта.

### 15.4 Таймлайн / кривые
- Линейка с тиками (мажор каждые 5–10, адаптив по зуму), playhead, **duration-bar с ручкой**, loop-скобка.
- Ключи **клампятся в [0, duration]** (альтернатива — «зона-призрак» + предупреждение + «Clamp all»). Ось значений: auto-fit + min/max схемы; выход за диапазон — подсветка/тултип.
- Строки на анимированные слоты + ганты нод `[delay → delay+duration]`.
- **Curve-редактор (безье):** сегмент несёт timing в нормализованном боксе; IR хранит `cubicBezier[x1,y1,x2,y2]`, x∈[0,1], y свободен (overshoot). Ручки — 2 тангента. При отпускании — snap к именованному easing (ε) → сериализуем имя, иначе числа (лосслесный round-trip). Не свободный AE-граф: время монотонно.

### 15.5 Превью / транспорт
- Бар: play/pause, stop, loop-индикатор, таймкод, Replay, auto-replay.
- **Preview-артефакт ≠ авторский датапак:** мост получает ту же ревизию, но с overrides (`loop:true`/большой duration, `fade_ticks:0`) — правки не загрязняют ассет.
- Правка (debounce 200–300мс) → auto-replay с t=0; скраб playhead — локальный пересчёт кривых (без запроса к игре). Статус-бейдж «rev N → в игре».
- Фикс «эффект пропадает» (#4) и «ключи просто затухают» (#15): см. инварианты 15.7.

### 15.6 App-shell / «Файл» / экспорт
- **Top bar:** имя проекта (→ настройки: name, namespace `[a-z0-9_.-]+`, дефолты), табы, транспорт, статус ревизии.
- **Файл:** New Project…, Open…, Recent, Save (Ctrl+S = компиляция+пуш превью), Export…, Settings.
- **Export-диалог:** цели — **ZIP** (браузерный download через мост `GET /export/zip?rev=N`, нативный save ОС, без AWT); **Папка** (нативный пикер через мост `JFileChooser(DIRECTORIES_ONLY)` вне рендер-потока, результат по request-id через SSE/поллинг; фолбэк — ручной ввод пути); **Мир** (список из `saves` + подтверждение перезаписи). Кнопку экспорта из тулбара убрать.
- Хоткеи: `Del`, `Ctrl+Z`/`Ctrl+Shift+Z`, `Ctrl+C/V/D`, `Ctrl+A`, `Ctrl+S`, `Space` play/pause, `R` replay, `K` ключ на playhead, `F` frame-selected, `Home` frame-all, `Tab`/dblclick поиск-добавление, `Ctrl+G` группа, `[`/`]` вкладки, `MMB`/`Space+drag` пан, `Ctrl+wheel` зум, `?` шпаргалка.

### 15.7 Undo и инварианты компилятора
- **Undo — снапшотный** (IR сериализуем): транзакция = `pointerdown→up` (один драг = один шаг) или одно действие; стек с капом 100. Заменяет командный стек.
- **I1:** слот с ≥1 ключом сериализуется только в `keyframes`; fade не эмулирует анимацию.
- **I2:** `fade_ticks` влияет только на затухание после конца жизни.
- **I3:** ключи за `[0,duration]` клампятся при компиляции с warning.
- **E2E-регрессия:** трек 0→1→0 в игре совпадает с кривой.

### 15.8 Маппинг слотов → ParamSpec
| IR | JSON |
|---|---|
| constant-нода → параметр | `"value": X` |
| трек → параметр | `"keyframes": [...]` (стабильно; 2 linear-ключа можно `start/end`) |
| bind-нода → параметр | `"bind": {...}` |
| expr-нода → параметр | `"expr": "..."` |
| прочий граф | `"graph"` + `"inputs":{"param":{"from":id}}` |
| трек на поле value-ноды | graph-нода `kind:"curve"` с ключами |
| multiplier слота | `"multiply": {...}` поверх любого |

Импорт — обратная нормализация: `parseParam` уже нормализует все формы → в IR всё становится слотами/треками.

### 15.9 Порядок внедрения (по консультации)
1. Quick wins: снапшот-undo + хоткеи, тики таймлайна, инлайн-поля без попапа, кламп ключей, пустая вкладка.
2. App-shell: «Файл», проект как объект, Export-диалог (+нативный пикер).
3. Слот-модель: унификация инспектора/графа (фолдинг), ромб-анимация, ПКМ «заменить на expr/bind», multiplier; компилятор — таблица 15.8 + инварианты.
4. Таймлайн/кривые: duration-bar, ганты, curve-редактор.
5. Композиции: N нод/вкладка, потоки, панель слоёв, понижение в `collection` (+аддитивный `layer`/`mode` при необходимости), затем reference-ноды.
6. Vector-группировка параметров (pos_x/y/z → pos и т.п., один ввод/бинд на все, раскрытие) — из фидбека теста.

## 16. Preview v2 (по консультации + фактам движка)

**Принцип:** превью — первосортный сеанс `PreviewSession = (DefinitionSet, ViewState, TimeControl) @ revision`,
а не «взлом» авторского плеера. Авторский набор не трогаем.

### 16.1 Регистрация (diff, приватный ns)
- id → `vfxweaver:editor_preview/<tabSlug>/<nodeUuid>` — датапак-слой не может перекрыть приватный ns.
- `POST /preview/sync` шлёт ВЕСЬ набор вкладки; контроллер diff по string-equality: changed → `registerLocal` (MERGE),
  removed → `unregisterLocal` (tombstone).
- rejected из `registerLocal` → играем last-good + событие `preview/error` (MERGE оставляет старую версию). Без чёрных экранов.
- Учёт: активна одна вкладка (десятки id), кап 256 не давит.

### 16.2 ViewState = инстанс-операции (без перекомпиляции)
- mute → `removeInstance`; solo → видимы только solo; reorder → `setParam(instance,"order",i)` (`order` читается per-frame,
  применится со следующего кадра).
- `order` передаётся **play-параметром** (работает даже для необъявленного параметра) → Exact-определения `order` НЕ содержат.
- Панель слоёв группируется по `screen_layer` (0/1/2): `order` сортирует только внутри слоя (смешение слоёв — warning).

### 16.3 Время (Вариант B — аддитивно)
- Глобальные часы `VFXEffectManager.clock` тикают каждый кадр (`advance`), `VFXReplayController` программной сессии не даёт
  → для pause/seek нужен аддитивный per-instance override на `VFXActiveEffect` (поле `ageOverride`, honour в `update`).
- Контроллер владеет `tick`; spawn анкерит `trigger = now − age`; пауза/скраб = `setAge`. Loop — строго контроллерный
  (`seek(0)` на wrap), НЕ движковый loop (движковый wrap per-instance по duration ребёнка ломает фазовые отношения delay).
- Exact: на каждом def `fade_ticks:0, loop:false, persistent:true, duration=<node>`; **никогда** `beginFadeOut`.
  Authored: обычный `play(root)` с авторскими таймингами (реальный жизненный цикл).

### 16.4 Контроллер-планировщик
- Exact играет детей **поодиночке** через `playReplay(defId, dur, trigger, stableId, contextPos, entities, params:{order:i}, null, false)`;
  корневую коллекцию играть НЕЛЬЗЯ (`ScheduledPlay` фиксирует детей при старте).
- `removeInstance` + `playReplay` в одном тике — позиционно бесшовно.

### 16.5 Seed (phase-stable re-issue)
- `random`/`expr random()` сейчас сеются заново на каждом `play` → на re-issue мигает. Аддитивно: seed от `instanceId`
  (стабильный hash64(tab,nodeUuid)). `noise` — чистая функция elapsed, не мигает.

### 16.6 Протокол моста
- `POST /preview/sync` `{revision, tab, definitions{}, root, meta{tabDuration, children[{key,def,delay,duration,isPost}], context{anchor,pos,targets}}}` → `{ok, applied, rejected}`.
- `POST /preview/viewstate` `{revision, order[], muted[], solo[]}`.
- `POST /preview/param` `{revision, key, param, value}` — fast-path @30Hz; release → sync.
- `POST /preview/transport` `{revision, action: play|pause|seek|stop|setMode, tick, mode: exact|authored}`.
- SSE: `preview/state {revision,mode,playing,tick,ended}`, `preview/error {revision,message,defId?}`.
- single-writer: применения в одной очереди на игровом потоке; viewstate/transport при `rev==lastApplied`; sync идемпотентен по revision.

### 16.7 Инварианты
- I1: ни один preview-def без явного `fade_ticks` (Exact=0); I2: Exact не эмитит `loop:true` и не зовёт `beginFadeOut`; I3: rejected≠∅ → last-good + error.
- Компилятор author-набора всегда пишет `duration/loop/persistent/fade_ticks` явно (движковый дефолт fade=10 не применяется).

### 16.8 Что НЕ делать
- Не играть root-коллекцию в Exact; не полагаться на движковый loop в Exact; не писать `order` в Exact-def (только play-param);
  не звать `beginFadeOut` в превью; не регистрировать под авторскими id; не пачкать author-профиль; не трогать `registerLocal`/`/vfx play`/сортировку движка.

### 16.9 Приоритеты
- **P0:** `/preview/sync` diff-register + PreviewTransport (Вариант B: ageOverride) + spawn/seek/pause/play/stop + контроллерный loop +
  Exact-проекция + инварианты + `/preview/viewstate` (mute/solo/order).
- **P1:** `/preview/param` fast-path + SSE state/error + статус/ended + Authored-режим + UX-дельты (группировка слоёв, бейдж живого bind, скраб=пауза).
- **P2:** disconnect-cleanup, seed=instanceId.
- **P3:** mute/solo в Authored, вложенные коллекции, reference-ноды.
