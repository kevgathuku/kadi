<!-- graft:start -->
## Graft — repo context graph

This repo is indexed in `graft/`: small linked markdown nodes that explain each
system and carry exact file:line spans, kept in sync with the code through git.

For ANY task here — understanding how something works, finding where code lives,
or scoping a change — get context from the graph before grepping or opening
source files. Re-ask freely (it's cheap) and reuse literal identifiers you
already have (symbol, error string, file name) as the query. New to this repo?
Run `graft map` first — a token-budgeted orientation (dir clusters, hubs,
hotspots), no LLM, no key.

- Run `graft ask "<your question>" --source` → ranked nodes with the relevant
  code spans inlined (each hit's ≤8-line crux by default; `--full` for whole
  definitions when the crux isn't enough). Match the tool to the task shape:
  for understanding or editing, the top node IS the answer — cite its
  `covers:` file:line spans and edit straight from `--source`. For
  exhaustive tasks ("every occurrence / every caller of this pattern"), ranked
  results are top-N, not complete — run `graft grep "<literal>"` instead
  (exhaustive over indexed files, grouped by enclosing symbol), falling back
  to raw `grep -rn` only for unindexed files.
- `graft skeleton <file>` → every definition's signature + span, ~10× cheaper
  than reading the file; use it to skim an API surface.
- `graft callers <symbol>` gives precomputed, exact edges — who calls this.
  Add `--direction out` for what it calls, or `--depth N` to walk
  transitively for the full blast radius. For structural questions, skip
  ranking and use this directly.
- Or browse: `graft/INDEX.md` lists every node; follow the links.
- Monorepos and folders of multiple repos rank fairly across sub-projects —
  hits carry `[scope/]` labels naming which one they're from. Narrow with
  `graft ask "<task>" --in <scope>/` once you know where you're working.

If a returned span is truncated ("+N more lines"), open the file at that exact
range before finalizing. Only open source files when a node genuinely lacks a
needed detail, and then at the exact file:line the node points to — never
re-read whole files.

After big code changes, refresh the graph with `graft build` (deterministic,
no API key, $0).
<!-- graft:end -->

# Agent Guidelines

This file provides guidance when working with code in this repository.

## Project Overview

Kadi is a multiplayer online card game built with Clojure, focused on implementing "Poker" (a card game popular in Kenya, also known as "Kadi"). This is a rewrite from a previous Elixir/Phoenix implementation - see `docs/CLOJURE_BOOTSTRAP_BRIEF.md` for the complete game specification and lessons learned.

### Core Architecture

```
Game state is a pure value (immutable map).
State transitions are pure functions: (state, action) -> state
Side effects (persistence, broadcasting) happen at the edges.
```

## Development Commands

### Setup

```bash
clj -P                    # Download dependencies
clj -M:dev -m kadi.db     # Initialize database (creates kadi.db)
```

### Running the Application

```bash
clj -M:run                # Start server at localhost:3000
clj -M:repl               # Start REPL with nREPL for editor connection
```

### Testing

```bash
clj -M:test               # Run all tests with Kaocha
clj -M:test --focus :unit # Run specific test suite
```

### REPL Development

```clojure
;; In REPL
(require '[kadi.core :as core])
(require '[kadi.game :as game])
(require '[kadi.db :as db])
(require '[kadi.auth :as auth])

;; Initialize DB
(db/init!)

;; Create and manipulate game state (pure functions)
(def g (game/new-game {:id 1 :short-code "TEST"}))
(def g (game/add-player g {:id 1 :name "Alice"}))
(def g (game/add-player g {:id 2 :name "Bob"}))
(def g (game/start-game g {}))

;; Apply actions
(game/apply-action g {:type :play-cards :player-id 1 :cards [...]})

;; Auth flow (in dev mode, prints link to console)
(def token (auth/create-signin-token! "test@example.com"))
(auth/send-signin-email! {:email "test@example.com" :token token})
;; Click link or call: (auth/verify-token! token)
```

### Clojure REPL Evaluation

The command `clj-nrepl-eval` is installed on your path for evaluating Clojure code via nREPL.

**Discover nREPL servers:**

`clj-nrepl-eval --discover-ports`

**Evaluate code:**

`clj-nrepl-eval -p <port> "<clojure-code>"`

With timeout (milliseconds)

`clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"`

The REPL session persists between evaluations - namespaces and state are maintained.
Always use `:reload` when requiring namespaces to pick up changes.

# Clojure Parenthesis Repair

The command `clj-paren-repair` is installed on your path.

Examples:
`clj-paren-repair <files>`
`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

**IMPORTANT:** Do NOT try to manually repair parenthesis errors.
If you encounter unbalanced delimiters, run `clj-paren-repair` on the file
instead of attempting to fix them yourself. If the tool doesn't work,
report to the user that they need to fix the delimiter error manually.

The tool automatically formats files with cljfmt when it processes them.

## Architecture

### Pure Game Logic (No Side Effects)

**`kadi.game`** - Core game state and transitions:
- `new-game`, `add-player`, `start-game`
- `apply-action` multimethod for all state transitions
- All functions are pure: `(state, action) -> state`

**`kadi.cards`** - Card representation and utilities:
- Card predicates: `ace?`, `king?`, `jack?`, `question-card?`, `penalty-card?`
- Matching: `matches-suit?`, `matches-rank?`, `matches?`
- Deck creation and shuffling

**`kadi.validation`** - Play validation (pure):
- `validate-play` returns `{:valid? bool :reason string}`
- All game rules encoded here

### Side Effects (At The Edges)

**`kadi.db`** - SQLite persistence:
- Game CRUD operations
- Event sourcing with `append-event!` and `get-events`
- Player management
- Auth token management for sign-in

**`kadi.auth`** - Username-or-email authentication:
- Sign in with email or username; magic link always goes to email
- Token generation and validation
- Session helpers
- No passwords - magic link sign-in only

**`kadi.views`** - Server-rendered HTML with Hiccup:
- All pages rendered on server
- HTMX for partial updates without full page reloads
- Layout with flash messages and session state

**`kadi.server`** / **`kadi.handlers`** - HTTP handlers:
- Ring + Reitit for routing
- Session cookies for authentication
- HTML responses (not JSON API)

## Database

SQLite with INTEGER primary keys (not UUIDs). Database file: `kadi.db`

**Tables:**
- `games` - state (JSON), state_sequence (links to last event)
- `players` - name (username, unique), email (no password - magic link auth only)
- `auth_tokens` - email magic link tokens (expires_at, used flag)
- `game_players` - authorization (who can access which game)
- `game_events` - event sourcing (sequence_number, event_type, event_data)

```bash
# View database
sqlite3 kadi.db ".tables"
sqlite3 kadi.db "SELECT id, short_code, state_sequence FROM games"
sqlite3 kadi.db "SELECT json_extract(state, '$.status') FROM games"
```

## Key Design Decisions

### From Elixir Lessons Learned

1. **Pure state transitions** - Unlike Elixir version where state changes were scattered across Ecto changesets, all transitions go through `apply-action`

2. **Event sourcing built-in** - `game_events` table stores all actions; `state_sequence` tracks which event the current state was derived from

3. **Single source of truth** - No duplicate columns; status lives only in state JSON, queried via `json_extract()`

4. **SQLite for simplicity** - Single file, embedded, zero config

5. **INTEGER IDs** - Simpler than UUIDs, SQLite INTEGER is already 64-bit

### Game Rules Quick Reference

| Rank | Match Rule | Combo | Effect | Can Start |
|------|------------|-------|--------|-----------|
| 2 | Suit/Rank | Yes | Draw 2 penalty | No |
| 3 | Suit/Rank | Yes | Draw 3 penalty | No |
| 4-7,9,10 | Suit/Rank | Yes | None | Yes |
| 8 | Suit/Rank | Q,8 | Question | Yes |
| J | Suit/Rank | J | Skip N | No |
| Q | Suit/Rank | Q,8 | Question | Yes |
| K | Suit/Rank | No | Reverse | Yes |
| A | Always | A | Suit select | Yes |

See `docs/CLOJURE_BOOTSTRAP_BRIEF.md` for complete rules.

## Testing Strategy

Tests are pure - no database setup required:

```clojure
(deftest play-king-reverses-direction
  (let [game (make-test-game)
        result (game/apply-action game {:type :play-cards
                                        :player-id 1
                                        :cards [king]})]
    (is (= :counter-clockwise (:direction result)))))
```

## Development Guidelines

### Code Changes

- Default to TDD when fixing bugs — write a failing test first, then fix
- Game logic changes go in `kadi.game` or `kadi.validation`
- Keep side effects in `kadi.db` and `kadi.handlers`
- Run tests after changes: `clj -M:test`
- All state transitions must go through `apply-action`

### Adding New Card Effects

1. Add predicate to `kadi.cards` if needed
2. Add validation rule to `kadi.validation`
3. Add effect handling to `apply-card-effects` in `kadi.game`
4. Add tests in `test/kadi/game_test.clj`

## Previous Implementation

The Elixir/Phoenix implementation is preserved at:
- Tag: `v1.0-elixir`
- Branch: `archive/elixir-implementation`

```bash
# View old implementation
git show v1.0-elixir:lib/kadi/games/play_validator.ex
```

## Agent skills

### Issue tracker

Issues live in GitHub Issues via `gh`. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at the repo root (ADRs created lazily; none yet). See `docs/agents/domain.md`.
