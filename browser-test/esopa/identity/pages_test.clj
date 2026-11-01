(ns esopa.identity.pages-test
  "The identity pages in Chromium. htmx swaps pages in without reloading, focus
  follows for screen readers, forms work with the keyboard alone and without
  JavaScript, and axe-core finds no WCAG violations, in light and dark themes. Every
  test also fails on console errors, script errors and failed asset requests (see
  esopa.browser/with-esopa)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [esopa.browser :as browser])
  (:import (com.microsoft.playwright Page Route)))

(use-fixtures :once browser/with-browser)
(use-fixtures :each browser/with-esopa)

(def ^:private password "correct horse battery")

(defn- heading
  [^Page page]
  (.textContent page "h1"))

(deftest boosted-links-test
  (let [page (browser/open-page)]
    (browser/visit page "/")
    (browser/mark-document! page)
    (browser/click! page "nav a[href='/register']")
    (testing "htmx swaps the next page in instead of loading it"
      (is (browser/same-document? page))
      (is (= "/register" (browser/path page)))
      (is (browser/focused? page "h1") "focus moves to the new page's heading, as a page load would")
      (is (= "Register · esopa" (.title page))))
    (testing "Back shows the previous page again"
      (browser/back! page)
      (is (= "/" (browser/path page)))
      (is (= "Welcome" (heading page))))))

(deftest form-problem-test
  (let [page (browser/open-page)]
    (browser/visit page "/register")
    (browser/mark-document! page)
    (browser/submit! page "/register" {:email "ada@example.com" :password "short"})
    (testing "a form with a problem comes back swapped in"
      (is (browser/same-document? page))
      (is (browser/focused? page "#problem") "the problem takes focus, so screen readers read it")
      (is (= "Use at least 8 characters." (.textContent page "#problem")))
      (is (= "true" (.getAttribute page "#password" "aria-invalid")))
      (is (= "ada@example.com" (.inputValue page "#email"))))
    (testing "show password works on the form swapped in"
      (.check page "input[data-show-password]")
      (is (= "text" (.getAttribute page "#password" "type")))
      (.uncheck page "input[data-show-password]")
      (is (= "password" (.getAttribute page "#password" "type"))))))

(deftest busy-button-test
  (let [page (browser/open-page)
        held (atom nil)]
    (browser/visit page "/register")
    (.route page "**/register" (browser/consumer #(reset! held %)))
    (.fill page "#email" "ada@example.com")
    (.fill page "#password" password)
    (.click page "form[action='/register'] button")
    (browser/wait-until page #(some? @held))
    (testing "while a form is being submitted, its button is marked busy"
      (is (= "true" (.getAttribute page "form[action='/register'] button" "aria-busy"))))
    (.resume ^Route @held)
    (is (browser/eventually? page "location.pathname === '/check-email'"))))

(deftest register-confirm-log-in-and-out-test
  (let [page (browser/open-page)]
    (browser/visit page "/register")
    (browser/mark-document! page)
    (browser/submit! page "/register" {:email "ada@example.com" :password password})
    (testing "after a form is posted, the address bar shows where esopa redirected"
      (is (= "/check-email" (browser/path page)))
      (is (browser/same-document? page)))

    (browser/visit page (browser/last-link))
    (browser/mark-document! page)
    (browser/click! page "form[action='/confirm'] button")
    (testing "a message after a redirect follows the heading, which has focus"
      (is (= "/login" (browser/path page)))
      (is (browser/focused? page "h1"))
      (is (= "Your email address is confirmed. You can log in now."
             (.textContent page "h1 + [role=status]"))))

    (browser/submit! page "/login" {:email "ada@example.com" :password password})
    (is (= "/" (browser/path page)))
    (is (str/includes? (.textContent page "main") "logged in as ada@example.com"))
    (browser/click! page "nav button")
    (is (= "Log in" (.textContent page "nav a[href='/login']")))
    (is (browser/same-document? page) "from confirming on, nothing was loaded")))

(deftest expired-form-test
  (let [page (browser/open-page)]
    (browser/visit page "/register")
    (browser/submit! page "/register" {:email "ada@example.com" :password password})
    (browser/visit page (browser/last-link))
    (browser/click! page "form[action='/confirm'] button")
    (browser/submit! page "/login" {:email "ada@example.com" :password password})
    (browser/visit page "/password/change")
    (browser/mark-document! page)
    (browser/expire-form! page "/password/change")
    (browser/submit! page "/password/change" {:current-password password :new-password "new correct horse"})
    (testing "an expired form gets a page that explains, swapped in like any other"
      (is (browser/same-document? page))
      (is (= "This form has expired" (heading page)))
      (is (browser/focused? page "h1")))
    (testing "the visitor is still logged in, and the page's own forms work"
      (is (= "ada@example.com" (.textContent page "nav ul:last-child li:first-child")))
      (browser/click! page "nav button")
      (is (= "Log in" (.textContent page "nav a[href='/login']"))))))

(deftest unreachable-test
  (let [page (browser/open-page)]
    (browser/visit page "/")
    (.setOffline (.context page) true)
    (.click page "nav a[href='/register']")
    (testing "when esopa can't be reached, the page says so, and the message takes focus"
      (is (browser/focused? page "#unreachable"))
      (is (= "esopa couldn't be reached. Check your connection and try again."
             (.textContent page "#unreachable")))
      (is (= "/" (browser/path page)))
      (is (some #(str/includes? % "htmx:error") (browser/take-problems! page))
          "htmx logs the failed request"))
    (testing "once it can be reached again, links work"
      (.setOffline (.context page) false)
      (browser/click! page "nav a[href='/register']")
      (is (= "Register" (heading page))))))

(deftest keyboard-test
  (let [page     (browser/open-page)
        keyboard (.keyboard page)]
    (browser/visit page "/")
    (testing "Tab reaches the register link, and Enter follows it"
      (is (browser/tab-to page "nav a[href='/register']"))
      (.press keyboard "Enter")
      (is (browser/focused? page "h1")))
    (testing "from the heading, Tab moves through the form in order"
      (.press keyboard "Tab")
      (is (browser/focused? page "#email"))
      (.type keyboard "ada@example.com")
      (.press keyboard "Tab")
      (is (browser/focused? page "#password"))
      (.type keyboard password)
      (.press keyboard "Tab")
      (is (browser/focused? page "input[data-show-password]"))
      (.press keyboard "Space")
      (is (= "text" (.getAttribute page "#password" "type")))
      (.press keyboard "Tab")
      (is (browser/focused? page "form[action='/register'] button"))
      (.press keyboard "Enter")
      (is (browser/eventually? page "location.pathname === '/check-email'")))))

(deftest without-javascript-test
  (let [page (browser/open-page :java-script? false)]
    (browser/visit page "/register")
    (testing "forms are plain HTML forms, which the browser posts itself"
      (.fill page "#email" "ada@example.com")
      (.fill page "#password" "short")
      (.click page "form[action='/register'] button")
      (is (= "Use at least 8 characters." (.textContent page "#problem")))
      (.fill page "#password" password)
      (.click page "form[action='/register'] button")
      (.waitForURL page "**/check-email")
      (is (= "Check your email" (heading page))))))

(defn- every-page
  "Takes `page` through every page esopa has, in each state it can be in, and calls
  (check description) on each."
  [page check]
  (let [visit (fn [path description]
                (browser/visit page path)
                (check description))
        post  (fn [action fields description]
                (browser/submit! page action fields)
                (check description))]
    (visit "/" "home")
    (visit "/register" "register")
    (post "/register" {:email "ada@example.com" :password "short"} "register, with a problem")
    (post "/register" {:email "ada@example.com" :password password} "check your email")
    (visit (browser/last-link) "confirm")
    (browser/click! page "form[action='/confirm'] button")
    (check "log in, with a message")
    (post "/login" {:email "ada@example.com" :password "wrong horse"} "log in, with a problem")
    (post "/login" {:email "ada@example.com" :password password} "home, logged in")
    (visit "/admin/accounts" "you don't have access")
    (browser/grant-role! "ada@example.com" "admin")
    (visit "/admin/accounts" "accounts")
    (.uncheck page "form:has(button[aria-label='Save roles for ada@example.com']) input[value='admin']")
    (browser/click! page "button[aria-label='Save roles for ada@example.com']")
    (check "accounts, with a problem")
    (visit "/password/change" "change password")
    (post "/password/change" {:current-password password :new-password "short"} "change password, with a problem")
    (browser/click! page "nav button")
    (visit "/confirm/resend" "get a new confirmation link")
    (visit "/no-such-page" "page not found")
    (visit "/password/forgot" "reset your password")
    (browser/expire-form! page "/password/forgot")
    (post "/password/forgot" {:email "ada@example.com"} "this form has expired")
    (browser/visit page "/password/forgot")
    (browser/submit! page "/password/forgot" {:email "ada@example.com"})
    (visit (browser/last-link) "choose a new password")
    (visit "/password/reset?token=garbage" "a link that doesn't work")
    (visit "/register" "register again")
    (browser/submit! page "/register" {:email "eve@example.com" :password password})
    (visit "/login" "log in again")
    (post "/login" {:email "eve@example.com" :password password} "confirm your email address first")
    (browser/visit page "/login")
    (dotimes [_ 5]
      (browser/submit! page "/login" {:email "ada@example.com" :password "wrong horse"}))
    (post "/login" {:email "ada@example.com" :password password} "too many failed attempts")))

(deftest accessibility-light-test
  (let [page (browser/open-page :color-scheme :light)]
    (every-page page (fn [description]
                       (testing description
                         (is (empty? (browser/accessibility-violations page))))))))

(deftest accessibility-dark-test
  (let [page (browser/open-page :color-scheme :dark)]
    (every-page page (fn [description]
                       (testing description
                         (is (empty? (browser/accessibility-violations page))))))))

(deftest phone-width-test
  (let [page (browser/open-page :width 375 :height 700)]
    (every-page page (fn [description]
                       (testing description
                         (is (browser/fits-width? page) "nothing scrolls sideways"))))))
