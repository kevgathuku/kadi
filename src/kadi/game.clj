(ns kadi.game
  "Game state and state transitions with validation-first commands.

  Core philosophy:
  - Game state is a pure value (immutable map)
  - Commands are validated before applying; invalid commands return {:error reason}
  - Only valid commands should be persisted as events
  - State transitions are pure: (state, command) -> {:ok state} | {:error reason}
  - Hands are stored in zones: :zones/:hands {player-id [cards...]}
  - Side effects (persistence, broadcasting) happen at the edges"
  (:require [kadi.cards :as cards]
            [kadi.validation :as validation]
            [kadi.schema :as schema]))

;; =============================================================================
;; Game State Shape (hierarchical)
;; =============================================================================

(defn generate-short-code
  "Generate a random 6-character game code."
  []
  (let [chars "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"]
    (apply str (repeatedly 6 #(rand-nth chars)))))

(defn new-game
  "Create a new game in lobby state with hierarchical schema."
  [short-code]
  {:game/ruleset :kadi
   :game/version 1
   :short-code short-code

   ;; Players (ordered by join time for turn order) - metadata only
   :players []

   ;; Turn management (flat schema)
   :current-player-index 0
   :direction :clockwise

   ;; Card locations
   :zones {:deck []
           :played-stack []
           :hands {}}

   ;; Active effects (penalties, suit selection, awaits, etc.)
   :effects []

   ;; Game metadata
   :status :lobby
   :meta {:created-at (java.time.Instant/now)
          :updated-at (java.time.Instant/now)}})

;; =============================================================================
;; Player Management
;; =============================================================================

(defn game-status [state]
  ;; Prefer root-level status; fallback to legacy meta path for compatibility
  (or (:status state)
      (get-in state [:meta :status])))

(defn turn-direction [state]
  (let [dir (:direction state :clockwise)]
    (cond
      (keyword? dir) dir
      (string? dir) (keyword dir)
      :else :clockwise)))

(defn current-player-index [state]
  (:current-player-index state 0))

(defn players [state]
  (:players state))

(defn add-player-metadata
  "Add player metadata (no hand here)."
  [state {:keys [id name]}]
  (update state :players conj {:id id
                               :name name
                               :status :normal}))

(defn get-player [state player-id]
  (first (filter #(= player-id (:id %)) (:players state))))

(defn get-player-index [state player-id]
  (first (keep-indexed (fn [i p] (when (= player-id (:id p)) i))
                       (:players state))))

(defn update-player [state player-id f]
  (update state :players
          (fn [players]
            (mapv (fn [p] (if (= player-id (:id p)) (f p) p))
                  players))))

(defn current-player [state]
  (get-in state [:players (current-player-index state)]))

(defn current-player-id [state]
  (:id (current-player state)))

;; =============================================================================
;; Hands Helpers (hands live in :zones/:hands keyed by player-id)
;; =============================================================================

(defn get-hand [state player-id]
  (get-in state [:zones :hands player-id] []))

(defn set-hand [state player-id new-hand]
  (assoc-in state [:zones :hands player-id] new-hand))

(defn update-hand [state player-id f]
  (update-in state [:zones :hands player-id] (fnil f [])))

;; Embed hands for legacy validation that expects :hand on players
(defn with-embedded-hands [state]
  (update state :players
          (fn [ps]
            (mapv (fn [p]
                    (assoc p :hand (get-hand state (:id p))))
                  ps))))

;; =============================================================================
;; Turn Management
;; =============================================================================

(defn next-player-index
  "Calculate the next player index, respecting direction and skip count."
  [{:keys [players] :as state} skip-count]
  (let [player-count (count players)
        direction (turn-direction state)
        current-player-index (current-player-index state)
        offset (case direction
                 :clockwise skip-count
                 :counter-clockwise (- skip-count))]
    (mod (+ current-player-index offset player-count)
         player-count)))

(defn advance-turn
  "Advance turn by skip-count players."
  ([state] (advance-turn state 1))
  ([state skip-count]
   (let [idx (next-player-index state skip-count)]
     (assoc state :current-player-index idx))))

(defn reverse-direction [state]
  (let [new-dir (case (turn-direction state)
                  :clockwise :counter-clockwise
                  :counter-clockwise :clockwise
                  :clockwise)]
    (assoc state :direction new-dir)))

;; =============================================================================
;; Card Operations
;; =============================================================================

(defn deal-cards
  "Deal n cards to a player from the deck."
  [state player-id n]
  (let [cards-to-deal (take n (get-in state [:zones :deck]))]
    (-> state
        (update-in [:zones :deck] #(drop n %))
        (update-hand player-id #(into % cards-to-deal)))))

(defn remove-cards-from-hand
  "Remove specific cards from a player's hand."
  [state player-id cards-to-remove]
  (let [cards-set (set cards-to-remove)]
    (update-hand state player-id
                 (fn [hand]
                   (vec (remove cards-set hand))))))

(defn add-to-played-stack
  "Add cards to the played stack."
  [state cards]
  (update-in state [:zones :played-stack] into cards))

(defn top-card [state]
  (last (get-in state [:zones :played-stack])))

(defn draw-card
  "Draw one card from deck to player's hand.
   Optional maintain-kadi? keeps player in :kadi status (voluntary draws only)."
  [state player-id & {:keys [maintain-kadi?] :or {maintain-kadi? false}}]
  (if (empty? (get-in state [:zones :deck]))
    state
    (let [card (first (get-in state [:zones :deck]))
          player (get-player state player-id)
          current-status (:status player)
          ;; Keep :kadi if maintain-kadi? is true AND player is currently in :kadi
          new-status (if (and maintain-kadi? (= :kadi current-status))
                       :kadi
                       :normal)]
      (-> state
          (update-in [:zones :deck] rest)
          (update-hand player-id #(conj % card))
          (update-player player-id #(assoc % :status new-status))))))

(defn recycle-played-stack
  "Move all but top card from played stack back to deck (shuffled)."
  [state]
  (let [played (get-in state [:zones :played-stack])
        top (last played)
        to-recycle (butlast played)]
    (if (empty? to-recycle)
      state
      (-> state
          (assoc-in [:zones :deck] (shuffle to-recycle))
          (assoc-in [:zones :played-stack] [top])))))

;; =============================================================================
;; Game Start
;; =============================================================================

(defn start-game
  "Transition game from lobby to live, deal cards, set starting card.
   Optional :deck / :starting-card make the deal deterministic —
   event replay passes the deal stored in the :start-game event."
  [state {:keys [cards-per-player deck starting-card] :or {cards-per-player 4}}]
  (if (< (count (:players state)) 2)
    state
    (let [deck (or deck (cards/make-deck))
          starting-card (or starting-card (cards/select-starting-card deck))
          deck-without-start (vec (remove #{starting-card} deck))]
      (-> state
          (assoc :status :live)
          (assoc-in [:zones :deck] deck-without-start)
          (assoc-in [:zones :played-stack] [starting-card])
          ;; Deal cards to each player
          (as-> s (reduce (fn [st player]
                            (deal-cards st (:id player) cards-per-player))
                          s
                          (:players s)))))))

;; =============================================================================
;; Card Effects
;; =============================================================================

(defn apply-card-effects
  "Apply special card effects after playing cards.
   Special case: When Ace blocks a penalty, it clears the penalty and
   sets the blocked card's suit as the active suit (not suit selection).
   
   prev-top-card: The card that was on top BEFORE these cards were played."
  [state cards prev-top-card]
  (let [ranks (map :rank cards)
        jack-count (count (filter #{"J"} ranks))
        has-ace? (some #{"A"} ranks)
        active-penalty (first (filter #(= :penalty (:type %)) (:effects state)))
        has-active-penalty? (some? active-penalty)
        
        ;; Use the previous top card (before Ace was played) as the blocked card
        ;; This is the penalty card (2 or 3) that the Ace is blocking
        blocked-card (when (and has-ace? has-active-penalty?)
                       prev-top-card)]
    
    (cond-> state
      ;; King reverses direction
      (some #{"K"} ranks)
      reverse-direction

      ;; Ace blocks penalty: clear penalty + set suit from blocked card
      ;; (NO suit selection when blocking)
      (and has-ace? has-active-penalty? blocked-card)
      (-> (update :effects #(remove (fn [e] (= :penalty (:type e))) %))
          (update :effects conj {:type :suit-selected 
                                  :suit (:suit blocked-card)
                                  :blocked-penalty true
                                  :blocked-card-rank (:rank blocked-card)}))

      ;; Ace without penalty: trigger suit selection (normal behavior)
      (and has-ace? (not has-active-penalty?))
      (update :effects conj {:type :select-suit})

      ;; 2 creates draw-2 penalty (only if not blocking an existing penalty)
      (and (some #{"2"} ranks) (not has-active-penalty?))
      (update :effects conj {:type :penalty :penalty-type :two})

      ;; 3 creates draw-3 penalty (only if not blocking an existing penalty)
      (and (some #{"3"} ranks) (not has-active-penalty?))
      (update :effects conj {:type :penalty :penalty-type :three})

      ;; Jack skips players
      (pos? jack-count)
      (assoc ::skip-count (inc jack-count))

      ;; Question without answer
      (and (every? cards/question-card? cards)
           (not (empty? cards)))
      (update :effects conj {:type :awaiting-answer}))))

(defn maybe-advance-turn
  "Advance turn unless waiting for suit selection or question answer."
  [state cards]
  (let [skip-count (or (::skip-count state) 1)]
    (cond
      ;; Waiting for suit selection
      (some #(= :select-suit (:type %)) (:effects state))
      (dissoc state ::skip-count)

      ;; Waiting for question answer
      (some #(= :awaiting-answer (:type %)) (:effects state))
      (dissoc state ::skip-count)

      ;; Normal turn advance
      :else
      (-> state
          (advance-turn skip-count)
          (dissoc ::skip-count)))))

(defn check-cardless
  "Check if player entered cardless state.
   Rules:
   - Playing K/J/2/3/A/Q/8 as the final card → ALWAYS cardless (even if in :kadi)
     (these cards leave unresolved follow-up actions like skip/penalty/suit-select/question)
   - Playing combos ending in regular cards (4-7, 9, 10) — including Q/8 + regular answer —
     leaves no pending action, so it does not trigger cardless (allows normal finish)
   - Kadi declaration is optional, not required"
  [state player-id cards]
  (let [player (get-player state player-id)
        hand (get-hand state player-id)
        final-card (last cards)
        ;; Cards that trigger cardless: when the final card remaining on top is a special action card
        triggers-cardless? (contains? #{"K" "J" "2" "3" "A" "Q" "8"} (:rank final-card))]
    (if (and (empty? hand) triggers-cardless?)
      (update-player state player-id #(assoc % :status :cardless))
      state)))

;; =============================================================================
;; Penalty Handling
;; =============================================================================

(defn set-penalty-target [state]
  (let [next-idx (next-player-index state 1)
        next-player-id (get-in state [:players next-idx :id])]
    (assoc-in state [:penalty :target-player-id] next-player-id)))

(defn clear-penalty [state]
  (assoc state :penalty {:active false
                         :type nil
                         :target-player-id nil
                         :blocked-suit nil}))

(defn accept-penalty
  "Player accepts penalty and draws cards."
  [state player-id]
  (let [penalty-effect (first (filter #(= :penalty (:type %)) (:effects state)))
        penalty-type (:penalty-type penalty-effect)
        draw-count (case penalty-type :two 2 :three 3 0)]
    (-> state
        ;; Draw cards one at a time, recycling if needed
        (as-> s (reduce (fn [st _]
                          (let [;; Recycle if deck is empty before drawing
                                st' (cond-> st
                                      (empty? (get-in st [:zones :deck]))
                                      recycle-played-stack)]
                            ;; Only draw if deck has cards after recycling
                            (if (seq (get-in st' [:zones :deck]))
                              (draw-card st' player-id)
                              st')))
                        s
                        (range draw-count)))
        (update :effects #(remove (fn [e] (= :penalty (:type e))) %))
        (advance-turn))))

;; =============================================================================
;; Command Validation Helpers
;; =============================================================================
(defn has-effect? [state effect-type]
  (some #(= effect-type (:type %)) (:effects state)))

(defn get-effect [state effect-type]
  (first (filter #(= effect-type (:type %)) (:effects state))))

;; =============================================================================
;; View Model (single interpreter of game state for rendering)
;; =============================================================================

(defn- banner-for
  "Build the effect banner data. Mirrors effect precedence: penalty,
   suit selection, required suit, awaiting answer."
  [state my-turn? current-name]
  (let [penalty (get-effect state :penalty)
        select-suit (get-effect state :select-suit)
        suit-selected (get-effect state :suit-selected)
        awaiting-answer (get-effect state :awaiting-answer)]
    (cond
      penalty
      (let [penalty-type (name (:penalty-type penalty))
            draw-count (case (:penalty-type penalty) :two 2 :three 3 0)]
        {:kind :penalty
         :draw-count draw-count
         :text (if my-turn?
                 (str "⚠️ Penalty active (" draw-count " cards)! Play " penalty-type " to block, or accept.")
                 (str "⚠️ Penalty active (" draw-count " cards). Waiting for " current-name "."))})

      select-suit
      {:kind :select-suit
       :text (if my-turn?
               "Ace played! Select a suit below."
               (str "Waiting for " current-name " to select a suit."))}

      suit-selected
      ;; Text is composed in views: the suit glyph is presentation.
      {:kind :suit-selected
       :suit (:suit suit-selected)
       :text nil}

      awaiting-answer
      {:kind :awaiting-answer
       :text (if my-turn?
               "Question asked! You must draw to answer."
               (str "Question asked! Waiting for " current-name " to draw."))}

      :else {:kind :none :text nil})))

(defn play-view
  "Derive everything the play screen needs from game state.
   The single interpreter: views render this map without branching
   on effect types or player statuses."
  [state player-id]
  (let [players (:players state)
        current-idx (current-player-index state)
        current (get players current-idx)
        current-name (:name current)
        my-player (first (filter #(= (:id %) player-id) players))
        my-turn? (= player-id (:id current))
        finished? (= :finished (:status state))
        winner (when finished?
                 (first (filter #(= (:id %) (:winner state)) players)))
        penalty (get-effect state :penalty)
        mode (cond
               finished? :finished
               (nil? my-player) :spectator
               (not my-turn?) :waiting
               (= :cardless (:status my-player)) :cardless
               (has-effect? state :awaiting-answer) :answer
               (has-effect? state :select-suit) :select-suit
               :else :play)]
    {:my-turn? my-turn?
     :finished? finished?
     :winner-name (:name winner)
     :current-name current-name
     :top-card (last (get-in state [:zones :played-stack]))
     :deck-count (count (get-in state [:zones :deck]))
     :direction (:direction state)
     :banner (banner-for state my-turn? current-name)
     :penalty? (some? penalty)
     :penalty-draw-count (when penalty
                           (case (:penalty-type penalty) :two 2 :three 3 0))
     :players (mapv (fn [[idx p]]
                      (let [current? (= idx current-idx)
                            me? (= (:id p) player-id)
                            kadi? (= :kadi (:status p))
                            hand-count (count (get-hand state (:id p)))]
                        {:id (:id p)
                         :name (:name p)
                         :hand-count hand-count
                         :current? current?
                         :me? me?
                         :kadi? kadi?
                         :status-text (cond
                                        (and current? me?) "your turn"
                                        kadi? "Kadi"
                                        :else (str hand-count " cards"))}))
                    (map-indexed vector players))
     :my-hand (or (when player-id (get-hand state player-id)) [])
     :my-status (:status my-player)
     :mode mode
     :poll? (and (not my-turn?) (not finished?))}))

(defn- validate-join [state {:keys [id name]}]
  (cond
    (not= :lobby (game-status state)) {:error "Game is not in lobby"}
    (get-player state id) {:error "Player already in game"}
    (not name) {:error "Player name required"}
    :else {:ok true}))

(defn join-player [state player]
  (let [player* (select-keys player [:id :name])
        v (validate-join state player*)]
    (if (:error v)
      v
      {:ok (-> state
               (add-player-metadata player*)
               (update-in [:meta :updated-at] (constantly (java.time.Instant/now))))})))

;; Compatibility helper: returns plain state on success, {:error "..."} on failure.
(defn add-player [state player]
  (let [res (join-player state player)]
    (if (:ok res) (:ok res) res)))

;; Forward declaration: *-cmd fns delegate to the apply-action methods below.
(declare apply-action)

(defn validate-start [state]
  (cond
    (not= :lobby (game-status state)) {:error "Game is not in lobby"}
    (< (count (players state)) 2) {:error "Need at least 2 players to start"}
    :else {:ok true}))

(defn start-game-cmd [state & {:keys [cards-per-player deck starting-card] :or {cards-per-player 4}}]
  (let [v (validate-start state)]
    (if (:error v)
      v
      {:ok (apply-action state {:type :start-game
                                :cards-per-player cards-per-player
                                :deck deck
                                :starting-card starting-card
                                :timestamp (java.time.Instant/now)})})))

(defn validate-draw [state player-id]
  (cond
    (not= :live (game-status state)) {:error "Game is not live"}
    (not (get-player state player-id)) {:error "Player not in game"}
    (not= player-id (current-player-id state)) {:error "Not your turn"}
    (has-effect? state :penalty) {:error "Must accept penalty first"}
    :else {:ok true}))

(defn draw-card-cmd [state player-id & {:keys [maintain-kadi?] :or {maintain-kadi? false}}]
  (let [v (validate-draw state player-id)]
    (if (:error v)
      v
      {:ok (apply-action state {:type :draw-card
                                :player-id player-id
                                :maintain-kadi? maintain-kadi?
                                :timestamp (java.time.Instant/now)})})))

(defn validate-play-cards [state player-id cards]  (cond
    (not= :live (game-status state)) {:error "Game is not live"}
    (not (get-player state player-id)) {:error "Player not in game"}
    (not= player-id (current-player-id state)) {:error "Not your turn"}
    (empty? cards) {:error "Must play at least one card"}
    :else (validation/validate-play (with-embedded-hands state) player-id cards)))

(defn play-cards-cmd [state player-id cards & {:keys [declare-kadi?] :or {declare-kadi? false}}]
  (let [v (validate-play-cards state player-id cards)]
    (if (:error v)
      v
      (if (:valid? v)
        {:ok (apply-action state {:type :play-cards
                                  :player-id player-id
                                  :cards cards
                                  :declare-kadi? declare-kadi?
                                  :timestamp (java.time.Instant/now)})}
        {:error (:reason v)}))))

(defn validate-select-suit [state suit]
  (let [normalized-suit (cards/normalize-suit suit)]
    (cond
      (not (has-effect? state :select-suit)) {:error "No suit selection in progress"}
      (not (contains? #{:clubs :diamonds :hearts :spades} normalized-suit)) {:error "Invalid suit"}
      :else {:ok true})))

(defn select-suit-cmd [state suit]
  (let [normalized-suit (cards/normalize-suit suit)
        v (validate-select-suit state normalized-suit)]
    (if (:error v)
      v
      {:ok (apply-action state {:type :select-suit
                                :suit suit
                                :timestamp (java.time.Instant/now)})})))

(defn validate-answer [state player-id]
  (cond
    (not= :live (game-status state)) {:error "Game is not live"}
    (not (get-player state player-id)) {:error "Player not in game"}
    (not= player-id (current-player-id state)) {:error "Not your turn"}
    (not (has-effect? state :awaiting-answer)) {:error "Not awaiting answer"}
    :else {:ok true}))

(defn answer-question-cmd [state player-id]
  (let [v (validate-answer state player-id)]
    (if (:error v)
      v
      {:ok (apply-action state {:type :answer-question
                                :player-id player-id
                                :timestamp (java.time.Instant/now)})})))

(defn validate-accept-penalty [state player-id]
  (cond
    (not= :live (game-status state)) {:error "Game is not live"}
    (not (get-player state player-id)) {:error "Player not in game"}
    (not= player-id (current-player-id state)) {:error "Not your turn"}
    (not (has-effect? state :penalty)) {:error "No penalty in progress"}
    :else {:ok true}))

(defn accept-penalty-cmd [state player-id]
  (let [v (validate-accept-penalty state player-id)]
    (if (:error v)
      v
      {:ok (apply-action state {:type :accept-penalty
                                :player-id player-id
                                :timestamp (java.time.Instant/now)})})))

;; =============================================================================
;; Event Replay (apply-action assumes events are valid)
;; =============================================================================

(defmulti apply-action
  "Apply an action (event) to the game state."
  (fn [_state action] (:type action)))

(defmethod apply-action :game-created [_state {:keys [player short-code]}]
  ;; Game creation starts fresh with creator as first player
  (let [code (or short-code (generate-short-code))]
    (-> (new-game code)
        (add-player-metadata player))))

(defmethod apply-action :join-game [state {:keys [player timestamp]}]
  (cond-> (add-player-metadata state player)
    timestamp (update-in [:meta :updated-at] (constantly timestamp))))

(defmethod apply-action :start-game [state {:keys [timestamp cards-per-player deck starting-card]}]
  ;; The event carries the deal (persisted at append time) so every replay
  ;; deals identical hands; legacy events without a deck fall back to a
  ;; fresh shuffle. Normalize the stored cards (suits arrive as strings
  ;; after the JSON roundtrip).
  (let [deck (when deck (vec (schema/normalize-cards deck)))
        starting-card (when starting-card (first (schema/normalize-cards [starting-card])))]
    (cond-> (start-game state {:cards-per-player (or cards-per-player 4)
                               :deck deck
                               :starting-card starting-card})
      timestamp (update-in [:meta :updated-at] (constantly timestamp)))))

(defmethod apply-action :play-cards [state {:keys [player-id cards declare-kadi? timestamp]}]
  (let [normalized-cards (schema/normalize-cards cards)
        ;; Capture the top card BEFORE adding new cards
        prev-top-card (last (get-in state [:zones :played-stack]))]
    (cond-> (-> state
                ;; Step 1: Set player to :kadi if declaring
                (cond-> declare-kadi? (update-player player-id #(assoc % :status :kadi)))
                ;; Step 2: Play cards normally
                (update :effects #(remove (fn [e] (= :suit-selected (:type e))) %))
                (remove-cards-from-hand player-id normalized-cards)
                (add-to-played-stack normalized-cards)
                (apply-card-effects normalized-cards prev-top-card)
                ;; Step 3: Check cardless (may set to :cardless if invalid finish)
                (check-cardless player-id normalized-cards)
                ;; Step 4: Check for win (if still in :kadi and hand empty)
                (as-> s
                  (let [player (get-player s player-id)
                        hand (get-hand s player-id)]
                    (if (and (= :kadi (:status player)) (empty? hand))
                      (-> s
                          (assoc :status :finished)
                          (assoc :winner player-id))
                      s)))
                (maybe-advance-turn normalized-cards))
      timestamp (update-in [:meta :updated-at] (constantly timestamp)))))

(defmethod apply-action :draw-card [state {:keys [player-id maintain-kadi? timestamp]}]
  (let [;; Recycle played stack if deck is empty
        state' (cond-> state
                 (empty? (get-in state [:zones :deck]))
                 recycle-played-stack)
        ;; If still empty after recycle, skip player (no draw)
        can-draw? (seq (get-in state' [:zones :deck]))]
    (cond-> (-> state'
                (cond-> can-draw? (draw-card player-id :maintain-kadi? (boolean maintain-kadi?)))
                (advance-turn))
      timestamp (update-in [:meta :updated-at] (constantly timestamp)))))

(defmethod apply-action :answer-question [state {:keys [player-id timestamp]}]
  (let [state' (cond-> state
                 (empty? (get-in state [:zones :deck]))
                 recycle-played-stack)
        can-draw? (seq (get-in state' [:zones :deck]))]
    (cond-> (-> state'
                (cond-> can-draw? (draw-card player-id))
                (update :effects #(remove (fn [e] (= :awaiting-answer (:type e))) %))
                (advance-turn))
      timestamp (update-in [:meta :updated-at] (constantly timestamp)))))

(defmethod apply-action :select-suit [state {:keys [suit timestamp]}]
  (let [normalized-suit (cards/normalize-suit suit)]
    (cond-> (-> state
                (update :effects #(remove (fn [e] (= :select-suit (:type e))) %))
                (update :effects conj {:type :suit-selected :suit normalized-suit})
                (advance-turn))
      timestamp (update-in [:meta :updated-at] (constantly timestamp)))))

(defmethod apply-action :accept-penalty [state {:keys [player-id timestamp]}]
  (cond-> (accept-penalty state player-id)
    timestamp (update-in [:meta :updated-at] (constantly timestamp))))

;; Backward-compat aliases for legacy event names in existing DBs
(defmethod apply-action :cards-played [state action]
  (apply-action state (assoc action :type :play-cards)))

(defmethod apply-action :card-drawn [state action]
  (apply-action state (assoc action :type :draw-card)))

(defmethod apply-action :default [state _]
  state)
