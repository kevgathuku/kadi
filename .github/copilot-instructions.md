# GitHub Copilot Instructions for Kadi

**Kadi** is a multiplayer card game ("Poker"/Kadi from Kenya) built with **Clojure 1.12**, using pure functional programming with SQLite persistence.

## Repository Overview

- **~1,800 LOC** (10 src files, 2 test files) | **Clojure 1.12** on **Java 17+**
- **Stack**: Ring + Reitit (HTTP), Hiccup (HTML), Sente (WebSockets), SQLite
- **Testing**: Kaocha | **DB**: `kadi.db` (SQLite, no migrations)
- **Architecture**: Pure functional core (`kadi.game`, `kadi.cards`, `kadi.validation`) + side effects at edges (`kadi.db`, `kadi.handlers`)

## Setup & Build

### Prerequisites
**Java 17+**, **Clojure CLI 1.11+** ([install](https://clojure.org/guides/install_clojure))

### Initial Setup (run in order)
```bash
clojure -P                        # Download deps (~30-60s first run)
clojure -M:dev -m kadi.db         # Initialize kadi.db (idempotent schema)
clojure -M:test                   # Run tests (~2-5s)
```

### Common Commands
```bash
# Run server (http://localhost:3000, ~3-5s startup)
clojure -M:run

# REPL (primary dev workflow)
clojure -M:repl                   # nREPL server for editor
clojure -M:dev-repl               # Auto-starts server + REPL

# Test
clojure -M:test                   # All tests (pure, no DB setup)
clojure -M:test --focus :unit     # Specific suite
```

### REPL Development (Preferred Workflow)
```clojure
;; Pure game logic (no DB)
(require '[kadi.game :as game] '[kadi.cards :as cards])
(def g (-> (game/new-game {})
           (game/add-player {:id 1 :name "Alice"})
           (game/start-game {})))
(game/apply-action g {:type :play-cards :player-id 1 :cards [...]})

;; DB operations
(require '[kadi.db :as db])
(db/init!)  ; Create tables if not exist

;; Server control
(require '[kadi.server :as server])
(server/start! {:port 3000})
(server/stop!)
```

## Project Structure

```
deps.edn           # Dependencies, aliases (:dev :test :run :repl)
tests.edn          # Kaocha config
dev/user.clj       # REPL utilities (start-server, stop-server)
docs/CLOJURE_BOOTSTRAP_BRIEF.md  # Complete game spec
src/kadi/
  core.clj         # -main entry
  game.clj         # Pure state transitions (300 LOC)
  cards.clj        # Card predicates (100 LOC)
  validation.clj   # Play validation (160 LOC)
  db.clj           # SQLite persistence (200 LOC)
  auth.clj         # Magic link auth (100 LOC)
  server.clj       # Ring/Jetty (50 LOC)
  routes.clj       # Reitit routes (150 LOC)
  handlers.clj     # HTTP handlers (200 LOC)
  views.clj        # Hiccup HTML (270 LOC)
test/kadi/
  game_test.clj    # Pure tests (200 LOC)
  routes_auth_test.clj  # HTTP tests (40 LOC)
```

### Key Files
- **deps.edn**: Aliases (`:dev`, `:test`, `:run`, `:repl`, `:dev-repl`)
- **src/kadi/db.clj**: Schema defined inline (no migrations), runs `CREATE TABLE IF NOT EXISTS`
- **docs/CLOJURE_BOOTSTRAP_BRIEF.md**: Complete game rules and architecture decisions

## Architecture

**Pure Core (No Side Effects)**:
- `kadi.game`: State transitions via `apply-action` multimethod (`:play-cards`, `:draw-card`, `:select-suit`)
- `kadi.cards`: Predicates (`ace?`, `king?`, `jack?`, `question-card?`, `penalty-card?`)
- `kadi.validation`: `validate-play` → `{:valid? bool :reason string}`

**Effects at Edges**:
- `kadi.db`: SQLite CRUD, event sourcing (`append-event!`, `get-events`)
- `kadi.handlers`: Read DB → apply pure functions → persist
- `kadi.auth`: Token generation, email (stubbed in dev)

### Database (SQLite, INTEGER PKs)
**Tables**: `games` (state JSON), `players` (email auth), `auth_tokens` (magic links), `game_players` (join table), `game_events` (event sourcing)

**Inspect**: `sqlite3 kadi.db ".tables"` or `SELECT json_extract(state, '$.status') FROM games;`

### Game Rules (See docs/CLOJURE_BOOTSTRAP_BRIEF.md)
| Rank | Effect | Combo | Can Start |
|------|--------|-------|-----------|
| A | Suit selection, always playable | Yes | Yes |
| 2/3 | Penalty (draw 2/3), block with same/Ace | Yes | No |
| K | Reverse direction | No | Yes |
| J | Skip N players | Yes (Jacks only) | No |
| Q/8 | Question (requires answer) | Yes (mix Q+8) | Yes |
| 4-7,9,10 | Regular | Yes | Yes |

**Critical**: Aces ignore all matching rules. Penalties can't cross-block (2 ≠ 3). Questions need non-question answer.

## Workflows

### Development
1. Start REPL: `clojure -M:repl`
2. Connect editor (nREPL port printed)
3. Eval code, reload namespaces: `(require '[kadi.game] :reload)`
4. Test in REPL: `(clojure.test/run-tests 'kadi.game-test)`
5. Run full suite: `clojure -M:test`

### Adding Card Effects
1. Add predicate to `kadi.cards` (e.g., `special-card?`)
2. Add rule to `kadi.validation/validate-play`
3. Add effect to `kadi.game/apply-card-effects`
4. Write pure tests in `test/kadi/game_test.clj`

### Testing Pattern (Pure, No DB)
```clojure
(deftest my-test
  (let [g (-> (game/new-game {})
              (game/add-player {:id 1 :name "Alice"})
              (game/start-game {}))]
    (is (= :live (:status g)))
    (is (= 4 (count (get-in g [:players 0 :hand]))))))
```

## CI/CD

**GitHub Actions** (`.github/workflows/clojure.yml`):
- Triggers: Push to `main`, all PRs
- Steps: Java 21 setup → Clojure CLI → Cache deps → `clojure -M:test`
- Duration: ~1-2 min (with cache)

**Replicate locally**: `clojure -M:test`

## Common Issues & Solutions

### Dependencies
- **"Could not find artifact"**: Run `clojure -P` to download deps
- **Network errors from Clojars/Maven**: Some networks block repos. Use VPN or Maven mirror
- **Stale deps after editing deps.edn**: `rm -rf ~/.clojure/.cpcache && clojure -P`

### REPL
- **Hangs on startup**: Port 3000 or nREPL port already in use
- **"No such namespace"**: Use `(require '[namespace] :reload)` after code changes

### Database
- **"database is locked"**: Another process has `kadi.db` open (SQLite uses WAL mode)
- **Schema not updated**: Delete `kadi.db` and run `clojure -M:dev -m kadi.db` (destructive, dev only)

### Tests
- **Failures**: Tests are pure - debug in REPL: `(require '[kadi.game-test] :reload)` and inspect

## Best Practices

- **Code Style**: Use descriptive names, keep functions small, prefer pure functions
- **DRY**: Search before adding: `grep -rn "defn function-name" src/`
- **Testing**: Tests are pure and fast - no mocking needed
- **Changes**: Make minimal changes → test (`clojure -M:test`) → REPL verify → commit

## Validation Checklist

Before merging:
1. ✅ `clojure -M:test` passes
2. ✅ CI passes on GitHub
3. ✅ Tested in REPL if new features
4. ✅ `kadi.db` in `.gitignore` (never commit DB)

## Resources

- **docs/CLOJURE_BOOTSTRAP_BRIEF.md**: Complete game spec
- **README.md**: Quick start, API examples
- **AGENTS.md**: AI assistant guidelines
- **Clojure**: https://clojure.org/reference/documentation

**Trust these instructions** - validated against actual code. Only search if incomplete or encountering undocumented errors.
