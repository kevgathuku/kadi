(ns kadi.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [kadi.db :as db]
            [kadi.game :as game]
            [kadi.cards :as cards]
            [kadi.schema :as schema]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; Use a test database file that gets cleaned up
(def test-db-file "test-kadi.db")

;; SET THE DEFAULT DB TO TEST DB AT NAMESPACE LOAD TIME
;; This ensures that even during namespace compilation/loading,
;; we use the test database instead of the main database
(alter-var-root #'db/*db-spec* (constantly {:dbtype "sqlite" :dbname test-db-file}))

;; Initialize the test database schema
(db/init!)

(defn with-test-db [f]
  ;; Re-assert our DB: alter-var-root at load time loses to whichever
  ;; test namespace loads last, so bind per-test instead of per-load.
  (alter-var-root #'db/*db-spec* (constantly {:dbtype "sqlite" :dbname test-db-file}))
  ;; Delete test db if it exists
  (let [file (java.io.File. test-db-file)]
    (when (.exists file)
      (.delete file)))

  ;; Re-initialize test database for this specific test
  (db/init!)

  (try
    (f)
    (finally
      ;; Clean up test db file
      (let [file (java.io.File. test-db-file)]
        (when (.exists file)
          (.delete file))))))

(use-fixtures :each with-test-db)

(deftest rebuild-state-from-events-test
  (testing "Rebuilding state from game-created event"
    (let [action {:player {:id 1 :name "Alice"}}
          result (db/create-game! action)
          game-id (:id result)]

      (testing "should create initial game state"
        (let [state (db/rebuild-state-from-events game-id)]
          (is (some? state) "State should not be nil")
          (is (= :lobby (:status state)) "Game should be in lobby status")
          (is (= 1 (count (:players state))) "Should have 1 player")
          (is (= "Alice" (-> state :players first :name)) "Player name should be Alice")
          (is (some? (:short-code state)) "Should have a short-code")))))

  (testing "Rebuilding state with multiple events")
  (let [action {:player {:id 1 :name "Alice"}}
        result (db/create-game! action)
        game-id (:id result)]

      ;; Join another player
    (db/append-event! game-id
                      (str (java.util.UUID/randomUUID))
                      :join-game
                      (java.time.Instant/now)
                      {:player {:id 2 :name "Bob"}
                       :timestamp (java.time.Instant/now)})

    (let [state (db/rebuild-state-from-events game-id)]
      (is (= 2 (count (:players state))) "Should have 2 players")
      (is (= "Bob" (-> state :players second :name)) "Second player should be Bob"))

      ;; Start the game
    (db/append-event! game-id
                      (str (java.util.UUID/randomUUID))
                      :start-game
                      (java.time.Instant/now)
                      {:timestamp (java.time.Instant/now)})

    (let [state (db/rebuild-state-from-events game-id)]
      (is (= :live (:status state)) "Game should be live after start-game event")
      (is (seq (get-in state [:zones :deck])) "Deck should have cards")
      (is (seq (get-in state [:zones :played-stack])) "Played stack should have starting card")
      (is (= 4 (count (game/get-hand state 1))) "Player 1 should have 4 cards")
      (is (= 4 (count (game/get-hand state 2))) "Player 2 should have 4 cards")
        ;; Validate actual cards in hands
      (let [p1-hand (game/get-hand state 1)
            p2-hand (game/get-hand state 2)]
        (is (every? map? p1-hand) "Player 1 hand should contain card maps")
        (is (every? #(and (:suit %) (:rank %)) p1-hand) "Player 1 cards should have suit and rank")
        (is (every? map? p2-hand) "Player 2 hand should contain card maps")
        (is (every? #(and (:suit %) (:rank %)) p2-hand) "Player 2 cards should have suit and rank")
        (is (= 4 (count p1-hand)) "Player 1 should have exactly 4 cards")
        (is (= 4 (count p2-hand)) "Player 2 should have exactly 4 cards")))))

(deftest ensure-fresh-state-test
  (testing "When state is stale (state_sequence < latest event)"
    (let [action {:player {:id 1 :name "Alice"}}
          result (db/create-game! action)
          game-id (:id result)
          short-code (:short-code result)]

      ;; Add an event after game creation
      (db/append-event! game-id
                        (str (java.util.UUID/randomUUID))
                        :join-game
                        (java.time.Instant/now)
                        {:player {:id 2 :name "Bob"}
                         :timestamp (java.time.Instant/now)})

      ;; Get game - should detect stale state and rebuild
      (let [game (db/get-game-by-code short-code)]
        (is (= 2 (count (get-in game [:state :players])))
            "Should have 2 players after ensuring fresh state")
        (is (= 2 (:state_sequence game))
            "State sequence should be 2 (game-created + join-game)"))))

  (testing "When state is fresh (state_sequence matches latest event)"
    (let [action {:player {:id 1 :name "Alice"}}
          result (db/create-game! action)
          short-code (:short-code result)
          ;; Fetch twice without adding events; neither fetch rebuilds.
          game1 (db/get-game-by-code short-code)
          initial-seq (:state_sequence game1)
          game2 (db/get-game-by-code short-code)]
      (is (= 1 initial-seq) "Should have sequence 1")
      ;; Second fetch - should not rebuild since state is fresh
      (is (= initial-seq (:state_sequence game2))
          "State sequence should not change when fresh")
      (is (= :lobby (get-in game2 [:state :status]))
          "Status should be lobby (keyword after normalization)")
      (is (= (count (get-in game1 [:state :players]))
             (count (get-in game2 [:state :players])))
          "Player count should be identical when fresh")))

  (testing "When state_sequence is 0 (initial state, no events applied)"
    (let [action {:player {:id 1 :name "Alice"}}
          result (db/create-game! action)
          game-id (:id result)
          short-code (:short-code result)]

      ;; Manually set state_sequence to 0 (simulating stale state)
      (jdbc/execute-one! (db/datasource)
                         ["UPDATE games SET state_sequence = 0 WHERE id = ?" game-id])

      ;; Get game - should rebuild from events since sequence is behind
      (let [game (db/get-game-by-code short-code)]
        (is (some? (:state_sequence game)) "Should have rebuilt state_sequence")
        (is (= 1 (:state_sequence game)) "Should match event count")
        (is (= :lobby (get-in game [:state :status]))
            "Should have lobby status (keyword after normalization)"))))

  (testing "When game is nil"
    (let [result (#'db/ensure-fresh-state nil)]
      (is (nil? result) "Should return nil when input is nil")))

  (testing "After multiple events, state should reflect all changes"
    (let [action {:player {:id 1 :name "Alice"}}
          result (db/create-game! action)
          game-id (:id result)
          short-code (:short-code result)]

      ;; Add multiple events
      (db/append-event! game-id
                        (str (java.util.UUID/randomUUID))
                        :join-game
                        (java.time.Instant/now)
                        {:player {:id 2 :name "Bob"}
                         :timestamp (java.time.Instant/now)})
      (db/append-event! game-id
                        (str (java.util.UUID/randomUUID))
                        :join-game
                        (java.time.Instant/now)
                        {:player {:id 3 :name "Charlie"}
                         :timestamp (java.time.Instant/now)})
      (db/append-event! game-id
                        (str (java.util.UUID/randomUUID))
                        :start-game
                        (java.time.Instant/now)
                        {:timestamp (java.time.Instant/now)})

      ;; Get game - should have all events applied
      (let [game (db/get-game-by-code short-code)]
        (is (= 4 (:state_sequence game))
            "Should have sequence 4 (create + 3 events)")
        (is (= 3 (count (get-in game [:state :players])))
            "Should have 3 players")
        (is (= :live (get-in game [:state :status]))
            "Game should be live after start-game (keyword after normalization)")))))

  (deftest event-idempotency-test
    (testing "Appending the same event_id twice is idempotent"
      (let [action {:player {:id 1 :name "Alice"}}
            result (db/create-game! action)
            game-id (:id result)
            short-code (:short-code result)
            event-id (str (java.util.UUID/randomUUID))
            timestamp (java.time.Instant/now)
            event-data {:player {:id 2 :name "Bob"} :timestamp timestamp}]

      ;; Append event first time
        (db/append-event! game-id event-id :join-game timestamp event-data)

      ;; Append same event_id again - should be idempotent
        (db/append-event! game-id event-id :join-game timestamp event-data)

      ;; Verify only one event was created
        (let [game (db/get-game-by-code short-code)]
          (is (= 2 (:state_sequence game)) "Should have sequence 2 (not 3)")
          (is (= 2 (count (get-in game [:state :players]))) "Should have 2 players (not duplicate)")))))

  (deftest create-game-test
    (testing "Creating a game with default short-code"
      (let [action {:player {:id 1 :name "Alice"}}
            result (db/create-game! action)]
        (is (some? (:id result)) "Should have game ID")
        (is (some? (:short-code result)) "Should have generated short-code")
        (is (= 6 (count (:short-code result))) "Short-code should be 6 characters")))

    (testing "Creating a game with custom short-code"
      (let [custom-code (str "CUST" (System/currentTimeMillis))
            action {:player {:id 1 :name "Alice"} :short-code custom-code}
            result (db/create-game! action)]
        (is (= custom-code (:short-code result)) "Should use custom short-code")))

    (testing "Creating multiple games with different custom short-codes"
    ;; Use unique codes with timestamp to avoid collisions
      (let [timestamp (System/currentTimeMillis)
            code1 (str "GA" timestamp)
            code2 (str "GB" timestamp)
            action1 {:player {:id 1 :name "Alice"} :short-code code1}
            result1 (db/create-game! action1)
            action2 {:player {:id 2 :name "Bob"} :short-code code2}
            result2 (db/create-game! action2)]
        (is (= code1 (:short-code result1)) "First game should have first code")
        (is (= code2 (:short-code result2)) "Second game should have second code")
        (is (not= (:id result1) (:id result2)) "Games should have different IDs"))))

  (deftest schema-normalization-preserves-hands-test
    (testing "Schema normalization preserves hands with string ranks after start-game"
      (let [action {:player {:id 1 :name "Alice"}}
            result (db/create-game! action)
            game-id (:id result)
            short-code (:short-code result)]

      ;; Add second player
        (db/append-event! game-id
                          (str (java.util.UUID/randomUUID))
                          :join-game
                          (java.time.Instant/now)
                          {:player {:id 2 :name "Bob"}
                           :timestamp (java.time.Instant/now)})

      ;; Start the game (which deals cards)
        (db/append-event! game-id
                          (str (java.util.UUID/randomUUID))
                          :start-game
                          (java.time.Instant/now)
                          {:timestamp (java.time.Instant/now)})

      ;; Fetch game from database - this goes through ensure-fresh-state -> rebuild -> normalize
        (let [game (db/get-game-by-code short-code)
              hands (get-in game [:state :zones :hands])]

        ;; Critical: hands should NOT be empty after normalization
          (is (map? hands) "Hands should be a map")
          (is (= 2 (count hands)) "Should have hands for 2 players")
          (is (contains? hands 1) "Should have hand for player 1")
          (is (contains? hands 2) "Should have hand for player 2")

        ;; Validate each player's hand
          (let [p1-hand (get hands 1)
                p2-hand (get hands 2)]
            (is (= 4 (count p1-hand)) "Player 1 should have 4 cards")
            (is (= 4 (count p2-hand)) "Player 2 should have 4 cards")

          ;; Validate card structure with STRING ranks (not keywords)
            (is (every? map? p1-hand) "Player 1 cards should be maps")
            (is (every? map? p2-hand) "Player 2 cards should be maps")

          ;; Critical: ranks should be STRINGS like "A", "2", "10" (not keywords like :ace, :two)
            (is (every? #(string? (:rank %)) p1-hand) "Player 1 card ranks should be strings")
            (is (every? #(string? (:rank %)) p2-hand) "Player 2 card ranks should be strings")

          ;; Suits should be keywords
            (is (every? #(keyword? (:suit %)) p1-hand) "Player 1 card suits should be keywords")
            (is (every? #(keyword? (:suit %)) p2-hand) "Player 2 card suits should be keywords")

          ;; Ranks should be valid values
            (let [valid-ranks #{"2" "3" "4" "5" "6" "7" "8" "9" "10" "J" "Q" "K" "A"}]
              (is (every? #(valid-ranks (:rank %)) p1-hand) "Player 1 ranks should be valid")
              (is (every? #(valid-ranks (:rank %)) p2-hand) "Player 2 ranks should be valid"))))))

    (testing "Direct normalization of game state with hands preserves string ranks"
    ;; Create a game state directly (not via database)
      (let [state (-> (game/new-game "TEST")
                      (game/add-player {:id 1 :name "Alice"})
                      (game/add-player {:id 2 :name "Bob"})
                      (game/start-game {}))
            hands-before (get-in state [:zones :hands])
            normalized (schema/normalize-game state)
            hands-after (get-in normalized [:zones :hands])]

      ;; Hands should be preserved through normalization
        (is (= (count hands-before) (count hands-after)) "Hand count should be preserved")
        (is (= 2 (count hands-after)) "Should have 2 players' hands")

      ;; Each player should still have 4 cards
        (is (= 4 (count (get hands-after 1))) "Player 1 should have 4 cards after normalization")
        (is (= 4 (count (get hands-after 2))) "Player 2 should have 4 cards after normalization")

      ;; Ranks should still be strings
        (let [all-cards (concat (get hands-after 1) (get hands-after 2))]
          (is (every? #(string? (:rank %)) all-cards) "All ranks should remain strings after normalization")
          (is (every? #(keyword? (:suit %)) all-cards) "All suits should remain keywords after normalization")))))

  (deftest get-player-by-username-test
    (testing "finds player case-insensitively"
      (let [created (db/create-player! {:name "alice" :email "a@test.com"})]
        (is (= (:id created) (:id (db/get-player-by-username "alice"))))
        (is (= (:id created) (:id (db/get-player-by-username "ALICE"))))
        (is (nil? (db/get-player-by-username "nobody"))))))

  (deftest username-uniqueness-test
    (testing "player names are unique case-insensitively"
      (db/create-player! {:name "alice" :email "a@test.com"})
      (is (thrown? Exception
                   (db/create-player! {:name "ALICE" :email "b@test.com"}))
          "Duplicate username with different case should violate unique index")))

  (deftest username-migration-test
    (testing "init! dedups legacy duplicate names before creating the index"
      (jdbc/execute! (db/datasource) ["DROP INDEX IF EXISTS idx_players_name_unique"])
      (db/create-player! {:name "Alice" :email "a@test.com"})
      (db/create-player! {:name "ALICE" :email "b@test.com"})
      (db/init!)
      (let [rows (jdbc/execute! (db/datasource) ["SELECT name FROM players"]
                                {:builder-fn rs/as-unqualified-lower-maps})]
        (is (= 2 (count rows)))
        (is (= 2 (count (set (map #(str/lower-case (:name %)) rows))))
            "Names should be unique case-insensitively after migration"))))

  (deftest cache-equals-full-rebuild-test
    (testing "games.state cache always equals a from-scratch event replay"
      (let [ts (java.time.Instant/parse "2026-01-01T00:00:00Z")
            p1 {:id 1 :name "alice"}
            p2 {:id 2 :name "bob"}
            {:keys [id short-code]} (db/create-game! {:player p1 :timestamp ts})
          ;; :meta holds wall-clock Instants stamped by new-game at replay
          ;; time, so it can never match across two replays; compare game
          ;; content only. :start-game persists its deal in the event, so
          ;; dealt zones are identical on every replay — compare everything.
            check (fn [label]
                    (let [a (dissoc (schema/normalize-game (db/rebuild-state-from-events id)) :meta)
                          b (dissoc (get-in (db/get-game-by-code short-code) [:state]) :meta)]
                      (is (= a b) (str label ": cache matches full replay"))
                      (when (= :live (:status a))
                        (is (= 4 (count (get-in a [:zones :hands 1]))))
                        (is (= 4 (count (get-in a [:zones :hands 2])))))))]
        (check "after-create") ; seq 0 -> full rebuild from scratch
        (db/append-event! id "e-join" :join-game ts {:player p2 :timestamp ts})
        (check "after-join") ; stale -> snapshot + incremental replay
        (db/append-event! id "e-start" :start-game ts {:timestamp ts})
        (check "after-start") ; stale again, now with hands dealt
        (let [row (db/get-game-by-code short-code)]
          (is (= 3 (:state_sequence row)) "cache tracks the last event")))))

  (deftest start-game-replay-deterministic-test
    (testing ":start-game persists its deal, so replays are identical"
      (let [ts (java.time.Instant/parse "2026-01-01T00:00:00Z")
            p1 {:id 1 :name "alice"}
            p2 {:id 2 :name "bob"}
            {:keys [id short-code]} (db/create-game! {:player p1 :timestamp ts})
            _ (db/append-event! id "e-join" :join-game ts {:player p2 :timestamp ts})
          ;; No deck supplied: append must persist the deal in the event.
            _ (db/append-event! id "e-start" :start-game ts {:timestamp ts})
            no-meta #(dissoc % :meta)
            a (no-meta (schema/normalize-game (db/rebuild-state-from-events id)))
            b (no-meta (schema/normalize-game (db/rebuild-state-from-events id)))
            cached (no-meta (get-in (db/get-game-by-code short-code) [:state]))]
        (is (= a b) "two from-scratch replays are fully identical")
        (is (= a cached) "cache equals full replay, including dealt zones"))))

  (deftest start-game-cards-per-player-persisted-test
    (testing "non-default cards-per-player survives the event roundtrip"
      (let [ts (java.time.Instant/parse "2026-01-01T00:00:00Z")
            p1 {:id 1 :name "alice"}
            p2 {:id 2 :name "bob"}
            {:keys [id short-code]} (db/create-game! {:player p1 :timestamp ts})
            _ (db/append-event! id "e-join" :join-game ts {:player p2 :timestamp ts})
            deck (cards/make-deck)
            starting (cards/select-starting-card deck)
            _ (db/append-event! id "e-start" :start-game ts
                                {:timestamp ts :cards-per-player 2
                                 :deck deck :starting-card starting})
            rebuilt (db/rebuild-state-from-events id)
            cached (get-in (db/get-game-by-code short-code) [:state])]
        (is (= 2 (count (game/get-hand rebuilt 1))))
        (is (= 2 (count (game/get-hand rebuilt 2))))
        (is (= (dissoc (schema/normalize-game rebuilt) :meta)
               (dissoc cached :meta))
            "replay with explicit count matches cache"))))

  (deftest stale-cache-refresh-never-regresses-test
    (testing "stale refresh must not overwrite newer cache (CAS)"
      (let [ts (java.time.Instant/parse "2026-01-01T00:00:00Z")
            p1 {:id 1 :name "alice"}
            p2 {:id 2 :name "bob"}
            {:keys [id short-code]} (db/create-game! {:player p1 :timestamp ts})
            _ (db/append-event! id "e-join" :join-game ts {:player p2 :timestamp ts})
            fresh (db/get-game-by-code short-code)
            fresh-seq (:state_sequence fresh)
            stale-state (db/rebuild-state-from-events id)]
        (is (= 2 fresh-seq))
      ;; Simulate a stale reader overwriting with an older snapshot:
      ;; cache is at 2, stale write tries to push seq 1.
      ;; Read the RAW row (get-game-by-code would self-heal and mask it).
        (db/update-game-cache! id stale-state 1)
        (let [raw (jdbc/execute-one! (db/datasource)
                                     ["SELECT state_sequence FROM games WHERE id = ?" id]
                                     {:builder-fn rs/as-unqualified-lower-maps})]
          (is (= 2 (:state_sequence raw))
              "stale write with older sequence must not regress the cache")))))

  (deftest stale-expected-seq-rejected-test
    (testing "append with a stale expected-seq must be rejected"
      (let [ts (java.time.Instant/parse "2026-01-01T00:00:00Z")
            p1 {:id 1 :name "alice"}
            p2 {:id 2 :name "bob"}
            {:keys [id short-code]} (db/create-game! {:player p1 :timestamp ts})
            _ (db/append-event! id "e-join" :join-game ts {:player p2 :timestamp ts})
            _ (db/append-event! id "e-start" :start-game ts {:timestamp ts})
            game (db/get-game-by-code short-code)
            state (:state game)
            stale-seq (:state_sequence game)
            p1-id (:id p1)
            _ (is (= p1-id (game/current-player-id state)) "P1 starts")
            _ (is (:ok (game/validate-draw state p1-id)) "draw validates against fresh state")
            hand-before (count (game/get-hand state p1-id))
          ;; First draw with the fresh seq succeeds.
            _ (db/append-event! id "e-draw-1" :draw-card ts
                                {:player-id p1-id :maintain-kadi? false :timestamp ts}
                                stale-seq)
          ;; Second draw was ALSO validated against the same stale state
          ;; (still P1's turn there). With a stale expected-seq it must be
          ;; rejected instead of letting P1 draw twice.
            _ (is (:ok (game/validate-draw state p1-id)) "stale validation still says :ok")
            _ (is (thrown? clojure.lang.ExceptionInfo
                           (db/append-event! id "e-draw-2" :draw-card ts
                                             {:player-id p1-id :maintain-kadi? false :timestamp ts}
                                             stale-seq))
                  "stale second draw is rejected")
            after (db/get-game-by-code short-code)
            state-after (:state after)]
        (is (= (inc hand-before) (count (game/get-hand state-after p1-id)))
            "P1 drew exactly once")
        (is (= (:id p2) (game/current-player-id state-after))
            "turn advanced to P2 exactly once")
        (is (= 4 (:state_sequence after)) "no second event persisted"))))

(deftest parallel-append-test
  (testing "concurrent appends across and within games stay consistent"
    (let [ts "2026-01-01T00:00:00Z"
          games (doall (for [n (range 2)]
                         (db/create-game! {:player {:id n :name (str "p" n)}
                                           :timestamp ts})))
          join! (fn [game-id k]
                  (db/append-event! game-id (str (java.util.UUID/randomUUID))
                                    :join-game ts
                                    {:player {:id (+ 100 (* game-id 100) k)
                                              :name (str "j" game-id "-" k)}
                                     :timestamp ts}))
          results (doall (map deref
                              (for [g games
                                    t (range 2)]
                                (future (doall (for [k (range 5)] (join! (:id g) (+ (* t 5) k))))))))]
      (is (every? #(= 5 (count %)) results) "all appends succeed")
      (doseq [[g res] (map vector games (partition 2 results))]
        (let [seqs (sort (map :sequence_number (apply concat res)))
              row (db/get-game-by-code (:short-code g))]
          (is (= (range 2 12) seqs) "per-game sequences are gapless")
          (is (= 11 (:state_sequence row)) "cache tracks the last event")
          (is (= 11 (count (get-in row [:state :players]))))))
      (is (= [11 11] (mapv #(count (get-in (db/get-game-by-code (:short-code %)) [:state :players])) games))
          "both games hold creator + 10 joins"))))
