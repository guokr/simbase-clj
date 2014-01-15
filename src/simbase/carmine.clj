(ns simbase.carmine "Clojure Redis client & message queue."
  {:author "Peter Taoussanis"}
  (:refer-clojure :exclude [time get set key keys type sync sort eval])
  (:require [clojure.string :as str]
            [simbase.commands :as commands]
            [taoensso.carmine
             (utils       :as utils)
             (protocol    :as protocol)
             (connections :as conns)]
            [taoensso.timbre      :as timbre]
            [taoensso.nippy.tools :as nippy-tools]))

;;;; Connections

(utils/defalias with-replies protocol/with-replies)

(defmacro wcar
  "Evaluates body in the context of a thread-bound pooled connection to Redis
  server. Sends Redis commands to server as pipeline and returns the server's
  response. Releases connection back to pool when done.

  `conn` arg is a map with connection pool and spec options:
    {:pool {} :spec {:host \"127.0.0.1\" :port 6379}} ; Default
    {:pool {} :spec {:uri \"redis://redistogo:pass@panga.redistogo.com:9475/\"}}
    {:pool {} :spec {:host \"127.0.0.1\" :port 6379
                     :password \"secret\"
                     :timeout-ms 6000
                     :db 3}}

  A `nil` or `{}` `conn` or opts will use defaults. A `:none` pool can be used
  to skip connection pooling. For other pool options, Ref. http://goo.gl/EiTbn."
  {:arglists '([conn :as-pipeline & body] [conn & body])}
  [conn & sigs]
  `(let [{pool-opts# :pool spec-opts# :spec} ~conn
         [pool# conn#] (conns/pooled-conn pool-opts# spec-opts#)]
     (try
       (let [response# (protocol/with-context conn#
                         (with-replies ~@sigs))]
         (conns/release-conn pool# conn#)
         response#)
       (catch Exception e# (conns/release-conn pool# conn# e#) (throw e#)))))

(comment
  (wcar {} (ping) "not-a-Redis-command" (ping))
  (with-open [p (conns/conn-pool {})] (wcar {:pool p} (ping) (ping)))
  (wcar {} (ping))
  (wcar {} :as-pipeline (ping))

  (wcar {} (echo 1) (println (with-replies (ping))) (echo 2))
  (wcar {} (echo 1) (println (with-replies :as-pipeline (ping))) (echo 2)))

;;;; Misc

;;; (number? x) for Carmine < v0.11.x backwards compatiblility
(defn as-long   [x] (when x (if (number? x) (long   x) (Long/parseLong     x))))
(defn as-double [x] (when x (if (number? x) (double x) (Double/parseDouble x))))
(defn as-bool   [x] (when x
                      (cond (or (true? x) (false? x))            x
                            (or (= x "false") (= x "0") (= x 0)) false
                            (or (= x "true")  (= x "1") (= x 1)) true
                            :else
                            (throw (Exception. (str "Couldn't coerce as bool: "
                                                    x))))))

(utils/defalias parse       protocol/parse)
(utils/defalias parser-comp protocol/parser-comp)

(defmacro parse-long    [& body] `(parse as-long   ~@body))
(defmacro parse-double  [& body] `(parse as-double ~@body))
(defmacro parse-bool    [& body] `(parse as-bool   ~@body))
(defmacro parse-keyword [& body] `(parse keyword   ~@body))
(defmacro parse-raw     [& body] `(parse (with-meta identity {:raw? true}) ~@body))
(defmacro parse-nippy [thaw-opts & body]
  `(parse (with-meta identity {:thaw-opts ~thaw-opts}) ~@body))

(defn as-map [coll & [kf vf]]
  {:pre  [(coll? coll) (or (nil? kf) (fn? kf) (identical? kf :keywordize))
                       (or (nil? vf) (fn? vf))]
   :post [(or (nil? %) (map? %))]}
  (when-let [s' (seq coll)]
    (let [kf (if-not (identical? kf :keywordize) kf
                     (fn [k _] (keyword k)))]
      (loop [m (transient {}) [k v :as s] s']
        (let [k (if-not kf k (kf k v))
              v (if-not vf v (vf k v))
              new-m (assoc! m k v)]
          (if-let [n (nnext s)]
            (recur new-m n)
            (persistent! new-m)))))))

(comment (as-map ["a" "A" "b" "B" "c" "C"] :keywordize
           (fn [k v] (case k (:a :b) (str "boo-" v) v))))

(defmacro parse-map [form & [kf vf]] `(parse #(as-map % ~kf ~vf) ~form))

(defn key
  "Joins parts to form an idiomatic compound Redis key name. Suggested style:
    * \"category:subcategory:id:field\" basic form.
    * Singular category names (\"account\" rather than \"accounts\").
    * Plural _field_ names when appropriate (\"account:friends\").
    * Dashes for long names (\"email-address\" rather than \"emailAddress\", etc.)."
  [& parts] (str/join ":" (mapv #(if (keyword? %) (utils/fq-name %) (str %))
                                parts)))

(comment (key :foo/bar :baz "qux" nil 10))

(utils/defalias raw            protocol/raw)
(utils/defalias with-thaw-opts nippy-tools/with-thaw-opts)
(utils/defalias freeze         nippy-tools/wrap-for-freezing
  "Forces argument of any type (incl. keywords, simple numbers, and binary types)
  to be subject to automatic de/serialization with Nippy.")

(utils/defalias return protocol/return)
(comment (wcar {} (return :foo) (ping) (return :bar))
         (wcar {} (parse name (return :foo)) (ping) (return :bar)))

;;;; Standard commands

(commands/defcommands) ; This kicks ass - big thanks to Andreas Bielk!

;;;; Helper commands

(defn redis-call
  "Sends low-level requests to Redis. Useful for DSLs, certain kinds of command
  composition, and for executing commands that haven't yet been added to the
  official `commands.json` spec.

  (redis-call [:set \"foo\" \"bar\"] [:get \"foo\"])"
  [& requests]
  (doseq [[cmd & args] requests]
    (let [cmd-parts (-> cmd name str/upper-case (str/split #"-"))]
      (protocol/send-request (into (vec cmd-parts) args)))))

(comment (wcar {} (redis-call [:set "foo" "bar"] [:get "foo"]
                              [:config-get "*max-*-entries*"])))

(defmacro atomic
  "Alpha - subject to change!!
  Tool to ease Redis transactions for fun & profit. Wraps body in a `wcar`
  call that terminates with `exec`, cleans up reply, and supports automatic
  retry for failed optimistic locking.

  Body must contain a `multi` call and may contain calls to: `watch`, `unwatch`,
  `discard`, etc. Ref. http://redis.io/topics/transactions for more info.

  `return` and `parse` NOT supported after `multi` has been called.

  Like `swap!` fn, body may be called multiple times so should avoid impure or
  expensive ops.

  ;;; Atomically increment integer key without using INCR
  (atomic {} 100 ; Retry <= 100 times on failed optimistic lock, or throw ex

    (watch  :my-int-key) ; Watch key for changes
    (let [curr-val (-> (wcar {} ; Note additional connection!
                         (get :my-int-key)) ; Use val of watched key
                       (as-long)
                       (or 0))]
      (return curr-val)

      (multi) ; Start the transaction
        (set :my-int-key (inc curr-val))
        (get :my-int-key)
      ))
  => [[\"OK\" nil \"OK\" \"QUEUED\" \"QUEUED\"] ; Prelude replies
      [\"OK\" \"1\"] ; Transaction replies (`exec` reply)
      ]

  See also `lua` as an alternative method of achieving transactional behaviour."
  [conn max-cas-attempts & body]
  (assert (>= max-cas-attempts 1))
  `(let [conn#       ~conn
         max-idx#    ~max-cas-attempts
         prelude-result# (atom nil)
         exec-result#
         (wcar :as-pipeline ; To ensure `peek` is always valid
           conn# ; Hold 1 conn for all attempts
           (loop [idx# 1]
             (try (do ~@body)
                  (catch Exception e#
                    (discard) ; Always return conn to normal state
                    (throw e#)))
             (reset! prelude-result# (protocol/get-parsed-replies :as-pipeline))
             (let [r# (->> (with-replies (exec))
                           (parse nil) ; Nb
                           )]
               (if (not= r# []) ; => empty `mutli` or watched key changed
                 (return r#)
                 (if (= idx# max-idx#)
                   (throw (Exception. (format "`atomic` failed after %s attempt(s)"
                                              idx#)))
                   (recur (inc idx#)))))))]

     [@prelude-result#
      ;; Mimic normal `get-parsed-replies` behaviour here re: vectorized replies:
      (let [r# exec-result#]
        (if (next r#) r#
            (let [r# (nth r# 0)]
              (if (instance? Exception r#) (throw r#) r#))))]))

(comment
  ;; Error before exec (=> syntax, etc.)
  (wcar {} (multi) (redis-call [:invalid]) (ping) (exec))
  ;; ["OK" #<Exception: ERR unknown command 'INVALID'> "QUEUED"
  ;;  #<Exception: EXECABORT Transaction discarded because of previous errors.>]

  ;; Error during exec (=> datatype, etc.)
  (wcar {} (set "aa" "string") (multi) (ping) (incr "aa") (ping) (exec))
  ;; ["OK" "OK" "QUEUED" "QUEUED" "QUEUED"
  ;;  ["PONG" #<Exception: ERR value is not an integer or out of range> "PONG"]]

  (wcar {} (multi) (ping) (discard)) ; ["OK" "QUEUED" "OK"]
  (wcar {} (multi) (ping) (discard) (exec))
  ;; ["OK" "QUEUED" "OK" #<Exception: ERR EXEC without MULTI>]

  (wcar {} (watch "aa") (set "aa" "string") (multi) (ping) (exec))
  ;; ["OK" "OK" "OK" "QUEUED" []]

  (wcar {} (watch "aa") (set "aa" "string") (unwatch) (multi) (ping) (exec))
  ;; ["OK" "OK" "OK" "OK" "QUEUED" ["PONG"]]

  (wcar {} (multi) (multi))
  ;; ["OK" #<Exception java.lang.Exception: ERR MULTI calls can not be nested>]
  )