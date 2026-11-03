# FinTrack — контекст для Claude Code

Android-приложение учёта личных финансов по ТЗ пользователя. Офлайн, без аккаунтов и рекламы.
Подробное описание функций — в `README.md`. Этот файл — как работать с проектом.

## Пользователь

- Пишет по-русски, отвечать по-русски. Интерфейс приложения — только на русском.
- Windows, Android Studio. Проект лежит в `D:\CODE\FinTrack`.
- Смотрит результат на своём телефоне: собирает release-APK и ставит поверх старой версии,
  поэтому данные в базе **должны сохраняться** между версиями (только миграции).
- Для дизайна просит «современно, красиво, с анимацией» (использует скилл ui-ux-pro-max).
- После изменений кратко объяснять, что сделано и как этим пользоваться.

## Стек

Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1 (нужен **JDK 21**, на JDK 25 не запускается —
в Android Studio: Settings → Build Tools → Gradle → Gradle JDK = 21 / встроенный JBR),
compileSdk/targetSdk 35, minSdk 29, Java/JVM target 17.
Jetpack Compose (BOM 2024.12.01, Material 3), Navigation Compose 2.8.5, Room 2.6.1 (KSP),
Hilt 2.52, DataStore 1.1.1. Шрифт Inter (`res/font`), иконки Material Icons Rounded (extended).
Версии — в `gradle/libs.versions.toml`. Текущая версия приложения: 0.8.0 (versionCode 8).

## Сборка и проверки (Windows)

```
gradlew.bat assembleRelease          # APK: app\build\outputs\apk\release\app-release.apk
gradlew.bat testDebugUnitTest        # JVM-тесты доменной логики (app/src/test), сейчас 83
gradlew.bat connectedDebugAndroidTest # Room и миграции на телефоне/эмуляторе (app/src/androidTest)
python tools\verify_sql.py           # SQL из Sql.kt/Migrations.kt на настоящей SQLite
```

Release подписывается debug-ключом (для установки на свой телефон), minify включён.
После любой правки: собрать, прогнать unit-тесты; при изменении SQL или схемы — ещё `verify_sql.py`.
Сборка должна проходить без ошибок до того, как отчитываться пользователю.

## Структура

```
app/src/main/java/app/fintrack/
  domain/model/Models.kt     Account, Category, Transaction, AppSettings… (чистый Kotlin)
  domain/logic/              Money, ExpressionEvaluator, KeypadInput, BalanceCalculator,
                             TransactionValidator, Periods, StatisticsCalculator,
                             BudgetCalculator, Insights, DepositCalculator, Piggy (+ Motivation, ruPlural),
                             Loans (LoanCalculator: аннуитет/дифференцированный график)
  data/db/                   Entities.kt, Daos.kt, Sql.kt (все сложные запросы), Migrations.kt,
                             FinTrackDatabase.kt (version 6), DefaultData.kt (стартовые категории)
  data/repository/           Account, Transaction, Category, Settings, Stats, Budget, Deposit, Piggy, Loan
  di/AppModule.kt            Room + миграции, DataStore
  ui/                        theme, components, navigation, charts,
                             home, editor, history, stats, budget, accounts, piggy, loans, categories, settings
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

## База данных и миграции

- Схема версии 6: 1→2 budget_limits, 2→3 deposits, 3→4 piggy_banks, 4→5 accounts.excludeFromTotal, 5→6 loans.
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
(`Routes.LOANS`, `Routes.loan(id)`, `Routes.loanEdit(id, kind)`).

## Что сделано

0.1–0.2 — MVP: счета, операции, переводы, корректировки, история с фильтрами, категории, настройки.
Затем редизайн, этап 3 (статистика с графиками, бюджеты с порогами, переносом остатка, прогнозом),
сворачиваемая клавиатура в редакторе, 160 иконок категорий,
0.5 — вклад с ежемесячной капитализацией, 0.6 — копилки с мотивационными фразами,
0.7 — «не учитывать счёт в общем балансе», исправление разрывов слов.
0.8 — кредиты/рассрочки/ипотека с графиком платежей; график баланса без счетов «вне баланса».

## Дальше по плану (обсуждать с пользователем перед началом)

- Этап 4: регулярные платежи (WorkManager, режим подтверждения), автопополнение копилок,
  курсы валют и пересчёт в основную валюту, уведомления (пороги бюджета).
- Этап 5: шифрованные резервные копии (Android Keystore), экспорт CSV/XLSX/PDF, импорт CSV, PIN/биометрия.
- Этап 6: виджет, полировка.
- Предлагалось: налог на проценты по вкладу, ежемесячное пополнение вклада.
