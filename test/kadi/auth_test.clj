(ns kadi.auth-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [kadi.auth :as auth]
            [kadi.schema :as schema]
            [malli.core :as m]
            [kadi.db :as db])
  (:import [java.time Instant]))

;; =============================================================================
;; Pure functions (no DB, no side effects)
;; =============================================================================

(deftest generate-token-test
  (testing "generates a non-empty string"
    (let [token (#'auth/generate-token)]
      (is (string? token))
      (is (pos? (count token)))))

  (testing "generates unique tokens"
    (let [tokens (repeatedly 10 #'auth/generate-token)]
      (is (= 10 (count (set tokens)))))))

(def ^:private test-now (Instant/parse "2024-06-01T12:00:00Z"))

(deftest expired?-test
  (testing "returns true for timestamp before now"
    (is (true? (#'auth/expired? "2024-06-01T11:00:00Z" test-now))))

  (testing "returns false for timestamp after now"
    (is (false? (#'auth/expired? "2024-06-01T13:00:00Z" test-now)))))

(deftest used?-test
  (testing "returns true when 1"
    (is (true? (#'auth/used? 1))))

  (testing "returns true for any positive value"
    (is (true? (#'auth/used? 2))))

  (testing "returns false when 0"
    (is (false? (#'auth/used? 0))))

  (testing "returns false when nil"
    (is (false? (#'auth/used? nil)))))

(def ^:private valid-token-record
  {:id 1
   :email "test@example.com"
   :token "abc123"
   :expires_at "2024-06-01T13:00:00Z"
   :used 0
   :created_at "2024-01-01T00:00:00Z"})

(deftest valid-token?-test
  (testing "returns true when not expired and not used"
    (is (true? (#'auth/valid-token? valid-token-record test-now))))

  (testing "returns false when expired"
    (is (false? (#'auth/valid-token?
                 (assoc valid-token-record :expires_at "2024-06-01T11:00:00Z")
                 test-now))))

  (testing "returns false when used"
    (is (false? (#'auth/valid-token? (assoc valid-token-record :used 1) test-now))))

  (testing "returns false when nil"
    (is (false? (#'auth/valid-token? nil test-now))))

  (testing "returns false when record doesn't match schema"
    (is (false? (#'auth/valid-token?
                 {:expires_at "2024-06-01T13:00:00Z" :used 0}
                 test-now)))))

(deftest valid-email?-test
  (testing "accepts valid emails"
    (is (true? (auth/valid-email? "test@example.com")))
    (is (true? (auth/valid-email? "user.name+tag@domain.co.uk"))))

  (testing "rejects invalid emails"
    (is (false? (auth/valid-email? "")))
    (is (false? (auth/valid-email? "not-an-email")))
    (is (false? (auth/valid-email? "@missing-local.com")))
    (is (false? (auth/valid-email? "missing-domain@")))
    (is (false? (auth/valid-email? nil)))
    (is (false? (auth/valid-email? 42))))

  (testing "rejects emails with surrounding whitespace"
    (is (false? (auth/valid-email? " test@example.com")))
    (is (false? (auth/valid-email? "test@example.com ")))))

(deftest email->display-name-test
  (testing "extracts local part before @"
    (is (= "alice" (#'auth/email->display-name "alice@example.com"))))

  (testing "preserves dots and plus tags"
    (is (= "user.name+tag" (#'auth/email->display-name "user.name+tag@domain.com")))))

(deftest valid-username?-test
  (testing "accepts valid usernames"
    (is (true? (auth/valid-username? "alice")))
    (is (true? (auth/valid-username? "bob2")))
    (is (true? (auth/valid-username? "a_b_c"))))

  (testing "rejects invalid usernames"
    (is (false? (auth/valid-username? "ab")))
    (is (false? (auth/valid-username? "user.name")))
    (is (false? (auth/valid-username? "user name")))
    (is (false? (auth/valid-username? nil)))))

(deftest resolve-identifier->email-test
  (testing "passes emails through"
    (is (= "a@test.com" (auth/resolve-identifier->email "a@test.com"))))

  (testing "resolves username to email"
    (with-redefs [db/get-player-by-username (fn [_] {:id 1 :name "alice" :email "alice@test.com"})]
      (is (= "alice@test.com" (auth/resolve-identifier->email "alice")))))

  (testing "returns nil for unknown username"
    (with-redefs [db/get-player-by-username (fn [_] nil)]
      (is (nil? (auth/resolve-identifier->email "nobody"))))))

(deftest signin-url-test
  (testing "generates URL with token"
    (let [url (#'auth/signin-url "abc123")]
      (is (string? url))
      (is (.endsWith url "/auth/verify/abc123")))))

;; =============================================================================
;; Side-effecting functions (DB stubbed with with-redefs)
;; =============================================================================

(deftest create-signin-token!-test
  (testing "creates token and persists via db"
    (let [created-args (atom nil)]
      (with-redefs [db/create-auth-token! (fn [args] (reset! created-args args))]
        (let [token (auth/create-signin-token! "test@example.com")]
          (is (string? token))
          (is (pos? (count token)))
          (is (= "test@example.com" (:email @created-args)))
          (is (string? (:token @created-args)))
          (is (string? (:expires-at @created-args))))))))

(deftest find-or-create-player!-test
  (testing "returns existing player when found"
    (with-redefs [db/get-player-by-email (fn [_] {:id 1 :name "alice"})]
      (is (= {:id 1 :name "alice"}
             (#'auth/find-or-create-player! "alice@test.com")))))

  (testing "creates player when none exists"
    (let [created (atom nil)]
      (with-redefs [db/get-player-by-email (fn [_] nil)
                    db/get-player-by-username (fn [_] nil)
                    db/create-player!      (fn [args] (reset! created args)
                                             {:id 99 :name "bob"})]
        (let [result (#'auth/find-or-create-player! "bob@test.com")]
          (is (= {:id 99 :name "bob"} result))
          (is (= "bob" (:name @created)))
          (is (= "bob@test.com" (:email @created)))))))

  (testing "dedups username on collision"
    (let [created (atom nil)]
      (with-redefs [db/get-player-by-email (fn [_] nil)
                    db/get-player-by-username (fn [u] (when (= u "bob") {:id 1 :name "bob"}))
                    db/create-player! (fn [args] (reset! created args) {:id 100})]
        (#'auth/find-or-create-player! "bob@test.com")
        (is (= "bob2" (:name @created)))))))

(defn- future-timestamp
  "Return an ISO-8601 timestamp 1 hour from now."
  []
  (str (.plusSeconds (Instant/now) 3600)))

(defn- past-timestamp
  "Return an ISO-8601 timestamp 1 hour ago."
  []
  (str (.minusSeconds (Instant/now) 3600)))

(deftest verify-token!-test
  (testing "returns player for valid unused token"
    (let [marked-used (atom false)]
      (with-redefs [db/get-auth-token    (fn [_] {:id 1 :email "alice@test.com"
                                                  :token "valid-token"
                                                  :expires_at (future-timestamp)
                                                  :used 0
                                                  :created_at "2024-01-01T00:00:00Z"})
                    db/mark-token-used!  (fn [_] (reset! marked-used true))
                    db/get-player-by-email (fn [_] {:id 1 :name "alice"})]
        (let [result (auth/verify-token! "valid-token")]
          (is (= {:id 1 :name "alice"} result))
          (is (true? @marked-used))))))

  (testing "creates new player when none exists for email"
    (let [created-player (atom nil)]
      (with-redefs [db/get-auth-token      (fn [_] {:id 2 :email "new@test.com"
                                                    :token "new-token"
                                                    :expires_at (future-timestamp)
                                                    :used 0
                                                    :created_at "2024-01-01T00:00:00Z"})
                    db/mark-token-used!    (fn [_] nil)
                    db/get-player-by-email (fn [_] nil)
                    db/get-player-by-username (fn [_] nil)
                    db/create-player!      (fn [args] (reset! created-player args)
                                             {:id 99 :name "new"})]
        (let [result (auth/verify-token! "new-token")]
          (is (= {:id 99 :name "new"} result))
          (is (= "new" (:name @created-player)))
          (is (= "new@test.com" (:email @created-player)))))))

  (testing "returns nil for unknown token"
    (with-redefs [db/get-auth-token (fn [_] nil)]
      (is (nil? (auth/verify-token! "unknown-token")))))

  (testing "returns nil for expired token"
    (with-redefs [db/get-auth-token (fn [_] {:id 3 :email "x@test.com"
                                             :token "expired-token"
                                             :expires_at (past-timestamp)
                                             :used 0
                                             :created_at "2024-01-01T00:00:00Z"})]
      (is (nil? (auth/verify-token! "expired-token")))))

  (testing "returns nil for used token"
    (with-redefs [db/get-auth-token (fn [_] {:id 4 :email "x@test.com"
                                             :token "used-token"
                                             :expires_at (future-timestamp)
                                             :used 1
                                             :created_at "2024-01-01T00:00:00Z"})]
      (is (nil? (auth/verify-token! "used-token"))))))

(deftest send-signin-email!-test
  (testing "logs sign-in link in dev mode"
    (let [log-messages (atom [])
          orig-log* log/log*]
      (with-redefs [log/log* (fn [logger level throwable message]
                               (swap! log-messages conj message)
                               (orig-log* logger level throwable message))]
        (auth/send-signin-email! {:email "dev@test.com" :token "tok123"}))
      (let [output (str/join "\n" @log-messages)]
        (is (.contains output "SIGN-IN LINK"))
        (is (.contains output "Email: dev@test.com"))
        (is (.contains output "/auth/verify/tok123"))))))

(deftest current-player-test
  (testing "returns player when session has player-id"
    (with-redefs [db/get-player (fn [id] {:id id :name "Alice"})]
      (let [result (auth/current-player {:session {:player-id 42}})]
        (is (= {:id 42 :name "Alice"} result)))))

  (testing "returns nil when no session"
    (is (nil? (auth/current-player {}))))

  (testing "returns nil when no player-id in session"
    (is (nil? (auth/current-player {:session {}})))))

(deftest authenticated?-test
  (testing "true when session has valid player"
    (with-redefs [db/get-player (fn [id] {:id id :name "Alice"})]
      (is (true? (auth/authenticated? {:session {:player-id 1}})))))

  (testing "false when player not found in DB"
    (with-redefs [db/get-player (fn [_] nil)]
      (is (false? (auth/authenticated? {:session {:player-id 999}})))))

  (testing "false when no session"
    (is (false? (auth/authenticated? {}))))

  (testing "false when no player-id"
    (is (false? (auth/authenticated? {:session {}})))))

;; =============================================================================
;; Schema validation
;; =============================================================================

(deftest email-schema-test
  (testing "validates correct emails"
    (is (true? (m/validate schema/Email "test@example.com")))
    (is (true? (m/validate schema/Email "user.name+tag@domain.co.uk"))))

  (testing "rejects invalid emails"
    (is (false? (m/validate schema/Email "")))
    (is (false? (m/validate schema/Email "not-an-email")))
    (is (false? (m/validate schema/Email "@missing-local.com")))))

(deftest auth-token-schema-test
  (testing "validates a complete token record"
    (is (true? (m/validate schema/AuthToken valid-token-record))))

  (testing "rejects record with missing fields"
    (is (false? (m/validate schema/AuthToken (dissoc valid-token-record :email)))))

  (testing "rejects record with invalid email"
    (is (false? (m/validate schema/AuthToken (assoc valid-token-record :email "bad"))))))

(deftest signin-email-request-schema-test
  (testing "validates correct request"
    (is (true? (m/validate schema/SigninEmailRequest {:email "test@example.com" :token "abc"}))))

  (testing "rejects request with invalid email"
    (is (false? (m/validate schema/SigninEmailRequest {:email "bad" :token "abc"}))))

  (testing "rejects request with missing token"
    (is (false? (m/validate schema/SigninEmailRequest {:email "test@example.com"})))))
