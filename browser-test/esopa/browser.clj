(ns esopa.browser
  "Drives esopa in Playwright's headless Chromium. Each test gets its own esopa on a
  free local port, backed by test-deps (see esopa.identity.service-test). Its pages
  record console errors and warnings, script errors and failed asset requests, and
  with-esopa fails the test on any of them."
  (:require [clojure.string :as str]
            [clojure.test :refer [is]]
            [esopa.identity.service-test :as service-test]
            [esopa.server :as server]
            [io.pedestal.connector :as connector])
  (:import (com.deque.html.axecore.playwright AxeBuilder)
           (com.deque.html.axecore.results Node Rule)
           (com.microsoft.playwright Browser Browser$NewContextOptions BrowserContext ConsoleMessage Page
                                     Playwright Playwright$CreateOptions Response)
           (com.microsoft.playwright.options ColorScheme)
           (java.net ServerSocket)
           (java.util.function BooleanSupplier Consumer)))

(def ^:dynamic *browser* nil)

(def ^:dynamic *esopa*
  "The esopa running for the current test: its :base-url, its :deps and the :pages opened on it."
  nil)

(defn with-browser
  "A :once fixture that launches headless Chromium for a namespace's tests. The mise
  task test:browser installs it, so Playwright doesn't download browsers itself."
  [f]
  (with-open [playwright (Playwright/create (.setEnv (Playwright$CreateOptions.)
                                                     {"PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" "1"}))]
    (binding [*browser* (.launch (.chromium playwright))]
      (f))))

(defn- free-port
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn with-esopa
  "An :each fixture that runs esopa for one test, on a free local port with fresh
  test-deps, and afterwards closes the pages the test opened. The test fails if one
  of them recorded a problem (see open-page)."
  [f]
  (let [port      (free-port)
        deps      (service-test/test-deps)
        connector (doto (server/create-connector {:host            "localhost"
                                                  :port            port
                                                  :session-key     (byte-array (range 16))
                                                  :cookie-secure?  false
                                                  :trusted-proxies 0
                                                  :identity-deps   deps})
                    (connector/start!))
        pages     (atom [])]
    (try
      (binding [*esopa* {:base-url (str "http://localhost:" port) :deps deps :pages pages}]
        (f))
      (finally
        (doseq [{:keys [^BrowserContext context problems]} @pages]
          (is (empty? @problems) "no console errors or warnings, script errors or failed asset requests")
          (.close context))
        (connector/stop! connector)))))

(defn consumer
  "`f` as a java.util.function.Consumer, for Playwright's event and route handlers."
  [f]
  (reify Consumer
    (accept [_ x] (f x))))

(defn- console-problem
  "The console message as a problem, or nil. Chromium also logs every response with an
  error status, like a form shown again with 422; failed assets show in their responses."
  [^ConsoleMessage message]
  (when (and (#{"error" "warning"} (.type message))
             (not (str/starts-with? (.text message) "Failed to load resource")))
    (str (.type message) ": " (.text message))))

(defn- asset-problem
  [^Response response]
  (when (and (<= 400 (.status response))
             (#{"script" "stylesheet" "image" "font"} (.resourceType (.request response))))
    (str (.status response) " for " (.url response))))

(def ^:private color-schemes
  {:light ColorScheme/LIGHT
   :dark  ColorScheme/DARK})

(defn open-page
  "A page on the running esopa, in a browser context of its own, so with its own
  cookies. Paths are relative to esopa. Options: :color-scheme, :light or :dark; the
  viewport's :width and :height; and :java-script? false to browse without JavaScript."
  [& {:keys [color-scheme width height java-script?]
      :or   {color-scheme :light width 1000 height 800 java-script? true}}]
  (let [{:keys [base-url pages]} *esopa*
        context  (.newContext ^Browser *browser*
                              (doto (Browser$NewContextOptions.)
                                (.setBaseURL base-url)
                                (.setColorScheme (color-schemes color-scheme))
                                (.setViewportSize (int width) (int height))
                                (.setJavaScriptEnabled java-script?)))
        page     (.newPage context)
        problems (atom [])
        record!  #(some->> % (swap! problems conj))]
    (.setDefaultTimeout context 5000.0)
    (.onConsoleMessage page (consumer #(record! (console-problem %))))
    (.onPageError page (consumer #(record! (str "script error: " %))))
    (.onResponse page (consumer #(record! (asset-problem %))))
    (swap! pages conj {:context context :problems problems})
    page))

(defn path
  "The path of the page `page` shows, like /login."
  [^Page page]
  (.getPath (java.net.URI. (.url page))))

(defn visit
  "Loads `path` in `page`, as typing it into the address bar does."
  [^Page page path]
  (.navigate page path))

(defn- true-within?
  "Whether the JavaScript expression `js` is true in `page`, or becomes true within `ms`
  milliseconds. It polls with evaluate: Playwright's waitForFunction compiles its
  expression in the page, which esopa's Content-Security-Policy refuses."
  [^Page page js ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (= true (.evaluate page js)) true
        (< deadline (System/currentTimeMillis)) false
        :else (do (.waitForTimeout page 50.0)
                  (recur))))))

(defn eventually?
  "Whether the JavaScript expression `js` is true in `page`, or becomes true within two seconds."
  [page js]
  (true-within? page js 2000))

(defn focused?
  "Whether the element matching `selector` has focus, or gets it within two seconds."
  [page selector]
  (eventually? page (str "document.activeElement?.matches(" (pr-str selector) ")")))

(defn mark-document!
  "Marks the document `page` shows, so same-document? can tell whether later pages
  were swapped into it or loaded."
  [^Page page]
  (.evaluate page "window.esopaMarked = true"))

(defn same-document?
  [^Page page]
  (= true (.evaluate page "window.esopaMarked === true")))

(defn- replacing-main
  "Runs `action`, which leads to another page, and waits until that page's main
  content has replaced this one's, whether htmx swapped it in or the browser loaded it."
  [^Page page action]
  (.evaluate page "document.querySelector('main').dataset.replaced = 'soon'")
  (action)
  (when-not (true-within? page "!document.querySelector('main[data-replaced]')" 5000)
    (throw (ex-info "The next page didn't replace this one" {:url (.url page)}))))

(defn click!
  "Clicks the link or button matching `selector`, and waits for the page it leads to."
  [^Page page selector]
  (replacing-main page #(.click page selector)))

(defn submit!
  "Fills in the form that posts to `action` with `fields`, a map from field name to
  value, submits it with its button, and waits for the page that comes back."
  [^Page page action fields]
  (doseq [[field value] fields]
    (.fill page (str "form[action='" action "'] [name='" (name field) "']") value))
  (click! page (str "form[action='" action "'] button")))

(defn back!
  "Goes back in `page`'s history, and waits for the previous page."
  [^Page page]
  (replacing-main page #(.goBack page)))

(defn wait-until
  "Lets Playwright handle events, like requests to route, until (f) is true."
  [^Page page f]
  (.waitForCondition page (reify BooleanSupplier
                            (getAsBoolean [_] (boolean (f))))))

(defn tab-to
  "Presses Tab until the element matching `selector` has focus, at most 20 times.
  Whether it got there."
  [^Page page selector]
  (loop [presses 0]
    (cond
      (= true (.evaluate page (str "document.activeElement?.matches(" (pr-str selector) ")"))) true
      (= 20 presses) false
      :else (do (.press (.keyboard page) "Tab")
                (recur (inc presses))))))

(defn fits-width?
  "Whether `page` shows without scrolling sideways."
  [^Page page]
  (= true (.evaluate page "document.documentElement.scrollWidth <= window.innerWidth")))

(defn last-link
  "The path of the link in the last email esopa sent."
  []
  (let [outbox (::service-test/outbox (:deps *esopa*))]
    (second (re-find #"http://esopa\.test(\S+)" (:body (last @outbox))))))

(defn accessibility-violations
  "The WCAG 2.2 level A and AA violations axe-core finds in `page`, each described with
  the elements it found it on."
  [^Page page]
  (for [^Rule rule (-> (AxeBuilder. page)
                       (.withTags ["wcag2a" "wcag2aa" "wcag21a" "wcag21aa" "wcag22aa"])
                       (.analyze)
                       (.getViolations))]
    (str (.getId rule) " (" (.getImpact rule) "): " (.getHelp rule) " On "
         (str/join ", " (map #(str (.getTarget ^Node %)) (.getNodes rule))))))
