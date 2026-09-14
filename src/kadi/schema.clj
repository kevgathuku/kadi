(ns kadi.schema
  "Malli schemas for domain objects with normalization/coercion."
  (:require [malli.core :as m]
            [malli.transform :as mt]
            [malli.util :as mu]))

;; =============================================================================
;; Custom Transformers
;; =============================================================================

(def string->keyword-transformer
  "Transformer that coerces strings to keywords for keyword fields."
  (mt/transformer
   {:name :string->keyword
    :decoders {:keyword? (fn [_schema]
                           (fn [value]
                             (cond
                               (keyword? value) value
                               (string? value) (keyword value)
                               :else value)))}}))

(def json-transformer
  "Transformer for JSON roundtrip normalization.
   Uses string-transformer (which handles enums) + custom keyword converter.
   Note: NOT using strip-extra-keys since :or schemas may incorrectly strip fields."
  (mt/transformer
   mt/string-transformer
   string->keyword-transformer))

;; =============================================================================
;; Domain Schemas
;; =============================================================================

(def PlayerStatus
  [:enum :normal :penalty :skip :selecting-suit :kadi :cardless])

(def GameStatus
  [:enum :lobby :live :finished])

(def Direction
  [:enum :clockwise :counter-clockwise])

(def Ruleset
  [:enum :kadi])

(def Suit
  [:enum :hearts :diamonds :clubs :spades])

(def Rank
  [:enum "2" "3" "4" "5" "6" "7" "8" "9" "10" "J" "Q" "K" "A"])

(def PenaltyType
  "Type of penalty: draw-2 or draw-3"
  [:enum :two :three])

(def Card
  [:map
   [:suit Suit]
   [:rank Rank]])

(def Player
  [:map
   [:id int?]
   [:name string?]
   [:status PlayerStatus]])

(def Zones
  [:map
   [:deck [:sequential Card]]
   [:played-stack [:sequential Card]]
   [:hands [:map-of int? [:sequential Card]]]])

(def GameMeta
  [:map
   [:created-at inst?]
   [:updated-at inst?]])

;; Effect schemas - each effect type has properly typed fields
;; that will be normalized from strings to keywords via json-transformer

(def SelectSuitEffect
  "Effect when Ace is played - player must select a suit"
  [:map
   [:type keyword?]])

(def SuitSelectedEffect
  "Effect after suit is selected - stores the required suit.
   Optional :blocked-penalty flag indicates Ace blocked a penalty.
   Optional :blocked-card-rank stores rank of blocked penalty card."
  [:map
   [:type keyword?]
   [:suit Suit]
   [:blocked-penalty {:optional true} boolean?]
   [:blocked-card-rank {:optional true} string?]])

(def PenaltyEffect
  "Effect when 2 or 3 is played - penalty draw"
  [:map
   [:type keyword?]
   [:penalty-type PenaltyType]])

(def AwaitingAnswerEffect
  "Effect when question card (Q/8) is played without answer"
  [:map
   [:type keyword?]])

(def Effect
  "Game effect - each effect type has properly typed fields.
   Uses :or schema for simpler transformation compatibility."
  [:or
   SelectSuitEffect
   SuitSelectedEffect
   PenaltyEffect
   AwaitingAnswerEffect])

(def Game
  "Core game state schema. Represents both in-memory and persisted game state.
   Uses flat schema for turn management (current-player-index and direction)."
  [:map
   [:status GameStatus]
   [:players [:sequential Player]]
   [:current-player-index int?]
   [:direction Direction]
   [:zones Zones]
   [:effects [:sequential Effect]]
   [:short-code string?]
   [:meta GameMeta]
   [:game/ruleset Ruleset]
   [:game/version int?]
   [:winner {:optional true} [:maybe int?]]])

(def GameRow
  "Database row schema with additional metadata."
  [:map
   [:id int?]
   [:short_code string?]
   [:state Game]
   [:state_sequence int?]
   [:created_at string?]
   [:updated_at string?]])

;; =============================================================================
;; Normalization Functions
;; =============================================================================

(defn- fix-hands-keys
  "Convert keyword/string hands keys to integers after JSON deserialization.
   JSON keys become keywords via jsonista (e.g. :1, :2) but get-hand
   looks up by integer player-id."
  [game]
  (if-let [hands (get-in game [:zones :hands])]
    (assoc-in game [:zones :hands]
              (into {} (map (fn [[k v]]
                              [(cond
                                 (int? k) k
                                 (keyword? k) (parse-long (name k))
                                 (string? k) (parse-long k)
                                 :else k)
                               v])
                            hands)))
    game))

(defn- normalize-effect
  "Normalize a single effect - convert enum string values to keywords.
   Handles :penalty-type and :suit fields that may be strings after JSON parse."
  [effect]
  (cond-> effect
    (:penalty-type effect) (update :penalty-type keyword)
    (:suit effect) (update :suit keyword)))

(defn normalize-game
  "Normalize a game state to ensure consistent types (keywords, etc).
   Useful after JSON deserialization or event sourcing."
  [game]
  (when game
    (let [fixed (fix-hands-keys game)
          decoded (m/decode Game fixed json-transformer)
          ;; Manually normalize effect enum fields (penalty-type, suit)
          ;; because multi-schema transformers are complex
          normalized-effects (mapv normalize-effect (:effects decoded))]
      (assoc decoded :effects normalized-effects))))

(defn normalize-game-row
  "Normalize a database game row, ensuring state is properly typed."
  [row]
  (when row
    (let [normalized-state (normalize-game (:state row))]
      (assoc row :state normalized-state))))

(defn normalize-cards
  "Normalize a sequence of cards (e.g. from JSON event data)."
  [cards]
  (m/decode [:sequential Card] cards json-transformer))

(defn validate-game
  "Validate a game state against the schema. Returns {:valid? bool :errors ...}"
  [game]
  (let [valid? (m/validate Game game)]
    (if valid?
      {:valid? true}
      {:valid? false
       :errors (m/explain Game game)})))

(defn validate-game!
  "Validate a game state, throwing on invalid data."
  [game]
  (when-not (m/validate Game game)
    (throw (ex-info "Invalid game state"
                    {:errors (m/explain Game game)
                     :game game})))
  game)

;; =============================================================================
;; Schema Helpers
;; =============================================================================

(defn game-schema
  "Return the Game schema for inspection."
  []
  Game)

;; =============================================================================
;; Auth Schemas
;; =============================================================================

(def Email
  "Email address validated by regex pattern."
  [:re #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$"])

(def Username
  "Username (players.name): 3-20 chars, letters/digits/underscore."
  [:re #"^[a-zA-Z0-9_]{3,20}$"])

(def AuthToken
  "Auth token record as returned by db/get-auth-token."
  [:map
   [:id int?]
   [:email Email]
   [:token string?]
   [:expires_at string?]
   [:used int?]
   [:created_at string?]])

(def DbPlayer
  "Database-level player record with email (superset of game Player)."
  [:map
   [:id int?]
   [:name string?]
   [:email Email]
   [:created_at string?]])

(def SigninEmailRequest
  "Input for send-signin-email!."
  [:map
   [:email Email]
   [:token string?]])

(comment
  ;; Test normalization
  (normalize-game {:status "live"
                   :players [{:id 1 :name "Alice" :status "normal"}]
                   :current-player-index 0
                   :direction "clockwise"
                   :zones {:deck [] :played-stack [] :hands {}}
                   :effects []
                   :short-code "ABC123"
                   :meta {:created-at (java.time.Instant/now)
                          :updated-at (java.time.Instant/now)}
                   :game/ruleset "kadi"
                   :game/version 1})

  ;; Test validation
  (validate-game {:status :live
                  :players [{:id 1 :name "Alice" :status :normal}]
                  :current-player-index 0
                  :direction :clockwise
                  :zones {:deck [] :played-stack [] :hands {}}
                  :effects []
                  :short-code "ABC123"
                  :meta {:created-at (java.time.Instant/now)
                         :updated-at (java.time.Instant/now)}
                  :game/ruleset :kadi
                  :game/version 1}))
