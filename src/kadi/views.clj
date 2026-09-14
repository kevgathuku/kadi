(ns kadi.views
  "HTML views using Hiccup."
  (:require [hiccup2.core :as h]
            [hiccup.util :refer [raw-string]]
            [kadi.cards :as cards]
            [kadi.game :as game]))

;; =============================================================================
;; Layout
;; =============================================================================

(defn layout
  "Base HTML layout with HTMX."
  [{:keys [title flash player]} & body]
  (str
   (h/html
    {:mode :html}
    (raw-string "<!DOCTYPE html>")
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title (or title "Kadi")]
      ;; HTMX
      [:script {:src "https://unpkg.com/htmx.org@2.0.4"
                :integrity "sha384-HGfztofotfshcF7+8n44JQL2oJmowVChPTg48S+jvZoztPfvwD79OC/LTtG6dMp+"
                :crossorigin "anonymous"}]
      [:link {:rel "stylesheet" :href "/css/game.css"}]
      ;; Card selection order tracking
      [:script (raw-string
                "document.addEventListener('DOMContentLoaded', function() {
           // Track selected cards in order
           const selectedCards = [];
           
           function updateOrderedCardsInput() {
             const orderedInput = document.getElementById('ordered-cards');
             if (orderedInput) {
               orderedInput.value = selectedCards.join(',');
             }
           }
           
           const suitSymbols = {hearts:'♥', diamonds:'♦', clubs:'♣', spades:'♠'};

           function cardLabel(cardId) {
             const parts = cardId.split('-');
             const rank = parts[0];
             const suit = parts[1];
             return rank + (suitSymbols[suit] || '');
           }

           function updatePlayButton() {
             const btn = document.getElementById('play-btn');
             if (!btn) return;
             const isPenalty = btn.hasAttribute('data-penalty');
             if (selectedCards.length === 0) {
               btn.textContent = isPenalty ? 'Select a Card to Block' : 'Play Selected';
               btn.disabled = isPenalty;
             } else {
               const labels = selectedCards.map(cardLabel).join(', ');
               btn.textContent = isPenalty ? 'Play ' + labels + ' to Block' : 'Play ' + labels;
               btn.disabled = false;
             }
           }

           // Listen for checkbox changes
           document.addEventListener('change', function(e) {
             if (e.target.classList.contains('card-checkbox')) {
               const cardId = e.target.dataset.cardId;

               if (e.target.checked) {
                 // Add to selection order if not already there
                 if (!selectedCards.includes(cardId)) {
                   selectedCards.push(cardId);
                 }
               } else {
                 // Remove from selection order
                 const index = selectedCards.indexOf(cardId);
                 if (index > -1) {
                   selectedCards.splice(index, 1);
                 }
               }

               updateOrderedCardsInput();
               updatePlayButton();
             }
           });
           
           // Clear selection order when form is submitted
           document.addEventListener('submit', function(e) {
             if (e.target.id === 'play-form') {
               // Form will submit with current ordered-cards value
             }
           });
         });")]]
     [:body
      [:nav
       [:div.flex-row
        [:a {:href "/"} [:strong "Kadi"]]
        (when player
          (list
           [:a {:href "/games"} "My Games"]
           [:a {:href "/join"} "Join Game"]))]
       (if player
         [:div
          [:span (str "Hi, " (:name player) " ")]
          [:form {:method "post" :action "/auth/signout" :class "inline"}
           [:button.btn.btn-secondary {:type "submit"} "Sign out"]]]
         [:a.btn.btn-primary {:href "/auth/signin"} "Sign in"])]
      (when flash
        [:div.flash {:class (str "flash-" (name (:type flash)))}
         (:message flash)])
      body]])))

;; =============================================================================
;; Auth Pages
;; =============================================================================

(defn signin-page
  "Sign-in page with email-or-username form."
  [{:keys [flash]}]
  (layout {:title "Sign In" :flash flash}
          [:div.card
           [:h2 "Sign in to Kadi"]
           [:p "Enter your email or username and we'll send you a sign-in link."]
           [:form {:method "post" :action "/auth/send-link"}
            [:div.form-group
             [:label {:for "identifier"} "Email or username"]
             [:input {:type "text" :id "identifier" :name "identifier"
                      :placeholder "you@example.com or alice" :required true :autofocus true}]]
            [:button.btn.btn-primary {:type "submit"} "Send sign-in link"]]]))

(defn check-email-page
  "Page shown after sending sign-in link."
  [{:keys [email]}]
  (layout {:title "Check Your Email"}
          [:div.card
           [:h2 "Check your email"]
           [:p "We sent a sign-in link to " [:strong email] ". Click the link in the email to sign in. The link expires in 30 minutes."]
           [:p [:a {:href "/auth/signin"} "Didn't receive it? Try again"]]]))

;; =============================================================================
;; Game Pages
;; =============================================================================

(defn join-page
  "Join game page with code input form."
  [{:keys [player flash]}]
  (layout {:title "Join Game" :player player :flash flash}
          [:div.card
           [:h2 "Join a Game"]
           [:p "Enter the 6-character game code to join."]
           [:form {:method "post" :action "/join"}
            [:div.form-group
             [:label {:for "code"} "Game Code"]
             [:input {:type "text" :id "code" :name "code"
                      :placeholder "e.g., ABC123"
                      :required true
                      :autofocus true
                      :maxlength "6"
                      :style "text-transform: uppercase;"}]]
            [:button.btn.btn-primary {:type "submit"} "Join Game"]]]))

(defn auth-error-page
  "Auth error page."
  [{:keys [message]}]
  (layout {:title "Sign In Error"}
          [:div.card
           [:h2 "Sign in failed"]
           [:p (or message "The sign-in link is invalid or has expired.")]
           [:p [:a.btn.btn-primary {:href "/auth/signin"} "Try again"]]]))

;; =============================================================================
;; Home / Lobby Pages
;; =============================================================================

(defn home-page
  "Home page for authenticated users."
  [{:keys [player games flash]}]
  (layout {:title "Kadi" :player player :flash flash}
          [:div.card
           [:h2 "Welcome to Kadi!"]
           [:p "A multiplayer card game."]
           [:div.flex-row
            [:form {:method "post" :action "/games"}
             [:button.btn.btn-primary {:type "submit"} "Create Game"]]
            [:a.btn.btn-secondary {:href "/games"} "My Games"]]]
          (when (seq games)
            [:div.card
             [:h3 "Your Active Games"]
             [:ul.game-list
              (for [game games]
                (let [status (get-in game [:state :status])
                      short-code (:short_code game)]
                  [:li
                   [:a {:href (str "/games/" short-code)}
                    (str "Game " short-code
                         (when status (str " - " (name status))))]]))]])))

(defn guest-home-page
  "Home page for guests."
  [{:keys [flash]}]
  (layout {:title "Kadi" :flash flash}
          [:div.card
           [:h2 "Welcome to Kadi!"]
           [:p "Kadi is a multiplayer card game popular in Kenya."]
           [:p "Sign in to create or join games."]
           [:a.btn.btn-primary {:href "/auth/signin"} "Sign in"]]))

;; =============================================================================
;; Fragments (for HTMX partial updates)
;; =============================================================================

(defn players-list-fragment
  "HTMX fragment for player list."
  [{:keys [players]}]
  (str
   (h/html
    [:ul.game-list
     (for [p players]
       [:li (:name p)])])))

(defn lobby-status-fragment
  "HTMX fragment for lobby status - includes player list and action buttons.
   This allows dynamic updates when players join without full page refresh."
  [{:keys [player game]}]
  (let [players (get-in game [:state :players])
        can-start? (>= (count players) 2)
        is-player? (some #(= (:id player) (:id %)) players)
        creator (first players)
        is-creator? (= (:id player) (:id creator))
        short-code (:short_code game)]
    (str
     (h/html
      [:div {:id "lobby-status"
             :hx-get (str "/games/" short-code "/lobby-status")
             :hx-trigger "every 3s"
             :hx-swap "outerHTML"}
       [:div.card
        [:div.flex-between
         [:h3 "Players in this game"]
         [:button.btn.btn-secondary
          {:hx-get (str "/games/" short-code "/lobby-status")
           :hx-target "#lobby-status"
           :hx-swap "outerHTML"}
          "Refresh"]]
        [:div {:id "player-list"}
         [:ul.game-list
          (for [p players]
            [:li (:name p)])]]]
       [:div.card
        (if is-player?
          (if (and can-start? is-creator?)
            [:form {:method "post" :action (str "/games/" short-code "/start")}
             [:button.btn.btn-primary {:type "submit"} "Start Game"]]
            [:p (if can-start?
                  "Waiting for the game creator to start..."
                  "Waiting for more players... (need at least 2)")])
          [:form {:method "post" :action (str "/games/" short-code "/join")}
           [:button.btn.btn-primary {:type "submit"} "Join Game"]])]]))))

;; =============================================================================
;; Game Pages
;; =============================================================================

(defn games-list-page
  "List of player's active and finished games."
  [{:keys [player games finished-games flash]}]
  (layout {:title "Games" :player player :flash flash}
          ;; Active games
          [:div.flex-between.mb-2
           [:h2 {:style "margin: 0;"} "Active Games"]
           [:a.btn.btn-primary {:href "/join"} "Join with Code"]]
          [:div.card
           [:form {:method "post" :action "/games" :class "mb-2"}
            [:button.btn.btn-primary {:type "submit"} "Create New Game"]]
           (if (seq games)
             [:ul.game-list
              (for [game games]
                [:li.flex-between
                 [:span (str "Game " (:short_code game)
                             " (" (count (get-in game [:state :players])) " players)"
                             (when-let [status (get-in game [:state :status])]
                               (str " - " (name status))))]
                 [:a.btn.btn-primary {:href (str "/games/" (:short_code game))} "Continue"]])]
             [:p "No active games. Create one!"])]
          ;; Finished games
          (when (seq finished-games)
            (list
             [:div.mt-4.mb-2
              [:h2 {:style "margin: 0;"} "Past Games"]]
             [:div.card
              [:ul.game-list
               (for [game finished-games]
                 (let [winner-id (get-in game [:state :winner])
                       players (get-in game [:state :players])
                       winner-name (some #(when (= (:id %) winner-id) (:name %)) players)]
                   [:li.flex-between
                    [:span (str "Game " (:short_code game)
                                " (" (count players) " players)"
                                (when winner-name (str " - Won by " winner-name)))]
                    [:a.btn.btn-secondary {:href (str "/games/" (:short_code game))} "View"]]))]]))))

(defn game-lobby-page
  "Game lobby - waiting for players."
  [{:keys [player game flash]}]
  (layout {:title (str "Game " (:short_code game)) :player player :flash flash}
          [:div.card
           [:h2 "Game Lobby"]
           [:p "Share this code with friends to let them join:"]
           [:div.game-code
            [:code (:short_code game)]]]
          ;; Use the fragment directly in the page so refresh updates everything
          (raw-string (lobby-status-fragment {:player player :game game}))))

;; =============================================================================
;; Game Play Page
;; =============================================================================

(defn card-class [card]
  (str "playing-card " (name (:suit card))))

(defn- suit-symbol
  "Get suit symbol for a given suit keyword."
  [suit]
  (case suit
    :hearts "♥"
    :diamonds "♦"
    :clubs "♣"
    :spades "♠"
    ""))

(defn card-display [card]
  (str (:rank card) (suit-symbol (:suit card))))

(defn- effect-banner
  "Render a banner for active game effects."
  [state {:keys [is-my-turn? current-player-name]}]
  (let [effects (:effects state)
        penalty (first (filter #(= :penalty (:type %)) effects))
        select-suit (first (filter #(= :select-suit (:type %)) effects))
        suit-selected (first (filter #(= :suit-selected (:type %)) effects))
        awaiting-answer (first (filter #(= :awaiting-answer (:type %)) effects))]
    (cond
      penalty
      (let [penalty-type (name (:penalty-type penalty))
            count (case (:penalty-type penalty) :two 2 :three 3 0)]
        [:div.effect-banner.penalty
         (if is-my-turn?
           (str "⚠️ Penalty active (" count " cards)! Play " penalty-type " to block, or accept.")
           (str "⚠️ Penalty active (" count " cards). Waiting for " current-player-name "."))])

      select-suit
      [:div.effect-banner.select-suit
       (if is-my-turn?
         "Ace played! Select a suit below."
         (str "Waiting for " current-player-name " to select a suit."))]

      suit-selected
      (let [required-suit (:suit suit-selected)
            suit-display (str (suit-symbol required-suit) " " (clojure.string/capitalize (name required-suit)))]
        [:div.effect-banner.suit-selected
         (str "🎴 Required suit: " suit-display)])

      awaiting-answer
      [:div.effect-banner.awaiting-answer
       (if is-my-turn?
         "Question asked! You must draw to answer."
         (str "Question asked! Waiting for " current-player-name " to draw."))])))

(defn- suit-picker
  "Render suit selection buttons."
  [short-code]
  [:div.mt-2
   [:p {:style "font-weight: 500;"} "Select a suit:"]
   [:div.suit-picker
    (for [suit [:hearts :diamonds :clubs :spades]]
      [:form {:method "post" :action (str "/games/" short-code "/select-suit")}
       [:input {:type "hidden" :name "suit" :value (name suit)}]
       [:button.btn.btn-secondary {:type "submit"}
        (str (suit-symbol suit) " " (clojure.string/capitalize (name suit)))]])]])

(defn- kadi-toggle-inline
  "Quiet inline Kadi toggle - no yellow background."
  []
  [:label.kadi-toggle-inline
   [:input {:type "checkbox" :name "declare-kadi" :id "declare-kadi"}]
   [:span.toggle-slider-sm]
   [:span.kadi-toggle-text-sm "Declare Kadi"]])

(defn game-play-content
  "Game play content fragment - used for initial render and HTMX polling updates.
   Returns HTML string with HTMX attributes for auto-refresh when not player's turn."
  [{:keys [player game]}]
  (let [state (:state game)
        current-player-idx (game/current-player-index state)
        players (:players state)
        current-player (get players current-player-idx)
        my-player (first (filter #(= (:id player) (:id %)) players))
        my-hand (when player (game/get-hand state (:id player)))
        is-my-turn? (= (:id player) (:id current-player))
        top-card (last (get-in state [:zones :played-stack]))
        deck-count (count (get-in state [:zones :deck]))
        direction (:direction state)
        has-select-suit? (game/has-effect? state :select-suit)
        has-penalty? (game/has-effect? state :penalty)
        has-awaiting-answer? (game/has-effect? state :awaiting-answer)
        penalty-effect (game/get-effect state :penalty)
        penalty-draw-count (when penalty-effect
                             (case (:penalty-type penalty-effect) :two 2 :three 3 0))
        game-finished? (= :finished (:status state))
        ;; Only poll when it's NOT my turn and game is NOT finished
        should-poll? (and (not is-my-turn?) (not game-finished?))]
    (str
     (h/html
      [:div {:id "game-content"
             :class (str "game-content"
                         (when (and is-my-turn? (not game-finished?)) " is-active-turn")
                         (when (and (not is-my-turn?) (not game-finished?)) " is-waiting"))
             :hx-get (when should-poll? (str "/games/" (:short_code game) "/state"))
             :hx-trigger (when should-poll? "every 2s")
             :hx-swap "outerHTML"}
       ;; Game finished banner
       (when game-finished?
         (let [winner-player (first (filter #(= (:id %) (:winner state)) players))]
           [:div.game-finished-banner
            [:h2 "🎉 Game Finished! 🎉"]
            [:p "Winner: " (:name winner-player)]
            [:a.btn {:href "/games"} "Back to Games"]]))
       [:div.game-state-zone
        [:div.game-code-chip (:short_code game)]
        (effect-banner state {:is-my-turn? is-my-turn?
                              :current-player-name (:name current-player)})
        (when top-card
          [:div.hero-card-area
           [:div {:class (str "top-card " (name (:suit top-card)))}
            (card-display top-card)]])
        [:p.game-meta
         (str deck-count " cards in deck")
         (when direction
           (str " • " (clojure.string/capitalize (name direction))))]]

       [:div.scoreboard
        (for [[idx p] (map-indexed vector players)]
          (let [is-current (= idx current-player-idx)
                is-kadi (= :kadi (:status p))
                is-me (= (:id p) (:id player))
                hand-count (count (game/get-hand state (:id p)))]
            [:div {:class (str "scoreboard__row"
                               (when is-current " scoreboard__row--active")
                               (when is-kadi " scoreboard__row--kadi"))}
             [:span.scoreboard__name (:name p)]
             [:span.scoreboard__cards
              (for [_ (range hand-count)]
                [:span.scoreboard__mini-card])]
             [:span.scoreboard__status
              (cond
                (and is-current is-me) "your turn"
                is-kadi "Kadi"
                :else (str hand-count " cards"))]]))]

       (when my-player
         [:div.card {:id "my-hand"}
          [:h3 "Your Hand"]
          (when (and (not is-my-turn?) (not game-finished?))
            [:p.waiting-label (str "Waiting for " (:name current-player) "...")])

          (cond
            ;; Cardless: must draw, no hand to show
            (= :cardless (:status my-player))
            (when (and is-my-turn? (not game-finished?))
              [:div.action-area
               [:div.effect-banner.cardless-warning
                [:p {:style "margin: 0; font-weight: bold;"}
                 "⚠️ You are CARDLESS — you must draw a card first!"]]
               [:div.action-buttons
                [:form {:method "post" :action (str "/games/" (:short_code game) "/draw") :id "draw-form"
                        :style "flex: 1;"}
                 [:button.btn.btn-primary.btn-action {:type "submit"} "Draw Card"]]]])

            ;; Awaiting answer: draw to answer
            (and is-my-turn? has-awaiting-answer?)
            (list
             [:div.hand
              (for [card my-hand]
                [:div {:class (card-class card)}
                 (card-display card)])]
             [:div.action-area
              [:div.action-buttons
               [:form {:method "post" :action (str "/games/" (:short_code game) "/answer-question")
                       :style "flex: 1;"}
                [:button.btn.btn-primary.btn-action {:type "submit"} "Draw to Answer"]]]])

            ;; Suit selection
            (and is-my-turn? has-select-suit?)
            (list
             [:div.hand
              (for [card my-hand]
                [:div {:class (card-class card)}
                 (card-display card)])]
             (suit-picker (:short_code game)))

            ;; Normal play or penalty blocking
            :else
            [:form {:method "post" :action (str "/games/" (:short_code game) "/play")
                    :id "play-form"}
             ;; Hidden input to track ordered card IDs
             [:input {:type "hidden" :name "ordered-cards" :id "ordered-cards" :value ""}]
             [:div.hand
              (for [card my-hand]
                [:label
                 [:input {:type "checkbox" :name "cards" :value (cards/card->id card)
                          :style "display: none"
                          :disabled (not is-my-turn?)
                          :data-card-id (cards/card->id card)
                          :class "card-checkbox"}]
                 [:div {:class (card-class card)}
                  (card-display card)]])]

             ;; Unified action area
             (when (and is-my-turn? (not game-finished?))
               (if has-penalty?
                 ;; Penalty: block + accept side-by-side
                 [:div.action-area
                  [:div.action-buttons
                   [:button.btn.btn-primary.btn-action-block
                    {:type "submit" :disabled true :data-penalty "true" :id "play-btn"}
                    "Select a Card to Block"]
                   [:button.btn.btn-danger-outline.btn-action-accept
                    {:type "submit"
                     :formaction (str "/games/" (:short_code game) "/accept-penalty")}
                    (str "Accept — Draw " penalty-draw-count)]]
                  (kadi-toggle-inline)]
                 ;; Normal: play + draw side-by-side
                 [:div.action-area
                  [:div.action-buttons
                   [:button.btn.btn-primary.btn-action-block
                    {:type "submit" :id "play-btn"}
                    "Play Selected"]
                   [:button.btn.btn-secondary.btn-action-accept
                    {:type "submit"
                     :formaction (str "/games/" (:short_code game) "/draw")}
                    "Draw Card"]]
                  (kadi-toggle-inline)]))])])]))))

(defn game-play-page
  "Live game page - wraps game-play-content fragment in layout."
  [{:keys [player game flash]}]
  (layout {:title (str "Game " (:short_code game)) :player player :flash flash}
          (raw-string (game-play-content {:player player :game game}))))

