(ns kadi.validation-test
  (:require [clojure.test :refer [deftest testing is]]
            [kadi.validation :as validation]
            [kadi.cards :as cards]))

;; =============================================================================
;; valid-combo? Tests
;; =============================================================================

(deftest valid-combo-test
  (testing "single card is always a valid combo"
    (is (validation/valid-combo? [(cards/make-card :hearts "5")])))

  (testing "same-rank combo is valid (non-king)"
    (is (validation/valid-combo? [(cards/make-card :hearts "5") (cards/make-card :clubs "5")])))

  (testing "Q+8 combo is valid (both are question cards)"
    (is (validation/valid-combo? [(cards/make-card :hearts "Q") (cards/make-card :clubs "8")])))

  (testing "king cannot combine"
    (is (not (validation/valid-combo? [(cards/make-card :hearts "K") (cards/make-card :clubs "K")]))))

  (testing "mixed ranks are invalid"
    (is (not (validation/valid-combo? [(cards/make-card :hearts "5") (cards/make-card :clubs "6")]))))

  (testing "multiple aces are valid"
    (is (validation/valid-combo? [(cards/make-card :hearts "A") (cards/make-card :clubs "A")])))

  (testing "multiple jacks are valid"
    (is (validation/valid-combo? [(cards/make-card :hearts "J") (cards/make-card :clubs "J")])))

  (testing "multiple twos are valid"
    (is (validation/valid-combo? [(cards/make-card :hearts "2") (cards/make-card :clubs "2")])))

  (testing "multiple threes are valid"
    (is (validation/valid-combo? [(cards/make-card :hearts "3") (cards/make-card :clubs "3")]))))

;; =============================================================================
;; valid-penalty-block? Tests
;; =============================================================================

(deftest valid-penalty-block-test
  (testing "2 blocks 2 penalty"
    (is (validation/valid-penalty-block? [(cards/make-card :hearts "2")] :two)))

  (testing "3 blocks 3 penalty"
    (is (validation/valid-penalty-block? [(cards/make-card :hearts "3")] :three)))

  (testing "Ace blocks 2 penalty"
    (is (validation/valid-penalty-block? [(cards/make-card :hearts "A")] :two)))

  (testing "Ace blocks 3 penalty"
    (is (validation/valid-penalty-block? [(cards/make-card :hearts "A")] :three)))

  (testing "cross-blocking prevented: 2 cannot block 3"
    (is (not (validation/valid-penalty-block? [(cards/make-card :hearts "2")] :three))))

  (testing "cross-blocking prevented: 3 cannot block 2"
    (is (not (validation/valid-penalty-block? [(cards/make-card :hearts "3")] :two))))

  (testing "regular card cannot block penalty"
    (is (not (validation/valid-penalty-block? [(cards/make-card :hearts "5")] :two)))))

;; =============================================================================
;; first-card-matches-top? Tests
;; =============================================================================

(deftest first-card-matches-top-test
  (let [;; Mock state with no active effects for standard matching tests
        empty-state {:effects []}]
    (testing "ace always matches"
      (is (validation/first-card-matches-top?
           [(cards/make-card :clubs "A")]
           (cards/make-card :hearts "5")
           nil
           empty-state)))

    (testing "action-suit enforcement"
      (is (validation/first-card-matches-top?
           [(cards/make-card :hearts "5")]
           (cards/make-card :clubs "A")
           :hearts
           empty-state))
      (is (not (validation/first-card-matches-top?
                [(cards/make-card :clubs "5")]
                (cards/make-card :clubs "A")
                :hearts
                empty-state))))

    (testing "suit matching"
      (is (validation/first-card-matches-top?
           [(cards/make-card :hearts "9")]
           (cards/make-card :hearts "5")
           nil
           empty-state)))

    (testing "rank matching"
      (is (validation/first-card-matches-top?
           [(cards/make-card :clubs "5")]
           (cards/make-card :hearts "5")
           nil
           empty-state)))

    (testing "no match"
      (is (not (validation/first-card-matches-top?
                [(cards/make-card :clubs "9")]
                (cards/make-card :hearts "5")
                nil
                empty-state))))))

;; =============================================================================
;; question-sequence-valid? Tests
;; =============================================================================

(deftest question-sequence-valid-test
  (testing "single question card is valid"
    (is (validation/question-sequence-valid? [(cards/make-card :hearts "Q")])))

  (testing "Q chain matching by suit"
    (is (validation/question-sequence-valid?
         [(cards/make-card :hearts "Q") (cards/make-card :hearts "8")])))

  (testing "Q+8 combo matching by suit"
    (is (validation/question-sequence-valid?
         [(cards/make-card :hearts "Q") (cards/make-card :hearts "8")]))))

;; =============================================================================
;; validate-play Integration Tests
;; =============================================================================

(deftest validate-play-test
  (let [base-state {:status :live
                    :players [{:id 1 :name "Alice" :status :normal
                               :hand [(cards/make-card :hearts "5") (cards/make-card :clubs "A")]}
                              {:id 2 :name "Bob" :status :normal
                               :hand [(cards/make-card :diamonds "7")]}]
                    :current-player-index 0 :direction :clockwise
                    :zones {:deck [(cards/make-card :spades "10")]
                            :played-stack [(cards/make-card :hearts "9")]
                            :hands {1 [(cards/make-card :hearts "5") (cards/make-card :clubs "A")]
                                    2 [(cards/make-card :diamonds "7")]}}
                    :effects []}]

    (testing "valid play by suit match"
      (let [result (validation/validate-play base-state 1 [(cards/make-card :hearts "5")])]
        (is (:valid? result))))

    (testing "valid play with ace (always matches)"
      (let [result (validation/validate-play base-state 1 [(cards/make-card :clubs "A")])]
        (is (:valid? result))))

    (testing "not your turn"
      (let [result (validation/validate-play base-state 2 [(cards/make-card :diamonds "7")])]
        (is (not (:valid? result)))
        (is (= "Not your turn" (:reason result)))))

    (testing "card doesn't match top"
      (let [state (assoc-in base-state [:players 0 :hand]
                            [(cards/make-card :clubs "6")])
            result (validation/validate-play state 1 [(cards/make-card :clubs "6")])]
        (is (not (:valid? result)))))

    (testing "player doesn't have the card"
      (let [result (validation/validate-play base-state 1 [(cards/make-card :spades "K")])]
        (is (not (:valid? result)))
        (is (= "You don't have those cards" (:reason result)))))

    (testing "penalty scenario - must block or accept"
      (let [state (-> base-state
                      (assoc :effects [{:type :penalty :penalty-type :two}])
                      (assoc-in [:players 0 :hand] [(cards/make-card :hearts "5")]))
            result (validation/validate-play state 1 [(cards/make-card :hearts "5")])]
        (is (not (:valid? result)))
        (is (= "Must block penalty with matching card or Ace" (:reason result)))))

    (testing "awaiting-answer - cannot play normal cards"
      (let [state (-> base-state
                      (assoc :effects [{:type :awaiting-answer}]))
            result (validation/validate-play state 1 [(cards/make-card :hearts "5")])]
        (is (not (:valid? result)))
        (is (= "Must draw to answer the question first" (:reason result)))))))

;; =============================================================================
;; Q+answer validate-play Tests (Bug 1 regression tests)
;; =============================================================================

(deftest validate-play-question-with-answer-test
  (let [base-state {:status :live
                    :players [{:id 1 :name "Alice" :status :normal
                               :hand [(cards/make-card :hearts "Q") (cards/make-card :hearts "5")
                                      (cards/make-card :hearts "2") (cards/make-card :hearts "8")
                                      (cards/make-card :hearts "K")]}
                              {:id 2 :name "Bob" :status :normal
                               :hand [(cards/make-card :diamonds "7")]}]
                    :current-player-index 0 :direction :clockwise
                    :zones {:deck [(cards/make-card :spades "10")]
                            :played-stack [(cards/make-card :hearts "9")]
                            :hands {1 [(cards/make-card :hearts "Q") (cards/make-card :hearts "5")
                                       (cards/make-card :hearts "2") (cards/make-card :hearts "8")
                                       (cards/make-card :hearts "K")]
                                    2 [(cards/make-card :diamonds "7")]}}
                    :effects []}]

    (testing "Q + matching answer card is accepted"
      (let [result (validation/validate-play base-state 1
                                             [(cards/make-card :hearts "Q") (cards/make-card :hearts "5")])]
        (is (:valid? result))))

    (testing "Q + penalty answer card is accepted"
      (let [result (validation/validate-play base-state 1
                                             [(cards/make-card :hearts "Q") (cards/make-card :hearts "2")])]
        (is (:valid? result))))

    (testing "8 + matching answer card is accepted"
      (let [state (-> base-state
                      (assoc-in [:zones :played-stack] [(cards/make-card :clubs "8")]))
            result (validation/validate-play state 1
                                             [(cards/make-card :hearts "8") (cards/make-card :hearts "5")])]
        (is (:valid? result))))

    (testing "Q + non-matching answer card is rejected"
      (let [state (-> base-state
                      (update-in [:players 0 :hand] conj (cards/make-card :diamonds "7"))
                      (update-in [:zones :hands 1] conj (cards/make-card :diamonds "7")))
            result (validation/validate-play state 1
                                             [(cards/make-card :hearts "Q") (cards/make-card :diamonds "7")])]
        (is (not (:valid? result)))
        (is (= "Answer must match the question" (:reason result)))))

    (testing "Q + King answer is accepted (matches suit)"
      (let [result (validation/validate-play base-state 1
                                             [(cards/make-card :hearts "Q") (cards/make-card :hearts "K")])]
        (is (:valid? result))))))

;; =============================================================================
;; answer-valid? Tests (Bug 3 regression tests)
;; =============================================================================

(deftest answer-valid-test
  (testing "no answers is valid"
    (is (validation/answer-valid? [(cards/make-card :hearts "Q")])))

  (testing "single matching answer is valid"
    (is (validation/answer-valid? [(cards/make-card :hearts "Q") (cards/make-card :hearts "5")])))

  (testing "non-matching answer is rejected"
    (is (not (validation/answer-valid?
              [(cards/make-card :hearts "Q") (cards/make-card :clubs "7")]))))

  (testing "multiple same-rank answers are valid"
    (is (validation/answer-valid?
         [(cards/make-card :hearts "Q") (cards/make-card :hearts "5") (cards/make-card :clubs "5")])))

  (testing "multiple different-rank answers are rejected"
    (is (not (validation/answer-valid?
              [(cards/make-card :hearts "Q") (cards/make-card :hearts "5") (cards/make-card :hearts "6")]))))

  (testing "answer must match last question"
    (is (not (validation/answer-valid?
              [(cards/make-card :hearts "Q") (cards/make-card :clubs "5")])))))

;; =============================================================================
;; question-sequence-valid? Additional Tests
;; =============================================================================

(deftest question-sequence-non-matching-test
  (testing "Q+8 that don't match by suit or rank is rejected"
    (is (not (validation/question-sequence-valid?
              [(cards/make-card :hearts "Q") (cards/make-card :clubs "8")])))))

;; =============================================================================
;; Bug Regression Tests - suit-selected with hands in :zones (Bug #1 + Bug #2)
;; =============================================================================

(deftest validate-play-with-zones-hands-test
  (testing "Bug #1: validate-play should work with hands in :zones (no :hand on player)"
    (let [state {:status :live
                 :players [{:id 1 :name "Alice" :status :normal}]
                 :current-player-index 0 :direction :clockwise
                 :zones {:deck []
                         :played-stack [(cards/make-card :hearts "9")]
                         :hands {1 [(cards/make-card :hearts "5")]}}
                 :effects []}
          result (validation/validate-play state 1 [(cards/make-card :hearts "5")])]
      (is (:valid? result) "Should validate cards from :zones/:hands")))

  (testing "Bug #2: action-suit as string should work (from JSON database)"
    (let [state {:status :live
                 :players [{:id 1 :name "Alice" :status :normal}]
                 :current-player-index 0 :direction :clockwise
                 :zones {:deck []
                         :played-stack [(cards/make-card :spades "A")]
                         :hands {1 [(cards/make-card :diamonds "5")]}}
                 :effects [{:type :suit-selected :suit "diamonds"}]}
          result (validation/validate-play state 1 [(cards/make-card :diamonds "5")])]
      (is (:valid? result) "Should handle string suit from database")))

  (testing "Bug #1 + Bug #2: Combined - 8+2 with string suit and zones hands"
    (let [state {:status :live
                 :players [{:id 1 :name "Alice" :status :normal}]
                 :current-player-index 0 :direction :clockwise
                 :zones {:deck []
                         :played-stack [(cards/make-card :spades "A")]
                         :hands {1 [(cards/make-card :diamonds "8")
                                    (cards/make-card :diamonds "2")]}}
                 :effects [{:type :suit-selected :suit "diamonds"}]}
          result (validation/validate-play state 1
                                           [(cards/make-card :diamonds "8")
                                            (cards/make-card :diamonds "2")])]
      (is (:valid? result) "8+2 should work with string suit and zones hands")))

  (testing "action-suit as keyword still works (backward compat)"
    (let [state {:status :live
                 :players [{:id 1 :name "Alice" :status :normal}]
                 :current-player-index 0 :direction :clockwise
                 :zones {:deck []
                         :played-stack [(cards/make-card :spades "A")]
                         :hands {1 [(cards/make-card :diamonds "5")]}}
                 :effects [{:type :suit-selected :suit :diamonds}]}
          result (validation/validate-play state 1 [(cards/make-card :diamonds "5")])]
      (is (:valid? result) "Should still work with keyword suit"))))

(deftest game-86AYWN-regression-test
  (testing "Real-world regression: 8-diamonds + 2-diamonds with suit-selected effect"
    (let [;; Simulates actual game state from DB after JSON deserialization
          state {:status :live
                 :players [{:id 2 :name "mo" :status :normal}
                           {:id 1 :name "kevin" :status :normal}]
                 :current-player-index 0 :direction :clockwise
                 :zones {:deck [(cards/make-card :spades "8")]
                         :played-stack [(cards/make-card :spades "A")]
                         :hands {2 [(cards/make-card :diamonds "J")
                                    (cards/make-card :diamonds "8")
                                    (cards/make-card :diamonds "2")
                                    (cards/make-card :hearts "A")]
                                 1 [(cards/make-card :clubs "J")]}}
                 :effects [{:type :suit-selected :suit "diamonds"}]}
          ;; Player 2 (mo) tries to play 8-diamonds + 2-diamonds
          result (validation/validate-play state 2
                                           [(cards/make-card :diamonds "8")
                                            (cards/make-card :diamonds "2")])]
      (is (:valid? result)
          "8-diamonds + 2-diamonds should be valid with suit-selected diamonds"))))

;; =============================================================================
;; Cardless Player Tests (Phase 2)
;; =============================================================================

(deftest cardless-player-cannot-play
  (testing "cardless player's play attempt is rejected"
    (let [state {:status :live
                 :players [{:id 1 :name "Alice" :status :cardless}
                           {:id 2 :name "Bob" :status :normal}]
                 :current-player-index 0 :direction :clockwise
                 :zones {:deck [(cards/make-card :spades "10")]
                         :played-stack [(cards/make-card :hearts "9")]
                         :hands {1 [(cards/make-card :hearts "5")]
                                 2 [(cards/make-card :diamonds "7")]}}
                 :effects []}
          result (validation/validate-play state 1 [(cards/make-card :hearts "5")])]
      (is (not (:valid? result)) "Cardless player should not be able to play")
      (is (= "You must draw a card first (cardless)" (:reason result))
          "Error message should explain cardless restriction"))))

(deftest cardless-player-normal-after-draw
  (testing "player is no longer cardless after drawing"
    (let [state {:status :live
                 :players [{:id 1 :name "Alice" :status :normal}
                           {:id 2 :name "Bob" :status :normal}]
                 :current-player-index 0 :direction :clockwise
                 :zones {:deck [(cards/make-card :spades "10")]
                         :played-stack [(cards/make-card :hearts "9")]
                         :hands {1 [(cards/make-card :hearts "5")]
                                 2 [(cards/make-card :diamonds "7")]}}
                 :effects []}
          result (validation/validate-play state 1 [(cards/make-card :hearts "5")])]
      (is (:valid? result) "Normal player can play cards"))))

;; =============================================================================
;; Ace as Starting Card Tests
;; =============================================================================

(deftest ace-starting-card-any-card-playable
  (testing "any card can be played when Ace is the starting card (no action-suit)"
    (let [state {:status :live
                 :players [{:id 1 :name "Alice" :status :normal}
                           {:id 2 :name "Bob" :status :normal}]
                 :current-player-index 0 :direction :clockwise
                 :zones {:deck [(cards/make-card :spades "10")]
                         :played-stack [(cards/make-card :hearts "A")]
                         :hands {1 [(cards/make-card :clubs "J")
                                    (cards/make-card :diamonds "7")
                                    (cards/make-card :spades "4")]
                                 2 [(cards/make-card :diamonds "5")]}}
                 :effects []}]
      (testing "J clubs on A hearts - different suit and rank"
        (let [result (validation/validate-play state 1 [(cards/make-card :clubs "J")])]
          (is (:valid? result) "Any card should be playable on a starting Ace")))
      (testing "7 diamonds on A hearts - different suit and rank"
        (let [result (validation/validate-play state 1 [(cards/make-card :diamonds "7")])]
          (is (:valid? result) "Any card should be playable on a starting Ace")))
      (testing "4 spades on A hearts - different suit and rank"
        (let [result (validation/validate-play state 1 [(cards/make-card :spades "4")])]
          (is (:valid? result) "Any card should be playable on a starting Ace")))))

  (testing "ace with action-suit still enforces suit matching"
    (let [state {:status :live
                 :players [{:id 1 :name "Alice" :status :normal}
                           {:id 2 :name "Bob" :status :normal}]
                 :current-player-index 0 :direction :clockwise
                 :zones {:deck [(cards/make-card :spades "10")]
                         :played-stack [(cards/make-card :hearts "A")]
                         :hands {1 [(cards/make-card :clubs "J")]
                                 2 [(cards/make-card :diamonds "5")]}}
                 :effects [{:type :suit-selected :suit :diamonds}]}]
    (is (not (:valid? (validation/validate-play state 1 [(cards/make-card :clubs "J")])))
        "With action-suit set, suit must match"))))
