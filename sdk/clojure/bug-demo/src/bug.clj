(ns bug
  (:require
   [jsonista.core :as j]
   [org.httpkit.server :as server]
   [starfederation.datastar.clojure.api :as d*]
   [starfederation.datastar.clojure.adapter.http-kit :refer [->sse-response on-open on-close]]
   [reitit.ring :as rr]
   [dev.onionpancakes.chassis.core :as h]
   [clojure.string :as str]))

(def datastar-beta9 "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-beta.9/bundles/datastar.js")
(def datastar-develop "https://cdn.jsdelivr.net/gh/starfederation/datastar@develop/bundles/datastar.js")
(def datastar-fix1 "https://cdn.jsdelivr.net/gh/starfederation/datastar@81caba5e681abf5e742bfaba9f4e8275094208c2/bundles/datastar.js")

(defn select-src [v]
  (condp = v
    "beta9"   datastar-beta9
    "81caba5" datastar-fix1
    datastar-develop))

(defn page [version body]
  (let [datastar-src (select-src version)]
    (h/html
     [[h/doctype-html5]
      [:html
       [:head
        [:style {:type "text/css"}
         "
#main {
    display: flex;
    justify-content: center;
    align-items: center;
    height: 100vh;
    margin: 0;
    padding: 0;
}
#my-btn {
    color: black;
    padding: 20px 40px;
    min-width: 12rem;
    font-size: 24px;
    transition: color 0.3s ease;
}
#my-btn.loading {
    color: white;
    background: red;
}
"]
        [:meta {:charset "UTF-8"}]
        [:script {:type "module" :crossorigin "anonymous" :src datastar-src}]]
       [:body
        [:div {:data-on-load         "@post('/updates')"
               :data-signals-version (j/write-value-as-string version)}]
        body]]])))

(defn bug-view [n]
  [:main {:id "main"}
   [:div {:style "margin-right: 4rem; max-width: 20rem;"}
    [:p "Steps to reproduce the bug:"]
    [:ol
     [:li "Load the page"]
     [:li "Click the Fetch button (it will turn red)"]
     [:li "Click the Re-Render button (the fetch button label will change)"]
     [:li "Press the Fetch button again (it will not turn red on d* develop, but it should)"]]
    [:p {:style "font-size: 0.8rem;"} "datastar version: " [:span {:style "font-family: monospace; " :data-text "$version"}] [:br]]
    [:div {:style "display: flex; gap: 10px;"}
     (map (fn [v]
            [:a {:data-attr-href (format  "'/?version=%s'" v)} v]) ["beta9" "develop" "81caba5"])]]

   [:div {:style " display: flex; flex-direction: column; justify-content:center;align-items:center;"}
    [:div {:style "max-width: 12rem;"}
     [:button {:id                      :my-btn
               :data-indicator-fetching true
               :data-class              "{loading: $fetching}"
               :data-on-click           "@post('/fetch')"}
      (str "Fetch" n)]
     [:p {:style "font-size: 0.8rem"} "Click the button to make a request which takes 3 seconds. The button will turn red while the request is in flight."]]
    [:div {:style "max-width: 12rem; margin-top: 4rem;"}
     [:button {:id            :rerenderr-btn
               :data-on-click "@post('/re-render')"}
      "Re-Render"]
     [:p {:style "font-size: 0.8rem"} "Click this button to re-render the view with a small tweak."]]]])

(defonce !conns (atom #{}))

(defn broadcast! [n]
  (doseq [conn @!conns]
    (try
      (d*/merge-fragment! conn  (h/html (bug-view n)))
      (d*/console-log! conn "re-rendered")
      (catch Exception e
        (println "Error: " e)))))

(defn long-connection [req]
  (->sse-response req
                  {on-open
                   (fn [sse]
                     (println "A client connected")
                     (swap! !conns conj sse)
                     (d*/console-log! sse "'connected'")
                     (broadcast! nil))
                   on-close
                   (fn on-close [sse status-code]
                     (swap! !conns disj sse)
                     (println "A client disconected, status: " status-code))}))

(defn parse-version [qs]
  (let [v (when qs
            (second (str/split qs #"=")))]
    (condp = v
      "beta9"   "beta9"
      "81caba5" "81caba5"
      "develop")))

(defn shim-handler [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (page (parse-version (:query-string req))
                  [:main {:id "main"} "Loading"])})

(def routes
  [""
   ;; Load the page skeleton
   ["/" {:get {:handler shim-handler}}]
   ;; Opens a long lived SSE connection and renders the bug-view on first conenct
   ["/updates" {:post {:handler long-connection}}]

   ;; Dummy endpoint that just sleeps for 3 seconds
   ["/fetch" {:post {:handler (fn [_]
                                (Thread/sleep 3000)
                                {:status 204})}}]
   ;; Re-render the bug-view async, returns nothing
   ["/re-render" {:post {:handler (fn [_]
                                    (broadcast! (rand-int 100))
                                    {:status 204})}}]])

(def router
  (rr/router routes))

(def default-handler (rr/create-default-handler))

(def handler
  (rr/ring-handler router
                   default-handler))

(defonce !hk-server (atom nil))

(defn reboot-hk-server! [handler]
  (swap! !hk-server
         (fn [server]
           (when server
             (server/server-stop! server))
           (server/run-server handler
                              {:port                 3002
                               :legacy-return-value? false}))))

(defn parse-port [args]
  (if-let [ep (System/getenv "DS_PORT")]
    (parse-long ep)
    (try
      (parse-long (first args))
      (catch Exception _
        3000))))

(defn -main [& args]
  (let [port (parse-port args)
        host (or (System/getenv "DS_HOST") "127.0.0.1")]
    (server/run-server handler
                       {:port                 port
                        :ip                   host
                        :legacy-return-value? false})
    (println "Server started. Go to" (format "http://%s:%s" host port))))

(comment
  ;; For REPL explorers

  ;; Start/Restart
  (reboot-hk-server! handler)

  ;; Force re-reder
  (broadcast! (rand-int 100))

  ;;
  )
