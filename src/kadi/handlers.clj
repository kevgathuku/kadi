(ns kadi.handlers
  "HTTP request handlers."
  (:require [kadi.db :as db]
            [kadi.game :as game]
            [kadi.auth :as auth]
            [kadi.views :as views]
            [kadi.cards :as cards]
            [clojure.string :as str]
            [ring.util.response :as resp]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- html-response [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- redirect
  ([path] (resp/redirect path))
  ([path flash]
   (-> (resp/redirect path)
       (assoc :flash flash))))

(defn- with-session [response session]
  (assoc response :session session))

(defn- parse-form [request]
  (or (:form-params request)
      (:params request)
      {}))

(defn require-auth
  "Middleware helper - redirects to signin if not authenticated."
  [handler]
  (fn [request]
    (if (auth/authenticated? request)
      (handler request)
      (redirect "/auth/signin" {:type :error :message "Please sign in to continue"}))))

(defn- player-in-game?
  "Check if player is in the game."
  [player game]
  (some #(= (:id player) (:id %)) (get-in game [:state :players])))

(defn- check-game-status
  "Check that game exists and has expected status. Returns [game error] pair."
  [short-code expected-status]
  (if-let [game (db/get-game-by-code short-code)]
    (let [actual-status (game/game-status (:state game))]
      (if (= actual-status expected-status)
        [game nil]
        [game (str "Game is in " (name actual-status) " status, not " (name expected-status))]))
    [nil "Game not found"]))

;; =============================================================================
;; Auth Handlers
;; =============================================================================

(defn signin-page [request]
  (if (auth/authenticated? request)
    (redirect "/")
    (html-response
     (views/signin-page {:flash (:flash request)}))))

(defn send-signin-link [request]
  (let [params (parse-form request)
        identifier (some-> (or (get params "identifier") (get params "email")
                               (get params :identifier) (get params :email))
                           str/trim)]
    (if-let [email (auth/resolve-identifier->email identifier)]
      (let [token (auth/create-signin-token! email)]
        (auth/send-signin-email! {:email email :token token})
        (html-response
         (views/check-email-page {:email email})))
      (redirect "/auth/signin" {:type :error :message "Enter a valid email or username"}))))

(defn verify-signin [request]
  (let [token (get-in request [:path-params :token])]
    (if-let [player (auth/verify-token! token)]
      (do
        (println "Auth success - player:" player)
        (-> (redirect "/")
            (with-session {:player-id (:id player)})))
      (do
        (println "Auth failed - token:" token)
        (html-response
         (views/auth-error-page {:message "This sign-in link is invalid or has expired."}))))))

(defn signout [_request]
  (-> (redirect "/")
      (with-session nil)))

;; =============================================================================
;; Page Handlers
;; =============================================================================

(defn index [request]
  (if-let [player (auth/current-player request)]
    (let [all-games (db/get-player-games (:id player))
          active-games (remove #(= :finished (get-in % [:state :status])) all-games)]
      (html-response
       (views/home-page {:player player :games active-games :flash (:flash request)})))
    (html-response
     (views/guest-home-page {:flash (:flash request)}))))

;; =============================================================================
;; Game Handlers
;; =============================================================================

(defn list-games [request]
  (if-let [player (auth/current-player request)]
    (let [all-games (db/get-player-games (:id player))
          active-games (remove #(= :finished (get-in % [:state :status])) all-games)
          finished-games (filter #(= :finished (get-in % [:state :status])) all-games)]
      (html-response
       (views/games-list-page {:player player
                               :games active-games
                               :finished-games finished-games
                               :flash (:flash request)})))
    (redirect "/auth/signin")))

(defn create-game [request]
  (if-let [player (auth/current-player request)]
    (let [event-id (str (java.util.UUID/randomUUID))
          timestamp (java.time.Instant/now)
          action {:player (select-keys player [:id :name])
                  :event-id event-id
                  :timestamp timestamp}
          result (db/create-game! action)]
      (redirect (str "/games/" (:short-code result))))
    (redirect "/auth/signin")))

(defn get-game [request]
  (let [short-code (get-in request [:path-params :code])
        player (auth/current-player request)]
    (if-let [game (db/get-game-by-code short-code)]
      (let [status (game/game-status (:state game))
            flash (:flash request)]
        (html-response
         (if (= :lobby status)
           (views/game-lobby-page {:player player :game game :flash flash})
           (views/game-play-page {:player player :game game :flash flash}))))
      (redirect "/games" {:type :error :message "Game not found"}))))

(defn get-players-fragment [request]
  (let [short-code (get-in request [:path-params :code])]
    (if-let [game (db/get-game-by-code short-code)]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (views/players-list-fragment {:players (get-in game [:state :players])})}
      {:status 404 :body "Game not found"})))

(defn get-lobby-status-fragment [request]
  "HTMX endpoint for refreshing lobby status - includes player list and action buttons.
   When the game has started, responds with HX-Redirect to send players to the game page."
  (let [short-code (get-in request [:path-params :code])
        player (auth/current-player request)]
    (if-let [game (db/get-game-by-code short-code)]
      (if (not= :lobby (game/game-status (:state game)))
        {:status 200
         :headers {"HX-Redirect" (str "/games/" short-code)}}
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (views/lobby-status-fragment {:player player :game game})})
      {:status 404 :body "Game not found"})))

(defn get-game-state-fragment [request]
  "HTMX endpoint for polling game state updates - returns game-play-content fragment."
  (let [short-code (get-in request [:path-params :code])
        player (auth/current-player request)]
    (if-let [game (db/get-game-by-code short-code)]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (views/game-play-content {:player player :game game})}
      {:status 404 :body "Game not found"})))

(defn join-game [request]
  (if-let [player (auth/current-player request)]
    (let [short-code (get-in request [:path-params :code])
          game (db/get-game-by-code short-code)]
      (if game
        (let [already-joined? (game/get-player (:state game) (:id player))
              result (if already-joined?
                       {:ok (:state game)} ; Silent success if already joined
                       (game/join-player (:state game) player))]
          (if (:error result)
            (redirect (str "/games/" short-code) {:type :error :message (:error result)})
            (do
              (when-not already-joined?
                (let [event-id (str (java.util.UUID/randomUUID))
                      timestamp (java.time.Instant/now)
                      action {:player (select-keys player [:id :name])
                              :timestamp timestamp}]
                  (db/append-event! (:id game) event-id :join-game timestamp action)
                  (db/add-player-to-game! (:id game) (:id player))))
              (redirect (str "/games/" short-code)))))
        (redirect "/games" {:type :error :message "Game not found"})))
    (redirect "/auth/signin")))

(defn join-page [request]
  "Display the join game page with code input."
  (let [player (auth/current-player request)]
    (html-response (views/join-page {:player player :flash (:flash request)}))))

(defn join-game-by-code [request]
  "Join a game by short code from the join form."
  (if-let [player (auth/current-player request)]
    (let [short-code (clojure.string/upper-case (clojure.string/trim (get-in request [:params :code] "")))
          game (when (seq short-code) (db/get-game-by-code short-code))]
      (if game
        (let [already-joined? (game/get-player (:state game) (:id player))
              result (if already-joined?
                       {:ok (:state game)}
                       (game/join-player (:state game) player))]
          (if (:error result)
            (redirect "/games/join" {:type :error :message (:error result)})
            (do
              (when-not already-joined?
                (let [event-id (str (java.util.UUID/randomUUID))
                      timestamp (java.time.Instant/now)
                      action {:player (select-keys player [:id :name])
                              :timestamp timestamp}]
                  (db/append-event! (:id game) event-id :join-game timestamp action)
                  (db/add-player-to-game! (:id game) (:id player))))
              (redirect (str "/games/" short-code)))))
        (redirect "/games/join" {:type :error :message (if (seq short-code)
                                                         "Game not found"
                                                         "Please enter a game code")})))
    (redirect "/auth/signin")))

(defn start-game [request]
  (if-let [player (auth/current-player request)]
    (let [short-code (get-in request [:path-params :code])
          game (db/get-game-by-code short-code)]
      (if game
        (if-not (player-in-game? player game)
          (redirect "/games" {:type :error :message "You are not in this game"})
          (let [result (game/start-game-cmd (:state game))]
            (if (:error result)
              (redirect (str "/games/" short-code) {:type :error :message (:error result)})
              (do
                (let [event-id (str (java.util.UUID/randomUUID))
                      timestamp (java.time.Instant/now)]
                  (db/append-event! (:id game) event-id :start-game timestamp {:timestamp timestamp}))
                (redirect (str "/games/" short-code))))))
        (redirect "/games" {:type :error :message "Game not found"})))
    (redirect "/auth/signin")))

(defn play-cards [request]
  (if-let [player (auth/current-player request)]
    (let [short-code (get-in request [:path-params :code])
          [game error-msg] (check-game-status short-code :live)]
      (if error-msg
        (redirect "/games" {:type :error :message error-msg})
        (if-not (player-in-game? player game)
          (redirect "/games" {:type :error :message "You are not in this game"})
          (let [params (parse-form request)
                ;; Use ordered-cards parameter which preserves selection order
                ordered-cards-str (get params "ordered-cards")
                card-ids (if (and ordered-cards-str (not= ordered-cards-str ""))
                           (clojure.string/split ordered-cards-str #",")
                           [])
                parsed-cards (keep cards/id->card card-ids)
                ;; Parse declare-kadi checkbox (will be "on" if checked)
                declare-kadi? (= "on" (get params "declare-kadi"))
                ;; Capture hand size before play (for event tracking)
                hand-size-before (count (game/get-hand (:state game) (:id player)))
                result (game/play-cards-cmd (:state game) (:id player) parsed-cards
                                            :declare-kadi? declare-kadi?)]
            (if (:error result)
              (redirect (str "/games/" short-code) {:type :error :message (:error result)})
              (do
                (let [event-id (str (java.util.UUID/randomUUID))
                      timestamp (java.time.Instant/now)]
                  (db/append-event! (:id game) event-id :play-cards timestamp
                                    {:player-id (:id player)
                                     :cards parsed-cards
                                     :declare-kadi? declare-kadi?
                                     :hand-size-before hand-size-before
                                     :timestamp timestamp}))
                (redirect (str "/games/" short-code))))))))
    (redirect "/auth/signin")))

(defn draw-card [request]
  (if-let [player (auth/current-player request)]
    (let [short-code (get-in request [:path-params :code])
          [game error-msg] (check-game-status short-code :live)]
      (if error-msg
        (redirect "/games" {:type :error :message error-msg})
        (if-not (player-in-game? player game)
          (redirect "/games" {:type :error :message "You are not in this game"})
          (let [params (parse-form request)
                ;; Parse maintain-kadi checkbox (will be "on" if checked)
                maintain-kadi? (= "on" (get params "maintain-kadi"))
                result (game/draw-card-cmd (:state game) (:id player)
                                           :maintain-kadi? maintain-kadi?)]
            (if (:error result)
              (redirect (str "/games/" short-code) {:type :error :message (:error result)})
              (do
                (let [event-id (str (java.util.UUID/randomUUID))
                      timestamp (java.time.Instant/now)]
                  (db/append-event! (:id game) event-id :draw-card timestamp
                                    {:player-id (:id player)
                                     :maintain-kadi? maintain-kadi?
                                     :timestamp timestamp}))
                (redirect (str "/games/" short-code))))))))
    (redirect "/auth/signin")))

(defn select-suit [request]
  (if-let [player (auth/current-player request)]
    (let [short-code (get-in request [:path-params :code])
          [game error-msg] (check-game-status short-code :live)]
      (if error-msg
        (redirect "/games" {:type :error :message error-msg})
        (if-not (player-in-game? player game)
          (redirect "/games" {:type :error :message "You are not in this game"})
          (let [params (parse-form request)
                suit-str (get params "suit")
                suit (when suit-str (keyword suit-str))
                result (game/select-suit-cmd (:state game) suit)]
            (if (:error result)
              (redirect (str "/games/" short-code) {:type :error :message (:error result)})
              (do
                (let [event-id (str (java.util.UUID/randomUUID))
                      timestamp (java.time.Instant/now)]
                  (db/append-event! (:id game) event-id :select-suit timestamp
                                    {:suit suit
                                     :timestamp timestamp}))
                (redirect (str "/games/" short-code))))))))
    (redirect "/auth/signin")))

(defn accept-penalty [request]
  (if-let [player (auth/current-player request)]
    (let [short-code (get-in request [:path-params :code])
          [game error-msg] (check-game-status short-code :live)]
      (if error-msg
        (redirect "/games" {:type :error :message error-msg})
        (if-not (player-in-game? player game)
          (redirect "/games" {:type :error :message "You are not in this game"})
          (let [result (game/accept-penalty-cmd (:state game) (:id player))]
            (if (:error result)
              (redirect (str "/games/" short-code) {:type :error :message (:error result)})
              (do
                (let [event-id (str (java.util.UUID/randomUUID))
                      timestamp (java.time.Instant/now)]
                  (db/append-event! (:id game) event-id :accept-penalty timestamp
                                    {:player-id (:id player)
                                     :timestamp timestamp}))
                (redirect (str "/games/" short-code))))))))
    (redirect "/auth/signin")))

(defn answer-question [request]
  (if-let [player (auth/current-player request)]
    (let [short-code (get-in request [:path-params :code])
          [game error-msg] (check-game-status short-code :live)]
      (if error-msg
        (redirect "/games" {:type :error :message error-msg})
        (if-not (player-in-game? player game)
          (redirect "/games" {:type :error :message "You are not in this game"})
          (let [result (game/answer-question-cmd (:state game) (:id player))]
            (if (:error result)
              (redirect (str "/games/" short-code) {:type :error :message (:error result)})
              (do
                (let [event-id (str (java.util.UUID/randomUUID))
                      timestamp (java.time.Instant/now)]
                  (db/append-event! (:id game) event-id :answer-question timestamp
                                    {:player-id (:id player)
                                     :timestamp timestamp}))
                (redirect (str "/games/" short-code))))))))
    (redirect "/auth/signin")))
