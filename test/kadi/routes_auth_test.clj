(ns kadi.routes-auth-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [kadi.handlers :as handlers]
            [kadi.views :as views]
            [kadi.db :as db]
            [kadi.game :as game]))

;; Ensure DB schema exists for tests
(defn setup-db [_]
  (db/init!))

(use-fixtures :once setup-db)

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
