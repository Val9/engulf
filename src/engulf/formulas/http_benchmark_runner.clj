(defn result
  [started-at ended-at]
  {:started-at started-at
   :ended-at ended-at
   :runtime (- ended-at started-at)})

(defn error-result
  [started-at ended-at throwable]
  (assoc (result started-at ended-at)
    :status (if (keyword? throwable)
                          throwable
                          (last (.split ^String (str (class throwable)) " ")))
    :throwable throwable))

(defn success-result
  [started-at ended-at status]
  (assoc (result started-at ended-at)
    :status status))

(def valid-methods #{:get :post :put :delete :patch})

(defn int-val [i] (Integer/valueOf i))

(defn markov-req-seq
  [{{:keys [corpus keep-alive?]} :target}]
  
  ;; TODO: Maybe consider having this just work...
  (when (> 2 (count corpus))
    (throw (Exception. (str "Markov corpus must contain at least 2 URLs. "
                            "Got: " (count corpus)))))

  (map #(assoc % :keep-alive? keep-alive?)
       (markov/corpus-chain corpus) ))

(defn fn-req-seq
  [forms-str]
  (letfn [(check-fn [val]
            (when (not (ifn? val))
              (throw (Exception.
                      (str  "User script '" val
                            "' did not return an fn!"))))
            val)
          (generator [forms]
            (let [cur-ns *ns*
                  script-ns (create-ns 'engulf.user-script-ns)]
              (try
                (in-ns (ns-name script-ns))
                (eval  '(clojure.core/refer 'clojure.core))
                (check-fn (eval forms))
                (finally
                 (in-ns (ns-name cur-ns))
                 (remove-ns (ns-name script-ns))))))
          (lazify [script-fn]
            (lazy-seq (cons (script-fn) (lazify script-fn))))]
    (lazify (generator (read-string forms-str)))))

(defn simple-req-seq
  [{target :target}]
  (letfn [(validate [refined]
            ;; Throw on bad URLs.
            (URL. (:url target))
            (when (not ((:method refined) valid-methods))
              (throw (Exception. (str "Invalid method: " (:method target) " "
                                      "expected one of " valid-methods))))
            refined)
          (refine [target]
            (validate
             (assoc target
              :method (keyword (lower-case (or (:method target) "get")))
              :timeout (or (:timeout target) 1000))))
          (lazify [req] (lazy-seq (cons req (lazify req))))]
    (lazify (refine target))))

;; TODO Clean this all up, it's a bit of a hairbal
(defn clean-job [{str-params :params node-count :node-count :as job}]
  (let [params (keywordize-keys str-params)]
    
    ;; Ensure required keys
    (let [diff (cset/difference #{:concurrency :limit} params)]
      (when (not (empty? diff))
        (throw (Exception. (str "Invalid parameters! Missing keys: " diff ". Got: " str-params)))))

    (when (> node-count (int-val (:concurrency params)))
      (throw (Exception. "Concurrency cannot be < node-count! Use a higher concurrency setting!")))

    (let [cast-params (-> params
                          (update-in [:concurrency] int-val)
                          (update-in [:target :timeout] #(when % (int-val %)))
                          (update-in [:limit] int-val)
                          (assoc :retry? true)
                          (assoc-in [:target :keep-alive?] (not= "false" (:keep-alive (:target params)))))
          seqd-params (assoc cast-params :req-seq
                             (if (= (:type (:target cast-params)) "markov")
                               (markov-req-seq cast-params)
                               (simple-req-seq cast-params)))]
          (assoc job :params seqd-params))))

(defn run-real-request
  [client req-params callback]
  (let [started-at (now)]
    (letfn
        [(succ-cb [response]
           (if (= :lamina/suspended response)
             (callback :lamina/suspended)
             (callback (success-result started-at
                                       (now)
                                       (or (:status response) "err")))))
         (enqueue-succ-cb [response]
           (.submit ^ExecutorService callbacks-pool ^Runnable (partial succ-cb response)))
         (error-cb [throwable]
           (log/warn throwable (str "Error executing HTTP Request: " req-params))
           (callback (error-result started-at (now) throwable)))
         (enqueue-error-cb [throwable]
           (.submit ^ExecutorService callbacks-pool ^Runnable (partial error-cb throwable)))
         (exec-request []
           (lc/on-realized (client req-params) enqueue-succ-cb enqueue-error-cb))]
      (try
        (exec-request)
        (catch Exception e
          (.submit ^ExecutorService callbacks-pool ^Runnable (partial error-cb e)))))))

(defn run-mock-request
  "Fake HTTP response for testing"
  [client params callback]
  (let [started-at (now)
        res (lc/result-channel)
        succ-cb #(lc/success res (success-result started-at (System/currentTimeMillis) 200))]
    (set-timeout 1 succ-cb)
    (lc/on-realized res #(callback %1) #(callback %1))))