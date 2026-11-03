#!/usr/bin/env python3
"""
Проверка SQL-запросов балансов и итогов на настоящей SQLite.

Берёт запросы прямо из app/src/main/java/app/fintrack/data/db/Sql.kt,
создаёт схему, заполняет случайными операциями и сверяет результат
с независимым расчётом на Python (те же правила, что BalanceCalculator).

Запуск:  python3 tools/verify_sql.py
"""
import random
import re
import sqlite3
import sys
from pathlib import Path

SQL_KT = Path(__file__).resolve().parent.parent / "app/src/main/java/app/fintrack/data/db/Sql.kt"


def load_consts(path):
    src = path.read_text(encoding="utf-8")
    consts = {}
    pattern = re.compile(r"const val (\w+) =\s*(.*?)(?=\n\s*(?:private )?(?:const )?val |\n\s*/\*\*|\n})", re.S)
    for name, body in pattern.findall(src):
        parts = []
        for literal, ident in re.findall(r'"((?:[^"\\]|\\.)*)"|\b([A-Z_]{3,})\b', body):
            if ident:
                parts.append(consts[ident])
            else:
                parts.append(literal)
        consts[name] = "".join(parts)
    return consts


SCHEMA = """
PRAGMA foreign_keys = ON;
CREATE TABLE accounts (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL,
  currencyCode TEXT NOT NULL, initialBalanceMinor INTEGER NOT NULL, color INTEGER NOT NULL,
  sortOrder INTEGER NOT NULL, isArchived INTEGER NOT NULL, createdAt INTEGER NOT NULL);
CREATE TABLE categories (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL,
  parentId TEXT, iconKey TEXT NOT NULL, color INTEGER NOT NULL, sortOrder INTEGER NOT NULL,
  isArchived INTEGER NOT NULL, isSystem INTEGER NOT NULL);
CREATE TABLE transactions (id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL, accountId TEXT NOT NULL,
  toAccountId TEXT, amountMinor INTEGER NOT NULL, toAmountMinor INTEGER, feeMinor INTEGER NOT NULL,
  categoryId TEXT, dateTime INTEGER NOT NULL, payee TEXT NOT NULL, comment TEXT NOT NULL,
  isFavorite INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
  FOREIGN KEY(accountId) REFERENCES accounts(id) ON DELETE RESTRICT,
  FOREIGN KEY(toAccountId) REFERENCES accounts(id) ON DELETE RESTRICT,
  FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE RESTRICT);
CREATE INDEX idx_t_acc ON transactions(accountId);
CREATE INDEX idx_t_to ON transactions(toAccountId);
CREATE INDEX idx_t_cat ON transactions(categoryId);
CREATE INDEX idx_t_date ON transactions(dateTime);
"""


def delta(tx, acc):
    t, a, to, amt, toamt, fee = tx
    d = 0
    if a == acc:
        d += {"INCOME": amt, "EXPENSE": -amt, "TRANSFER": -(amt + fee), "ADJUSTMENT": amt}[t]
    if t == "TRANSFER" and to == acc:
        d += toamt
    return d


def main():
    q = load_consts(SQL_KT)
    db = sqlite3.connect(":memory:")
    db.executescript(SCHEMA)
    rnd = random.Random(7)

    accounts = {"cash": ("BYN", 10_000), "card": ("BYN", 250_000), "usd": ("USD", 50_000), "arch": ("BYN", 0)}
    for i, (aid, (cur, init)) in enumerate(accounts.items()):
        db.execute("INSERT INTO accounts VALUES (?,?,?,?,?,?,?,?,?)",
                   (aid, aid, "CASH", cur, init, 0, i, 1 if aid == "arch" else 0, i))
    db.execute("INSERT INTO categories VALUES ('food','Продукты','EXPENSE',NULL,'g',0,0,0,0)")
    db.execute("INSERT INTO categories VALUES ('veg','Овощи','EXPENSE','food','g',0,0,0,0)")
    db.execute("INSERT INTO categories VALUES ('sal','Зарплата','INCOME',NULL,'s',0,0,0,0)")

    txs = []
    ids = list(accounts)
    for i in range(30_000):
        kind = rnd.choice(["INCOME", "EXPENSE", "TRANSFER", "ADJUSTMENT"])
        a = rnd.choice(ids)
        to, toamt, fee, cat = None, None, 0, None
        amt = rnd.randint(1, 1_000_000)
        if kind == "TRANSFER":
            to = rnd.choice([x for x in ids if x != a])
            toamt = amt if accounts[a][0] == accounts[to][0] else rnd.randint(1, 3_000_000)
            fee = rnd.randint(0, 300)
        elif kind == "ADJUSTMENT":
            amt = rnd.randint(-50_000, 50_000)
        else:
            cat = "sal" if kind == "INCOME" else rnd.choice(["food", "veg"])
        dt = rnd.randint(0, 1_000_000)
        txs.append((f"t{i}", kind, a, to, amt, toamt, fee, cat, dt))
        db.execute("INSERT INTO transactions VALUES (?,?,?,?,?,?,?,?,?,'','',?,?,?)",
                   (f"t{i}", kind, a, to, amt, toamt, fee, cat, dt, i % 7 == 0, i, i))

    def expected_balances(rows):
        return {aid: init + sum(delta(r[1:7], aid) for r in rows) for aid, (_, init) in accounts.items()}

    failures = 0

    def check(label, got, want):
        nonlocal failures
        ok = got == want
        failures += not ok
        print(("OK   " if ok else "FAIL ") + label + ("" if ok else f"\n     got  {got}\n     want {want}"))

    # 1. Балансы всех счетов
    got = {r[0]: r[-1] for r in db.execute(q["ACCOUNTS_WITH_BALANCE"])}
    check("ACCOUNTS_WITH_BALANCE", got, expected_balances(txs))
    order = [r[0] for r in db.execute(q["ACCOUNTS_WITH_BALANCE"])]
    check("архивные счета в конце списка", order[-1], "arch")

    # 2. Баланс одного счёта
    for aid in ids:
        (b,) = db.execute(q["ACCOUNT_BALANCE"], {"accountId": aid}).fetchone()
        check(f"ACCOUNT_BALANCE {aid}", b, expected_balances(txs)[aid])

    # 3. Удаление и редактирование пересчитывают баланс
    db.execute("DELETE FROM transactions WHERE id = 't5'")
    db.execute("UPDATE transactions SET amountMinor = 777, accountId = 'card' WHERE id = 't6' AND type IN ('INCOME','EXPENSE')")
    txs2 = [t for t in txs if t[0] != "t5"]
    txs2 = [(t[0], t[1], "card", t[3], 777, *t[5:]) if t[0] == "t6" and t[1] in ("INCOME", "EXPENSE") else t for t in txs2]
    got = {r[0]: r[-1] for r in db.execute(q["ACCOUNTS_WITH_BALANCE"])}
    check("баланс после удаления/редактирования", got, expected_balances(txs2))

    # 4. Перевод не меняет общий баланс в валюте
    before = sum(v for k, v in got.items() if accounts[k][0] == "BYN")
    db.execute("INSERT INTO transactions VALUES ('tr','TRANSFER','cash','card',5000,5000,0,NULL,5,'','',0,0,0)")
    after = sum(r[-1] for r in db.execute(q["ACCOUNTS_WITH_BALANCE"]) if accounts[r[0]][0] == "BYN")
    check("перевод без комиссии не меняет общий BYN-баланс", after, before)
    db.execute("DELETE FROM transactions WHERE id = 'tr'")

    # 5. Итоги за период
    start, end = 200_000, 600_000
    rows = [t for t in txs2 if start <= t[8] < end]
    want = {}
    for t in rows:
        cur = accounts[t[2]][0]
        inc, exp = want.get(cur, (0, 0))
        if t[1] == "INCOME":
            inc += t[4]
        elif t[1] == "EXPENSE":
            exp += t[4]
        elif t[1] == "TRANSFER":
            exp += t[6]
        want[cur] = (inc, exp)
    got = {r[0]: (r[1], r[2]) for r in db.execute(q["PERIOD_TOTALS"], {"start": start, "end": end})}
    check("PERIOD_TOTALS", got, want)

    # 6. Фильтры истории
    params = dict(start=None, end=None, type=None, accountId=None, categoryId=None, favoritesOnly=0)
    n_all = len(db.execute(q["DETAILS_FILTERED"], params).fetchall())
    check("DETAILS_FILTERED без фильтров", n_all, len(txs2))
    p = dict(params, categoryId="food")
    n_food = len(db.execute(q["DETAILS_FILTERED"], p).fetchall())
    check("фильтр по категории включает подкатегории", n_food,
          sum(1 for t in txs2 if t[7] in ("food", "veg")))
    p = dict(params, accountId="usd", start=start, end=end)
    n = len(db.execute(q["DETAILS_FILTERED"], p).fetchall())
    check("фильтр по счёту (включая входящие переводы) и периоду", n,
          sum(1 for t in txs2 if (t[2] == "usd" or t[3] == "usd") and start <= t[8] < end))
    p = dict(params, type="TRANSFER", favoritesOnly=1)
    n = len(db.execute(q["DETAILS_FILTERED"], p).fetchall())
    fav = {f"t{i}" for i in range(30_000) if i % 7 == 0}
    check("фильтр по типу и избранному", n, sum(1 for t in txs2 if t[1] == "TRANSFER" and t[0] in fav))

    recent = db.execute(q["DETAILS_RECENT"], {"limit": 10}).fetchall()
    check("DETAILS_RECENT отдаёт 10 последних по дате", [r[8] for r in recent],
          sorted((t[8] for t in txs2), reverse=True)[:10])
    one = db.execute(q["DETAILS_BY_ID"], {"id": "t1"}).fetchone()
    check("DETAILS_BY_ID", one[0], "t1")

    # 7. Статистика: строки периода и корневые категории
    rows = db.execute(q["STAT_ENTRIES"], {"start": start, "end": end}).fetchall()
    check("STAT_ENTRIES: число строк периода", len(rows), sum(1 for t in txs2 if start <= t[8] < end))
    roots = {r[10] for r in rows if r[9] == "veg"}
    check("STAT_ENTRIES: подкатегория сворачивается в родителя", roots, {"food"} if roots else set())
    cur_ok = all(r[7] == accounts[r[1]][0] for r in rows)
    check("STAT_ENTRIES: валюта счёта", cur_ok, True)

    # 8. Баланс на момент времени = начальный + операции до момента
    before = 500_000
    want_b = {}
    for aid, (cur, init) in accounts.items():
        want_b[cur] = want_b.get(cur, 0) + init + sum(delta(r[1:7], aid) for r in txs2 if r[8] < before)
    (earliest,) = db.execute(q["EARLIEST_DATE"]).fetchone()
    check("EARLIEST_DATE", earliest, min(t[8] for t in txs2))

    # 9. Миграция 1→2: таблица лимитов, индекс, каскадное удаление с категорией
    m = load_consts(SQL_KT.parent / "Migrations.kt")
    db.execute(m["CREATE_BUDGET_LIMITS"])
    db.execute(m["CREATE_BUDGET_LIMITS_INDEX"])
    db.execute(m["CREATE_BUDGET_LIMITS"])  # IF NOT EXISTS — повторный запуск безопасен
    cols = [(r[1], r[2], r[3], r[5]) for r in db.execute("PRAGMA table_info(budget_limits)")]
    check("миграция: колонки (имя, тип, NOT NULL, PK)", cols, [
        ("id", "TEXT", 1, 1), ("categoryId", "TEXT", 0, 0), ("currencyCode", "TEXT", 1, 0),
        ("amountMinor", "INTEGER", 1, 0), ("rollover", "INTEGER", 1, 0), ("startMonth", "INTEGER", 1, 0),
        ("endMonth", "INTEGER", 0, 0), ("createdAt", "INTEGER", 1, 0)])
    idx = [r[1] for r in db.execute("PRAGMA index_list(budget_limits)") if not r[1].startswith("sqlite_")]
    check("миграция: индекс по categoryId", idx, ["index_budget_limits_categoryId"])
    fk = [(r[2], r[3], r[4], r[6]) for r in db.execute("PRAGMA foreign_key_list(budget_limits)")]
    check("миграция: внешний ключ CASCADE", fk, [("categories", "categoryId", "id", "CASCADE")])
    db.execute("INSERT INTO categories VALUES ('tmp','Временная','EXPENSE',NULL,'g',0,9,0,0)")
    db.execute("INSERT INTO budget_limits VALUES ('b1','tmp','BYN',1000,0,202611,NULL,0)")
    db.execute("INSERT INTO budget_limits VALUES ('b2',NULL,'BYN',5000,1,202611,NULL,0)")
    db.execute("DELETE FROM categories WHERE id = 'tmp'")
    left = [r[0] for r in db.execute("SELECT id FROM budget_limits ORDER BY id")]
    check("миграция: лимит удаляется вместе с категорией, общий остаётся", left, ["b2"])

    # 10. Миграция 2→3: вклады; баланс счёта на момент времени; сумма процентов по префиксу id
    db.execute(m["CREATE_DEPOSITS"])
    cols = [(r[1], r[2], r[3], r[5]) for r in db.execute("PRAGMA table_info(deposits)")]
    check("миграция 2→3: колонки deposits", cols, [
        ("accountId", "TEXT", 1, 1), ("rateBp", "INTEGER", 1, 0), ("startDay", "INTEGER", 1, 0),
        ("termMonths", "INTEGER", 1, 0), ("interestCategoryId", "TEXT", 0, 0), ("postedMonths", "INTEGER", 1, 0),
        ("createdAt", "INTEGER", 1, 0)])
    fk = [(r[2], r[3], r[4], r[6]) for r in db.execute("PRAGMA foreign_key_list(deposits)")]
    check("миграция 2→3: внешний ключ CASCADE на счёт", fk, [("accounts", "accountId", "id", "CASCADE")])
    for aid in ids:
        (b,) = db.execute(q["ACCOUNT_BALANCE_BEFORE"], {"accountId": aid, "before": before}).fetchone()
        want = accounts[aid][1] + sum(delta(r[1:7], aid) for r in txs2 if r[8] < before)
        check(f"ACCOUNT_BALANCE_BEFORE {aid}", b, want)
    db.execute("INSERT INTO transactions VALUES ('dep:card:1','INCOME','card',NULL,1000,NULL,0,'sal',5,'','',0,0,0)")
    db.execute("INSERT INTO transactions VALUES ('dep:card:2','INCOME','card',NULL,1010,NULL,0,'sal',6,'','',0,0,0)")
    (sm,) = db.execute("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE id LIKE :p || '%'", {"p": "dep:card:"}).fetchone()
    check("сумма процентов по префиксу id", sm, 2010)
    try:
        db.execute("INSERT INTO transactions VALUES ('dep:card:1','INCOME','card',NULL,1000,NULL,0,'sal',5,'','',0,0,0)")
        check("повторное начисление того же месяца отклоняется", "вставлено", "ошибка")
    except sqlite3.IntegrityError:
        check("повторное начисление того же месяца отклоняется", "ошибка", "ошибка")
    db.execute("INSERT INTO accounts VALUES ('dep','Вклад','SAVINGS','BYN',100000,0,9,0,0)")
    db.execute("INSERT INTO deposits VALUES ('dep',1200,20000,12,'sal',0,0)")
    db.execute("DELETE FROM accounts WHERE id = 'dep'")
    (left,) = db.execute("SELECT COUNT(*) FROM deposits").fetchone()
    check("вклад удаляется вместе со счётом", left, 0)

    # 11. Миграция 3→4: копилки; число пополнений и сумма последнего
    db.execute(m["CREATE_PIGGY_BANKS"])
    cols = [(r[1], r[2], r[3], r[5]) for r in db.execute("PRAGMA table_info(piggy_banks)")]
    check("миграция 3→4: колонки piggy_banks", cols, [
        ("accountId", "TEXT", 1, 1), ("targetMinor", "INTEGER", 1, 0), ("iconKey", "TEXT", 1, 0),
        ("deadlineDay", "INTEGER", 0, 0), ("createdAt", "INTEGER", 1, 0)])
    fk = [(r[2], r[3], r[4], r[6]) for r in db.execute("PRAGMA foreign_key_list(piggy_banks)")]
    check("миграция 3→4: внешний ключ CASCADE на счёт", fk, [("accounts", "accountId", "id", "CASCADE")])
    db.execute("INSERT INTO accounts VALUES ('pig','Отпуск','PIGGY','BYN',0,0,10,0,0)")
    db.execute("INSERT INTO piggy_banks VALUES ('pig',500000,'beach',NULL,0)")
    db.execute("INSERT INTO transactions VALUES ('p1','TRANSFER','card','pig',5000,5000,0,NULL,100,'','',0,1,1)")
    db.execute("INSERT INTO transactions VALUES ('p2','TRANSFER','card','pig',7000,7000,0,NULL,300,'','',0,2,2)")
    db.execute("INSERT INTO transactions VALUES ('p3','TRANSFER','cash','pig',3000,3000,0,NULL,200,'','',0,3,3)")
    db.execute("INSERT INTO transactions VALUES ('p4','TRANSFER','pig','card',1000,1000,0,NULL,400,'','',0,4,4)")
    # перевод на обычный счёт не считается пополнением копилки
    db.execute("INSERT INTO transactions VALUES ('p5','TRANSFER','cash','card',100,100,0,NULL,500,'','',0,5,5)")
    rows = db.execute(q["PIGGY_TOP_UPS"]).fetchall()
    check("пополнения копилки: число и последнее по дате", rows, [("pig", 3, 7000)])
    (pb,) = db.execute(q["ACCOUNT_BALANCE"], {"accountId": "pig"}).fetchone()
    check("баланс копилки = пополнения − снятия", pb, 14000)
    db.execute("INSERT INTO accounts VALUES ('pig2','Пустая','PIGGY','BYN',0,0,11,0,0)")
    db.execute("INSERT INTO piggy_banks VALUES ('pig2',0,'savings',20500,0)")
    db.execute("DELETE FROM accounts WHERE id = 'pig2'")
    (left,) = db.execute("SELECT COUNT(*) FROM piggy_banks WHERE accountId = 'pig2'").fetchone()
    check("цель копилки удаляется вместе со счётом", left, 0)

    # 12. Миграция 4→5: флаг «не учитывать в общем балансе», существующие счета остаются в балансе
    before_rows = db.execute(q["ACCOUNTS_WITH_BALANCE"]).fetchall()
    db.execute(m["ADD_EXCLUDE_FROM_TOTAL"])
    col = [(r[1], r[2], r[3], r[4]) for r in db.execute("PRAGMA table_info(accounts)") if r[1] == "excludeFromTotal"]
    check("миграция 4→5: колонка excludeFromTotal", col, [("excludeFromTotal", "INTEGER", 1, "0")])
    (n_ex,) = db.execute("SELECT COUNT(*) FROM accounts WHERE excludeFromTotal <> 0").fetchone()
    check("миграция 4→5: старые счета в балансе", n_ex, 0)
    after_rows = db.execute(q["ACCOUNTS_WITH_BALANCE"]).fetchall()
    check("миграция 4→5: балансы не изменились", [r[-1] for r in after_rows], [r[-1] for r in before_rows])

    # 8б. Баланс по валютам на момент времени (колонка excludeFromTotal появилась в 4→5);
    # к этому моменту в базе есть проценты вклада и копилка — ожидание считаем по всем строкам
    def expected_before(moment):
        all_tx = db.execute("SELECT type, accountId, toAccountId, amountMinor, toAmountMinor, feeMinor, dateTime"
                            " FROM transactions").fetchall()
        want = {}
        for aid, cur, init, ex in db.execute("SELECT id, currencyCode, initialBalanceMinor, excludeFromTotal FROM accounts"):
            if not ex:
                want[cur] = want.get(cur, 0) + init + sum(delta(t[:6], aid) for t in all_tx if t[6] < moment)
        return want

    got_b = {r[0]: r[1] for r in db.execute(q["BALANCES_BEFORE"], {"before": before})}
    check("BALANCES_BEFORE по валютам", got_b, expected_before(before))
    got_all = {r[0]: r[1] for r in db.execute(q["BALANCES_BEFORE"], {"before": 10**12})}
    want_all = {}
    for r in db.execute(q["ACCOUNTS_WITH_BALANCE"]):
        want_all[r[3]] = want_all.get(r[3], 0) + r[-1]
    check("BALANCES_BEFORE(∞) = текущие балансы", got_all, want_all)

    # Счёт «не учитывать в общем балансе» не входит в начальную точку графика баланса
    db.execute("UPDATE accounts SET excludeFromTotal = 1 WHERE id = 'card'")
    got_inc = {r[0]: r[1] for r in db.execute(q["BALANCES_BEFORE"], {"before": before})}
    check("BALANCES_BEFORE без счетов вне общего баланса", got_inc, expected_before(before))
    db.execute("UPDATE accounts SET excludeFromTotal = 0 WHERE id = 'card'")

    # 13а. Миграция 5→6: кредиты; выдача и платежи — операции с id «loan:<кредит>:…»
    db.execute(m["CREATE_LOANS"])
    cols = [(r[1], r[2], r[3], r[5]) for r in db.execute("PRAGMA table_info(loans)")]
    check("миграция 5→6: колонки loans", cols, [
        ("accountId", "TEXT", 1, 1), ("kind", "TEXT", 1, 0), ("schedule", "TEXT", 1, 0),
        ("principalMinor", "INTEGER", 1, 0), ("rateBp", "INTEGER", 1, 0), ("startDay", "INTEGER", 1, 0),
        ("termMonths", "INTEGER", 1, 0), ("initialPaid", "INTEGER", 1, 0), ("interestCategoryId", "TEXT", 0, 0),
        ("createdAt", "INTEGER", 1, 0)])
    fk = [(r[2], r[3], r[4], r[6]) for r in db.execute("PRAGMA foreign_key_list(loans)")]
    check("миграция 5→6: внешний ключ CASCADE на счёт", fk, [("accounts", "accountId", "id", "CASCADE")])
    db.execute("INSERT INTO accounts VALUES ('loan1','Кредит','LOAN','BYN',0,0,12,0,0,1)")
    db.execute("INSERT INTO loans VALUES ('loan1','CREDIT','ANNUITY',300000,1500,20000,12,0,'food',0)")
    db.execute("INSERT INTO transactions VALUES ('loan:loan1:0:d','TRANSFER','loan1','card',300000,300000,0,NULL,10,'','',0,10,10)")
    db.execute("INSERT INTO transactions VALUES ('loan:loan1:1:p','TRANSFER','card','loan1',23327,23327,0,NULL,20,'','',0,20,20)")
    db.execute("INSERT INTO transactions VALUES ('loan:loan1:1:i','EXPENSE','card',NULL,3750,NULL,0,'food',20,'','',0,21,21)")
    (lb,) = db.execute(q["ACCOUNT_BALANCE"], {"accountId": "loan1"}).fetchone()
    check("долг = −выдача + погашение тела (проценты на долг не влияют)", lb, -300000 + 23327)
    ids = sorted(r[0] for r in db.execute(q["LOAN_TRANSACTIONS"]))
    check("LOAN_TRANSACTIONS: выдача и платёж", ids, ["loan:loan1:0:d", "loan:loan1:1:i", "loan:loan1:1:p"])
    hist = [r[0] for r in db.execute(q["DETAILS_FOR_LOAN"], {"accountId": "loan1", "idPrefix": "loan:loan1:"})]
    check("DETAILS_FOR_LOAN: проценты со счёта карты тоже в истории", sorted(hist), ids)
    got_b = {r[0]: r[1] for r in db.execute(q["BALANCES_BEFORE"], {"before": 10**12})}
    db.execute("UPDATE accounts SET excludeFromTotal = 0 WHERE id = 'loan1'")
    got_b2 = {r[0]: r[1] for r in db.execute(q["BALANCES_BEFORE"], {"before": 10**12})}
    check("кредит вне общего баланса не влияет на график баланса", got_b2["BYN"] - got_b["BYN"], lb)
    db.execute("UPDATE accounts SET excludeFromTotal = 1 WHERE id = 'loan1'")

    # 13б. Миграция 6→7: регулярные платежи; повторы — обычные операции с id «rec:<шаблон>:<день>»
    db.execute(m["CREATE_RECURRING"])
    for k in ("CREATE_RECURRING_INDEX_ACCOUNT", "CREATE_RECURRING_INDEX_TO_ACCOUNT", "CREATE_RECURRING_INDEX_CATEGORY"):
        db.execute(m[k])
    cols = [r[1] for r in db.execute("PRAGMA table_info(recurring)")]
    check("миграция 6→7: колонки recurring", cols, [
        "id", "type", "accountId", "toAccountId", "amountMinor", "toAmountMinor", "feeMinor", "categoryId",
        "payee", "comment", "unit", "repeatInterval", "startDay", "lastDayOfMonth", "endDay", "maxCount",
        "minuteOfDay", "processedCount", "mode", "isPaused", "createdAt"])
    idx = sorted(r[1] for r in db.execute("PRAGMA index_list(recurring)") if not r[1].startswith("sqlite_"))
    check("миграция 6→7: индексы", idx, ["index_recurring_accountId", "index_recurring_categoryId", "index_recurring_toAccountId"])
    fk = sorted((r[2], r[3], r[6]) for r in db.execute("PRAGMA foreign_key_list(recurring)"))
    check("миграция 6→7: внешние ключи", fk, [
        ("accounts", "accountId", "CASCADE"), ("accounts", "toAccountId", "CASCADE"), ("categories", "categoryId", "SET NULL")])
    db.execute("INSERT INTO accounts VALUES ('rc','Для шаблона','CARD','BYN',0,0,20,0,0,0)")
    db.execute("INSERT INTO categories VALUES ('rcat','Подписки','EXPENSE',NULL,'g',0,20,0,0)")
    db.execute("INSERT INTO recurring VALUES ('r1','EXPENSE','rc',NULL,2500,NULL,0,'rcat','Интернет','','MONTH',1,"
               "20000,0,NULL,NULL,720,0,'CONFIRM',0,0)")
    db.execute("INSERT INTO transactions VALUES ('rec:r1:20000','EXPENSE','rc',NULL,2500,NULL,0,'rcat',1,'','',0,1,1)")
    (rb,) = db.execute(q["ACCOUNT_BALANCE"], {"accountId": "rc"}).fetchone()
    check("проведённый повтор — обычная операция в балансе", rb, -2500)
    try:
        db.execute("INSERT INTO transactions VALUES ('rec:r1:20000','EXPENSE','rc',NULL,2500,NULL,0,'rcat',1,'','',0,1,1)")
        check("повтор той же даты не дублируется", "вставлено", "ошибка")
    except sqlite3.IntegrityError:
        check("повтор той же даты не дублируется", "ошибка", "ошибка")
    db.execute("DELETE FROM recurring WHERE id = 'r1'")
    (left,) = db.execute("SELECT COUNT(*) FROM transactions WHERE id = 'rec:r1:20000'").fetchone()
    check("удаление шаблона не трогает проведённые операции", left, 1)
    db.execute("INSERT INTO accounts VALUES ('rc2','Пустой','CARD','BYN',0,0,21,0,0,0)")
    db.execute("INSERT INTO categories VALUES ('rcat2','Временная','EXPENSE',NULL,'g',0,21,0,0)")
    db.execute("INSERT INTO recurring VALUES ('r2','EXPENSE','rc2',NULL,100,NULL,0,'rcat2','','','DAY',1,"
               "20000,0,NULL,NULL,720,0,'AUTO',0,0)")
    db.execute("DELETE FROM categories WHERE id = 'rcat2'")
    (cat,) = db.execute("SELECT categoryId FROM recurring WHERE id = 'r2'").fetchone()
    check("удалённая категория обнуляется в шаблоне", cat, None)
    db.execute("DELETE FROM accounts WHERE id = 'rc2'")
    (n,) = db.execute("SELECT COUNT(*) FROM recurring WHERE id = 'r2'").fetchone()
    check("шаблон удаляется вместе со счётом без операций", n, 0)

    # 13. Внешний ключ не даёт удалить счёт с операциями
    try:
        db.execute("DELETE FROM accounts WHERE id = 'cash'")
        check("RESTRICT на удаление счёта", "удалён", "ошибка")
    except sqlite3.IntegrityError:
        check("RESTRICT на удаление счёта", "ошибка", "ошибка")

    print("\nИтог:", "все проверки пройдены" if failures == 0 else f"ошибок: {failures}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
