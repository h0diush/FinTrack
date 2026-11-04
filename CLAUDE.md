# FinTrack — контекст для Claude Code

Android-приложение учёта личных финансов по ТЗ пользователя. Без аккаунтов и рекламы; в сеть ходит
только за официальными курсами НБРБ (api.nbrb.by), данные пользователя никуда не уходят.
Подробное описание функций — в `README.md`. Этот файл — как работать с проектом.

## Пользователь

- Пишет по-русски, отвечать по-русски. Интерфейс приложения — только на русском.
- Windows, Android Studio. Проект лежит в `D:\CODE\FinTrack`.
- Смотрит результат на своём телефоне: собирает release-APK и ставит поверх старой версии,
  поэтому данные в базе **должны сохраняться** между версиями (только миграции).
- Для дизайна просит «современно, красиво, с анимацией» (использует скилл ui-ux-pro-max).
- После изменений кратко объяснять, что сделано и как этим пользоваться.

## Стек

Kotlin 2.0.21, AGP 8.13.2, Gradle 8.13 (нужен **JDK 21**, на JDK 25 не запускается —
в Android Studio: Settings → Build Tools → Gradle → Gradle JDK = 21 / встроенный JBR),
compileSdk/targetSdk 35, minSdk 29, Java/JVM target 17.
Jetpack Compose (BOM 2024.12.01, Material 3), Navigation Compose 2.8.5, Room 2.6.1 (KSP),
Hilt 2.52, DataStore 1.1.1, WorkManager 2.9.1, Biometric 1.1.0. Шрифт Inter (`res/font`), иконки Material Icons Rounded (extended).
Версии — в `gradle/libs.versions.toml`. Текущая версия приложения: 0.15.0 (versionCode 17).

## Сборка и проверки (Windows)

```
gradlew.bat assembleRelease          # APK: app\build\outputs\apk\release\app-release.apk
gradlew.bat testDebugUnitTest        # JVM-тесты доменной логики (app/src/test), сейчас 138
gradlew.bat connectedDebugAndroidTest # Room, миграции, копии, файлы экспорта на устройстве (app/src/androidTest), сейчас 30
python tools\verify_sql.py           # SQL из Sql.kt/Migrations.kt на настоящей SQLite
```

Release подписывается debug-ключом (для установки на свой телефон), minify включён.
После любой правки: собрать, прогнать unit-тесты; при изменении SQL или схемы — ещё `verify_sql.py`.
Сборка должна проходить без ошибок до того, как отчитываться пользователю.
Gradle из среды Claude запускать с `--no-daemon "-Pkotlin.compiler.execution.strategy=in-process"`:
оставленные ею демоны не видят ссылки в AppData\Local и ломают сборку в Android Studio.
SDK — `D:\LOCALAPPDATA\Android\Sdk` (в `local.properties`).
Тесты на устройстве запускать с `"-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"` — иначе Gradle удалит
приложение вместе с данными эмулятора. Эмулятор без окна: `emulator -avd Medium_Phone_API_37.0 -no-window -no-audio`.
Изменения базы, файлов и восстановления проверять на устройстве: JVM-тесты этого не покрывают.

## Структура

```
app/src/main/java/app/fintrack/
  domain/model/Models.kt     Account, Category, Transaction, AppSettings… (чистый Kotlin)
  domain/logic/              Money, ExpressionEvaluator, KeypadInput, BalanceCalculator,
                             TransactionValidator, Periods, StatisticsCalculator,
                             BudgetCalculator, Insights, DepositCalculator, Piggy (+ Motivation, ruPlural),
                             Loans (LoanCalculator: аннуитет/дифференцированный график),
                             Recurring (RecurrenceCalculator: даты повторов, описание по-русски),
                             BudgetAlerts (когда слать уведомление о пороге бюджета),
                             Currencies (CurrencyConverter — пересчёт через BYN, NbrbParser),
                             AppLock (PinHasher — PBKDF2, LockPolicy — тайм-аут и пауза после ошибок),
                             DataExport (ExportTable, CsvWriter, XlsxWriter — XLSX без библиотек),
                             StatementImport (CsvReader, StatementParser, ImportDedup, CategoryGuesser),
                             Backup (BackupCrypto — AES-256-GCM по паролю, BackupPayload, PrefsCodec)
  data/db/                   Entities.kt, Daos.kt, Sql.kt (все сложные запросы), Migrations.kt,
                             FinTrackDatabase.kt (version 9), DefaultData.kt (стартовые категории)
  data/repository/           Account, Transaction, Category, Settings, Stats, Budget, Deposit, Piggy, Loan, Recurring, Rates, Security, Export (+ PdfReport), Import, Backup
  di/AppModule.kt            Room + миграции, DataStore
  notify/                    RecurringWorker (WorkManager, ежедневно ~9:00), RecurringNotifier (канал, уведомления),
                             RecurringActionReceiver (кнопки «Провести»/«Пропустить» в уведомлении),
                             BudgetAlertMonitor (следит за бюджетом месяца, запускается в FinTrackApp),
                             BalanceWidget (WidgetUpdater и BalanceWidgetProvider — виджет рабочего стола)
  ui/                        theme, components, navigation, charts,
                             home, editor, history, stats, budget, accounts, piggy, loans, recurring, rates, lock, exchange, categories, settings
```

MVVM: Screen (Compose) → HiltViewModel (StateFlow) → Repository → Room/DataStore.
UI зависит от доменных моделей, не от сущностей БД (мапперы в `data/Mappers.kt`).
`domain/` не должен импортировать Android — он тестируется на обычной JVM.

## Правила учёта денег (не нарушать)

- Суммы — `Long` в минимальных единицах валюты (копейки). Ввод/вывод только через `Money`
  (`parse`, `format`, `toInput`); BigDecimal для вычислений с округлением, никаких Double.
- Баланс счёта **не хранится** — вычисляется: начальный остаток + журнал операций.
  Формула в SQL (`Sql.BALANCE_EXPR`) и в Kotlin (`BalanceCalculator`) должна совпадать;
  `verify_sql.py` и `LedgerDaoTest` это сверяют. Меняешь одно — меняй другое.
- Перевод — **одна строка** с `accountId` и `toAccountId` (комиссия — расход источника).
  Перевод не доход и не расход. Корректировка — операция `ADJUSTMENT` со знаком.
- Запись, зависящая от чтения (проверка остатка, начисление), — внутри `db.withTransaction`.
- Счета и категории с операциями не удаляются (FK RESTRICT) — архивируются.
- Общий баланс: `BalanceCalculator.totalByCurrency` — без архивных и без счетов с
  `excludeFromTotal = true`. График «Баланс» в статистике тоже без таких счетов
  (`Sql.BALANCES_BEFORE` + `excluded` в `balanceSeries`). Доходы/расходы в статистике и бюджет
  учитывают операции всех счетов.
- Вклад: проценты раз в месяц на фактический остаток, id операции `dep:<accountId>:<номер>` —
  начисление идемпотентно, пропущенные месяцы догоняются (`DepositRepository.postDueInterest`,
  вызывается из `HomeViewModel.init`).
- Копилка = счёт с типом `AccountType.PIGGY` + строка в `piggy_banks` (цель, иконка, срок).
  Пополнение/снятие — обычный перевод в той же валюте (`PiggyRepository`).
- Кредит = счёт `AccountType.LOAN` (остаток = −долг, по умолчанию вне общего баланса) + строка в `loans`
  (вид, схема, сумма, ставка, дата выдачи, срок, `initialPaid` — платежи до начала учёта).
  Платёж (`LoanRepository.pay`): перевод «счёт → кредит» на тело долга + расход «Кредиты» на проценты,
  id `loan:<кредит>:<номер>:p|i`; досрочный — номер `x…`, только тело; выдача на счёт — `loan:<кредит>:0:d`.
  Число платежей считается по журналу (удалили операцию — график откатился). Проценты месяца = долг × ставка / 12.
- Регулярный платёж = шаблон в `recurring` (данные операции + правило: единица, интервал, дата начала,
  «последний день месяца», окончание по дате или числу раз, время, режим CONFIRM/AUTO, пауза).
  `processedCount` — сколько первых повторов проведено или пропущено; повторы обрабатываются по порядку.
  Проведённый повтор — обычная операция с id `rec:<шаблон>:<день эпохи>` (дубли невозможны).
  AUTO проводится в `RecurringRepository.processDue` (запуск приложения + ежедневный `RecurringWorker`),
  пропущенные дни догоняются; CONFIRM ждёт на главной/в уведомлении. Изменение шаблона пересчитывает
  правило от даты следующего платежа (счётчик обнуляется). Создаётся из формы операции (строка «Повтор»).
- Курсы валют: `RatesRepository` (DataStore) — курсы НБРБ к BYN (загрузка при запуске и в `RecurringWorker`,
  не чаще раза в 6 ч) и ручные (важнее НБРБ). Любая пара — через BYN (`CurrencyConverter`).
  Общий баланс на главной и в «Счетах» — в `AppSettings.totalCurrency` (чип выбора валюты),
  статистика пересчитывает все операции в выбранную валюту (`convertEntries`) по ТЕКУЩЕМУ курсу.
  Бюджет не пересчитывается (лимит в своей валюте). Валюты без курса не входят в суммы — показывается предупреждение.
- Доход/расход в другой валюте (20 USD с карты в BYN): `amountMinor` — всегда сумма в валюте счёта
  (по курсу или введённая вручную «Списано со счёта»), `originalAmountMinor`/`originalCurrency` — справочно,
  в балансах и статистике не участвуют. Для переводов не используется.
- Регулярный платёж в другой валюте: `recurring.foreignCurrency` задан — `amountMinor` шаблона в этой валюте;
  при каждом проведении `RecurringRepository.post` пересчитывает по текущему курсу (нет курса — `NO_RATE`,
  платёж ждёт подтверждения), «своя сумма» при подтверждении — всегда в валюте счёта.
- Уведомления бюджета: `BudgetAlertMonitor` считает уровни тем же `BudgetOverviewBuilder`, что экран «Бюджет»,
  и шлёт уведомление только при росте уровня (`BudgetAlerts.evaluate`); показанные уровни месяца хранятся
  в DataStore. Первая проверка (новая установка/версия) запоминает состояние молча.

## Виджет

- Обычный `AppWidgetProvider` + `RemoteViews` (`res/layout/widget_balance.xml`), без Glance: новая зависимость не нужна.
  В разметке — только то, что поддерживает RemoteViews.
- `WidgetUpdater` следит за данными, пока жив процесс (запускается в `FinTrackApp`), и рисует по запросу системы
  (`onUpdate`, раз в 2 часа). Баланс считается тем же `BalanceCalculator.totalByCurrency` и в `AppSettings.totalCurrency`.
- Суммы видны всем, кто смотрит на экран: при заданном PIN или «Скрывать суммы» виджет показывает маску. Кнопки остаются.
- Кнопки открывают форму через `RecurringNotifier.EXTRA_OPEN` со значением `add:<тип операции>`; при PIN сначала экран блокировки.

## Экспорт и импорт

- Файлы — только через системные окна (CreateDocument/OpenDocument), разрешений на память нет. Перед их открытием
  вызывать `SecurityRepository.allowExternalScreen()`, иначе при «Блокировать сразу» вернётся экран PIN.
- CSV: `;`, запятая в суммах, BOM, CRLF (под русский Excel), текст с `= + - @` экранируется апострофом.
- XLSX пишется вручную (`XlsxWriter`, inlineStr): суммы и даты — числа. Проверено openpyxl. PDF — `PdfDocument`, всегда светлый.
- Импорт — только доходы и расходы (переводы и корректировки из нашего экспорта пропускаются). Кодировка UTF-8/16
  или windows-1251. Id операции `imp:<sha256>` от счёта, даты, суммы, описания и номера повтора — повторная загрузка
  не задваивает. Строка, похожая на ручную операцию (день + сумма + направление), по умолчанию не загружается.
  Сумма в другой валюте пересчитывается по курсу, исходная сохраняется в `original*`.
- `minSdk 29`: не использовать API новее, даже если компилируется (например `InputStream.readNBytes` — только API 33).

## Резервные копии

- Файл `.ftbackup`: заголовок + AES-256-GCM, ключ из пароля (PBKDF2, итерации в заголовке). Keystore не используется
  намеренно: его ключ не покидает телефон, копию нельзя было бы открыть после потери телефона.
- Внутри ZIP: файл базы целиком (после `wal_checkpoint(TRUNCATE)`), настройки из DataStore, описание. Новые таблицы
  попадают в копию сами. Настройки с префиксами `pin_`, `lock_`, `backup_` — локальные: в копию не идут и при
  восстановлении не меняются; `budget_alert_` — состояние, сбрасывается.
- `BackupRepository.restore` закрывает базу, подменяет файл переименованием (прежний — `*.before-restore`) и перезапускает
  процесс. Старая копия обновляется обычными миграциями, копия новее текущей схемы отклоняется. Поэтому миграции
  важны и для копий: `Migrations.ALL` должен покрывать все версии.
- Схема `3.json` восстановлена вручную из 4.json (настоящий файл был утерян), identityHash в ней условный.

## Блокировка приложения

- PIN из 4 цифр: в DataStore только соль и хеш PBKDF2 (`SecurityRepository`), сам PIN не хранится.
  После 5 ошибок — растущая пауза (`LockPolicy.lockoutMs`). Восстановления нет (только очистка данных).
- `MainActivity` — `FragmentActivity` (нужно для `BiometricPrompt`). Состояние `LockState` живёт в
  `SecurityRepository`: при запуске процесса с PIN — LOCKED; `onStop` запоминает время, `onStart` блокирует
  по тайм-ауту. `LockScreen` рисуется поверх `FinTrackNavHost` (навигация не сбрасывается, семантика скрыта).
- «Скрывать в недавних» — FLAG_SECURE (и запрет скриншотов), отдельная настройка, по умолчанию выключена.

## База данных и миграции

- Схема версии 9: 1→2 budget_limits, 2→3 deposits, 3→4 piggy_banks, 4→5 accounts.excludeFromTotal, 5→6 loans,
  6→7 recurring, 7→8 transactions.originalAmountMinor/originalCurrency, 8→9 recurring.foreignCurrency.
- Новая схема = `version + 1` в `FinTrackDatabase`, миграция в `Migrations.kt` (точный SQL,
  как генерирует Room; SQL — константой, чтобы его проверял `verify_sql.py`), добавить в `Migrations.ALL`,
  тест в `MigrationTest`. Новые NOT NULL колонки — с `@ColumnInfo(defaultValue = ...)`, совпадающим с миграцией.
- `fallbackToDestructiveMigration` **не использовать** — данные пользователя не стираются.
- Схемы экспортируются в `app/schemas/` при сборке (нужны MigrationTest).
- Перечисления хранятся текстом (имя константы): переименовывать константы нельзя.
- Ключи иконок категорий (`CategoryIcons`) хранятся в БД: существующие ключи не менять, только добавлять.

## Интерфейс и дизайн

- Токены: цвета в `ui/theme/Theme.kt` (`FinTheme.colors`: income/expense/transfer, hero-градиент,
  chartIncome #0D9488 / chartExpense #DC3545 — различимы при дальтонизме, статусы),
  анимации в `ui/components/Motion.kt` (`Motion.bouncy/smooth`, `bounceClick`, `popOnChange`,
  `StaggeredAppear`). Анимации Compose учитывают системный масштаб анимаций.
- Текст на цветном фоне: `Color.contentOn()`; акцент как цвет текста: `readableOn(фон)` (контраст ≥ 4.5).
- **Узкие подписи — через `FitText`** (`ui/components/FitText.kt`): уменьшает шрифт, затем переносит
  по слогам; обычный `Text` в узкой ячейке рвёт слово по буквам («Коммунальны / е»).
  `AmountText` уже использует FitText.
- Пара «крупная сумма + подпись» — во `FlowRow`, а не в `Row`, иначе подпись сжимается в столбик.
  В `Row` с несколькими текстами у одного из них должен быть `weight`.
- `AnimatedVisibility` внутри `Box`, вложенного в `Row`/`FlowRow`/`Column`, вызывать по полному имени
  `androidx.compose.animation.AnimatedVisibility(...)` — иначе ошибка DslMarker (RowScope).
- Не называть свои объекты так же, как иконки Material (был конфликт `Insights`).
- Цели касания ≥ 48 dp, у иконок-кнопок — `contentDescription` на русском, без эмодзи в интерфейсе.
- Графики — свои на Canvas (`ui/charts/Charts.kt`): DonutChart, BarChart, LineChart.
- Русские числительные: `ruPlural(n, "день", "дня", "дней")`.

## Навигация

Нижняя панель: Главная · История · [+] · Статистика · Бюджет (`Routes.tabs`).
Счета — с карточки баланса на главной и из настроек; настройки — шестерёнка на главной;
копилки — лента на главной и раздел в «Счетах» (`Routes.piggy(id)`, `Routes.piggyEdit()`);
кредиты — блок ближайших платежей на главной, раздел в «Счетах», пункт в настройках
(`Routes.LOANS`, `Routes.loan(id)`, `Routes.loanEdit(id, kind)`);
регулярные платежи — блок на главной, пункт в настройках, уведомление (`Routes.RECURRING`,
`Routes.recurringEdit(id)`, `Routes.recurringNew()` — форма операции в режиме шаблона);
резервная копия, экспорт и импорт — раздел «Данные» в настройках (`Routes.BACKUP`, `Routes.EXPORT`, `Routes.IMPORT`);
виджет добавляется из настроек («Виджет на рабочий стол», `WidgetUpdater.requestPin`) или меню рабочего стола.

## Что сделано

0.1–0.2 — MVP: счета, операции, переводы, корректировки, история с фильтрами, категории, настройки.
Затем редизайн, этап 3 (статистика с графиками, бюджеты с порогами, переносом остатка, прогнозом),
сворачиваемая клавиатура в редакторе, 160 иконок категорий,
0.5 — вклад с ежемесячной капитализацией, 0.6 — копилки с мотивационными фразами,
0.7 — «не учитывать счёт в общем балансе», исправление разрывов слов.
0.8 — кредиты/рассрочки/ипотека с графиком платежей; график баланса без счетов «вне баланса».
0.9 — регулярные платежи: шаблоны, повторы, подтверждение или автопроведение, уведомления (WorkManager).
0.9.1 — уведомления о порогах бюджета (50/80/100 %, переключатель в настройках).
0.10 — курсы валют (НБРБ + ручные), итог баланса в выбранной валюте, статистика с пересчётом.
0.11 — доход/расход в другой валюте с пересчётом в валюту счёта; окно лимита бюджета над клавиатурой.
0.12 — блокировка PIN-кодом и отпечатком, тайм-аут, защита от подбора, скрытие в недавних.
0.12.1 — регулярные платежи в другой валюте (пересчёт по курсу на день платежа).
0.13 — экспорт в CSV, Excel и PDF, импорт выписок из CSV.
0.14 — зашифрованные резервные копии и восстановление.
0.15 — виджет на рабочий стол, полировка экранов, значки строки состояния по теме приложения.

## Дальше по плану (обсуждать с пользователем перед началом)

- Этап 4 (остаток): автопополнение копилок (по сути есть: регулярный перевод в копилку).
- Резервные копии: автоматическое создание по расписанию (пароль можно хранить под ключом Keystore).
- Предлагалось: налог на проценты по вкладу, ежемесячное пополнение вклада.
