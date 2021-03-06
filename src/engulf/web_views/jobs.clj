(ns engulf.web-views.jobs
  (:use engulf.utils
        engulf.web-views.common
        noir.core
        lamina.core
        aleph.formats)
  (:require [engulf.job-manager :as jmgr]
            [noir-async.core :as na]
            [noir.request :as noir-req]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]))

(defn jsonify-results
  [results]
  (json/generate-string
   (assoc results "percentiles"
          (.toString ^Object (results "percentiles")))))

(defn async-stream
  [conn ch]
  (na/async-push conn {:status 200 :chunked true})
  (receive-all ch #(na/async-push conn (json-chunk %)))
  (on-closed ch #(na/close-connection conn)))

(defpage [:get "/jobs/:uuid"] {:keys [uuid]}
  (if-let [job (jmgr/find-job-by-uuid uuid)]
    (json-resp 200 job)
    (json-resp 404 {:message "Not found!"})))

(defpage [:delete "/jobs/:uuid"] {:keys [uuid]}
  (if-let [job (jmgr/find-job-by-uuid uuid)]
    (if (:ended-at job)
      (do (jmgr/delete-job-by-uuid uuid)
          (json-resp 200 {:message "Deleted"}))
      (json-resp 409 {:message "Could not delete, still running!"}))
    (json-resp 404 {:message "Not found!"})))

(defpage "/jobs" {:keys [page per-page]}
  (let [page (if page (Integer/valueOf page) 1)
        per-page (if per-page (Integer/valueOf per-page) 10)
        jobs (jmgr/paginated-jobs page per-page :desc)]
    (json-resp 200 jobs)))

(defpage [:get "/jobs/current"]  {}
  (if-let [job @jmgr/current-job]
    (json-resp 200 @jmgr/current-job)
    (json-resp 404 {:message "No current job!"})))

(na/defpage-async [:post "/jobs/current"] {} conn
  (try
    (let [{:keys [title notes params]}
          (walk/keywordize-keys (json/parse-string (na/request-body-str conn)))
          {:keys [results-ch job]}
          (jmgr/start-job title notes params)]
      (if (= (:_stream params) "true")
        (async-stream conn results-ch)
        (na/async-push conn (json-resp 200 job))))
    (catch Exception e
      (log/warn e "Error starting job!")
      (na/async-push conn (json-resp 400 {:message (str e)})))))

(defpage [:delete "/jobs/current"] {}
  (if-let [stopped (jmgr/stop-job)]
    (on-realized stopped
                 #(json-resp 200 %)
                 #(json-resp 500 %))
    (json-resp 404 {})))