(ns kadi.routes-auth-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [kadi.handlers :as handlers]
            [kadi.views :as views]
            [kadi.db :as db]
            [kadi.game :as game]))

;; Isolated test database: never touch the dev/prod kadi.db, and start
;; each test from an empty DB so fixed short-codes can't collide.
(def test-db-file "test-routes-auth.db")

;; SET THE DEFAULT DB TO TEST DB AT NAMESPACE LOAD TIME
(alter-var-root #'db/*db-spec* (constantly {:dbtype "sqlite" :dbname test-db-file}))

;; Initialize the test database schema
(db/init!)

(defn with-test-db [f]
  ;; Re-assert our DB: alter-var-root at load time loses to whichever
  ;; test namespace loads last, so bind per-test instead of per-load.
  (alter-var-root #'db/*db-spec* (constantly {:dbtype "sqlite" :dbname test-db-file}))
  (let [file (java.io.File. test-db-file)]
    (when (.exists file)
      (.delete file)))
  (db/init!)
  (try
    (f)
    (finally
      (let [file (java.io.File. test-db-file)]
        (when (.exists file)
          (.delete file))))))

(use-fixtures :each with-test-db)

(defn make-game-with-code [code]
  (db/create-game! {:player {:id 1 :name "Creator"}
                    :short-code code}))

(deftest protect-game-route-requires-auth
  (testing "unauthenticated access redirects to signin"
    (let [code "ABC123"
          _    (make-game-with-code code)
          req  {:request-method :get
                :path-params {:code code}}
          resp ((handlers/require-auth handlers/get-game) req)]
      (is (= 302 (:status resp)))
      (is (= "/auth/signin" (get-in resp [:headers "Location"])))))

  (testing "authenticated access returns 200"
    (let [code "XYZ789"
          game (make-game-with-code code)
          suffix (str (System/currentTimeMillis))
          player (db/create-player! {:name (str "alice" (subs suffix (- (count suffix) 5)))
                                      :email (str "alice" suffix "@example.com")})
          req {:request-method :get
               :path-params {:code code}
               :session {:player-id (:id player)}}
          resp ((handlers/require-auth handlers/get-game) req)]
      (is (= 200 (:status resp)))
      (is (string? (:body resp))))))

(deftest send-signin-link-identifier-test
  (testing "username identifier resolves to email and sends link"
    (with-redefs [db/get-player-by-username (fn [_] {:id 1 :name "alice" :email "alice@test.com"})
                  db/create-auth-token! (fn [_] nil)]
      (let [resp (handlers/send-signin-link {:form-params {"identifier" "alice"}})]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "alice@test.com")))))

  (testing "email identifier still works (old email param too)"
    (with-redefs [db/create-auth-token! (fn [_] nil)]
      (let [resp (handlers/send-signin-link {:form-params {"identifier" "a@test.com"}})]
        (is (= 200 (:status resp))))
      (let [resp (handlers/send-signin-link {:form-params {"email" "b@test.com"}})]
        (is (= 200 (:status resp))))))

  (testing "unknown username redirects with error"
    (with-redefs [db/get-player-by-username (fn [_] nil)]
      (let [resp (handlers/send-signin-link {:form-params {"identifier" "nobody"}})]
        (is (= 302 (:status resp)))))))

(deftest signin-page-identifier-test
  (testing "sign-in form accepts email or username"
    (let [html (views/signin-page {})]
      (is (str/includes? html "name=\"identifier\""))
      (is (str/includes? html "username")))))

(defn- render-state [state player-id]
  (views/game-play-content {:player {:id player-id}
                            :game {:short_code "TEST" :state state}}))

(defn- live-game []
  (-> (game/new-game "TEST")
      (#(:ok (game/join-player % {:id 1 :name "Alice"})))
      (#(:ok (game/join-player % {:id 2 :name "Bob"})))
      (game/start-game {})))

(deftest game-play-content-render-test
  (testing "normal turn renders play actions and status"
    (let [html (render-state (live-game) 1)]
      (is (str/includes? html "Play Selected"))
      (is (str/includes? html "your turn"))
      (is (str/includes? html "Draw Card"))))

  (testing "waiting renders poll endpoint and waiting label"
    (let [html (render-state (live-game) 2)]
      (is (str/includes? html "/games/TEST/state"))
      (is (str/includes? html "Waiting for"))))

  (testing "penalty renders block and accept actions"
    (let [html (render-state (-> (live-game)
                                 (update :effects conj {:type :penalty :penalty-type :two})) 1)]
      (is (str/includes? html "Select a Card to Block"))
      (is (str/includes? html "Accept — Draw 2"))
      (is (str/includes? html "Penalty active"))))

  (testing "cardless, answer and select-suit render their actions"
    (let [base (live-game)]
      (is (str/includes? (render-state (game/update-player base 1 #(assoc % :status :cardless)) 1)
                         "CARDLESS"))
      (is (str/includes? (render-state (update base :effects conj {:type :awaiting-answer}) 1)
                         "Draw to Answer"))
      (is (str/includes? (render-state (update base :effects conj {:type :select-suit}) 1)
                         "Select a suit:"))))

  (testing "finished game renders winner banner"
    (let [html (render-state (-> (live-game)
                                 (assoc :status :finished :winner 1)) 1)]
      (is (str/includes? html "Game Finished!"))
      (is (str/includes? html "Winner: ")))))
