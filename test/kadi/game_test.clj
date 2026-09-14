(ns kadi.game-test
  (:require [clojure.test :refer [deftest testing is]]
            [kadi.game :as game]
            [kadi.cards :as cards]
            [kadi.schema :as schema]
            [jsonista.core :as json]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn make-test-game
  "Create a game with two players for testing."
  []
  (-> (game/new-game "TEST")
      (#(:ok (game/join-player % {:id 1 :name "Alice"})))
      (#(:ok (game/join-player % {:id 2 :name "Bob"})))
      (game/start-game {})))

(defn make-3p-test-game
  "Create a game with three players for testing."
  []
  (-> (game/new-game "TEST3")
      (#(:ok (game/join-player % {:id 1 :name "Alice"})))
      (#(:ok (game/join-player % {:id 2 :name "Bob"})))
      (#(:ok (game/join-player % {:id 3 :name "Charlie"})))
      (game/start-game {})))

(defn give-card
  "Give a specific card to a player (for testing)."
  [state player-id card]
  (game/update-hand state player-id #(conj % card)))

(defn set-top-card
  "Set the top card of the played stack (for testing)."
  [state card]
  (assoc-in state [:zones :played-stack] [card]))

(defn clear-hand
  "Clear a player's hand (for testing cardless scenarios)."
  [state player-id]
  (game/set-hand state player-id []))

;; =============================================================================
;; Card Tests
;; =============================================================================

(deftest card-predicates
  (testing "ace detection"
    (is (cards/ace? {:suit :hearts :rank "A"}))
    (is (not (cards/ace? {:suit :hearts :rank "K"}))))

  (testing "question card detection"
    (is (cards/question-card? {:suit :hearts :rank "Q"}))
    (is (cards/question-card? {:suit :clubs :rank "8"}))
    (is (not (cards/question-card? {:suit :hearts :rank "K"}))))

  (testing "penalty card detection"
    (is (cards/penalty-card? {:suit :hearts :rank "2"}))
    (is (cards/penalty-card? {:suit :clubs :rank "3"}))
    (is (not (cards/penalty-card? {:suit :hearts :rank "4"})))))

(deftest card-matching
  (testing "suit matching"
    (is (cards/matches-suit? {:suit :hearts :rank "5"}
                             {:suit :hearts :rank "K"}))
    (is (not (cards/matches-suit? {:suit :hearts :rank "5"}
                                  {:suit :clubs :rank "5"}))))

  (testing "rank matching"
    (is (cards/matches-rank? {:suit :hearts :rank "5"}
                             {:suit :clubs :rank "5"}))
    (is (not (cards/matches-rank? {:suit :hearts :rank "5"}
                                  {:suit :hearts :rank "6"})))))

;; =============================================================================
;; Game State Tests
;; =============================================================================

(deftest new-game-creation
  (let [game (game/new-game "TEST")]
    (is (= "TEST" (:short-code game)))
    (is (= :lobby (:status game)))
    (is (empty? (:players game)))
    (is (= :clockwise (:direction game)))))

(deftest player-management
  (testing "adding players"
    (let [game (-> (game/new-game "TEST")
                   (game/add-player {:id 1 :name "Alice"})
                   (game/add-player {:id 2 :name "Bob"}))]
      (is (= 2 (count (:players game))))
      (is (= "Alice" (get-in game [:players 0 :name])))))

  (testing "cannot add players after game starts"
    (let [game (make-test-game)
          result (game/add-player game {:id 3 :name "Charlie"})]
      (is (:error result)))))

(deftest game-start
  (testing "game starts with correct state"
    (let [game (make-test-game)]
      (is (= :live (:status game)))
      (is (= 4 (count (game/get-hand game (:id (get-in game [:players 0]))))))
      (is (= 1 (count (get-in game [:zones :played-stack]))))
      (is (pos? (count (get-in game [:zones :deck]))))))

  (testing "each player has 4 cards after start"
    (let [game (make-test-game)
          players (:players game)]
      (doseq [player players]
        (let [hand (game/get-hand game (:id player))]
          (is (= 4 (count hand))
              (str "Player " (:name player) " should have 4 cards"))))))

  (testing "cannot start with less than 2 players"
    (let [game (-> (game/new-game "TEST")
                   (game/add-player {:id 1 :name "Alice"})
                   (game/start-game {}))]
      (is (= :lobby (:status game))))))

;; =============================================================================
;; Turn Management Tests
;; =============================================================================

(deftest turn-advancement
  (testing "turn advances clockwise"
    (let [game (make-test-game)
          next-game (game/advance-turn game)]
      (is (= 1 (game/current-player-index next-game)))))

  (testing "turn wraps around"
    (let [game (assoc (make-test-game) :current-player-index 1)
          next-game (game/advance-turn game)]
      (is (= 0 (game/current-player-index next-game))))))

(deftest direction-reversal
  (testing "king reverses direction"
    (let [game (make-test-game)
          reversed (game/reverse-direction game)]
      (is (= :counter-clockwise (:direction reversed))))

    (let [game (-> (make-test-game)
                   (assoc :direction :counter-clockwise))
          reversed (game/reverse-direction game)]
      (is (= :clockwise (:direction reversed))))))

;; =============================================================================
;; Card Play Tests
;; =============================================================================

(deftest play-matching-card
  (testing "can play card matching by suit"
    (let [top {:suit :hearts :rank "5"}
          card {:suit :hearts :rank "9"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (give-card 1 card))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [card]})]
      (is (not (:error result)))
      (is (= card (game/top-card result)))))

  (testing "can play card matching by rank"
    (let [top {:suit :hearts :rank "5"}
          card {:suit :clubs :rank "5"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (give-card 1 card))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [card]})]
      (is (not (:error result))))))

(deftest ace-always-playable
  (testing "ace can be played on any card"
    (let [top {:suit :hearts :rank "5"}
          ace {:suit :clubs :rank "A"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (give-card 1 ace))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [ace]})]
      (is (not (:error result)))
      (is (some #(= :select-suit (:type %)) (:effects result))))))

(deftest king-reverses-direction
  (testing "playing king reverses game direction"
    (let [top {:suit :hearts :rank "5"}
          king {:suit :hearts :rank "K"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (give-card 1 king))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [king]})]
      (is (not (:error result)))
      (is (= :counter-clockwise (:direction result))))))

(deftest jack-skips-player
  (testing "playing jack skips next player"
    (let [top {:suit :hearts :rank "5"}
          jack {:suit :hearts :rank "J"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (give-card 1 jack))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [jack]})]
      (is (not (:error result)))
      ;; In 2-player game, skip brings back to player 1
      (is (= 0 (game/current-player-index result))))))

;; =============================================================================
;; Penalty Tests
;; =============================================================================

(deftest penalty-creation
  (testing "playing 2 creates draw-2 penalty"
    (let [top {:suit :hearts :rank "5"}
          two {:suit :hearts :rank "2"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (give-card 1 two))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [two]})]
      (is (not (:error result)))
      (is (some #(= :penalty (:type %)) (:effects result))
          "penalty effect present")
      (is (= :two (:penalty-type (first (filter #(= :penalty (:type %)) (:effects result)))))))))

;; =============================================================================
;; Command Function Tests
;; =============================================================================

(deftest play-cards-cmd-errors
  (testing "not live game"
    (let [game (-> (game/new-game "TEST")
                   (game/add-player {:id 1 :name "Alice"})
                   (game/add-player {:id 2 :name "Bob"}))]
      (is (:error (game/play-cards-cmd game 1 [{:suit :hearts :rank "5"}])))))

  (testing "not your turn"
    (let [game (make-test-game)
          card {:suit :hearts :rank "5"}
          game (give-card game 2 card)]
      (is (:error (game/play-cards-cmd game 2 [card])))))

  (testing "card doesn't match"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"}))
          card {:suit :clubs :rank "9"}
          game (give-card game 1 card)]
      (is (:error (game/play-cards-cmd game 1 [card]))))))

(deftest draw-card-cmd-test
  (testing "successful draw"
    (let [game (make-test-game)
          result (game/draw-card-cmd game (game/current-player-id game))]
      (is (:ok result))
      (is (= 5 (count (game/get-hand (:ok result) (game/current-player-id game)))))))

  (testing "not your turn"
    (let [game (make-test-game)
          other-id (:id (second (:players game)))]
      (is (:error (game/draw-card-cmd game other-id))))))

(deftest select-suit-cmd-test
  (testing "successful suit selection"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :select-suit}))
          result (game/select-suit-cmd game :hearts)]
      (is (:ok result))
      (is (not (game/has-effect? (:ok result) :select-suit)))
      (is (game/has-effect? (:ok result) :suit-selected))))

  (testing "invalid suit"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :select-suit}))]
      (is (:error (game/select-suit-cmd game :invalid)))))

  (testing "no effect active"
    (let [game (make-test-game)]
      (is (:error (game/select-suit-cmd game :hearts))))))

(deftest accept-penalty-cmd-test
  (testing "successful accept draw-2"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :penalty :penalty-type :two}))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/accept-penalty-cmd game player-id)]
      (is (:ok result))
      (is (= (+ hand-before 2) (count (game/get-hand (:ok result) player-id))))
      (is (not (game/has-effect? (:ok result) :penalty)))))

  (testing "successful accept draw-3"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :penalty :penalty-type :three}))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/accept-penalty-cmd game player-id)]
      (is (:ok result))
      (is (= (+ hand-before 3) (count (game/get-hand (:ok result) player-id))))))

  (testing "no penalty active"
    (let [game (make-test-game)]
      (is (:error (game/accept-penalty-cmd game (game/current-player-id game)))))))

(deftest answer-question-cmd-test
  (testing "successful answer"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :awaiting-answer}))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/answer-question-cmd game player-id)]
      (is (:ok result))
      (is (= (+ hand-before 1) (count (game/get-hand (:ok result) player-id))))
      (is (not (game/has-effect? (:ok result) :awaiting-answer)))))

  (testing "no awaiting answer"
    (let [game (make-test-game)]
      (is (:error (game/answer-question-cmd game (game/current-player-id game)))))))

;; =============================================================================
;; Game Mechanics Tests
;; =============================================================================

(deftest penalty-3-creation
  (testing "playing 3 creates draw-3 penalty"
    (let [top {:suit :hearts :rank "5"}
          three {:suit :hearts :rank "3"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (give-card 1 three))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [three]})]
      (is (not (:error result)))
      (is (some #(= :penalty (:type %)) (:effects result)))
      (is (= :three (:penalty-type (first (filter #(= :penalty (:type %)) (:effects result)))))))))

(deftest penalty-blocking
  (testing "2 blocks 2 penalty"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "2"})
                   (update :effects conj {:type :penalty :penalty-type :two})
                   (give-card 1 {:suit :clubs :rank "2"}))
          result (game/play-cards-cmd game 1 [{:suit :clubs :rank "2"}])]
      (is (:ok result))
      ;; Should still have a penalty effect (new one stacked)
      (is (some #(= :penalty (:type %)) (:effects (:ok result))))))

  (testing "3 blocks 3 penalty"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "3"})
                   (update :effects conj {:type :penalty :penalty-type :three})
                   (give-card 1 {:suit :clubs :rank "3"}))
          result (game/play-cards-cmd game 1 [{:suit :clubs :rank "3"}])]
      (is (:ok result))))

  (testing "Ace blocks any penalty"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "2"})
                   (update :effects conj {:type :penalty :penalty-type :two})
                   (give-card 1 {:suit :clubs :rank "A"}))
          result (game/play-cards-cmd game 1 [{:suit :clubs :rank "A"}])]
      (is (:ok result))))

  (testing "cross-blocking prevented: 2 cannot block 3 penalty"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "3"})
                   (update :effects conj {:type :penalty :penalty-type :three})
                   (give-card 1 {:suit :hearts :rank "2"}))
          result (game/play-cards-cmd game 1 [{:suit :hearts :rank "2"}])]
      (is (:error result)))))

(deftest multi-card-combos
  (testing "same rank combo valid"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (give-card 1 {:suit :hearts :rank "5"})
                   (give-card 1 {:suit :clubs :rank "5"}))
          result (game/play-cards-cmd game 1
                                      [{:suit :hearts :rank "5"} {:suit :clubs :rank "5"}])]
      (is (:ok result))))

  (testing "king combo rejected"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (give-card 1 {:suit :hearts :rank "K"})
                   (give-card 1 {:suit :clubs :rank "K"}))
          result (game/play-cards-cmd game 1
                                      [{:suit :hearts :rank "K"} {:suit :clubs :rank "K"}])]
      (is (:error result)))))

(deftest cardless-state
  (testing "K as last card triggers cardless"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "K"}))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [{:suit :hearts :rank "K"}]})]
      (is (= :cardless (:status (game/get-player result 1))))))

  (testing "J as last card triggers cardless"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "J"}))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [{:suit :hearts :rank "J"}]})]
      (is (= :cardless (:status (game/get-player result 1))))))

  (testing "2 as last card triggers cardless"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "2"}))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [{:suit :hearts :rank "2"}]})]
      (is (= :cardless (:status (game/get-player result 1))))))

  (testing "regular card as last does not trigger cardless"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "9"}))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [{:suit :hearts :rank "9"}]})]
      (is (= :normal (:status (game/get-player result 1))))))

  (testing "A as last card triggers cardless (requires suit selection)"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (clear-hand 1)
                   (give-card 1 {:suit :clubs :rank "A"}))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [{:suit :clubs :rank "A"}]})]
      (is (= :cardless (:status (game/get-player result 1))))))

  (testing "Q as last card triggers cardless (requires answer)"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "Q"}))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [{:suit :hearts :rank "Q"}]})]
      (is (= :cardless (:status (game/get-player result 1))))))

  (testing "8 as last card triggers cardless (requires answer)"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "8"}))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [{:suit :hearts :rank "8"}]})]
      (is (= :cardless (:status (game/get-player result 1)))))))

(deftest deck-recycling
  (testing "recycling moves played stack (minus top) back to deck"
    (let [game (-> (make-test-game)
                   (assoc-in [:zones :deck] [])
                   (assoc-in [:zones :played-stack]
                             [{:suit :hearts :rank "5"}
                              {:suit :clubs :rank "6"}
                              {:suit :diamonds :rank "7"}]))
          recycled (game/recycle-played-stack game)]
      ;; Top card stays on played stack
      (is (= [{:suit :diamonds :rank "7"}] (get-in recycled [:zones :played-stack])))
      ;; Other cards go to deck
      (is (= 2 (count (get-in recycled [:zones :deck]))))))

  (testing "single card in played stack is no-op"
    (let [game (-> (make-test-game)
                   (assoc-in [:zones :played-stack] [{:suit :hearts :rank "5"}]))
          recycled (game/recycle-played-stack game)]
      (is (= game recycled)))))

(deftest three-player-jack-skip
  (testing "jack skips one player in 3-player game"
    (let [top {:suit :hearts :rank "5"}
          jack {:suit :hearts :rank "J"}
          game (-> (make-3p-test-game)
                   (set-top-card top)
                   (give-card 1 jack))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [jack]})]
      ;; Player 1 (idx 0) plays, skip player 2 (idx 1), go to player 3 (idx 2)
      (is (= 2 (game/current-player-index result))))))

(deftest three-player-direction-reversal
  (testing "king reverses direction in 3-player game"
    (let [top {:suit :hearts :rank "5"}
          king {:suit :hearts :rank "K"}
          game (-> (make-3p-test-game)
                   (set-top-card top)
                   (give-card 1 king))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [king]})]
      (is (= :counter-clockwise (:direction result)))
      ;; After reversal from idx 0, next is idx 2 (wraps counter-clockwise)
      (is (= 2 (game/current-player-index result))))))

;; =============================================================================
;; Event Replay Tests
;; =============================================================================

(deftest event-replay-play-cards
  (testing "apply-action :play-cards replays correctly"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (give-card 1 {:suit :hearts :rank "9"}))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [{:suit :hearts :rank "9"}]})]
      (is (= {:suit :hearts :rank "9"} (game/top-card result))))))

(deftest event-replay-draw-card
  (testing "apply-action :draw-card replays correctly"
    (let [game (make-test-game)
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/apply-action game {:type :draw-card
                                          :player-id player-id})]
      (is (= (inc hand-before) (count (game/get-hand result player-id)))))))

(deftest event-replay-select-suit
  (testing "apply-action :select-suit replays correctly"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :select-suit}))
          result (game/apply-action game {:type :select-suit :suit :hearts})]
      (is (not (game/has-effect? result :select-suit)))
      (is (game/has-effect? result :suit-selected)))))

(deftest event-replay-accept-penalty
  (testing "apply-action :accept-penalty replays correctly"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :penalty :penalty-type :two}))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/apply-action game {:type :accept-penalty
                                          :player-id player-id})]
      (is (= (+ hand-before 2) (count (game/get-hand result player-id))))
      (is (not (game/has-effect? result :penalty))))))

(deftest event-replay-answer-question
  (testing "apply-action :answer-question replays correctly"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :awaiting-answer}))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/apply-action game {:type :answer-question
                                          :player-id player-id})]
      (is (= (inc hand-before) (count (game/get-hand result player-id))))
      (is (not (game/has-effect? result :awaiting-answer))))))

;; =============================================================================
;; Backward Compatibility Alias Tests
;; =============================================================================

(deftest backward-compat-cards-played
  (testing ":cards-played delegates to :play-cards"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (give-card 1 {:suit :hearts :rank "9"}))
          result (game/apply-action game {:type :cards-played
                                          :player-id 1
                                          :cards [{:suit :hearts :rank "9"}]})]
      (is (= {:suit :hearts :rank "9"} (game/top-card result))))))

(deftest backward-compat-card-drawn
  (testing ":card-drawn delegates to :draw-card"
    (let [game (make-test-game)
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/apply-action game {:type :card-drawn
                                          :player-id player-id})]
      (is (= (inc hand-before) (count (game/get-hand result player-id)))))))

;; =============================================================================
;; Q/8 Question-Answer Flow Tests
;; =============================================================================

(deftest question-sets-awaiting-answer
  (testing "playing Q sets :awaiting-answer and keeps turn on same player"
    (let [top {:suit :clubs :rank "Q"}
          q {:suit :hearts :rank "Q"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (give-card 1 q))
          idx-before (game/current-player-index game)
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [q]})]
      (is (not (:error result)))
      (is (game/has-effect? result :awaiting-answer)
          "awaiting-answer effect should be set")
      (is (= idx-before (game/current-player-index result))
          "turn should stay on same player"))))

(deftest question-then-answer-succeeds
  (testing "answer-question after Q play draws 1 card, clears effect, advances turn"
    (let [top {:suit :clubs :rank "Q"}
          q {:suit :hearts :rank "Q"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (give-card 1 q))
          after-q (game/apply-action game {:type :play-cards
                                           :player-id 1
                                           :cards [q]})
          player-id (game/current-player-id after-q)
          hand-before (count (game/get-hand after-q player-id))
          result (game/answer-question-cmd after-q player-id)]
      (is (:ok result))
      (is (= (inc hand-before) (count (game/get-hand (:ok result) player-id)))
          "should draw 1 card")
      (is (not (game/has-effect? (:ok result) :awaiting-answer))
          "awaiting-answer should be cleared")
      (is (not= 0 (game/current-player-index (:ok result)))
          "turn should advance to next player"))))

(deftest question-with-answer-combo-no-awaiting
  (testing "Q+answer combo does not set :awaiting-answer"
    (let [top {:suit :clubs :rank "Q"}
          q {:suit :hearts :rank "Q"}
          answer {:suit :hearts :rank "5"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (give-card 1 q)
                   (give-card 1 answer))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [q answer]})]
      (is (not (:error result)))
      (is (not (game/has-effect? result :awaiting-answer))
          "no awaiting-answer when Q is played with an answer card"))))

;; =============================================================================
;; Q+answer via play-cards-cmd Tests (Bug 1 regression)
;; =============================================================================

(deftest question-with-answer-via-cmd
  (testing "Q + answer card via play-cards-cmd succeeds and advances turn"
    (let [top {:suit :hearts :rank "9"}
          q {:suit :hearts :rank "Q"}
          answer {:suit :hearts :rank "5"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (clear-hand 1)
                   (give-card 1 q)
                   (give-card 1 answer))
          result (game/play-cards-cmd game 1 [q answer])]
      (is (:ok result) "Q+answer should succeed")
      (is (not (game/has-effect? (:ok result) :awaiting-answer)))
      (is (= 1 (game/current-player-index (:ok result)))
          "turn should advance to next player")))

  (testing "Q + penalty answer sets penalty effect"
    (let [top {:suit :hearts :rank "9"}
          q {:suit :hearts :rank "Q"}
          two {:suit :hearts :rank "2"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (clear-hand 1)
                   (give-card 1 q)
                   (give-card 1 two))
          result (game/play-cards-cmd game 1 [q two])]
      (is (:ok result) "Q+penalty answer should succeed")
      (is (game/has-effect? (:ok result) :penalty)
          "penalty effect should be set from answer card")))

  (testing "Q + Ace answer sets select-suit effect"
    (let [top {:suit :hearts :rank "9"}
          q {:suit :hearts :rank "Q"}
          ace {:suit :hearts :rank "A"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (clear-hand 1)
                   (give-card 1 q)
                   (give-card 1 ace))
          result (game/play-cards-cmd game 1 [q ace])]
      (is (:ok result) "Q+Ace answer should succeed")
      (is (game/has-effect? (:ok result) :select-suit)
          "select-suit should be set from Ace answer")))

  (testing "Q + King answer reverses direction"
    (let [top {:suit :hearts :rank "9"}
          q {:suit :hearts :rank "Q"}
          king {:suit :hearts :rank "K"}
          game (-> (make-test-game)
                   (set-top-card top)
                   (clear-hand 1)
                   (give-card 1 q)
                   (give-card 1 king))
          result (game/play-cards-cmd game 1 [q king])]
      (is (:ok result) "Q+King answer should succeed")
      (is (= :counter-clockwise (:direction (:ok result)))
          "direction should reverse from King answer"))))

;; =============================================================================
;; Answer-question deck exhaustion Tests (Bug 2)
;; =============================================================================

(deftest answer-question-empty-deck-recycles
  (testing "answer-question with empty deck recycles played stack"
    (let [game (-> (make-test-game)
                   (assoc-in [:zones :deck] [])
                   (assoc-in [:zones :played-stack]
                             [{:suit :hearts :rank "5"}
                              {:suit :clubs :rank "6"}
                              {:suit :diamonds :rank "7"}])
                   (update :effects conj {:type :awaiting-answer}))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/answer-question-cmd game player-id)]
      (is (:ok result) "should succeed after recycling")
      (is (= (inc hand-before) (count (game/get-hand (:ok result) player-id)))
          "player should draw 1 card from recycled deck")
      (is (not (game/has-effect? (:ok result) :awaiting-answer)))))

  (testing "answer-question with empty deck AND empty played pile skips player"
    (let [game (-> (make-test-game)
                   (assoc-in [:zones :deck] [])
                   (assoc-in [:zones :played-stack] [{:suit :hearts :rank "5"}])
                   (update :effects conj {:type :awaiting-answer}))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/answer-question-cmd game player-id)]
      (is (:ok result) "should succeed even with no cards to draw")
      (is (= hand-before (count (game/get-hand (:ok result) player-id)))
          "player should not draw any cards (skipped)")
      (is (not (game/has-effect? (:ok result) :awaiting-answer))
          "awaiting-answer should still be cleared"))))

;; =============================================================================
;; suit-selected clearing Tests (Bug 4)
;; =============================================================================

(deftest suit-selected-cleared-on-play
  (testing "suit-selected is cleared when next card is played"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :clubs :rank "A"})
                   (update :effects conj {:type :suit-selected :suit :hearts})
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "5"}))
          result (game/play-cards-cmd game 1 [{:suit :hearts :rank "5"}])]
      (is (:ok result) "play should succeed matching action-suit")
      (is (not (game/has-effect? (:ok result) :suit-selected))
          "suit-selected should be cleared after play")))

  (testing "suit-selected is cleared via apply-action :play-cards"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :clubs :rank "A"})
                   (update :effects conj {:type :suit-selected :suit :hearts})
                   (give-card 1 {:suit :hearts :rank "5"}))
          result (game/apply-action game {:type :play-cards
                                          :player-id 1
                                          :cards [{:suit :hearts :rank "5"}]})]
      (is (not (game/has-effect? result :suit-selected))
          "suit-selected should be cleared after play via apply-action"))))

;; =============================================================================
;; Schema Normalization Tests
;; =============================================================================

(deftest effects-survive-normalization
  (testing "penalty effect preserves :penalty-type after normalization"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :penalty :penalty-type :two}))
          normalized (schema/normalize-game game)
          penalty (game/get-effect normalized :penalty)]
      (is (some? penalty) "penalty effect should exist")
      (is (= :two (:penalty-type penalty))
          "penalty-type should survive normalization")))

  (testing "suit-selected effect preserves :suit after normalization"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :suit-selected :suit :hearts}))
          normalized (schema/normalize-game game)
          suit-effect (game/get-effect normalized :suit-selected)]
      (is (some? suit-effect) "suit-selected effect should exist")
      (is (= :hearts (:suit suit-effect))
          "suit should survive normalization")))

  (testing "draw-3 penalty preserves :penalty-type after normalization"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :penalty :penalty-type :three}))
          normalized (schema/normalize-game game)
          penalty (game/get-effect normalized :penalty)]
      (is (= :three (:penalty-type penalty))
          "penalty-type :three should survive normalization"))))

;; =============================================================================
;; JSON Roundtrip Tests (Bug Fix: String to Keyword Conversion)
;; =============================================================================

(deftest penalty-effect-json-roundtrip
  (testing "penalty-type converts from string to keyword after JSON roundtrip"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :penalty :penalty-type :two}))
          ;; Simulate JSON roundtrip (what happens when saved to DB)
          json-str (json/write-value-as-string game)
          parsed (json/read-value json-str json/keyword-keys-object-mapper)
          ;; Normalize the parsed data
          normalized (schema/normalize-game parsed)
          penalty (game/get-effect normalized :penalty)]
      (is (some? penalty) "penalty effect should exist after roundtrip")
      (is (keyword? (:penalty-type penalty))
          "penalty-type should be a keyword, not a string")
      (is (= :two (:penalty-type penalty))
          "penalty-type should be keyword :two, not string \"two\"")))

  (testing "draw-3 penalty-type converts from string to keyword"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :penalty :penalty-type :three}))
          json-str (json/write-value-as-string game)
          parsed (json/read-value json-str json/keyword-keys-object-mapper)
          normalized (schema/normalize-game parsed)
          penalty (game/get-effect normalized :penalty)]
      (is (keyword? (:penalty-type penalty))
          "penalty-type should be a keyword")
      (is (= :three (:penalty-type penalty))
          "penalty-type should be keyword :three, not string \"three\""))))

(deftest suit-selected-effect-json-roundtrip
  (testing "suit converts from string to keyword after JSON roundtrip"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :suit-selected :suit :hearts}))
          json-str (json/write-value-as-string game)
          parsed (json/read-value json-str json/keyword-keys-object-mapper)
          normalized (schema/normalize-game parsed)
          suit-effect (game/get-effect normalized :suit-selected)]
      (is (some? suit-effect) "suit-selected effect should exist")
      (is (keyword? (:suit suit-effect))
          "suit should be a keyword, not a string")
      (is (= :hearts (:suit suit-effect))
          "suit should be keyword :hearts, not string \"hearts\""))))

(deftest penalty-draw-count-after-reload
  (testing "penalty draw count displays correctly after JSON roundtrip"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :penalty :penalty-type :two}))
          ;; Simulate save/reload cycle
          json-str (json/write-value-as-string game)
          parsed (json/read-value json-str json/keyword-keys-object-mapper)
          normalized (schema/normalize-game parsed)
          penalty-effect (game/get-effect normalized :penalty)
          ;; This is what views.clj does to calculate draw count
          draw-count (case (:penalty-type penalty-effect) :two 2 :three 3 0)]
      (is (= 2 draw-count)
          "draw count should be 2, not 0 (which happens when penalty-type is string)")))

  (testing "draw-3 penalty draw count displays correctly after JSON roundtrip"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :penalty :penalty-type :three}))
          json-str (json/write-value-as-string game)
          parsed (json/read-value json-str json/keyword-keys-object-mapper)
          normalized (schema/normalize-game parsed)
          penalty-effect (game/get-effect normalized :penalty)
          draw-count (case (:penalty-type penalty-effect) :two 2 :three 3 0)]
      (is (= 3 draw-count)
          "draw count should be 3, not 0"))))

;; =============================================================================
;; Ace Blocks Penalty Tests (Bug Fix: No Suit Selection When Blocking)
;; =============================================================================

(deftest ace-blocks-penalty-correctly
  (testing "Ace blocks 2 penalty - clears penalty, sets suit, no suit selection"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "2"})
                   (update :effects conj {:type :penalty :penalty-type :two})
                   (give-card 1 {:suit :clubs :rank "A"}))
          result (game/play-cards-cmd game 1 [{:suit :clubs :rank "A"}])
          new-state (:ok result)]
      ;; Should succeed
      (is (some? new-state) "Ace should successfully block penalty")

      ;; Penalty should be cleared
      (is (nil? (game/get-effect new-state :penalty))
          "Penalty should be cleared after Ace blocks")

      ;; Should NOT have select-suit effect
      (is (nil? (game/get-effect new-state :select-suit))
          "Ace blocking penalty should NOT trigger suit selection")

      ;; Should have suit-selected effect with blocked card's suit
      (let [suit-effect (game/get-effect new-state :suit-selected)]
        (is (some? suit-effect) "Should have suit-selected effect")
        (is (= :hearts (:suit suit-effect))
            "Should preserve suit of blocked card (2♥)")
        (is (true? (:blocked-penalty suit-effect))
            "Should flag that this came from blocking a penalty"))))

  (testing "Ace blocks 3 penalty - preserves suit of blocked 3♦"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :diamonds :rank "3"})
                   (update :effects conj {:type :penalty :penalty-type :three})
                   (give-card 1 {:suit :spades :rank "A"}))
          result (game/play-cards-cmd game 1 [{:suit :spades :rank "A"}])
          new-state (:ok result)]
      (is (some? new-state))
      (is (nil? (game/get-effect new-state :penalty)))
      (is (nil? (game/get-effect new-state :select-suit)))
      (let [suit-effect (game/get-effect new-state :suit-selected)]
        (is (= :diamonds (:suit suit-effect))
            "Should preserve suit of blocked 3♦")
        (is (true? (:blocked-penalty suit-effect))))))

  (testing "Next player can match suit after Ace blocks penalty"
    (let [game (-> (make-3p-test-game)
                   (set-top-card {:suit :hearts :rank "2"})
                   (update :effects conj {:type :penalty :penalty-type :two})
                   (give-card 1 {:suit :clubs :rank "A"})
                   (give-card 2 {:suit :hearts :rank "5"}))
          ;; Player 1 blocks with Ace
          after-block (:ok (game/play-cards-cmd game 1 [{:suit :clubs :rank "A"}]))
          ;; Player 2 plays hearts (matching blocked card's suit)
          result (game/play-cards-cmd after-block 2 [{:suit :hearts :rank "5"}])]
      (is (:ok result) "Player 2 should be able to match blocked card's suit")))

  (testing "Next player can play any 2 after Ace blocks 2 penalty (rank bypass)"
    (let [game (-> (make-3p-test-game)
                   (set-top-card {:suit :hearts :rank "2"})
                   (update :effects conj {:type :penalty :penalty-type :two})
                   (give-card 1 {:suit :clubs :rank "A"})
                   (give-card 2 {:suit :spades :rank "2"})) ;; Different suit!
          after-block (:ok (game/play-cards-cmd game 1 [{:suit :clubs :rank "A"}]))
          result (game/play-cards-cmd after-block 2 [{:suit :spades :rank "2"}])]
      (is (:ok result)
          "Player 2 should be able to play any 2 regardless of suit (rank bypass)")))

  (testing "Next player can play any 3 after Ace blocks 3 penalty (rank bypass)"
    (let [game (-> (make-3p-test-game)
                   (set-top-card {:suit :diamonds :rank "3"})
                   (update :effects conj {:type :penalty :penalty-type :three})
                   (give-card 1 {:suit :clubs :rank "A"})
                   (give-card 2 {:suit :hearts :rank "3"})) ;; Different suit!
          after-block (:ok (game/play-cards-cmd game 1 [{:suit :clubs :rank "A"}]))
          result (game/play-cards-cmd after-block 2 [{:suit :hearts :rank "3"}])]
      (is (:ok result)
          "Player 2 should be able to play any 3 regardless of suit (rank bypass)")))

  (testing "Next player CANNOT play non-matching card after Ace blocks"
    (let [game (-> (make-3p-test-game)
                   (set-top-card {:suit :hearts :rank "2"})
                   (update :effects conj {:type :penalty :penalty-type :two})
                   (give-card 1 {:suit :clubs :rank "A"})
                   (give-card 2 {:suit :spades :rank "7"})) ;; Wrong suit and rank!
          after-block (:ok (game/play-cards-cmd game 1 [{:suit :clubs :rank "A"}]))
          result (game/play-cards-cmd after-block 2 [{:suit :spades :rank "7"}])]
      (is (:error result)
          "Player 2 should NOT be able to play card that doesn't match suit or rank")))

  (testing "Ace without active penalty still triggers suit selection (normal behavior)"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (give-card 1 {:suit :hearts :rank "A"}))
          result (game/play-cards-cmd game 1 [{:suit :hearts :rank "A"}])
          new-state (:ok result)]
      (is (some? new-state))
      ;; Should have select-suit effect (normal Ace behavior)
      (is (some? (game/get-effect new-state :select-suit))
          "Ace without penalty should trigger suit selection")
      ;; Should NOT have suit-selected effect
      (is (nil? (game/get-effect new-state :suit-selected))
          "Should not auto-select suit for normal Ace play")))

  (testing "3 cannot be played when blocking 2 penalty (cross-blocking still prevented)"
    (let [game (-> (make-3p-test-game)
                   (set-top-card {:suit :hearts :rank "2"})
                   (update :effects conj {:type :penalty :penalty-type :two})
                   (give-card 1 {:suit :clubs :rank "A"})
                   (give-card 2 {:suit :diamonds :rank "3"})) ;; 3 doesn't match!
          after-block (:ok (game/play-cards-cmd game 1 [{:suit :clubs :rank "A"}]))
          result (game/play-cards-cmd after-block 2 [{:suit :diamonds :rank "3"}])]
      (is (:error result)
          "3♦ cannot be played when suit requirement is ♥ (blocked 2♥)"))))

;; =============================================================================
;; Kadi + Penalty Blocking Tests
;; =============================================================================

(deftest declare-kadi-while-blocking-penalty
  (testing "declare kadi while blocking a 2-penalty with Ace"
    (let [game (-> (make-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "5"})
                   (give-card 1 {:suit :clubs :rank "A"})
                   (set-top-card {:suit :hearts :rank "2"})
                   (update :effects conj {:type :penalty :penalty-type :two}))
          result (game/play-cards-cmd game 1 [{:suit :clubs :rank "A"}] :declare-kadi? true)
          new-state (:ok result)
          player (game/get-player new-state 1)]
      (is (not (:error result)) "Blocking with Ace while declaring kadi should succeed")
      (is (= :kadi (:status player)) "Player should have kadi status")
      (is (= 1 (count (game/get-hand new-state 1))) "Player should have 1 card remaining")
      (is (nil? (game/get-effect new-state :penalty)) "Penalty should be cleared")))

  (testing "blocking penalty with last Ace and kadi becomes cardless (Ace is special)"
    (let [game (-> (make-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :clubs :rank "A"})
                   (set-top-card {:suit :hearts :rank "2"})
                   (update :effects conj {:type :penalty :penalty-type :two}))
          result (game/play-cards-cmd game 1 [{:suit :clubs :rank "A"}] :declare-kadi? true)
          new-state (:ok result)
          player (game/get-player new-state 1)]
      (is (not (:error result)) "Blocking with last Ace while declaring kadi should succeed")
      (is (= :cardless (:status player)) "Player should become cardless (Ace triggers cardless)")
      (is (empty? (game/get-hand new-state 1)) "Player should have no cards")
      (is (nil? (game/get-effect new-state :penalty)) "Penalty should be cleared"))))

;; =============================================================================
;; Kadi Finishing Tests
;; =============================================================================

(deftest kadi-finishing-feature
  (testing "valid single card finish with Kadi declaration"
    (let [game (-> (make-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "5"})
                   (set-top-card {:suit :hearts :rank "7"}))
          result (game/play-cards-cmd game 1 [{:suit :hearts :rank "5"}] :declare-kadi? true)
          final-state (:ok result)]
      (is (not (:error result)) "Play should succeed")
      (is (= :finished (:status final-state)) "Game should be finished")
      (is (= 1 (:winner final-state)) "Player 1 should be the winner")
      (is (empty? (game/get-hand final-state 1)) "Player 1 should have empty hand")))

  (testing "valid combo finish with Kadi declaration"
    (let [game (-> (make-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "7"})
                   (give-card 1 {:suit :diamonds :rank "7"})
                   (give-card 1 {:suit :clubs :rank "7"})
                   (set-top-card {:suit :hearts :rank "5"}))
          result (game/play-cards-cmd game 1 [{:suit :hearts :rank "7"}
                                              {:suit :diamonds :rank "7"}
                                              {:suit :clubs :rank "7"}]
                                      :declare-kadi? true)
          final-state (:ok result)]
      (is (not (:error result)) "Combo play should succeed")
      (is (= :finished (:status final-state)) "Game should be finished")
      (is (= 1 (:winner final-state)) "Player 1 should be the winner")))

  (testing "invalid finish with King as last card becomes cardless"
    (let [game (-> (make-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "K"})
                   (set-top-card {:suit :hearts :rank "7"}))
          result (game/play-cards-cmd game 1 [{:suit :hearts :rank "K"}] :declare-kadi? true)
          final-state (:ok result)
          player (game/get-player final-state 1)]
      (is (not (:error result)) "Play should succeed")
      (is (= :cardless (:status player)) "Player should become cardless, not win")
      (is (not= :finished (:status final-state)) "Game should not be finished")))

  (testing "invalid finish with Jack as last card becomes cardless"
    (let [game (-> (make-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "J"})
                   (set-top-card {:suit :hearts :rank "7"}))
          result (game/play-cards-cmd game 1 [{:suit :hearts :rank "J"}] :declare-kadi? true)
          final-state (:ok result)
          player (game/get-player final-state 1)]
      (is (= :cardless (:status player)) "Playing Jack as last card should result in cardless")))

  (testing "invalid finish with 2 as last card becomes cardless"
    (let [game (-> (make-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "2"})
                   (set-top-card {:suit :hearts :rank "7"}))
          result (game/play-cards-cmd game 1 [{:suit :hearts :rank "2"}] :declare-kadi? true)
          final-state (:ok result)
          player (game/get-player final-state 1)]
      (is (= :cardless (:status player)) "Playing 2 as last card should result in cardless")))

  (testing "invalid finish with 3 as last card becomes cardless"
    (let [game (-> (make-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "3"})
                   (set-top-card {:suit :hearts :rank "7"}))
          result (game/play-cards-cmd game 1 [{:suit :hearts :rank "3"}] :declare-kadi? true)
          final-state (:ok result)
          player (game/get-player final-state 1)]
      (is (= :cardless (:status player)) "Playing 3 as last card should result in cardless")))

  (testing "empty hand without Kadi declaration stays normal (no penalty)"
    (let [game (-> (make-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "5"})
                   (set-top-card {:suit :hearts :rank "7"}))
          ;; Play without declare-kadi? (defaults to false)
          result (game/play-cards-cmd game 1 [{:suit :hearts :rank "5"}])
          final-state (:ok result)
          player (game/get-player final-state 1)]
      (is (not (:error result)) "Play should succeed")
      (is (= :normal (:status player)) "Player stays normal (Kadi is optional, not required)")
      (is (not= :finished (:status final-state)) "Game should not be finished (no win without Kadi)")))

  (testing "voluntary draw with maintain-kadi keeps player in Kadi"
    (let [game (-> (make-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "5"})
                   (game/update-player 1 #(assoc % :status :kadi)))
          result (game/draw-card-cmd game 1 :maintain-kadi? true)
          final-state (:ok result)
          player (game/get-player final-state 1)]
      (is (not (:error result)) "Draw should succeed")
      (is (= :kadi (:status player)) "Player should remain in Kadi status")
      (is (= 2 (count (game/get-hand final-state 1))) "Player should now have 2 cards")))

  (testing "voluntary draw without maintain-kadi resets to normal"
    (let [game (-> (make-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "5"})
                   (game/update-player 1 #(assoc % :status :kadi)))
          result (game/draw-card-cmd game 1 :maintain-kadi? false)
          final-state (:ok result)
          player (game/get-player final-state 1)]
      (is (not (:error result)) "Draw should succeed")
      (is (= :normal (:status player)) "Player should be reset to normal status")))

  (testing "accept penalty while in Kadi auto-resets to normal"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "2"})
                   (update :effects conj {:type :penalty :penalty-type :two})
                   (game/update-player 2 #(assoc % :status :kadi))
                   (game/advance-turn)) ;; Advance turn to player 2
          ;; Player 2 is in Kadi and accepts penalty
          result (game/accept-penalty-cmd game 2)
          final-state (:ok result)
          player (game/get-player final-state 2)]
      (is (not (:error result)) "Accept penalty should succeed")
      (is (= :normal (:status player)) "Player should be auto-reset to normal (miscalculated)"))))

;; =============================================================================
;; Deck Recycling and Anomaly Skip Tests (Phase 1)
;; =============================================================================

(deftest draw-card-empty-deck-recycles
  (testing "draw from empty deck triggers recycle and succeeds"
    (let [game (-> (make-test-game)
                   (assoc-in [:zones :deck] [])
                   (assoc-in [:zones :played-stack]
                             [{:suit :hearts :rank "5"}
                              {:suit :clubs :rank "6"}
                              {:suit :diamonds :rank "7"}]))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/draw-card-cmd game player-id)]
      (is (:ok result) "Draw should succeed after recycling")
      (is (= (inc hand-before) (count (game/get-hand (:ok result) player-id)))
          "Player should draw 1 card from recycled deck")
      ;; Verify deck was recycled (should have 2 cards, played stack keeps top card)
      (is (pos? (count (get-in (:ok result) [:zones :deck])))
          "Deck should have cards after recycling"))))

(deftest draw-card-empty-deck-and-stack-anomaly-skip
  (testing "draw when deck empty AND played stack has only 1 card → anomaly skip"
    (let [game (-> (make-test-game)
                   (assoc-in [:zones :deck] [])
                   (assoc-in [:zones :played-stack] [{:suit :hearts :rank "5"}]))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          idx-before (game/current-player-index game)
          result (game/draw-card-cmd game player-id)]
      (is (:ok result) "Should succeed even with no cards to draw")
      (is (= hand-before (count (game/get-hand (:ok result) player-id)))
          "Player should not draw any cards (anomaly skip)")
      (is (not= idx-before (game/current-player-index (:ok result)))
          "Turn should still advance to next player"))))

(deftest accept-penalty-recycles-mid-draw
  (testing "accept-penalty recycles deck mid-draw if needed"
    (let [game (-> (make-test-game)
                   ;; Set up deck with only 1 card (penalty requires 2)
                   (assoc-in [:zones :deck] [{:suit :spades :rank "10"}])
                   (assoc-in [:zones :played-stack]
                             [{:suit :hearts :rank "5"}
                              {:suit :clubs :rank "6"}
                              {:suit :diamonds :rank "7"}])
                   (update :effects conj {:type :penalty :penalty-type :two}))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/accept-penalty-cmd game player-id)]
      (is (:ok result) "Accept penalty should succeed")
      ;; Player should draw 2 cards (1 from original deck, 1 from recycled)
      (is (= (+ hand-before 2) (count (game/get-hand (:ok result) player-id)))
          "Player should draw 2 cards despite deck running out mid-draw"))))

(deftest apply-action-draw-card-recycles
  (testing "apply-action :draw-card recycles when deck is empty (event replay)"
    (let [game (-> (make-test-game)
                   (assoc-in [:zones :deck] [])
                   (assoc-in [:zones :played-stack]
                             [{:suit :hearts :rank "5"}
                              {:suit :clubs :rank "6"}]))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          result (game/apply-action game {:type :draw-card
                                          :player-id player-id})]
      (is (= (inc hand-before) (count (game/get-hand result player-id)))
          "Player should draw from recycled deck during event replay"))))

(deftest apply-action-draw-card-anomaly-skip
  (testing "apply-action :draw-card skips when deck cannot be recycled (event replay)"
    (let [game (-> (make-test-game)
                   (assoc-in [:zones :deck] [])
                   (assoc-in [:zones :played-stack] [{:suit :hearts :rank "5"}]))
          player-id (game/current-player-id game)
          hand-before (count (game/get-hand game player-id))
          idx-before (game/current-player-index game)
          result (game/apply-action game {:type :draw-card
                                          :player-id player-id})]
      (is (= hand-before (count (game/get-hand result player-id)))
          "Player should not draw (anomaly skip)")
      (is (not= idx-before (game/current-player-index result))
          "Turn should advance even with no draw"))))

;; =============================================================================
;; Cardless Player Tests (Phase 2)
;; =============================================================================

(deftest cardless-player-forced-to-draw
  (testing "cardless player cannot play cards"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "5"})
                   (game/update-player 1 #(assoc % :status :cardless))
                   (give-card 1 {:suit :hearts :rank "9"}))
          result (game/play-cards-cmd game 1 [{:suit :hearts :rank "9"}])]
      (is (:error result) "Cardless player should not be able to play")
      (is (= "You must draw a card first (cardless)" (:error result)))))

  (testing "cardless player can draw and returns to normal"
    (let [game (-> (make-test-game)
                   (game/update-player 1 #(assoc % :status :cardless)))
          result (game/draw-card-cmd game 1)
          final-state (:ok result)
          player (game/get-player final-state 1)]
      (is (:ok result) "Cardless player should be able to draw")
      (is (= :normal (:status player)) "Player should return to normal after drawing")
      (is (= 5 (count (game/get-hand final-state 1))) "Player should have 5 cards (4 + 1 drawn)")))

  (testing "cardless player draw via apply-action returns to normal"
    (let [game (-> (make-test-game)
                   (game/update-player 1 #(assoc % :status :cardless)))
          result (game/apply-action game {:type :draw-card :player-id 1})
          player (game/get-player result 1)]
      (is (= :normal (:status player)) "Player should return to normal after drawing (event replay)")))

  (testing "cardless player facing penalty cannot draw (must accept penalty)"
    (let [game (-> (make-test-game)
                   (set-top-card {:suit :hearts :rank "3"})
                   (game/update-player 1 #(assoc % :status :cardless))
                   (update :effects conj {:type :penalty :penalty-type :three}))
          result (game/draw-card-cmd game 1)]
      (is (:error result) "Cardless player should not be able to draw when penalty is active")
      (is (= "Must accept penalty first" (:error result))))))

;; =============================================================================
;; Write-path parity (candidate 1 pilot)
;; =============================================================================

(deftest play-cards-cmd-apply-parity-test
  (testing "cmd and apply-action produce identical states for identical input"
    (let [card {:suit :hearts :rank "5"}
          state (-> (make-test-game)
                    (clear-hand 1)
                    (give-card 1 card)
                    (set-top-card {:suit :hearts :rank "9"}))
          via-cmd (:ok (game/play-cards-cmd state 1 [card]))
          via-apply (game/apply-action state {:type :play-cards
                                              :player-id 1
                                              :cards [card]
                                              :declare-kadi? false
                                              :timestamp (java.time.Instant/parse "2026-01-01T00:00:00Z")})]
      (is (some? via-cmd) "cmd should succeed")
      (is (= (dissoc via-apply :meta) (dissoc via-cmd :meta))
          "states should match apart from :meta/updated-at timestamps"))))

(deftest normalize-cards-idempotent-test
  (testing "normalize-cards is a no-op on handler-parsed cards"
    (let [parsed (cards/id->card "5-hearts")]
      (is (= {:suit :hearts :rank "5"} parsed))
      (is (= [parsed] (vec (schema/normalize-cards [parsed])))))))

(deftest all-actions-cmd-apply-parity-test
  (testing "every cmd delegates to its apply-action method: identical states"
    (let [ts (java.time.Instant/parse "2026-01-01T00:00:00Z")
          no-meta #(dissoc % :meta)]
      (testing "draw-card"
        (let [state (make-test-game)
              via-cmd (:ok (game/draw-card-cmd state 1))
              via-apply (game/apply-action state {:type :draw-card :player-id 1
                                                  :maintain-kadi? false :timestamp ts})]
          (is (some? via-cmd))
          (is (= (no-meta via-apply) (no-meta via-cmd)))))
      (testing "select-suit"
        (let [state (-> (make-test-game)
                        (update :effects conj {:type :select-suit}))
              via-cmd (:ok (game/select-suit-cmd state :hearts))
              via-apply (game/apply-action state {:type :select-suit :suit :hearts
                                                  :timestamp ts})]
          (is (some? via-cmd))
          (is (= (no-meta via-apply) (no-meta via-cmd)))))
      (testing "accept-penalty"
        (let [state (-> (make-test-game)
                        (update :effects conj {:type :penalty :penalty-type :two}))
              via-cmd (:ok (game/accept-penalty-cmd state 1))
              via-apply (game/apply-action state {:type :accept-penalty :player-id 1
                                                  :timestamp ts})]
          (is (some? via-cmd))
          (is (= (no-meta via-apply) (no-meta via-cmd)))))
      (testing "answer-question"
        (let [state (-> (make-test-game)
                        (update :effects conj {:type :awaiting-answer}))
              via-cmd (:ok (game/answer-question-cmd state 1))
              via-apply (game/apply-action state {:type :answer-question :player-id 1
                                                  :timestamp ts})]
          (is (some? via-cmd))
          (is (= (no-meta via-apply) (no-meta via-cmd)))))
      (testing "play-cards while kadi declared still matches"
        (let [card {:suit :hearts :rank "5"}
              state (-> (make-test-game)
                        (clear-hand 1)
                        (give-card 1 card)
                        (set-top-card {:suit :hearts :rank "9"})
                        (game/update-player 1 #(assoc % :status :kadi)))
              via-cmd (:ok (game/play-cards-cmd state 1 [card]))
              via-apply (game/apply-action state {:type :play-cards :player-id 1
                                                  :cards [card] :declare-kadi? false
                                                  :timestamp ts})]
          (is (some? via-cmd))
          (is (= (no-meta via-apply) (no-meta via-cmd))))))))

(deftest migrated-cmd-error-branches-test
  (testing "draw-card during penalty must accept first"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :penalty :penalty-type :two}))
          result (game/draw-card-cmd game (game/current-player-id game))]
      (is (:error result))
      (is (= "Must accept penalty first" (:error result)))))

  (testing "draw-card when game not live"
    (let [game (-> (game/new-game "TEST")
                   (game/add-player {:id 1 :name "Alice"})
                   (game/add-player {:id 2 :name "Bob"}))]
      (is (:error (game/draw-card-cmd game 1)))))

  (testing "play-cards rejects unknown player and empty play"
    (let [game (make-test-game)
          card {:suit :hearts :rank "5"}
          game (give-card game 1 card)]
      (is (:error (game/play-cards-cmd game 99 [card]))
          "Player not in game")
      (is (:error (game/play-cards-cmd game 1 []))
          "Must play at least one card")))

  (testing "answer-question rejects wrong turn"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :awaiting-answer}))
          other-id (:id (second (:players game)))]
      (is (:error (game/answer-question-cmd game other-id)))))

  (testing "accept-penalty when game not live"
    (let [game (-> (make-test-game)
                   (assoc :status :finished)
                   (update :effects conj {:type :penalty :penalty-type :two}))]
      (is (:error (game/accept-penalty-cmd game (game/current-player-id game))))))

  (testing "select-suit accepts string suit via delegated normalization"
    (let [game (-> (make-test-game)
                   (update :effects conj {:type :select-suit}))
          result (game/select-suit-cmd game "hearts")]
      (is (:ok result) "String suit should normalize through the delegated path")
      (is (game/has-effect? (:ok result) :suit-selected)))))

(deftest accept-penalty-turn-test
  (testing "only the penalized (current) player can accept, 3-player game"
    (let [game (-> (make-3p-test-game)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "2"})
                   (set-top-card {:suit :hearts :rank "9"}))
          played (:ok (game/play-cards-cmd game 1 [{:suit :hearts :rank "2"}]))
          _ (assert played "setup play should succeed")
          penalized (game/current-player-id played)
          hand-before (count (game/get-hand played penalized))]
      (is (= 2 penalized) "Player 2 is penalized after player 1 plays a 2")
      (is (:error (game/accept-penalty-cmd played 3))
          "Player 3 (not current) must be rejected")
      (let [result (:ok (game/accept-penalty-cmd played penalized))]
        (is (some? result) "Penalized player accepts")
        (is (= (+ hand-before 2) (count (game/get-hand result penalized)))
            "Penalized player draws 2")
        (is (not (game/has-effect? result :penalty)) "Penalty cleared")
        (is (= 3 (game/current-player-id result)) "Turn advances to player 3")))))

(deftest accept-penalty-direction-test
  (testing "penalty follows turn direction, counter-clockwise leg"
    (let [game (-> (make-3p-test-game)
                   (game/reverse-direction)
                   (clear-hand 1)
                   (give-card 1 {:suit :hearts :rank "2"})
                   (set-top-card {:suit :hearts :rank "9"}))
          played (:ok (game/play-cards-cmd game 1 [{:suit :hearts :rank "2"}]))
          _ (assert played "setup play should succeed")
          penalized (game/current-player-id played)
          hand-before (count (game/get-hand played penalized))]
      (is (= :counter-clockwise (:direction played)))
      (is (= 3 penalized) "Counter-clockwise from player 1 penalizes player 3")
      (is (:error (game/accept-penalty-cmd played 2))
          "Player 2 (not current) must be rejected")
      (let [result (:ok (game/accept-penalty-cmd played penalized))]
        (is (some? result) "Penalized player accepts")
        (is (= (+ hand-before 2) (count (game/get-hand result penalized)))
            "Penalized player draws 2")
        (is (not (game/has-effect? result :penalty)) "Penalty cleared")
        (is (= 2 (game/current-player-id result))
            "Turn advances counter-clockwise to player 2")))))

(deftest play-view-test
  (testing "normal turn view-model"
    (let [vm (game/play-view (make-test-game) 1)]
      (is (true? (:my-turn? vm)))
      (is (= :play (:mode vm)))
      (is (= :none (get-in vm [:banner :kind])))
      (is (false? (:penalty? vm)))
      (is (false? (:poll? vm)))
      (is (= "your turn" (:status-text (first (:players vm)))))))

  (testing "waiting view-model polls"
    (let [vm (game/play-view (make-test-game) 2)]
      (is (false? (:my-turn? vm)))
      (is (= :waiting (:mode vm)))
      (is (true? (:poll? vm)))
      (is (= "4 cards" (:status-text (second (:players vm)))))))

  (testing "penalty banner"
    (let [state (-> (make-test-game)
                    (update :effects conj {:type :penalty :penalty-type :two}))
          mine (game/play-view state 1)
          theirs (game/play-view state 2)]
      (is (= :penalty (get-in mine [:banner :kind])))
      (is (= 2 (:penalty-draw-count mine)))
      (is (true? (:penalty? mine)))
      (is (clojure.string/includes? (get-in mine [:banner :text]) "Play two to block"))
      (is (clojure.string/includes? (get-in theirs [:banner :text]) "Waiting for"))))

  (testing "select-suit and suit-selected banners"
    (let [selecting (-> (make-test-game)
                        (update :effects conj {:type :select-suit}))
          vm (game/play-view selecting 1)]
      (is (= :select-suit (get-in vm [:banner :kind])))
      (is (= :select-suit (:mode vm)))
      (is (= "Ace played! Select a suit below." (get-in vm [:banner :text]))))
    (let [selected (-> (make-test-game)
                       (update :effects conj {:type :suit-selected :suit :hearts}))
          vm (game/play-view selected 2)]
      (is (= :suit-selected (get-in vm [:banner :kind])))
      (is (= :hearts (get-in vm [:banner :suit])))))

  (testing "awaiting-answer and cardless modes"
    (let [vm (game/play-view (-> (make-test-game)
                                  (update :effects conj {:type :awaiting-answer})) 1)]
      (is (= :answer (:mode vm)))
      (is (= :awaiting-answer (get-in vm [:banner :kind]))))
    (let [vm (game/play-view (-> (make-test-game)
                                  (game/update-player 1 #(assoc % :status :cardless))) 1)]
      (is (= :cardless (:mode vm)))))

  (testing "finished game names the winner"
    (let [state (-> (make-test-game)
                    (clear-hand 1)
                    (give-card 1 {:suit :hearts :rank "5"})
                    (set-top-card {:suit :hearts :rank "9"})
                    (game/update-player 1 #(assoc % :status :kadi)))
          played (:ok (game/play-cards-cmd state 1 [{:suit :hearts :rank "5"}]))
          vm (game/play-view played 1)]
      (is (= :finished (:mode vm)))
      (is (true? (:finished? vm)))
      (is (= "Alice" (:winner-name vm)))
      (is (false? (:poll? vm))))))
