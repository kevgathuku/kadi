# Multi-Game Architecture Plan

## Goal

Support multiple card games (starting with two) that share infrastructure but have distinct rules, views, and routes. Each game should be independently deployable under its own subdomain in the future.

## Current Dependency Graph

```
core.clj (entry point)
  └── server.clj (middleware)
        └── routes.clj
              └── handlers.clj
                    ├── auth.clj ──── db.clj ──── schema.clj
                    ├── db.clj
                    ├── views.clj ─── cards.clj, game.clj
                    ├── game.clj ─── cards.clj, validation.clj, schema.clj
                    └── cards.clj
```

No circular dependencies. Game logic (`game.clj`, `cards.clj`, `validation.clj`) is pure and isolated.

## Classification: Shared vs Game-Specific

### Shared Infrastructure (game-agnostic)

| Current File | Responsibility |
|---|---|
| `core.clj` | Entry point, boot sequence |
| `server.clj` | Jetty, Ring middleware, session config |
| `auth.clj` | Magic link auth, session helpers |
| `schema.clj` | Base schemas (Email, AuthToken, DbPlayer), transformers |
| `db.clj` | Connection, event sourcing mechanics, player/auth CRUD |
| `views.clj` (partial) | Layout shell, nav, flash, auth pages, games list |
| `game.css` (partial) | Base styles (buttons, layout, nav, forms, flash) |

### Game-Specific (Kadi Poker)

| Current File | Responsibility |
|---|---|
| `cards.clj` | Standard 52-card deck, suit/rank predicates, matching rules |
| `validation.clj` | Kadi play rules (combos, penalties, suit matching) |
| `game.clj` | State shape, `apply-action`, commands, effects, turn logic |
| `handlers.clj` (partial) | Game action handlers (play, draw, accept-penalty, etc.) |
| `views.clj` (partial) | Game play page, scoreboard, action area, suit picker |
| `routes.clj` (partial) | Game action routes (/play, /draw, /select-suit, etc.) |
| `schema.clj` (partial) | Game schemas (Card, Player, Zones, GameStatus, Effects) |
| `game.css` (partial) | Hero card, scoreboard, action area, playing cards |

## Target Namespace Structure

```
src/
  kadi/
    core.clj                    # Entry point (boots all games)

    ;; ── Shared Infrastructure ──
    shared/
      server.clj                # Jetty, middleware, route composition
      auth.clj                  # Magic link auth, sessions
      db.clj                    # Connection, event sourcing, player CRUD
      schema.clj                # Base schemas + transformers
      layout.clj                # HTML shell, nav, flash, auth pages
      game_registry.clj         # Game type registry (see below)

    ;; ── Game: Kadi Poker ──
    poker/
      cards.clj                 # 52-card deck, predicates, matching
      validation.clj            # Play rules, combos, penalties
      game.clj                  # State shape, apply-action, commands
      handlers.clj              # Play/draw/accept-penalty handlers
      views.clj                 # Play page, scoreboard, action area
      routes.clj                # Game-specific routes
      schema.clj                # Card, Player, Zones, Effects schemas

    ;; ── Game: [New Game] ──
    newgame/
      cards.clj                 # Different card types or mechanics
      validation.clj            # Different rules
      game.clj                  # Different state shape + transitions
      handlers.clj              # Game-specific handlers
      views.clj                 # Game-specific UI
      routes.clj                # Game-specific routes
      schema.clj                # Game-specific schemas

resources/
  public/
    css/
      base.css                  # Shared: buttons, layout, nav, forms
      poker.css                 # Kadi Poker styles
      newgame.css               # New game styles
```

## Game Registry

A lightweight registry that maps game types to their modules. No protocols needed — just a map of keywords to namespace functions.

```clojure
;; kadi.shared.game-registry

(def registry
  {:poker {:apply-action   kadi.poker.game/apply-action
           :new-game       kadi.poker.game/new-game
           :normalize      kadi.poker.schema/normalize-game
           :routes         kadi.poker.routes/routes
           :css            "/css/poker.css"}
   :newgame {:apply-action kadi.newgame.game/apply-action
             :new-game     kadi.newgame.game/new-game
             :normalize    kadi.newgame.schema/normalize-game
             :routes       kadi.newgame.routes/routes
             :css          "/css/newgame.css"}})

(defn get-game [game-type]
  (get registry game-type))
```

## Database Changes

Add a `game_type` column to the `games` table so the system knows which game module handles event replay:

```sql
ALTER TABLE games ADD COLUMN game_type TEXT NOT NULL DEFAULT 'poker';
```

Event replay becomes:

```clojure
;; kadi.shared.db

(defn rebuild-state-from-events [game-id game-type]
  (let [apply-fn (:apply-action (registry/get-game game-type))
        events   (get-events game-id)]
    (reduce (fn [state event] (apply-fn state event))
            (:new-game (registry/get-game game-type))
            events)))
```

## Route Composition

Each game registers its routes under a prefix. The server merges them:

```clojure
;; kadi.shared.server

(defn all-routes []
  (concat
    ;; Shared routes (home, auth, games list)
    shared-routes
    ;; Game-specific routes under prefix
    [["/poker" {:middleware []}]
     (prefix-routes "/poker" (kadi.poker.routes/routes))]
    [["/newgame" {:middleware []}]
     (prefix-routes "/newgame" (kadi.newgame.routes/routes))]))
```

Current URLs like `/games/ABC123/play` become `/poker/games/ABC123/play`.

## Subdomain Support (Future)

When subdomain routing is needed, add middleware that inspects the `Host` header and selects the appropriate route set:

```clojure
;; Future middleware (not needed now)

(defn wrap-subdomain-routing [handler]
  (fn [request]
    (let [host (get-in request [:headers "host"])
          game-type (cond
                      (str/starts-with? host "poker.")   :poker
                      (str/starts-with? host "newgame.") :newgame
                      :else                               :poker)]
      (handler (assoc request ::game-type game-type)))))
```

This works because:
- Path-based routing (`/poker/...`) works today with zero DNS config
- Subdomain routing is additive — same route sets, different dispatch
- Both can coexist: subdomain sets default, path overrides
- No game code changes needed — only the dispatch layer changes

### DNS/Deployment Setup (When Ready)

```
poker.kadi.example.com    → same app, subdomain middleware selects :poker routes
newgame.kadi.example.com  → same app, subdomain middleware selects :newgame routes
kadi.example.com          → shared pages (home, auth, game list across all types)
```

Single deployment, single process. Nginx or Caddy handles SSL termination and proxies all subdomains to the same backend.

## Shared Layout Approach

`kadi.shared.layout` provides the HTML shell and takes a CSS path parameter:

```clojure
(defn layout [{:keys [title player flash css]} & body]
  ;; Same as current layout, but css is parameterized
  [:head
    [:link {:rel "stylesheet" :href "/css/base.css"}]
    (when css [:link {:rel "stylesheet" :href css}])]
  ...)
```

Each game's views call layout with their game-specific CSS:

```clojure
;; kadi.poker.views
(defn game-page [...]
  (layout/layout {:title "Poker" :css "/css/poker.css" :player player}
    ...))
```

## Migration Steps

### Phase 1: Extract shared infrastructure (non-breaking)

1. Create `src/kadi/shared/` directory
2. Move `auth.clj` → `shared/auth.clj`
3. Move `server.clj` → `shared/server.clj`
4. Extract layout/nav/auth views from `views.clj` → `shared/layout.clj`
5. Extract base schemas from `schema.clj` → `shared/schema.clj`
6. Split `db.clj`:
   - Generic event sourcing + player CRUD → `shared/db.clj`
   - Game-specific state rebuilding stays with game module
7. Split `game.css` → `base.css` + `poker.css`
8. Update all `require` declarations
9. Run tests — everything should still pass

### Phase 2: Move game code into poker namespace

1. Create `src/kadi/poker/` directory
2. Move `game.clj` → `poker/game.clj`
3. Move `cards.clj` → `poker/cards.clj`
4. Move `validation.clj` → `poker/validation.clj`
5. Extract game handlers from `handlers.clj` → `poker/handlers.clj`
6. Extract game views from `views.clj` → `poker/views.clj`
7. Extract game routes from `routes.clj` → `poker/routes.clj`
8. Extract game schemas from `schema.clj` → `poker/schema.clj`
9. Add `game_type` column to `games` table
10. Update all `require` declarations
11. Run tests — everything should still pass

### Phase 3: Add route prefixes

1. Prefix poker routes under `/poker/...`
2. Add redirect from old `/games/...` → `/poker/games/...` (temporary)
3. Update any hardcoded URLs in views

### Phase 4: Build second game

1. Create `src/kadi/newgame/` with the same file structure as poker
2. Implement game-specific logic
3. Register in game registry
4. Add routes under `/newgame/...`

## What NOT To Do

- **Don't introduce protocols/interfaces** — two concrete namespaces with the same shape is simpler than a shared abstraction. Extract a protocol only if you reach 4+ games.
- **Don't split into separate repos/deployments** — one process serving multiple games is simpler to deploy, share auth sessions, and maintain.
- **Don't over-abstract the DB layer** — each game can have its own helper functions wrapping the shared event sourcing. A game-specific `rebuild-state` is fine.
- **Don't change the event sourcing model** — `apply-action` multimethod per game module works. Events are already game-specific by nature.
