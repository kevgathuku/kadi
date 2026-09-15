(ns kadi.play-actions-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [kadi.cards :as cards]
            [kadi.handlers :as handlers]
            [kadi.db :as db]
            [kadi.game :as game]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [next.jdbc :as jdbc]))

;; Use a test database file to ensure persistence across connections during tests
;; but isolation from the main DB.
(def test-db-file "test-play-actions.db")
(def test-db-spec {:dbtype "sqlite" :dbname test-db-file})

;; Set default DB to test DB at load time
(alter-var-root #'db/*db-spec* (constantly test-db-spec))

;; Initialize schema immediately
(db/init!)

(defn with-test-db [f]
  ;; Re-assert our DB per-test: alter-var-root at load time loses to
  ;; whichever test namespace loads last.
  (alter-var-root #'db/*db-spec* (constantly test-db-spec))
  ;; Clean up before test
  (let [file (java.io.File. test-db-file)]
    (when (.exists file)
      (.delete file)))

  ;; Re-init
  (db/init!)

  (try
    (f)
    (finally
      ;; Clean up after test
      (let [file (java.io.File. test-db-file)]
        (when (.exists file)
          (.delete file))))))

(use-fixtures :each with-test-db)

;; Mock app with necessary middleware
(defn mock-app [handler]
  (-> handler
      wrap-keyword-params
      wrap-params))

(defn setup-test-game []
  (let [player1 (db/create-player! {:name "Alice" :email "alice@example.com"})
        player2 (db/create-player! {:name "Bob" :email "bob@example.com"})
        game-info (db/create-game! {:player (select-keys player1 [:id :name])
                                    :short-code (game/generate-short-code)}) ;; Unique code
        short-code (:short-code game-info)
        game-id (:id game-info)]
    (db/add-player-to-game! game-id (:id player2))
    ;; Append join event for player 2
    (db/append-event! game-id (str (java.util.UUID/randomUUID)) :join-game
                      (java.time.Instant/now) {:player (select-keys player2 [:id :name])})
    ;; Start game
    (db/append-event! game-id (str (java.util.UUID/randomUUID)) :start-game
                      (java.time.Instant/now) {:timestamp (java.time.Instant/now)})

    ;; Force fresh state load
    (db/get-game-by-code short-code)

    {:short-code short-code
     :game-id game-id
     :p1 player1
     :p2 player2}))

(deftest play-single-card-test
  (testing "Playing a single valid card works via HTTP handler"
    (let [{:keys [short-code p1 game-id]} (setup-test-game)
          game (db/get-game-by-code short-code)
          current-state (:state game)

          ;; Rig hand to ensure we have a playable card
          rigged-hand [{:suit :hearts :rank "5"} {:suit :clubs :rank "9"} {:suit :spades :rank "K"}]
          rigged-top-card {:suit :hearts :rank "9"}

          state-with-rigged-hand (-> current-state
                                     (game/set-hand (:id p1) rigged-hand)
                                     (assoc-in [:zones :played-stack] [rigged-top-card]))

          ;; Get latest seq to ensure cache is trusted
          latest-seq (or (:seq (jdbc/execute-one! (db/datasource)
                                                  ["SELECT MAX(sequence_number) as seq FROM game_events WHERE game_id = ?" game-id]))
                         0)
          _ (db/update-game-cache! game-id state-with-rigged-hand latest-seq)

          ;; Play 5 of hearts (matches suit)
          playable-card {:suit :hearts :rank "5"}

          req {:request-method :post
               :path-params {:code short-code}
               :headers {"content-type" "application/x-www-form-urlencoded"}
               :body (java.io.ByteArrayInputStream. (.getBytes (str "ordered-cards=" (cards/card->id playable-card))))
               :session {:player-id (:id p1)}}

          app (mock-app handlers/play-cards)
          resp (app req)]

      (is (= 302 (:status resp)))
      (is (= (str "/games/" short-code) (get-in resp [:headers "Location"])))

      (let [flash (get-in resp [:flash])]
        (is (nil? flash) (str "Play failed with error: " (:message flash))))

      (let [updated-game (db/get-game-by-code short-code)]
        (is (= 2 (count (game/get-hand (:state updated-game) (:id p1)))))))))

(deftest draw-card-test
  (testing "Drawing a card works via HTTP handler"
    (let [{:keys [short-code p1]} (setup-test-game)
          game (db/get-game-by-code short-code)
          hand-count (count (game/get-hand (:state game) (:id p1)))

          req {:request-method :post
               :path-params {:code short-code}
               :session {:player-id (:id p1)}}

          app (mock-app handlers/draw-card)
          resp (app req)]

      (is (= 302 (:status resp)))
      (let [updated-game (db/get-game-by-code short-code)]
        (is (= (inc hand-count) (count (game/get-hand (:state updated-game) (:id p1)))))))))

(deftest play-multiple-cards-test
  (testing "Playing multiple matching cards works via HTTP handler"
    (let [{:keys [short-code p1 game-id]} (setup-test-game)
          game (db/get-game-by-code short-code)
          current-state (:state game)

          rigged-hand [{:suit :hearts :rank "5"} {:suit :clubs :rank "5"} {:suit :spades :rank "9"} {:suit :diamonds :rank "K"}]
          rigged-top-card {:suit :hearts :rank "9"}

          state-with-rigged-hand (-> current-state
                                     (game/set-hand (:id p1) rigged-hand)
                                     (assoc-in [:zones :played-stack] [rigged-top-card]))

          ;; Get latest seq to ensure cache is trusted
          latest-seq (or (:seq (jdbc/execute-one! (db/datasource)
                                                  ["SELECT MAX(sequence_number) as seq FROM game_events WHERE game_id = ?" game-id]))
                         0)
          _ (db/update-game-cache! game-id state-with-rigged-hand latest-seq)

          req {:request-method :post
               :path-params {:code short-code}
               :headers {"content-type" "application/x-www-form-urlencoded"}
               :body (java.io.ByteArrayInputStream. (.getBytes "ordered-cards=5-hearts,5-clubs"))
               :session {:player-id (:id p1)}}

          app (mock-app handlers/play-cards)
          resp (app req)]

      (is (= 302 (:status resp)))
      (let [flash (get-in resp [:flash])]
        (is (nil? flash) (str "Should not have error: " (:message flash))))

      (let [updated-game (db/get-game-by-code short-code)
            new-hand (game/get-hand (:state updated-game) (:id p1))]
        (is (= 2 (count new-hand)) "Should have 2 cards left (played 2)")))))

(deftest middleware-order-verification-test
  (testing "verify that wrap-params and wrap-keyword-params work together as expected"
    (let [handler (fn [req] {:status 200 :body (:params req)})
          app (-> handler wrap-keyword-params wrap-params)
          req {:request-method :post
               :headers {"content-type" "application/x-www-form-urlencoded"}
               :body (java.io.ByteArrayInputStream. (.getBytes "cards=5-hearts&cards=5-clubs"))}
          resp (app req)]
      (is (= {:cards ["5-hearts" "5-clubs"]} (:body resp)) "Should parse multiple params into vector"))))

(deftest play-question-with-answer-ordered-test
  (testing "Playing Q+answer cards respects the order (Q first, then answer)"
    (let [{:keys [short-code p1 game-id]} (setup-test-game)
          game (db/get-game-by-code short-code)
          current-state (:state game)

          ;; Rig hand with Q and a matching answer card
          rigged-hand [{:suit :hearts :rank "Q"} {:suit :hearts :rank "5"} {:suit :spades :rank "9"}]
          rigged-top-card {:suit :hearts :rank "9"}

          state-with-rigged-hand (-> current-state
                                     (game/set-hand (:id p1) rigged-hand)
                                     (assoc-in [:zones :played-stack] [rigged-top-card]))

          ;; Get latest seq to ensure cache is trusted
          latest-seq (or (:seq (jdbc/execute-one! (db/datasource)
                                                  ["SELECT MAX(sequence_number) as seq FROM game_events WHERE game_id = ?" game-id]))
                         0)
          _ (db/update-game-cache! game-id state-with-rigged-hand latest-seq)

          ;; Play Q first, then 5 (correct order)
          req {:request-method :post
               :path-params {:code short-code}
               :headers {"content-type" "application/x-www-form-urlencoded"}
               :body (java.io.ByteArrayInputStream. (.getBytes "ordered-cards=Q-hearts,5-hearts"))
               :session {:player-id (:id p1)}}

          app (mock-app handlers/play-cards)
          resp (app req)]

      (is (= 302 (:status resp)))
      (let [flash (get-in resp [:flash])]
        (is (nil? flash) (str "Should not have error: " (:message flash))))

      (let [updated-game (db/get-game-by-code short-code)
            new-hand (game/get-hand (:state updated-game) (:id p1))
            state (:state updated-game)]
        (is (= 1 (count new-hand)) "Should have 1 card left (played Q+5)")
        (is (not (game/has-effect? state :awaiting-answer)) "Should not be awaiting answer after complete Q+A")))))

