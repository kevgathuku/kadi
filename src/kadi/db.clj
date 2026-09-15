(ns kadi.db
  "SQLite database operations."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [jsonista.core :as json]
            [clojure.string :as str]
            [kadi.cards :as cards]
            [kadi.game :as game]
            [kadi.schema :as schema]))

(def ^:dynamic *db-spec* {:dbtype "sqlite" :dbname (or (System/getenv "DATABASE_PATH") "kadi.db")})

(defn datasource
  "Get or create a datasource for the current *db-spec*.
   Always reads *db-spec* so dynamic binding works correctly."
  []
  (jdbc/get-datasource *db-spec*))

(def ^:private json-mapper (json/object-mapper {:decode-key-fn keyword}))

(defn ->json [data]
  (json/write-value-as-string data json-mapper))

(defn <-json [s]
  (when s
    (json/read-value s json-mapper)))

;; =============================================================================
;; Schema
;; =============================================================================

(def schema
  "CREATE TABLE IF NOT EXISTS games (
     id INTEGER PRIMARY KEY,
     short_code TEXT UNIQUE NOT NULL,
     state TEXT NOT NULL,
     state_sequence INTEGER NOT NULL DEFAULT 0,
     created_at TEXT NOT NULL DEFAULT (datetime('now')),
     updated_at TEXT NOT NULL DEFAULT (datetime('now'))
   );

   CREATE TABLE IF NOT EXISTS players (
     id INTEGER PRIMARY KEY,
     name TEXT NOT NULL,
     email TEXT UNIQUE NOT NULL,
     created_at TEXT NOT NULL DEFAULT (datetime('now'))
   );

   CREATE TABLE IF NOT EXISTS auth_tokens (
     id INTEGER PRIMARY KEY,
     email TEXT NOT NULL,
     token TEXT NOT NULL UNIQUE,
     expires_at TEXT NOT NULL,
     used INTEGER NOT NULL DEFAULT 0,
     created_at TEXT NOT NULL DEFAULT (datetime('now'))
   );

   CREATE TABLE IF NOT EXISTS game_players (
     id INTEGER PRIMARY KEY,
     game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
     player_id INTEGER NOT NULL REFERENCES players(id),
     joined_at TEXT NOT NULL DEFAULT (datetime('now')),
     UNIQUE(game_id, player_id)
   );

   CREATE TABLE IF NOT EXISTS game_events (
     id INTEGER PRIMARY KEY,
     game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
     sequence_number INTEGER NOT NULL,
     event_id TEXT NOT NULL UNIQUE,
     event_type TEXT NOT NULL,
     event_data TEXT NOT NULL,
     timestamp TEXT NOT NULL,
     created_at TEXT NOT NULL DEFAULT (datetime('now')),
     UNIQUE(game_id, sequence_number)
   );

  CREATE INDEX IF NOT EXISTS idx_games_short_code ON games(short_code);
  CREATE INDEX IF NOT EXISTS idx_games_status ON games(json_extract(state, '$.status'));
  CREATE INDEX IF NOT EXISTS idx_games_status_meta ON games(json_extract(state, '$.meta.status'));
   CREATE INDEX IF NOT EXISTS idx_game_events_game_id ON game_events(game_id);
   CREATE INDEX IF NOT EXISTS idx_game_events_event_id ON game_events(event_id);
   CREATE INDEX IF NOT EXISTS idx_game_players_game_id ON game_players(game_id);
   CREATE INDEX IF NOT EXISTS idx_auth_tokens_token ON auth_tokens(token);
   CREATE INDEX IF NOT EXISTS idx_auth_tokens_email ON auth_tokens(email);
   CREATE UNIQUE INDEX IF NOT EXISTS idx_players_name_unique ON players(lower(name));")

(defn- ensure-unique-player-names!
  "Rename duplicate player names (case-insensitive) with a numeric suffix
   so the unique index on lower(name) can be created on legacy DBs."
  [ds]
  (try
    (let [rows (jdbc/execute! ds ["SELECT id, name FROM players"]
                              {:builder-fn rs/as-unqualified-lower-maps})
          seen (atom #{})]
      (doseq [{:keys [id name]} (sort-by :id rows)]
        (let [lower (str/lower-case (or name ""))]
          (if (contains? @seen lower)
            (loop [n 2]
              (let [candidate (str name n)
                    candidate-lower (str/lower-case candidate)]
                (if (contains? @seen candidate-lower)
                  (recur (inc n))
                  (do (jdbc/execute-one! ds ["UPDATE players SET name = ? WHERE id = ?" candidate id])
                      (swap! seen conj candidate-lower)))))
            (swap! seen conj lower)))))
    (catch Exception _ nil)))

(defn backfill-start-game-zones!
  "Persist the cached deal into legacy :start-game events that carry no
   deal. Only exact cases are backfilled: the start event must be the
   game's latest event and the cache must be live at that sequence, so
   the cached zones ARE the post-start deal. Games with later plays keep
   a faithful cache while fresh; their original deal is unrecoverable.
   Idempotent: events already carrying a deck or zones are skipped."
  []
  (let [ds (datasource)
        starts (jdbc/execute! ds
                              ["SELECT id, game_id, sequence_number, event_data FROM game_events WHERE event_type = 'start-game'"]
                              {:builder-fn rs/as-unqualified-lower-maps})]
    (doseq [{:keys [id game_id sequence_number event_data]} starts
            :let [data (<-json event_data)]
            :when (and (nil? (:deck data)) (nil? (:zones data)))]
      (let [row (jdbc/execute-one! ds
                                   ["SELECT state, state_sequence FROM games WHERE id = ?" game_id]
                                   {:builder-fn rs/as-unqualified-lower-maps})
            latest (:seq (jdbc/execute-one! ds
                                            ["SELECT MAX(sequence_number) as seq FROM game_events WHERE game_id = ?" game_id]
                                            {:builder-fn rs/as-unqualified-lower-maps}))
            state (when (:state row) (schema/normalize-game (<-json (:state row))))]
        ;; Sound only when nothing happened after the start: latest event
        ;; is this start, and the live cache tracks exactly it.
        (when (and (= sequence_number latest)
                   (= (:state_sequence row) latest)
                   (= :live (game/game-status state))
                   (seq (get-in state [:zones :played-stack])))
          (jdbc/execute-one! ds
                             ["UPDATE game_events SET event_data = ? WHERE id = ?"
                              (->json (assoc data :zones (get state :zones)))
                              id]))))))

(defn init!
  "Initialize the database with schema and pragmas."
  []
  (let [db-file (:dbname *db-spec*)
        parent (.getParentFile (java.io.File. db-file))]
    (when (and parent (not (.exists parent)))
      (.mkdirs parent)))
  (let [ds (datasource)]
    ;; Enable foreign keys and WAL mode
    (jdbc/execute! ds ["PRAGMA foreign_keys=ON"])
    (jdbc/execute! ds ["PRAGMA journal_mode=WAL"])
    ;; Create tables first, dedup legacy names, then indexes
    ;; (unique index on lower(name) fails on legacy duplicate names otherwise).
    (let [stmts (->> (str/split schema #";")
                     (map str/trim)
                     (remove str/blank?))
          {:keys [tables indexes]} (group-by #(if (re-find #"(?i)^CREATE\s+(UNIQUE\s+)?INDEX" %)
                                                :indexes :tables) stmts)]
      (doseq [stmt tables]
        (jdbc/execute! ds [stmt]))
      (ensure-unique-player-names! ds)
      (doseq [stmt indexes]
        (jdbc/execute! ds [stmt]))
      ;; Pin down legacy :start-game deals so event replay stays faithful.
      (backfill-start-game-zones!))))

;; =============================================================================
;; Event Sourcing
;; =============================================================================

(defn get-events
  "Get all events for a game."
  [game-id]
  (->> (jdbc/execute! (datasource)
                      ["SELECT * FROM game_events WHERE game_id = ? ORDER BY sequence_number" game-id]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (map #(update % :event_data <-json))))

(defn get-events-after
  "Get events after a given sequence number."
  [game-id after-sequence]
  (->> (jdbc/execute! (datasource)
                      ["SELECT * FROM game_events WHERE game_id = ? AND sequence_number > ? ORDER BY sequence_number"
                       game-id after-sequence]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (map #(update % :event_data <-json))))

(defn rebuild-state-from-events
  "Rebuild game state by replaying all events from the event log.
  This allows verification of state correctness and recovery from corruption.
  Starts with nil since the first event (:game-created) creates the initial state."
  [game-id]
  (let [events (get-events game-id)]
    (reduce (fn [state {:keys [event_type event_data]}]
              (game/apply-action state (merge event_data {:type (keyword event_type)})))
            nil
            events)))

(defn- with-write-txn
  "Run f with a single SQLite connection inside BEGIN IMMEDIATE.
   IMMEDIATE reserves the write lock up front so concurrent writers
   queue instead of hitting upgrade deadlocks; busy_timeout makes them
   wait rather than fail. f receives the connection; commit on success,
   rollback on error."
  [ds f]
  (with-open [conn (jdbc/get-connection ds)]
    (jdbc/execute! conn ["PRAGMA busy_timeout = 5000"])
    (jdbc/execute! conn ["PRAGMA foreign_keys = ON"])
    (jdbc/execute! conn ["BEGIN IMMEDIATE"])
    (try
      (let [result (f conn)]
        (jdbc/execute! conn ["COMMIT"])
        result)
      (catch Exception e
        (try (jdbc/execute! conn ["ROLLBACK"]) (catch Exception _ nil))
        (throw e)))))

(defn- fresh-state-tx
  "Compute current state for game-id inside a write txn: use the cache
   when it tracks the latest event, else rebuild from the log."
  [tx game-id]
  (let [row (jdbc/execute-one! tx
                               ["SELECT state, state_sequence FROM games WHERE id = ?" game-id]
                               {:builder-fn rs/as-unqualified-lower-maps})
        latest (or (:seq (jdbc/execute-one! tx
                                            ["SELECT MAX(sequence_number) as seq FROM game_events WHERE game_id = ?" game-id]
                                            {:builder-fn rs/as-unqualified-lower-maps}))
                   0)]
    (if (and (:state row) (= (:state_sequence row) latest))
      (schema/normalize-game (<-json (:state row)))
      (reduce (fn [state {:keys [event_type event_data]}]
                (game/apply-action state (merge event_data {:type (keyword event_type)})))
              nil
              (->> (jdbc/execute! tx
                                  ["SELECT * FROM game_events WHERE game_id = ? ORDER BY sequence_number" game-id]
                                  {:builder-fn rs/as-unqualified-lower-maps})
                   (map #(update % :event_data <-json)))))))

(defn append-event!
  "Append an event to a game's event log and advance the cached state,
   atomically. Stale readers may also refresh the cache, but only
   forward (compare-and-set on state_sequence), so readers never
   regress the cache.
   If event_id already exists, returns the existing event without
   touching the cache (idempotent).
   When expected-seq is given, the append is rejected with an
   ex-info (:reason :stale-state) unless the log still ends at
   expected-seq — optimistic concurrency so a command validated
   against stale state cannot silently apply to newer state."
  ([game-id event-id event-type timestamp event-data]
   (append-event! game-id event-id event-type timestamp event-data nil))
  ([game-id event-id event-type timestamp event-data expected-seq]
   (when (nil? game-id)
     (throw (Exception. "game-id cannot be nil in append-event!")))
   (let [ds (datasource)]
     (with-write-txn ds
       (fn [tx]
         (let [existing (jdbc/execute-one! tx
                                           ["SELECT sequence_number, event_type, event_data FROM game_events WHERE event_id = ?"
                                            event-id]
                                           {:builder-fn rs/as-unqualified-lower-maps})]
           (if existing
            ;; Event already exists, return it (idempotent)
             (assoc existing :event_data (<-json (:event_data existing)))
            ;; New event: compute base state first (cache tracks next-seq - 1
            ;; at this point), then insert, apply, and write the cache.
            ;; A :start-game event must carry its deal: shuffling here and
            ;; storing the deck makes every later replay deterministic.
            ;; Legacy callers send only {:timestamp}; enrich those.
            (let [event-data (if (and (= :start-game event-type) (nil? (:deck event-data)))
                               (let [deck (cards/make-deck)]
                                 (assoc event-data
                                        :deck deck
                                        :starting-card (cards/select-starting-card deck)
                                        :cards-per-player (or (:cards-per-player event-data) 4)))
                               event-data)
                  next-seq (or (:seq (jdbc/execute-one! tx
                                                         ["SELECT COALESCE(MAX(sequence_number), 0) + 1 as seq FROM game_events WHERE game_id = ?" game-id]
                                                         {:builder-fn rs/as-unqualified-lower-maps}))
                                1)
                  ;; Optimistic concurrency: the caller validated its command
                  ;; against expected-seq. Inside the write lock the log still
                  ;; has to end there, else a concurrent writer got in first.
                   _ (when (and (some? expected-seq) (not= (dec next-seq) expected-seq))
                       (throw (ex-info "Stale game state: event log advanced since validation"
                                       {:reason :stale-state
                                        :expected expected-seq
                                        :actual (dec next-seq)})))
                   base-state (fresh-state-tx tx game-id)
                   _ (jdbc/execute-one! tx
                                        ["INSERT INTO game_events (game_id, sequence_number, event_id, event_type, event_data, timestamp) VALUES (?, ?, ?, ?, ?, ?)"
                                         game-id next-seq event-id (name event-type) (->json event-data) (str timestamp)]
                                        {:return-keys true
                                         :builder-fn rs/as-unqualified-lower-maps})
                   new-state (game/apply-action base-state (merge event-data {:type event-type}))
                   normalized (schema/normalize-game new-state)]
               (jdbc/execute-one! tx
                                  ["UPDATE games SET state = ?, state_sequence = ?, updated_at = datetime('now') WHERE id = ?"
                                   (->json normalized) next-seq game-id])
               {:sequence_number next-seq :event_type event-type :event_data event-data}))))))))

;; =============================================================================
;; Game Operations
;; =============================================================================

(defn- get-latest-event-seq
  "Get the latest event sequence number for a game."
  [game-id]
  (or (:seq (jdbc/execute-one! (datasource)
                               ["SELECT MAX(sequence_number) as seq FROM game_events WHERE game_id = ?" game-id]
                               {:builder-fn rs/as-unqualified-lower-maps}))
      0))

(defn update-game-cache!
  "Update the denormalized game state cache, but never backward.
   Compare-and-set on state_sequence: a snapshot for an older sequence
   can never overwrite a newer one (same-sequence rewrites, e.g. state
   corrections, are still allowed)."
  [game-id game-state state-sequence]
  (jdbc/execute-one! (datasource)
                     ["UPDATE games SET state = ?, state_sequence = ?, updated_at = datetime('now') WHERE id = ? AND state_sequence <= ?"
                      (->json game-state)
                      state-sequence
                      game-id
                      state-sequence]))

(defn- ensure-fresh-state
  "Check if game state is stale and rebuild from snapshot + subsequent events if needed.
   Uses the cached state as a snapshot and only replays events after state_sequence.
   Returns game with fresh state (normalized)."
  [game]
  (when game
    (let [latest-seq (get-latest-event-seq (:id game))
          cached-seq (:state_sequence game)]
      (if (or (nil? cached-seq) (< cached-seq latest-seq))
        ;; State is stale, rebuild from snapshot + subsequent events
        (let [snapshot-state (if (and (:state game) (pos? cached-seq))
                               (schema/normalize-game (:state game))  ; Normalize before replay
                               nil)           ; No snapshot, start from scratch
              subsequent-events (get-events-after (:id game) (or cached-seq 0))
              fresh-state (reduce (fn [state {:keys [event_type event_data]}]
                                    (game/apply-action state (merge event_data {:type (keyword event_type)})))
                                  snapshot-state
                                  subsequent-events)
              normalized-state (schema/normalize-game fresh-state)]
          (update-game-cache! (:id game) normalized-state latest-seq)
          ;; A newer writer may have committed while we rebuilt; our CAS
          ;; write is then a no-op. Re-read and prefer the newest snapshot
          ;; so we never hand a caller state older than the cache.
          (let [current (jdbc/execute-one! (datasource)
                                           ["SELECT state, state_sequence FROM games WHERE id = ?" (:id game)]
                                           {:builder-fn rs/as-unqualified-lower-maps})
                current-seq (:state_sequence current)]
            (if (and current-seq (> current-seq latest-seq))
              (assoc game :state (schema/normalize-game (<-json (:state current)))
                     :state_sequence current-seq)
              (assoc game :state normalized-state :state_sequence latest-seq))))
        ;; State is fresh
        game))))

(defn get-game-by-code
  "Get a game by short code with fresh state."
  [short-code]
  (when-let [row (jdbc/execute-one! (datasource)
                                    ["SELECT * FROM games WHERE short_code = ?" short-code]
                                    {:builder-fn rs/as-unqualified-lower-maps})]
    (-> row
        (update :state <-json)
        ensure-fresh-state
        schema/normalize-game-row)))

(defn list-games
  "List all games, optionally filtered by status.
   Ensures fresh state via event replay so newly-created games are included."
  ([] (list-games nil))
  ([status]
   (let [query ["SELECT * FROM games ORDER BY created_at DESC"]
         games (->> (jdbc/execute! (datasource) query {:builder-fn rs/as-unqualified-lower-maps})
                    (map #(update % :state <-json))
                    (map ensure-fresh-state)
                    (map schema/normalize-game-row))]
     (if status
       (filter #(= status (get-in % [:state :status])) games)
       games))))

(defn create-game!
  "Create a new game from an action. Persists event and game_player in a transaction,
   then computes state by applying the event. Returns {:id :short-code}.
   Database UNIQUE constraint on short_code prevents collisions."
  [action]
  (let [player-id (get-in action [:player :id])
        event-id (or (:event-id action) (str (java.util.UUID/randomUUID)))
        timestamp (or (:timestamp action) (java.time.Instant/now))
        short-code (or (:short-code action) (game/generate-short-code))
        action-with-code (assoc action :short-code short-code)
        {:keys [game-id]}
        (jdbc/with-transaction [tx (datasource)]
          (let [game-result (jdbc/execute-one! tx
                                               ["INSERT INTO games (short_code, state, state_sequence) VALUES (?, ?, 0)"
                                                short-code "{}"]
                                               {:return-keys true
                                                :builder-fn rs/as-unqualified-lower-maps})
                new-game-id (or (:id game-result) (get game-result (keyword "last_insert_rowid()")))
                next-seq 1]
            ;; Append event with event_id and timestamp
            (jdbc/execute-one! tx
                               ["INSERT INTO game_events (game_id, sequence_number, event_id, event_type, event_data, timestamp) VALUES (?, ?, ?, ?, ?, ?)"
                                new-game-id next-seq event-id "game-created" (->json action-with-code) (str timestamp)])
            ;; Add player to game for authorization
            (jdbc/execute-one! tx
                               ["INSERT OR IGNORE INTO game_players (game_id, player_id) VALUES (?, ?)"
                                new-game-id player-id])
            ;; Write the initial cache from the same event (sole-writer
            ;; invariant: cache always tracks the last event)
            (let [initial (-> (game/apply-action nil (assoc action-with-code :type :game-created))
                              schema/normalize-game)]
              (jdbc/execute-one! tx
                                 ["UPDATE games SET state = ?, state_sequence = 1 WHERE id = ?"
                                  (->json initial) new-game-id]))
            {:game-id new-game-id}))]
    {:id game-id :short-code short-code}))

;; =============================================================================
;; Player Operations
;; =============================================================================

(defn get-player
  "Get a player by ID."
  [player-id]
  (jdbc/execute-one! (datasource)
                     ["SELECT * FROM players WHERE id = ?" player-id]
                     {:builder-fn rs/as-unqualified-lower-maps}))
(defn create-player!
  "Create a new player and return the full record."
  [{:keys [name email]}]
  (let [result (jdbc/execute-one! (datasource)
                                  ["INSERT INTO players (name, email) VALUES (?, ?)"
                                   name email]
                                  {:return-keys true
                                   :builder-fn rs/as-unqualified-lower-maps})
        player-id (or (:id result) (get result (keyword "last_insert_rowid()")))]
    (get-player player-id)))

(defn get-player-by-email
  "Get a player by email."
  [email]
  (jdbc/execute-one! (datasource)
                     ["SELECT * FROM players WHERE email = ?" email]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn get-player-by-username
  "Get a player by username (players.name), case-insensitive."
  [username]
  (jdbc/execute-one! (datasource)
                     ["SELECT * FROM players WHERE lower(name) = lower(?)" username]
                     {:builder-fn rs/as-unqualified-lower-maps}))

;; =============================================================================
;; Auth Token Operations
;; =============================================================================

(defn create-auth-token!
  "Create a new auth token for email sign-in."
  [{:keys [email token expires-at]}]
  (jdbc/execute-one! (datasource)
                     ["INSERT INTO auth_tokens (email, token, expires_at) VALUES (?, ?, ?)"
                      email token expires-at]
                     {:return-keys true
                      :builder-fn rs/as-unqualified-lower-maps}))

(defn get-auth-token
  "Get an auth token by token string."
  [token]
  (jdbc/execute-one! (datasource)
                     ["SELECT * FROM auth_tokens WHERE token = ?" token]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn mark-token-used!
  "Mark an auth token as used."
  [token]
  (jdbc/execute-one! (datasource)
                     ["UPDATE auth_tokens SET used = 1 WHERE token = ?" token]))

(defn delete-expired-tokens!
  "Clean up expired tokens."
  []
  (jdbc/execute-one! (datasource)
                     ["DELETE FROM auth_tokens WHERE expires_at < datetime('now')"]))

;; =============================================================================
;; Game Players (for authorization - who can access which game)
;; =============================================================================

(defn add-player-to-game!
  "Add a player to a game (for authorization tracking)."
  [game-id player-id]
  (jdbc/execute-one! (datasource)
                     ["INSERT OR IGNORE INTO game_players (game_id, player_id) VALUES (?, ?)"
                      game-id player-id]
                     {:return-keys true
                      :builder-fn rs/as-unqualified-lower-maps}))

(defn get-game-player-ids
  "Get all player IDs for a game."
  [game-id]
  (->> (jdbc/execute! (datasource)
                      ["SELECT player_id FROM game_players WHERE game_id = ?" game-id]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (map :player_id)))

(defn player-in-game?
  "Check if a player is in a game."
  [game-id player-id]
  (some? (jdbc/execute-one! (datasource)
                            ["SELECT 1 FROM game_players WHERE game_id = ? AND player_id = ?" game-id player-id]
                            {:builder-fn rs/as-unqualified-lower-maps})))

(defn get-player-games
  "Get all games a player is in, with fresh state."
  [player-id]
  (->> (jdbc/execute! (datasource)
                      ["SELECT g.* FROM games g
                        JOIN game_players gp ON g.id = gp.game_id
                        WHERE gp.player_id = ?
                        ORDER BY g.updated_at DESC" player-id]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (map #(update % :state <-json))
       (map ensure-fresh-state)
       (map schema/normalize-game-row)))

(comment
  (rebuild-state-from-events 120))
